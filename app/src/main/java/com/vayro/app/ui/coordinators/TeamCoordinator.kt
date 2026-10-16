package com.vayro.app.ui.coordinators

import com.vayro.app.agent.team.AgentTeamOrchestrator

/**
 * Multi-agent team domain coordinator (Milestone 1).
 *
 * Explicit owner of the team-session state machine, delegating to the real
 * [AgentTeamOrchestrator]. Only real execution exists: without a wired
 * per-role executor the orchestrator refuses to run instead of simulating.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class TeamCoordinator(
    private val orchestrator: AgentTeamOrchestrator,
) {
    fun planTeamProject(goal: String) {
        orchestrator.planProject(goal)
    }

    fun cancelTeamSession() {
        orchestrator.cancelSession()
    }
}
