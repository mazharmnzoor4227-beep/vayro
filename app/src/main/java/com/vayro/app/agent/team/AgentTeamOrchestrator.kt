package com.vayro.app.agent.team

import com.vayro.app.agent.team.blackboard.TeamBlackboard
import com.vayro.app.agent.team.budget.TeamBudgetTracker
import com.vayro.app.agent.team.comm.TeamCommunicationBus
import com.vayro.app.agent.team.model.ArtifactType
import com.vayro.app.agent.team.model.BuiltinAgentOverride
import com.vayro.app.agent.team.model.ConflictDetectionResult
import com.vayro.app.agent.team.model.CustomTeamAgent
import com.vayro.app.agent.team.model.ReviewRecord
import com.vayro.app.agent.team.model.ReviewVerdict
import com.vayro.app.agent.team.model.RoleBudgetUsage
import com.vayro.app.agent.team.model.TeamArtifact
import com.vayro.app.agent.team.model.TeamBudget
import com.vayro.app.agent.team.model.TeamCollaborationEvent
import com.vayro.app.agent.team.model.TeamMessage
import com.vayro.app.agent.team.model.TeamMessageType
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.model.TeamRoleConfig
import com.vayro.app.agent.team.model.TeamRolePermissions
import com.vayro.app.agent.team.model.TeamSessionState
import com.vayro.app.agent.team.model.TeamTask
import com.vayro.app.agent.team.model.TeamTaskPriority
import com.vayro.app.agent.team.model.TeamTaskStatus
import com.vayro.app.agent.team.review.TeamReviewGatekeeper
import com.vayro.app.agent.team.runtime.ConcurrentTeamDispatcher
import com.vayro.app.agent.team.runtime.AdaptiveConcurrencyController
import com.vayro.app.agent.team.runtime.LiveTaskInfo
import com.vayro.app.agent.team.runtime.LiveTaskStatus
import com.vayro.app.agent.team.runtime.RealTeamAgentExecutor
import com.vayro.app.agent.team.runtime.TeamAgentExecutor
import com.vayro.app.agent.team.runtime.TeamRoleValidator
import com.vayro.app.agent.team.runtime.TeamRunReport
import com.vayro.app.agent.team.scheduler.TeamTaskScheduler
import com.vayro.app.model.AgentKind
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Typealiases for backward compatibility with existing codebase
typealias TeamRole = com.vayro.app.agent.team.model.TeamRole
typealias TeamTaskStatus = com.vayro.app.agent.team.model.TeamTaskStatus
typealias TeamTask = com.vayro.app.agent.team.model.TeamTask
typealias TeamCollaborationEvent = com.vayro.app.agent.team.model.TeamCollaborationEvent
typealias TeamSessionState = com.vayro.app.agent.team.model.TeamSessionState

/**
 * Multi-Agent Engineering Team Coordinator (Phase 11 v2.2).
 * Orchestrates an autonomous 10-role engineering squad:
 * - Manager: Task decomposition, progress tracking, budget oversight
 * - Architect: Architecture design, interface contracts, ADRs
 * - Developer: Core logic implementation
 * - UI Engineer: Compose screens & UX
 * - Backend Engineer: API endpoints & data pipelines
 * - Database Engineer: Schemas, queries, caching
 * - Tester: Automated test suites & QA validation
 * - Security Reviewer: Vulnerability scans & risk audits
 * - Code Reviewer: Code quality gates & peer reviews
 * - DevOps Engineer: CI/CD, Gradle scripts, packaging
 */
