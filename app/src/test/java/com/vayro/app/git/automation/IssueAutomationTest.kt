package com.vayro.app.git.automation

import com.vayro.app.core.result.OperationStatus
import com.vayro.app.network.GitHubClient
import com.vayro.app.network.GitHubIssueComment
import com.vayro.app.network.GitHubLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeIssueClient : GitHubClient() {
    val appliedLabels = mutableListOf<List<String>>()
    val assigned = mutableListOf<List<String>>()
    val comments = mutableListOf<String>()
    var closed = 0
    var failOn: String? = null

    override fun addLabels(token: String, repoFullName: String, issueNumber: Int, labels: List<String>): List<GitHubLabel> {
        if (failOn == "labels") error("labels API exploded")
        appliedLabels += labels
        return labels.map { GitHubLabel(name = it) }
    }

    override fun assignIssue(token: String, repoFullName: String, issueNumber: Int, assignees: List<String>): Boolean {
        if (failOn == "assign") error("assign API exploded")
        assigned += assignees
        return true
    }

    override fun createIssueComment(token: String, repoFullName: String, issueNumber: Int, comment: String): GitHubIssueComment {
        if (failOn == "comment") error("comment API exploded")
        comments += comment
        return GitHubIssueComment(id = 1L, body = comment, user = "bot", createdAt = "")
    }

    override fun closeIssue(token: String, repoFullName: String, issueNumber: Int): Boolean {
        closed++
        return true
    }
}

class IssueAutomationTest {

    @Test
    fun triageAppliesAllActions() {
        val client = FakeIssueClient()
        val result = IssueAutomation(client).triage(
            "tok", "o/r", 5,
            IssueTriageRule(
                labelsToAdd = listOf("bug", "p0"),
                assignees = listOf("sunmughan"),
                comment = "Reproduced, working on it.",
                close = true,
            )
        )
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertEquals(listOf(listOf("bug", "p0")), client.appliedLabels)
        assertEquals(listOf(listOf("sunmughan")), client.assigned)
        assertEquals(listOf("Reproduced, working on it."), client.comments)
        assertEquals(1, client.closed)
        assertEquals(4, result.logs.size)
    }

    @Test
    fun triageSkipsEmptyActions() {
        val client = FakeIssueClient()
        val result = IssueAutomation(client).triage("tok", "o/r", 5, IssueTriageRule())
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertTrue(client.appliedLabels.isEmpty())
        assertTrue(client.assigned.isEmpty())
        assertTrue(client.comments.isEmpty())
        assertEquals(0, client.closed)
    }

    @Test
    fun triageFailureKeepsPartialLog() {
        val client = FakeIssueClient().also { it.failOn = "comment" }
        val result = IssueAutomation(client).triage(
            "tok", "o/r", 5,
            IssueTriageRule(labelsToAdd = listOf("bug"), comment = "hi", close = true)
        )
        assertEquals(OperationStatus.FAILED, result.status)
        // labels were applied before the comment threw; close never ran
        assertEquals(1, client.appliedLabels.size)
        assertEquals(0, client.closed)
        assertTrue(result.logs.any { it.contains("labels applied") })
    }

    @Test
    fun triageBlankTokenIsNotConfigured() {
        val client = FakeIssueClient()
        val result = IssueAutomation(client).triage("", "o/r", 5, IssueTriageRule(close = true))
        assertEquals(OperationStatus.NOT_CONFIGURED, result.status)
        assertEquals(0, client.closed)
    }

    @Test
    fun postStatusCommentSuccess() {
        val client = FakeIssueClient()
        val result = IssueAutomation(client).postStatusComment("tok", "o/r", 5, "Status: fixed in CI.")
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertEquals(listOf("Status: fixed in CI."), client.comments)
        assertTrue(result.verificationEvidence!!.contains("comment id=1"))
    }

    @Test
    fun postStatusCommentRefusesBlank() {
        val client = FakeIssueClient()
        val result = IssueAutomation(client).postStatusComment("tok", "o/r", 5, "   ")
        assertEquals(OperationStatus.FAILED, result.status)
        assertTrue(client.comments.isEmpty())
    }

    @Test
    fun postStatusCommentBlankTokenIsNotConfigured() {
        val client = FakeIssueClient()
        val result = IssueAutomation(client).postStatusComment("", "o/r", 5, "hi")
        assertEquals(OperationStatus.NOT_CONFIGURED, result.status)
        assertTrue(client.comments.isEmpty())
    }
}
