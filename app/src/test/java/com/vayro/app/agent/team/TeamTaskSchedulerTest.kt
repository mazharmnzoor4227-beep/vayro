package com.vayro.app.agent.team

import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.model.TeamTask
import com.vayro.app.agent.team.model.TeamTaskStatus
import com.vayro.app.agent.team.scheduler.TeamTaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TeamTaskSchedulerTest {

    private lateinit var scheduler: TeamTaskScheduler

    @Before
    fun setUp() {
        scheduler = TeamTaskScheduler()
    }

    @Test
    fun testRootTasksBecomeReadyAutomatically() {
        val task1 = TeamTask(id = "task-1", title = "Task 1", description = "", assignedRole = TeamRole.MANAGER)
        val task2 = TeamTask(id = "task-2", title = "Task 2", description = "", assignedRole = TeamRole.ARCHITECT, dependencies = listOf("task-1"))

        scheduler.setTasks(listOf(task1, task2))

        val readyTasks = scheduler.getReadyTasks()
        assertEquals(1, readyTasks.size)
        assertEquals("task-1", readyTasks.first().id)
        assertEquals(TeamTaskStatus.READY, readyTasks.first().status)

        val retrievedTask2 = scheduler.getTask("task-2")
        assertEquals(TeamTaskStatus.PENDING, retrievedTask2?.status)
    }

    @Test
    fun testCompletingDependencyUnlocksDownstreamTask() {
        val task1 = TeamTask(id = "task-1", title = "Task 1", description = "", assignedRole = TeamRole.MANAGER)
        val task2 = TeamTask(id = "task-2", title = "Task 2", description = "", assignedRole = TeamRole.ARCHITECT, dependencies = listOf("task-1"))

        scheduler.setTasks(listOf(task1, task2))
        assertEquals(1, scheduler.getReadyTasks().size)

        // Complete task 1
        scheduler.updateTaskStatus("task-1", TeamTaskStatus.COMPLETED)

        val readyTasks = scheduler.getReadyTasks()
        assertEquals(1, readyTasks.size)
        assertEquals("task-2", readyTasks.first().id)
        assertEquals(TeamTaskStatus.READY, readyTasks.first().status)
    }

    @Test
    fun testFailingDependencyCascadesToBlocked() {
        val task1 = TeamTask(id = "task-1", title = "Task 1", description = "", assignedRole = TeamRole.DEVELOPER)
        val task2 = TeamTask(id = "task-2", title = "Task 2", description = "", assignedRole = TeamRole.TESTER, dependencies = listOf("task-1"))

        scheduler.setTasks(listOf(task1, task2))
        scheduler.updateTaskStatus("task-1", TeamTaskStatus.FAILED)

        val retrievedTask2 = scheduler.getTask("task-2")
        assertEquals(TeamTaskStatus.BLOCKED, retrievedTask2?.status)
        assertTrue(retrievedTask2?.reviewFeedback?.contains("Blocked") == true)
    }

    @Test
    fun testCycleDetection() {
        // Valid DAG
        val taskA = TeamTask(id = "A", title = "A", description = "", assignedRole = TeamRole.MANAGER)
        val taskB = TeamTask(id = "B", title = "B", description = "", assignedRole = TeamRole.ARCHITECT, dependencies = listOf("A"))
        val taskC = TeamTask(id = "C", title = "C", description = "", assignedRole = TeamRole.DEVELOPER, dependencies = listOf("B"))

        scheduler.setTasks(listOf(taskA, taskB, taskC))
        assertFalse(scheduler.hasCycle())

        // Cyclic DAG: A -> B -> C -> A
        val cyclicA = TeamTask(id = "A", title = "A", description = "", assignedRole = TeamRole.MANAGER, dependencies = listOf("C"))
        val cyclicB = TeamTask(id = "B", title = "B", description = "", assignedRole = TeamRole.ARCHITECT, dependencies = listOf("A"))
        val cyclicC = TeamTask(id = "C", title = "C", description = "", assignedRole = TeamRole.DEVELOPER, dependencies = listOf("B"))

        val cyclicScheduler = TeamTaskScheduler()
        cyclicScheduler.setTasks(listOf(cyclicA, cyclicB, cyclicC))
        assertTrue(cyclicScheduler.hasCycle())
    }

    @Test
    fun testExecutionStagesComputation() {
        val taskA = TeamTask(id = "A", title = "A", description = "", assignedRole = TeamRole.MANAGER)
        val taskB = TeamTask(id = "B", title = "B", description = "", assignedRole = TeamRole.ARCHITECT, dependencies = listOf("A"))
        val taskC = TeamTask(id = "C", title = "C", description = "", assignedRole = TeamRole.DATABASE_ENGINEER, dependencies = listOf("A"))
        val taskD = TeamTask(id = "D", title = "D", description = "", assignedRole = TeamRole.DEVELOPER, dependencies = listOf("B", "C"))

        scheduler.setTasks(listOf(taskA, taskB, taskC, taskD))
        val stages = scheduler.getExecutionStages()

        assertEquals(3, stages.size)
        // Stage 0: Task A
        assertEquals(1, stages[0].size)
        assertEquals("A", stages[0].first().id)

        // Stage 1: Task B and Task C can run in parallel
        assertEquals(2, stages[1].size)
        val stage1Ids = stages[1].map { it.id }.toSet()
        assertTrue(stage1Ids.contains("B"))
        assertTrue(stage1Ids.contains("C"))

        // Stage 2: Task D
        assertEquals(1, stages[2].size)
        assertEquals("D", stages[2].first().id)
    }

    @Test
    fun testAllCompletedAndStalledState() {
        val task1 = TeamTask(id = "task-1", title = "Task 1", description = "", assignedRole = TeamRole.MANAGER)
        scheduler.setTasks(listOf(task1))

        assertFalse(scheduler.isAllCompleted())
        assertFalse(scheduler.isStalled())

        scheduler.updateTaskStatus("task-1", TeamTaskStatus.COMPLETED)
        assertTrue(scheduler.isAllCompleted())
        assertFalse(scheduler.isStalled())
    }
}
