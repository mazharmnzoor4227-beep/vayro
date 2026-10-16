package com.vayro.app.context

/**
 * Milestone 4 — measurable context budget.
 *
 * A budget is enforced over named sections ordered by priority (lowest
 * priority number = most important). Enforcement is measurable: every call
 * returns a [BudgetReport] with the exact token counts, so callers and tests
 * can prove the budget was never exceeded.
 */
data class BudgetSection(
    val name: String,
    val content: String,
    /** Lower number = higher priority; kept first when the budget is tight. */
    val priority: Int,
    /** If false, the section is never truncated or dropped. */
    val truncatable: Boolean = true,
)

data class BudgetReport(
    val tokenizerName: String,
    val tokenizerIsModelAccurate: Boolean,
    val maxTokens: Int,
    val tokensUsed: Int,
    val sectionsKept: List<String>,
    val sectionsTruncated: List<String>,
    val sectionsDropped: List<String>,
) {
    val withinBudget: Boolean get() = tokensUsed <= maxTokens
}

data class BudgetEnforcementResult(
    val sections: List<BudgetSection>,
    val report: BudgetReport,
)

class ContextBudget(
    val maxTokens: Int,
    val tokenizer: ModelTokenizer = ApproximateTokenizer(),
) {
    init {
        require(maxTokens > 0) { "maxTokens must be positive" }
    }

    /**
     * Enforces the budget over [sections]. Non-truncatable sections are always
     * kept whole; truncatable sections are trimmed from lowest priority upward,
     * then dropped entirely if still over budget. Returns the surviving
     * sections plus a measurable report.
     */
    fun enforce(sections: List<BudgetSection>): BudgetEnforcementResult {
        fun reportFor(
            kept: List<BudgetSection>,
            truncated: List<String>,
            dropped: List<String>,
        ): BudgetReport {
            val total = kept.sumOf { tokenizer.countTokens(it.content) }
            return BudgetReport(
                tokenizerName = tokenizer.name,
                tokenizerIsModelAccurate = tokenizer.isModelAccurate,
                maxTokens = maxTokens,
                tokensUsed = total,
                sectionsKept = kept.map { it.name },
                sectionsTruncated = truncated,
                sectionsDropped = dropped,
            )
        }

        if (sections.isEmpty()) {
            return BudgetEnforcementResult(emptyList(), reportFor(emptyList(), emptyList(), emptyList()))
        }

        val initialTotal = sections.sumOf { tokenizer.countTokens(it.content) }
        if (initialTotal <= maxTokens) {
            return BudgetEnforcementResult(sections, reportFor(sections, emptyList(), emptyList()))
        }

        // Over budget: work from lowest priority (highest number) upward.
        val kept = sections.toMutableList()
        val truncated = mutableListOf<String>()
        val dropped = mutableListOf<String>()

        for (section in sections.sortedByDescending { it.priority }) {
            if (kept.sumOf { tokenizer.countTokens(it.content) } <= maxTokens) break
            if (!section.truncatable) continue
            val idx = kept.indexOf(section)
            if (idx < 0) continue

            val sectionTokens = tokenizer.countTokens(section.content)
            val overBy = kept.sumOf { tokenizer.countTokens(it.content) } - maxTokens
            val keepTokens = (sectionTokens - overBy - RESERVE_TOKENS).coerceAtLeast(MIN_KEEP_TOKENS)
            if (keepTokens < sectionTokens && keepTokens >= MIN_KEEP_TOKENS) {
                val trimmed = section.content.take(keepTokens * CHARS_PER_TOKEN).trimEnd() +
                    "\n<!-- truncated by context budget -->"
                kept[idx] = section.copy(content = trimmed)
                truncated.add(section.name)
            } else {
                kept.removeAt(idx)
                dropped.add(section.name)
            }
        }

        // If non-truncatable sections alone exceed the budget, the report shows
        // withinBudget=false honestly instead of silently overflowing.
        val surviving = kept.sortedBy { it.priority }
        return BudgetEnforcementResult(surviving, reportFor(surviving, truncated, dropped))
    }

    companion object {
        private const val RESERVE_TOKENS = 16
        private const val MIN_KEEP_TOKENS = 8
        private const val CHARS_PER_TOKEN = 4
    }
}
