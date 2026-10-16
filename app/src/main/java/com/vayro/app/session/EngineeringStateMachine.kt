package com.vayro.app.session

import org.json.JSONArray
import org.json.JSONObject

enum class EngineeringState {
    IDLE,
    ANALYZING,
    PLANNING,
    IMPLEMENTING,
    BUILDING,
    TESTING,
    ANALYZING_FAILURE,
    FIXING,
    RETESTING,
    REVIEWING,
    WAITING_FOR_APPROVAL,
    COMPLETED,
    FAILED,
    ROLLED_BACK,
}

enum class ApprovalPolicy {
    ALWAYS_ASK,
    ON_HIGH_RISK,
    AUTO_APPROVE,
}

data class PendingApprovalRequest(
    val id: String,
    val title: String,
    val description: String,
    val changes: List<String> = emptyList(),
    val isHighRisk: Boolean = false,
    val timestampMillis: Long = System.currentTimeMillis(),
)

data class ChangeReviewReport(
    val modifiedFiles: List<String> = emptyList(),
    val addedFiles: List<String> = emptyList(),
    val deletedFiles: List<String> = emptyList(),
    val diffSummary: String = "",
    val totalChanges: Int = 0,
)

data class EngineeringStepEvent(
    val state: EngineeringState,
    val stepTitle: String,
    val details: String? = null,
    val iteration: Int = 1,
    val timestampMillis: Long = System.currentTimeMillis(),
)

data class OrchestratorSnapshot(
    val state: EngineeringState,
    val currentIteration: Int,
    val maxIterations: Int,
    val planSteps: List<String>,
    val currentStepIndex: Int,
    val history: List<EngineeringStepEvent>,
    val failureSummary: String? = null,
    val currentTaskGoal: String? = null,
    val pendingApproval: PendingApprovalRequest? = null,
    val changeReview: ChangeReviewReport? = null,
    val isPaused: Boolean = false,
)

object EngineeringStateMachine {

    /**
     * Validates whether a state transition follows the autonomous software engineering pipeline.
     */
    fun canTransition(from: EngineeringState, to: EngineeringState): Boolean {
        if (from == to) return true
        if (to == EngineeringState.FAILED || to == EngineeringState.ROLLED_BACK || to == EngineeringState.IDLE) return true

        return when (from) {
            EngineeringState.IDLE -> to == EngineeringState.ANALYZING
            EngineeringState.ANALYZING -> to == EngineeringState.PLANNING || to == EngineeringState.IMPLEMENTING || to == EngineeringState.BUILDING
            EngineeringState.PLANNING -> to == EngineeringState.IMPLEMENTING || to == EngineeringState.WAITING_FOR_APPROVAL
            EngineeringState.IMPLEMENTING -> to == EngineeringState.BUILDING || to == EngineeringState.TESTING || to == EngineeringState.REVIEWING
            EngineeringState.BUILDING -> to == EngineeringState.TESTING || to == EngineeringState.ANALYZING_FAILURE || to == EngineeringState.REVIEWING
            EngineeringState.TESTING -> to == EngineeringState.ANALYZING_FAILURE || to == EngineeringState.REVIEWING || to == EngineeringState.COMPLETED
            EngineeringState.ANALYZING_FAILURE -> to == EngineeringState.FIXING || to == EngineeringState.WAITING_FOR_APPROVAL
            EngineeringState.FIXING -> to == EngineeringState.RETESTING || to == EngineeringState.BUILDING
            EngineeringState.RETESTING -> to == EngineeringState.REVIEWING || to == EngineeringState.ANALYZING_FAILURE || to == EngineeringState.COMPLETED
            EngineeringState.REVIEWING -> to == EngineeringState.WAITING_FOR_APPROVAL || to == EngineeringState.COMPLETED
            EngineeringState.WAITING_FOR_APPROVAL -> to == EngineeringState.IMPLEMENTING || to == EngineeringState.BUILDING || to == EngineeringState.COMPLETED
            EngineeringState.COMPLETED -> to == EngineeringState.IDLE || to == EngineeringState.ANALYZING
            EngineeringState.FAILED -> to == EngineeringState.IDLE || to == EngineeringState.ANALYZING || to == EngineeringState.ROLLED_BACK
            EngineeringState.ROLLED_BACK -> to == EngineeringState.IDLE || to == EngineeringState.ANALYZING
        }
    }

