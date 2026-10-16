package com.vayro.app.agent.team.runtime

import com.vayro.app.agent.team.model.ArtifactType
import com.vayro.app.agent.team.model.ReviewVerdict
import com.vayro.app.agent.team.model.TeamArtifact
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.model.TeamRoleConfig
import com.vayro.app.agent.team.model.TeamTask
import com.vayro.app.core.result.OperationResult
import com.vayro.app.model.AgentKind
import java.io.File

/**
 * Context handed to a real per-role agent executor (Milestone 10).
 *
 * Carries everything a role agent needs to do genuine work on its task:
 * the project under work, the outputs of its completed dependencies, and a
 * digest of the shared blackboard. Pure data — no Android dependencies.
 */
data class TeamExecutionContext(
    /** Project directory the agent works in; null when the run has no project bound. */
    val projectDir: File?,
    /** The high-level goal this team run serves. */
    val goal: String,
    /** The role executing this task. */
    val role: TeamRole,
    /** Role configuration (permissions, agent kind, system prompt). */
    val roleConfig: TeamRoleConfig,
    /** Coding-agent engine this role is wired to. */
    val agentKind: AgentKind,
    /** Completed dependency outputs, keyed by task id, in dependency order. */
    val dependencyOutputs: Map<String, String> = emptyMap(),
    /** Digest of blackboard artifacts relevant to this task. */
    val blackboardSummary: String = "",
    /** Hard execution budget for this single task. */
    val timeoutMs: Long = 20L * 60L * 1000L,
)

/**
 * Genuine outcome of one real agent execution.
 *
 * [result] follows the v3 integrity contract: SUCCESS only when the agent
 * genuinely ran and its outcome was verified; a SIMULATED agent result is
 * refused by the production executor and surfaces as FAILED. [artifacts] are
 * genuine ([TeamArtifact.isSimulated] = false) — never manufactured.
 */
data class TeamExecutionResult(
    val result: OperationResult,
    /** Human-readable summary of what the agent did, derived from the real run. */
    val outputText: String,
    /** Genuine artifacts produced by the run. */
    val artifacts: List<TeamArtifact> = emptyList(),
    /** Review verdict, set only for reviewer roles when the agent emitted an explicit verdict marker. */
    val reviewVerdict: ReviewVerdict? = null,
    /** Measured tokens when the runner reports them, null when unknown (never invented). */
    val tokensUsed: Long? = null,
    /** Measured wall-clock duration of the agent run. */
    val durationMs: Long = 0L,
)

/**
 * Real per-role agent executor (Milestone 10).
 *
 * Each team role is wired to a genuine agent execution. Implementations must
 * never fabricate output — when the underlying agent did not really run, the
 * result must be FAILED or NOT_CONFIGURED, never SUCCESS.
 */
fun interface TeamAgentExecutor {
    suspend fun execute(task: TeamTask, context: TeamExecutionContext): TeamExecutionResult
}

/** Artifact types produced per role by the real runtime (mirrors the demo-mode shapes). */
fun defaultArtifactTypeForRole(role: TeamRole): ArtifactType = when (role) {
    TeamRole.ARCHITECT -> ArtifactType.API_CONTRACT
    TeamRole.DATABASE_ENGINEER -> ArtifactType.DB_SCHEMA
    TeamRole.TESTER -> ArtifactType.TEST_REPORT
    TeamRole.SECURITY_REVIEWER -> ArtifactType.SECURITY_AUDIT
    TeamRole.CODE_REVIEWER -> ArtifactType.CODE_REVIEW_SIGNOFF
    TeamRole.DEVOPS_ENGINEER -> ArtifactType.DEPLOYMENT_CONFIG
    TeamRole.DEVELOPER, TeamRole.UI_ENGINEER, TeamRole.BACKEND_ENGINEER -> ArtifactType.SOURCE_PATCH
    TeamRole.MANAGER -> ArtifactType.DOCUMENTATION
}
