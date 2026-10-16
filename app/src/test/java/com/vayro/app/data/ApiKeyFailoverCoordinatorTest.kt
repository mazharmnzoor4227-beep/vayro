package com.vayro.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiKeyFailoverCoordinatorTest {

    private lateinit var coordinator: ApiKeyFailoverCoordinator

    private fun cred(id: String, name: String, active: Boolean = false) =
        ApiKeyCredential(id, name, "secret-$id", active)

    @Before
    fun setUp() {
        coordinator = ApiKeyFailoverCoordinator()
    }

    @Test
    fun `returns null when no credentials`() {
        assertNull(coordinator.nextKey(emptyList()))
    }

    @Test
    fun `returns first credential when none failed`() {
        val creds = listOf(cred("1", "Primary", true), cred("2", "Backup"))
        assertEquals("1", coordinator.nextKey(creds)?.id)
    }

    @Test
    fun `skips failed key and picks next`() {
        val creds = listOf(cred("1", "Primary", true), cred("2", "Backup"), cred("3", "Tertiary"))
        assertTrue(coordinator.recordFailure("1", "HTTP 429: rate limit exceeded"))
        assertEquals("2", coordinator.nextKey(creds)?.id)
    }

    @Test
    fun `non-key failures do not trigger rotation`() {
        val creds = listOf(cred("1", "Primary", true), cred("2", "Backup"))
        assertFalse(coordinator.recordFailure("1", "network unreachable"))
        assertEquals("1", coordinator.nextKey(creds)?.id)
        assertEquals(0, coordinator.failedCount())
    }

    @Test
    fun `quota failure puts key in cooldown`() {
        val creds = listOf(cred("1", "Primary", true), cred("2", "Backup"))
        val now = System.currentTimeMillis()
        assertTrue(coordinator.recordFailure("1", "quota exceeded", now))
        // Key 1 is in cooldown, key 2 is fresh.
        assertEquals("2", coordinator.nextKey(creds, now)?.id)
    }

    @Test
    fun `cooled down key becomes eligible after cooldown window`() {
        val creds = listOf(cred("1", "Primary", true), cred("2", "Backup"))
        val start = System.currentTimeMillis()
        coordinator.recordFailure("1", "HTTP 429 too many requests", start)
        coordinator.recordFailure("2", "quota exceeded", start)

        // Both failed; none eligible yet.
        assertNull(coordinator.nextKey(creds, start + 1000L))

        // After the cooldown window, the oldest-exhausted key returns.
        val later = start + ApiKeyFailoverCoordinator.QUOTA_COOLDOWN_MS + 1000L
        val chosen = coordinator.nextKey(creds, later)
        assertEquals("1", chosen?.id)
    }

    @Test
    fun `all keys failed returns null when all in cooldown`() {
        val creds = listOf(cred("1", "Primary", true))
        val now = System.currentTimeMillis()
        coordinator.recordFailure("1", "out of credits", now)
        assertNull(coordinator.nextKey(creds, now + 1000L))
    }

    @Test
    fun `reset clears failure state`() {
        val creds = listOf(cred("1", "Primary", true), cred("2", "Backup"))
        coordinator.recordFailure("1", "HTTP 401 unauthorized")
        assertEquals("2", coordinator.nextKey(creds)?.id)
        coordinator.reset()
        assertEquals("1", coordinator.nextKey(creds)?.id)
        assertEquals(0, coordinator.failedCount())
    }

    @Test
    fun `detects key failure patterns`() {
        val failures = listOf(
            "Invalid API key provided",
            "authentication failed",
            "HTTP 401 Unauthorized",
            "HTTP 403 Forbidden",
            "expired API key",
            "quota exceeded for key",
            "rate limit hit",
            "insufficient credits",
            "user not found",
        )
        failures.forEach { reason ->
            assertTrue("Expected key failure for: $reason", ApiKeyFailoverCoordinator.isKeyFailure(reason))
        }
    }

    @Test
    fun `detects quota-specific failures`() {
        val quotas = listOf(
            "quota exceeded",
            "HTTP 429",
            "rate limit exceeded",
            "out of credits",
            "too many requests",
            "usage limit reached",
        )
        quotas.forEach { reason ->
            assertTrue("Expected quota failure for: $reason", ApiKeyFailoverCoordinator.isQuotaFailure(reason))
        }
        assertFalse(ApiKeyFailoverCoordinator.isQuotaFailure("HTTP 401 Unauthorized"))
        assertFalse(ApiKeyFailoverCoordinator.isQuotaFailure("invalid api key"))
    }

    @Test
    fun `multi-key rotation cycles through all keys`() {
        val creds = listOf(cred("1", "K1", true), cred("2", "K2"), cred("3", "K3"))
        val now = System.currentTimeMillis()

        // K1 hits quota -> K2
        coordinator.recordFailure("1", "quota exceeded", now)
        assertEquals("2", coordinator.nextKey(creds, now)?.id)
        // K2 hits quota -> K3
        coordinator.recordFailure("2", "HTTP 429", now)
        assertEquals("3", coordinator.nextKey(creds, now)?.id)
        // K3 hits quota -> none left
        coordinator.recordFailure("3", "rate limit", now)
        assertNull(coordinator.nextKey(creds, now))
        // After cooldown, oldest (K1) rotates back in
        val later = now + ApiKeyFailoverCoordinator.QUOTA_COOLDOWN_MS + 1
        assertEquals("1", coordinator.nextKey(creds, later)?.id)
    }
}
