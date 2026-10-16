package com.vayro.app.agent.team.runtime

import com.vayro.app.agent.team.model.ReviewRecord
import com.vayro.app.agent.team.model.TeamTask
import com.vayro.app.agent.team.model.TeamTaskStatus

/** Per-task outcome aggregated by the real runtime. */
data class TeamTaskOutcome(
    val task: TeamTask,
    val status: TeamTaskStatus,
    val outputSummary: String,
    val durationMs: Long,
    val tokensUsed: Long?,
    val reviewVerdict: com.vayro.app.agent.team.model.ReviewVerdict? = null,
)

/** Aggregated result of one real multi-agent run — genuine, never simulated. */
data class TeamRunReport(
    val goal: String,
    val outcomes: List<TeamTaskOutcome>,
    val reviews: List<ReviewRecord>,
    val totalDurationMs: Long,
    val cancelled: Boolean = false,
) {
    val succeeded: Int get() = outcomes.count { it.status == TeamTaskStatus.COMPLETED }
    val failed: Int get() = outcomes.count { it.status == TeamTaskStatus.FAILED }
    val blocked: Int get() = outcomes.count { it.status == TeamTaskStatus.BLOCKED }
    val skipped: Int get() = outcomes.count { it.status == TeamTaskStatus.PENDING }
    val isSuccess: Boolean get() = !cancelled && failed == 0 && blocked == 0 && outcomes.isNotEmpty()
}
