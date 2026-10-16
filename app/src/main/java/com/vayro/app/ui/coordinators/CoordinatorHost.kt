package com.vayro.app.ui.coordinators

import android.content.Context
import com.vayro.app.ui.AppUiState
import kotlinx.coroutines.CoroutineScope

/**
 * Shared host for the domain coordinators extracted from `MainViewModel`
 * (Milestone 1 — decompose the monolithic ViewModel).
 *
 * Each coordinator owns exactly one domain's operations and is the explicit
 * owner of that domain's state machine. The host gives coordinators scoped
 * access to what they need — coroutine scope, UI-state reads/updates, the
 * application context, and toasts — without inheriting the whole ViewModel.
 *
 * `MainViewModel` keeps a stable public API by delegating to these
 * coordinators, so existing UI callers and tests keep working unchanged.
 */
class CoordinatorHost(
    val appContext: Context,
    val scope: CoroutineScope,
    val state: () -> AppUiState,
    val updateState: ((AppUiState) -> AppUiState) -> Unit,
    val showToast: (String) -> Unit,
)
