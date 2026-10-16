package com.vayro.app.visual

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

private fun solidBuffer(w: Int, h: Int, argb: Int): PixelBuffer =
    PixelBuffer(w, h, IntArray(w * h) { argb })

class VisualDifferTest {

    @Test
    fun identicalBuffers_diffZeroAndPassed() {
        val before = solidBuffer(64, 64, 0xFF112233.toInt())
        val after = solidBuffer(64, 64, 0xFF112233.toInt())
        val result = VisualDiffer.diff(before, after, thresholdPercentage = 0.0)
        assertEquals(0.0, result.diffPercentage, 0.0001)
        assertEquals(0L, result.changedPixelCount)
        assertEquals(64L * 64L, result.totalComparedPixels)
        assertTrue(result.changedRegions.isEmpty())
        assertTrue(result.passed)
    }

    @Test
    fun completelyDifferentBuffers_diffHundred() {
        val before = solidBuffer(32, 32, 0xFF000000.toInt())
        val after = solidBuffer(32, 32, 0xFFFFFFFF.toInt())
        val result = VisualDiffer.diff(before, after)
        assertEquals(100.0, result.diffPercentage, 0.0001)
        assertEquals(32L * 32L, result.changedPixelCount)
        assertTrue(result.changedRegions.isNotEmpty())
        assertFalse(result.passed) // default threshold 0.0
    }

