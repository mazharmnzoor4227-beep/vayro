package com.vayro.app.agent.team.review

import com.vayro.app.agent.team.model.ConflictDetectionResult
import com.vayro.app.agent.team.model.ReviewRecord
import com.vayro.app.agent.team.model.ReviewVerdict
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.model.TeamTask
import com.vayro.app.agent.team.model.TeamTaskStatus
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Multi-Agent Review Gatekeeper & Conflict Resolver (Phase 11).
 * Detects concurrent file modifications to prevent race conditions and enforces
 * required peer reviews (Code Reviewer, Security Auditor) before task completion.
 */
class TeamReviewGatekeeper {

    private val reviews = CopyOnWriteArrayList<ReviewRecord>()

    /**
     * Checks if concurrent tasks have conflicting file modifications.
     */
    fun detectFileConflicts(tasks: List<TeamTask>): ConflictDetectionResult {
        val activeTasks = tasks.filter {
            it.status == TeamTaskStatus.IN_PROGRESS || it.status == TeamTaskStatus.READY
        }

        val fileUsage = mutableMapOf<String, MutableList<TeamTask>>()
        activeTasks.forEach { task ->
            task.affectedFiles.forEach { file ->
                val normalized = file.trim().lowercase()
                if (normalized.isNotBlank()) {
                    fileUsage.getOrPut(normalized) { mutableListOf() }.add(task)
                }
            }
        }

        val conflicts = mutableListOf<Pair<TeamTask, TeamTask>>()
        val conflictingFiles = mutableListOf<String>()

        fileUsage.forEach { (file, occupyingTasks) ->
            if (occupyingTasks.size > 1) {
                conflictingFiles.add(file)
                for (i in 0 until occupyingTasks.size) {
                    for (j in i + 1 until occupyingTasks.size) {
                        conflicts.add(occupyingTasks[i] to occupyingTasks[j])
                    }
                }
            }
        }

        val hasConflict = conflicts.isNotEmpty()
        val description = if (hasConflict) {
            "Detected overlapping file access on ${conflictingFiles.size} file(s) across ${conflicts.size} task pair(s)."
        } else {
            "No file conflicts detected among active tasks."
        }

        return ConflictDetectionResult(
            hasConflict = hasConflict,
            conflictingTasks = conflicts,
            conflictingFiles = conflictingFiles,
            description = description
        )
    }

    /**
     * Submits a formal review verdict from a reviewer agent.
     */
    fun submitReview(
        taskId: String,
        reviewerRole: TeamRole,
        verdict: ReviewVerdict,
        comments: String,
        isSimulated: Boolean = false
    ): ReviewRecord {
        val record = ReviewRecord(
            taskId = taskId,
            reviewerRole = reviewerRole,
            verdict = verdict,
            comments = comments,
            timestamp = System.currentTimeMillis(),
            isSimulated = isSimulated
        )
        reviews.add(record)
        return record
    }

    /**
     * Checks if a task satisfies mandatory review criteria to be marked COMPLETED.
     */
    fun canCompleteTask(task: TeamTask, requiresSecurity: Boolean = false): Boolean {
        // Non-code tasks or manager tasks don't require gatekeeper approval
        if (task.assignedRole == TeamRole.MANAGER ||
            task.assignedRole == TeamRole.CODE_REVIEWER ||
            task.assignedRole == TeamRole.SECURITY_REVIEWER ||
            task.assignedRole == TeamRole.TESTER
        ) {
            return true
        }

        val taskReviews = reviews.filter { it.taskId == task.id }

        // Must have a REAL (non-simulated) APPROVED review from CODE_REVIEWER.
        // Simulated demo reviews never count as approval (Milestone 0 integrity gate).
        val hasCodeReviewApproval = taskReviews.any {
            it.reviewerRole == TeamRole.CODE_REVIEWER && it.verdict == ReviewVerdict.APPROVED && !it.isSimulated
        }

        if (!hasCodeReviewApproval) {
            return false
        }

        // If security review is required, must also have APPROVED review from SECURITY_REVIEWER
        val securityNeeded = requiresSecurity || task.affectedFiles.any {
            it.contains("auth", ignoreCase = true) ||
                it.contains("secret", ignoreCase = true) ||
                it.contains("security", ignoreCase = true) ||
                it.contains("token", ignoreCase = true)
        }

        if (securityNeeded) {
            val hasSecurityApproval = taskReviews.any {
                it.reviewerRole == TeamRole.SECURITY_REVIEWER && it.verdict == ReviewVerdict.APPROVED && !it.isSimulated
            }
            if (!hasSecurityApproval) {
                return false
            }
        }

        return true
    }

    fun getReviewsForTask(taskId: String): List<ReviewRecord> {
        return reviews.filter { it.taskId == taskId }
    }

    fun getAllReviews(): List<ReviewRecord> = reviews.toList()

    fun clear() {
        reviews.clear()
    }
}
