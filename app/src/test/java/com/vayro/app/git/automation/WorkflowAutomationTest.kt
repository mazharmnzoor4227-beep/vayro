package com.vayro.app.git.automation

import com.vayro.app.core.result.OperationStatus
import com.vayro.app.network.GitHubClient
import com.vayro.app.network.GitHubWorkflowRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun run(id: Long, status: String, conclusion: String? = null) = GitHubWorkflowRun(
    id = id, name = "CI", status = status, conclusion = conclusion,
    htmlUrl = "https://github.com/o/r/actions/runs/$id",
    headBranch = "main", event = "workflow_dispatch", createdAt = "", runNumber = 1,
)

private class FakeWorkflowClient(
    var existingRuns: List<GitHubWorkflowRun> = emptyList(),
    var runsAfterDispatch: List<GitHubWorkflowRun> = emptyList(),
    var dispatchOk: Boolean = true,
    var cancelOk: Boolean = true,
) : GitHubClient() {
    var dispatchCalls = 0

    override fun listWorkflowRunsForWorkflow(token: String, repoFullName: String, workflowId: String): List<GitHubWorkflowRun> {
        return if (dispatchCalls > 0) runsAfterDispatch else existingRuns
    }

    override fun dispatchWorkflow(token: String, repoFullName: String, workflowId: String, ref: String, inputs: Map<String, Any>): Boolean {
        dispatchCalls++
        return dispatchOk
    }

    override fun getWorkflowRun(token: String, repoFullName: String, runId: Long): GitHubWorkflowRun {
        return (runsAfterDispatch + existingRuns).first { it.id == runId }
    }

    override fun cancelWorkflowRun(token: String, repoFullName: String, runId: Long): Boolean = cancelOk
}

class WorkflowAutomationTest {

    @Test
    fun dispatchAccepted() {
        val client = FakeWorkflowClient()
        val result = WorkflowAutomation(client).dispatch("tok", "o/r", "ci.yml")
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertEquals(1, client.dispatchCalls)
    }

    @Test
    fun dispatchRejectedIsFailure() {
        val client = FakeWorkflowClient(dispatchOk = false)
        val result = WorkflowAutomation(client).dispatch("tok", "o/r", "ci.yml")
        assertEquals(OperationStatus.FAILED, result.status)
    }

    @Test
    fun dispatchBlankTokenIsNotConfigured() {
        val client = FakeWorkflowClient()
        val result = WorkflowAutomation(client).dispatch("", "o/r", "ci.yml")
        assertEquals(OperationStatus.NOT_CONFIGURED, result.status)
        assertEquals(0, client.dispatchCalls)
    }

    @Test
    fun dispatchAndAwaitSuccess() = runBlocking {
        val client = FakeWorkflowClient(
            existingRuns = listOf(run(100, "completed", "success")),
            runsAfterDispatch = listOf(run(100, "completed", "success"), run(101, "completed", "success")),
        )
        val result = WorkflowAutomation(client).dispatchAndAwait(
            "tok", "o/r", "ci.yml", pollMs = 0L, timeoutMs = 5_000L, sleeper = {},
        )
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertTrue(result.verificationEvidence!!.contains("conclusion=success"))
        assertTrue(result.artifacts.any { it.contains("/runs/101") })
    }

    @Test
    fun dispatchAndAwaitFailureConclusionIsFailure() = runBlocking {
        val client = FakeWorkflowClient(
            runsAfterDispatch = listOf(run(101, "completed", "failure")),
        )
        val result = WorkflowAutomation(client).dispatchAndAwait(
            "tok", "o/r", "ci.yml", pollMs = 0L, timeoutMs = 5_000L, sleeper = {},
        )
        assertEquals(OperationStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("conclusion=failure"))
    }

    @Test
    fun dispatchAndAwaitTimesOutWhenNoRunAppears() = runBlocking {
        val client = FakeWorkflowClient() // no runs ever appear
        val result = WorkflowAutomation(client).dispatchAndAwait(
            "tok", "o/r", "ci.yml", pollMs = 0L, timeoutMs = 50L, sleeper = {},
        )
        assertEquals(OperationStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("Timed out"))
    }

    @Test
    fun cancelRunSuccess() {
        val client = FakeWorkflowClient()
        val result = WorkflowAutomation(client).cancelRun("tok", "o/r", 101L)
        assertEquals(OperationStatus.SUCCESS, result.status)
    }

    @Test
    fun cancelRunBlankTokenIsNotConfigured() {
        val client = FakeWorkflowClient()
        val result = WorkflowAutomation(client).cancelRun("", "o/r", 101L)
        assertEquals(OperationStatus.NOT_CONFIGURED, result.status)
    }
}
