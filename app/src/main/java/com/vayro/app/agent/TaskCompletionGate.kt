package com.vayro.app.agent

/**
 * v3.2.44 — Task Completion Gate.
 *
 * The workspace agent used to end task runs with a progress-style final
 * message ("this is done, that is done", "next I will render…") while the
 * actual deliverable was never produced. The DELIVERY_CONTRACT wording
 * alone did not stop it, so the ending is now gated MECHANICALLY: before
 * a workspace task run may end as completed, this gate checks
 *
 *  a. open plan steps (the approved roadmap's unfinished steps, plus any
 *     unchecked checklist items in the agent's own final message),
 *  b. promised artifacts — absolute /workspace/ paths the final message
 *     names must exist on disk and be non-empty (checked by the caller
 *     through [Input.pathExists]),
 *  c. unfinished-business language in the final message (the marker sets
 *     below — English + Roman Urdu/Hindi).
 *
 * The gate is pure logic (no Android types) so the whole decision table
 * is unit-tested. MainViewModel owns the consequences: auto-continue the
 * same run (up to [MAX_CONTINUATIONS] rounds), or end it honestly as
 * incomplete — never as "completed".
 */
object TaskCompletionGate {

    /** Auto-continue rounds allowed per task run before the cap ends it. */
    const val MAX_CONTINUATIONS = 10

    /** How many times the same declared blocker may repeat before the run ends incomplete-with-reason. */
    const val MAX_SAME_BLOCKER = 2

    /**
     * Strong unfinished markers — scanned over the WHOLE final message.
     * These phrases, in the agent's own voice, mean it stopped mid-task.
     * Keep every marker set in this one place (unit-tested).
     */
    val STRONG_UNFINISHED_MARKERS: List<String> = listOf(
        "standing by",
        "to be continued",
        "work in progress",
        "still in progress",
        "will now proceed",
        "let me know when",
        "agle step",
        "baqi hai",
        "baaki hai",
        "abhi baqi",
        "ab main karunga",
        "ab main kar dunga",
    )

    /**
     * Promise/future markers — scanned only in the closing portion of the
     * final message (the last [CLOSING_WINDOW_CHARS] chars), where the
     * agent speaks about its own next actions. Scanning only the tail
     * keeps delivered content (a document the user asked for, which may
     * legitimately contain "you will…") from tripping the gate.
     */
    val CLOSING_UNFINISHED_MARKERS: List<String> = listOf(
        "next i will",
        "next, i will",
        "i will now",
        "now let me",
        "let me now",
        "i'll now",
        "will proceed to",
        "next step is to",
        "the next step is",
        "i will create",
        "i will render",
        "i will generate",
        "i will build",
        "i will make",
        "ab main",
        "phir main",
        "us ke baad main",
    )

    /** The message admits the work was not finished (closing portion). */
    val FAILURE_DECLARATIONS: List<String> = listOf(
        "could not complete",
        "couldn't complete",
        "could not finish",
        "couldn't finish",
        "could not do it",
        "couldn't do it",
        "unable to complete",
        "unable to finish",
        "did not finish",
        "didn't finish",
        "not finished yet",
        "task is incomplete",
        "task incomplete",
        "mukammal nahi hua",
        "poora nahi hua",
        "nahi ho paya",
        "nahin ho paya",
    )

    /**
     * Concrete external blocker causes. A declared blocker only counts
     * when one of these concrete causes is named together with a stop
     * frame ([BLOCKER_FRAMES]) — a vague "I couldn't" never passes.
     */
    val BLOCKER_CAUSES: List<String> = listOf(
        "api key",
        "apikey",
        "api-key",
        "credential",
        "missing key",
        "permission denied",
        "unauthorized",
        "not authenticated",
        "command not found",
        "not installed",
        "rate limit",
        "quota exceeded",
        "out of credits",
        "insufficient credits",
        "no credits",
        "connection refused",
        "network error",
        "network is unreachable",
        "sign in required",
        "login required",
        "log in required",
    )

    /** Stop frames that turn a named cause into a declared blocker. */
    val BLOCKER_FRAMES: List<String> = listOf(
        "blocked",
        "cannot proceed",
        "can't proceed",
        "could not proceed",
        "couldn't proceed",
        "cannot continue",
        "can't continue",
        "cannot complete",
        "can't complete",
        "unable to",
        "i cannot",
        "i can't",
        "not possible",
        "ruk gaya",
        "ruk gayi",
        "nahi kar sakta",
        "nahin kar sakta",
    )

    /** Explicit blocker phrases that need no separate frame (closing portion). */
    val EXPLICIT_BLOCKER_PHRASES: List<String> = listOf(
        "api key is missing",
        "api key is required",
        "missing api key",
        "no api key",
        "key is missing",
        "permission denied",
        "command not found",
    )

    private const val CLOSING_WINDOW_CHARS = 800

