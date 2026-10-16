package com.vayro.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfHealingLoopExpandedTest {

    @Test
    fun `diagnoses compilation error and extracts line coordinates`() {
        val loop = SelfHealingLoop(maxIterations = 3)
        val log = """
            e: src/services/UserService.kt:42:15 Unresolved reference 'calculateDiscount'.
        """.trimIndent()

        val diag = loop.recordFailure(log)
        assertEquals(ErrorType.COMPILATION_ERROR, diag.type)
        assertTrue(diag.affectedFiles.contains("src/services/UserService.kt"))
        assertEquals(42, diag.lineCoordinates["src/services/UserService.kt"])
        assertTrue(diag.suggestedFix.contains("missing imports") || diag.suggestedFix.contains("types"))
    }

    @Test
    fun `diagnoses test failure and generates actionable repair prompt`() {
        val loop = SelfHealingLoop(maxIterations = 3)
        val log = """
            org.junit.ComparisonFailure: expected:<[100]> but was:<[80]>
                at com.example.OrderTest.verifyTotal(OrderTest.kt:25)
        """.trimIndent()

        val diag = loop.recordFailure(log)
        assertEquals(ErrorType.TEST_FAILURE, diag.type)
        assertTrue(diag.affectedFiles.contains("OrderTest.kt"))

        val prompt = loop.buildRepairPrompt(diag, 1, 3)
        assertTrue(prompt.contains("TEST_FAILURE"))
        assertTrue(prompt.contains("Iteration 1 of 3"))
        assertTrue(prompt.contains("OrderTest.kt"))
    }

    @Test
    fun `diagnoses syntax error and missing dependency`() {
        val loop = SelfHealingLoop(maxIterations = 3)

        val syntaxLog = "e: src/Parser.kt:10:5 Syntax error: Expecting ')' before '{'"
        val syntaxDiag = loop.diagnose(syntaxLog)
        assertEquals(ErrorType.SYNTAX_ERROR, syntaxDiag.type)
        assertTrue(syntaxDiag.affectedFiles.contains("src/Parser.kt"))

        val depLog = "Could not resolve com.squareup.retrofit2:retrofit:2.9.0"
        val depDiag = loop.diagnose(depLog)
        assertEquals(ErrorType.MISSING_DEPENDENCY, depDiag.type)
    }

    @Test
    fun `diagnoses runtime crash and build timeout`() {
        val loop = SelfHealingLoop(maxIterations = 3)

        val crashLog = "java.lang.NullPointerException: Attempt to invoke virtual method on a null object reference"
        val crashDiag = loop.diagnose(crashLog)
        assertEquals(ErrorType.RUNTIME_CRASH, crashDiag.type)

        val timeoutLog = "Execution timed out after 300 seconds"
        val timeoutDiag = loop.diagnose(timeoutLog)
        assertEquals(ErrorType.BUILD_TIMEOUT, timeoutDiag.type)
    }
}
