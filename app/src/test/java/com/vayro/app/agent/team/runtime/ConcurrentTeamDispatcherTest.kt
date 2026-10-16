package com.vayro.app.agent.team.runtime

import com.vayro.app.agent.team.blackboard.TeamBlackboard
import com.vayro.app.agent.team.budget.TeamBudgetTracker
import com.vayro.app.agent.team.comm.TeamCommunicationBus
import com.vayro.app.agent.team.model.ArtifactType
import com.vayro.app.agent.team.model.TeamArtifact
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.model.TeamTask
import com.vayro.app.agent.team.model.TeamTaskPriority
import com.vayro.app.agent.team.model.TeamTaskStatus
import com.vayro.app.agent.team.review.TeamReviewGatekeeper
import com.vayro.app.agent.team.scheduler.TeamTaskScheduler
import com.vayro.app.core.result.OperationResult
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConcurrentTeamDispatcherTest {

    private lateinit var scheduler: TeamTaskScheduler
    private lateinit var blackboard: TeamBlackboard
    private lateinit var commBus: TeamCommunicationBus
    private lateinit var gatekeeper: TeamReviewGatekeeper
    private lateinit var budgetTracker: TeamBudgetTracker

    @Before
    fun setUp() {
        scheduler = TeamTaskScheduler()
        blackboard = TeamBlackboard()
        commBus = TeamCommunicationBus()
        gatekeeper = TeamReviewGatekeeper()
        budgetTracker = TeamBudgetTracker()
    }

    private fun devTask(title: String, files: List<String> = emptyList(), deps: List<String> = emptyList()) =
        TeamTask(
            title = title,
            description = "desc",
            assignedRole = TeamRole.DEVELOPER,
            priority = TeamTaskPriority.HIGH,
            dependencies = deps,
            affectedFiles = files,
        )

    private fun successExecutor(
        onExecute: (TeamTask) -> Unit = {},
    ) = TeamAgentExecutor { task, _ ->
        onExecute(task)
        TeamExecutionResult(
            result = OperationResult.success(verificationEvidence = "ok"),
            outputText = "output of ${task.title}",
            artifacts = listOf(
                TeamArtifact(
                    title = "Artifact: ${task.title}",
                    type = ArtifactType.SOURCE_PATCH,
                    authorRole = task.assignedRole,
                    content = "output of ${task.title}",
                    metadata = mapOf("taskId" to task.id),
                    isSimulated = false,
                )
            ),
        )
    }

    private fun dispatcher(
        executor: TeamAgentExecutor,
        maxConcurrent: Int = 3,
    ) = ConcurrentTeamDispatcher(
        scheduler = scheduler,
        blackboard = blackboard,
        commBus = commBus,
        gatekeeper = gatekeeper,
        budgetTracker = budgetTracker,
        executor = executor,
        maxConcurrentAgents = maxConcurrent,
    )

    @Test
    fun testIndependentTasksRunConcurrently() = runBlocking {
        val active = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val executor = successExecutor {
            val now = active.incrementAndGet()
            maxObserved.updateAndGet { m -> maxOf(m, now) }
            Thread.sleep(150)
            active.decrementAndGet()
        }
        scheduler.setTasks(listOf(devTask("A"), devTask("B")))
        val report = dispatcher(executor, maxConcurrent = 2).runAll()
        assertEquals(2, report.succeeded)
        assertEquals(2, maxObserved.get())
    }

    @Test
    fun testConflictingFileTasksSerialize() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val executor = successExecutor { task ->
            events += "start-${task.title}"
            Thread.sleep(120)
            events += "end-${task.title}"
        }
        scheduler.setTasks(
            listOf(
                devTask("A", files = listOf("shared/file.kt")),
                devTask("B", files = listOf("shared/file.kt")),
            )
        )
        val report = dispatcher(executor, maxConcurrent = 2).runAll()
        assertEquals(2, report.succeeded)
        // The two tasks must not overlap: one's end precedes the other's start.
        val endA = events.indexOf("end-A")
        val startB = events.indexOf("start-B")
        val endB = events.indexOf("end-B")
        val startA = events.indexOf("start-A")
        val serialized = (endA < startB) || (endB < startA)
        assertTrue("conflicting tasks overlapped: $events", serialized)
    }

    @Test
    fun testDependencyOrderRespectedAcrossStages() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val executor = successExecutor { order += it.title }
        val first = devTask("First")
        // second depends on first: build with real id
        val firstWithId = first.copy(id = "id-first")
        val second = devTask("Second", deps = listOf("id-first"))
        scheduler.setTasks(listOf(firstWithId, second))
        val report = dispatcher(executor).runAll()
        assertEquals(2, report.succeeded)
        assertEquals(listOf("First", "Second"), order)
    }

    @Test
    fun testFailedTaskBlocksDependents() = runBlocking {
        val executor = TeamAgentExecutor { task, _ ->
            if (task.title == "Bad") {
                TeamExecutionResult(
                    result = OperationResult.failure(error = "agent failed"),
                    outputText = "failed",
                )
            } else {
                TeamExecutionResult(
                    result = OperationResult.success(),
                    outputText = "ok",
                )
            }
        }
        val bad = devTask("Bad").copy(id = "id-bad")
        val dependent = devTask("Dependent", deps = listOf("id-bad"))
        scheduler.setTasks(listOf(bad, dependent))
        val report = dispatcher(executor).runAll()
        assertEquals(1, report.failed)
        assertEquals(TeamTaskStatus.BLOCKED, scheduler.getTask(dependent.id)?.status)
        assertTrue(report.outcomes.none { it.task.title == "Dependent" && it.status == TeamTaskStatus.COMPLETED })
    }

    @Test
    fun testQuotaExceededHaltsDispatch() = runBlocking {
        budgetTracker.setTokenQuota(0L) // 0 used >= 0 quota => exceeded
        var executed = 0
        val executor = successExecutor { executed++ }
        scheduler.setTasks(listOf(devTask("A"), devTask("B")))
        val report = dispatcher(executor).runAll()
        assertEquals(0, executed)
        assertEquals(0, report.succeeded)
    }

    @Test
    fun testGenuineArtifactsPublishedToBlackboard() = runBlocking {
        scheduler.setTasks(listOf(devTask("A")))
        val report = dispatcher(successExecutor()).runAll()
        assertEquals(1, report.succeeded)
        val artifacts = blackboard.getAllArtifacts()
        assertEquals(1, artifacts.size)
        assertTrue(artifacts.none { it.isSimulated })
    }

    @Test
    fun testCyclicGraphRefusesDispatch() = runBlocking {
        val a = devTask("A", deps = listOf("id-b")).copy(id = "id-a")
        val b = devTask("B", deps = listOf("id-a")).copy(id = "id-b")
        scheduler.setTasks(listOf(a, b))
        var executed = 0
        val report = dispatcher(successExecutor { executed++ }).runAll()
        assertEquals(0, executed)
        assertTrue(report.cancelled)
    }

    @Test
    fun testCancelStopsBetweenStages() = runBlocking {
        val first = devTask("First").copy(id = "id-first")
        val second = devTask("Second", deps = listOf("id-first"))
        scheduler.setTasks(listOf(first, second))
        val d = dispatcher(successExecutor())
        d.cancel()
        val report = d.runAll()
        assertTrue(report.cancelled)
        assertEquals(0, report.succeeded)
    }

    @Test
    fun testHandoffMessagesSentToDependents() = runBlocking {
        val first = devTask("First").copy(id = "id-first")
        val second = devTask("Second", deps = listOf("id-first")).copy(assignedRole = TeamRole.UI_ENGINEER)
        scheduler.setTasks(listOf(first, second))
        dispatcher(successExecutor()).runAll()
        val handoffs = commBus.getMessages().filter {
            it.type == com.vayro.app.agent.team.model.TeamMessageType.HANDOFF && it.toRole == TeamRole.UI_ENGINEER
        }
        assertEquals(1, handoffs.size)
    }

    @Test
    fun testRendezvousProvesParallelStart() = runBlocking {
        // Both tasks must be inside the executor at the same time.
        val latch = CountDownLatch(2)
        val executor = TeamAgentExecutor { task, _ ->
            latch.countDown()
            val bothInside = latch.await(5, TimeUnit.SECONDS)
            assertTrue("tasks did not run concurrently", bothInside)
            TeamExecutionResult(result = OperationResult.success(), outputText = "ok")
        }
        scheduler.setTasks(listOf(devTask("A"), devTask("B")))
        val report = dispatcher(executor, maxConcurrent = 2).runAll()
        assertEquals(2, report.succeeded)
    }
}
