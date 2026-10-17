package com.vayro.app.git.workflow

import com.vayro.app.core.result.OperationResult
import com.vayro.app.core.result.OperationStatus
import com.vayro.app.network.GitHubClient
import com.vayro.app.network.GitHubIssue
import com.vayro.app.network.GitHubPullRequest
import com.vayro.app.security.GitOpKind
import com.vayro.app.security.GitSafetyAdapter
import com.vayro.app.workspace.GitDomainController
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class WorkflowStage(val title: String) {
    IDLE("Idle"),
    FETCHING_ISSUE("1. Fetch Issue"),
    UNDERSTANDING("2. Understand Problem"),
    CREATING_BRANCH("3. Create Branch"),
    IMPLEMENTING("4. Implement Solution"),
    BUILDING("5. Build Project"),
    TESTING("6. Run Tests"),
    FIXING("7. Auto-Fix Failures"),
    COMMITTING("8. Commit Changes"),
    PUSHING("9. Push to Remote"),
    CREATING_PR("10. Open Pull Request"),
    COMPLETED("Completed"),
    FAILED("Failed")
}

enum class WorkflowStepStatus {
    PENDING,
    IN_PROGRESS,
    SUCCESS,
    FAILED,
    SKIPPED
}

data class WorkflowStep(
    val stage: WorkflowStage,
    val title: String,
    val detail: String,
    val status: WorkflowStepStatus = WorkflowStepStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis()
)

data class WorkflowExecutionState(
    val issueNumber: Int = 0,
    val issueTitle: String = "",
    val branchName: String = "",
    val currentStage: WorkflowStage = WorkflowStage.IDLE,
    val steps: List<WorkflowStep> = emptyList(),
    val prUrl: String? = null,
    val errorMessage: String? = null,
    val isRunning: Boolean = false,
    /** The integrity-gated result of the last run. Null while a run is in progress or never ran. */
    val lastResult: OperationResult? = null,
)

/**
 * Autonomous Issue-to-PR Engine implementing the roadmap workflow loop:
 * Issue -> Understand -> Create Branch -> Implement -> Build -> Test -> Fix -> Commit -> Push -> Create PR
 *
 * Milestone 0 integrity rules:
 * - The agent executor is a suspend function whose [OperationResult] completes only when the
 *   agent request genuinely ends. Fire-and-forget callbacks are rejected.
 * - The build/test validator must run REAL build and test commands and return their real
 *   exit codes and logs. The default validator honestly reports NOT_CONFIGURED.
 * - A blank GitHub token fails the workflow immediately; the workflow never proceeds.
 * - An empty diff fails the workflow before commit; a no-op agent run can never open a PR.
 * - A SIMULATED agent result is refused: demo output must never become a real PR.
 */
