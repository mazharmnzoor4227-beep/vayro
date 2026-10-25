package com.vayro.app.agent.team.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.vayro.app.agent.team.blackboard.TeamBlackboard
import com.vayro.app.agent.team.budget.TeamBudgetTracker
import com.vayro.app.agent.team.comm.TeamCommunicationBus
import com.vayro.app.agent.team.model.ReviewRecord
import com.vayro.app.agent.team.model.ReviewVerdict
import com.vayro.app.agent.team.model.TeamMessageType
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.model.TeamRoleConfig
import com.vayro.app.agent.team.model.TeamTask
import com.vayro.app.agent.team.model.TeamTaskStatus
import com.vayro.app.agent.team.review.TeamReviewGatekeeper
import com.vayro.app.agent.team.scheduler.TeamTaskScheduler
import com.vayro.app.model.AgentKind
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Safe concurrent DAG dispatcher for the real multi-agent runtime (Milestone 10).
 *
 * Executes each dependency stage concurrently (bounded by [maxConcurrentAgents]
 * and each role's [TeamRoleConfig.maxConcurrentTasks]) while guaranteeing:
 * - tasks whose `affectedFiles` overlap never run at the same time (per-file
 *   mutexes, derived from the same normalization as
 *   [TeamReviewGatekeeper.detectFileConflicts]);
 * - the team budget quota is checked before every launch and halts dispatch;
 * - a failed task blocks its dependents via the scheduler (never silently skipped);
 * - cancellation between stages stops new launches; in-flight agent runs are
 *   left to finish rather than being unsafely killed.
 *
 * All task results, artifacts, messages and reviews produced here are genuine
 * (never simulated). The dispatcher itself performs no demo-mode simulation.
 */
class ConcurrentTeamDispatcher(
    private val scheduler: TeamTaskScheduler,
    private val blackboard: TeamBlackboard,
    private val commBus: TeamCommunicationBus,
    private val gatekeeper: TeamReviewGatekeeper,
    private val budgetTracker: TeamBudgetTracker,
    private val executor: TeamAgentExecutor,
    private val validator: TeamRoleValidator = TeamRoleValidator(),
    private val roleConfigs: Map<TeamRole, TeamRoleConfig> = emptyMap(),
    private val roleAgents: Map<TeamRole, AgentKind> = emptyMap(),
    private val projectDir: File? = null,
    private val projectBinding: TeamProjectBinding? = null,
    private val goal: String = "",
    private val maxConcurrentAgents: Int = 3,
    /** Resolves a custom agent id to its definition; null when custom agents are unavailable. */
    private val customAgentResolver: ((String) -> com.vayro.app.agent.team.model.CustomTeamAgent?)? = null,
    /**
     * Adaptive resource-aware scheduler. When present, device pressure is
     * re-evaluated before every stage and every 30s during the run; the stage
     * semaphore is rebuilt from the live effective count. In-flight tasks are
     * never killed by throttling.
     */
    private val concurrencyController: AdaptiveConcurrencyController? = null,
) {
    private val cancelled = AtomicBoolean(false)
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    fun cancel() {
        cancelled.set(true)
    }

    /** Cancels the background scope; called via AgentTeamOrchestrator.shutdown(). */
    fun shutdown() {
        concurrencyController?.shutdown()
    }

    /**
     * Executes one READY task through the real runtime (used by the
     * orchestrator's single-step path). Returns the aggregated outcome.
     */
    suspend fun runReadyTask(task: TeamTask): TeamTaskOutcome {
        return executeOne(task).outcome
    }

    /**
     * Runs the full DAG to completion: stage by stage, tasks within a stage
     * concurrently. Returns the aggregated [TeamRunReport].
     */
    suspend fun runAll(): TeamRunReport = coroutineScope {
        val startedAt = System.currentTimeMillis()
        val outcomes = mutableListOf<TeamTaskOutcome>()
        val reviewRecords = mutableListOf<ReviewRecord>()

        val stages = scheduler.getExecutionStages()
        if (stages.isEmpty() && scheduler.getTasks().isNotEmpty()) {
            commBus.escalateToManager(
                fromRole = TeamRole.MANAGER,
                reason = "Task graph has a dependency cycle or is unorderable; refusing to dispatch.",
            )
            return@coroutineScope TeamRunReport(
                goal = goal,
                outcomes = scheduler.getTasks().map {
                    TeamTaskOutcome(it, it.status, "Not dispatched: unorderable task graph.", 0L, null)
                },
                reviews = emptyList(),
                totalDurationMs = System.currentTimeMillis() - startedAt,
                cancelled = true,
            )
        }

        val controller = concurrencyController
        controller?.startMonitoring()
        try {
            for (stage in stages) {
                if (cancelled.get()) break
                if (budgetTracker.isQuotaExceeded()) {
                    commBus.escalateToManager(
                        fromRole = TeamRole.MANAGER,
                        reason = "Token quota exceeded; halting dispatch before stage of ${stage.size} tasks.",
                    )
                    break
                }
                // Adaptive scheduling: re-read device pressure before each stage
                // and size this stage's parallelism from the live effective count.
                controller?.evaluateNow()
                val stageLimit = (controller?.currentMaxAgents() ?: maxConcurrentAgents).coerceAtLeast(1)
                val semaphore = Semaphore(stageLimit)

                val stageOutcomes = stage
                    // NOTE: stage lists snapshot task state at computation time; re-check
                    // liveness against the scheduler so tasks unlocked by the previous
                    // stage are actually dispatched.
                    .filter { scheduler.getTask(it.id)?.status == TeamTaskStatus.READY }
                    .map { task ->
                        async(Dispatchers.Default) {
                            semaphore.withPermit { executeOne(task) }
                        }
                    }
                    .awaitAll()
                stageOutcomes.forEach { (outcome, reviews) ->
                    outcomes += outcome
                    reviewRecords += reviews
                }
            }
        } finally {
            controller?.stopMonitoring()
        }

        TeamRunReport(
            goal = goal,
            outcomes = outcomes,
            reviews = reviewRecords,
            totalDurationMs = System.currentTimeMillis() - startedAt,
            cancelled = cancelled.get(),
        )
    }

    private data class ExecutedPair(val outcome: TeamTaskOutcome, val reviews: List<ReviewRecord>)

    /**
     * Executes a single task with per-file locking, real execution, genuine
     * artifact publishing, real validation for TESTER/SECURITY_REVIEWER roles,
     * and inter-agent messaging (status updates + handoffs to dependents).
     */
    private suspend fun executeOne(task: TeamTask): ExecutedPair {
        val taskStartedAt = System.currentTimeMillis()
        val reviews = mutableListOf<ReviewRecord>()

        if (cancelled.get()) {
            return ExecutedPair(
                TeamTaskOutcome(task, TeamTaskStatus.PENDING, "Skipped: run cancelled.", 0L, null),
                emptyList(),
            )
        }

        scheduler.updateTaskStatus(task.id, TeamTaskStatus.IN_PROGRESS)
        commBus.sendMessage(
            fromRole = task.assignedRole,
            toRole = null,
            type = TeamMessageType.STATUS_UPDATE,
            content = "${task.title} started.",
            taskId = task.id,
        )

        // Acquire per-file locks so overlapping affectedFiles never run concurrently.
        val locks = task.affectedFiles
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { fileLocks.getOrPut(it) { Mutex() } }

        val execResult = try {
            locks.forEach { it.lock() }
            try {
                val customAgent = task.assignedCustomAgentId?.let { customAgentResolver?.invoke(it) }
                val baseConfig = roleConfigs[task.assignedRole] ?: TeamRoleConfig(task.assignedRole)
                val effectiveConfig = if (customAgent != null) {
                    baseConfig.copy(
                        systemPrompt = customAgent.systemPrompt.ifBlank { baseConfig.systemPrompt },
                        permissions = customAgent.permissions,
                        defaultAgentKind = customAgent.defaultAgentKind
                    )
                } else baseConfig
                val context = TeamExecutionContext(
                    projectDir = projectDir,
                    projectBinding = projectBinding,
                    goal = goal,
                    role = task.assignedRole,
                    roleConfig = effectiveConfig,
                    customAgent = customAgent,
                    agentKind = customAgent?.defaultAgentKind
                        ?: roleAgents[task.assignedRole]
                        ?: AgentKind.ANTIGRAVITY,
                    dependencyOutputs = dependencyOutputsOf(task),
                    blackboardSummary = summarizeBlackboard(),
                    timeoutMs = 20L * 60L * 1000L,
                )
                executor.execute(task, context)
            } finally {
                locks.forEach { runCatching { it.unlock() } }
            }
        } catch (e: Exception) {
            scheduler.updateTaskStatus(task.id, TeamTaskStatus.FAILED, output = "Dispatcher error: ${e.message}")
            commBus.escalateToManager(task.assignedRole, "Dispatcher error on '${task.title}': ${e.message}", task.id)
            return ExecutedPair(
                TeamTaskOutcome(task, TeamTaskStatus.FAILED, "Dispatcher error: ${e.message}", System.currentTimeMillis() - taskStartedAt, null),
                emptyList(),
            )
        }

        val durationMs = System.currentTimeMillis() - taskStartedAt

        if (!execResult.result.isSuccess) {
            scheduler.updateTaskStatus(
                taskId = task.id,
                status = TeamTaskStatus.FAILED,
                // Preserve the real failure reason: fail-fast paths (e.g.
                // "runtime not installed") carry it in result.error with
                // empty outputText. Dropping it left the UI with no cause.
                output = execResult.outputText.ifBlank {
                    execResult.result.error ?: execResult.result.status.toString()
                },
                tokensUsed = execResult.tokensUsed ?: 0L,
                executionTimeMs = durationMs,
                isSimulated = false,
            )
            budgetTracker.recordUsage(task.assignedRole, execResult.tokensUsed ?: 0L, durationMs)
            commBus.escalateToManager(
                task.assignedRole,
                "'${task.title}' failed: ${execResult.result.error ?: execResult.result.status}.",
                task.id,
            )
            return ExecutedPair(
                TeamTaskOutcome(task, TeamTaskStatus.FAILED, execResult.outputText.take(500), durationMs, execResult.tokensUsed),
                emptyList(),
            )
        }

        // Genuine artifacts onto the blackboard.
        execResult.artifacts.forEach { blackboard.publishArtifact(it) }

        // Real validation for TESTER / SECURITY_REVIEWER roles.
        var reviewVerdict: ReviewVerdict? = execResult.reviewVerdict
        when (task.assignedRole) {
            TeamRole.TESTER -> {
                val validation = validator.validateTester(task, projectDir)
                blackboard.publishArtifact(validation.artifact)
                reviewVerdict = validation.verdict
                val record = gatekeeper.submitReview(task.id, TeamRole.TESTER, validation.verdict, validation.summary, isSimulated = false)
                reviews += record
                commBus.sendMessage(task.assignedRole, null, TeamMessageType.STATUS_UPDATE, validation.summary, task.id)
            }
            TeamRole.SECURITY_REVIEWER -> {
                val validation = validator.validateSecurity(task, projectDir)
                blackboard.publishArtifact(validation.artifact)
                reviewVerdict = validation.verdict
                val record = gatekeeper.submitReview(task.id, TeamRole.SECURITY_REVIEWER, validation.verdict, validation.summary, isSimulated = false)
                reviews += record
                commBus.sendMessage(task.assignedRole, null, TeamMessageType.STATUS_UPDATE, validation.summary, task.id)
            }
            TeamRole.CODE_REVIEWER -> {
                // A code-review approval is recorded only when the agent emitted an explicit verdict marker.
                if (reviewVerdict != null) {
                    val record = gatekeeper.submitReview(
                        taskId = task.id,
                        reviewerRole = TeamRole.CODE_REVIEWER,
                        verdict = reviewVerdict,
                        comments = execResult.outputText.take(2000),
                        isSimulated = false,
                    )
                    reviews += record
                } else {
                    commBus.sendMessage(
                        task.assignedRole, null, TeamMessageType.STATUS_UPDATE,
                        "Code review produced no explicit verdict marker; no approval recorded.", task.id,
                    )
                }
            }
            else -> {}
        }

        val taskSucceeded = when (task.assignedRole) {
            TeamRole.TESTER, TeamRole.SECURITY_REVIEWER -> reviewVerdict == ReviewVerdict.APPROVED
            else -> true
        }

        val finalStatus = if (taskSucceeded) TeamTaskStatus.COMPLETED else TeamTaskStatus.FAILED
        scheduler.updateTaskStatus(
            taskId = task.id,
            status = finalStatus,
            output = execResult.outputText,
            tokensUsed = execResult.tokensUsed ?: 0L,
            executionTimeMs = durationMs,
            isSimulated = false,
        )
        budgetTracker.recordUsage(task.assignedRole, execResult.tokensUsed ?: 0L, durationMs)
        budgetTracker.recordTaskCompleted(task.assignedRole)

        commBus.sendMessage(
            fromRole = task.assignedRole,
            toRole = null,
            type = TeamMessageType.STATUS_UPDATE,
            content = "${task.title} ${finalStatus.name.lowercase()} in ${durationMs}ms.",
            taskId = task.id,
        )
        // Handoff: notify dependents with the real output summary.
        scheduler.getTasks()
            .filter { it.dependencies.contains(task.id) }
            .forEach { dependent ->
                commBus.sendMessage(
                    fromRole = task.assignedRole,
                    toRole = dependent.assignedRole,
                    type = TeamMessageType.HANDOFF,
                    content = "Handoff for '${dependent.title}': prerequisite '${task.title}' ${finalStatus.name.lowercase()}. " +
                        "Output: ${execResult.outputText.take(1000)}",
                    taskId = dependent.id,
                )
            }

        return ExecutedPair(
            TeamTaskOutcome(task, finalStatus, execResult.outputText.take(500), durationMs, execResult.tokensUsed, reviewVerdict),
            reviews,
        )
    }

    private fun dependencyOutputsOf(task: TeamTask): Map<String, String> {
        val linked = LinkedHashMap<String, String>()
        task.dependencies.forEach { depId ->
            scheduler.getTask(depId)?.let { linked[depId] = it.output }
        }
        return linked
    }

    private fun summarizeBlackboard(): String {
        val artifacts = blackboard.getAllArtifacts().takeLast(12)
        if (artifacts.isEmpty()) return ""
        return artifacts.joinToString("\n") { "- [${it.type.label}] ${it.title}: ${it.content.take(300)}" }
    }
}

/** How often device pressure is re-evaluated while a team run is active. */
private const val MONITOR_INTERVAL_MS = 30_000L

private const val GB = 1024L * 1024L * 1024L

/**
 * Smart resource-aware scheduler for parallel agent execution.
 *
 * Keeps the whole app smooth whether 1 or 3 agents run, on old phones and new
 * phones alike. The parallel agent count auto-adapts to device load:
 *
 * Baseline (computed once at startup from hardware):
 * - 1 agent when total RAM is 3 GB or less, or CPU cores are 4 or fewer
 * - 2 agents when total RAM is 6 GB or less
 * - 3 agents above that (never more than 3)
 *
 * Dynamic throttling (re-evaluated before every dispatch stage and every 30s
 * during a run; scales back up when pressure clears, never above baseline):
 * - thermal state MODERATE or worse: drop to 1 agent
 * - available RAM below 15% of total: drop to 1; below 25%: drop to 2
 * - battery below 15% and not charging: drop to 1 agent
 *
 * In-flight agent runs are never killed by throttling; the new limit applies
 * to newly dispatched stages.
 */
class AdaptiveConcurrencyController(context: Context) {

    data class ThrottleState(
        /** Agents allowed to run concurrently right now. */
        val effectiveMaxAgents: Int,
        /** Hardware baseline; the effective count never exceeds this. */
        val baselineMaxAgents: Int,
        /** Human-readable reason shown in the UI, null when not throttled. */
        val throttleReason: String?,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Hardware baseline, computed once at startup. */
    val baselineMaxAgents: Int = computeBaseline()

    private val _state = MutableStateFlow(
        ThrottleState(
            effectiveMaxAgents = baselineMaxAgents,
            baselineMaxAgents = baselineMaxAgents,
            throttleReason = null,
        )
    )

    /** Observable throttle state for the UI header ("2 agents running (thermal throttle)"). */
    val state: StateFlow<ThrottleState> = _state.asStateFlow()

    /** Current allowed parallelism (thread-safe read). */
    fun currentMaxAgents(): Int = _state.value.effectiveMaxAgents

    init {
        scope.launch { evaluateNow() }
    }

    private fun computeBaseline(): Int {
        val totalMemBytes = readTotalMemBytes()
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            totalMemBytes <= 3 * GB || cores <= 4 -> 1
            totalMemBytes <= 6 * GB -> 2
            else -> 3
        }.coerceIn(1, 3)
    }

    /**
     * Re-reads thermal, memory and battery pressure right now and updates the
     * effective agent count. Safe to call from any thread.
     */
    suspend fun evaluateNow() = withContext(Dispatchers.Default) {
        val baseline = baselineMaxAgents
        var effective = baseline
        var reason: String? = null

        // Thermal pressure wins: a hot phone must cool down first.
        if (readThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE) {
            effective = 1
            reason = "thermal throttle"
        }

        // Memory pressure.
        val availRatio = readAvailableMemRatio()
        val memLimit = when {
            availRatio < 0.15 -> 1
            availRatio < 0.25 -> 2
            else -> baseline
        }
        if (memLimit < effective) {
            effective = memLimit
            reason = "low memory"
        }

        // Battery: only throttles when low AND not charging.
        val battery = readBattery()
        if (battery != null && battery.level in 0..14 && !battery.charging && effective > 1) {
            effective = 1
            reason = "low battery"
        }

        _state.value = ThrottleState(
            effectiveMaxAgents = effective.coerceIn(1, 3),
            baselineMaxAgents = baseline,
            throttleReason = reason,
        )
    }

    private var monitorJob: Job? = null

    /** Starts the 30s pressure monitor; call when a team run begins. Idempotent. */
    fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                delay(MONITOR_INTERVAL_MS)
                evaluateNow()
            }
        }
    }

    /** Stops the pressure monitor; call when a team run ends. Idempotent. */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /** Cancels the background scope; called when the owning ViewModel is cleared. */
    fun shutdown() {
        stopMonitoring()
        scope.cancel()
    }

    private fun readTotalMemBytes(): Long {
        val activityManager =
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0L
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.totalMem
    }

    private fun readAvailableMemRatio(): Double {
        val activityManager =
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 1.0
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        if (info.totalMem <= 0L) return 1.0
        return info.availMem.toDouble() / info.totalMem.toDouble()
    }

    private fun readThermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return PowerManager.THERMAL_STATUS_NONE
        val powerManager =
            appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return PowerManager.THERMAL_STATUS_NONE
        return powerManager.currentThermalStatus
    }

    private data class BatteryState(val level: Int, val charging: Boolean)

    private fun readBattery(): BatteryState? {
        val batteryManager =
            appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return null
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level < 0) return null // unknown
        val charging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            batteryManager.isCharging
        } else {
            false
        }
        return BatteryState(level, charging)
    }
}
