package com.vayro.app.agent.team.scheduler

import com.vayro.app.agent.team.model.TeamTask
import com.vayro.app.agent.team.model.TeamTaskStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * Dependency DAG Scheduler for Multi-Agent Task Orchestration (Phase 11).
 * Resolves task dependencies, evaluates topological execution waves, detects cycles,
 * and unlocks ready tasks as prerequisites complete.
 */
class TeamTaskScheduler {

    private val tasksById = ConcurrentHashMap<String, TeamTask>()

    @Synchronized
    fun setTasks(tasks: List<TeamTask>) {
        tasksById.clear()
        tasks.forEach { task ->
            // If task is PENDING and has no dependencies, mark it READY
            val initialStatus = if (task.status == TeamTaskStatus.PENDING && task.dependencies.isEmpty()) {
                TeamTaskStatus.READY
            } else {
                task.status
            }
            tasksById[task.id] = task.copy(status = initialStatus)
        }
        evaluateReadyQueue()
    }

    @Synchronized
    fun addTask(task: TeamTask) {
        val initialStatus = if (task.status == TeamTaskStatus.PENDING && task.dependencies.isEmpty()) {
            TeamTaskStatus.READY
        } else {
            task.status
        }
        tasksById[task.id] = task.copy(status = initialStatus)
        evaluateReadyQueue()
    }

    fun getTasks(): List<TeamTask> = tasksById.values.toList()

    fun getTask(id: String): TeamTask? = tasksById[id]

    /**
     * Returns tasks that are currently available to be worked on.
     */
    fun getReadyTasks(): List<TeamTask> {
        return tasksById.values
            .filter { it.status == TeamTaskStatus.READY }
            .sortedByDescending { it.priority.ordinal }
    }

    /**
     * Updates status, outputs, metrics, and cascades state transitions across the DAG.
     */
    @Synchronized
    fun updateTaskStatus(
        taskId: String,
        status: TeamTaskStatus,
        output: String = "",
        feedback: String = "",
        tokensUsed: Long = 0L,
        executionTimeMs: Long = 0L,
        isSimulated: Boolean = false
    ): TeamTask? {
        val current = tasksById[taskId] ?: return null
        val updated = current.copy(
            status = status,
            output = output.ifBlank { current.output },
            reviewFeedback = feedback.ifBlank { current.reviewFeedback },
            tokensUsed = if (tokensUsed > 0L) current.tokensUsed + tokensUsed else current.tokensUsed,
            executionTimeMs = if (executionTimeMs > 0L) current.executionTimeMs + executionTimeMs else current.executionTimeMs,
            isSimulated = isSimulated || current.isSimulated
        )
        tasksById[taskId] = updated

        if (status == TeamTaskStatus.COMPLETED) {
            evaluateReadyQueue()
        } else if (status == TeamTaskStatus.FAILED) {
            cascadeFailure(taskId)
        }

        return updated
    }

    /**
     * Checks if the task dependency graph contains a cycle.
     */
    fun hasCycle(): Boolean {
        val allTasks = tasksById.values
        val inDegree = mutableMapOf<String, Int>()
        val adjList = mutableMapOf<String, MutableList<String>>()

        allTasks.forEach { task ->
            inDegree[task.id] = 0
            adjList[task.id] = mutableListOf()
        }

        allTasks.forEach { task ->
            task.dependencies.forEach { depId ->
                if (adjList.containsKey(depId)) {
                    adjList[depId]?.add(task.id)
                    inDegree[task.id] = (inDegree[task.id] ?: 0) + 1
                }
            }
        }

        val queue = ArrayDeque<String>()
        inDegree.filter { it.value == 0 }.keys.forEach { queue.add(it) }

        var visitedCount = 0
        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            visitedCount++
            adjList[curr]?.forEach { neighbor ->
                val newDeg = (inDegree[neighbor] ?: 1) - 1
                inDegree[neighbor] = newDeg
                if (newDeg == 0) {
                    queue.add(neighbor)
                }
            }
        }

        return visitedCount != allTasks.size
    }

    /**
     * Computes parallel execution stages (waves) based on dependency levels.
     */
    fun getExecutionStages(): List<List<TeamTask>> {
        val allTasks = tasksById.values.toList()
        if (allTasks.isEmpty() || hasCycle()) return emptyList()

        val stageMap = mutableMapOf<String, Int>()

        fun computeStage(taskId: String, visited: Set<String> = emptySet()): Int {
            stageMap[taskId]?.let { return it }
            val task = tasksById[taskId] ?: return 0
            if (task.dependencies.isEmpty()) {
                stageMap[taskId] = 0
                return 0
            }
            if (visited.contains(taskId)) return 0 // Guard against cycles

            val maxDepStage = task.dependencies.maxOfOrNull { depId ->
                computeStage(depId, visited + taskId)
            } ?: -1
            val stage = maxDepStage + 1
            stageMap[taskId] = stage
            return stage
        }

        allTasks.forEach { computeStage(it.id) }

        val maxStage = stageMap.values.maxOrNull() ?: 0
        val stages = ArrayList<MutableList<TeamTask>>(maxStage + 1)
        for (i in 0..maxStage) {
            stages.add(mutableListOf())
        }

        allTasks.forEach { task ->
            val stageIdx = stageMap[task.id] ?: 0
            stages[stageIdx].add(task)
        }

        return stages
    }

    fun isAllCompleted(): Boolean {
        val tasks = tasksById.values
        return tasks.isNotEmpty() && tasks.all { it.status == TeamTaskStatus.COMPLETED }
    }

    fun isStalled(): Boolean {
        val tasks = tasksById.values
        if (tasks.isEmpty() || isAllCompleted()) return false
        val active = tasks.any { it.status == TeamTaskStatus.IN_PROGRESS || it.status == TeamTaskStatus.READY || it.status == TeamTaskStatus.UNDER_REVIEW }
        return !active
    }

    private fun evaluateReadyQueue() {
        tasksById.values.forEach { task ->
            if (task.status == TeamTaskStatus.PENDING || task.status == TeamTaskStatus.BLOCKED) {
                val allDepsComplete = task.dependencies.all { depId ->
                    tasksById[depId]?.status == TeamTaskStatus.COMPLETED
                }
                if (allDepsComplete) {
                    tasksById[task.id] = task.copy(status = TeamTaskStatus.READY)
                }
            }
        }
    }

    private fun cascadeFailure(failedTaskId: String) {
        tasksById.values.forEach { task ->
            if (task.dependencies.contains(failedTaskId) && task.status != TeamTaskStatus.COMPLETED) {
                tasksById[task.id] = task.copy(
                    status = TeamTaskStatus.BLOCKED,
                    reviewFeedback = "Blocked due to prerequisite task failure: $failedTaskId"
                )
            }
        }
    }
}
