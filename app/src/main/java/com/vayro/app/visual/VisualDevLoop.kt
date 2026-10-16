package com.vayro.app.visual

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Per-iteration verdict of the visual dev loop. */
enum class IterationVerdict {
    PASS,           // diff within threshold — converged
    RETRY,          // diff above threshold — another iteration allowed
    EXHAUSTED,      // max iterations reached without convergence
    EDIT_FAILED,    // the edit applier reported failure
    CAPTURE_FAILED, // screenshot capture threw
}

/** Supplies screenshots as pure-JVM pixel buffers (production: WebView capture → buffer). */
fun interface ScreenshotProvider {
    @Throws(Exception::class)
    fun capture(url: String, viewport: ViewportPreset): PixelBuffer
}

/** Applies an AI-suggested edit between captures (production: file edit + reload trigger). */
fun interface EditApplier {
    fun applyEdit(iteration: Int, lastDiff: VisualDiffResult?): EditApplication
}

data class EditApplication(
    val description: String,
    val applied: Boolean,
    val detail: String = "",
)

data class VisualDevLoopConfig(
    val url: String,
    val viewport: ViewportPreset = ViewportPreset.PIXEL_8,
    val maxIterations: Int = 5,
    val diffThresholdPercentage: Double = 1.0,
    val pixelTolerance: Int = 0,
    val ignoreRegions: List<DiffIgnoreRegion> = emptyList(),
) {
    init {
        require(url.isNotBlank()) { "URL must not be blank." }
        require(maxIterations >= 1) { "maxIterations must be >= 1." }
        require(diffThresholdPercentage in 0.0..100.0) { "Threshold must be in 0.0..100.0." }
    }
}

data class VisualDevIteration(
    val iteration: Int,
    val diffPercentage: Double,
    val changedPixelCount: Long,
    val changedRegionCount: Int,
    val editDescription: String,
    val editApplied: Boolean,
    val verdict: IterationVerdict,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("iteration", iteration)
        put("diffPercentage", diffPercentage)
        put("changedPixelCount", changedPixelCount)
        put("changedRegionCount", changedRegionCount)
        put("editDescription", editDescription)
        put("editApplied", editApplied)
        put("verdict", verdict.name)
    }

    companion object {
        fun fromJson(json: JSONObject): VisualDevIteration = VisualDevIteration(
            iteration = json.getInt("iteration"),
            diffPercentage = json.getDouble("diffPercentage"),
            changedPixelCount = json.getLong("changedPixelCount"),
            changedRegionCount = json.getInt("changedRegionCount"),
            editDescription = json.optString("editDescription", ""),
            editApplied = json.getBoolean("editApplied"),
            verdict = runCatching { IterationVerdict.valueOf(json.optString("verdict", "RETRY")) }
                .getOrDefault(IterationVerdict.RETRY),
        )
    }
}

data class VisualDevLoopResult(
    val id: String = UUID.randomUUID().toString(),
    val config: VisualDevLoopConfig,
    val converged: Boolean,
    val iterations: List<VisualDevIteration>,
    val finalDiffPercentage: Double,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", config.url)
        put("viewport", config.viewport.id)
        put("maxIterations", config.maxIterations)
        put("diffThresholdPercentage", config.diffThresholdPercentage)
        put("pixelTolerance", config.pixelTolerance)
        put("converged", converged)
        put("finalDiffPercentage", finalDiffPercentage)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt)
        put("iterations", JSONArray(iterations.map { it.toJson() }))
    }

    companion object {
        fun fromJson(json: JSONObject): VisualDevLoopResult {
            val iterationsArray = json.optJSONArray("iterations")
            val iterations = mutableListOf<VisualDevIteration>()
            if (iterationsArray != null) {
                for (i in 0 until iterationsArray.length()) {
                    iterations.add(VisualDevIteration.fromJson(iterationsArray.getJSONObject(i)))
                }
            }
            val viewportId = json.optString("viewport", ViewportPreset.PIXEL_8.id)
            val viewport = ViewportPreset.ALL.firstOrNull { it.id == viewportId } ?: ViewportPreset.PIXEL_8
            return VisualDevLoopResult(
                id = json.optString("id", UUID.randomUUID().toString()),
                config = VisualDevLoopConfig(
                    url = json.optString("url", ""),
                    viewport = viewport,
                    maxIterations = json.optInt("maxIterations", 5),
                    diffThresholdPercentage = json.optDouble("diffThresholdPercentage", 1.0),
                    pixelTolerance = json.optInt("pixelTolerance", 0),
                ),
                converged = json.optBoolean("converged", false),
                iterations = iterations,
                finalDiffPercentage = json.optDouble("finalDiffPercentage", 100.0),
                startedAt = json.optLong("startedAt", 0L),
                finishedAt = json.optLong("finishedAt", 0L),
            )
        }
    }
}

