package com.vayro.app.ui.voice

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/**
 * Mutable drawing history for [LiveWaveform]. Kept outside composition state:
 * the frame loop mutates it and the canvas reads it during the draw phase.
 */
private class WaveformHistory(capacity: Int) {
    /** Oldest first; -1 marks a slot that has not been filled yet. */
    val bars = FloatArray(capacity) { -1f }
    var smoothedLevel = 0f
    var sinceLastBarMs = 0f

    fun pushBar(value: Float) {
        bars.copyInto(bars, destinationOffset = 0, startIndex = 1)
        bars[bars.lastIndex] = value
    }
}

/**
 * Scrolling "voice memo" waveform for the inline voice panel.
 *
 * A new bar is pushed on the right every [tickMs] and the strip glides left
 * continuously (sub-bar offset, so no stepping). Bar heights follow the live
 * mic level from [level]: loud speech grows tall bars, silence shrinks them
 * to small dots. Attack is fast and release slow, which is what makes the
 * motion feel calm rather than twitchy. Bars taper towards both edges.
 */
@Composable
fun LiveWaveform(
    level: () -> Float,
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.dp,
    barGap: Dp = 2.3.dp,
    minBarHeight: Dp = 4.dp,
    maxBarHeight: Dp = 22.dp,
    color: Color = Color(0xFFF7F5EE),
    tickMs: Float = 60f,
    attackMs: Float = 45f,
    releaseMs: Float = 170f,
) {
    val history = remember { WaveformHistory(capacity = 96) }
    var frameTick by remember { mutableLongStateOf(0L) }
    val currentLevel by rememberUpdatedState(level)

    LaunchedEffect(Unit) {
        var previous = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (previous != 0L) {
                val dtMs = ((now - previous) / 1_000_000f).coerceAtMost(100f)
                val target = currentLevel().coerceIn(0f, 1f)
                val tau = if (target > history.smoothedLevel) attackMs else releaseMs
                history.smoothedLevel += (target - history.smoothedLevel) * (1f - exp(-dtMs / tau))

                history.sinceLastBarMs += dtMs
                while (history.sinceLastBarMs >= tickMs) {
                    history.sinceLastBarMs -= tickMs
                    // Jitter is baked in once per bar so neighbours differ slightly
                    // without flickering between frames.
                    val jitter = 0.9f + 0.2f * Random.nextFloat()
                    history.pushBar((history.smoothedLevel * jitter).coerceIn(0f, 1f))
                }
            }
            previous = now
            frameTick = now
        }
    }

    Canvas(modifier) {
        // Reading frameTick here ties redraws to the frame loop above.
        if (frameTick == Long.MIN_VALUE) return@Canvas

        val barPx = barWidth.toPx()
        val pitch = barPx + barGap.toPx()
        val minH = minBarHeight.toPx()
        val maxH = maxBarHeight.toPx()
        val centerY = size.height / 2f
        val glide = (history.sinceLastBarMs / tickMs).coerceIn(0f, 1f) * pitch
        val total = history.bars.size

        for (age in 0 until total) {
            val value = history.bars[total - 1 - age]
            if (value < 0f) continue
            val x = size.width - barPx - age * pitch - glide
            if (x < -barPx) break

            // Smoothstep taper: dots at the far left, gentle grow-in on the right.
            val centerX = x + barPx / 2f
            val leftT = (centerX / (5f * pitch)).coerceIn(0f, 1f)
            val rightT = ((size.width - centerX) / (2.5f * pitch)).coerceIn(0f, 1f)
            val t = minOf(leftT, rightT)
            val taper = t * t * (3f - 2f * t)

            val fullHeight = minH + (maxH - minH) * value.pow(0.75f)
            val floorHeight = minH * 0.8f
            val height = floorHeight + (fullHeight - floorHeight) * taper

            drawRoundRect(
                color = color,
                topLeft = Offset(x, centerY - height / 2f),
                size = Size(barPx, height),
                cornerRadius = CornerRadius(barPx / 2f),
            )
        }
    }
}
