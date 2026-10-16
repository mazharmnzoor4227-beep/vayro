package com.vayro.app.workflow

import com.vayro.app.agent.AutonomousLoopEngine
import com.vayro.app.core.result.OperationResult
import com.vayro.app.core.result.OperationStatus
import com.vayro.app.security.FileSafetyAdapter
import com.vayro.app.security.GitOpKind
import com.vayro.app.security.GitSafetyAdapter
import com.vayro.app.security.SafetyDeniedException
import com.vayro.app.security.SafetyPromptRequiredException
import com.vayro.app.security.ShellSafetyAdapter
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * M6: step executors.
 *
 * Every side-effecting step executes through the M5 safety gateway adapters:
 * file reads/writes via [FileSafetyAdapter], shell commands via
 * [ShellSafetyAdapter], Git operations via [GitSafetyAdapter]. A DENY from the
 * gateway throws [SafetyDeniedException] out of the executor before any
 * effect runs — the plan executor turns it into an honest FAILED step that is
 * never auto-repaired. A PROMPT throws [SafetyPromptRequiredException]:
 * "do not execute yet", surfaced for a user grant, never retried blindly.
 */

data class WorkflowContext(
    val runId: String,
    val workspaceRoot: File,
    val projectId: String? = null,
    val sessionId: String? = null,
    val fileAdapter: FileSafetyAdapter,
    val shellAdapter: ShellSafetyAdapter,
    val gitAdapter: GitSafetyAdapter,
    val checkpointStore: CheckpointStore,
)

interface StepExecutor {
    val kind: PlanStepKind
    suspend fun execute(step: PlanStep, ctx: WorkflowContext): OperationResult
}

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

fun CommandResult.toOperationResult(verificationNote: String): OperationResult {
    val logs = (stdout.lines() + stderr.lines()).filter { it.isNotBlank() }.takeLast(60)
    return if (exitCode == 0) {
        OperationResult.success(
            exitCode = 0,
            logs = logs,
            verificationEvidence = verificationNote,
        )
    } else {
        OperationResult.failure(
            error = "Command exited with code $exitCode",
            exitCode = exitCode,
            logs = logs,
        )
    }
}

/** Runs real OS commands. Injectable so tests can fake execution. */
interface CommandRunner {
    fun run(command: String, workingDir: File): CommandResult
}

/** Real implementation: executes through `sh -c` with a bounded wait. */
class ProcessCommandRunner(
    private val timeoutMinutes: Long = 10,
) : CommandRunner {
    override fun run(command: String, workingDir: File): CommandResult {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .directory(workingDir)
                .redirectErrorStream(false)
                .start()
            val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(-1, "", "Command timed out after $timeoutMinutes minutes: $command")
            }
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            CommandResult(process.exitValue(), stdout, stderr)
        } catch (e: Exception) {
            CommandResult(-1, "", "Failed to launch command: ${e.message}")
        }
    }
}

/** Runs real Git commands. Injectable so tests can fake execution. */
interface GitRunner {
    fun run(args: List<String>, repoDir: File): CommandResult
}

/** Real implementation: delegates to the `git` binary. */
class ProcessGitRunner(
    private val commandRunner: CommandRunner = ProcessCommandRunner(),
) : GitRunner {
    override fun run(args: List<String>, repoDir: File): CommandResult {
        // Quote each arg for the shell to avoid injection through message text.
        val quoted = args.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
        return commandRunner.run("git $quoted", repoDir)
    }
}

/**
 * Maps a step target to a real shell command. The aliases "build" and "test"
 * resolve through [AutonomousLoopEngine.detectBuildTool] so the command is
 * real for the project at hand; anything else is treated as a literal command.
 */
interface CommandResolver {
    fun resolve(step: PlanStep, workspaceRoot: File): String?
}

class DefaultCommandResolver : CommandResolver {
    override fun resolve(step: PlanStep, workspaceRoot: File): String? {
        val target = step.target.trim()
        if (target.isEmpty()) return null
        if (target == "build" || target == "test") {
            return AutonomousLoopEngine.detectBuildTool(workspaceRoot)?.verificationCommand
        }
        return target
    }
}

/** ANALYZE: reads the listed files through the gateway (read-only). */
class AnalyzeStepExecutor : StepExecutor {
    override val kind: PlanStepKind = PlanStepKind.ANALYZE

    override suspend fun execute(step: PlanStep, ctx: WorkflowContext): OperationResult {
        val paths = step.target.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) {
            return OperationResult.success(
                verificationEvidence = "Analyze step '${step.id}': no files listed, nothing read",
            )
        }
        val read = mutableListOf<String>()
        return try {
            paths.forEach { rel ->
                ctx.fileAdapter.read(rel, ctx.sessionId, ctx.projectId) {
                    val f = File(ctx.workspaceRoot, rel)
                    read.add("$rel (${if (f.exists()) f.length() else 0} bytes)")
                }
            }
            OperationResult.success(
                verificationEvidence = "Analyzed ${read.size} file(s): ${read.joinToString()}",
                logs = read,
            )
        } catch (e: SafetyDeniedException) {
            throw e // never converted, never repaired: a DENY must abort, not retry
        } catch (e: SafetyPromptRequiredException) {
            throw e // PROMPT means "do not execute yet": surfaced, not retried blindly
        } catch (e: Exception) {
            OperationResult.failure("Analyze step '${step.id}' failed: ${e.message}")
        }
    }
}

/**
 * IMPLEMENT: delegates to the injected agent executor. A SIMULATED agent
 * result is refused outright (M0 integrity rule): demo output must never
 * become a real change.
 */
