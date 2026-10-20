package com.vayro.app.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v3.2.48: the composer layout contract, measured from the owner's
 * Claude reference recording (see
 * ~/workspace/kodex-composer-spec/COMPOSER-SPEC.md — colour-segmentation
 * measurements on 720x1600 frames, dp = px / 2).
 *
 * Claude's composer is ONE persistent bar: the text field and the
 * controls row are composed together in every state, keyboard open or
 * closed, and the bar's position is driven by the keyboard's own inset
 * animation. There is no idle/focused layout swap. These constants are
 * the single source of truth for both composers (home + workspace) so
 * the two can never drift apart again; [ComposerLayoutTest] pins them.
 *
 * Deliberate deviation: Claude's circles measure 31 dp; Kodex keeps its
 * owner-approved 40 dp touch targets, so the controls row is 40 dp and
 * a one-line card lands ~9 dp taller than Claude's.
 */
object ComposerLayout {
    /** Card margin from the screen's left/right edges (measured 15). */
    val CardHorizontalMargin: Dp = 15.dp

    /** Card corner radius (measured ~19). */
    val CardRadius: Dp = 19.dp

    /** Padding above the chip zone / field inside the card. */
    val CardInnerTopPadding: Dp = 8.dp

    /** Text field inset from the card's left/right edges (measured 11.5). */
    val FieldHorizontalPadding: Dp = 12.dp

    /** Field padding inside the card (top targets the measured glyph seat). */
    val FieldTopPadding: Dp = 8.dp
    val FieldBottomPadding: Dp = 4.dp

    /** Controls row inset from the card's edges (measured 6.5). */
    val ControlsHorizontalPadding: Dp = 7.dp
    val ControlsTopPadding: Dp = 6.dp
    val ControlsBottomPadding: Dp = 8.dp

    /** Gap between consecutive controls (measured ~8). */
    val ControlsGap: Dp = 8.dp

    /** +, mic and right-slot circles, and the pill, all span the row. */
    val CircleSize: Dp = 40.dp
    val PillHeight: Dp = 40.dp
    val ControlsRowHeight: Dp = 40.dp

    /** Home: card floats this far above the keyboard / nav area. */
    val HomeBottomSpacing: Dp = 15.dp

    /** Workspace: card spacing above the tab bar (keyboard closed) and
     * above the keyboard itself (open, tab bar gone) — measured ~8 / ~15. */
    val WorkspaceBottomSpacingKeyboardClosed: Dp = 8.dp
    val WorkspaceBottomSpacingKeyboardOpen: Dp = 15.dp
}

/** What the composer's single right-hand slot shows. */
enum class ComposerRightSlot { Stop, Send, Voice }

/**
 * The right slot is ONE element whose content is decided instantly —
 * never a crossfade (that crossfade was the v3.2.40–47 "ghost": two
 * layout states rendered in one card on three different animation
 * clocks). A running turn wins the slot (Stop) even when text is
 * present; otherwise text means Send; an empty field offers Voice.
 */
fun composerRightSlot(isRunning: Boolean, canSend: Boolean): ComposerRightSlot = when {
    isRunning -> ComposerRightSlot.Stop
    canSend -> ComposerRightSlot.Send
    else -> ComposerRightSlot.Voice
}
