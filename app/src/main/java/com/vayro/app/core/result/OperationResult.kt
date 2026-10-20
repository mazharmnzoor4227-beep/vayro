package com.vayro.app.core.result

/**
 * Shared integrity contract for every operation that can succeed, fail, be
 * cancelled, be unconfigured, or be simulated (Milestone 0 — integrity gate).
 *
 * Rule: no feature may report success without a real result. Anything that did
 * not genuinely execute must surface as [OperationStatus.SIMULATED] (explicit
 * demo mode) or [OperationStatus.NOT_CONFIGURED] (missing backend/credential),
 * never as [OperationStatus.SUCCESS].
 */
enum class OperationStatus {
    /** The work genuinely executed and its outcome was verified. */
    SUCCESS,

    /** The work executed (or was attempted) and did not succeed. See [OperationResult.error]. */
    FAILED,

    /** The work was cancelled before completion. */
    CANCELLED,

    /** No real backend/credential/endpoint is configured, so nothing executed. */
    NOT_CONFIGURED,

    /** Explicit demo/simulation mode: no real work executed. Must never count as real approval. */
    SIMULATED,
}

/**
 * The outcome of a single operation.
 *
 * @param status machine-readable outcome. Never SUCCESS unless the work really ran.
 * @param exitCode process exit code when the operation ran a real command, null otherwise.
 * @param startedAt epoch millis when the operation began.
 * @param finishedAt epoch millis when the operation ended, null if still running.
 * @param logs real captured output lines (stdout/stderr), in order. Never canned text.
 * @param artifacts identifiers/paths of genuinely produced artifacts.
 * @param error human-readable failure reason; required when [status] is FAILED.
 * @param verificationEvidence how success was verified (e.g. "gradle testDebugUnitTest exit=0",
 * "HTTP 201 from api.github.com/repos/.../pulls"). Required when [status] is SUCCESS for
 * externally-visible claims.
 */
data class OperationResult(
    val status: OperationStatus,
    val exitCode: Int? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val logs: List<String> = emptyList(),
    val artifacts: List<String> = emptyList(),
    val error: String? = null,
    val verificationEvidence: String? = null,
) {
    /** True only for genuinely verified success. */
    val isSuccess: Boolean get() = status == OperationStatus.SUCCESS

    /** True when the operation did not really execute (demo or unconfigured). */

    val durationMs: Long get() = (finishedAt ?: System.currentTimeMillis()) - startedAt

    fun finished(
        status: OperationStatus,
        exitCode: Int? = this.exitCode,
        error: String? = this.error,
        verificationEvidence: String? = this.verificationEvidence,
    ): OperationResult = copy(
        status = status,
        exitCode = exitCode,
        finishedAt = System.currentTimeMillis(),
        error = error,
        verificationEvidence = verificationEvidence,
    )

    companion object {
        fun success(
            exitCode: Int? = null,
            logs: List<String> = emptyList(),
            artifacts: List<String> = emptyList(),
            verificationEvidence: String? = null,
            startedAt: Long = System.currentTimeMillis(),
        ): OperationResult = OperationResult(
            status = OperationStatus.SUCCESS,
            exitCode = exitCode,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            logs = logs,
            artifacts = artifacts,
            verificationEvidence = verificationEvidence,
        )

        fun failure(
            error: String,
            exitCode: Int? = null,
            logs: List<String> = emptyList(),
            startedAt: Long = System.currentTimeMillis(),
        ): OperationResult = OperationResult(
            status = OperationStatus.FAILED,
            exitCode = exitCode,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            logs = logs,
            error = error,
        )

        fun notConfigured(message: String): OperationResult = OperationResult(
            status = OperationStatus.NOT_CONFIGURED,
            finishedAt = System.currentTimeMillis(),
            error = message,
        )

        fun simulated(note: String, logs: List<String> = emptyList()): OperationResult = OperationResult(
            status = OperationStatus.SIMULATED,
            finishedAt = System.currentTimeMillis(),
            logs = logs,
            error = note,
        )
    }
}
