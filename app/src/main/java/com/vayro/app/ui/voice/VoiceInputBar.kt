package com.vayro.app.ui.voice

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp

/**
 * Recording bar that replaces the chat composer while voice input is active:
 *
 *   (live transcript, italic, above the bar)
 *   [ ✕ ]  live waveform  [ ■ ] [ ↑ ]
 *
 * ✕ discards the session, ■ stops and drops the text into the composer for
 * editing, ↑ stops and sends straight away. Colours come from the app theme;
 * the send button is the theme's lime primary with a dark arrow.
 */
@Composable
fun VoiceInputBar(
    recognizer: VoiceRecognizer,
    onCancel: () -> Unit,
    onStop: (String) -> Unit,
    onSend: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by recognizer.state.collectAsState()
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(state.error) {
        state.error?.let(onError)
    }

    BackHandler {
        recognizer.cancel()
        onCancel()
    }

    Column(modifier.fillMaxWidth()) {
        LiveTranscript(state.fullText)
        Spacer(Modifier.height(10.dp))

        Surface(
            shape = RoundedCornerShape(23.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VoiceBarButton(
                    background = MaterialTheme.colorScheme.background,
                    description = "Cancel voice input",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        recognizer.cancel()
                        onCancel()
                    },
                ) { CloseGlyph(MaterialTheme.colorScheme.onSurface) }

                LiveWaveform(
                    level = { recognizer.level },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 16.dp, end = 10.dp),
                )

                VoiceBarButton(
                    background = MaterialTheme.colorScheme.background,
                    description = "Stop recording",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        recognizer.finish { text -> onStop(text) }
                    },
                ) { StopGlyph(MaterialTheme.colorScheme.onSurface) }
                Spacer(Modifier.width(6.dp))
                VoiceBarButton(
                    background = MaterialTheme.colorScheme.primary,
                    description = "Send",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        recognizer.finish { text -> onSend(text) }
                    },
                ) { ArrowUpGlyph(MaterialTheme.colorScheme.onPrimary) }
            }
        }
    }
}

@Composable
private fun LiveTranscript(text: String) {
    val scrollState = rememberScrollState()
    LaunchedEffect(text) { scrollState.animateScrollTo(scrollState.maxValue) }
    AnimatedVisibility(visible = text.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 110.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun VoiceBarButton(
    background: Color,
    description: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(background)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

@Composable
private fun CloseGlyph(color: Color) = Canvas(Modifier.size(12.dp)) {
    val stroke = 1.6.dp.toPx()
    drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), stroke, StrokeCap.Round)
    drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
}

@Composable
private fun StopGlyph(color: Color) {
    Box(
        Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    )
}

@Composable
private fun ArrowUpGlyph(color: Color) = Canvas(Modifier.size(16.dp)) {
    val stroke = 1.8.dp.toPx()
    val centerX = size.width / 2f
    drawLine(color, Offset(centerX, size.height), Offset(centerX, 0f), stroke, StrokeCap.Round)
    drawLine(color, Offset(centerX, 0f), Offset(0f, size.height * 0.45f), stroke, StrokeCap.Round)
    drawLine(color, Offset(centerX, 0f), Offset(size.width, size.height * 0.45f), stroke, StrokeCap.Round)
}
