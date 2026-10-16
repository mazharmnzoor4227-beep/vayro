package com.vayro.app.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpAuditLoggerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun logger() = McpAuditLogger(temp.newFolder(), maxEntries = 10)

    private fun entry(args: String, preview: String = "ok") = McpAuditEntry(
        serverId = "srv", toolName = "tool", argumentsJson = args,
        success = true, resultPreview = preview,
    )

    @Test
    fun `sensitive argument values are redacted on log`() {
        val l = logger()
        l.log(entry("""{"path":"/a","github_token":"ghp_real","nested":{"api_key":"k"},"list":[{"password":"p"}]}"""))
        val stored = l.getEntries(1).single()
        val json = JSONObject(stored.argumentsJson)
        assertEquals("/a", json.getString("path"))
        assertEquals("[REDACTED]", json.getString("github_token"))
        assertEquals("[REDACTED]", json.getJSONObject("nested").getString("api_key"))
        assertEquals("[REDACTED]", json.getJSONArray("list").getJSONObject(0).getString("password"))
    }

    @Test
    fun `non sensitive arguments pass through unchanged`() {
        val l = logger()
        val args = """{"command":"ls","count":3}"""
        l.log(entry(args))
        // org.json re-serializes, so compare parsed content, not the raw string.
        assertEquals(
            JSONObject(args).toString(),
            JSONObject(l.getEntries(1).single().argumentsJson).toString()
        )
    }

    @Test
    fun `result preview is capped at 500 chars`() {
        val l = logger()
        l.log(entry("{}", "x".repeat(2000)))
        val stored = l.getEntries(1).single()
        assertTrue(stored.resultPreview.length <= 500)
    }

    @Test
    fun `redaction is persisted to the audit file`() {
        val dir = temp.newFolder()
        McpAuditLogger(dir).log(entry("""{"bearer":"abc123"}"""))
        val raw = java.io.File(dir, "mcp_audit.json").readText()
        assertFalse(raw.contains("abc123"))
        assertTrue(raw.contains("[REDACTED]"))
    }
}
