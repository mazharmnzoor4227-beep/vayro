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
