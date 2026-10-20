package com.vayro.app.ui

import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * v3.2.49 — pins a bottom sheet's dialog window to a fixed frame for the
 * sheet's whole lifetime.
 *
 * The defect (owner recording, v3.2.48): opening the Activity sheet while
 * the keyboard was up rendered the sheet partway up the screen, let the
 * workspace tab bar bleed through beneath it, doubled the header, and
 * flickered between heights. Mechanism: every Material3 ModalBottomSheet
 * lives in its own dialog window, and Compose creates that window with
 * SOFT_INPUT_ADJUST_RESIZE — so while the IME is visible or animating,
 * the sheet's window frame shrinks and grows frame by frame, the sheet's
 * anchors and height caps recompute against the moving frame, and the
 * strip the shrunken window no longer covers shows the activity behind
 * WITHOUT the sheet's scrim over it.
 *
 * The fix: as the sheet's first composition step, switch its dialog
 * window to SOFT_INPUT_ADJUST_NOTHING. The window then keeps one stable
 * full-height frame no matter what the keyboard does; the sheet renders
 * as a single surface over a full-screen scrim, and the keyboard (which
 * the host screen or the sheet itself dismisses) animates in the
 * activity window underneath, never touching the sheet's geometry.
 * Sheets that keep their own top-anchored text field stay usable: the
 * field never moves, and the IME simply overlaps the sheet's lower area
 * while typing — the standard search-sheet behavior. The previous mode
 * is restored when the sheet leaves composition (the dialog window is
 * destroyed on dismissal anyway; the restore is belt and braces).
 *
 * Deliberately NOT applied to QuickTerminalSheet: the terminal's own
 * input line must ride the keyboard, and that sheet's height is a fixed
 * window fraction rather than content-hugging anchors, so it does not
 * share this defect mechanism.
 *
 * No-op outside a dialog window (defensive: a future non-dialog host
 * must never crash or alter the activity window).
 */
@Composable
internal fun SheetWindowImeGuard() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = findDialogWindow(view)
        val previous = window?.attributes?.softInputMode
        if (window != null && previous != null) {
            val attrs = window.attributes
            attrs.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            window.attributes = attrs
        }
        onDispose {
            if (window != null && previous != null) {
                runCatching {
                    val attrs = window.attributes
                    attrs.softInputMode = previous
                    window.attributes = attrs
                }
            }
        }
    }
}

private fun findDialogWindow(view: View): Window? {
    var parent: ViewParent? = view.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = (parent as? View)?.parent
    }
    return null
}
