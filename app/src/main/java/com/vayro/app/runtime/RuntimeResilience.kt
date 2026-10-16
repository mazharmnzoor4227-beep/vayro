package com.vayro.app.runtime

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

object RuntimeFailureClassifier {
    fun isTransientNetworkFailure(reason: String): Boolean {
        val value = reason.lowercase(Locale.ROOT)
        if (value.isBlank() || isPermanentProviderFailure(value) || value.contains("stopped by user")) return false
        return listOf(
            "network error", "network unavailable", "network is unreachable", "network connection",
            "network issue", "issue connecting", "connecting to the server", "failed to connect",
            "could not connect", "unable to connect", "connection failed", "network problem",
            "failed to reach", "offline", "connection reset", "connection refused", "connection closed",
            "connection abort", "connection aborted", "software caused connection abort",
            "connection timed out", "connect timed out", "connection interrupted",
            "connection lost", "read tcp", "write tcp", "broken pipe",
            "socket timeout", "sockettimeoutexception", "socketexception",
            "unknownhostexception", "connectexception", "dns resolution failed", "dns lookup failed",
            "temporary failure", "temporarily unavailable", "service unavailable",
            "bad gateway", "gateway timeout", "http 408", "http 425", "http 502",
            "http 503", "http 504", "http 522", "http 524", "unexpected eof",
            "stream closed", "stream reset", "stream error", "http2: stream error",
            "streamgeneratecontent", "unexpected end of", "transport error",
            "client.timeout", "context deadline exceeded", "handshake timeout", "tls handshake",
            "fetch failed", "econnrefused", "econnreset", "etimedout",
            "enotfound", "ehostunreach", "enetunreach", "err_network_changed",
            "err_internet_disconnected", "err_connection_reset",
            "err_connection_timed_out", "err_connection_refused",
        ).any(value::contains)
    }

    private fun isPermanentProviderFailure(value: String): Boolean =
        listOf(
            "authentication required", "authentication failed", "unauthorized", "forbidden",
            "http 401", "http 403", "invalid api", "invalid model", "unknown model", "quota",
            "out of credits", "rate limit", "http 429", "user not found", "not signed in",
        ).any(value::contains)

    fun friendlyNetworkErrorMessage(raw: String): String {
        val value = raw.lowercase(Locale.ROOT)
        return when {
            value.contains("connection abort") || value.contains("connection reset") || value.contains("read tcp") ->
                "Network connection was interrupted by cellular/Wi-Fi disturbance. The connection dropped midway."
            value.contains("timeout") || value.contains("timed out") || value.contains("deadline") ->
                "Network request timed out while waiting for a response from the server."
            value.contains("dns") || value.contains("unknownhost") ->
                "Unable to resolve host. Please check your internet connection."
            value.contains("unreachable") || value.contains("no route") || value.contains("offline") ->
                "Network is unreachable. Please verify your internet connection."
            value.contains("connecting to the server") || value.contains("issue connecting") || value.contains("network issue") ->
                "There was a network issue connecting to the server. Please check your internet connection."
            else ->
                "Network connection error occurred while communicating with the service."
        }
    }

    fun normalizeThrowable(error: Throwable): String = when (error) {
        is UnknownHostException -> "Network unavailable: DNS lookup failed."
        is SocketTimeoutException -> "Network error: connection timed out."
        is ConnectException -> "Network error: connection could not be established."
        is SocketException -> "Network error: " + error.message.orEmpty().ifBlank { "socket connection failed" } + "."
        else -> error.message.orEmpty().ifBlank { error::class.java.simpleName }
    }

    fun verifyAndHealRootfs(rootfsDir: java.io.File): RootfsIntegrityReport =
        RootfsIntegrityVerifier.verifyAndHeal(rootfsDir)
}

data class RuntimeRetryPolicy(
    val maxAutomaticRetries: Int = 15,
    val initialBackoffMillis: Long = 2_000L,
    val maxBackoffMillis: Long = 15_000L,
    val jitterRatio: Double = 0.20,
    val maxTotalRetryDurationMillis: Long = 180_000L,
) {
    init {
        require(maxAutomaticRetries >= 0)
        require(initialBackoffMillis > 0)
        require(maxBackoffMillis >= initialBackoffMillis)
        require(jitterRatio in 0.0..0.5)
        require(maxTotalRetryDurationMillis >= initialBackoffMillis)
    }

    fun delayMillis(attempt: Int, jitter: Double): Long {
        require(attempt >= 1)
        val exponential = initialBackoffMillis.toDouble() * (1L shl (attempt - 1).coerceAtMost(20))
        val bounded = exponential.coerceAtMost(maxBackoffMillis.toDouble())
        val factor = 1.0 + ((jitter * 2.0) - 1.0) * jitterRatio
        return (bounded * factor).toLong().coerceAtLeast(250L)
    }
}

fun formatDurationText(totalSeconds: Long): String = when {
    totalSeconds >= 3_600 -> "${totalSeconds / 3_600}h ${(totalSeconds % 3_600) / 60}m"
    totalSeconds >= 60 -> "${totalSeconds / 60}m ${totalSeconds % 60}s"
    else -> "${totalSeconds}s"
}

