package com.vayro.app.agent.team.model

import com.vayro.app.model.AgentKind
import java.util.UUID

/**
 * 10 Specialized Roles in the Multi-Agent Engineering Team (Phase 11).
 */
enum class TeamRole(val title: String, val description: String) {
    MANAGER(
        title = "Engineering Manager",
        description = "Decomposes high-level requirements into subtasks, delegates, and oversees team velocity."
    ),
    ARCHITECT(
        title = "Software Architect",
        description = "Designs system blueprints, interfaces, data schemas, and technical contracts."
    ),
    DEVELOPER(
        title = "Fullstack Developer",
        description = "Implements business logic, APIs, UI components, and integrations."
    ),
    UI_ENGINEER(
        title = "UI/UX Specialist",
        description = "Refines user interfaces, Compose views, animations, and responsive layouts."
    ),
    BACKEND_ENGINEER(
        title = "Backend Specialist",
        description = "Constructs endpoints, server logic, database interactions, and middleware."
    ),
    DATABASE_ENGINEER(
        title = "Database Architect",
        description = "Creates schemas, queries, migrations, and caching layers."
    ),
    TESTER(
        title = "QA & Test Engineer",
        description = "Designs unit tests, integration suites, edge cases, and verifies execution."
    ),
    SECURITY_REVIEWER(
        title = "Security Auditor",
        description = "Inspects code for vulnerabilities, secret leaks, permission violations, and risk levels."
    ),
    CODE_REVIEWER(
        title = "Code Reviewer",
        description = "Enforces architecture consistency, SOLID design patterns, and code clarity."
    ),
    DEVOPS_ENGINEER(
        title = "DevOps & CI/CD",
        description = "Manages build scripts, Gradle configurations, GitHub workflows, and container runtimes."
    )
}

/**
 * Granular permissions per role for sandboxed team safety.
 */
data class TeamRolePermissions(
    val canWriteCode: Boolean = false,
    val canRunTerminal: Boolean = false,
    val canAccessNetwork: Boolean = false,
    val canCommitGit: Boolean = false,
    val canApproveReviews: Boolean = false,
) {
    companion object {
        fun forRole(role: TeamRole): TeamRolePermissions = when (role) {
            TeamRole.MANAGER -> TeamRolePermissions(canAccessNetwork = true, canApproveReviews = true)
            TeamRole.ARCHITECT -> TeamRolePermissions(canAccessNetwork = true, canApproveReviews = true)
            TeamRole.DEVELOPER -> TeamRolePermissions(canWriteCode = true, canRunTerminal = true)
            TeamRole.UI_ENGINEER -> TeamRolePermissions(canWriteCode = true, canRunTerminal = true)
            TeamRole.BACKEND_ENGINEER -> TeamRolePermissions(canWriteCode = true, canRunTerminal = true)
            TeamRole.DATABASE_ENGINEER -> TeamRolePermissions(canWriteCode = true, canRunTerminal = true)
            TeamRole.TESTER -> TeamRolePermissions(canWriteCode = true, canRunTerminal = true)
            TeamRole.SECURITY_REVIEWER -> TeamRolePermissions(canApproveReviews = true)
            TeamRole.CODE_REVIEWER -> TeamRolePermissions(canApproveReviews = true)
            TeamRole.DEVOPS_ENGINEER -> TeamRolePermissions(
                canWriteCode = true,
                canRunTerminal = true,
                canCommitGit = true,
                canAccessNetwork = true
            )
        }
    }
}

/**
 * Configuration and system prompt per role.
 */
data class TeamRoleConfig(
    val role: TeamRole,
    val defaultAgentKind: AgentKind = AgentKind.ANTIGRAVITY,
    val permissions: TeamRolePermissions = TeamRolePermissions.forRole(role),
    val systemPrompt: String = "",
    val maxConcurrentTasks: Int = 1
)

/**
 * Lifecycle states of an engineering task in the dependency DAG.
 */
enum class TeamTaskStatus {
    PENDING,
    READY,
    IN_PROGRESS,
    BLOCKED,
    UNDER_REVIEW,
    COMPLETED,
    FAILED
}

/**
 * Task priority levels.
 */
enum class TeamTaskPriority {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}

/**
 * Decomposed task node in the Team DAG.
 */
data class TeamTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val assignedRole: TeamRole,
    /** When set, this task runs as a user-created custom agent specializing [assignedRole]. */
    val assignedCustomAgentId: String? = null,
    val priority: TeamTaskPriority = TeamTaskPriority.MEDIUM,
    val status: TeamTaskStatus = TeamTaskStatus.PENDING,
    val dependencies: List<String> = emptyList(),
    val affectedFiles: List<String> = emptyList(),
    val output: String = "",
    val reviewFeedback: String = "",
    val tokensUsed: Long = 0L,
    val executionTimeMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    /** True when this task was executed in demo/simulation mode: output is illustrative only. */
    val isSimulated: Boolean = false
)

