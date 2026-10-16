package com.vayro.app.lsp

import com.vayro.app.runtime.WorkspaceSemanticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LspManagerTest {

    @Test
    fun `server status honestly distinguishes real server from built-in fallback`() {
        val manager = LspManager()
        // Milestone 0: no real server is running at init — only the fallback.
        // isRunning must never be true when only the fallback is active.
        val status = manager.getServerStatus("Kotlin")
        assertFalse(status.isRunning)
        assertFalse(status.isRealServer)
        assertTrue(status.isBuiltInFallback)

        manager.stopServer("Kotlin")
        val stoppedStatus = manager.getServerStatus("Kotlin")
        assertFalse(stoppedStatus.isRunning)
        assertFalse(stoppedStatus.isRealServer)

        // No language-server binary is installed in the test environment, so
        // startServer must honestly return false and keep isRunning=false.
        val started = manager.startServer("Kotlin", "/workspace")
        assertFalse(started)
        val restartedStatus = manager.getServerStatus("Kotlin")
        assertFalse(restartedStatus.isRunning)
        assertFalse(restartedStatus.isRealServer)
        assertTrue(restartedStatus.isBuiltInFallback)
    }

    @Test
    fun `requestHover returns formatted markdown docs for symbols`() {
        val index = WorkspaceSemanticIndex(null)
        val lsp = LspManager(workspaceDir = null, semanticIndex = index)

        val code = """
            package com.example

            /**
             * Primary service for processing orders.
             */
            class OrderService {
                fun execute() {}
            }
        """.trimIndent()

        index.updateFile("src/OrderService.kt", code)

        // Line 6 is "class OrderService {"
        val hover = lsp.requestHover("Kotlin", "src/OrderService.kt", 6, 10, code)
        assertNotNull(hover)
        assertTrue(hover!!.contents.contains("OrderService"))
        assertTrue(hover.contents.contains("Primary service for processing orders"))
    }

    @Test
    fun `requestCodeActions only offers actions with a real appliable edit`() {
        val index = WorkspaceSemanticIndex(null)
        val lsp = LspManager(workspaceDir = null, semanticIndex = index)

        // Unclosed delimiter: no single correct insertion point, so no
        // action is offered (a guessed edit would corrupt nested code).
        val brokenCode = """
            fun broken() {
                val x = 10
        """.trimIndent()
        val noActions = lsp.requestCodeActions("Kotlin", "src/Broken.kt", 2, 1, brokenCode)
        assertTrue(noActions.none { it.kind.startsWith("refactor") })
        assertTrue(noActions.none { it.title.contains("closing delimiter") })
        assertTrue(noActions.all { it.newText != null })

        // Duplicate import: the fix deletes the duplicate (2nd) line.
        val dupCode = "import foo.Bar\nimport foo.Bar\nval x = 1"
        val dupActions = lsp.requestCodeActions("Kotlin", "src/Dup.kt", 2, 1, dupCode)
        assertEquals(1, dupActions.size)
        assertEquals("import foo.Bar\nval x = 1", dupActions[0].newText)
        assertEquals("src/Dup.kt", dupActions[0].targetFile)

        // Empty catch: the fix rethrows the caught variable.
        val catchCode = "try { risky() } catch (e: Exception) {}"
        val catchActions = lsp.requestCodeActions("Kotlin", "src/Catch.kt", 1, 1, catchCode)
        assertEquals(1, catchActions.size)
        assertTrue(catchActions[0].newText!!.contains("throw e"))
        assertTrue(catchActions[0].newText!!.contains("catch (e: Exception)"))

        // No interactive Extract actions: they cannot apply with one tap.
        assertTrue(dupActions.none { it.kind.startsWith("refactor") })
        assertTrue(catchActions.none { it.kind.startsWith("refactor") })
    }

    @Test
    fun `requestCompletions returns symbols and keywords`() {
        val index = WorkspaceSemanticIndex(null)
        val lsp = LspManager(workspaceDir = null, semanticIndex = index)

        index.updateFile("src/Utils.kt", "class MathUtils { fun add() {} }")

        val code = "val x = Math"
        val completions = lsp.requestCompletions("Kotlin", "src/Test.kt", 1, 12, code)
        assertTrue(completions.isNotEmpty())
        assertTrue(completions.any { it.label.contains("MathUtils") })
    }
}
