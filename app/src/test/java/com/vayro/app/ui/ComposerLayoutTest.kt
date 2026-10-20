package com.vayro.app.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v3.2.48: pins for the rebuilt composer. The right-slot decision is
 * the only state the composer renders from (one slot, instant content,
 * never a crossfade — the crossfade between an idle row and a focused
 * row was the recorded "double layer" ghost), and the layout constants
 * are the measured Claude spec (kodex-composer-spec/COMPOSER-SPEC.md)
 * so a later edit cannot silently drift from it.
 */
class ComposerLayoutTest {

    @Test
    fun `empty field and idle shows voice`() {
        assertEquals(ComposerRightSlot.Voice, composerRightSlot(isRunning = false, canSend = false))
    }

    @Test
    fun `text present shows send`() {
        assertEquals(ComposerRightSlot.Send, composerRightSlot(isRunning = false, canSend = true))
    }

    @Test
    fun `running turn shows stop even with text present`() {
        assertEquals(ComposerRightSlot.Stop, composerRightSlot(isRunning = true, canSend = true))
    }

    @Test
    fun `running turn shows stop with empty field`() {
        assertEquals(ComposerRightSlot.Stop, composerRightSlot(isRunning = true, canSend = false))
    }

    @Test
    fun `layout constants match the measured spec`() {
        assertEquals(15.dp, ComposerLayout.CardHorizontalMargin)
        assertEquals(19.dp, ComposerLayout.CardRadius)
        assertEquals(12.dp, ComposerLayout.FieldHorizontalPadding)
        assertEquals(7.dp, ComposerLayout.ControlsHorizontalPadding)
        assertEquals(8.dp, ComposerLayout.ControlsGap)
        assertEquals(40.dp, ComposerLayout.CircleSize)
        assertEquals(40.dp, ComposerLayout.PillHeight)
        assertEquals(40.dp, ComposerLayout.ControlsRowHeight)
        assertEquals(15.dp, ComposerLayout.HomeBottomSpacing)
        assertEquals(8.dp, ComposerLayout.WorkspaceBottomSpacingKeyboardClosed)
        assertEquals(15.dp, ComposerLayout.WorkspaceBottomSpacingKeyboardOpen)
    }
}
