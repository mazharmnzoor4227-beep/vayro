package com.vayro.app.git.automation

import com.vayro.app.core.result.OperationResult
import com.vayro.app.network.GitHubClient

/**
 * Milestone 9 — issue management automation.
 *
 * Rule-based issue triage: applies labels, assigns people, posts a comment, and
 * optionally closes the issue in one audited pass. Every action is logged; if any
 * action throws, the operation fails with the partial log so the caller sees
 * exactly what was applied and what was not.
 */
data class IssueTriageRule(
    val labelsToAdd: List<String> = emptyList(),
    val assignees: List<String> = emptyList(),
    val comment: String? = null,
    val close: Boolean = false,
    val title: String? = null,
)

class IssueAutomation(
    private val client: GitHubClient = GitHubClient(),
) {
    fun triage(
        token: String,
        repoFullName: String,
        issueNumber: Int,
        rule: IssueTriageRule,
    ): OperationResult {
        val startedAt = System.currentTimeMillis()
        if (token.isBlank()) {
            return OperationResult.notConfigured(
                "GitHub token is missing. Refusing to triage issue #$issueNumber without credentials."
            )
        }
        val logs = mutableListOf<String>()
        return try {
            if (rule.labelsToAdd.isNotEmpty()) {
                val applied = client.addLabels(token, repoFullName, issueNumber, rule.labelsToAdd)
                logs += "labels applied: ${applied.map { it.name }}"
            }
            if (rule.assignees.isNotEmpty()) {
                client.assignIssue(token, repoFullName, issueNumber, rule.assignees)
                logs += "assigned to: ${rule.assignees}"
            }
            if (!rule.comment.isNullOrBlank()) {
                val c = client.createIssueComment(token, repoFullName, issueNumber, rule.comment)
                logs += "comment posted (id=${c.id})"
            }
            if (rule.title != null) {
                client.updateIssue(token, repoFullName, issueNumber, title = rule.title)
                logs += "title updated"
            }
            if (rule.close) {
                val closed = client.closeIssue(token, repoFullName, issueNumber)
                logs += if (closed) "issue closed" else "close requested (API did not confirm)"
            }
            val evidence = "Issue #$issueNumber triaged: ${logs.size} action(s) applied"
            OperationResult.success(
                logs = logs,
                verificationEvidence = evidence,
                startedAt = startedAt,
            )
        } catch (e: Exception) {
            OperationResult.failure(
                error = "Issue triage failed for #$issueNumber after partial actions: ${e.message}",
                logs = logs,
                startedAt = startedAt,
            )
        }
    }

    /**
     * Posts a status comment on an issue or PR (issues and PRs share the comments API).
     */
    fun postStatusComment(
        token: String,
        repoFullName: String,
        issueNumber: Int,
        comment: String,
    ): OperationResult {
        val startedAt = System.currentTimeMillis()
        if (token.isBlank()) {
            return OperationResult.notConfigured(
                "GitHub token is missing. Refusing to comment on #$issueNumber without credentials."
            )
        }
        if (comment.isBlank()) {
            return OperationResult.failure(
                error = "Refusing to post an empty comment on #$issueNumber.",
                startedAt = startedAt,
            )
        }
        return try {
            val c = client.createIssueComment(token, repoFullName, issueNumber, comment)
            OperationResult.success(
                logs = listOf("comment id=${c.id} posted on #$issueNumber by ${c.user}"),
                artifacts = listOf(c.id.toString()),
                verificationEvidence = "GitHub comment id=${c.id} created on issue #$issueNumber",
                startedAt = startedAt,
            )
        } catch (e: Exception) {
            OperationResult.failure(
                error = "Failed to comment on #$issueNumber: ${e.message}",
                startedAt = startedAt,
            )
        }
    }
}
