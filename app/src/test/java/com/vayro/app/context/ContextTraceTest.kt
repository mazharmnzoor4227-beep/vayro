package com.vayro.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 4 — context trace tests.
 */
class ContextTraceTest {

    @Test
    fun `trace records per-signal contributions and renders included versus excluded`() {
        val builder = ContextTraceBuilder()
        builder.record(TraceSignal.EXPLICIT_MENTION, "src/Main.kt", 1000, true, "explicitly mentioned in request")
        builder.record(TraceSignal.SYMBOL_GRAPH, "src/Main.kt", 250, true, "caller relation")
        builder.record(TraceSignal.KEYWORD, "src/Unrelated.kt", 0, false, "score 0: no signal fired; excluded")

        val trace = builder.build()
        assertEquals(setOf(TraceSignal.EXPLICIT_MENTION, TraceSignal.SYMBOL_GRAPH, TraceSignal.KEYWORD), trace.signalsUsed)
        assertEquals(listOf("src/Main.kt"), trace.includedPaths())
        assertEquals(listOf("src/Unrelated.kt"), trace.excludedPaths())
        assertEquals(1250, trace.totalScoreFor("src/Main.kt"))
        assertEquals(2, trace.entriesFor("src/Main.kt").size)

        val rendered = trace.renderText()
        assertTrue(rendered.contains("INCLUDED src/Main.kt"))
        assertTrue(rendered.contains("excluded src/Unrelated.kt"))
        assertTrue(rendered.contains("EXPLICIT_MENTION"))
    }

    @Test
    fun `empty trace renders without crashing`() {
        val trace = ContextTrace.empty()
        assertTrue(trace.entries.isEmpty())
        assertTrue(trace.includedPaths().isEmpty())
        assertTrue(trace.renderText().contains("Included files: 0"))
    }
}
