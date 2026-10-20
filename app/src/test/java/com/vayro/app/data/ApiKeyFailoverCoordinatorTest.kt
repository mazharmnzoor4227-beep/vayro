package com.vayro.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v3.2.49: the coordinator's session-scoped rotation state (nextKey /
 * reset / failedCount) was removed as production-dead — rotation is
 * hand-rolled at the call sites. What remains, and is pinned here, is
 * the shared failure classification the live rotation paths consult.
 */
class ApiKeyFailoverCoordinatorTest {

    private lateinit var coordinator: ApiKeyFailoverCoordinator

    @Before
    fun setUp() {
        coordinator = ApiKeyFailoverCoordinator()
    }

    @Test
    fun `recordFailure reports key failures only`() {
        assertTrue(coordinator.recordFailure("1", "HTTP 429: rate limit exceeded"))
        assertTrue(coordinator.recordFailure("1", "quota exceeded"))
        assertTrue(coordinator.recordFailure("1", "HTTP 401 unauthorized"))
        assertFalse(coordinator.recordFailure("1", "network unreachable"))
        assertFalse(coordinator.recordFailure("1", "the model is overloaded right now"))
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
}
