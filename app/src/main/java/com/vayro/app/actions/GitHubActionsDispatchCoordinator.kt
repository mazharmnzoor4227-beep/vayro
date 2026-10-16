package com.vayro.app.actions

import com.vayro.app.core.result.OperationResult
import com.vayro.app.git.automation.WorkflowAutomation
import com.vayro.app.network.GitHubClient
import com.vayro.app.security.SafetyGateway
import com.vayro.app.security.SafetyOperation
import com.vayro.app.security.SafetyVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DispatchTarget {
    LOCAL,
    GITHUB_ACTIONS
}

data class ActionsDispatchState(
    val isCheckingAuth: Boolean = false,
    val isAuthenticated: Boolean = false,
    val authenticatedUser: String? = null,
    val isDispatching: Boolean = false,
    val dispatchResult: String? = null,
    val runStatus: String? = null,
)

class GitHubActionsDispatchCoordinator(
    private val gitHubClient: GitHubClient,
    private val workflowAutomation: WorkflowAutomation,
    private val safetyGateway: SafetyGateway,
    private val ghAuthStatus: suspend () -> Pair<Boolean, String?>,
    private val onStartDeviceFlow: () -> Unit,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ActionsDispatchState())
    val state: StateFlow<ActionsDispatchState> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun checkAuthStatus() {
        scope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isCheckingAuth = true)
            try {
                val (authenticated, user) = ghAuthStatus()
                _state.value = _state.value.copy(
                    isCheckingAuth = false,
                    isAuthenticated = authenticated,
                    authenticatedUser = user
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isCheckingAuth = false,
                    isAuthenticated = false,
                    dispatchResult = "Auth check failed: ${e.message}"
                )
            }
        }
    }

    fun startDeviceFlowAuth() {
        onStartDeviceFlow()
    }

    fun dispatchBuild(
        token: String = "",
        repoFullName: String = "mazharmnzoor4227-beep/vayro",
        workflowId: String = "android.yml",
        ref: String = "v3",
    ) {
        if (token.isBlank()) {
            _state.value = _state.value.copy(dispatchResult = "GitHub token not available. Connect GitHub first.")
            return
        }
        val decision = safetyGateway.authorize(
            SafetyOperation.NetworkRequest("https://api.github.com/repos/$repoFullName/actions/workflows/$workflowId/dispatches")
        )
        if (decision.verdict != SafetyVerdict.ALLOW) {
            _state.value = _state.value.copy(dispatchResult = "Dispatch blocked: ${decision.reason}")
            return
        }
        scope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isDispatching = true, dispatchResult = null)
            try {
                val result = workflowAutomation.dispatch(token, repoFullName, workflowId, ref)
                if (result.isSuccess) {
                    _state.value = _state.value.copy(
                        isDispatching = false,
                        dispatchResult = "Dispatch accepted. Polling run status..."
                    )
                    startPolling(token, repoFullName, workflowId)
                } else {
                    _state.value = _state.value.copy(
                        isDispatching = false,
                        dispatchResult = "Dispatch failed: ${result.error}"
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isDispatching = false,
                    dispatchResult = "Dispatch error: ${e.message}"
                )
            }
        }
    }

    private fun startPolling(token: String, repoFullName: String, workflowId: String) {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            repeat(60) {
                delay(10000)
                try {
                    val runs = gitHubClient.listWorkflowRunsForWorkflow(token, repoFullName, workflowId)
                    val latest = runs.firstOrNull()
                    if (latest != null) {
                        _state.value = _state.value.copy(
                            runStatus = "${latest.status} / ${latest.conclusion ?: "in progress"}"
                        )
                        if (latest.status == "completed") {
                            pollJob?.cancel()
                        }
                    }
                } catch (e: Exception) {
                    _state.value = _state.value.copy(runStatus = "Poll error: ${e.message}")
                }
            }
        }
    }

    fun cancel() {
        pollJob?.cancel()
    }
}
