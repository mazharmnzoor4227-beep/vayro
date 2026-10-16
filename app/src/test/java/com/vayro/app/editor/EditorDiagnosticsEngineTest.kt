package com.vayro.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDiagnosticsEngineTest {

    @Test
    fun `clean document produces zero diagnostics`() {
        val cleanCode = """
            package com.example

            import java.io.File

            class Calculator {
                fun add(a: Int, b: Int): Int {
                    return a + b
                }
            }
        """.trimIndent()

        val diagnostics = EditorDiagnosticsEngine.analyzeDocument(cleanCode, "Calculator.kt")
        assertTrue("Clean code should have no diagnostics, found: $diagnostics", diagnostics.isEmpty())
    }

    @Test
    fun `detects unclosed brace and reports error`() {
        val brokenCode = """
            fun broken() {
                val x = 10
        """.trimIndent()

        val diagnostics = EditorDiagnosticsEngine.analyzeDocument(brokenCode, "Broken.kt")
        val error = diagnostics.firstOrNull { it.ruleId == "syntax.unclosed_delimiter" }
        assertTrue("Expected unclosed delimiter diagnostic", error != null)
        assertEquals(DiagnosticSeverity.ERROR, error!!.severity)
        assertEquals(1, error.lineNumber)
    }

    @Test
    fun `detects unexpected closing bracket and reports error`() {
        val brokenCode = """
            val arr = [1, 2, 3]
            val x = 10 }
        """.trimIndent()

        val diagnostics = EditorDiagnosticsEngine.analyzeDocument(brokenCode, "Test.kt")
        val error = diagnostics.firstOrNull { it.ruleId == "syntax.unmatched_brace" }
        assertTrue("Expected unmatched brace diagnostic", error != null)
        assertEquals(DiagnosticSeverity.ERROR, error!!.severity)
        assertEquals(2, error.lineNumber)
    }

    @Test
    fun `detects unclosed string literal`() {
        val codeWithUnclosedString = """
            val message = "Hello world
            val x = 42
        """.trimIndent()

        val diagnostics = EditorDiagnosticsEngine.analyzeDocument(codeWithUnclosedString, "Test.kt")
        val warning = diagnostics.firstOrNull { it.ruleId == "syntax.unclosed_string" }
        assertTrue("Expected unclosed string diagnostic", warning != null)
        assertEquals(1, warning!!.lineNumber)
        assertEquals(DiagnosticSeverity.WARNING, warning.severity)
    }

    @Test
    fun `detects unclosed multiline triple-quote string`() {
        val code = """
            val raw = ${"\"\"\""}
            line 1
            line 2
        """.trimIndent()

        val diagnostics = EditorDiagnosticsEngine.analyzeDocument(code, "Test.kt")
        val error = diagnostics.firstOrNull { it.ruleId == "syntax.unclosed_multiline_string" }
        assertTrue("Expected unclosed multiline string diagnostic", error != null)
        assertEquals(DiagnosticSeverity.ERROR, error!!.severity)
    }

    @Test
    fun `detects duplicate imports`() {
        val code = """
            import java.io.File
            import java.net.URL
            import java.io.File

            class App
        """.trimIndent()

        val diagnostics = EditorDiagnosticsEngine.analyzeDocument(code, "App.kt")
        val dup = diagnostics.firstOrNull { it.ruleId == "linter.duplicate_import" }
        assertTrue("Expected duplicate import warning", dup != null)
        assertEquals(DiagnosticSeverity.WARNING, dup!!.severity)
        assertEquals(3, dup.lineNumber)
    }

    @Test
    fun `detects empty catch blocks and TODO reminders`() {
        val code = """
            fun run() {
                try {
                    doWork()
                } catch (e: Exception) {}
                // TODO: finish refactoring
            }
        """.trimIndent()

        val diagnostics = EditorDiagnosticsEngine.analyzeDocument(code, "App.kt")
        val catchWarning = diagnostics.firstOrNull { it.ruleId == "linter.empty_catch" }
        assertTrue("Expected empty catch warning", catchWarning != null)

        val todoInfo = diagnostics.firstOrNull { it.ruleId == "linter.todo" }
        assertTrue("Expected todo item info", todoInfo != null)
        assertEquals(DiagnosticSeverity.INFO, todoInfo!!.severity)
    }
}