    private val URL_REGEX = Regex("""https?://\S+""")

    /** Absolute guest-workspace paths (deliverables live under /workspace/). */
    private val PATH_REGEX = Regex("""(?<![\w./~-])(/workspace(?:/[\w.\-~%+]+)+)""")

    private val UNCHECKED_ITEM_REGEX = Regex(
        """^\s*(?:[-*+]|\d+[.)])\s+\[\s\]\s+(.+?)\s*$""",
        RegexOption.MULTILINE,
    )
    private val CHECKED_ITEM_REGEX = Regex(
        """^\s*(?:[-*+]|\d+[.)])\s+\[[xX]\]\s+(.+?)\s*$""",
        RegexOption.MULTILINE,
    )

    /** One plan step as the app tracks it: its title and whether it is done. */
    data class PlanStepState(val title: String, val done: Boolean)

    data class Input(
        /** True only for a real task run — never for a pure conversation turn. */
        val armed: Boolean,
        /** The agent's final message for this run (may be blank). */
        val finalText: String,
        /** Approved-roadmap steps still tracked by the app (SKIPPED steps already removed). */
        val planSteps: List<PlanStepState>,
        /** Resolves a referenced absolute path: true only if it exists and is a directory or a non-empty file. */
        val pathExists: (String) -> Boolean,
    )

    sealed interface Verdict {
        /** The run may end as completed. */
        object Complete : Verdict

        /** Not finished — auto-continue the same run with these reasons. */
        data class Continue(
            val openSteps: List<String>,
            val missingPaths: List<String>,
            val markerHits: List<String>,
            val endedOnFailureNote: Boolean,
        ) : Verdict

        /**
         * The agent declared a concrete external blocker. [signature] is
         * stable per cause so the caller can count repeats of the SAME
         * blocker across continuation rounds.
         */
        data class Blocked(val signature: String, val cause: String) : Verdict
    }

    /** Absolute /workspace/ paths named in [text] (URLs stripped first). */
    fun extractReferencedPaths(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val withoutUrls = URL_REGEX.replace(text, " ")
        return PATH_REGEX.findAll(withoutUrls)
            .map { it.groupValues[1].trimEnd('.', ',', ';', ':', ')', ']', '}', '`', '"', '\'') }
            .filter { it.length > "/workspace/".length }
            .distinct()
            .toList()
    }