    @Test
    fun halfChangedBuffer_diffFifty() {
        val w = 64; val h = 32
        val before = solidBuffer(w, h, 0xFF000000.toInt())
        val pixels = IntArray(w * h) { i -> if (i < w * h / 2) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val after = PixelBuffer(w, h, pixels)
        val result = VisualDiffer.diff(before, after)
        assertEquals(50.0, result.diffPercentage, 0.0001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dimensionMismatch_throwsHonestly() {
        VisualDiffer.diff(solidBuffer(10, 10, 0), solidBuffer(10, 11, 0))
    }

    @Test
    fun tolerance_allowsNearMatches() {
        // Differ by 3 in the blue channel only.
        val before = solidBuffer(16, 16, 0xFF101010.toInt())
        val after = solidBuffer(16, 16, 0xFF101013.toInt())
        val strict = VisualDiffer.diff(before, after, tolerance = 0)
        assertEquals(100.0, strict.diffPercentage, 0.0001)
        val lenient = VisualDiffer.diff(before, after, tolerance = 5)
        assertEquals(0.0, lenient.diffPercentage, 0.0001)
        assertTrue(lenient.passed)
    }

    @Test
    fun ignoreRegion_excludesPixelsFromDiff() {
        val w = 64; val h = 64
        val before = solidBuffer(w, h, 0xFF000000.toInt())
        val pixels = IntArray(w * h) { 0xFF000000.toInt() }
        // Change only the top-left 16x16 block.
        for (y in 0 until 16) for (x in 0 until 16) pixels[y * w + x] = 0xFFFFFFFF.toInt()
        val after = PixelBuffer(w, h, pixels)

        val withoutIgnore = VisualDiffer.diff(before, after)
        assertTrue(withoutIgnore.diffPercentage > 0.0)

        val withIgnore = VisualDiffer.diff(
            before, after,
            ignoreRegions = listOf(DiffIgnoreRegion(0, 0, 16, 16)),
        )
        assertEquals(0.0, withIgnore.diffPercentage, 0.0001)
        assertEquals((w * h - 256).toLong(), withIgnore.totalComparedPixels)
        assertTrue(withIgnore.passed)
    }

    @Test
    fun changedRegions_adjacentCellsMerged() {
        val w = 64; val h = 64
        val before = solidBuffer(w, h, 0xFF000000.toInt())
        val pixels = IntArray(w * h) { 0xFF000000.toInt() }
        // One contiguous 32x32 changed block in the middle (spans 2x2 cells).
        for (y in 16 until 48) for (x in 16 until 48) pixels[y * w + x] = 0xFFFFFFFF.toInt()
        val after = PixelBuffer(w, h, pixels)
        val result = VisualDiffer.diff(before, after)
        assertEquals(1, result.changedRegions.size)
        val region = result.changedRegions[0]
        assertTrue(region.left <= 16 && region.top <= 16)
        assertTrue(region.right >= 48 && region.bottom >= 48)
        assertEquals(32 * 32, region.changedPixelCount)
    }

    @Test
    fun changedRegions_distantChangesStaySeparate() {
        val w = 128; val h = 64
        val before = solidBuffer(w, h, 0xFF000000.toInt())
        val pixels = IntArray(w * h) { 0xFF000000.toInt() }
        for (y in 0 until 8) for (x in 0 until 8) pixels[y * w + x] = 0xFFFFFFFF.toInt()
        for (y in 32 until 40) for (x in 100 until 108) pixels[y * w + x] = 0xFFFFFFFF.toInt()
        val after = PixelBuffer(w, h, pixels)
        val result = VisualDiffer.diff(before, after)
        assertEquals(2, result.changedRegions.size)
    }

    @Test
    fun threshold_passFailBoundary() {
        val before = solidBuffer(100, 100, 0xFF000000.toInt())
        val pixels = IntArray(100 * 100) { i -> if (i < 200) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val after = PixelBuffer(100, 100, pixels) // exactly 2% changed
        assertTrue(VisualDiffer.diff(before, after, thresholdPercentage = 2.0).passed)
        assertFalse(VisualDiffer.diff(before, after, thresholdPercentage = 1.99).passed)
    }
}

private class ScriptedScreenshotProvider(
    private val frames: List<PixelBuffer>,
    private val throwOnCall: Set<Int> = emptySet(),
) : ScreenshotProvider {
    var calls = 0
        private set

    override fun capture(url: String, viewport: ViewportPreset): PixelBuffer {
        calls++
        if (calls in throwOnCall) throw RuntimeException("camera unavailable")
        return frames[minOf(calls - 1, frames.lastIndex)]
    }
}

class VisualDevLoopTest {

    private val red = solidBuffer(32, 32, 0xFFFF0000.toInt())
    private val blue = solidBuffer(32, 32, 0xFF0000FF.toInt())

    @Test
    fun loopConvergesWhenEditFixesDiff() {
        // Baseline = red, after edit = red again → 0% diff → PASS on iteration 1.
        val provider = ScriptedScreenshotProvider(listOf(red, red))
        val applier = EditApplier { iteration, _ ->
            EditApplication(description = "fix button color (iter $iteration)", applied = true)
        }
        val result = VisualDevLoop.run(
            VisualDevLoopConfig(url = "http://localhost:3000", maxIterations = 5, diffThresholdPercentage = 1.0),
            provider, applier,
        )
        assertTrue(result.converged)
        assertEquals(1, result.iterations.size)
        assertEquals(IterationVerdict.PASS, result.iterations[0].verdict)
        assertEquals(0.0, result.finalDiffPercentage, 0.0001)
        assertEquals(2, provider.calls) // baseline + one post-edit capture
    }

    @Test
    fun loopExhaustsIterationsWhenNeverConverging() {
        // Every capture stays blue → diff stays 100%.
        val provider = ScriptedScreenshotProvider(listOf(red, blue, blue, blue))
        val applier = EditApplier { iteration, _ ->
            EditApplication(description = "attempt $iteration", applied = true)
        }
        val result = VisualDevLoop.run(
            VisualDevLoopConfig(url = "http://localhost:3000", maxIterations = 3, diffThresholdPercentage = 1.0),
            provider, applier,
        )
        assertFalse(result.converged)
        assertEquals(3, result.iterations.size)
        assertEquals(IterationVerdict.RETRY, result.iterations[0].verdict)
        assertEquals(IterationVerdict.RETRY, result.iterations[1].verdict)
        assertEquals(IterationVerdict.EXHAUSTED, result.iterations[2].verdict)
        assertEquals(100.0, result.finalDiffPercentage, 0.0001)
    }

    @Test
    fun loopStopsHonestlyOnEditFailure() {
        val provider = ScriptedScreenshotProvider(listOf(red, red))
        val applier = EditApplier { _, _ -> EditApplication(description = "broken patch", applied = false) }
        val result = VisualDevLoop.run(
            VisualDevLoopConfig(url = "http://localhost:3000", maxIterations = 5),
            provider, applier,
        )
        assertFalse(result.converged)
        assertEquals(1, result.iterations.size)
        assertEquals(IterationVerdict.EDIT_FAILED, result.iterations[0].verdict)
        assertEquals(1, provider.calls) // only the baseline was captured
    }

    @Test
    fun loopStopsHonestlyOnCaptureFailure() {
        val provider = ScriptedScreenshotProvider(listOf(red), throwOnCall = setOf(1))
        val applier = EditApplier { _, _ -> EditApplication(description = "x", applied = true) }
        val result = VisualDevLoop.run(
            VisualDevLoopConfig(url = "http://localhost:3000", maxIterations = 5),
            provider, applier,
        )
        assertFalse(result.converged)
        assertEquals(IterationVerdict.CAPTURE_FAILED, result.iterations[0].verdict)
    }

    @Test
    fun loopRecordsDiffHistoryAcrossIterations() {
        // Iter1: blue (100%), Iter2: red (0%) → PASS.
        val provider = ScriptedScreenshotProvider(listOf(red, blue, red))
        val seenDiffs = mutableListOf<Double?>()
        val applier = EditApplier { _, lastDiff ->
            seenDiffs.add(lastDiff?.diffPercentage)
            EditApplication(description = "tweak", applied = true)
        }
        val result = VisualDevLoop.run(
            VisualDevLoopConfig(url = "http://localhost:3000", maxIterations = 5, diffThresholdPercentage = 1.0),
            provider, applier,
        )
        assertTrue(result.converged)
        assertEquals(2, result.iterations.size)
        assertEquals(100.0, result.iterations[0].diffPercentage, 0.0001)
        assertEquals(0.0, result.iterations[1].diffPercentage, 0.0001)
        // First edit saw no prior diff; second edit saw the 100% diff.
        assertEquals(listOf(null, 100.0), seenDiffs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun configRejectsBlankUrl() {
        VisualDevLoopConfig(url = "  ", maxIterations = 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun configRejectsZeroIterations() {
        VisualDevLoopConfig(url = "http://x", maxIterations = 0)
    }
}

class VisualLoopStoreTest {

    @Test
    fun saveListLoad_roundTrip() {
        val tempDir = Files.createTempDirectory("loopstore-test").toFile()
        try {
            val result = VisualDevLoopResult(
                config = VisualDevLoopConfig(url = "http://localhost:5173", maxIterations = 2),
                converged = true,
                iterations = listOf(
                    VisualDevIteration(
                        iteration = 1, diffPercentage = 0.5, changedPixelCount = 12,
                        changedRegionCount = 1, editDescription = "nudge", editApplied = true,
                        verdict = IterationVerdict.PASS,
                    )
                ),
                finalDiffPercentage = 0.5,
                startedAt = 1000L, finishedAt = 2000L,
            )
            val file = VisualLoopStore.save(tempDir, result)
            assertTrue(file.isFile)
            assertTrue(file.name.startsWith("loop_"))

            val listed = VisualLoopStore.list(tempDir)
            assertEquals(1, listed.size)
            assertEquals(result.id, listed[0].id)
            assertEquals("http://localhost:5173", listed[0].url)
            assertTrue(listed[0].converged)
            assertEquals(1, listed[0].iterationCount)
            assertEquals(0.5, listed[0].finalDiffPercentage, 0.0001)

            val loaded = VisualLoopStore.load(file)
            assertEquals(result.id, loaded.id)
            assertTrue(loaded.converged)
            assertEquals(1, loaded.iterations.size)
            assertEquals(IterationVerdict.PASS, loaded.iterations[0].verdict)
            assertEquals("nudge", loaded.iterations[0].editDescription)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun listEmptyWhenNoHistory() {
        val tempDir = Files.createTempDirectory("loopstore-empty").toFile()
        try {
            assertTrue(VisualLoopStore.list(tempDir).isEmpty())
            assertTrue(VisualLoopStore.list(File(tempDir, "nonexistent")).isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun loadMissingFile_throws() {
        VisualLoopStore.load(File("/nonexistent/loop.json"))
    }
}
