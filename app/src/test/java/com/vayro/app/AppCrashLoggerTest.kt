package com.vayro.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCrashLoggerTest {

    @Test
    fun sanitizedThrowableRedactsSecretsButKeepsStackTrace() {
        val original = RuntimeException("login failed: password=hunter2")
        original.stackTrace = arrayOf(StackTraceElement("com.example.Api", "call", "Api.kt", 10))
        val sanitized = AppCrashLogger.sanitizedForCrashlytics(original)
        assertFalse(sanitized.message.orEmpty().contains("hunter2"))
        assertTrue(sanitized.message.orEmpty().contains("[REDACTED]"))
        assertTrue(sanitized.message.orEmpty().contains("RuntimeException"))
        assertEquals(1, sanitized.stackTrace.size)
        assertEquals("com.example.Api", sanitized.stackTrace[0].className)
    }

    @Test
    fun sanitizedThrowableSanitizesCauseChain() {
        val cause = IllegalStateException("api_key=supersecret")
        val outer = RuntimeException("wrapper", cause)
        val sanitized = AppCrashLogger.sanitizedForCrashlytics(outer)
        assertFalse(sanitized.cause?.message.orEmpty().contains("supersecret"))
        assertTrue(sanitized.cause?.message.orEmpty().contains("[REDACTED]"))
    }

    @Test
    fun crashReportingFlagDefaultsOnAndIsSettable() {
        assertTrue(AppCrashLogger.isCrashReportingEnabled)
        AppCrashLogger.setCrashReportingEnabled(false)
        try {
            assertFalse(AppCrashLogger.isCrashReportingEnabled)
        } finally {
            AppCrashLogger.setCrashReportingEnabled(true)
        }
        assertTrue(AppCrashLogger.isCrashReportingEnabled)
    }
}
