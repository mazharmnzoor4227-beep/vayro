package com.vayro.app.session

import com.vayro.app.runtime.MemoryCategory
import com.vayro.app.runtime.ProjectMemoryStore
import com.vayro.app.workspace.GitDomainController
import com.vayro.app.workspace.SafeFileOps
import com.vayro.app.workspace.WorkspaceCheckpointManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Autonomous Agent Orchestrator.
 * Drives the complete autonomous engineering pipeline:
 * UNDERSTAND -> PLAN -> CONTEXT -> IMPLEMENT -> BUILD -> TEST -> ANALYZE -> FIX -> RETEST -> REVIEW -> APPROVAL -> COMMIT
 */
class AgentOrchestrator(
    private val checkpointManager: WorkspaceCheckpointManager? = null,
    private val memoryStore: ProjectMemoryStore? = null,
    private val gitDomainController: GitDomainController? = null,
    val maxSelfHealingIterations: Int = 3,
) {
    val selfHealing = SelfHealingLoop(maxSelfHealingIterations)

    private val _snapshot = MutableStateFlow(
        OrchestratorSnapshot(
            state = EngineeringState.IDLE,
            currentIteration = 0,
            maxIterations = maxSelfHealingIterations,
            planSteps = emptyList(),
            currentStepIndex = 0,
            history = emptyList(),
        )
    )
    val snapshot: StateFlow<OrchestratorSnapshot> = _snapshot.asStateFlow()

    @Volatile private var baselineCheckpointId: String? = null
    @Volatile private var activeProjectId: String? = null
    @Volatile private var approvalGranted: Boolean = false

    fun startTask(
        projectId: String,
        workspaceDir: File,
        userGoal: String,
        plan: List<String> = emptyList(),
    ) {
        selfHealing.reset()
        activeProjectId = projectId
        approvalGranted = false

        // Save pre-task checkpoint if manager is configured
        val cp = checkpointManager?.createCheckpoint(projectId, workspaceDir, "Pre-task baseline: $userGoal")
        baselineCheckpointId = cp?.id

        val initialEvent = EngineeringStepEvent(
            state = EngineeringState.ANALYZING,
            stepTitle = "Analyzing requirements",
            details = userGoal,
            iteration = 1,
        )

        _snapshot.value = OrchestratorSnapshot(
            state = EngineeringState.ANALYZING,
            currentIteration = 1,
            maxIterations = maxSelfHealingIterations,
            planSteps = plan,
            currentStepIndex = 0,
            history = listOf(initialEvent),
            currentTaskGoal = userGoal,
        )
    }

    fun transition(state: EngineeringState, stepTitle: String, details: String? = null) {
        val event = EngineeringStepEvent(
            state = state,
            stepTitle = stepTitle,
            details = details,
            iteration = selfHealing.currentIteration + 1,
        )
        _snapshot.update { cur ->
            cur.copy(
                state = state,
                history = cur.history + event,
            )
        }
    }

    // --- Human Approval APIs ---

    fun requestApproval(
        title: String,
        description: String,
        changes: List<String> = emptyList(),
        isHighRisk: Boolean = false,
    ) {
        approvalGranted = false
        val request = PendingApprovalRequest(
            id = UUID.randomUUID().toString().take(8),
            title = title,
            description = description,
            changes = changes,
            isHighRisk = isHighRisk,
        )
        _snapshot.update { cur ->
            cur.copy(
                state = EngineeringState.WAITING_FOR_APPROVAL,
                pendingApproval = request,
                isPaused = true,
            )
        }
    }

    fun approveCurrentStep() {
        approvalGranted = true
        _snapshot.update { cur ->
            cur.copy(
                pendingApproval = null,
                isPaused = false,
            )
        }
    }

    fun rejectCurrentStep(reason: String) {
        approvalGranted = false
        _snapshot.update { cur ->
            cur.copy(
                state = EngineeringState.FAILED,
                failureSummary = "User rejected step: $reason",
                pendingApproval = null,
                isPaused = false,
            )
        }
    }

    /**
     * Waits for the pending approval until it is granted or rejected, or
     * [timeoutMs] elapses. A timeout fails the run honestly instead of
     * parking the pipeline in WAITING_FOR_APPROVAL forever.
     */
    internal suspend fun awaitApprovalDecision(timeoutMs: Long = APPROVAL_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!approvalGranted && _snapshot.value.state == EngineeringState.WAITING_FOR_APPROVAL) {
            if (System.currentTimeMillis() >= deadline) {
                val msg = "Approval timed out after ${timeoutMs / 60_000} min with no response."
                transition(EngineeringState.FAILED, msg)
                _snapshot.update { it.copy(failureSummary = msg) }
                return false
            }
            kotlinx.coroutines.delay(50)
        }
        return _snapshot.value.state != EngineeringState.FAILED
    }

    // --- Change Review & Git Integration ---

    fun buildChangeReview(workspaceDir: File): ChangeReviewReport {
        if (gitDomainController == null) {
            return ChangeReviewReport(diffSummary = "Git domain controller not attached.")
        }
        val statusRes = gitDomainController.status(workspaceDir)
        val diffRes = gitDomainController.diff(workspaceDir)

        val modified = mutableListOf<String>()
        val added = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        if (statusRes.success) {
            statusRes.output.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    val parts = trimmed.split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2) {
                        val statusCode = parts[0]
                        val path = parts[1]
                        when {
                            statusCode.contains("M") -> modified.add(path)
                            statusCode.contains("?") || statusCode.contains("A") -> added.add(path)
                            statusCode.contains("D") -> deleted.add(path)
                            else -> modified.add(path)
                        }
                    }
                }
            }
        }

        val total = modified.size + added.size + deleted.size
        val summary = if (diffRes.success) diffRes.output else "Changes across $total file(s)"

        val report = ChangeReviewReport(
            modifiedFiles = modified,
            addedFiles = added,
            deletedFiles = deleted,
            diffSummary = summary,
            totalChanges = total,
        )
        _snapshot.update { it.copy(changeReview = report) }
        return report
    }

    fun commitVerifiedWork(workspaceDir: File, commitMessage: String): Boolean {
        if (gitDomainController == null) return false
        val addRes = gitDomainController.executeGit(listOf("add", "-A"), workspaceDir)
        if (!addRes.success) return false
        val commitRes = gitDomainController.executeGit(listOf("commit", "-m", commitMessage), workspaceDir)
        return commitRes.success
    }

    // --- Bounded Verification & Self-Healing Loop ---

    suspend fun executeVerificationLoop(
        projectId: String,
        buildAndTest: suspend () -> Pair<Boolean, String>,
        applyAutoFix: suspend (ErrorDiagnosis) -> Boolean,
    ): Boolean {
        transition(EngineeringState.BUILDING, "Building project")
        var (success, output) = buildAndTest()

        if (success) {
            transition(EngineeringState.TESTING, "Running tests")
            transition(EngineeringState.REVIEWING, "Verification successful")
            transition(EngineeringState.COMPLETED, "Task completed successfully")
            return true
        }

        while (!success && selfHealing.canRetry()) {
            transition(EngineeringState.ANALYZING_FAILURE, "Analyzing build/test error", output)
            val diagnosis = selfHealing.recordFailure(output)

            // Record error memory
            memoryStore?.record(
                projectId = projectId,
                category = MemoryCategory.ERROR,
                key = diagnosis.rootCause,
                content = diagnosis.suggestedFix,
            )

            transition(EngineeringState.FIXING, "Applying self-healing repair (${selfHealing.currentIteration}/$maxSelfHealingIterations)", diagnosis.suggestedFix)
            val fixApplied = applyAutoFix(diagnosis)
            if (!fixApplied) {
                transition(EngineeringState.FAILED, "Automatic fix could not be applied")
                return false
            }

            transition(EngineeringState.RETESTING, "Retesting after fix")
            val retestResult = buildAndTest()
            success = retestResult.first
            output = retestResult.second

            if (success) {
                transition(EngineeringState.REVIEWING, "All checks passed after self-healing repair")
                transition(EngineeringState.COMPLETED, "Task completed and verified")
                return true
            }
        }

        transition(EngineeringState.FAILED, "Exceeded maximum self-healing retries ($maxSelfHealingIterations)", output)
        return false
    }

    // --- Full End-to-End Autonomous Engineering Pipeline ---

    suspend fun executeFullAutonomousWorkflow(
        projectId: String,
        workspaceDir: File,
        userGoal: String,
        planSteps: List<String> = emptyList(),
        approvalPolicy: ApprovalPolicy = ApprovalPolicy.AUTO_APPROVE,
        onAnalyze: suspend () -> String = { "Analyzed project requirements" },
        onPlan: suspend () -> List<String> = { planSteps.ifEmpty { listOf("Implement changes", "Verify build") } },
        onImplement: suspend (currentStep: String, stepIndex: Int) -> Boolean,
        buildAndTest: suspend () -> Pair<Boolean, String>,
        applyAutoFix: suspend (ErrorDiagnosis) -> Boolean,
        commitMessage: String? = null,
    ): Boolean {
        // 1. UNDERSTAND
        startTask(projectId, workspaceDir, userGoal, planSteps)
        val analysis = onAnalyze()
        transition(EngineeringState.ANALYZING, "Requirement analysis complete", analysis)

        // 2. PLAN
        transition(EngineeringState.PLANNING, "Formulating step-by-step implementation plan")
        val resolvedSteps = onPlan()
        _snapshot.update { it.copy(planSteps = resolvedSteps) }

        // 3. IMPLEMENT
        transition(EngineeringState.IMPLEMENTING, "Starting implementation of ${resolvedSteps.size} steps")
        for ((idx, step) in resolvedSteps.withIndex()) {
            _snapshot.update { it.copy(currentStepIndex = idx) }
            transition(EngineeringState.IMPLEMENTING, "Executing step ${idx + 1}/${resolvedSteps.size}: $step")

            if (approvalPolicy == ApprovalPolicy.ALWAYS_ASK) {
                requestApproval("Approve Step ${idx + 1}", step, listOf("Working on $step"), isHighRisk = false)
                if (!awaitApprovalDecision()) return false
                transition(EngineeringState.IMPLEMENTING, "Proceeding with step ${idx + 1}")
            }

            val stepSuccess = onImplement(step, idx)
            if (!stepSuccess) {
                transition(EngineeringState.FAILED, "Step execution failed on: $step")
                return false
            }
        }

        // 4. BUILD, TEST & SELF-HEALING
        val verified = executeVerificationLoop(projectId, buildAndTest, applyAutoFix)
        if (!verified) return false

        // 5. REVIEW
        transition(EngineeringState.REVIEWING, "Generating change review report")
        val reviewReport = buildChangeReview(workspaceDir)

        // 6. USER APPROVAL (if required by policy)
        if (approvalPolicy != ApprovalPolicy.AUTO_APPROVE) {
            requestApproval(
                title = "Approve final changes",
                description = "Review changes before committing to repository",
                changes = reviewReport.modifiedFiles + reviewReport.addedFiles,
                isHighRisk = reviewReport.deletedFiles.isNotEmpty(),
            )
            if (!awaitApprovalDecision()) return false
        }

        // 7. COMMIT & PERSIST
        val finalCommitMsg = commitMessage ?: "feat: $userGoal"
        if (gitDomainController != null) {
            commitVerifiedWork(workspaceDir, finalCommitMsg)
        }

        memoryStore?.recordPreviousImplementation(
            projectId = projectId,
            key = userGoal.take(30),
            content = "Completed and verified: $finalCommitMsg (modified ${reviewReport.totalChanges} files)",
        )

        transition(EngineeringState.COMPLETED, "Autonomous engineering task completed and committed: $finalCommitMsg")
        return true
    }

    // --- Session Persistence & Recovery ---

    fun saveState(baseDir: File, projectId: String? = activeProjectId): Boolean {
        val pId = projectId ?: return false
        val stateFile = File(baseDir, "orchestrator_state/$pId.json")
        val json = EngineeringStateMachine.toJson(_snapshot.value)
        return runCatching {
            SafeFileOps.atomicWriteText(stateFile, json.toString())
            true
        }.getOrDefault(false)
    }

    fun restoreActiveSession(projectId: String, baseDir: File): Boolean {
        val stateFile = File(baseDir, "orchestrator_state/$projectId.json")
        if (!stateFile.isFile) return false
        return runCatching {
            val json = JSONObject(stateFile.readText(Charsets.UTF_8))
            val restored = EngineeringStateMachine.fromJson(json)
            activeProjectId = projectId
            _snapshot.value = restored
            true
        }.getOrDefault(false)
    }

    // --- Checkpoint Rollback ---

    fun rollbackToBaseline(workspaceDir: File): Boolean {
        transition(EngineeringState.ROLLED_BACK, "Rolling back to baseline checkpoint")
        val pId = activeProjectId ?: return false
        val cpId = baselineCheckpointId ?: return false
        return checkpointManager?.rollbackToCheckpoint(pId, cpId, workspaceDir) ?: false
    }

    companion object {
        /** Approvals that sit unanswered this long fail the run instead of hanging it. */
        const val APPROVAL_TIMEOUT_MS = 30 * 60_000L
    }
}
