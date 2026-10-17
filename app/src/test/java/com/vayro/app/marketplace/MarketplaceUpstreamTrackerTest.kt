package com.vayro.app.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceUpstreamTrackerTest {

    @Test
    fun `parseGitHubRepo extracts owner and repo from plain repo url`() {
        assertEquals(
            "morluto" to "rea",
            parseGitHubRepo("https://github.com/morluto/rea"),
        )
    }

    @Test
    fun `parseGitHubRepo tolerates trailing slash and git suffix`() {
        assertEquals(
            "deepfounder-ai" to "desigma",
            parseGitHubRepo("https://github.com/deepfounder-ai/desigma/"),
        )
        assertEquals(
            "creatorberry" to "flick",
            parseGitHubRepo("https://github.com/creatorberry/flick.git"),
        )
    }

    @Test
    fun `parseGitHubRepo rejects non github and malformed urls`() {
        assertNull(parseGitHubRepo(""))
        assertNull(parseGitHubRepo("npx -y some-package"))
        assertNull(parseGitHubRepo("https://gitlab.com/owner/repo"))
        assertNull(parseGitHubRepo("https://github.com/onlyowner"))
        assertNull(parseGitHubRepo("https://github.com/a/b/c"))
        assertNull(parseGitHubRepo("template://android-compose"))
    }

    @Test
    fun `isUpdateAvailable only true when both shas known and different`() {
        assertTrue(isUpdateAvailable("abc123", "def456"))
        assertFalse(isUpdateAvailable("abc123", "abc123"))
        assertFalse(isUpdateAvailable("ABC123", "abc123"))
        assertFalse(isUpdateAvailable("", "def456"))
        assertFalse(isUpdateAvailable("abc123", ""))
        assertFalse(isUpdateAvailable(null, "def456"))
        assertFalse(isUpdateAvailable("abc123", null))
    }

    @Test
    fun `updateCheckDue respects the six hour window`() {
        val now = 1_700_000_000_000L
        assertTrue(updateCheckDue(0L, now))
        assertTrue(updateCheckDue(-5L, now))
        assertFalse(updateCheckDue(now - 60_000L, now))
        assertFalse(updateCheckDue(now - MARKETPLACE_UPDATE_CHECK_INTERVAL_MS + 1_000L, now))
        assertTrue(updateCheckDue(now - MARKETPLACE_UPDATE_CHECK_INTERVAL_MS, now))
        assertTrue(updateCheckDue(now - MARKETPLACE_UPDATE_CHECK_INTERVAL_MS - 1_000L, now))
    }

    @Test
    fun `pendingSeedIds never returns ids that already have records`() {
        val installed = setOf("catalog-skill-rea", "catalog-skill-compose-architect")
        assertEquals(
            setOf(
                "catalog-skill-desigma",
                "catalog-skill-flick",
                "catalog-skill-app-store-screenshots",
                "catalog-skill-ux-ui-agent-skills",
                "catalog-skill-gsap-skills",
                "catalog-skill-typography-system",
                "catalog-skill-awesome-gamedev-agent-skills",
                "catalog-skill-game-ui-mobile-friendly-design",
            ),
            pendingSeedIds(CURATED_PRESEED_IDS, installed),
        )
    }

    @Test
    fun `pendingSeedIds returns everything when nothing installed`() {
        assertEquals(CURATED_PRESEED_IDS, pendingSeedIds(CURATED_PRESEED_IDS, emptySet()))
    }

    @Test
    fun `pendingSeedIds returns nothing when all seeded`() {
        assertTrue(pendingSeedIds(CURATED_PRESEED_IDS, CURATED_PRESEED_IDS).isEmpty())
    }

    @Test
    fun `curated preseed set holds exactly the nine expected ids`() {
        assertEquals(
            setOf(
                "catalog-skill-rea",
                "catalog-skill-desigma",
                "catalog-skill-flick",
                "catalog-skill-app-store-screenshots",
                "catalog-skill-ux-ui-agent-skills",
                "catalog-skill-gsap-skills",
                "catalog-skill-typography-system",
                "catalog-skill-awesome-gamedev-agent-skills",
                "catalog-skill-game-ui-mobile-friendly-design",
            ),
            CURATED_PRESEED_IDS,
        )
    }
}
