package com.vayro.app.ui

/**
 * v3.2.38 (Part N): decides whether a home-chat turn is CONVERSATION
 * (answered in place) or a WORK command (handoff card).
 *
 * The v3.2.37 classifier was an English-only allowlist for conversation
 * (greeting prefix OR "?" OR <= 5 words) with a task-word denylist, so
 * any longer Hindi/Urdu question without a "?" fell through to TASK and
 * got the "This is a job for Work" card — the exact misfire the user hit
 * with "मैं सिर्फ पूछ रहा हूं कि तुम क्या-क्या कर सकते हो".
 *
 * The rule order is now the user's ruling:
 *  1. DEFAULT = CONVERSATION. Everything is answered in place.
 *  2. QUESTION DETECTION WINS OVER EVERYTHING — interrogatives in
 *     English / Roman Urdu-Hindi / Devanagari / Urdu script, "?", and
 *     meta-asking phrases ("सिर्फ पूछ रहा हूं", "just asking") classify
 *     as conversation unconditionally. Bare verbs inside questions or
 *     ability talk ("तुम क्या-क्या कर सकते हो") are NOT commands.
 *  3. The card appears ONLY on an unmistakable creation command: an
 *     imperative creation verb TOGETHER WITH a concrete artifact
 *     (app, website, file, project, game, a code-file extension, ...).
 *     Ambiguity always resolves to chat.
 *
 * Pure Kotlin (no Android imports) so the regression battery in
 * HomeTurnClassifierTest runs as a plain JVM unit test in CI.
 */
object HomeTurnClassifier {

    fun isConversational(
        text: String,
        hasAttachments: Boolean = false,
        mentionedPaths: List<String> = emptyList(),
    ): Boolean {
        if (hasAttachments || mentionedPaths.isNotEmpty()) return false
        val t = text.trim()
        if (t.isEmpty()) return true
        val lower = t.lowercase()
        if (isQuestion(lower)) return true
        if (isCreationCommand(lower)) return false
        // Long, instruction-shaped briefs with work verbs and no question
        // signal keep the pre-N task behavior; everything else is chat.
        if (t.length > 240 && LONG_BRIEF_VERBS.any { lower.contains(it) }) return false
        return true
    }

    // ------------------------------------------------------------------
    // Rule 2: questions win
    // ------------------------------------------------------------------

    private val DEVANAGARI_QUESTIONS = listOf(
        "क्या", "कैसे", "कैसी", "कैसा", "क्यों", "कौन", "कब", "किधर", "कहाँ", "कहां",
        "कितना", "कितने", "कितनी", "किस", "किसी",
    )

    private val URDU_QUESTIONS = listOf(
        "کیا", "کیسے", "کیسی", "کیوں", "کون", "کب", "کدھر", "کہاں", "کتنا", "کتنے", "کس",
    )

    private val ASKING_PHRASES = listOf(
        // Devanagari meta-asking / explain requests
        "सिर्फ पूछ", "पूछ रहा", "पूछ रही", "पूछना", "बस जानना", "जानना है",
        "जानना चाहता", "जानना चाहती", "समझाओ", "समझा दो",
        "तुम्हारा नाम", "आपका नाम", "तुम कौन", "आप कौन", "कर सकते", "सकते हो",
        // Roman Urdu/Hindi
        "sirf pooch", "pooch raha", "pooch rahi", "poochna hai", "bas janna",
        "janna hai", "janna chahta", "janna chahti", "samjhao", "samjha do",
        "tumhara naam", "aapka naam", "tum kon", "tum kaun", "aap kon", "aap kaun",
        "kar sakte", "sakte ho",
        // English
        "just asking", "just wanted to know", "just want to know",
        "can you", "could you", "do you", "are you", "your name",
        "explain", "tell me about", "what is", "what are", "who are",
        "what can you", "how does", "how do",
    )

    private val QUESTION_WORDS = Regex(
        "\\b(what|how|why|who|whom|whose|when|where|which|" +
            "kya|kia|kaise|kaisi|kaisa|kyon|kyun|kon|kaun|konsa|kaunsa|kab|kidhar|kahan|kitna|kitne|kitni|kis)\\b",
    )