    /** Titles of unchecked checklist items (`- [ ] …`) in [text]. */
    fun uncheckedChecklistItems(text: String): List<String> =
        UNCHECKED_ITEM_REGEX.findAll(text).map { cleanTitle(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()

    /** Normalized titles of checked checklist items (`- [x] …`) in [text]. */
    fun checkedChecklistTitles(text: String): Set<String> =
        CHECKED_ITEM_REGEX.findAll(text).map { normalizeTitle(cleanTitle(it.groupValues[1])) }.filter { it.isNotBlank() }.toSet()

    private fun cleanTitle(raw: String): String =
        raw.replace("**", "").replace("`", "").trim().take(140)

    private fun normalizeTitle(title: String): String =
        title.lowercase().replace(Regex("""[^a-z0-9 ]+"""), " ").replace(Regex("""\s+"""), " ").trim()

    /**
     * Returns the declared blocker's (signature, cause) when the final
     * message stops on a concrete external cause, or null. A stop frame
     * plus a named cause anywhere in the message counts; an explicit
     * blocker phrase in the closing portion counts on its own.
     */
    fun detectDeclaredBlocker(text: String): Pair<String, String>? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        val closing = lower.takeLast(CLOSING_WINDOW_CHARS)
        for (phrase in EXPLICIT_BLOCKER_PHRASES) {
            if (closing.contains(phrase)) {
                val cause = BLOCKER_CAUSES.firstOrNull { phrase.contains(it) } ?: phrase
                return cause to cause
            }
        }
        val hasFrame = BLOCKER_FRAMES.any { lower.contains(it) }
        if (!hasFrame) return null
        val cause = BLOCKER_CAUSES.firstOrNull { lower.contains(it) } ?: return null
        return cause to cause
    }

    fun evaluate(input: Input): Verdict {
        if (!input.armed) return Verdict.Complete
        val text = input.finalText.trim()

        val checkedTitles = checkedChecklistTitles(text)
        val openSteps = LinkedHashSet<String>()
        uncheckedChecklistItems(text).forEach { openSteps += it }
        input.planSteps.forEach { step ->
            if (!step.done && normalizeTitle(step.title) !in checkedTitles) {
                openSteps += step.title
            }
        }
        val openStepList = openSteps.toList()

        if (text.isBlank()) {
            return if (openStepList.isEmpty()) Verdict.Complete
            else Verdict.Continue(openStepList, emptyList(), emptyList(), endedOnFailureNote = false)
        }

        val missingPaths = extractReferencedPaths(text).filter { !input.pathExists(it) }

        val blocker = detectDeclaredBlocker(text)
        if (blocker != null && openStepList.isEmpty() && missingPaths.isEmpty()) {
            return Verdict.Blocked(signature = blocker.first, cause = blocker.second)
        }

        val lower = text.lowercase()
        val closing = lower.takeLast(CLOSING_WINDOW_CHARS)
        val markerHits = mutableListOf<String>()
        STRONG_UNFINISHED_MARKERS.forEach { if (lower.contains(it)) markerHits += it }
        CLOSING_UNFINISHED_MARKERS.forEach { if (closing.contains(it)) markerHits += it }
        val endedOnFailureNote = FAILURE_DECLARATIONS.any { closing.contains(it) }

        return if (openStepList.isEmpty() && missingPaths.isEmpty() && markerHits.isEmpty() && !endedOnFailureNote) {
            Verdict.Complete
        } else {
            Verdict.Continue(openStepList, missingPaths, markerHits.distinct(), endedOnFailureNote)
        }
    }

    /** The internal continuation instruction for a [Verdict.Continue]. Not a user-visible chat message. */
    fun buildContinuationInstruction(verdict: Verdict.Continue, originalTask: String): String = buildString {
        appendLine("<task_not_finished>")
        appendLine("Your last message ended the task BEFORE it was finished. This is an automatic continuation of the SAME task — do not restart, do not re-plan from zero, and do not answer with another progress report.")
        if (originalTask.isNotBlank()) {
            appendLine()
            appendLine("Original task: ${originalTask.take(600)}")
        }
        appendLine()
        appendLine("What is still missing:")
        verdict.openSteps.forEach { appendLine("- Open step you have not finished: $it") }
        verdict.missingPaths.forEach { appendLine("- This file does NOT exist yet (or is empty), although your message named it: $it") }
        if (verdict.markerHits.isNotEmpty()) {
            appendLine("- Your message ended with unfinished wording (${verdict.markerHits.joinToString(", ") { "\"$it\"" }}). A task turn must never end on a promise, a plan, or a progress note while work remains.")
        }
        if (verdict.endedOnFailureNote) {
            appendLine("- Your message said the work could not be finished, without finishing it. Keep working, or end with the exact concrete blocker (the missing key/credential, or the exact repeated tool error).")
        }
        appendLine()
        appendLine("Continue working NOW with your tools until the deliverable actually exists and you have verified it yourself (the file exists, is non-empty, and opens/parses/renders as far as this environment allows; a website is live at its URL).")
        appendLine("Your next final message must contain the deliverable itself: the absolute path of every file you produced (or the live https:// URL for a website). If you list the plan or a checklist, mark every finished step [x].")
        appendLine("</task_not_finished>")
    }

    /** The internal continuation instruction after a declared blocker. */
    fun buildBlockerContinuationInstruction(cause: String, originalTask: String): String = buildString {
        appendLine("<task_not_finished>")
        appendLine("Your last message stopped on a blocker: $cause.")
        appendLine("This is an automatic continuation of the SAME task. First try to work around the blocker now — another tool, another approach, a different source, or produce everything that does not depend on it.")
        if (originalTask.isNotBlank()) {
            appendLine()
            appendLine("Original task: ${originalTask.take(600)}")
        }
        appendLine()
        appendLine("If the blocker truly cannot be worked around, end with the exact same blocker stated plainly. The same blocker twice ends the task as incomplete, honestly — it never counts as finished.")
        appendLine("</task_not_finished>")
    }

    /** Short human summary of what is still missing (Activity list + session record). */
    fun buildMissingSummary(openSteps: List<String>, missingPaths: List<String>, blockerCause: String?): String {
        val parts = mutableListOf<String>()
        if (blockerCause != null) parts += "Blocked: $blockerCause"
        if (openSteps.isNotEmpty()) parts += "Open steps: ${openSteps.take(4).joinToString("; ")}"
        if (missingPaths.isNotEmpty()) parts += "Missing files: ${missingPaths.take(4).joinToString("; ")}"
        if (parts.isEmpty()) parts += "The agent stopped before delivering the result"
        return parts.joinToString(" · ")
    }

    /**
     * The visible, honest ending for a run the gate stopped at the cap or
     * on a repeated blocker. It never claims the task is done/completed.
     */
    fun buildIncompleteMessage(openSteps: List<String>, missingPaths: List<String>, blockerCause: String?): String = buildString {
        appendLine("Task not finished — the agent stopped before the deliverable was complete.")
        val items = mutableListOf<String>()
        if (blockerCause != null) items += "Blocker reported by the agent: $blockerCause"
        openSteps.forEach { items += "Step not finished: $it" }
        missingPaths.forEach { items += "File not created: $it" }
        if (items.isNotEmpty()) {
            appendLine()
            appendLine("Still missing:")
            items.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("The work above is partial. Send a message if you want the agent to keep going from here.")
    }
}