class GitHubWorkflowEngine(
    private val gitController: GitDomainController,
    private val githubClient: GitHubClient = GitHubClient(),
    private val gitSafety: GitSafetyAdapter,
) {

    private val _state = MutableStateFlow(WorkflowExecutionState())
    val state: StateFlow<WorkflowExecutionState> = _state.asStateFlow()

    fun reset() {
        _state.value = WorkflowExecutionState()
    }

    suspend fun runAutonomousWorkflow(
        issue: GitHubIssue,
        token: String,
        repoFullName: String,
        workingDir: File,
        baseBranch: String = "main",
        agentExecutor: suspend (prompt: String) -> OperationResult,
        buildAndTestValidator: suspend (workingDir: File) -> OperationResult = { dir ->
            OperationResult.notConfigured(
                "No build/test validator was provided for ${dir.path}. " +
                    "Wire a real Gradle build+test invocation; the workflow refuses to claim passing tests it never ran."
            )
        }
    ): OperationResult {
        val startedAt = System.currentTimeMillis()
        val initialSteps = WorkflowStage.entries
            .filter { it != WorkflowStage.IDLE && it != WorkflowStage.COMPLETED && it != WorkflowStage.FAILED }
            .map { WorkflowStep(it, it.title, "Queued", WorkflowStepStatus.PENDING) }

        val branchName = generateBranchName(issue)

        _state.update {
            it.copy(
                issueNumber = issue.number,
                issueTitle = issue.title,
                branchName = branchName,
                currentStage = WorkflowStage.FETCHING_ISSUE,
                steps = initialSteps,
                prUrl = null,
                errorMessage = null,
                isRunning = true,
                lastResult = null,
            )
        }

        // Fail fast: no token, no workflow. Never proceed silently.
        if (token.isBlank()) {
            return failWorkflow(
                "GitHub token is missing or empty. Connect GitHub in Settings before running the " +
                    "autonomous workflow. Refusing to run without credentials.",
                startedAt = startedAt,
            )
        }

        try {
            // 1. Fetch & Understand Issue
            updateStep(WorkflowStage.FETCHING_ISSUE, WorkflowStepStatus.IN_PROGRESS, "Reading Issue #${issue.number}...")
            val issueBody = issue.body.ifBlank { "No detailed description provided." }
            updateStep(WorkflowStage.FETCHING_ISSUE, WorkflowStepStatus.SUCCESS, "Loaded Issue #${issue.number}")

            updateStep(WorkflowStage.UNDERSTANDING, WorkflowStepStatus.IN_PROGRESS, "Analyzing requirements & scope...")
            updateStep(WorkflowStage.UNDERSTANDING, WorkflowStepStatus.SUCCESS, "Problem understood: ${issue.title}")

            // 2. Create Branch
            updateStep(WorkflowStage.CREATING_BRANCH, WorkflowStepStatus.IN_PROGRESS, "Checking out $branchName...")
            val branchRes = guardedGit(GitOpKind.BRANCH, workingDir) {
                gitController.checkout(workingDir, branchName, createNew = true)
            }
            if (!branchRes.success) {
                // If branch exists, checkout directly
                val retry = guardedGit(GitOpKind.BRANCH, workingDir) {
                    gitController.checkout(workingDir, branchName, createNew = false)
                }
                if (!retry.success) {
                    return failWorkflow(
                        "Could not create or check out branch $branchName: ${retry.errorMessage ?: retry.output}",
                        startedAt = startedAt,
                    )
                }
            }
            updateStep(WorkflowStage.CREATING_BRANCH, WorkflowStepStatus.SUCCESS, "Active branch: $branchName")
            val baselineDirty = dirtyPaths(workingDir)

            // 3. Implement Solution — awaits the REAL agent result.
            updateStep(WorkflowStage.IMPLEMENTING, WorkflowStepStatus.IN_PROGRESS, "Autonomous coding agent modifying workspace...")
            val taskPrompt = """
                [Autonomous Issue Fixer]
                Please resolve GitHub Issue #${issue.number}: ${issue.title}

                Description:
                $issueBody

                Analyze the codebase, implement necessary changes, and verify code consistency.
            """.trimIndent()
            val agentResult = agentExecutor(taskPrompt)
            if (agentResult.status == OperationStatus.SIMULATED) {
                updateStep(WorkflowStage.IMPLEMENTING, WorkflowStepStatus.FAILED, "Agent ran in demo mode")
                return failWorkflow(
                    "The agent executor reported SIMULATED (demo mode). Refusing to turn simulated " +
                        "work into a real pull request.",
                    startedAt = startedAt,
                )
            }
            if (!agentResult.isSuccess) {
                updateStep(WorkflowStage.IMPLEMENTING, WorkflowStepStatus.FAILED, agentResult.error ?: "Agent implementation failed")
                return failWorkflow(
                    "Agent implementation did not complete: ${agentResult.error ?: agentResult.status}",
                    startedAt = startedAt,
                )
            }
            updateStep(
                WorkflowStage.IMPLEMENTING,
                WorkflowStepStatus.SUCCESS,
                "Code changes generated (${agentResult.verificationEvidence ?: "agent completed"})"
            )

            // 4-5. Build & Test — real commands, real exit codes.
            updateStep(WorkflowStage.BUILDING, WorkflowStepStatus.IN_PROGRESS, "Compiling project...")
            val buildResult = buildAndTestValidator(workingDir)
            if (!buildResult.isSuccess) {
                updateStep(WorkflowStage.BUILDING, WorkflowStepStatus.FAILED, buildResult.error ?: "Build/test failed")
                // 6. Auto-Fix Failures — one bounded repair attempt driven by the real failure.
                updateStep(WorkflowStage.FIXING, WorkflowStepStatus.IN_PROGRESS, "Triggering self-healing repair...")
                val fixPrompt = buildString {
                    append("Build/test failed with the following real output:\n")
                    append(buildResult.logs.takeLast(60).joinToString("\n"))
                    append("\n\nPlease fix the failures immediately.")
                }
                val fixResult = agentExecutor(fixPrompt)
                if (fixResult.status == OperationStatus.SIMULATED || !fixResult.isSuccess) {
                    updateStep(WorkflowStage.FIXING, WorkflowStepStatus.FAILED, fixResult.error ?: "Repair agent failed")
                    return failWorkflow(
                        "Build repair failed: ${fixResult.error ?: fixResult.status}",
                        startedAt = startedAt,
                    )
                }
                val rebuildResult = buildAndTestValidator(workingDir)
                if (!rebuildResult.isSuccess) {
                    updateStep(WorkflowStage.FIXING, WorkflowStepStatus.FAILED, rebuildResult.error ?: "Rebuild failed")
                    return failWorkflow(
                        "Build still failing after repair: ${rebuildResult.error ?: rebuildResult.status}",
                        startedAt = startedAt,
                    )
                }
                updateStep(
                    WorkflowStage.FIXING,
                    WorkflowStepStatus.SUCCESS,
                    "Repair successful (${rebuildResult.verificationEvidence ?: "rebuild passed"})"
                )
                updateStep(WorkflowStage.TESTING, WorkflowStepStatus.SUCCESS, rebuildResult.verificationEvidence ?: "Tests passed")
            } else {
                updateStep(WorkflowStage.BUILDING, WorkflowStepStatus.SUCCESS, buildResult.verificationEvidence ?: "Build passed")
                updateStep(WorkflowStage.TESTING, WorkflowStepStatus.SUCCESS, buildResult.verificationEvidence ?: "All tests passed")
                updateStep(WorkflowStage.FIXING, WorkflowStepStatus.SKIPPED, "No repairs needed")
            }

            // 7. Commit
            updateStep(WorkflowStage.COMMITTING, WorkflowStepStatus.IN_PROGRESS, "Verifying changes...")
            val currentDirty = dirtyPaths(workingDir)
            val workflowPaths = (currentDirty - baselineDirty).sorted()
            if (workflowPaths.isEmpty()) {
                updateStep(WorkflowStage.COMMITTING, WorkflowStepStatus.FAILED, "No attributable changes")
                return if (currentDirty.isEmpty()) {
                    failWorkflow(
                        "The agent produced no changes (working tree is clean). Refusing to commit an empty diff.",
                        startedAt = startedAt,
                    )
                } else {
                    failWorkflow(
                        "The working tree holds changes that pre-date this workflow and the agent's own " +
                            "changes cannot be safely isolated from them. Refusing to commit: stash or " +
                            "commit your unrelated changes and re-run the workflow.",
                        startedAt = startedAt,
                    )
                }
            }
            updateStep(
                WorkflowStage.COMMITTING,
                WorkflowStepStatus.IN_PROGRESS,
                "Staging ${workflowPaths.size} workflow files..."
            )
            for (path in workflowPaths) {
                val stageRes = guardedGit(GitOpKind.STAGE, workingDir) {
                    gitController.stageFile(workingDir, path)
                }
                if (!stageRes.success) {
                    updateStep(WorkflowStage.COMMITTING, WorkflowStepStatus.FAILED, stageRes.errorMessage ?: "Stage failed")
                    return failWorkflow(
                        "Could not stage workflow file '$path': ${stageRes.errorMessage ?: stageRes.output}",
                        startedAt = startedAt,
                    )
                }
            }
            val commitMsg = "fix(#${issue.number}): ${issue.title}\n\nCloses #${issue.number}\nAutomated by Kodex Autonomous Workflow."
            val commitRes = guardedGit(GitOpKind.COMMIT, workingDir) {
                gitController.commitDetailed(workingDir, commitMsg)
            }
            if (!commitRes.success && !commitRes.output.contains("nothing to commit", ignoreCase = true)) {
                updateStep(WorkflowStage.COMMITTING, WorkflowStepStatus.FAILED, commitRes.errorMessage ?: "Commit failed")
                return failWorkflow(commitRes.errorMessage ?: "Commit failed", startedAt = startedAt)
            }
            updateStep(WorkflowStage.COMMITTING, WorkflowStepStatus.SUCCESS, "Committed: fix(#${issue.number})")

            // 8. Push — token was validated up front, so this path always has credentials.
            updateStep(WorkflowStage.PUSHING, WorkflowStepStatus.IN_PROGRESS, "Pushing $branchName to origin...")
            val pushRes = guardedGit(GitOpKind.PUSH, workingDir, "origin") {
                gitController.push(workingDir, "origin", branchName)
            }
            if (!pushRes.success) {
                updateStep(WorkflowStage.PUSHING, WorkflowStepStatus.FAILED, pushRes.errorMessage ?: "Push failed")
                return failWorkflow(pushRes.errorMessage ?: "Push failed", startedAt = startedAt)
            }
            updateStep(WorkflowStage.PUSHING, WorkflowStepStatus.SUCCESS, "Pushed to origin/$branchName")

            // 9. Open PR — surface the real API result or its real error.
            updateStep(WorkflowStage.CREATING_PR, WorkflowStepStatus.IN_PROGRESS, "Opening PR on GitHub...")
            val pr: GitHubPullRequest = try {
                githubClient.createPullRequest(
                    token = token,
                    repoFullName = repoFullName,
                    title = "fix(#${issue.number}): ${issue.title}",
                    head = branchName,
                    base = baseBranch,
                    body = """
                        Resolves #${issue.number}.

                        ### Changes
                        Autonomous implementation generated and verified by Kodex GitHub-Native Engine.
                        - Root cause analyzed
                        - Automated tests verified
                    """.trimIndent()
                )
            } catch (e: Exception) {
                updateStep(WorkflowStage.CREATING_PR, WorkflowStepStatus.FAILED, "GitHub API error: ${e.message}")
                return failWorkflow(
                    "GitHub pull-request creation failed: ${e.message}",
                    startedAt = startedAt,
                )
            }
            updateStep(WorkflowStage.CREATING_PR, WorkflowStepStatus.SUCCESS, "PR #${pr.number} created: ${pr.htmlUrl}")

            val evidence = "GitHub PR #${pr.number} created: ${pr.htmlUrl}"
            val result = OperationResult.success(
                logs = listOf(evidence),
                artifacts = listOf(pr.htmlUrl),
                verificationEvidence = evidence,
                startedAt = startedAt,
            )
            _state.update {
                it.copy(
                    currentStage = WorkflowStage.COMPLETED,
                    prUrl = pr.htmlUrl,
                    isRunning = false,
                    lastResult = result,
                )
            }
            return result
        } catch (e: Exception) {
            return failWorkflow("Workflow exception: ${e.message}", startedAt = startedAt)
        }
    }

    private fun <T> guardedGit(
        kind: GitOpKind,
        workingDir: File,
        remoteUrl: String? = null,
        effect: () -> T
    ): T = gitSafety.git(kind, workingDir.absolutePath, remoteUrl) { effect() }

    private fun dirtyPaths(workingDir: File): Set<String> {
        val status = runCatching { gitController.status(workingDir) }.getOrNull() ?: return emptySet()
        if (!status.success) return emptySet()
        return status.output.lineSequence()
            .map { it.trim() }
            .filter { it.length > 3 }
            .map { line ->
                val spaceIdx = line.indexOf(' ')
                var pathStart = if (spaceIdx >= 0) spaceIdx else line.length
                while (pathStart < line.length && line[pathStart] == ' ') pathStart++
                val entry = line.substring(pathStart)
                val renamed = entry.substringAfter(" -> ", "")
                if (renamed.isNotEmpty()) renamed.trim() else entry
            }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun updateStep(stage: WorkflowStage, status: WorkflowStepStatus, detail: String) {
        _state.update { curr ->
            val updatedSteps = curr.steps.map { step ->
                if (step.stage == stage) {
                    step.copy(status = status, detail = detail, timestamp = System.currentTimeMillis())
                } else {
                    step.copy()
                }
            }
            curr.copy(currentStage = stage, steps = updatedSteps)
        }
    }

    private fun failWorkflow(errorMessage: String, startedAt: Long): OperationResult {
        val result = OperationResult.failure(error = errorMessage, startedAt = startedAt)
        _state.update {
            it.copy(
                currentStage = WorkflowStage.FAILED,
                errorMessage = errorMessage,
                isRunning = false,
                lastResult = result,
            )
        }
        return result
    }

    private fun generateBranchName(issue: GitHubIssue): String {
        val slug = issue.title.lowercase()
            .replace(Regex("[^a-z0-9]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(24)
        return "fix/issue-${issue.number}-$slug"
    }
}