class ImplementStepExecutor(
    private val agentExecutor: suspend (prompt: String) -> OperationResult,
) : StepExecutor {
    override val kind: PlanStepKind = PlanStepKind.IMPLEMENT

    override suspend fun execute(step: PlanStep, ctx: WorkflowContext): OperationResult {
        if (step.target.isBlank()) {
            return OperationResult.failure("Implement step '${step.id}' has no prompt (target is blank)")
        }
        return try {
            val result = agentExecutor(step.target)
            if (result.status == OperationStatus.SIMULATED) {
                OperationResult.failure(
                    "Refused SIMULATED agent result for implement step '${step.id}': " +
                        "demo output must never become a real change"
                )
            } else {
                result
            }
        } catch (e: Exception) {
            OperationResult.failure("Agent executor failed for step '${step.id}': ${e.message}")
        }
    }
}

/**
 * WRITE_FILE: checkpoint-before-write, then writes through the gateway.
 * The checkpoint lets [CheckpointStore.rollback] unwind the change if the
 * run later fails.
 */
class WriteFileStepExecutor : StepExecutor {
    override val kind: PlanStepKind = PlanStepKind.WRITE_FILE

    override suspend fun execute(step: PlanStep, ctx: WorkflowContext): OperationResult {
        val rel = step.target.trim()
        if (rel.isEmpty()) {
            return OperationResult.failure("Write step '${step.id}' has no target path")
        }
        return try {
            ctx.checkpointStore.snapshot(ctx.runId, ctx.workspaceRoot, listOf(rel))
            ctx.fileAdapter.write(rel, ctx.sessionId, ctx.projectId) {
                val f = File(ctx.workspaceRoot, rel)
                f.parentFile?.mkdirs()
                f.writeText(step.payload)
            }
            val bytes = step.payload.toByteArray().size
            OperationResult.success(
                verificationEvidence = "Wrote $bytes byte(s) to $rel (checkpointed before write)",
                logs = listOf("Wrote $rel"),
            )
        } catch (e: SafetyDeniedException) {
            throw e // never converted, never repaired: a DENY must abort, not retry
        } catch (e: SafetyPromptRequiredException) {
            throw e // PROMPT means "do not execute yet": surfaced, not retried blindly
        } catch (e: Exception) {
            OperationResult.failure("Write step '${step.id}' failed: ${e.message}")
        }
    }
}

/** RUN_COMMAND / RUN_TESTS: shell execution through the gateway with real exit codes. */
class CommandStepExecutor(
    override val kind: PlanStepKind,
    private val commandRunner: CommandRunner = ProcessCommandRunner(),
    private val resolver: CommandResolver = DefaultCommandResolver(),
) : StepExecutor {

    init {
        require(kind == PlanStepKind.RUN_COMMAND || kind == PlanStepKind.RUN_TESTS) {
            "CommandStepExecutor only handles RUN_COMMAND/RUN_TESTS, got $kind"
        }
    }

    override suspend fun execute(step: PlanStep, ctx: WorkflowContext): OperationResult {
        val command = resolver.resolve(step, ctx.workspaceRoot)
            ?: return OperationResult.failure(
                "Step '${step.id}' resolved to no command: " +
                    "set a literal command or use the 'build'/'test' alias in a project " +
                    "with a detectable toolchain"
            )
        return try {
            val cmdResult = ctx.shellAdapter.exec(command, ctx.workspaceRoot.path, ctx.sessionId, ctx.projectId) {
                commandRunner.run(command, ctx.workspaceRoot)
            }
            cmdResult.toOperationResult(
                "Command exit=${cmdResult.exitCode}: $command"
            )
        } catch (e: SafetyDeniedException) {
            throw e // never converted, never repaired: a DENY must abort, not retry
        } catch (e: SafetyPromptRequiredException) {
            throw e // PROMPT means "do not execute yet": surfaced, not retried blindly
        }
    }
}

/** GIT_COMMIT / GIT_PUSH: Git operations through the gateway. */
class GitStepExecutor(
    override val kind: PlanStepKind,
    private val gitRunner: GitRunner = ProcessGitRunner(),
) : StepExecutor {

    init {
        require(kind == PlanStepKind.GIT_COMMIT || kind == PlanStepKind.GIT_PUSH) {
            "GitStepExecutor only handles GIT_COMMIT/GIT_PUSH, got $kind"
        }
    }

    override suspend fun execute(step: PlanStep, ctx: WorkflowContext): OperationResult {
        val repoPath = ctx.workspaceRoot.path
        return try {
            when (kind) {
                PlanStepKind.GIT_COMMIT -> {
                    val message = step.target.ifBlank { step.title }
                    val cmdResult = ctx.gitAdapter.git(GitOpKind.COMMIT, repoPath, null, ctx.sessionId, ctx.projectId) {
                        gitRunner.run(listOf("commit", "-m", message), ctx.workspaceRoot)
                    }
                    cmdResult.toOperationResult("git commit exit=${cmdResult.exitCode}")
                }
                PlanStepKind.GIT_PUSH -> {
                    val remote = step.target.ifBlank { "origin" }
                    val cmdResult = ctx.gitAdapter.git(GitOpKind.PUSH, repoPath, remote, ctx.sessionId, ctx.projectId) {
                        gitRunner.run(listOf("push", remote, "HEAD"), ctx.workspaceRoot)
                    }
                    cmdResult.toOperationResult("git push exit=${cmdResult.exitCode}")
                }
                else -> OperationResult.failure("Unsupported git step kind $kind")
            }
        } catch (e: SafetyDeniedException) {
            throw e // never converted, never repaired: a DENY must abort, not retry
        } catch (e: SafetyPromptRequiredException) {
            throw e // PROMPT means "do not execute yet": surfaced, not retried blindly
        }
    }
}
