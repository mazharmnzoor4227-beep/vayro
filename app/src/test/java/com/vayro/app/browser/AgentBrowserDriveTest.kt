package com.vayro.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentBrowserDriveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleTargetsJson(): String = "[" +
        "{\"id\":\"A\",\"type\":\"page\",\"title\":\"Example\",\"url\":\"https://example.com\"," +
        "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/page/A\"}," +
        "{\"id\":\"B\",\"type\":\"page\",\"title\":\"\",\"url\":\"devtools://devtools/bundled/inspector.html\"," +
        "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/page/B\"}," +
        "{\"id\":\"C\",\"type\":\"page\",\"title\":\"Blank\",\"url\":\"about:blank\"," +
        "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/page/C\"}" +
        "]"

    @Test
    fun `parseDevToolsTargets parses page targets`() {
        val targets = parseDevToolsTargets(sampleTargetsJson())
        assertEquals(3, targets.size)
        assertEquals("A", targets[0].id)
        assertEquals("page", targets[0].type)
        assertEquals("Example", targets[0].title)
        assertEquals("https://example.com", targets[0].url)
        assertEquals("ws://127.0.0.1:9222/devtools/page/A", targets[0].webSocketDebuggerUrl)
    }

    @Test
    fun `parseDevToolsTargets returns empty on garbage`() {
        assertTrue(parseDevToolsTargets("").isEmpty())
        assertTrue(parseDevToolsTargets("not json").isEmpty())
        assertTrue(parseDevToolsTargets("[]").isEmpty())
        assertTrue(parseDevToolsTargets("{}").isEmpty())
    }

    @Test
    fun `parseDevToolsTargets drops targets missing id or socket url`() {
        assertTrue(parseDevToolsTargets("[{\"id\":\"A\"}]").isEmpty())
        assertTrue(parseDevToolsTargets("[{\"webSocketDebuggerUrl\":\"ws://x\"}]").isEmpty())
    }

    @Test
    fun `parseDevToolsTargets handles escaped quotes in title`() {
        val json = "[{\"id\":\"A\",\"type\":\"page\",\"title\":\"Say \\\"hi\\\"\",\"url\":\"https://x.com\"," +
            "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/page/A\"}]"
        assertEquals("Say \"hi\"", parseDevToolsTargets(json)[0].title)
    }

    @Test
    fun `selectWebViewPageTarget prefers exact preferredUrl match`() {
        val targets = parseDevToolsTargets(sampleTargetsJson())
        val selected = selectWebViewPageTarget(targets, "https://example.com")
        assertEquals("A", selected?.id)
    }

    @Test
    fun `selectWebViewPageTarget falls back to host match`() {
        val targets = parseDevToolsTargets(sampleTargetsJson())
        val selected = selectWebViewPageTarget(targets, "https://example.com/other/page")
        assertEquals("A", selected?.id)
    }

    @Test
    fun `selectWebViewPageTarget skips devtools frontend`() {
        val targets = parseDevToolsTargets(sampleTargetsJson())
        val selected = selectWebViewPageTarget(targets, null)
        assertEquals("A", selected?.id)
    }

    @Test
    fun `selectWebViewPageTarget skips blank page unless it is the only choice`() {
        val blankOnly = parseDevToolsTargets(
            "[{\"id\":\"C\",\"type\":\"page\",\"title\":\"Blank\",\"url\":\"about:blank\"," +
                "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/page/C\"}]",
        )
        assertEquals("C", selectWebViewPageTarget(blankOnly, null)?.id)
    }

    @Test
    fun `selectWebViewPageTarget returns null when no pages`() {
        assertNull(selectWebViewPageTarget(emptyList(), null))
        val nonPages = parseDevToolsTargets(
            "[{\"id\":\"W\",\"type\":\"worker\",\"title\":\"w\",\"url\":\"https://x.com\"," +
                "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/page/W\"}]",
        )
        assertNull(selectWebViewPageTarget(nonPages, null))
    }

    @Test
    fun `rewriteDevToolsWsUrl swaps port keeping path`() {
        assertEquals(
            "ws://127.0.0.1:9223/devtools/page/A",
            rewriteDevToolsWsUrl("ws://127.0.0.1:9222/devtools/page/A"),
        )
        assertEquals(
            "ws://127.0.0.1:9223/devtools/page/A",
            rewriteDevToolsWsUrl("ws://127.0.0.1:9222/devtools/page/A", 9223),
        )
    }

    @Test
    fun `rewriteDevToolsWsUrl leaves foreign urls untouched`() {
        val foreign = "ws://192.168.1.5:9999/devtools/page/A"
        assertEquals(foreign, rewriteDevToolsWsUrl(foreign))
    }

    @Test
    fun `devToolsSocketName format`() {
        assertEquals("webview_devtools_remote_1234", devToolsSocketName(1234))
    }

    @Test
    fun `isFresh boundary checks`() {
        assertTrue(isFresh(1000, 2000, 5000))
        assertTrue(isFresh(2000, 2000, 5000))
        assertFalse(isFresh(1000, 9000, 5000))
        assertFalse(isFresh(0, 2000, 5000))
        assertFalse(isFresh(9000, 2000, 5000))
    }

    @Test
    fun `attach state json round trip`() {
        val state = AgentBrowserAttachState(
            webSocketDebuggerUrl = "ws://127.0.0.1:9223/devtools/page/A",
            tabUrl = "https://example.com",
            tabTitle = "Example \"quoted\"",
            attachedAt = 1234567890123L,
        )
        assertEquals(state, AgentBrowserAttachState.fromJson(state.toJson()))
    }

    @Test
    fun `attach state fromJson rejects blank socket url`() {
        assertNull(AgentBrowserAttachState.fromJson("{\"tabUrl\":\"https://x.com\"}"))
        assertNull(AgentBrowserAttachState.fromJson("not json"))
    }

    @Test
    fun `webview target info json round trip`() {
        val info = WebViewTargetInfo(
            devtoolsSocket = "webview_devtools_remote_42",
            webSocketDebuggerUrl = "ws://127.0.0.1:9222/devtools/page/A",
            tabUrl = "https://example.com/a?b=c",
            tabTitle = "Back\\slash",
            discoveredAt = 1234567890123L,
        )
        assertEquals(info, WebViewTargetInfo.fromJson(info.toJson()))
    }

    @Test
    fun `readAttachState returns null for missing file`() {
        assertNull(readAgentBrowserAttachState(tmp.root))
    }

    @Test
    fun `readAttachState returns fresh state`() {
        val now = System.currentTimeMillis()
        val state = AgentBrowserAttachState("ws://127.0.0.1:9223/devtools/page/A", "https://a.com", "A", now)
        tmp.root.resolve(AGENT_BROWSER_ATTACH_FILE).writeText(state.toJson())
        assertEquals(state, readAgentBrowserAttachState(tmp.root, now))
    }

    @Test
    fun `readAttachState deletes stale state and returns null`() {
        val now = System.currentTimeMillis()
        val stale = AgentBrowserAttachState(
            "ws://127.0.0.1:9223/devtools/page/A",
            "https://a.com",
            "A",
            now - AGENT_BROWSER_ATTACH_MAX_AGE_MS - 1000,
        )
        val file = tmp.root.resolve(AGENT_BROWSER_ATTACH_FILE)
        file.writeText(stale.toJson())
        assertNull(readAgentBrowserAttachState(tmp.root, now))
        assertFalse(file.exists())
    }

    @Test
    fun `readAttachState rejects corrupt file`() {
        tmp.root.resolve(AGENT_BROWSER_ATTACH_FILE).writeText("{broken")
        assertNull(readAgentBrowserAttachState(tmp.root))
    }

    @Test
    fun `clearAgentBrowserAttachState removes the file`() {
        val file = tmp.root.resolve(AGENT_BROWSER_ATTACH_FILE)
        file.writeText("x")
        clearAgentBrowserAttachState(tmp.root)
        assertFalse(file.exists())
        clearAgentBrowserAttachState(tmp.root)
    }
}
