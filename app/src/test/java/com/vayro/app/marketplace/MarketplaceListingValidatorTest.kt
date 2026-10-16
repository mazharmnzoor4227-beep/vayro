package com.vayro.app.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceListingValidatorTest {

    private fun validDraft() = ListingDraft(
        name = "Postgres MCP Pro",
        category = MarketplaceCategory.MCP_SERVERS,
        description = "A production-ready Postgres MCP server with pooling.",
        version = "2.1.0",
        artifactUrl = "https://example.com/postgres-pro.zip",
        sha256 = "b".repeat(64),
        tags = listOf("sql", "postgres", "mcp"),
    )

    @Test
    fun validDraftPasses() {
        val result = validateListing(validDraft())
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun shortNameFails() {
        val result = validateListing(validDraft().copy(name = "AB"))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("name") })
    }

    @Test
    fun missingCategoryFails() {
        val result = validateListing(validDraft().copy(category = null))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("category") })
    }

    @Test
    fun shortDescriptionFails() {
        val result = validateListing(validDraft().copy(description = "Too short"))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("description") })
    }

    @Test
    fun badVersionFails() {
        listOf("v1", "1.0.0.0.0x", "latest", "").forEach { v ->
            val result = validateListing(validDraft().copy(version = v))
            assertFalse("version '$v' should fail", result.isValid)
        }
    }

    @Test
    fun prereleaseVersionPasses() {
        val result = validateListing(validDraft().copy(version = "2.0.0-beta.1"))
        assertTrue(result.isValid)
    }

    @Test
    fun nonHttpArtifactUrlFails() {
        val result = validateListing(validDraft().copy(artifactUrl = "ftp://example.com/x.zip"))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("artifactUrl") })
    }

    @Test
    fun blankArtifactUrlFails() {
        val result = validateListing(validDraft().copy(artifactUrl = "  "))
        assertFalse(result.isValid)
    }

    @Test
    fun badSha256Fails() {
        val result = validateListing(validDraft().copy(sha256 = "not-hex"))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("sha256") })
    }

    @Test
    fun tooManyTagsFail() {
        val result = validateListing(validDraft().copy(tags = (1..11).map { "tag$it" }))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("tags") })
    }

    @Test
    fun badTagFails() {
        val result = validateListing(validDraft().copy(tags = listOf("ok-tag", "bad tag!")))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("tag") })
    }

    @Test
    fun multipleErrorsAreAllReported() {
        val result = validateListing(
            validDraft().copy(name = "x", description = "y", artifactUrl = "ftp://example.com/x.zip")
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.size >= 3)
    }

    @Test
    fun draftConvertsToListingJson() {
        val json = validDraft().toListingJson()
        assertEquals("Postgres MCP Pro", json.getString("name"))
        assertEquals("MCP_SERVERS", json.getString("category"))
        // No price, no seller: everything in the marketplace is free.
        assertFalse(json.has("price"))
        assertFalse(json.has("sellerId"))
    }

    // QA C8: publishing validates the item before persisting it.

    private fun validItem() = MarketplaceItem(
        name = "Postgres MCP",
        category = MarketplaceCategory.MCP_SERVERS,
        author = "Local Developer",
        description = "A production-ready Postgres MCP server with pooling.",
        installUrl = "npx -y @modelcontextprotocol/server-postgres",
        isCustom = true,
    )

    @Test
    fun validMarketplaceItemPasses() {
        assertTrue(validateMarketplaceItem(validItem()).isValid)
    }

    @Test
    fun shortMarketplaceItemNameFails() {
        val result = validateMarketplaceItem(validItem().copy(name = "AB"))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("name") })
    }

    @Test
    fun shortMarketplaceItemDescriptionFails() {
        val result = validateMarketplaceItem(validItem().copy(description = "Too short"))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("description") })
    }

    @Test
    fun blankMarketplaceItemInstallUrlFails() {
        val result = validateMarketplaceItem(validItem().copy(installUrl = "  "))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("install") })
    }

    @Test
    fun commandInstallUrlPassesValidation() {
        // An MCP item's install value is a command, not a URL: the
        // ListingDraft http(s) artifactUrl rule must not apply here.
        val result = validateMarketplaceItem(validItem().copy(installUrl = "npx -y some-mcp-server"))
        assertTrue(result.isValid)
    }

    @Test
    fun malformedSha256Fails() {
        val result = validateMarketplaceItem(validItem().copy(sha256 = "not-a-hash"))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("sha256") })
    }

    @Test
    fun wellFormedSha256Passes() {
        val result = validateMarketplaceItem(validItem().copy(sha256 = "a".repeat(64)))
        assertTrue(result.isValid)
    }

    @Test
    fun malformedTagFails() {
        val result = validateMarketplaceItem(validItem().copy(tags = listOf("good-tag", "bad tag!")))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("bad tag!") })
    }

    @Test
    fun tooManyTagsFailOnItem() {
        val result = validateMarketplaceItem(validItem().copy(tags = (1..11).map { "tag$it" }))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("10 tags") })
    }

    @Test
    fun wellFormedTagsPass() {
        val result = validateMarketplaceItem(validItem().copy(tags = listOf("mcp", "database", "postgres")))
        assertTrue(result.isValid)
    }
}
