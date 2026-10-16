package com.vayro.app.workflow

import com.vayro.app.core.result.OperationResult
import com.vayro.app.core.result.OperationStatus
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * M6: checkpoint-before-write and resumable run state.
 *
 * Before any WRITE_FILE step executes, [snapshot] copies the affected files
 * into the run's checkpoint directory. [rollback] restores those copies, so a
 * failed run can be unwound. Run state ([WorkflowRunState]) is persisted as
 * JSON after every step, so an interrupted run can be resumed later with
 * [loadRunState]. Corrupt state files are ignored (return null) — a damaged
 * checkpoint never crashes a resume attempt.
 *
 * Layout under [baseDir]:
 * - `<runId>/checkpoints/<relative path>` — pre-write file snapshots.
 * - `<runId>/state.json` — the persisted [WorkflowRunState].
 */
class CheckpointStore(private val baseDir: File) {

    private fun runDir(runId: String): File = File(baseDir, sanitize(runId))
    private fun checkpointDir(runId: String): File = File(runDir(runId), "checkpoints")
    private fun stateFile(runId: String): File = File(runDir(runId), "state.json")

    private fun sanitize(runId: String): String =
        runId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "run" }

    /**
     * Copies [relativePaths] (workspace-relative) into the checkpoint area.
     * Files that do not exist are recorded as absent so rollback deletes any
     * file the run created. Returns the list of snapshotted paths.
     */
    fun snapshot(runId: String, workspaceRoot: File, relativePaths: List<String>): List<String> {
        val dir = checkpointDir(runId)
        val snapshotted = mutableListOf<String>()
        relativePaths.forEach { rel ->
            val source = File(workspaceRoot, rel)
            val dest = File(dir, rel)
            dest.parentFile?.mkdirs()
            if (source.exists()) {
                source.copyTo(dest, overwrite = true)
            } else {
                // Marker: the file did not exist before the write.
                File(dest.parentFile, dest.name + ".absent").createNewFile()
            }
            snapshotted.add(rel)
        }
        return snapshotted
    }

    /** Restores every snapshotted file; deletes files that were absent before. */
    fun rollback(runId: String, workspaceRoot: File) {
        val dir = checkpointDir(runId)
        if (!dir.exists()) return
        dir.walkTopDown().filter { it.isFile }.forEach { snap ->
            val rel = snap.relativeTo(dir).path
            if (rel.endsWith(".absent")) {
                val original = rel.removeSuffix(".absent")
                File(workspaceRoot, original).delete()
            } else {
                val dest = File(workspaceRoot, rel)
                dest.parentFile?.mkdirs()
                snap.copyTo(dest, overwrite = true)
            }
        }
    }

    /** Drops all checkpoint + state data for a run. */
    fun discard(runId: String) {
        runDir(runId).deleteRecursively()
    }

    /** Persists the run state; failures are reported, never thrown. */
    fun saveRunState(state: WorkflowRunState): Boolean = runCatching {
        val f = stateFile(state.runId)
        f.parentFile?.mkdirs()
        f.writeText(stateToJson(state).toString())
        true
    }.getOrDefault(false)

    /** Loads a persisted run state, or null when missing/corrupt. */
    fun loadRunState(runId: String): WorkflowRunState? = runCatching {
        val f = stateFile(runId)
        if (!f.exists()) return null
        jsonToState(runId, JSONObject(f.readText()))
    }.getOrNull()

    fun listRunIds(): List<String> =
        baseDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    // ---- JSON ----

    private fun stateToJson(state: WorkflowRunState): JSONObject {
        val steps = JSONArray()
        state.plan.steps.forEach { step ->
            steps.put(JSONObject()
                .put("id", step.id)
                .put("kind", step.kind.name)
                .put("title", step.title)
                .put("detail", step.detail)
                .put("target", step.target)
                .put("payload", step.payload)
                .put("dependsOn", JSONArray(step.dependsOn))
                .put("maxAttempts", step.maxAttempts))
        }
        val results = JSONObject()
        state.stepResults.forEach { (id, r) ->
            val o = JSONObject()
                .put("status", r.status.name)
                .put("attempts", r.attempts)
                .put("repaired", r.repaired)
            r.result?.let { res ->
                o.put("result", JSONObject()
                    .put("status", res.status.name)
                    .put("exitCode", res.exitCode ?: JSONObject.NULL)
                    .put("error", res.error ?: JSONObject.NULL)
                    .put("verificationEvidence", res.verificationEvidence ?: JSONObject.NULL)
                    .put("logs", JSONArray(res.logs)))
            }
            results.put(id, o)
        }
        return JSONObject()
            .put("runId", state.runId)
            .put("phase", state.phase.name)
            .put("reviewNotes", state.reviewNotes)
            .put("goalTitle", state.plan.goal.title)
            .put("goalDescription", state.plan.goal.description)
            .put("goalKind", state.plan.goal.kind.name)
            .put("steps", steps)
            .put("stepResults", results)
    }

    private fun jsonToState(runId: String, o: JSONObject): WorkflowRunState {
        val steps = mutableListOf<PlanStep>()
        val arr = o.getJSONArray("steps")
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val deps = mutableListOf<String>()
            val dArr = s.getJSONArray("dependsOn")
            for (j in 0 until dArr.length()) deps.add(dArr.getString(j))
            steps.add(PlanStep(
                id = s.getString("id"),
                kind = PlanStepKind.valueOf(s.getString("kind")),
                title = s.getString("title"),
                detail = s.optString("detail", ""),
                target = s.optString("target", ""),
                payload = s.optString("payload", ""),
                dependsOn = deps,
                maxAttempts = s.optInt("maxAttempts", 1),
            ))
        }
        val results = mutableMapOf<String, StepRunResult>()
        val rObj = o.optJSONObject("stepResults")
        if (rObj != null) {
            rObj.keys().forEach { id ->
                val r = rObj.getJSONObject(id)
                val resJson = r.optJSONObject("result")
                val res = resJson?.let {
                    OperationResult(
                        status = OperationStatus.valueOf(it.getString("status")),
                        exitCode = if (it.isNull("exitCode")) null else it.getInt("exitCode"),
                        logs = buildList {
                            val lArr = it.optJSONArray("logs")
                            if (lArr != null) for (k in 0 until lArr.length()) add(lArr.getString(k))
                        },
                        error = if (it.isNull("error")) null else it.getString("error"),
                        verificationEvidence = if (it.isNull("verificationEvidence")) null
                            else it.getString("verificationEvidence"),
                    )
                }
                results[id] = StepRunResult(
                    stepId = id,
                    status = StepRunStatus.valueOf(r.getString("status")),
                    attempts = r.optInt("attempts", 0),
                    repaired = r.optBoolean("repaired", false),
                    result = res,
                )
            }
        }
        val goal = WorkflowGoal(
            title = o.optString("goalTitle", ""),
            description = o.optString("goalDescription", ""),
            kind = runCatching { WorkflowGoalKind.valueOf(o.getString("goalKind")) }
                .getOrDefault(WorkflowGoalKind.CUSTOM),
        )
        return WorkflowRunState(
            runId = runId,
            plan = WorkflowPlan(goal, steps),
            stepResults = results,
            phase = runCatching { WorkflowPhase.valueOf(o.getString("phase")) }
                .getOrDefault(WorkflowPhase.FAILED),
            reviewNotes = o.optString("reviewNotes", ""),
        )
    }
}