/**
 * Typed inter-agent messages.
 */
enum class TeamMessageType {
    PROPOSAL,
    REVIEW_REQUEST,
    FEEDBACK,
    STATUS_UPDATE,
    QUERY,
    RESPONSE,
    HANDOFF,
    BROADCAST,
    ESCALATION
}

/**
 * Communication payload routed across team agents.
 */
data class TeamMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromRole: TeamRole,
    val toRole: TeamRole? = null,
    val taskId: String? = null,
    val type: TeamMessageType = TeamMessageType.STATUS_UPDATE,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val read: Boolean = false
)

/**
 * Legacy collaboration event retained for backward compatibility.
 */
data class TeamCollaborationEvent(
    val id: String = UUID.randomUUID().toString(),
    val fromRole: TeamRole,
    val toRole: TeamRole?,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Supported knowledge artifact categories in the Centralized Shared Blackboard.
 */
enum class ArtifactType(val label: String) {
    ADR("Architecture Decision Record"),
    API_CONTRACT("API Specification & Contract"),
    DB_SCHEMA("Database Schema & Migrations"),
    TEST_REPORT("Test Suite & QA Report"),
    SECURITY_AUDIT("Security Audit & Risk Assessment"),
    CODE_REVIEW_SIGNOFF("Code Review Feedback & Sign-off"),
    SOURCE_PATCH("Source Code Diff / Implementation"),
    DEPLOYMENT_CONFIG("DevOps CI/CD & Deploy Config"),
    DOCUMENTATION("Technical Documentation")
}

/**
 * Published knowledge artifact on the Team Blackboard.
 */
data class TeamArtifact(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val type: ArtifactType,
    val authorRole: TeamRole,
    val content: String,
    val version: Int = 1,
    val tags: Set<String> = emptySet(),
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap(),
    /** True when this artifact was manufactured by demo/simulation mode, not real execution. */
    val isSimulated: Boolean = false
)

/**
 * Peer review verdicts.
 */
enum class ReviewVerdict {
    APPROVED,
    CHANGES_REQUESTED,
    REJECTED,
    PENDING
}

/**
 * Formal review record logged by Review Gatekeeper.
 */
data class ReviewRecord(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val reviewerRole: TeamRole,
    val verdict: ReviewVerdict,
    val comments: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** True when this review was simulated. Simulated reviews NEVER count as real approval. */
    val isSimulated: Boolean = false
)

/**
 * Result of file overlap analysis across concurrently running tasks.
 */
data class ConflictDetectionResult(
    val hasConflict: Boolean,
    val conflictingTasks: List<Pair<TeamTask, TeamTask>> = emptyList(),
    val conflictingFiles: List<String> = emptyList(),
    val description: String = ""
)

/**
 * Team token and cost tracking state.
 */
data class TeamBudget(
    val tokenQuota: Long = 100_000L,
    val totalTokensUsed: Long = 0L,
    val maxExecutionTimeMs: Long = 600_000L,
    val elapsedTimeMs: Long = 0L,
    val costPer1kTokens: Double = 0.003,
    val totalCostUsd: Double = 0.0,
    val isQuotaExceeded: Boolean = false
)

/**
 * Per-role resource and cost breakdown.
 */
data class RoleBudgetUsage(
    val role: TeamRole,
    val tokensUsed: Long = 0L,
    val tasksCompleted: Int = 0,
    val costUsd: Double = 0.0
)

/**
 * Complete state of the Multi-Agent Engineering Team session.
 */
data class TeamSessionState(
    val activeGoal: String = "",
    val isRunning: Boolean = false,
    val activeRole: TeamRole? = null,
    /** Number of agents with a RUNNING live task right now (parallel execution). */
    val activeTaskCount: Int = 0,
    val tasks: List<TeamTask> = emptyList(),
    val events: List<TeamCollaborationEvent> = emptyList(),
    val messages: List<TeamMessage> = emptyList(),
    val artifacts: List<TeamArtifact> = emptyList(),
    val budget: TeamBudget = TeamBudget(),
    val reviews: List<ReviewRecord> = emptyList(),
    val conflictWarnings: List<String> = emptyList(),
    val executionLog: List<String> = emptyList(),
    /**
     * Last execution failure surfaced to the UI (e.g. a required agent
     * runtime is not installed). Null when the last run had no error.
     * The UI shows this as a banner instead of silently going dead.
     */
    val lastError: String? = null
)
