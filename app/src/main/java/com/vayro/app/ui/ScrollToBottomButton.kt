package com.vayro.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * v3.2.40 (Scope H): the floating scroll-to-bottom arrow for the chat
 * lists (Claude/ChatGPT pattern). Small circular shell-styled button;
 * fades in only when the user is meaningfully away from the bottom
 * and out again at the bottom. It lives in the chat-list layer, above
 * the list and clear of the composer — never over sheets or popups.
 */
@Composable
fun ScrollToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 4.dp,
            modifier = Modifier
                .size(40.dp)
                .clickable(onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Scroll to bottom",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Scope H visibility rule, shared by both lists: show the arrow when
 * the last item is not visible AND at least a screenful of items
 * (the currently visible count, at least 2) sits below the viewport.
 */
fun isFarFromBottom(
    totalItems: Int,
    lastVisibleIndex: Int,
    visibleCount: Int,
): Boolean {
    if (totalItems <= 0 || lastVisibleIndex < 0) return false
    val below = totalItems - 1 - lastVisibleIndex
    return below >= maxOf(2, visibleCount)
}

/**
 * v3.2.43 (D3): the reversed-list form of the Scope H rule, loosened
 * to what the user actually asked for. The v3.2.42 rule waited until
 * a whole screenful of messages sat between the user and the
 * newest — by then he had long wanted the arrow. The arrow now
 * shows as soon as he has scrolled away from the newest EVEN A
 * LITTLE: the newest message (index 0 in the reversed lists) is no
 * longer the first visible item, or it is only partially in view
 * (a first-item scroll offset past a few pixels — partial
 * visibility). It hides only when the list is parked exactly on
 * the newest (index 0, offset 0).
 */
const val NEWEST_ARROW_OFFSET_THRESHOLD_PX: Int = 8

fun shouldShowScrollToNewest(
    firstVisibleIndex: Int,
    firstVisibleOffsetPx: Int,
): Boolean {
    if (firstVisibleIndex < 0) return false
    return firstVisibleIndex > 0 || firstVisibleOffsetPx > NEWEST_ARROW_OFFSET_THRESHOLD_PX
}

/**
 * v3.2.43 (D4): travel back to the newest message — smoothly and
 * swiftly, never with the step/teleport a bare long-distance
 * `animateScrollToItem` shows. The route is decided by
 * [ScrollGlide.plan] from the current distance: near trips animate
 * the whole way; far trips first jump invisibly to a point about a
 * screenful short of the newest, then animate that last stretch.
 * Either way the trip ends with an exact landing on index 0 /
 * offset 0 — in the workspace list that is the working/streaming
 * tail row when one exists, otherwise the newest message itself.
 */
suspend fun androidx.compose.foundation.lazy.LazyListState.glideToNewest() {
    val info = layoutInfo
    if (info.totalItemsCount <= 0) return
    when (
        val glidePlan = ScrollGlide.plan(
            distanceItems = firstVisibleItemIndex,
            visibleCount = info.visibleItemsInfo.size,
        )
    ) {
        ScrollGlide.Plan.AnimateOnly -> animateScrollToItem(ScrollFollowGate.NEWEST_INDEX)
        is ScrollGlide.Plan.JumpThenAnimate -> {
            scrollToItem(glidePlan.jumpToIndex)
            animateScrollToItem(ScrollFollowGate.NEWEST_INDEX)
        }
    }
    // Exact landing: if the animation settled a hair off the anchor
    // (a row was still being measured), snap the final pixels —
    // sub-item distance, invisible.
    if (firstVisibleItemIndex != ScrollFollowGate.NEWEST_INDEX || firstVisibleItemScrollOffset != 0) {
        scrollToItem(ScrollFollowGate.NEWEST_INDEX, 0)
    }
}