    fun toJson(snapshot: OrchestratorSnapshot): JSONObject {
        val root = JSONObject()
        root.put("state", snapshot.state.name)
        root.put("currentIteration", snapshot.currentIteration)
        root.put("maxIterations", snapshot.maxIterations)
        root.put("currentStepIndex", snapshot.currentStepIndex)
        root.put("failureSummary", snapshot.failureSummary ?: JSONObject.NULL)
        root.put("currentTaskGoal", snapshot.currentTaskGoal ?: JSONObject.NULL)
        root.put("isPaused", snapshot.isPaused)

        val planArray = JSONArray()
        snapshot.planSteps.forEach { planArray.put(it) }
        root.put("planSteps", planArray)

        val historyArray = JSONArray()
        snapshot.history.forEach { ev ->
            val evObj = JSONObject()
            evObj.put("state", ev.state.name)
            evObj.put("stepTitle", ev.stepTitle)
            evObj.put("details", ev.details ?: JSONObject.NULL)
            evObj.put("iteration", ev.iteration)
            evObj.put("timestampMillis", ev.timestampMillis)
            historyArray.put(evObj)
        }
        root.put("history", historyArray)

        snapshot.pendingApproval?.let { req ->
            val reqObj = JSONObject()
            reqObj.put("id", req.id)
            reqObj.put("title", req.title)
            reqObj.put("description", req.description)
            reqObj.put("isHighRisk", req.isHighRisk)
            reqObj.put("timestampMillis", req.timestampMillis)
            val chArray = JSONArray()
            req.changes.forEach { chArray.put(it) }
            reqObj.put("changes", chArray)
            root.put("pendingApproval", reqObj)
        }

        snapshot.changeReview?.let { rev ->
            val revObj = JSONObject()
            revObj.put("diffSummary", rev.diffSummary)
            revObj.put("totalChanges", rev.totalChanges)
            val modArr = JSONArray(); rev.modifiedFiles.forEach { modArr.put(it) }; revObj.put("modifiedFiles", modArr)
            val addArr = JSONArray(); rev.addedFiles.forEach { addArr.put(it) }; revObj.put("addedFiles", addArr)
            val delArr = JSONArray(); rev.deletedFiles.forEach { delArr.put(it) }; revObj.put("deletedFiles", delArr)
            root.put("changeReview", revObj)
        }

        return root
    }

    fun fromJson(root: JSONObject): OrchestratorSnapshot {
        val state = runCatching { EngineeringState.valueOf(root.getString("state")) }.getOrDefault(EngineeringState.IDLE)
        val currentIteration = root.optInt("currentIteration", 0)
        val maxIterations = root.optInt("maxIterations", 3)
        val currentStepIndex = root.optInt("currentStepIndex", 0)
        val failureSummary = if (root.isNull("failureSummary")) null else root.optString("failureSummary")
        val currentTaskGoal = if (root.isNull("currentTaskGoal")) null else root.optString("currentTaskGoal")
        val isPaused = root.optBoolean("isPaused", false)

        val planArray = root.optJSONArray("planSteps") ?: JSONArray()
        val planSteps = (0 until planArray.length()).map { planArray.getString(it) }

        val historyArray = root.optJSONArray("history") ?: JSONArray()
        val history = (0 until historyArray.length()).mapNotNull { i ->
            historyArray.optJSONObject(i)?.let { obj ->
                EngineeringStepEvent(
                    state = runCatching { EngineeringState.valueOf(obj.getString("state")) }.getOrDefault(EngineeringState.IDLE),
                    stepTitle = obj.optString("stepTitle", ""),
                    details = if (obj.isNull("details")) null else obj.optString("details"),
                    iteration = obj.optInt("iteration", 1),
                    timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis()),
                )
            }
        }

        val pendingApproval = root.optJSONObject("pendingApproval")?.let { obj ->
            val chArr = obj.optJSONArray("changes") ?: JSONArray()
            val chList = (0 until chArr.length()).map { chArr.getString(it) }
            PendingApprovalRequest(
                id = obj.optString("id", ""),
                title = obj.optString("title", ""),
                description = obj.optString("description", ""),
                changes = chList,
                isHighRisk = obj.optBoolean("isHighRisk", false),
                timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis()),
            )
        }

        val changeReview = root.optJSONObject("changeReview")?.let { obj ->
            val modArr = obj.optJSONArray("modifiedFiles") ?: JSONArray()
            val addArr = obj.optJSONArray("addedFiles") ?: JSONArray()
            val delArr = obj.optJSONArray("deletedFiles") ?: JSONArray()
            ChangeReviewReport(
                modifiedFiles = (0 until modArr.length()).map { modArr.getString(it) },
                addedFiles = (0 until addArr.length()).map { addArr.getString(it) },
                deletedFiles = (0 until delArr.length()).map { delArr.getString(it) },
                diffSummary = obj.optString("diffSummary", ""),
                totalChanges = obj.optInt("totalChanges", 0),
            )
        }

        return OrchestratorSnapshot(
            state = state,
            currentIteration = currentIteration,
            maxIterations = maxIterations,
            planSteps = planSteps,
            currentStepIndex = currentStepIndex,
            history = history,
            failureSummary = failureSummary,
            currentTaskGoal = currentTaskGoal,
            pendingApproval = pendingApproval,
            changeReview = changeReview,
            isPaused = isPaused,
        )
    }
}
