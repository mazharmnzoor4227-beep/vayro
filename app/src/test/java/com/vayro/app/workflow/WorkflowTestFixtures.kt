package com.vayro.app.workflow

import com.vayro.app.core.result.OperationResult
import com.vayro.app.security.FileSafetyAdapter
import com.vayro.app.security.GitSafetyAdapter
import com.vayro.app.security.PermissionCategory
import com.vayro.app.security.PermissionLevel
import com.vayro.app.security.PermissionPolicyEngine
import com.vayro.app.security.SafetyGateway
import com.vayro.app.security.ShellSafetyAdapter
import java.io.File

/**
 * M6 test fixtures: fake executors/runners and a real safety-gateway-backed
 * [WorkflowContext] builder. Every fake records its invocations so tests can
 * assert what did (and did not) execute.
 */

class FakeStepExecutor(
    override val kind: PlanStepKind,
    private val behavior: suspend (PlanStep, WorkflowContext) -> OperationResult,
) : StepExecutor {
    val calls = mutableListOf<PlanStep>()
    override suspend fun execute(step: PlanStep, ctx: WorkflowContext): OperationResult {
        calls.add(step)
        return behavior(step, ctx)
    }
}

class FakeCommandRunner(
    var result: CommandResult = CommandResult(0, "ok", ""),
) : CommandRunner {
    val commands = mutableListOf<String>()
    override fun run(command: String, workingDir: File): CommandResult {
        commands.add(command)
        return result
    }
}

class FakeGitRunner(
    var result: CommandResult = CommandResult(0, "ok", ""),
) : GitRunner {
    val invocations = mutableListOf<List<String>>()
    override fun run(args: List<String>, repoDir: File): CommandResult {
        invocations.add(args)
        return result
    }
}

class WorkflowTestHarness(
    val workspaceRoot: File,
    val policyDir: File,
    val checkpointBase: File,
) {
    val policyEngine = PermissionPolicyEngine(policyDir = policyDir)
    val gateway = SafetyGateway(policyEngine = policyEngine, workspaceRoot = workspaceRoot)
    val checkpointStore = CheckpointStore(checkpointBase)

    fun allowGitPush() {
        policyEngine.grantPermission(PermissionCategory.GIT_PUSH, PermissionLevel.ALWAYS)
    }

    fun denyFileWrites() {
        policyEngine.grantPermission(PermissionCategory.FILE_WRITE, PermissionLevel.DENY)
    }

    fun context(runId: String): WorkflowContext = WorkflowContext(
        runId = runId,
        workspaceRoot = workspaceRoot,
        fileAdapter = FileSafetyAdapter(gateway),
        shellAdapter = ShellSafetyAdapter(gateway),
        gitAdapter = GitSafetyAdapter(gateway),
        checkpointStore = checkpointStore,
    )

    /** Real executors wired to fakes — the integration path used by runner tests. */
    fun realExecutors(
        agentExecutor: suspend (String) -> OperationResult = { OperationResult.success() },
        commandRunner: CommandRunner = FakeCommandRunner(),
        gitRunner: GitRunner = FakeGitRunner(),
    ): Map<PlanStepKind, StepExecutor> = mapOf(
        PlanStepKind.ANALYZE to AnalyzeStepExecutor(),
        PlanStepKind.IMPLEMENT to ImplementStepExecutor(agentExecutor),
        PlanStepKind.WRITE_FILE to WriteFileStepExecutor(),
        PlanStepKind.RUN_COMMAND to CommandStepExecutor(PlanStepKind.RUN_COMMAND, commandRunner),
        PlanStepKind.RUN_TESTS to CommandStepExecutor(PlanStepKind.RUN_TESTS, commandRunner),
        PlanStepKind.GIT_COMMIT to GitStepExecutor(PlanStepKind.GIT_COMMIT, gitRunner),
        PlanStepKind.GIT_PUSH to GitStepExecutor(PlanStepKind.GIT_PUSH, gitRunner),
    )
}
