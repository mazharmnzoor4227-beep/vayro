package com.vayro.app.agent.team

import com.vayro.app.agent.team.model.ArtifactType
import com.vayro.app.agent.team.model.ReviewVerdict
import com.vayro.app.agent.team.model.TeamArtifact
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.runtime.SecurityScanResult
import com.vayro.app.agent.team.runtime.TeamAgentExecutor
import com.vayro.app.agent.team.runtime.TeamExecutionContext
import com.vayro.app.agent.team.runtime.TeamExecutionResult
import com.vayro.app.agent.team.runtime.TeamProjectBinding
import com.vayro.app.agent.team.runtime.TeamRoleValidator
import com.vayro.app.agent.team.runtime.TestSuiteResult
import com.vayro.app.agent.team.runtime.resolveTeamSessionIdentity
import com.vayro.app.core.result.OperationResult
import com.vayro.app.model.ProjectKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class TeamProjectBindingTest {

    private fun approvingValidator() = TeamRoleValidator(
        testSuiteRunner = { TestSuiteResult(ran = true, passed = 10, failed = 0, skipped = 0, note = "exit=0") },
        securityScanner = { SecurityScanResult(filesScanned = 50) },
    )

    private fun capturingExecutor(captured: MutableList<TeamExecutionContext>): TeamAgentExecutor =
        TeamAgentExecutor { task, context ->
            captured += context
            val verdict = if (context.role == TeamRole.CODE_REVIEWER) ReviewVerdict.APPROVED else null
            TeamExecutionResult(
                result = OperationResult.success(verificationEvidence = "agent completed"),
                outputText = "Real output for ${task.title}." + (verdict?.let { "\nVERDICT: $it" } ?: ""),
                artifacts = listOf(
                    TeamArtifact(
                        title = "${context.role.title}: ${task.title}",
                        type = ArtifactType.SOURCE_PATCH,
                        authorRole = context.role,
                        content = "Real output for ${task.title}.",
                        metadata = mapOf("taskId" to task.id),
                        isSimulated = false,
                    )
                ),
                reviewVerdict = verdict,
            )
        }

    @Test
    fun boundIdentityUsesTheProjectsOwnIdSlugAndKind() {
        val binding = TeamProjectBinding(
            projectId = "proj-123",
            projectSlug = "my-shop-app",
            projectName = "My Shop App",
            projectKind = ProjectKind.QUICK_PROJECT,
        )
        val identity = resolveTeamSessionIdentity("any goal", binding)
        assertEquals("proj-123", identity.projectId)
        assertEquals("my-shop-app", identity.projectSlug)
        assertEquals(ProjectKind.QUICK_PROJECT, identity.projectKind)
    }

    @Test
    fun unboundIdentityIsASyntheticTeamWorkspace() {
        val first = resolveTeamSessionIdentity("Build a notes app", null)
        val second = resolveTeamSessionIdentity("Build a notes app", null)
        val other = resolveTeamSessionIdentity("Build a game", null)
        assertEquals(first, second)
        assertNotEquals(first.projectId, other.projectId)
        assertTrue(first.projectId.startsWith("team-"))
        assertTrue(first.projectSlug.startsWith("team-"))
        assertEquals(ProjectKind.PROJECT, first.projectKind)
    }

    @Test
    fun boundProjectReachesEveryTaskContext() = runBlocking {
        val captured = Collections.synchronizedList(mutableListOf<TeamExecutionContext>())
        val orchestrator = AgentTeamOrchestrator(
            teamExecutor = capturingExecutor(captured),
            teamValidator = approvingValidator(),
        )
        orchestrator.bindProject(
            TeamProjectBinding(projectId = "proj-9", projectSlug = "billing-api", projectName = "Billing API")
        )
        orchestrator.planProject("Add invoice export")
        val report = orchestrator.autoRunAllReal()
        assertTrue(report.isSuccess)
        assertTrue(captured.isNotEmpty())
        assertTrue(captured.all { it.projectBinding?.projectId == "proj-9" })
        assertTrue(captured.all { it.projectBinding?.projectSlug == "billing-api" })
    }

    @Test
    fun unboundRunCarriesNoBinding() = runBlocking {
        val captured = Collections.synchronizedList(mutableListOf<TeamExecutionContext>())
        val orchestrator = AgentTeamOrchestrator(
            teamExecutor = capturingExecutor(captured),
            teamValidator = approvingValidator(),
        )
        orchestrator.planProject("Build a notes app")
        val report = orchestrator.autoRunAllReal()
        assertTrue(report.isSuccess)
        assertTrue(captured.isNotEmpty())
        assertTrue(captured.all { it.projectBinding == null })
    }

    @Test
    fun rebindingBetweenRunsTakesEffect() = runBlocking {
        val captured = Collections.synchronizedList(mutableListOf<TeamExecutionContext>())
        val orchestrator = AgentTeamOrchestrator(
            teamExecutor = capturingExecutor(captured),
            teamValidator = approvingValidator(),
        )
        orchestrator.bindProject(TeamProjectBinding("a-id", "a-app", "A App"))
        orchestrator.planProject("First goal")
        orchestrator.autoRunAllReal()
        val firstRun = captured.toList()
        captured.clear()

        orchestrator.bindProject(null)
        orchestrator.planProject("Second goal")
        orchestrator.autoRunAllReal()

        assertTrue(firstRun.isNotEmpty())
        assertTrue(firstRun.all { it.projectBinding?.projectId == "a-id" })
        assertTrue(captured.isNotEmpty())
        assertTrue(captured.all { it.projectBinding == null })
        assertTrue(captured.all { it.goal == "Second goal" })
    }
}