package com.vayro.app.runtime

/**
 * v3.2.49 — decides whether a failed session's reason is also shown as a
 * plain chat message.
 *
 * History: v3.2.47 added this for one DeepSeek case (a harness-repair
 * failure carried its full plain explanation as the SessionFailed reason
 * and was appended to the chat verbatim, keyed on
 * [DshCompatibility.REPAIR_FAILED_PREFIX]). The owner's v3.2.48 recording
 * proved the same hole for every other bridge: an Antigravity turn ran
 * 54 s and ended "Task stopped", and the actual reason — the bridge's own
 * "Eligibility check failed: Your current account is not eligible for
 * Antigravity…" — existed only as an expanded Activity item; the chat
 * showed nothing (and the toast sanitizer returned null for it).
 *
 * The rule now applies to EVERY bridge, because all of them report
 * through the same RuntimeEvent.SessionFailed: when a session ends in
 * failure with a substantive reason, the chat gains exactly one
 * assistant message carrying the provider's own words verbatim — no
 * jargon added, nothing rewritten. The Activity sheet keeps its
 * technical lines exactly as before.
 *
 * Safety against retry loops: this decision runs at the terminal point
 * of session handling, AFTER the key-rotation / account-failover /
 * transient-recovery attempts have already declined for the event, and
 * the returned text is appended as UI state only — it is never re-fed
 * as an event reason, so RuntimeFailureClassifier / isApiKeyFailure
 * never re-classify it. (Verified per pattern in
 * FailureReasonSurfacingTest: the eligibility text matches neither the
 * transient-network list nor the API-key list; quota/auth-shaped
 * reasons only ever reach this point once rotation has declined.)
 */
object FailureReasonSurfacing {

    /** Stops the user caused themselves — never surfaced as an error message. */
    private val USER_STOP_MARKERS = listOf("stopped by user")

    /**
     * A reason shorter than this is never treated as "already shown" by
     * containment: short generic strings could appear inside an
     * unrelated assistant message by coincidence.
     */
    private const val MIN_DISTINCT_LENGTH = 24

    /**
     * Returns the chat message text to append for a failed session, or
     * null when nothing should be appended:
     * - the reason is blank,
     * - the stop was user-initiated,
     * - the reason is already visible in this turn's assistant messages
     *   (exact text, or fully contained in one of them when it is long
     *   enough to be distinctive).
     *
     * [shownAssistantTexts] are the assistant message texts of the
     * current turn (after the last user message), in order.
     */
    fun messageToSurface(reason: String, shownAssistantTexts: List<String>): String? {
        val text = reason.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        if (USER_STOP_MARKERS.any { it in lower }) return null
        val shown = shownAssistantTexts.map { it.trim() }.filter { it.isNotEmpty() }
        if (shown.any { it == text }) return null
        if (text.length >= MIN_DISTINCT_LENGTH && shown.any { it.contains(text) }) return null
        return text
    }
}
