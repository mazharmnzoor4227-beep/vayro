package com.vayro.app.security

private val PEM_BLOCK_PATTERN =
    Regex("(?s)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----")
private val BEARER_PATTERN = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+\\-/]+=*(?![A-Za-z0-9._~+\\-/=])")
private val KEY_VALUE_SECRET_PATTERN = Regex(
    "(?i)(api[_-]?key|secret[_-]?key|access[_-]?key|auth[_-]?token|access[_-]?token" +
        "|client[_-]?secret|password|passwd|pwd|token|secret|auth|credential)\\b[\"']?" +
        "\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|\\S+)"
)

internal fun redactSecrets(text: String): String {
    var result = PEM_BLOCK_PATTERN.replace(text, "[REDACTED PRIVATE KEY]")
    result = BEARER_PATTERN.replace(result, "Bearer [REDACTED]")
    result = KEY_VALUE_SECRET_PATTERN.replace(result, "$1=[REDACTED]")
    return result
}
