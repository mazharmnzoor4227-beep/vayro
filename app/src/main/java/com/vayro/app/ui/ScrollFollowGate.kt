package com.vayro.app.ui

/**
 * v3.2.41 (Scope 1): the one decision point for programmatic chat
 * scrolling. The user's complaint: chat history sometimes JUMPED to
 * the very top or bottom on its own. The causes found in the
 * follow/restore effects were all of the same shape — a programmatic
 * scroll fired while the user owned the position:
 *
 *  - a follow effect ran `animateScrollToItem` / `scrollToItem`
 *    while the user's own fling or drag was still in progress,
 *  - a restore fired after an overlay closed even though the live
 *    list still held a valid position (e.g. the user had scrolled
 *    the underlying list through a scrim or the state was re-created
 *    mid-read),
 *  - a delayed/streaming update re-triggered "follow" after the
 *    user had already scrolled away from the bottom.
 *
 * The rule is now absolute and lives here so both lists (home thread
 * and workspace chat) share it: **the user owns the scroll position;
 * Kodex may move the list only for the user's own send, or when the
 * user is already parked at the bottom — and never during the user's
 * own scroll gesture or while an overlay covers the list.**
 */
object ScrollFollowGate {

    /**
     * May the list auto-scroll to the newest content right now?
     *
     * @param ownSend the change is the user's own just-sent message
     *        (following your own send is the expected behavior).
     * @param atBottom the list is already at (or beyond) the end, so
     *        following keeps the user where they already are.
     * @param userScrollInProgress a user scroll/fling is in flight.
     * @param userScrolledUp the user has deliberately scrolled away
     *        from the bottom (workspace chat tracks this while a run
     *        streams).
     * @param overlayOpen a sheet/dialog covers the list.
     */
    fun shouldFollow(
        ownSend: Boolean,
        atBottom: Boolean,
        userScrollInProgress: Boolean,
        userScrolledUp: Boolean,
        overlayOpen: Boolean,
    ): Boolean {
        if (userScrollInProgress) return false
        if (overlayOpen) return false
        if (userScrolledUp) return false
        return ownSend || atBottom
    }

    /**
     * May a saved position be restored (e.g. when an overlay closes
     * or a screen returns)? Only when something was actually saved
     * AND the live list has demonstrably lost its position (index 0
     * AND offset 0 — a list the user is reading never sits exactly
     * there unless it was reset). Restoring over a live position is
     * how closing a sheet used to yank the chat to an old spot.
     */
    fun shouldRestore(
        storeHasPosition: Boolean,
        liveIndex: Int,
        liveOffset: Int,
        userScrollInProgress: Boolean,
    ): Boolean {
        if (!storeHasPosition) return false
        if (userScrollInProgress) return false
        return liveIndex == 0 && liveOffset == 0
    }

    /**
     * v3.2.42: both chat lists now run with `reverseLayout = true`
     * (the JetChat structure): the NEWEST message is list index 0 —
     * the visual bottom — and the layout itself anchors the viewport
     * there, so a user parked at the newest message needs no scroll
     * call at all when new content arrives; the list simply grows
     * upward from its anchor. In list-state terms the user is parked
     * at the newest exactly when the state cannot scroll backward
     * (toward index 0) any further. This replaces the pre-3.2.42
     * "at bottom" test (`!canScrollForward` on a forward list),
     * whose meaning inverts with the layout direction.
     */
    fun isParkedAtNewest(canScrollBackward: Boolean): Boolean = !canScrollBackward

    /** The list index of the newest message in a reversed chat list. */
    const val NEWEST_INDEX: Int = 0
}

/**
 * v3.2.43 (D4): how a programmatic trip back to the newest message
 * is travelled. A bare `animateScrollToItem(0)` across a long
 * conversation visibly steps and teleports: the lazy list has to
 * measure and recycle tall rows all the way down, frame by frame.
 * The shipped approach is two-phase — the same shape a hand scroll
 * takes:
 *
 *  - NEAR (within about a screenful of the newest): one smooth
 *    animation the whole way.
 *  - FAR: an invisible instant jump to a point still
 *    [ANIMATED_TAIL_ITEMS]-ish short of the newest, then the SAME
 *    smooth animation for the remaining ~screenful — swift, with
 *    no jerk and no step-cut, landing exactly on index 0.
 *
 * The decision is pure and unit-tested here; the travelling itself
 * lives in `LazyListState.glideToNewest` (ScrollToBottomButton.kt).
 */
object ScrollGlide {

    sealed interface Plan {
        /** Animate the whole (short) distance. */
        object AnimateOnly : Plan

        /** Jump instantly to [jumpToIndex], then animate the rest. */
        data class JumpThenAnimate(val jumpToIndex: Int) : Plan
    }

    /** Items the animated leg covers at minimum (about a screenful). */
    const val MIN_ANIMATED_TAIL_ITEMS: Int = 3

    /**
     * @param distanceItems how many items sit between the viewport
     *        and the newest message — in a reversed list this is the
     *        state's firstVisibleItemIndex.
     * @param visibleCount how many items the viewport currently
     *        shows (one screenful).
     */
    fun plan(distanceItems: Int, visibleCount: Int): Plan {
        val tail = maxOf(visibleCount, MIN_ANIMATED_TAIL_ITEMS)
        if (distanceItems <= tail + 1) return Plan.AnimateOnly
        return Plan.JumpThenAnimate(jumpToIndex = distanceItems - tail)
    }
}
