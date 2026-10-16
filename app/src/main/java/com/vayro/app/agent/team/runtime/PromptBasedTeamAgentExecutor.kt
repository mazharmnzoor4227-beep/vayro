package com.vayro.app.agent.team.runtime

import com.vayro.app.agent.team.model.TeamArtifact
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.core.result.OperationResult
import com.vayro.app.core.result.OperationStatus

/**
 * Production [TeamAgentExecutor]: renders a role-specific prompt and sends it
 * through the real agent pipeline (Milestone 10).
 *
 * @param agentRunner the real pipeline seam — suspends until the agent request
 * genuinely ends and returns an [OperationResult] that is SUCCESS only on a
 * verified completion (same shape as MainViewModel's `runAgentPromptAwaitingResult`).
 * The app wires the genuine pipeline; tests inject scripted fakes.
 *
 * Integrity rules (Milestone 0 contract, enforced here):
 * - A SIMULATED agent result is refused outright and surfaces as FAILED.
 * - Reviewer roles only earn a [TeamExecutionResult.reviewVerdict] when their
 *   output carries an explicit `VERDICT:` marker; otherwise no review is recorded.
 * - Artifacts are genuine ([TeamArtifact.isSimulated] = false) — produced from
 *   the real run, never manufactured.
 * - Token usage is reported only when the runner provides it; never invented.
 */
class PromptBasedTeamAgentExecutor(
    private val agentRunner: suspend (prompt: String) -> OperationResult,
) : TeamAgentExecutor {

    override suspend fun execute(task: com.vayro.app.agent.team.model.TeamTask, context: TeamExecutionContext): TeamExecutionResult {
        val startedAt = System.currentTimeMillis()
        val prompt = RolePromptRenderer.render(context, task.title, task.description)

        val agentResult = try {
            agentRunner(prompt)
        } catch (e: Exception) {
            return TeamExecutionResult(
                result = OperationResult.failure(
                    error = "Agent runner threw: ${e.message}",
                    startedAt = startedAt,
                ),
                outputText = "Agent execution failed before producing output.",
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }

        val durationMs = System.currentTimeMillis() - startedAt

        // M0 integrity: a SIMULATED agent result is refused outright.
        if (agentResult.status == OperationStatus.SIMULATED) {
            return TeamExecutionResult(
                result = OperationResult.failure(
                    error = "Agent returned a SIMULATED result; real-mode execution refuses simulated output.",
                    logs = agentResult.logs,
                    startedAt = startedAt,
                ),
                outputText = "Refused simulated agent output.",
                durationMs = durationMs,
            )
        }

        if (!agentResult.isSuccess) {
            return TeamExecutionResult(
                result = agentResult,
                outputText = "Agent execution did not succeed: ${agentResult.error ?: agentResult.status}.",
                durationMs = durationMs,
            )
        }

        val outputText = agentResult.logs.joinToString("\n").ifBlank {
            agentResult.verificationEvidence ?: "Agent completed (no captured output)."
        }
        val verdict = if (context.role == TeamRole.CODE_REVIEWER ||
            context.role == TeamRole.SECURITY_REVIEWER ||
            context.role == TeamRole.TESTER
        ) {
            RolePromptRenderer.parseVerdict(outputText)
        } else {
            null
        }

        val artifact = TeamArtifact(
            title = "${context.role.title}: ${task.title}",
            type = defaultArtifactTypeForRole(context.role),
            authorRole = context.role,
            content = outputText.take(8000),
            tags = setOf("team-run", context.role.name.lowercase()),
            metadata = mapOf("taskId" to task.id),
            isSimulated = false,
        )

        return TeamExecutionResult(
            result = agentResult,
            outputText = outputText,
            artifacts = listOf(artifact),
            reviewVerdict = verdict,
            tokensUsed = null,
            durationMs = durationMs,
        )
    }
}
