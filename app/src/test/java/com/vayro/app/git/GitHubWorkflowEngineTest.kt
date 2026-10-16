package com.vayro.app.git

import com.vayro.app.core.result.OperationResult
import com.vayro.app.git.workflow.GitHubWorkflowEngine
import com.vayro.app.git.workflow.WorkflowStage
import com.vayro.app.git.workflow.WorkflowStepStatus
import com.vayro.app.network.GitHubClient
import com.vayro.app.network.GitHubIssue
import com.vayro.app.network.GitHubPullRequest
import com.vayro.app.security.GitSafetyAdapter
import com.vayro.app.security.PermissionCategory
import com.vayro.app.security.PermissionLevel
import com.vayro.app.security.PermissionPolicyEngine
import com.vayro.app.security.SafetyGateway
import com.vayro.app.workspace.GitDomainController
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeGitHubClient : GitHubClient() {
    override fun createPullRequest(
token: String,
        repoFullName: String,
        title: String,
        head: String,
        base: String,
        body: String,
        draft: Boolean
    ): GitHubPullRequest = GitHubPullRequest(
        number = 7,
        title = title,
        state = "open",
        user = "tester",
        headBranch = head,
        baseBranch = base,
        htmlUrl = "https://github.com/$repoFullName/pull/7",
        body = body
    )
}

class GitHubWorkflowEngineTest {

    private fun testGitSafety(
        repoDir: File,
        deny: Set<PermissionCategory> = emptySet()
    ): GitSafetyAdapter {
        val policyDir = File(
            System.getProperty("java.io.tmpdir"),
            "git_safety_policy_${System.nanoTime()}"
        ).apply { mkdirs() }
        val policyEngine = PermissionPolicyEngine(policyDir = policyDir)
        listOf(
            PermissionCategory.GIT_COMMIT,
            PermissionCategory.GIT_PUSH,
            PermissionCategory.GIT_BRANCH
        ).forEach { policyEngine.grantPermission(it, PermissionLevel.ALWAYS) }
        deny.forEach { policyEngine.grantPermission(it, PermissionLevel.DENY) }
        val gateway = SafetyGateway(policyEngine = policyEngine, workspaceRoot = repoDir)
        return GitSafetyAdapter(gateway)
    }