class AgentTeamOrchestrator(
    val blackboard: TeamBlackboard = TeamBlackboard(),
    val scheduler: TeamTaskScheduler = TeamTaskScheduler(),
    val commBus: TeamCommunicationBus = TeamCommunicationBus(),
    val gatekeeper: TeamReviewGatekeeper = TeamReviewGatekeeper(),
    val budgetTracker: TeamBudgetTracker = TeamBudgetTracker(),
    /**
     * Real per-role agent executor. Required: without it the orchestrator
     * refuses to run ([requireRealExecution]) instead of simulating output.
     */
    val teamExecutor: TeamAgentExecutor? = null,
    /**
     * Real test/security validator used in real mode. Defaults to the genuine
     * Gradle + heuristic-scan implementation.
     */
    val teamValidator: TeamRoleValidator = TeamRoleValidator(),
    /** Project directory the real agents work in; null when no project is bound. */
    val projectDir: File? = null,
    /** Upper bound on concurrently executing agents in real mode. */
    val maxConcurrentAgents: Int = 3,
    /**
     * Adaptive resource-aware scheduler. When present, the hardware baseline
     * caps [maxConcurrentAgents] and thermal/memory/battery pressure throttles
     * it dynamically during runs.
     */
    val concurrencyController: AdaptiveConcurrencyController? = null,
) {

    private val _sessionState = MutableStateFlow(TeamSessionState())
    val sessionState: StateFlow<TeamSessionState> = _sessionState.asStateFlow()

    private val roleInstances = ConcurrentHashMap<TeamRole, AgentKind>()
    private val roleConfigs = ConcurrentHashMap<TeamRole, TeamRoleConfig>()

    /**
     * Resolves custom agent ids to definitions. Set by MainViewModel from
     * CustomTeamAgentStore; the dispatcher consults it per task.
     */
    var customAgentResolver: ((String) -> CustomTeamAgent?)? = null

    /**
     * Existing project the next runs are bound to; null means runs work in a
     * synthetic per-goal team workspace. Set via [bindProject] before planning.
     */
    @Volatile
    var boundProject: com.vayro.app.agent.team.runtime.TeamProjectBinding? = null
        private set

    /** Binds subsequent runs to an existing project (null unbinds). */
    fun bindProject(binding: com.vayro.app.agent.team.runtime.TeamProjectBinding?) {
        boundProject = binding
    }

    /**
     * Applies user customizations of built-in agents (instructions and
     * permission overrides) on top of the default role configs.
     */
    fun applyBuiltinOverrides(overrides: Map<TeamRole, BuiltinAgentOverride>) {
        overrides.forEach { (role, override) ->
            val base = roleConfigs[role] ?: TeamRoleConfig(role)
            roleConfigs[role] = base.copy(
                systemPrompt = override.systemPrompt.ifBlank { base.systemPrompt },
                permissions = override.permissions ?: base.permissions
            )
        }
    }

    /**
     * Live per-agent execution snapshots from the real executor (Antigravity-style
     * observability). Empty when no real executor is wired.
     */
    val liveTasks: StateFlow<Map<String, LiveTaskInfo>> =
        (teamExecutor as? RealTeamAgentExecutor)?.liveTasks
            ?: MutableStateFlow<Map<String, LiveTaskInfo>>(emptyMap())

    /**
     * Adaptive throttle state for the UI header
     * ("2 agents running (thermal throttle)"). Never throttled without a controller.
     */
    val throttleState: StateFlow<AdaptiveConcurrencyController.ThrottleState> =
        concurrencyController?.state
            ?: MutableStateFlow(
                AdaptiveConcurrencyController.ThrottleState(
                    effectiveMaxAgents = maxConcurrentAgents,
                    baselineMaxAgents = maxConcurrentAgents,
                    throttleReason = null,
                )
            )

    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cancels background collectors; called from MainViewModel.onCleared(). */
    fun shutdown() {
        observerScope.cancel()
        (teamExecutor as? RealTeamAgentExecutor)?.shutdown()
        synchronized(dispatcherLock) { dispatcherCache?.second }?.shutdown()
    }

    private val dispatcherLock = Any()
    private var dispatcherCache: Pair<Pair<com.vayro.app.agent.team.runtime.TeamProjectBinding?, String>, ConcurrentTeamDispatcher>? = null

    /**
     * Real dispatcher (Milestone 10), rebuilt whenever the bound project or
     * the active goal changes so a run never inherits a previous run's
     * binding or goal. Null until a [teamExecutor] is wired;
     * [requireRealExecution] guarantees real mode never runs without it.
     */
    private fun realDispatcher(): ConcurrentTeamDispatcher? {
        val executor = teamExecutor ?: return null
        val key = boundProject to _sessionState.value.activeGoal
        return synchronized(dispatcherLock) {
            val cached = dispatcherCache
            if (cached != null && cached.first == key) {
                cached.second
            } else {
                val built = ConcurrentTeamDispatcher(
                    scheduler = scheduler,
                    blackboard = blackboard,
                    commBus = commBus,
                    gatekeeper = gatekeeper,
                    budgetTracker = budgetTracker,
                    executor = executor,
                    validator = teamValidator,
                    roleConfigs = roleConfigs.toMap(),
                    roleAgents = roleInstances.toMap(),
                    projectDir = projectDir,
                    projectBinding = key.first,
                    goal = key.second,
                    customAgentResolver = customAgentResolver,
                    // Hardware baseline caps the configured maximum; dynamic pressure
                    // throttles it further per stage inside the dispatcher.
                    maxConcurrentAgents = minOf(
                        maxConcurrentAgents,
                        concurrencyController?.baselineMaxAgents ?: maxConcurrentAgents
                    ),
                    concurrencyController = concurrencyController,
                )
                dispatcherCache = key to built
                built
            }
        }
    }

    /**
     * Enforces the real-execution contract: the orchestrator only runs with a
     * real [TeamAgentExecutor] wired. Without one it fails loudly instead of
     * simulating output.
     */
    fun requireRealExecution() {
        if (teamExecutor == null) {
            throw IllegalStateException(
                "Multi-agent mode requires a TeamAgentExecutor: no real executor is wired. " +
                    "Pass a real executor; simulated execution does not exist."
            )
        }
    }

    init {
        // Register default agent engines and configurations for all 10 roles
        TeamRole.entries.forEach { role ->
            val kind = when (role) {
                TeamRole.MANAGER, TeamRole.ARCHITECT, TeamRole.CODE_REVIEWER -> AgentKind.CLAUDE_CODE
                TeamRole.SECURITY_REVIEWER, TeamRole.DEVOPS_ENGINEER -> AgentKind.ANTIGRAVITY
                else -> AgentKind.ANTIGRAVITY
            }
            roleInstances[role] = kind
            roleConfigs[role] = TeamRoleConfig(
                role = role,
                defaultAgentKind = kind,
                permissions = TeamRolePermissions.forRole(role)
            )
        }

        // Synchronize Blackboard changes into session state
        blackboard.subscribe { _ ->
            syncState()
        }

        // Synchronize CommBus messages into session state
        commBus.subscribe { _ ->
            syncState()
        }

        // Mirror live parallel-execution state into the session: the UI reads
        // activeTaskCount to show "N agents running".
        observerScope.launch {
            liveTasks.collect { tasks ->
                val running = tasks.count { it.value.status == LiveTaskStatus.RUNNING }
                _sessionState.update { it.copy(activeTaskCount = running) }
            }
        }
    }

    /**
     * Decomposes a user goal into an interconnected 10-role engineering DAG.
     */
    fun planProject(userGoal: String): List<TeamTask> =
        planProject(userGoal, customAgents = emptyList())

    /**
     * Plans the project DAG, then appends one task per suggested custom agent.
     * Custom agent tasks specialize their base role and run with the agent's
     * own instructions and permissions.
     */
    fun planProject(userGoal: String, customAgents: List<CustomTeamAgent>): List<TeamTask> {
        blackboard.clear()
        commBus.clear()
        gatekeeper.clear()
        budgetTracker.reset()

        val managerTaskId = UUID.randomUUID().toString()
        val architectTaskId = UUID.randomUUID().toString()
        val dbTaskId = UUID.randomUUID().toString()
        val backendTaskId = UUID.randomUUID().toString()
        val uiTaskId = UUID.randomUUID().toString()
        val devTaskId = UUID.randomUUID().toString()
        val testerTaskId = UUID.randomUUID().toString()
        val secTaskId = UUID.randomUUID().toString()
        val codeReviewTaskId = UUID.randomUUID().toString()
        val devopsTaskId = UUID.randomUUID().toString()

        val tasks = listOf(
            TeamTask(
                id = managerTaskId,
                title = "Sprint Planning & Goal Decomposition",
                description = "Define scope, milestones, and deliverable targets for: $userGoal",
                assignedRole = TeamRole.MANAGER,
                priority = TeamTaskPriority.CRITICAL,
                dependencies = emptyList(),
                affectedFiles = listOf("docs/sprint_plan.md")
            ),
            TeamTask(
                id = architectTaskId,
                title = "System Architecture & API Contracts",
                description = "Produce system blueprint, module boundaries, and interface contracts.",
                assignedRole = TeamRole.ARCHITECT,
                priority = TeamTaskPriority.CRITICAL,
                dependencies = listOf(managerTaskId),
                affectedFiles = listOf("docs/adr_001.md", "core/contract.kt")
            ),
            TeamTask(
                id = dbTaskId,
                title = "Database Schemas & Data Model Design",
                description = "Construct database schemas, Room entities, and migration definitions.",
                assignedRole = TeamRole.DATABASE_ENGINEER,
                priority = TeamTaskPriority.HIGH,
                dependencies = listOf(architectTaskId),
                affectedFiles = listOf("db/schema.sql", "db/entity.kt")
            ),
            TeamTask(
                id = backendTaskId,
                title = "Backend Service & Data Pipeline Logic",
                description = "Develop business endpoints, repository layer, and network services.",
                assignedRole = TeamRole.BACKEND_ENGINEER,
                priority = TeamTaskPriority.HIGH,
                dependencies = listOf(architectTaskId, dbTaskId),
                affectedFiles = listOf("backend/service.kt", "backend/repository.kt")
            ),
            TeamTask(
                id = uiTaskId,
                title = "Compose Screen UI & Responsive Layouts",
                description = "Build Material 3 user interfaces, design tokens, and state bindings.",
                assignedRole = TeamRole.UI_ENGINEER,
                priority = TeamTaskPriority.HIGH,
                dependencies = listOf(architectTaskId),
                affectedFiles = listOf("ui/screen.kt", "ui/components.kt")
            ),
            TeamTask(
                id = devTaskId,
                title = "Fullstack Integration & Feature Implementation",
                description = "Integrate UI components with backend repositories and state models.",
                assignedRole = TeamRole.DEVELOPER,
                priority = TeamTaskPriority.HIGH,
                dependencies = listOf(backendTaskId, uiTaskId),
                affectedFiles = listOf("app/feature.kt", "app/viewmodel.kt")
            ),
            TeamTask(
                id = testerTaskId,
                title = "Automated Unit & Integration Test Suite",
                description = "Author test cases covering happy paths, error states, and edge conditions.",
                assignedRole = TeamRole.TESTER,
                priority = TeamTaskPriority.HIGH,
                dependencies = listOf(devTaskId),
                affectedFiles = listOf("test/FeatureUnitTest.kt")
            ),
            TeamTask(
                id = secTaskId,
                title = "Security Audit & Risk Assessment",
                description = "Scan source code for leaks, unsafe inputs, and permission flaws.",
                assignedRole = TeamRole.SECURITY_REVIEWER,
                priority = TeamTaskPriority.HIGH,
                dependencies = listOf(devTaskId),
                affectedFiles = listOf("docs/security_audit.md")
            ),
            TeamTask(
                id = codeReviewTaskId,
                title = "Code Quality & Architectural Review Gate",
                description = "Perform comprehensive code review, SOLID enforcement, and peer sign-off.",
                assignedRole = TeamRole.CODE_REVIEWER,
                priority = TeamTaskPriority.CRITICAL,
                dependencies = listOf(testerTaskId, secTaskId),
                affectedFiles = listOf("docs/code_review.md")
            ),
            TeamTask(
                id = devopsTaskId,
                title = "CI/CD & Release Build Packaging",
                description = "Configure build sanity scripts, verify Gradle tasks, and package artifact.",
                assignedRole = TeamRole.DEVOPS_ENGINEER,
                priority = TeamTaskPriority.MEDIUM,
                dependencies = listOf(codeReviewTaskId),
                affectedFiles = listOf("build.gradle.kts", ".github/workflows/ci.yml")
            )
        )

        scheduler.setTasks(tasks)

        val customTasks = customAgents.filter { it.isEnabled }.map { agent ->
            TeamTask(
                title = "${agent.name}: ${userGoal.take(60)}",
                description = agent.description,
                assignedRole = agent.baseRole,
                assignedCustomAgentId = agent.id,
                priority = TeamTaskPriority.MEDIUM,
                dependencies = listOf(architectTaskId),
                affectedFiles = emptyList()
            )
        }
        if (customTasks.isNotEmpty()) {
            scheduler.setTasks(tasks + customTasks)
            customTasks.forEach { task ->
                val agent = customAgents.first { it.id == task.assignedCustomAgentId }
                commBus.sendMessage(
                    fromRole = TeamRole.MANAGER,
                    toRole = task.assignedRole,
                    type = TeamMessageType.HANDOFF,
                    content = "Custom agent '${agent.name}' added to this run (suggested for this goal).",
                    taskId = task.id
                )
            }
        }

        // Publish project blueprint into Shared Blackboard
        blackboard.publishArtifact(
            TeamArtifact(
                title = "System Architecture Blueprint: $userGoal",
                type = ArtifactType.ADR,
                authorRole = TeamRole.MANAGER,
                content = "High-level architectural blueprint decomposing '$userGoal' into 10 synchronized roles.",
                tags = setOf("architecture", "init", "sprint-1")
            )
        )

        // Publish kickoff announcement into Communication Bus
        commBus.sendMessage(
            fromRole = TeamRole.MANAGER,
            toRole = null,
            type = TeamMessageType.BROADCAST,
            content = "Project goal accepted: '$userGoal'. Initialized 10 engineering tasks across team DAG."
        )

        syncState(userGoal = userGoal, isRunning = true, activeRole = TeamRole.MANAGER, lastError = null)
        return tasks + customTasks
    }

    /**
     * Executes the next READY task through the real agent runtime (Milestone 10).
     *
     * The task genuinely executes via [teamExecutor]: real output, genuine
     * artifacts on the blackboard, real validation + review records for
     * TESTER/SECURITY_REVIEWER/CODE_REVIEWER roles, and handoff messages to
     * dependents. Failed tasks are marked FAILED (dependents block via the
     * scheduler) — never silently completed.
     */
    suspend fun executeNextReadyTask(): TeamTask? {
        requireRealExecution()
        val dispatcher = realDispatcher()
            ?: throw IllegalStateException("Real dispatcher unavailable without a TeamAgentExecutor.")
        if (budgetTracker.isQuotaExceeded()) {
            commBus.escalateToManager(
                fromRole = TeamRole.MANAGER,
                reason = "Token quota exceeded (${budgetTracker.getBudgetSummary().totalTokensUsed} tokens). Halting team execution."
            )
            _sessionState.value = _sessionState.value.copy(isRunning = false)
            return null
        }

        val readyTasks = scheduler.getReadyTasks()
        if (readyTasks.isEmpty()) {
            if (scheduler.isAllCompleted()) {
                _sessionState.value = _sessionState.value.copy(isRunning = false, activeRole = null)
            } else if (scheduler.isStalled()) {
                // Nothing can proceed: a failed task blocked the whole DAG.
                // Say so plainly instead of leaving Step as a dead button.
                _sessionState.update { it.copy(lastError = stalledErrorMessage()) }
            }
            return null
        }

        val task = readyTasks.first()
        _sessionState.value = _sessionState.value.copy(isRunning = true, activeRole = task.assignedRole)
        dispatcher.runReadyTask(task)
        val finished = scheduler.getTask(task.id)
        if (finished?.status == TeamTaskStatus.FAILED) {
            _sessionState.update {
                it.copy(lastError = "Task '${finished.title}' failed: ${finished.output.ifBlank { "no error detail" }.take(300)}")
            }
        }
        syncState()
        return finished
    }

    /**
     * Runs the whole task DAG through the real runtime with safe concurrent
     * stage dispatch (Milestone 10). Returns the aggregated [TeamRunReport].
     */
    suspend fun autoRunAllReal(): TeamRunReport {
        requireRealExecution()
        val dispatcher = realDispatcher()
            ?: throw IllegalStateException("Real dispatcher unavailable without a TeamAgentExecutor.")
        _sessionState.update { it.copy(isRunning = true) }
        val report = dispatcher.runAll()
        if (report.failed > 0 || scheduler.isStalled()) {
            _sessionState.update { it.copy(lastError = stalledErrorMessage()) }
        }
        syncState()
        return report
    }

    /**
     * Re-queues every FAILED task as READY so a stalled run can be retried
     * after the underlying cause (e.g. a missing agent runtime) is fixed.
     * BLOCKED dependents stay blocked until their failed dependency completes.
     */
    fun retryFailedTasks() {
        val failed = scheduler.getTasks().filter { it.status == TeamTaskStatus.FAILED }
        if (failed.isEmpty()) return
        failed.forEach { scheduler.updateTaskStatus(it.id, TeamTaskStatus.READY) }
        _sessionState.update { it.copy(isRunning = true, lastError = null) }
        syncState()
    }

    /** Dismisses the surfaced execution error banner. */
    fun clearLastError() {
        _sessionState.update { it.copy(lastError = null) }
    }

    /** Surfaces an unexpected execution error to the UI instead of dropping it. */
    fun reportError(message: String) {
        _sessionState.update { it.copy(lastError = message) }
    }

    /** Human-readable "run is stalled" message naming the failed tasks. */
    private fun stalledErrorMessage(): String {
        val failed = scheduler.getTasks().filter { it.status == TeamTaskStatus.FAILED }
        if (failed.isEmpty()) return "Run stalled: no task is ready and the run is not complete."
        val first = failed.first()
        val detail = first.output.ifBlank { "no error detail" }.take(300)
        val extra = if (failed.size > 1) " (+${failed.size - 1} more failed)" else ""
        return "Run stalled: '${first.title}' failed: $detail$extra. Fix the cause, then tap Retry failed."
    }

    /** Requests cancellation of a real run; in-flight agent runs finish rather than being killed. */
    fun cancelRealRun() {
        synchronized(dispatcherLock) { dispatcherCache?.second }?.cancel()
        _sessionState.value = _sessionState.value.copy(isRunning = false, activeRole = null)
    }

    fun updateTaskStatus(taskId: String, status: TeamTaskStatus, output: String = "", feedback: String = "") {
        scheduler.updateTaskStatus(taskId, status, output, feedback)
        syncState()
    }

    fun broadcastMessage(from: TeamRole, to: TeamRole?, message: String) {
        commBus.sendMessage(
            fromRole = from,
            toRole = to,
            type = if (to == null) TeamMessageType.BROADCAST else TeamMessageType.STATUS_UPDATE,
            content = message
        )
        syncState()
    }

    fun submitReview(taskId: String, reviewerRole: TeamRole, verdict: ReviewVerdict, comments: String): ReviewRecord {
        val record = gatekeeper.submitReview(taskId, reviewerRole, verdict, comments)
        commBus.sendMessage(
            fromRole = reviewerRole,
            toRole = null,
            type = TeamMessageType.FEEDBACK,
            content = "[Review ${verdict.name}]: $comments",
            taskId = taskId
        )
        syncState()
        return record
    }

    fun cancelSession() {
        _sessionState.value = _sessionState.value.copy(isRunning = false, activeRole = null)
    }

    private fun syncState(
        userGoal: String = _sessionState.value.activeGoal,
        isRunning: Boolean = _sessionState.value.isRunning,
        activeRole: TeamRole? = _sessionState.value.activeRole,
        lastError: String? = _sessionState.value.lastError
    ) {
        val allTasks = scheduler.getTasks()
        val allMessages = commBus.getMessages()
        val allArtifacts = blackboard.getAllArtifacts()
        val budget = budgetTracker.getBudgetSummary()
        val reviews = gatekeeper.getAllReviews()

        // Check for file conflicts
        val conflictResult = gatekeeper.detectFileConflicts(allTasks)
        val conflictWarnings = if (conflictResult.hasConflict) {
            conflictResult.conflictingFiles.map { "Potential write conflict detected on: $it" }
        } else {
            emptyList()
        }

        // Maintain backward-compatible events list
        val legacyEvents = allMessages.map {
            TeamCollaborationEvent(
                id = it.id,
                fromRole = it.fromRole,
                toRole = it.toRole,
                message = it.content,
                timestamp = it.timestamp
            )
        }

        val allDone = scheduler.isAllCompleted()
        val nextActiveRole = if (allDone) null else (scheduler.getReadyTasks().firstOrNull()?.assignedRole ?: activeRole)

        _sessionState.value = TeamSessionState(
            activeGoal = userGoal,
            // A stalled run (failed task blocked the DAG) is not "running":
            // the header must stop claiming "Executing" on a dead run.
            isRunning = isRunning && !allDone && !scheduler.isStalled(),
            activeRole = nextActiveRole,
            tasks = allTasks,
            events = legacyEvents,
            messages = allMessages,
            artifacts = allArtifacts,
            budget = budget,
            reviews = reviews,
            conflictWarnings = conflictWarnings,
            executionLog = allMessages.map { "[${it.fromRole.name}] ${it.content}" },
            lastError = lastError
        )
    }
}