    private fun isQuestion(lower: String): Boolean {
        if (lower.contains("?") || lower.contains("؟")) return true
        if (DEVANAGARI_QUESTIONS.any { lower.contains(it) }) return true
        if (URDU_QUESTIONS.any { lower.contains(it) }) return true
        if (ASKING_PHRASES.any { lower.contains(it) }) return true
        if (QUESTION_WORDS.containsMatchIn(lower)) return true
        return false
    }

    // ------------------------------------------------------------------
    // Rule 3: unmistakable creation commands only (verb + artifact)
    // ------------------------------------------------------------------

    private val CREATION_VERBS = Regex(
        "\\b(build|create|make|generate|write|design|develop|code|add|fix|update|delete|remove|" +
            "install|deploy|push|refactor|convert|upload|download|export|zip|banao|bana|likho|likh)\\b",
    )

    private val SCRIPT_VERBS = listOf(
        "बना", "बनाओ", "बना दो", "बनादो", "लिख", "लिखो",
        "بنا", "بناؤ", "بنا دو", "لکھ",
    )

    private val ARTIFACTS = listOf(
        "app", "apps", "website", "websites", "web site", "webpage", "web page", "landing page",
        "page", "pages", "file", "files", "project", "projects", "game", "games",
        "screen", "screens", "feature", "features", "tool", "tools",
        "script", "scripts", "bot", "form", "forms", "code", "function", "github",
        // v3.2.43 (P3c): file-FORMAT artifacts — "give me this as a PDF"
        // is a file-production job for Work, in every script the user
        // writes. (Bare "document"/"letter"/"guide" stay OUT: those are
        // written in home chat as documents, per P3b.)
        "pdf", "zip", "archive", "पीडीएफ", "ज़िप", "پی ڈی ایف",
        "ऐप", "एप", "वेबसाइट", "फाइल", "फ़ाइल", "प्रोजेक्ट", "गेम", "पेज", "स्क्रीन", "कोड",
        "ایپ", "ویب سائٹ", "فائل", "پروجیکٹ", "گیم", "پیج",
    )

    private val FILE_EXTENSIONS = Regex("\\.(kt|apk|html|json|xml|java|py|js|ts|md|csv|pdf|zip|txt)\\b")

    // v3.2.43 (P3c): the give/export form of a file-delivery request —
    // "give me this as a PDF", "is ki PDF de do", "اس کی پی ڈی ایف دے دو"
    // — carries no classic creation verb, but a named file FORMAT plus
    // a give/export verb is still an unmistakable file-production
    // command. Question forms stay conversation: rule 2 runs first.
    private val FILE_FORMAT_WORDS = listOf("pdf", "zip", "archive", "पीडीएफ", "ज़िप", "پی ڈی ایف")
    private val FILE_DELIVERY_VERBS = Regex("\\b(give|export|send|share)\\b")
    private val FILE_DELIVERY_PHRASES = listOf("de do", "dedo", "bhej do", "دے دو")

    private fun hasFileFormat(lower: String): Boolean =
        FILE_FORMAT_WORDS.any { word ->
            if (word.all { it.code < 128 }) {
                Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(lower)
            } else {
                lower.contains(word)
            }
        } || Regex("\\.(pdf|zip)\\b").containsMatchIn(lower)

    private fun isCreationCommand(lower: String): Boolean {
        val hasVerb = CREATION_VERBS.containsMatchIn(lower) || SCRIPT_VERBS.any { lower.contains(it) }
        val hasArtifact = ARTIFACTS.any { artifact ->
            if (artifact.all { it.code < 128 }) {
                Regex("\\b${Regex.escape(artifact)}\\b").containsMatchIn(lower)
            } else {
                lower.contains(artifact)
            }
        }
        if (hasVerb && (hasArtifact || FILE_EXTENSIONS.containsMatchIn(lower))) return true
        if (!hasVerb && hasFileFormat(lower)) {
            return FILE_DELIVERY_VERBS.containsMatchIn(lower) ||
                FILE_DELIVERY_PHRASES.any { lower.contains(it) }
        }
        return false
    }

    private val LONG_BRIEF_VERBS = listOf(
        "build", "create", "make", "fix", "add", "change", "update", "delete",
        "remove", "write", "edit", "install", "run", "push", "deploy",
        "generate", "design", "implement", "refactor", "convert",
        "बना", "बनाओ",
    )
}
