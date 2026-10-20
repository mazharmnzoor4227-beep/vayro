package com.vayro.app.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

enum class AppErrorCategory {
    RUNTIME_CRASH,
    PROCESS_TIMEOUT,
    FILESYSTEM_CORRUPTION,
    NETWORK_ERROR,
    GIT_ERROR,
    PERMISSION_DENIED,
    LOW_STORAGE,
    UNKNOWN
}

data class AppErrorReport(
    val id: String = UUID.randomUUID().toString(),
    val category: AppErrorCategory,
    val message: String,
    val exceptionType: String? = null,
    val stackTraceSnippet: String? = null,
    val isRecoverable: Boolean = true,
    val suggestedAction: String? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * Standardized Central Application Error Coordinator (Phase 1 Foundation Hardening).
 * Collects, categorizes, and coordinates recovery suggestions for runtime crashes,
 * process failures, filesystem errors, and network issues.
 */
class AppErrorCoordinator(
    private val maxHistorySize: Int = 50
) {

    private val _recentErrors = MutableStateFlow<List<AppErrorReport>>(emptyList())
    val recentErrors: StateFlow<List<AppErrorReport>> = _recentErrors.asStateFlow()

    private val _activeCriticalError = MutableStateFlow<AppErrorReport?>(null)
    val activeCriticalError: StateFlow<AppErrorReport?> = _activeCriticalError.asStateFlow()

    fun reportError(
        category: AppErrorCategory,
        message: String,
        throwable: Throwable? = null,
        isRecoverable: Boolean = true,
        suggestedAction: String? = null
    ): AppErrorReport {
        val stackSnippet = throwable?.let { t ->
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            sw.toString().lineSequence().take(10).joinToString("\n")
        }

        val report = AppErrorReport(
            category = category,
            message = message,
            exceptionType = throwable?.javaClass?.simpleName,
            stackTraceSnippet = stackSnippet,
            isRecoverable = isRecoverable,
            suggestedAction = suggestedAction ?: defaultSuggestedAction(category)
        )

        _recentErrors.update { list ->
            (listOf(report) + list).take(maxHistorySize)
        }

        if (!isRecoverable) {
            _activeCriticalError.value = report
        }

        return report
    }

    fun clearError(id: String) {
        _recentErrors.update { list -> list.filter { it.id != id } }
        if (_activeCriticalError.value?.id == id) {
            _activeCriticalError.value = null
        }
    }


    fun diagnoseThrowable(throwable: Throwable): AppErrorCategory {
        val msg = (throwable.message ?: "").lowercase()
        val name = throwable.javaClass.name.lowercase()

        return when {
            name.contains("timeout") || msg.contains("timed out") -> AppErrorCategory.PROCESS_TIMEOUT
            name.contains("security") || msg.contains("permission denied") -> AppErrorCategory.PERMISSION_DENIED
            name.contains("socket") || name.contains("unknownhost") || msg.contains("network") || msg.contains("connection refused") -> AppErrorCategory.NETWORK_ERROR
            msg.contains("no space left") || msg.contains("storage") -> AppErrorCategory.LOW_STORAGE
            msg.contains("git") || msg.contains("repository") -> AppErrorCategory.GIT_ERROR
            msg.contains("rootfs") || msg.contains("corrupt") || msg.contains("enoent") -> AppErrorCategory.FILESYSTEM_CORRUPTION
            else -> AppErrorCategory.RUNTIME_CRASH
        }
    }

    private fun defaultSuggestedAction(category: AppErrorCategory): String {
        return when (category) {
            AppErrorCategory.RUNTIME_CRASH -> "Restart the runtime session or check logs in Settings."
            AppErrorCategory.PROCESS_TIMEOUT -> "The command took too long. Try running with smaller inputs or in the background."
            AppErrorCategory.FILESYSTEM_CORRUPTION -> "Run integrity verification or restore from the latest workspace checkpoint."
            AppErrorCategory.NETWORK_ERROR -> "Check your internet connection or proxy settings."
            AppErrorCategory.GIT_ERROR -> "Inspect git status or discard uncommitted conflicting changes."
            AppErrorCategory.PERMISSION_DENIED -> "Grant the necessary permission in Settings or the safety prompt."
            AppErrorCategory.LOW_STORAGE -> "Free up internal storage space on your device before proceeding."
            AppErrorCategory.UNKNOWN -> "Retry the action or check session logs."
        }
    }
}
