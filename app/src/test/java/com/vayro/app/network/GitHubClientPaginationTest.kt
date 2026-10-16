package com.vayro.app.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA Medium: list endpoints used to fetch a single page (30/20 items)
 * and silently truncate. Pages are now followed at per_page=100 until
 * a short page, up to 5 pages.
 */
class GitHubClientPaginationTest {

    private fun issueJson(number: Int): JSONObject = JSONObject().apply {
        put("number", number)
        put("title", "Issue $number")
        put("state", "open")
        put("user", JSONObject().put("login", "user$number"))
        put("comments", 0)
        put("html_url", "https://github.com/o/r/issues/$number")
        put("created_at", "2026-10-14T00:00:00Z")
        put("body", "")
    }

    private fun runJson(id: Long): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", "CI Run")
        put("status", "completed")
        put("conclusion", "success")
        put("html_url", "https://github.com/o/r/actions/runs/$id")
        put("head_branch", "main")
        put("event", "push")
        put("created_at", "2026-10-14T00:00:00Z")
        put("run_number", 1)
    }

    private class PagedClient(
        private val pages: Map<Int, Any>,
    ) : GitHubClient() {
        val fetchedPages = mutableListOf<Int>()

        override fun getJson(endpoint: String, token: String): Any {
            val page = endpoint.substringAfter("&page=").substringBefore("&").toInt()
            fetchedPages += page
            return pages.getValue(page)
        }
    }

    @Test
    fun `issues follow pages past the old 30-item cap`() {
        val page1 = JSONArray().apply { for (i in 1..100) put(issueJson(i)) }
        val page2 = JSONArray().apply { put(issueJson(101)) }
        val client = PagedClient(mapOf(1 to page1, 2 to page2))
        val issues = client.issues("token", "o/r")
        assertEquals(101, issues.size)
        assertEquals(101, issues.last().number)
        assertEquals(listOf(1, 2), client.fetchedPages)
    }

    @Test
    fun `short first page stops pagination`() {
        val page1 = JSONArray().apply { put(issueJson(1)); put(issueJson(2)) }
        val client = PagedClient(mapOf(1 to page1))
        val issues = client.issues("token", "o/r")
        assertEquals(2, issues.size)
        assertEquals(listOf(1), client.fetchedPages)
    }

    @Test
    fun `pull request entries are excluded from issues across pages`() {
        val page1 = JSONArray().apply {
            put(issueJson(1))
            put(issueJson(2).put("pull_request", JSONObject()))
        }
        val client = PagedClient(mapOf(1 to page1))
        val issues = client.issues("token", "o/r")
        assertEquals(listOf(1), issues.map { it.number })
    }

    @Test
    fun `workflow runs unwrap the wrapper object across pages`() {
        val page1 = JSONObject().put("workflow_runs", JSONArray().apply {
            for (i in 1..100) put(runJson(i.toLong()))
        })
        val page2 = JSONObject().put("workflow_runs", JSONArray().apply { put(runJson(101)) })
        val client = PagedClient(mapOf(1 to page1, 2 to page2))
        val runs = client.listWorkflowRuns("token", "o/r")
        assertEquals(101, runs.size)
        assertEquals(101L, runs.last().id)
    }

    @Test
    fun `pagination caps at five pages`() {
        val fullPage = JSONArray().apply { for (i in 1..100) put(issueJson(i)) }
        val client = PagedClient((1..5).associateWith { fullPage })
        val issues = client.issues("token", "o/r")
        assertEquals(500, issues.size)
        assertEquals(listOf(1, 2, 3, 4, 5), client.fetchedPages)
        assertTrue(client.fetchedPages.size == 5)
    }
}