    private fun engine(
        gitController: GitDomainController,
        deny: Set<PermissionCategory> = emptySet()
    ) = GitHubWorkflowEngine(gitController, FakeGitHubClient(), testGitSafety(tempDir, deny))

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "workflow_test_repo").apply { mkdirs() }

    private fun scriptedStatusGitController(
        executedCommands: MutableList<List<String>>,
        statusOutputs: List<String>
    ): GitDomainController {
        var statusCalls = 0
        return GitDomainController { args, _, _ ->
            executedCommands += args
            if (args.firstOrNull() == "status") {
                val output = statusOutputs.getOrElse(statusCalls) { "" }
                statusCalls++
                0 to output
            } else {
                0 to "ok"
            }
        }
    }

    @Test
    fun autonomousWorkflowOfflineSuccess() = runBlocking {
        val executedCommands = mutableListOf<List<String>>()
        val gitController = scriptedStatusGitController(
            executedCommands,
            listOf("", " M src/App.kt\n?? newfile.txt")
        )
        val workflowEngine = engine(gitController)

        val issue = GitHubIssue(
            number = 42,
            title = "Crash on App Launch",
            state = "open",
            user = "developer",
            comments = 3,
            htmlUrl = "https://github.com/example/repo/issues/42",
            body = "App crashes when initializing database."
        )

        var agentExecutedPrompt = ""
        val result = workflowEngine.runAutonomousWorkflow(
            issue = issue,
            token = "fake-token-for-testing", // Offline test: fake token; git + GitHub API are mocked, no network
            repoFullName = "example/repo",
            workingDir = tempDir,
            baseBranch = "main",
            agentExecutor = { prompt ->
                agentExecutedPrompt = prompt
                OperationResult.success()
            },
            buildAndTestValidator = { OperationResult.success(logs = listOf("All tests pass")) }
        )

        assertTrue(
            "expected workflow success but got error: ${result.error} " +
                "(state error: ${workflowEngine.state.value.errorMessage})",
            result.isSuccess
        )
        val state = workflowEngine.state.value

        assertEquals(WorkflowStage.COMPLETED, state.currentStage)
        assertEquals(42, state.issueNumber)
        assertEquals("Crash on App Launch", state.issueTitle)
        assertEquals("fix/issue-42-crash-on-app-launch", state.branchName)
        assertFalse(state.isRunning)
        assertNull(state.errorMessage)

        // Verify agent received prompt with issue details
        assertTrue(agentExecutedPrompt.contains("Crash on App Launch"))
        assertTrue(agentExecutedPrompt.contains("App crashes when initializing database."))

        // Verify steps status
        val steps = state.steps
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.FETCHING_ISSUE }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.UNDERSTANDING }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.CREATING_BRANCH }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.IMPLEMENTING }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.BUILDING }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.TESTING }?.status)
        assertEquals(WorkflowStepStatus.SKIPPED, steps.find { it.stage == WorkflowStage.FIXING }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.COMMITTING }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.PUSHING }?.status)
        assertEquals(WorkflowStepStatus.SUCCESS, steps.find { it.stage == WorkflowStage.CREATING_PR }?.status)

        // Verify git commands executed
        assertTrue(executedCommands.any { it.contains("checkout") && it.contains("fix/issue-42-crash-on-app-launch") })
        assertTrue(executedCommands.any { it == listOf("add", "src/App.kt") })
        assertTrue(executedCommands.any { it == listOf("add", "newfile.txt") })
        assertTrue(
            "workflow must never stage the whole tree",
            executedCommands.none { it.contains("-A") }
        )
        assertTrue(executedCommands.any { it.contains("commit") })
        assertTrue(executedCommands.any { it.contains("push") })
    }

    @Test
    fun autonomousWorkflowSelfRepairCycle() = runBlocking {
        val gitController = scriptedStatusGitController(
            mutableListOf(),
            listOf("", " M src/Fix.kt")
        )
        val workflowEngine = engine(gitController)

        val issue = GitHubIssue(
            number = 99,
            title = "Fix typo in config",
            state = "open",
            user = "developer",
            comments = 0,
            htmlUrl = "https://github.com/example/repo/issues/99"
        )

        var buildAttempt = 0
        var repairAgentCalled = false

        val result = workflowEngine.runAutonomousWorkflow(
            issue = issue,
            token = "fake-token-for-testing",
            repoFullName = "example/repo",
            workingDir = tempDir,
            agentExecutor = { prompt ->
                if (prompt.contains("Build/test failed")) {
                    repairAgentCalled = true
                }
                OperationResult.success()
            },
            buildAndTestValidator = {
                buildAttempt++
                if (buildAttempt == 1) {
                    OperationResult.failure("Syntax error: unresolved reference")
                } else {
                    OperationResult.success(logs = listOf("Build succeeded on retry"))
                }
            }
        )

        assertTrue(result.isSuccess)
        assertTrue(repairAgentCalled)
        val state = workflowEngine.state.value

        assertEquals(WorkflowStage.COMPLETED, state.currentStage)
        val fixingStep = state.steps.find { it.stage == WorkflowStage.FIXING }
        assertEquals(WorkflowStepStatus.SUCCESS, fixingStep?.status)
        assertTrue(fixingStep?.detail?.startsWith("Repair successful") == true)
    }

    @Test
    fun autonomousWorkflowFailsWhenAgentFails() = runBlocking {
        val gitController = GitDomainController { _, _, _ -> 0 to "ok" }
        val workflowEngine = engine(gitController)

        val issue = GitHubIssue(
            number = 101,
            title = "Impossible task",
            state = "open",
            user = "user",
            comments = 1,
            htmlUrl = ""
        )

        val result = workflowEngine.runAutonomousWorkflow(
            issue = issue,
            token = "fake-token-for-testing",
            repoFullName = "example/repo",
            workingDir = tempDir,
            agentExecutor = { OperationResult.failure("Agent failed") } // Agent fails
        )

        assertFalse(result.isSuccess)
        val state = workflowEngine.state.value

        assertEquals(WorkflowStage.FAILED, state.currentStage)
        assertFalse(state.isRunning)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Agent implementation did not complete"))
    }

    @Test
    fun workflowResetClearsState() = runBlocking {
        val gitController = scriptedStatusGitController(
            mutableListOf(),
            listOf("", " M src/Bug.kt")
        )
        val workflowEngine = engine(gitController)

        val issue = GitHubIssue(number = 1, title = "Bug", state = "open", user = "u", comments = 0, htmlUrl = "")
        workflowEngine.runAutonomousWorkflow(
            issue = issue,
            token = "fake-token-for-testing",
            repoFullName = "repo",
            workingDir = tempDir,
            agentExecutor = { OperationResult.success() },
            buildAndTestValidator = { OperationResult.success() }
        )

        assertEquals(WorkflowStage.COMPLETED, workflowEngine.state.value.currentStage)
        workflowEngine.reset()

        assertEquals(WorkflowStage.IDLE, workflowEngine.state.value.currentStage)
        assertEquals(0, workflowEngine.state.value.issueNumber)
        assertTrue(workflowEngine.state.value.steps.isEmpty())
    }

    @Test
    fun deniedPushNeverReachesGit() = runBlocking {
        val executedCommands = mutableListOf<List<String>>()
        val gitController = scriptedStatusGitController(
            executedCommands,
            listOf("", " M src/App.kt")
        )
        val workflowEngine = engine(gitController, deny = setOf(PermissionCategory.GIT_PUSH))

        val issue = GitHubIssue(number = 7, title = "Denied push", state = "open", user = "u", comments = 0, htmlUrl = "")
        val result = workflowEngine.runAutonomousWorkflow(
            issue = issue,
            token = "fake-token-for-testing",
            repoFullName = "example/repo",
            workingDir = tempDir,
            agentExecutor = { OperationResult.success() },
            buildAndTestValidator = { OperationResult.success() }
        )

        assertFalse(result.isSuccess)
        assertEquals(WorkflowStage.FAILED, workflowEngine.state.value.currentStage)
        assertTrue(executedCommands.any { it.contains("commit") })
        assertTrue(
            "a gateway-denied push must never reach the git boundary",
            executedCommands.none { it.contains("push") }
        )
    }

    @Test
    fun deniedStageNeverReachesGit() = runBlocking {
        val executedCommands = mutableListOf<List<String>>()
        val gitController = scriptedStatusGitController(
            executedCommands,
            listOf("", " M src/App.kt")
        )
        val workflowEngine = engine(gitController, deny = setOf(PermissionCategory.GIT_COMMIT))

        val issue = GitHubIssue(number = 8, title = "Denied stage", state = "open", user = "u", comments = 0, htmlUrl = "")
        val result = workflowEngine.runAutonomousWorkflow(
            issue = issue,
            token = "fake-token-for-testing",
            repoFullName = "example/repo",
            workingDir = tempDir,
            agentExecutor = { OperationResult.success() },
            buildAndTestValidator = { OperationResult.success() }
        )

        assertFalse(result.isSuccess)
        assertEquals(WorkflowStage.FAILED, workflowEngine.state.value.currentStage)
        assertTrue(
            "a gateway-denied stage must never reach the git boundary",
            executedCommands.none { it.contains("add") }
        )
        assertTrue(executedCommands.none { it.contains("commit") })
    }

    @Test
    fun onlyWorkflowPathsAreStaged() = runBlocking {
        val executedCommands = mutableListOf<List<String>>()
        val gitController = scriptedStatusGitController(
            executedCommands,
            listOf(" M old.txt", " M old.txt\n?? new.txt")
        )
        val workflowEngine = engine(gitController)

        val issue = GitHubIssue(number = 9, title = "Selective stage", state = "open", user = "u", comments = 0, htmlUrl = "")
        val result = workflowEngine.runAutonomousWorkflow(
            issue = issue,
            token = "fake-token-for-testing",
            repoFullName = "example/repo",
            workingDir = tempDir,
            agentExecutor = { OperationResult.success() },
            buildAndTestValidator = { OperationResult.success() }
        )

        assertTrue(result.isSuccess)
        assertTrue(executedCommands.any { it == listOf("add", "new.txt") })
        assertTrue(
            "pre-existing dirty files must never be staged",
            executedCommands.none { it == listOf("add", "old.txt") }
        )
        assertTrue(executedCommands.none { it.contains("-A") })
    }

    @Test
    fun unisolatableDirtyTreeRefusesCommit() = runBlocking {
        val executedCommands = mutableListOf<List<String>>()
        val gitController = scriptedStatusGitController(
            executedCommands,
            listOf(" M old.txt", " M old.txt")
        )
        val workflowEngine = engine(gitController)

        val issue = GitHubIssue(number = 10, title = "Dirty tree", state = "open", user = "u", comments = 0, htmlUrl = "")
        val result = workflowEngine.runAutonomousWorkflow(
            issue = issue,
            token = "fake-token-for-testing",
            repoFullName = "example/repo",
            workingDir = tempDir,
            agentExecutor = { OperationResult.success() },
            buildAndTestValidator = { OperationResult.success() }
        )

        assertFalse(result.isSuccess)
        assertEquals(WorkflowStage.FAILED, workflowEngine.state.value.currentStage)
        assertTrue(result.error.orEmpty().contains("cannot be safely isolated"))
        assertTrue(executedCommands.none { it.contains("commit") })
        assertTrue(executedCommands.none { it.contains("push") })
    }

    @Test
    fun cleanTreeStillRefusesEmptyCommit() = runBlocking {
        val executedCommands = mutableListOf<List<String>>()
        val gitController = scriptedStatusGitController(
            executedCommands,
            listOf("", "")
        )
        val workflowEngine = engine(gitController)

        val issue = GitHubIssue(number = 11, title = "No changes", state = "open", user = "u", comments = 0, htmlUrl = "")
        val result = workflowEngine.runAutonomousWorkflow(
            issue = issue,
            token = "fake-token-for-testing",
            repoFullName = "example/repo",
            workingDir = tempDir,
            agentExecutor = { OperationResult.success() },
            buildAndTestValidator = { OperationResult.success() }
        )

        assertFalse(result.isSuccess)
        assertTrue(result.error.orEmpty().contains("no changes"))
        assertTrue(executedCommands.none { it.contains("commit") })
    }
}
