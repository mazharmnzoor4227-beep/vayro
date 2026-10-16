package com.vayro.app.agent.team.runtime

import com.vayro.app.agent.team.model.ReviewVerdict
import com.vayro.app.agent.team.model.TeamRole

/**
 * Pure prompt renderer for the real multi-agent runtime (Milestone 10).
 *
 * Builds the prompt each role agent receives: role identity, the task,
 * dependency outputs, and the blackboard digest. Reviewer roles get an
 * explicit instruction to close their output with a machine-readable verdict
 * marker (`VERDICT: APPROVED|CHANGES_REQUESTED|REJECTED`), which the executor
 * parses honestly — no marker means no review is recorded.
 *
 * Pure JVM: no Android, no I/O, fully unit-testable.
 */
object RolePromptRenderer {

    /** Marker reviewers must emit; parsed by [PromptBasedTeamAgentExecutor]. */
    const val VERDICT_MARKER = "VERDICT:"

    private val reviewerRoles = setOf(
        TeamRole.CODE_REVIEWER,
        TeamRole.SECURITY_REVIEWER,
        TeamRole.TESTER,
    )

    fun render(context: TeamExecutionContext, taskTitle: String, taskDescription: String): String {
        val sb = StringBuilder()
        val systemPrompt = context.roleConfig.systemPrompt.ifBlank {
            "You are the ${context.role.title} on an autonomous engineering team. ${context.role.description}"
        }
        sb.appendLine(systemPrompt)
        sb.appendLine()
        sb.appendLine("Project goal: ${context.goal}")
        sb.appendLine("Your task: $taskTitle")
        sb.appendLine("Task details: $taskDescription")
        if (context.dependencyOutputs.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Outputs from prerequisite tasks (use these as ground truth for what already exists):")
            context.dependencyOutputs.entries.forEachIndexed { index, (taskId, output) ->
                sb.appendLine("--- prerequisite ${index + 1} (task $taskId) ---")
                sb.appendLine(output.ifBlank { "(no output recorded)" })
            }
        }
        if (context.blackboardSummary.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("Shared team knowledge:")
            sb.appendLine(context.blackboardSummary)
        }
        context.projectDir?.let {
            sb.appendLine()
            sb.appendLine("Working directory: ${it.absolutePath}")
        }
        if (context.role in reviewerRoles) {
            sb.appendLine()
            sb.appendLine(
                "End your response with exactly one line in this format: " +
                    "$VERDICT_MARKER APPROVED, $VERDICT_MARKER CHANGES_REQUESTED, or $VERDICT_MARKER REJECTED, " +
                    "reflecting your genuine assessment. Do not emit a verdict you did not earn through real analysis."
            )
        }
        return sb.toString().trimEnd()
    }

    /**
     * Parses an explicit verdict marker from reviewer output. Returns null when
     * the agent did not emit a valid marker — the caller must then record no
     * review rather than inventing one.
     */
    fun parseVerdict(output: String): ReviewVerdict? {
        val line = output.lines()
            .map { it.trim() }
            .lastOrNull { it.startsWith(VERDICT_MARKER, ignoreCase = true) }
            ?: return null
        // The marker match above is case-insensitive, so locate it the same way —
        // a case-sensitive substringAfter would miss a lowercase "verdict:".
        val markerIndex = line.indexOf(VERDICT_MARKER, ignoreCase = true)
        if (markerIndex < 0) return null
        val raw = line.substring(markerIndex + VERDICT_MARKER.length).trim().uppercase()
        return when {
            raw.startsWith("APPROVED") -> ReviewVerdict.APPROVED
            raw.startsWith("CHANGES_REQUESTED") -> ReviewVerdict.CHANGES_REQUESTED
            raw.startsWith("REJECTED") -> ReviewVerdict.REJECTED
            else -> null
        }
    }
}
