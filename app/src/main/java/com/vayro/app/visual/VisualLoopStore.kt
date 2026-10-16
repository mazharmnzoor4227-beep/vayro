package com.vayro.app.visual

import org.json.JSONObject
import java.io.File

data class LoopRunSummary(
    val id: String,
    val url: String,
    val converged: Boolean,
    val iterationCount: Int,
    val finalDiffPercentage: Double,
    val finishedAt: Long,
    val fileName: String,
)

/**
 * Persists visual dev loop runs under `<projectRoot>/.mobile_harness/visual/loops/`
 * (v3, Milestone 8). Only summaries and per-iteration records are stored —
 * raw pixel buffers are never written to disk.
 */
object VisualLoopStore {

    private const val LOOPS_DIR = ".mobile_harness/visual/loops"

    fun save(projectRoot: File, result: VisualDevLoopResult): File {
        val dir = File(projectRoot, LOOPS_DIR).apply { mkdirs() }
        require(dir.isDirectory) { "Could not create loop history dir: ${dir.absolutePath}" }
        val file = File(dir, "loop_${result.finishedAt}_${result.id.take(8)}.json")
        file.writeText(result.toJson().toString(2))
        return file
    }

    fun list(projectRoot: File): List<LoopRunSummary> {
        val dir = File(projectRoot, LOOPS_DIR)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    LoopRunSummary(
                        id = json.optString("id", file.nameWithoutExtension),
                        url = json.optString("url", ""),
                        converged = json.optBoolean("converged", false),
                        iterationCount = json.optJSONArray("iterations")?.length() ?: 0,
                        finalDiffPercentage = json.optDouble("finalDiffPercentage", 100.0),
                        finishedAt = json.optLong("finishedAt", 0L),
                        fileName = file.name,
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.finishedAt }
            ?: emptyList()
    }

    fun load(file: File): VisualDevLoopResult {
        require(file.isFile) { "Loop run file not found: ${file.absolutePath}" }
        return VisualDevLoopResult.fromJson(JSONObject(file.readText()))
    }
}
