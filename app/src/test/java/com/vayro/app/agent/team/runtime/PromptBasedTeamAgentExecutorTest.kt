package com.vayro.app.agent.team.runtime

import com.vayro.app.agent.team.model.ReviewVerdict
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.model.TeamRoleConfig
import com.vayro.app.agent.team.model.TeamTask
import com.vayro.app.agent.team.model.TeamTaskPriority
import com.vayro.app.core.result.OperationResult
import com.vayro.app.core.result.OperationStatus
import com.vayro.app.model.AgentKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBasedTeamAgentExecutorTest {

    private fun task(role: TeamRole) = TeamTask(
        title = "Do the thing",
        description = "Details",
        assignedRole = role,
        priority = TeamTaskPriority.HIGH,
    )

    private fun context(role: TeamRole) = TeamExecutionContext(
        projectDir = null,
        goal = "Goal",
        role = role,
        roleConfig = TeamRoleConfig(role = role),
        agentKind = AgentKind.CLAUDE_CODE,
    )

    @Test
    fun testSuccessfulRunProducesGenuineArtifact() = runBlocking {
        val executor = PromptBasedTeamAgentExecutor { prompt ->
            assertTrue(prompt.contains("Do the thing"))
            OperationResult.success(
                logs = listOf("Implemented the feature."),
                verificationEvidence = "Agent task finished (completed)",
            )
        }
        val result = executor.execute(task(TeamRole.DEVELOPER), context(TeamRole.DEVELOPER))
        assertTrue(result.result.isSuccess)
        assertEquals(1, result.artifacts.size)
        assertFalse(result.artifacts[0].isSimulated)
        assertTrue(result.outputText.contains("Implemented the feature."))
        assertTrue(result.durationMs >= 0)
    }

    @Test
    fun testSimulatedAgentResultIsRefused() = runBlocking {
        val executor = PromptBasedTeamAgentExecutor { _ ->
            OperationResult.simulated("demo output")
        }
        val result = executor.execute(task(TeamRole.DEVELOPER), context(TeamRole.DEVELOPER))
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.result.error!!.contains("SIMULATED"))
        assertTrue(result.artifacts.isEmpty())
    }

    @Test
    fun testFailedAgentResultPropagates() = runBlocking {
        val executor = PromptBasedTeamAgentExecutor { _ ->
            OperationResult.failure(error = "agent crashed")
        }
        val result = executor.execute(task(TeamRole.DEVELOPER), context(TeamRole.DEVELOPER))
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.artifacts.isEmpty())
    }

    @Test
    fun testRunnerExceptionBecomesFailure() = runBlocking {
        val executor = PromptBasedTeamAgentExecutor { _ ->
            throw RuntimeException("boom")
        }
        val result = executor.execute(task(TeamRole.DEVELOPER), context(TeamRole.DEVELOPER))
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.result.error!!.contains("boom"))
    }

    @Test
    fun testReviewerVerdictParsedFromMarker() = runBlocking {
        val executor = PromptBasedTeamAgentExecutor { _ ->
            OperationResult.success(logs = listOf("Looks good.\nVERDICT: APPROVED"))
        }
        val result = executor.execute(task(TeamRole.CODE_REVIEWER), context(TeamRole.CODE_REVIEWER))
        assertTrue(result.result.isSuccess)
        assertEquals(ReviewVerdict.APPROVED, result.reviewVerdict)
    }

    @Test
    fun testReviewerWithoutMarkerGetsNoVerdict() = runBlocking {
        val executor = PromptBasedTeamAgentExecutor { _ ->
            OperationResult.success(logs = listOf("Looks good, no marker."))
        }
        val result = executor.execute(task(TeamRole.CODE_REVIEWER), context(TeamRole.CODE_REVIEWER))
        assertTrue(result.result.isSuccess)
        assertNull(result.reviewVerdict)
    }

    @Test
    fun testNonReviewerNeverGetsVerdict() = runBlocking {
        val executor = PromptBasedTeamAgentExecutor { _ ->
            OperationResult.success(logs = listOf("Done.\nVERDICT: APPROVED"))
        }
        val result = executor.execute(task(TeamRole.DEVELOPER), context(TeamRole.DEVELOPER))
        assertNull(result.reviewVerdict)
    }

    @Test
    fun testTokensUnknownNotInvented() = runBlocking {
        val executor = PromptBasedTeamAgentExecutor { _ ->
            OperationResult.success(logs = listOf("ok"))
        }
        val result = executor.execute(task(TeamRole.DEVELOPER), context(TeamRole.DEVELOPER))
        assertNull(result.tokensUsed)
    }
}
