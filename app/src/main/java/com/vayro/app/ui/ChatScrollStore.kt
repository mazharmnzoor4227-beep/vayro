package com.vayro.app.ui

import java.util.concurrent.ConcurrentHashMap

/**
 * v3.2.40 (Scope I/I2): the conversation's scroll position belongs to
 * the user. Nothing may move it except the user's own scrolling, his
 * own send (jump to latest), his tap on the scroll-to-bottom arrow,
 * or opening a different thread.
 *
 * Both chat surfaces (home thread, workspace chat) continuously save
 * their list position here — keyed per thread/chat — and restore it
 * when an event would otherwise hand back a zeroed list state. This
 * store lives at PROCESS level on purpose: it survives composition
 * teardown, branch swaps (Chat <-> Work), sheet/dialog cycles and
 * activity recreation, which are exactly the events that destroyed
 * positions when the state lived in a screen-level `remember` (the
 * same family as the v3.2.39 Scope 2 settings-list bug). It dies with
 * the process — where the Scope E cold-start landing rule governs.
 *
 * v3.2.43: positions are LAYOUT-VERSIONED and restores are
 * RANGE-VALIDATED. An (index, offset) pair is only meaningful under
 * the list layout that produced it: v3.2.41's forward-layout indices
 * point at the opposite end of the v3.2.42+ reversed lists, and a
 * position captured for a longer conversation clamps a shorter one
 * onto its LAST item — in a reversed list the oldest message, i.e.
 * the visual top (the v3.2.42 "chat jumps to the very top" reports).
 * So: entries written under any layout version other than the
 * current one are treated as absent, and [validatedPosition] —
 * the only read the restore effects use — returns a position only
 * when its index is inside the restoring list's item range.
 * Anything untrustworthy degrades to "no saved position", and the
 * caller lands on the NEWEST message, never the oldest.
 */
object ChatScrollStore {

    /**
     * The list-layout generation the stored indices belong to.
     * 1 was the pre-3.2.42 forward layout; 2 is the reversed layout
     * (newest = index 0). Bump on any future layout flip so stale
     * indices are invalidated instead of applied.
     */
    const val LAYOUT_VERSION_REVERSED: Int = 2
    private const val CURRENT_LAYOUT_VERSION: Int = LAYOUT_VERSION_REVERSED

    private data class Entry(val layoutVersion: Int, val index: Int, val offset: Int)

    private val positions = ConcurrentHashMap<String, Entry>()

    fun save(key: String, index: Int, offset: Int) {
        positions[key] = Entry(CURRENT_LAYOUT_VERSION, index, offset)
    }

    /**
     * Writes an entry under an explicit layout version. Production
     * code always writes the current version via [save]; this exists
     * so tests can prove foreign-version entries are rejected.
     */
    internal fun saveWithLayoutVersion(key: String, index: Int, offset: Int, layoutVersion: Int) {
        positions[key] = Entry(layoutVersion, index, offset)
    }

    /** The saved (firstVisibleItemIndex, scrollOffset), or null if none. */
    fun get(key: String): Pair<Int, Int>? = positions[key]
        ?.takeIf { it.layoutVersion == CURRENT_LAYOUT_VERSION }
        ?.let { it.index to it.offset }

    /**
     * The ONLY read the chat restore effects may use: the saved
     * position when it is trustworthy for the list being restored —
     * written under the current layout version AND its index inside
     * `0 until totalItems`. Null means "no trustworthy position":
     * the caller must land on the newest message (index 0), never
     * clamp an out-of-range index onto the oldest item.
     */
    fun validatedPosition(key: String, totalItems: Int): Pair<Int, Int>? {
        if (totalItems <= 0) return null
        val saved = get(key) ?: return null
        if (saved.first !in 0 until totalItems) return null
        return saved
    }

    fun clear(key: String) {
        positions.remove(key)
    }
}
