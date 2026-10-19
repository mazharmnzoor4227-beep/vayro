package com.vayro.app.voice

/**
 * v3.2.39 (Scope 4): which on-device TTS voices become voice cards.
 *
 * The rule the user set: cards are REAL, DISTINCT, USABLE voices only.
 * Two entries that resolve to the same underlying system voice (same
 * [VoiceCardInfo.name]) are ONE voice — showing both would be a fake
 * card, because tapping either would speak with the same voice. The
 * picker therefore dedupes by name (keeping the best instance of each
 * name) and never pads the row: a phone with two real voices gets two
 * cards, not six with duplicates wearing different friendly labels.
 *
 * Pure data in/out so the rule is unit-testable off-device.
 */
data class VoiceCardInfo(
    /** The system voice name — the identity that gets stored + applied. */
    val name: String,
    /** Language tag of the voice's locale (e.g. "en"), or null. */
    val language: String?,
    val quality: Int,
    val networkRequired: Boolean,
)

/** How many cards the design shows at most. */
const val MAX_VOICE_CARDS = 6

/**
 * Picks the voice cards from the device's voice set: distinct names only,
 * best-first (quality, then network voices), English-preferred when at
 * least 3 distinct English voices exist, capped at [MAX_VOICE_CARDS].
 * The returned size is the honest count of usable voices (capped) — it
 * is never inflated with duplicates.
 */
fun pickVoiceCards(voices: List<VoiceCardInfo>): List<VoiceCardInfo> {
    val byQuality = compareByDescending<VoiceCardInfo> { it.quality }
        .thenByDescending { if (it.networkRequired) 1 else 0 }
    // Sort first so the BEST instance of each name survives the dedupe.
    val distinct = voices.sortedWith(byQuality).distinctBy { it.name }
    val english = distinct.filter { it.language == "en" }
    val pool = if (english.size >= 3) english else distinct
    return pool.take(MAX_VOICE_CARDS)
}
