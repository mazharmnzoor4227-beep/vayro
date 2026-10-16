package com.vayro.app.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubClientExpandedTest {

    @Test
    fun pullRequestMergeMethodValues() {
        assertEquals("merge", PullRequestMergeMethod.MERGE.value)
        assertEquals("squash", PullRequestMergeMethod.SQUASH.value)
        assertEquals("rebase", PullRequestMergeMethod.REBASE.value)
    }

    @Test
    fun gitHubModelsInstantiation() {
        val comment = GitHubIssueComment(
            id = 12345L,
            body = "Looks good to me!",
            user = "reviewer",
            createdAt = "2026-10-11T12:00:00Z"
        )
        assertEquals(12345L, comment.id)
        assertEquals("reviewer", comment.user)
        assertEquals("Looks good to me!", comment.body)

        val review = GitHubReview(
            id = 54321L,
            user = "reviewer",
            state = "APPROVED",
            body = "LGTM!",
            submittedAt = "2026-10-11T12:05:00Z"
        )
        assertEquals("APPROVED", review.state)

        val workflow = GitHubWorkflow(
            id = 999L,
            name = "Android CI",
            path = ".github/workflows/ci.yml",
            state = "active"
        )
        assertEquals("Android CI", workflow.name)
        assertEquals(".github/workflows/ci.yml", workflow.path)

        val workflowRun = GitHubWorkflowRun(
            id = 888L,
            name = "Build & Test",
            status = "completed",
            conclusion = "success",
            htmlUrl = "https://github.com/example/repo/actions/runs/888",
            headBranch = "main",
            event = "push",
            createdAt = "2026-10-11T12:10:00Z",
            runNumber = 42
        )
        assertEquals("completed", workflowRun.status)
        assertEquals("success", workflowRun.conclusion)
        assertEquals(42, workflowRun.runNumber)

        val asset = GitHubReleaseAsset(
            id = 777L,
            name = "app-release.apk",
            sizeBytes = 15_000_000L,
            downloadUrl = "https://github.com/example/repo/releases/download/v1.0.0/app-release.apk",
            contentType = "application/vnd.android.package-archive"
        )
        assertEquals("app-release.apk", asset.name)
        assertEquals(15_000_000L, asset.sizeBytes)

        val release = GitHubRelease(
            id = 666L,
            tagName = "v1.0.0",
            name = "Version 1.0.0 Production",
            body = "Initial production release.",
            draft = false,
            prerelease = false,
            createdAt = "2026-10-11T12:15:00Z",
            publishedAt = "2026-10-11T12:20:00Z",
            htmlUrl = "https://github.com/example/repo/releases/tag/v1.0.0",
            assets = listOf(asset)
        )
        assertEquals("v1.0.0", release.tagName)
        assertFalse(release.draft)
        assertFalse(release.prerelease)
        assertEquals(1, release.assets.size)
        assertEquals("app-release.apk", release.assets[0].name)

        val mergeResult = MergeResult(
            merged = true,
            message = "Pull Request successfully merged",
            sha = "6dcb09b5b57875f334f61aebed695e2e4193db5e"
        )
        assertTrue(mergeResult.merged)
        assertEquals("6dcb09b5b57875f334f61aebed695e2e4193db5e", mergeResult.sha)
    }

    @Test
    fun jsonParsingVerificationForGitHubResponses() {
        val rawRunJson = """
            {
              "id": 101,
              "name": "CI",
              "status": "completed",
              "conclusion": "failure",
              "html_url": "https://github.com/run/101",
              "head_branch": "fix/issue-1",
              "event": "pull_request",
              "created_at": "2026-10-11T12:00:00Z",
              "run_number": 5
            }
        """.trimIndent()

        val json = JSONObject(rawRunJson)
        val run = GitHubWorkflowRun(
            id = json.getLong("id"),
            name = json.getString("name"),
            status = json.getString("status"),
            conclusion = json.optString("conclusion").takeIf { it.isNotBlank() },
            htmlUrl = json.getString("html_url"),
            headBranch = json.getString("head_branch"),
            event = json.getString("event"),
            createdAt = json.getString("created_at"),
            runNumber = json.getInt("run_number")
        )

        assertEquals(101L, run.id)
        assertEquals("failure", run.conclusion)
        assertEquals(5, run.runNumber)
    }

    // QA H16: the PR-diff path and the JSON path share one error-message
    // mapping. A 404/403/rate-limit must surface GitHub's own message,
    // never a bare FileNotFoundException, and an empty/non-JSON body must
    // fall back to the HTTP status.
    @Test
    fun githubErrorMessagePrefersGitHubMessage() {
        assertEquals(
            "Not Found",
            githubErrorMessage(404, """{"message":"Not Found","documentation_url":"https://docs.github.com"}""")
        )
        assertEquals(
            "API rate limit exceeded for user ID 1.",
            githubErrorMessage(403, """{"message":"API rate limit exceeded for user ID 1."}""")
        )
    }

    @Test
    fun githubErrorMessageFallsBackToStatus() {
        assertEquals("GitHub returned HTTP 403", githubErrorMessage(403, ""))
        assertEquals("GitHub returned HTTP 500", githubErrorMessage(500, "<html>not json</html>"))
        assertEquals("GitHub returned HTTP 404", githubErrorMessage(404, """{"message":"   "}"""))
        assertEquals("GitHub returned HTTP 401", githubErrorMessage(401, """{"other":"no message key"}"""))
    }
}
