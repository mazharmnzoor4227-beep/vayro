package com.vayro.app.context

import kotlin.math.ceil
import kotlin.math.max

/**
 * Milestone 4 — model-aware token counting.
 *
 * A [ModelTokenizer] counts tokens the way a specific model would. Where a
 * model-specific tokenizer is unavailable, [ApproximateTokenizer] is used and
 * is honestly labelled as an approximation (it must never be presented as an
 * exact model token count).
 */
interface ModelTokenizer {
    /** Human-readable name, e.g. "approximate-4-chars" or "gpt-4o-tiktoken". */
    val name: String

    /** True only for a tokenizer that matches a real model's tokenization. */
    val isModelAccurate: Boolean

    /** Deterministic token count for [text]. Must never throw. */
    fun countTokens(text: String): Int
}

/**
 * Deterministic fallback tokenizer: ceil(chars / 4), minimum 1 token for
 * non-empty text. Labelled approximate — never claim model accuracy.
 */
class ApproximateTokenizer : ModelTokenizer {
    override val name: String = "approximate-4-chars"
    override val isModelAccurate: Boolean = false

    override fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return max(1, ceil(text.length / 4.0).toInt())
    }
}

