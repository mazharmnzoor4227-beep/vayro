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
 */
object ChatScrollStore {
    private val positions = ConcurrentHashMap<String, Pair<Int, Int>>()

    fun save(key: String, index: Int, offset: Int) {
        positions[key] = index to offset
    }

    /** The saved (firstVisibleItemIndex, scrollOffset), or null if none. */
    fun get(key: String): Pair<Int, Int>? = positions[key]

    fun clear(key: String) {
        positions.remove(key)
    }
}
