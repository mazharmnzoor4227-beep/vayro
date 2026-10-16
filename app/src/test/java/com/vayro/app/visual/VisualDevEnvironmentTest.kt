package com.vayro.app.visual

import android.graphics.Bitmap
import com.vayro.app.model.ChatAttachment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class VisualDevEnvironmentTest {

    @Test
    fun localhostPortManagerDetectsFrameworkHeuristics() {
        assertEquals("Next.js / React (3000)", LocalhostPortManager.guessService(3000))
        assertEquals("Vite / Vue / Svelte (5173)", LocalhostPortManager.guessService(5173))
        assertEquals("Vue / Webpack / Spring (8080)", LocalhostPortManager.guessService(8080))
        assertEquals("Django / FastAPI (8000)", LocalhostPortManager.guessService(8000))
        assertEquals("Flask / Python (5000)", LocalhostPortManager.guessService(5000))
        assertEquals("Angular CLI (4200)", LocalhostPortManager.guessService(4200))
        assertEquals("Metro Bundler / RN (8081)", LocalhostPortManager.guessService(8081))
        assertEquals("Chrome DevTools (9222)", LocalhostPortManager.guessService(9222))

        // Header overrides
        assertEquals("Vite Dev Server", LocalhostPortManager.guessService(5173, "vite v5.0"))
        assertEquals("Flask Dev Server", LocalhostPortManager.guessService(5000, "Werkzeug/3.0.1 Python/3.11"))
        assertEquals("FastAPI / Uvicorn", LocalhostPortManager.guessService(8000, "uvicorn"))
    }

    @Test
    fun localhostPortManagerProbesListeningSocket() {
        val server = ServerSocket(0) // bind to random ephemeral port
        val port = server.localPort
        try {
            val manager = LocalhostPortManager(defaultTimeoutMs = 300)
            val info = manager.probePort(port)
            assertNotNull("Port $port should be probed as listening", info)
            assertTrue(info!!.isListening)
            assertEquals(port, info.port)
            assertTrue(info.url.contains(":$port"))
            assertTrue(info.responseTimeMs >= 0L)
        } finally {
            server.close()
        }
    }

    @Test
    fun domInspectorEngineGeneratesValidScripts() {
        val activeScript = DomInspectorEngine.buildInspectorScript(true)
        assertTrue(activeScript.contains("__mh_inspector_overlay"))
        assertTrue(activeScript.contains("MH_DOM_ELEMENT:"))
        assertTrue(activeScript.contains("getComputedStyles"))

        val inactiveScript = DomInspectorEngine.buildInspectorScript(false)
        assertTrue(inactiveScript.contains("window.__mh_inspect_active = false"))

        val diagnosticsScript = DomInspectorEngine.buildDiagnosticsInterceptorScript()
        assertTrue(diagnosticsScript.contains("MH_CONSOLE:"))
        assertTrue(diagnosticsScript.contains("MH_NETWORK:"))
        assertTrue(diagnosticsScript.contains("window.fetch"))
        assertTrue(diagnosticsScript.contains("XMLHttpRequest"))
    }

    @Test
    fun domInspectorEngineParsesStructuredMessages() {
        // DOM element parsing
        val rawDom = """MH_DOM_ELEMENT:{"tag":"button","id":"pay-btn","classes":["btn","btn-primary"],"selector":"div > button#pay-btn","innerText":"Checkout Now","attributes":{"type":"submit"},"computedStyles":{"display":"block","color":"rgb(255, 255, 255)"},"rect":{"left":20.0,"top":150.0,"width":200.0,"height":44.0}}"""
        val dom = DomInspectorEngine.parseDomElementMessage(rawDom)
        assertNotNull(dom)
        assertEquals("button", dom!!.tag)
        assertEquals("pay-btn", dom.id)
        assertEquals(listOf("btn", "btn-primary"), dom.classes)
        assertEquals("div > button#pay-btn", dom.selector)
        assertEquals("Checkout Now", dom.innerText)
        assertEquals("submit", dom.attributes["type"])
        assertEquals("block", dom.computedStyles["display"])
        assertEquals("<button#pay-btn.btn.btn-primary>", dom.displayName)
        assertEquals(20.0f, dom.rect.left, 0.01f)
        assertEquals(200.0f, dom.rect.width, 0.01f)

        // Console log parsing
        val rawConsole = """MH_CONSOLE:{"level":"ERROR","message":"Uncaught TypeError: null is not an object","source":"app.js","lineNumber":42,"timestamp":1700000000}"""
        val consoleLog = DomInspectorEngine.parseConsoleMessage(rawConsole)
        assertNotNull(consoleLog)
        assertEquals(LogLevel.ERROR, consoleLog!!.level)
        assertEquals("Uncaught TypeError: null is not an object", consoleLog.message)
        assertEquals("app.js", consoleLog.source)
        assertEquals(42, consoleLog.lineNumber)

        // Network error parsing
        val rawNet = """MH_NETWORK:{"method":"POST","url":"/api/pay","statusCode":500,"statusText":"Internal Server Error","durationMs":45,"isFailed":true,"timestamp":1700000000}"""
        val netLog = DomInspectorEngine.parseNetworkMessage(rawNet)
        assertNotNull(netLog)
        assertEquals("POST", netLog!!.method)
        assertEquals("/api/pay", netLog.url)
        assertEquals(500, netLog.statusCode)
        assertTrue(netLog.isFailed)
        assertEquals(45L, netLog.durationMs)
    }

    @Test
    fun aiVisualDebuggerGeneratesStructuredReport() {
        val dom = DomElementInfo(
            tag = "button",
            id = "submit-btn",
            classes = listOf("primary"),
            selector = "form > button#submit-btn",
            innerText = "Submit Form",
            computedStyles = mapOf("display" to "block", "height" to "0px"),
            rect = RectFInfo(left = 10f, top = 20f, width = 100f, height = 0f),
        )
        val consoleLogs = listOf(
            ConsoleLogEntry(level = LogLevel.ERROR, message = "Form submission crashed", source = "bundle.js", lineNumber = 10),
            ConsoleLogEntry(level = LogLevel.INFO, message = "Form initialized"),
        )
        val netLogs = listOf(
            NetworkLogEntry(method = "POST", url = "/api/v1/submit", statusCode = 404, statusText = "Not Found", isFailed = true),
        )
        val snapshot = VisualSnapshot(
            title = "Broken Button",
            url = "http://localhost:3000",
            screenshotPath = ".mobile_harness/visual/screenshots/snap1.png",
        )

        val report = AiVisualDebugger.generateBugReportPrompt(
            url = "http://localhost:3000",
            viewport = ViewportPreset.PIXEL_8,
            selectedElement = dom,
            consoleLogs = consoleLogs,
            networkLogs = netLogs,
            snapshot = snapshot,
            userNotes = "Button rendered with 0 height and 404 on click",
        )

        assertTrue(report.contains("<visual_bug_report>"))
        assertTrue(report.contains("Target URL: http://localhost:3000"))
        assertTrue(report.contains("Pixel 8"))
        assertTrue(report.contains("<button#submit-btn.primary>"))
        assertTrue(report.contains("form > button#submit-btn"))
        assertTrue(report.contains("height: 0px"))
        assertTrue(report.contains("[ERROR] Form submission crashed (bundle.js:10)"))
        assertFalse("Should filter out INFO logs from error section", report.contains("Form initialized"))
        assertTrue(report.contains("[404] POST /api/v1/submit"))
        assertTrue(report.contains(".mobile_harness/visual/screenshots/snap1.png"))
        assertTrue(report.contains("Button rendered with 0 height and 404 on click"))
        assertTrue(report.contains("</visual_bug_report>"))
    }

    @Test
    fun visualCaptureEngineSavesSnapshotAndComputesDiff() = runBlocking {
        val tempDir = Files.createTempDirectory("visual-test").toFile()
        try {
            // Null bitmaps diff is 100%
            val diffNull = VisualCaptureEngine.computePixelDiffPercentage(null, null)
            assertEquals(100.0, diffNull, 0.001)

            // Save snapshot
            val snapshot = VisualCaptureEngine.saveSnapshot(
                projectRoot = tempDir,
                bitmap = null,
                title = "Red Screen",
                url = "http://localhost:5173",
                consoleErrorsCount = 1,
                networkErrorsCount = 0,
            )

            assertNotNull(snapshot)
            assertEquals("Red Screen", snapshot.title)
            assertEquals("http://localhost:5173", snapshot.url)
            assertTrue(snapshot.screenshotPath.startsWith(".mobile_harness/visual/screenshots/"))
            val savedFile = File(tempDir, snapshot.screenshotPath)
            assertTrue("Saved PNG must exist on disk", savedFile.exists() && savedFile.isFile)
            assertTrue("Saved PNG size must be greater than 0", savedFile.length() > 0)

            // Chat attachment creation
            val attachment = AiVisualDebugger.createChatAttachment(snapshot, tempDir)
            assertNotNull(attachment)
            assertEquals("image/png", attachment!!.mimeType)
            assertEquals(savedFile.name, attachment.displayName)
            assertEquals(savedFile.length(), attachment.sizeBytes)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun visualDevModelsJsonRoundtrip() {
        val dom = DomElementInfo(
            tag = "div",
            id = "hero",
            classes = listOf("flex", "items-center"),
            selector = "#hero",
            xpath = "/html/body/div",
            innerText = "Hello World",
            attributes = mapOf("data-testid" to "hero-banner"),
            computedStyles = mapOf("color" to "white", "font-size" to "24px"),
            rect = RectFInfo(left = 0f, top = 50f, width = 360f, height = 200f),
        )
        val domJson = dom.toJson()
        val restoredDom = DomElementInfo.fromJson(domJson)
        assertEquals(dom.tag, restoredDom.tag)
        assertEquals(dom.id, restoredDom.id)
        assertEquals(dom.classes, restoredDom.classes)
        assertEquals(dom.selector, restoredDom.selector)
        assertEquals(dom.innerText, restoredDom.innerText)
        assertEquals(dom.attributes, restoredDom.attributes)
        assertEquals(dom.computedStyles, restoredDom.computedStyles)
        assertEquals(dom.rect.width, restoredDom.rect.width, 0.01f)

        val log = ConsoleLogEntry(level = LogLevel.WARN, message = "Warning text", source = "index.ts", lineNumber = 15)
        val logRestored = ConsoleLogEntry.fromJson(log.toJson())
        assertEquals(log.level, logRestored.level)
        assertEquals(log.message, logRestored.message)
        assertEquals(log.source, logRestored.source)
        assertEquals(log.lineNumber, logRestored.lineNumber)

        val net = NetworkLogEntry(method = "GET", url = "/api/data", statusCode = 200, durationMs = 12, isFailed = false)
        val netRestored = NetworkLogEntry.fromJson(net.toJson())
        assertEquals(net.method, netRestored.method)
        assertEquals(net.url, netRestored.url)
        assertEquals(net.statusCode, netRestored.statusCode)
        assertEquals(net.isFailed, netRestored.isFailed)

        val port = LocalhostPortInfo(port = 5173, serviceGuess = "Vite Dev Server", isListening = true, responseTimeMs = 15)
        val portRestored = LocalhostPortInfo.fromJson(port.toJson())
        assertEquals(port.port, portRestored.port)
        assertEquals(port.serviceGuess, portRestored.serviceGuess)
        assertEquals(port.isListening, portRestored.isListening)

        val snap = VisualSnapshot(title = "Baseline", url = "http://localhost:3000", screenshotPath = "path/to/img.png", consoleErrorsCount = 2)
        val snapRestored = VisualSnapshot.fromJson(snap.toJson())
        assertEquals(snap.title, snapRestored.title)
        assertEquals(snap.url, snapRestored.url)
        assertEquals(snap.screenshotPath, snapRestored.screenshotPath)
        assertEquals(snap.consoleErrorsCount, snapRestored.consoleErrorsCount)
    }
}
