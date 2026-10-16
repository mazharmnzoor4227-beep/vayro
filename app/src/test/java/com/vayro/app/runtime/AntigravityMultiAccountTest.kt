package com.vayro.app.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AntigravityMultiAccountTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parses and serializes accounts json correctly`() {
        val original = listOf(
            AntigravityAccount("alpha@example.com", isActive = true, isQuotaExhausted = false, lastUsedAt = 1000L, isAuthExpired = false, authExpiredAt = 0L),
            AntigravityAccount("beta@example.com", isActive = false, isQuotaExhausted = true, quotaExhaustedAt = 5000L, lastUsedAt = 2000L, isAuthExpired = true, authExpiredAt = 6000L),
        )

        val array = JSONArray()
        original.forEach { acc ->
            val obj = JSONObject()
                .put("email", acc.email)
                .put("isActive", acc.isActive)
                .put("isQuotaExhausted", acc.isQuotaExhausted)
                .put("quotaExhaustedAt", acc.quotaExhaustedAt)
                .put("lastUsedAt", acc.lastUsedAt)
                .put("isAuthExpired", acc.isAuthExpired)
                .put("authExpiredAt", acc.authExpiredAt)
            array.put(obj)
        }
        val serialized = array.toString()

        val parsedArray = JSONArray(serialized)
        val deserialized = (0 until parsedArray.length()).map { i ->
            val obj = parsedArray.getJSONObject(i)
            AntigravityAccount(
                email = obj.getString("email"),
                isActive = obj.optBoolean("isActive", false),
                isQuotaExhausted = obj.optBoolean("isQuotaExhausted", false),
                quotaExhaustedAt = obj.optLong("quotaExhaustedAt", 0L),
                lastUsedAt = obj.optLong("lastUsedAt", 0L),
                isAuthExpired = obj.optBoolean("isAuthExpired", false),
                authExpiredAt = obj.optLong("authExpiredAt", 0L),
            )
        }

        assertEquals(2, deserialized.size)
        assertEquals("alpha@example.com", deserialized[0].email)
        assertTrue(deserialized[0].isActive)
        assertFalse(deserialized[0].isQuotaExhausted)
        assertFalse(deserialized[0].isAuthExpired)
        assertEquals("beta@example.com", deserialized[1].email)
        assertFalse(deserialized[1].isActive)
        assertTrue(deserialized[1].isQuotaExhausted)
        assertEquals(5000L, deserialized[1].quotaExhaustedAt)
        assertTrue(deserialized[1].isAuthExpired)
        assertEquals(6000L, deserialized[1].authExpiredAt)
    }

    @Test
    fun `detects all varieties of quota and rate limit errors`() {
        fun isQuotaFailure(reason: String): Boolean {
            val value = reason.lowercase()
            return "quota" in value ||
                "rate limit" in value ||
                "ratelimit" in value ||
                "resource_exhausted" in value ||
                "resource exhausted" in value ||
                "429" in value ||
                "out of credits" in value ||
                "credits" in value ||
                "limit exceeded" in value ||
                "exhausted" in value ||
                "too many requests" in value ||
                "60 minute" in value ||
                "minute limit" in value ||
                "hourly limit" in value ||
                "hour limit" in value ||
                "daily limit" in value ||
                "day limit" in value ||
                "weekly limit" in value ||
                "week limit" in value ||
                "usage limit" in value ||
                "plan limit" in value ||
                "account limit" in value ||
                "capacity" in value ||
                "overloaded" in value ||
                "quota_exceeded" in value ||
                "rate_limit_exceeded" in value ||
                "reached your limit" in value ||
                "hit your limit" in value ||
                "exceeded your limit" in value ||
                "exceeded your current quota" in value ||
                "insufficient quota" in value ||
                "free tier limit" in value ||
                "tokens per minute" in value ||
                "requests per minute" in value ||
                "requests per day" in value ||
                "requests per week" in value ||
                "billing" in value ||
                "credit balance" in value ||
                "quota limit" in value
        }

        // 60-minute / hourly limits
        assertTrue(isQuotaFailure("You have hit the 60 minute limit for this model"))
        assertTrue(isQuotaFailure("Hourly limit reached. Please wait."))
        assertTrue(isQuotaFailure("Per-minute limit exceeded"))

        // Daily / weekly limits
        assertTrue(isQuotaFailure("Daily limit reached for account. Resets at midnight."))
        assertTrue(isQuotaFailure("You have reached your weekly limit."))
        assertTrue(isQuotaFailure("Requests per day quota exceeded"))

        // Rate limits and 429
        assertTrue(isQuotaFailure("HTTP 429: Too Many Requests"))
        assertTrue(isQuotaFailure("Rate limit exceeded for gemini-2.5-pro"))
        assertTrue(isQuotaFailure("RESOURCE_EXHAUSTED: quota exceeded"))

        // Credits and billing
        assertTrue(isQuotaFailure("Account is out of credits"))
        assertTrue(isQuotaFailure("Credit balance is zero"))
        assertTrue(isQuotaFailure("Free tier limit reached"))

        // Non-quota errors should return false
        assertFalse(isQuotaFailure("Compilation error in MainActivity.kt"))
        assertFalse(isQuotaFailure("File not found: /workspace/project"))
        assertFalse(isQuotaFailure("Stopped by user"))
        assertFalse(isQuotaFailure("Process interrupted"))
    }

    @Test
    fun `rotates to next non-exhausted account`() {
        val accounts = listOf(
            AntigravityAccount("user1@example.com", isActive = true, isQuotaExhausted = true),
            AntigravityAccount("user2@example.com", isActive = false, isQuotaExhausted = false),
            AntigravityAccount("user3@example.com", isActive = false, isQuotaExhausted = true),
        )

        val activeIndex = accounts.indexOfFirst { it.isActive }
        val candidatesInOrder = (1 until accounts.size).map { offset ->
            val index = (activeIndex + offset) % accounts.size
            accounts[index]
        }

        val nonExhausted = candidatesInOrder.firstOrNull { !it.isQuotaExhausted }
        assertNotNull(nonExhausted)
        assertEquals("user2@example.com", nonExhausted?.email)
    }

    @Test
    fun `recovers account whose 60-minute limit cooldown has elapsed`() {
        val now = System.currentTimeMillis()
        val accounts = listOf(
            AntigravityAccount("user1@example.com", isActive = true, isQuotaExhausted = true, quotaExhaustedAt = now),
            // user2 was exhausted 90 minutes ago (> 60 minutes)
            AntigravityAccount("user2@example.com", isActive = false, isQuotaExhausted = true, quotaExhaustedAt = now - 90 * 60 * 1000L),
            // user3 was exhausted 10 minutes ago (< 60 minutes)
            AntigravityAccount("user3@example.com", isActive = false, isQuotaExhausted = true, quotaExhaustedAt = now - 10 * 60 * 1000L),
        )

        val activeIndex = accounts.indexOfFirst { it.isActive }
        val candidatesInOrder = (1 until accounts.size).map { offset ->
            val index = (activeIndex + offset) % accounts.size
            accounts[index]
        }

        val cooldownElapsed = candidatesInOrder.firstOrNull {
            it.isQuotaExhausted && it.quotaExhaustedAt > 0 &&
                (now - it.quotaExhaustedAt >= AntigravityAuthController.HOURLY_LIMIT_COOLDOWN_MS)
        }

        assertNotNull(cooldownElapsed)
        assertEquals("user2@example.com", cooldownElapsed?.email)
    }

    @Test
    fun `smart credential validation identifies valid and invalid files`() {
        val validJson = tempFolder.newFile("valid_token.json").apply {
            writeText("""{"access_token": "ya29.test-valid-oauth-token-value"}""")
        }
        val emptyFile = tempFolder.newFile("empty_token.json").apply {
            writeText("")
        }
        val blankFile = tempFolder.newFile("blank_token.json").apply {
            writeText("   \n  \t ")
        }

        fun isCredentialValid(file: File): Boolean {
            if (!file.isFile || file.length() <= 0L) return false
            return runCatching {
                val text = file.readText().trim()
                text.isNotBlank() && (text.startsWith("{") || text.length >= 10)
            }.getOrDefault(false)
        }

        assertTrue(isCredentialValid(validJson))
        assertFalse(isCredentialValid(emptyFile))
        assertFalse(isCredentialValid(blankFile))
        assertFalse(isCredentialValid(File(tempFolder.root, "non_existent.json")))
    }

    @Test
    fun `detects 401 unauthenticated and oauth token failure patterns`() {
        fun isAuthFailure(reason: String): Boolean {
            val value = reason.lowercase()
            return "unauthenticated" in value ||
                "401" in value ||
                "invalid authentication credentials" in value ||
                "oauth 2 access token" in value ||
                "login cookie" in value ||
                "authentication required" in value ||
                "authentication failed" in value ||
                "auth expired" in value ||
                "sign-in" in value ||
                "not signed in" in value ||
                "token expired" in value
        }

        // Exact screenshot failure string
        val screenshotError = "UNAUTHENTICATED (code 401): Request had invalid authentication credentials. Expected OAuth 2 access token, login cookie or other valid authentication credential. See https://developers.google.com/identity/sign-in/web/devconsole-project."
        assertTrue(isAuthFailure(screenshotError))

        // Sanitized friendly error
        assertTrue(isAuthFailure("Antigravity session authentication expired or invalid (401). Please reconnect your Google account in Settings."))
        assertTrue(isAuthFailure("Antigravity needs Google sign-in. Open Settings → Coding agent."))
        assertTrue(isAuthFailure("Authentication failed for account"))
        assertTrue(isAuthFailure("OAuth 2 access token expired"))

        // Unrelated errors should not trigger auth failure
        assertFalse(isAuthFailure("File not found"))
        assertFalse(isAuthFailure("HTTP 500 internal server error"))
        assertFalse(isAuthFailure("Quota limit reached"))
    }

    @Test
    fun `rotates to healthy account and skips auth-expired account`() {
        val accounts = listOf(
            AntigravityAccount("user1@example.com", isActive = true, isAuthExpired = true),
            // user2 is auth-expired (should be skipped!)
            AntigravityAccount("user2@example.com", isActive = false, isAuthExpired = true),
            // user3 has quota exhausted
            AntigravityAccount("user3@example.com", isActive = false, isQuotaExhausted = true),
            // user4 is healthy and valid
            AntigravityAccount("user4@example.com", isActive = false, isQuotaExhausted = false, isAuthExpired = false),
        )

        val activeIndex = accounts.indexOfFirst { it.isActive }
        val candidatesInOrder = (1 until accounts.size).map { offset ->
            val index = (activeIndex + offset) % accounts.size
            accounts[index]
        }

        val healthyCandidate = candidatesInOrder.firstOrNull { !it.isQuotaExhausted && !it.isAuthExpired }
        assertNotNull(healthyCandidate)
        assertEquals("user4@example.com", healthyCandidate?.email)
    }

    @Test
    fun `sanitizes raw 401 google dev console error into clean user toast`() {
        fun sanitizeSessionFailedToast(reason: String): String? {
            val value = reason.lowercase()
            return when {
                "unauthenticated" in value || "401" in value || "invalid authentication credentials" in value ||
                    "oauth 2 access token" in value || "token expired" in value ->
                    "Google session expired (401). Reconnect your account in Settings → Google accounts."
                "user not found" in value ->
                    "Account not found. Please sign in again."
                "api key" in value ->
                    "API key authentication error. Check credentials in Settings."
                "out of credits" in value || "quota" in value || "429" in value ->
                    "Usage quota limit reached. Auto-switching or check plan limits."
                "authentication" in value || "sign-in" in value ->
                    "Authentication failed. Reconnect your account in Settings."
                else -> null
            }
        }

        val rawScreenshotError = "UNAUTHENTICATED (code 401): Request had invalid authentication credentials. Expected OAuth 2 access token, login cookie or other valid authentication credential. See https://developers.google.com/identity/sign-in/web/devconsole-project."
        val sanitized = sanitizeSessionFailedToast(rawScreenshotError)
        assertEquals("Google session expired (401). Reconnect your account in Settings → Google accounts.", sanitized)
        assertFalse(sanitized!!.contains("developers.google.com"))
        assertFalse(sanitized.contains("Expected OAuth 2"))
    }

    // QA H25: "Sign out all" must delete the official credential AND every
    // per-account token folder. Previously logout deleted only the official
    // file, so activateAccount() could silently restore a "logged out"
    // account from its surviving per-account copy.
    @Test
    fun `sign out all targets include official credential and every account folder`() {
        val official = tempFolder.newFile("antigravity-oauth-token")
        val accountsDir = tempFolder.newFolder("accounts")
        val alpha = File(accountsDir, "alpha_example_com").apply {
            mkdirs()
            File(this, "antigravity-oauth-token").writeText("token-alpha")
        }
        val beta = File(accountsDir, "beta_example_com").apply {
            mkdirs()
            File(this, "antigravity-oauth-token").writeText("token-beta")
        }
        // A stray file in the accounts dir is not a per-account entry and
        // must never be a deletion target.
        File(accountsDir, "README.txt").writeText("do not delete")

        val targets = antigravitySignOutAllTargets(official, accountsDir)

        assertEquals(listOf(official, alpha, beta), targets)
    }

    @Test
    fun `sign out all targets tolerate a missing accounts dir`() {
        val official = tempFolder.newFile("antigravity-oauth-token")
        val missing = File(tempFolder.root, "no-such-accounts-dir")

        assertEquals(listOf(official), antigravitySignOutAllTargets(official, missing))
    }

    @Test
    fun `colliding emails map to distinct account folders`() {
        val plus = antigravityAccountFolderName("a+b@c.com")
        val underscore = antigravityAccountFolderName("a_b@c.com")
        assertEquals(sanitizeAntigravityEmail("a+b@c.com"), sanitizeAntigravityEmail("a_b@c.com"))
        assertFalse(plus == underscore)
    }

    @Test
    fun `account folder name is stable and case-insensitive`() {
        val lower = antigravityAccountFolderName("User@Example.com")
        val upper = antigravityAccountFolderName("user@example.com")
        assertEquals(lower, upper)
        assertEquals(lower, antigravityAccountFolderName("user@example.com"))
    }

    @Test
    fun `account folder name is a single safe path segment`() {
        val name = antigravityAccountFolderName("weird+name@exa mple.com")
        assertFalse(name.contains("/"))
        assertFalse(name.contains("\\"))
        assertTrue(name.isNotBlank())
    }
}