/**
 * The visual development loop (v3, Milestone 8): inspect → AI edit → reload →
 * screenshot-compare, bounded by [VisualDevLoopConfig.maxIterations].
 *
 * Each iteration applies one edit, captures the result, and diffs it against
 * the original baseline. The loop converges when the diff drops at or below
 * the threshold. Failures (edit/capture) stop the loop honestly — they are
 * recorded as verdicts, never papered over as convergence.
 */
object VisualDevLoop {

    fun run(
        config: VisualDevLoopConfig,
        screenshotProvider: ScreenshotProvider,
        editApplier: EditApplier,
    ): VisualDevLoopResult {
        val startedAt = System.currentTimeMillis()
        val iterations = mutableListOf<VisualDevIteration>()

        val baseline: PixelBuffer
        try {
            baseline = screenshotProvider.capture(config.url, config.viewport)
        } catch (e: Exception) {
            iterations.add(
                VisualDevIteration(
                    iteration = 0,
                    diffPercentage = 100.0,
                    changedPixelCount = -1,
                    changedRegionCount = 0,
                    editDescription = "",
                    editApplied = false,
                    verdict = IterationVerdict.CAPTURE_FAILED,
                )
            )
            return VisualDevLoopResult(
                config = config,
                converged = false,
                iterations = iterations,
                finalDiffPercentage = 100.0,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
            )
        }

        var lastDiff: VisualDiffResult? = null
        var converged = false
        var finalDiff = 100.0

        for (iteration in 1..config.maxIterations) {
            val edit = runCatching { editApplier.applyEdit(iteration, lastDiff) }.getOrNull()
            if (edit == null || !edit.applied) {
                iterations.add(
                    VisualDevIteration(
                        iteration = iteration,
                        diffPercentage = lastDiff?.diffPercentage ?: 100.0,
                        changedPixelCount = lastDiff?.changedPixelCount ?: -1,
                        changedRegionCount = lastDiff?.changedRegions?.size ?: 0,
                        editDescription = edit?.description ?: "",
                        editApplied = false,
                        verdict = IterationVerdict.EDIT_FAILED,
                    )
                )
                finalDiff = lastDiff?.diffPercentage ?: 100.0
                break
            }

            val after: PixelBuffer
            try {
                after = screenshotProvider.capture(config.url, config.viewport)
            } catch (e: Exception) {
                iterations.add(
                    VisualDevIteration(
                        iteration = iteration,
                        diffPercentage = lastDiff?.diffPercentage ?: 100.0,
                        changedPixelCount = lastDiff?.changedPixelCount ?: -1,
                        changedRegionCount = lastDiff?.changedRegions?.size ?: 0,
                        editDescription = edit.description,
                        editApplied = true,
                        verdict = IterationVerdict.CAPTURE_FAILED,
                    )
                )
                finalDiff = lastDiff?.diffPercentage ?: 100.0
                break
            }

            val diff = VisualDiffer.diff(
                before = baseline,
                after = after,
                tolerance = config.pixelTolerance,
                ignoreRegions = config.ignoreRegions,
                thresholdPercentage = config.diffThresholdPercentage,
            )
            lastDiff = diff
            finalDiff = diff.diffPercentage

            if (diff.passed) {
                iterations.add(
                    VisualDevIteration(
                        iteration = iteration,
                        diffPercentage = diff.diffPercentage,
                        changedPixelCount = diff.changedPixelCount,
                        changedRegionCount = diff.changedRegions.size,
                        editDescription = edit.description,
                        editApplied = true,
                        verdict = IterationVerdict.PASS,
                    )
                )
                converged = true
                break
            }

            val verdict =
                if (iteration == config.maxIterations) IterationVerdict.EXHAUSTED else IterationVerdict.RETRY
            iterations.add(
                VisualDevIteration(
                    iteration = iteration,
                    diffPercentage = diff.diffPercentage,
                    changedPixelCount = diff.changedPixelCount,
                    changedRegionCount = diff.changedRegions.size,
                    editDescription = edit.description,
                    editApplied = true,
                    verdict = verdict,
                )
            )
            if (verdict == IterationVerdict.EXHAUSTED) break
        }

        return VisualDevLoopResult(
            config = config,
            converged = converged,
            iterations = iterations,
            finalDiffPercentage = finalDiff,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
        )
    }
}
