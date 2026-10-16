package com.vayro.app.git.automation

import com.vayro.app.core.result.OperationResult
import com.vayro.app.network.GitHubClient
import com.vayro.app.network.GitHubWorkflowRun
import kotlinx.coroutines.delay

/**
 * Milestone 9 — workflow dispatch automation.
 *
 * Dispatch → await: fires a workflow dispatch event, locates the run it produced
 * (by taking the max run id before dispatch as the baseline), then polls until
 * the run completes or the timeout expires. Conclusions are reported exactly as
 * GitHub returns them — a "failure" conclusion is a FAILED result, never
 * reinterpreted as success.
 */
class WorkflowAutomation(
    private val client: GitHubClient = GitHubClient(),
) {
    fun dispatch(
        token: String,
        repoFullName: String,
        workflowId: String,
        ref: String = "main",
        inputs: Map<String, Any> = emptyMap(),
    ): OperationResult {
        val startedAt = System.currentTimeMillis()
        if (token.isBlank()) {
            return OperationResult.notConfigured(
                "GitHub token is missing. Refusing to dispatch workflow $workflowId without credentials."
            )
        }
        return try {
            val ok = client.dispatchWorkflow(token, repoFullName, workflowId, ref, inputs)
            if (!ok) {
                OperationResult.failure(
                    error = "Workflow dispatch for $workflowId on $ref was not accepted.",
                    startedAt = startedAt,
                )
            } else {
                OperationResult.success(
                    logs = listOf("dispatch accepted: workflow=$workflowId ref=$ref"),
                    verificationEvidence = "GitHub accepted the dispatch event for $workflowId on $ref",
                    startedAt = startedAt,
                )
            }
        } catch (e: Exception) {
            OperationResult.failure(
                error = "Workflow dispatch failed for $workflowId: ${e.message}",
                startedAt = startedAt,
            )
        }
    }

    /**
     * Dispatches and waits for the resulting run to complete.
     *
     * @param timeoutMs maximum time to wait for completion.
     * @param pollMs interval between status polls.
     * @param sleeper injectable sleep for tests (defaults to real delay).
     */
    suspend fun dispatchAndAwait(
        token: String,
        repoFullName: String,
        workflowId: String,
        ref: String = "main",
        inputs: Map<String, Any> = emptyMap(),
        timeoutMs: Long = 10 * 60 * 1000L,
        pollMs: Long = 10_000L,
        sleeper: suspend (Long) -> Unit = { delay(it) },
    ): OperationResult {
        val startedAt = System.currentTimeMillis()
        if (token.isBlank()) {
            return OperationResult.notConfigured(
                "GitHub token is missing. Refusing to dispatch workflow $workflowId without credentials."
            )
        }
        val logs = mutableListOf<String>()
        return try {
            val baselineMaxId = client.listWorkflowRunsForWorkflow(token, repoFullName, workflowId)
                .maxOfOrNull { it.id } ?: 0L
            if (!client.dispatchWorkflow(token, repoFullName, workflowId, ref, inputs)) {
                return OperationResult.failure(
                    error = "Workflow dispatch for $workflowId on $ref was not accepted.",
                    startedAt = startedAt,
                )
            }
            logs += "dispatch accepted; waiting for a run newer than id=$baselineMaxId"

            var run: GitHubWorkflowRun? = null
            val deadline = startedAt + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val candidate = client.listWorkflowRunsForWorkflow(token, repoFullName, workflowId)
                    .filter { it.id > baselineMaxId }
                    .maxByOrNull { it.id }
                if (candidate != null) {
                    val fetched = client.getWorkflowRun(token, repoFullName, candidate.id)
                    logs += "run ${fetched.id} status=${fetched.status} conclusion=${fetched.conclusion ?: "-"}"
                    if (fetched.status == "completed") {
                        run = fetched
                        break
                    }
                }
                sleeper(pollMs)
                // Re-check deadline after sleeping; loop condition handles exit.
                if (System.currentTimeMillis() >= deadline) break
            }

            val finalRun = run ?: client.listWorkflowRunsForWorkflow(token, repoFullName, workflowId)
                .filter { it.id > baselineMaxId }
                .maxByOrNull { it.id }
                ?.let { client.getWorkflowRun(token, repoFullName, it.id) }

            if (finalRun == null || finalRun.status != "completed") {
                return OperationResult.failure(
                    error = "Timed out after ${timeoutMs}ms waiting for workflow $workflowId to complete. " +
                        "Last observed: ${finalRun?.let { "run ${it.id} status=${it.status}" } ?: "no run appeared"}.",
                    logs = logs,
                    startedAt = startedAt,
                )
            }
            val conclusion = finalRun.conclusion ?: "unknown"
            logs += "run ${finalRun.id} completed with conclusion=$conclusion: ${finalRun.htmlUrl}"
            if (conclusion == "success") {
                OperationResult.success(
                    logs = logs,
                    artifacts = listOf(finalRun.htmlUrl),
                    verificationEvidence = "Workflow $workflowId run ${finalRun.id} completed with conclusion=success",
                    startedAt = startedAt,
                )
            } else {
                OperationResult.failure(
                    error = "Workflow $workflowId run ${finalRun.id} completed with conclusion=$conclusion. " +
                        "See ${finalRun.htmlUrl}",
                    logs = logs,
                    startedAt = startedAt,
                )
            }
        } catch (e: Exception) {
            OperationResult.failure(
                error = "dispatchAndAwait failed for $workflowId: ${e.message}",
                logs = logs,
                startedAt = startedAt,
            )
        }
    }

    fun cancelRun(token: String, repoFullName: String, runId: Long): OperationResult {
        val startedAt = System.currentTimeMillis()
        if (token.isBlank()) {
            return OperationResult.notConfigured(
                "GitHub token is missing. Refusing to cancel run $runId without credentials."
            )
        }
        return try {
            val ok = client.cancelWorkflowRun(token, repoFullName, runId)
            if (ok) {
                OperationResult.success(
                    logs = listOf("cancel requested for run $runId"),
                    verificationEvidence = "GitHub accepted the cancel request for run $runId",
                    startedAt = startedAt,
                )
            } else {
                OperationResult.failure(
                    error = "GitHub did not accept the cancel request for run $runId.",
                    startedAt = startedAt,
                )
            }
        } catch (e: Exception) {
            OperationResult.failure(
                error = "Failed to cancel run $runId: ${e.message}",
                startedAt = startedAt,
            )
        }
    }
}
