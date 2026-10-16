package com.vayro.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    @Test
    fun bearerTokenIsRedacted() {
        val redacted = redactSecrets("Request failed: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload")
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(redacted.contains("Bearer [REDACTED]"))
    }

    @Test
    fun apiKeyAssignmentIsRedacted() {
        val redacted = redactSecrets("api_key=supersecret123")
        assertEquals("api_key=[REDACTED]", redacted)
    }

    @Test
    fun passwordAssignmentIsRedacted() {
        val redacted = redactSecrets("login failed: password: hunter2 for user admin")
        assertFalse(redacted.contains("hunter2"))
        assertTrue(redacted.contains("password=[REDACTED]"))
    }

    @Test
    fun underscoredTokenKeyIsRedacted() {
        val redacted = redactSecrets("bearer_token=abc123")
        assertEquals("bearer_token=[REDACTED]", redacted)
    }

    @Test
    fun jsonStyleSecretIsRedacted() {
        val redacted = redactSecrets("{\"api_key\": \"abc123\", \"ok\": true}")
        assertFalse(redacted.contains("abc123"))
        assertTrue(redacted.contains("api_key=[REDACTED]"))
        assertTrue(redacted.contains("\"ok\": true"))
    }

    @Test
    fun privateKeyBlockIsRedacted() {
        val key = "-----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJBAK7\n-----END RSA PRIVATE KEY-----"
        val redacted = redactSecrets("key material leaked: $key done")
        assertFalse(redacted.contains("MIIBOgIBAAJBAK7"))
        assertTrue(redacted.contains("[REDACTED PRIVATE KEY]"))
    }

    @Test
    fun benignTextIsUntouched() {
        val benign = "NullPointerException at MainActivity.onCreate(MainActivity.kt:42)"
        assertEquals(benign, redactSecrets(benign))
    }

    @Test
    fun ordinaryWordsWithoutValuesAreUntouched() {
        val text = "authentication failed: invalid token format, secret storage unavailable"
        assertEquals(text, redactSecrets(text))
    }

    @Test
    fun stackTraceKeepsStructureWhileRedactingSecrets() {
        val trace = "java.lang.RuntimeException: api_key=supersecret\n" +
            "\tat com.example.Api.call(Api.kt:10)\n" +
            "Caused by: token expired: Bearer deadbeef123"
        val redacted = redactSecrets(trace)
        assertFalse(redacted.contains("supersecret"))
        assertFalse(redacted.contains("deadbeef123"))
        assertTrue(redacted.contains("at com.example.Api.call(Api.kt:10)"))
    }
}
