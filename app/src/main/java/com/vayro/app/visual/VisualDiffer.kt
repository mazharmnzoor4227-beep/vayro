package com.vayro.app.visual

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Raw pixel buffer (ARGB_8888 ints, row-major) independent of the Android
 * framework so visual diffing is fully unit-testable on the JVM.
 */
data class PixelBuffer(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "PixelBuffer dimensions must be positive." }
        require(pixels.size == width * height) {
            "Pixel array size ${pixels.size} does not match ${width}x$height."
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PixelBuffer) return false
        return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        return result
    }
}

/** Rectangular region to exclude from diffing (e.g. timestamps, ads). Bounds are [left, right) x [top, bottom). */
data class DiffIgnoreRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}

/** A merged bounding box of adjacent changed cells. */
data class ChangedRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val changedPixelCount: Int,
)

data class VisualDiffResult(
    val diffPercentage: Double,
    val changedPixelCount: Long,
    val totalComparedPixels: Long,
    val changedRegions: List<ChangedRegion>,
    val thresholdPercentage: Double,
    val passed: Boolean,
)

/**
 * Pure-JVM visual differ (v3, Milestone 8).
 *
 * Compares two same-size [PixelBuffer]s channel-by-channel with an optional
 * per-channel [tolerance] (0 = exact match), skips [ignoreRegions], and
 * merges adjacent changed cells into [ChangedRegion] bounding boxes.
 * Throws [IllegalArgumentException] on dimension mismatch — never silently
 * reports 0% or 100% for incomparable inputs.
 */
object VisualDiffer {

    private const val CELL_SIZE = 16

    fun diff(
        before: PixelBuffer,
        after: PixelBuffer,
        tolerance: Int = 0,
        ignoreRegions: List<DiffIgnoreRegion> = emptyList(),
        thresholdPercentage: Double = 0.0,
    ): VisualDiffResult {
        require(tolerance in 0..255) { "Tolerance must be in 0..255." }
        require(thresholdPercentage in 0.0..100.0) { "Threshold must be in 0.0..100.0." }
        require(before.width == after.width && before.height == after.height) {
            "Cannot diff buffers of different sizes: ${before.width}x${before.height} vs ${after.width}x${after.height}."
        }

        val w = before.width
        val h = before.height
        var changed = 0L
        var compared = 0L
        val cols = (w + CELL_SIZE - 1) / CELL_SIZE
        val rows = (h + CELL_SIZE - 1) / CELL_SIZE
        val changedCells = Array(rows) { BooleanArray(cols) }
        val cellChangedCounts = Array(rows) { IntArray(cols) }

        var idx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val ignored = ignoreRegions.any { it.contains(x, y) }
                if (!ignored) {
                    compared++
                    if (!pixelsMatch(before.pixels[idx], after.pixels[idx], tolerance)) {
                        changed++
                        val c = x / CELL_SIZE
                        val r = y / CELL_SIZE
                        changedCells[r][c] = true
                        cellChangedCounts[r][c]++
                    }
                }
                idx++
            }
        }

        val regions = mergeCells(changedCells, cellChangedCounts, w, h)
        val diffPct = if (compared == 0L) 0.0 else (changed.toDouble() / compared.toDouble()) * 100.0
        return VisualDiffResult(
            diffPercentage = diffPct,
            changedPixelCount = changed,
            totalComparedPixels = compared,
            changedRegions = regions,
            thresholdPercentage = thresholdPercentage,
            passed = diffPct <= thresholdPercentage,
        )
    }

    private fun pixelsMatch(a: Int, b: Int, tolerance: Int): Boolean {
        if (tolerance == 0) return a == b
        return abs(((a ushr 24) and 0xFF) - ((b ushr 24) and 0xFF)) <= tolerance &&
            abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)) <= tolerance &&
            abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)) <= tolerance &&
            abs((a and 0xFF) - (b and 0xFF)) <= tolerance
    }

    private fun mergeCells(
        changedCells: Array<BooleanArray>,
        cellChangedCounts: Array<IntArray>,
        width: Int,
        height: Int,
    ): List<ChangedRegion> {
        val rows = changedCells.size
        if (rows == 0) return emptyList()
        val cols = changedCells[0].size
        val visited = Array(rows) { BooleanArray(cols) }
        val regions = mutableListOf<ChangedRegion>()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!changedCells[r][c] || visited[r][c]) continue
                // Flood fill (4-connectivity) over changed cells.
                var minR = r; var maxR = r; var minC = c; var maxC = c
                var pixelCount = 0
                val stack = ArrayDeque<Pair<Int, Int>>()
                stack.add(r to c)
                visited[r][c] = true
                while (stack.isNotEmpty()) {
                    val (cr, cc) = stack.removeLast()
                    minR = min(minR, cr); maxR = max(maxR, cr)
                    minC = min(minC, cc); maxC = max(maxC, cc)
                    pixelCount += cellChangedCounts[cr][cc]
                    for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                        val nr = cr + dr; val nc = cc + dc
                        if (nr in 0 until rows && nc in 0 until cols &&
                            changedCells[nr][nc] && !visited[nr][nc]
                        ) {
                            visited[nr][nc] = true
                            stack.add(nr to nc)
                        }
                    }
                }
                regions.add(
                    ChangedRegion(
                        left = minC * CELL_SIZE,
                        top = minR * CELL_SIZE,
                        right = min((maxC + 1) * CELL_SIZE, width),
                        bottom = min((maxR + 1) * CELL_SIZE, height),
                        changedPixelCount = pixelCount,
                    )
                )
            }
        }
        return regions.sortedByDescending { it.changedPixelCount }
    }
}
