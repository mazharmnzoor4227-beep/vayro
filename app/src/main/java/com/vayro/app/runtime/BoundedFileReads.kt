package com.vayro.app.runtime

import java.io.File
import java.io.RandomAccessFile

/** Reads at most [maxBytes] from the end of a file without allocating for the whole file. */
internal fun File.readTailText(maxBytes: Int): String {
    if (!isFile || maxBytes <= 0) return ""
    return RandomAccessFile(this, "r").use { input ->
        val byteCount = minOf(input.length(), maxBytes.toLong()).toInt()
        input.seek(input.length() - byteCount)
        val bytes = ByteArray(byteCount)
        input.readFully(bytes)
        bytes.toString(Charsets.UTF_8)
    }
}

/**
 * Truncates long command/build/terminal output by preserving the initial setup (head)
 * and the failure/summary lines (tail), while skipping redundant intermediate log lines.
 * Inspired by Claude Code output compression.
 */
internal fun truncateHeadAndTail(
    text: String,
    maxHeadLines: Int = 15,
    maxTailLines: Int = 35,
    maxTotalLines: Int = 60,
): String {
    if (text.isBlank()) return text
    val lines = text.lines()
    if (lines.size <= maxTotalLines) return text

    val head = lines.take(maxHeadLines)
    val tail = lines.takeLast(maxTailLines)
    val omittedCount = lines.size - maxHeadLines - maxTailLines

    return buildString {
        head.forEach { appendLine(it) }
        appendLine("... [output truncated: $omittedCount lines omitted] ...")
        tail.forEachIndexed { index, line ->
            if (index == tail.lastIndex) append(line) else appendLine(line)
        }
    }
}

