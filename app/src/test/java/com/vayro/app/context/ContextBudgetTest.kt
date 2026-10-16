package com.vayro.app.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 4 — measurable context budget tests.
 */
class ContextBudgetTest {

    private val tokenizer = ApproximateTokenizer()

    @Test
    fun `approximate tokenizer is deterministic and honest about accuracy`() {
        assertEquals(0, tokenizer.countTokens(""))
        assertEquals(1, tokenizer.countTokens("a"))
        assertEquals(25, tokenizer.countTokens("x".repeat(100)))
        assertFalse(tokenizer.isModelAccurate)
        assertEquals(tokenizer.countTokens("hello world"), tokenizer.countTokens("hello world"))
    }

    @Test
    fun `within budget keeps every section and reports exact tokens`() {
        val budget = ContextBudget(maxTokens = 1000, tokenizer = tokenizer)
        val sections = listOf(
            BudgetSection("a", "x".repeat(100), priority = 1),
            BudgetSection("b", "y".repeat(100), priority = 2),
        )
        val result = budget.enforce(sections)
        assertEquals(2, result.sections.size)
        assertTrue(result.report.withinBudget)
        assertEquals(50, result.report.tokensUsed) // 200 chars / 4
        assertTrue(result.report.sectionsDropped.isEmpty())
        assertTrue(result.report.sectionsTruncated.isEmpty())
    }

    @Test
    fun `over budget truncates lowest priority first and stays within budget`() {
        val budget = ContextBudget(maxTokens = 60, tokenizer = tokenizer)
        val sections = listOf(
            BudgetSection("important", "a".repeat(120), priority = 1), // 30 tokens
            BudgetSection("less_important", "b".repeat(200), priority = 2), // 50 tokens
        )
        val result = budget.enforce(sections)
        assertTrue(result.report.withinBudget)
        assertTrue(result.report.tokensUsed <= 60)
        // High-priority section survives (possibly whole); low-priority truncated or dropped.
        assertTrue(result.report.sectionsKept.contains("important"))
        assertTrue(
            result.report.sectionsTruncated.contains("less_important") ||
                result.report.sectionsDropped.contains("less_important")
        )
    }

    @Test
    fun `non-truncatable sections are never truncated or dropped`() {
        val budget = ContextBudget(maxTokens = 20, tokenizer = tokenizer)
        val sections = listOf(
            BudgetSection("critical", "c".repeat(40), priority = 1, truncatable = false), // 10 tokens
            BudgetSection("filler", "d".repeat(400), priority = 2), // 100 tokens
        )
        val result = budget.enforce(sections)
        assertTrue(result.report.sectionsKept.contains("critical"))
        assertFalse(result.report.sectionsTruncated.contains("critical"))
        assertFalse(result.report.sectionsDropped.contains("critical"))
        val critical = result.sections.first { it.name == "critical" }
        assertEquals("c".repeat(40), critical.content)
    }

    @Test
    fun `budget report names the tokenizer and its accuracy honestly`() {
        val budget = ContextBudget(maxTokens = 100, tokenizer = tokenizer)
        val result = budget.enforce(listOf(BudgetSection("s", "hello", priority = 1)))
        assertEquals("approximate-4-chars", result.report.tokenizerName)
        assertFalse(result.report.tokenizerIsModelAccurate)
        assertEquals(100, result.report.maxTokens)
    }

    @Test
    fun `empty sections produce a zero-token report`() {
        val budget = ContextBudget(maxTokens = 100, tokenizer = tokenizer)
        val result = budget.enforce(emptyList())
        assertEquals(0, result.report.tokensUsed)
        assertTrue(result.report.withinBudget)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero max tokens is rejected`() {
        ContextBudget(maxTokens = 0, tokenizer = tokenizer)
    }
}
