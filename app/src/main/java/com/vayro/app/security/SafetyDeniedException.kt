package com.vayro.app.security

/**
 * M5: thrown when a side-effecting operation is denied by [SafetyGateway].
 * Providers must surface this instead of executing — there is no bypass path.
 */
class SafetyDeniedException(
    val operation: SafetyOperation,
    reason: String
) : SecurityException("Denied: ${operation.describe()}. $reason")
