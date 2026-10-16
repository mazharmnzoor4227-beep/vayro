package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

class RuntimeExtractionBudgetTest {

    private class ZeroStream(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 0 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            remaining -= n
            return n
        }
    }

    private class SinkStream : OutputStream() {
        var bytes = 0L
        override fun write(b: Int) { bytes += 1 }
        override fun write(b: ByteArray, off: Int, len: Int) { bytes += len }
    }

    @Test
    fun copyBoundedPassesThroughBytesUnderLimits() {
        val budget = RuntimeExtractionBudget()
        val sink = SinkStream()
        budget.copyBounded(ZeroStream(1024 * 1024), sink)
        assertEquals(1024L * 1024L, sink.bytes)
        assertEquals(1024L * 1024L, budget.totalBytes)
    }

    @Test
    fun checkEntryCountsEntries() {
        val budget = RuntimeExtractionBudget()
        repeat(3) { budget.checkEntry() }
        assertEquals(3, budget.entries)
    }

    @Test
    fun checkEntryRejectsBeyondMaxEntries() {
        val budget = RuntimeExtractionBudget()
        try {
            repeat(MAX_RUNTIME_ARCHIVE_ENTRIES + 1) { budget.checkEntry() }
            fail("Expected entry-count rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("too many entries"))
        }
    }

    @Test
    fun copyBoundedRejectsOversizedSingleFile() {
        val budget = RuntimeExtractionBudget()
        try {
            budget.copyBounded(ZeroStream(MAX_RUNTIME_ARCHIVE_FILE_BYTES + 1), SinkStream())
            fail("Expected per-file size rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("per-file"))
        }
    }

    @Test
    fun copyBoundedRejectsBeyondCumulativeCap() {
        val budget = RuntimeExtractionBudget()
        try {
            repeat(5) { budget.copyBounded(ZeroStream(MAX_RUNTIME_ARCHIVE_FILE_BYTES), SinkStream()) }
            fail("Expected cumulative-size rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("maximum extracted size"))
        }
    }

    @Test
    fun archiveSizeCapCoversLargestKnownBundle() {
        assertTrue(MAX_RUNTIME_ARCHIVE_BYTES >= 569_652_007L)
        assertTrue(MAX_RUNTIME_EXTRACTED_BYTES >= 866_703_360L)
    }
}
