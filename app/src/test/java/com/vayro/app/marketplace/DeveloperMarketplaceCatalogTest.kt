package com.vayro.app.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeveloperMarketplaceCatalogTest {

    @Before
    fun setUp() {
        // Clean any custom items or test state
        DeveloperMarketplaceCatalog.getAllItems().filter { it.isCustom }.forEach {
            DeveloperMarketplaceCatalog.removeCustomItem(it.id)
        }
    }

    @Test
    fun testCuratedCatalogHasAllFourCategories() {
        val allItems = DeveloperMarketplaceCatalog.curatedCatalog
        assertTrue("Curated catalog should contain items", allItems.isNotEmpty())

        val agents = DeveloperMarketplaceCatalog.filterByCategory(MarketplaceCategory.AGENTS)
        val skills = DeveloperMarketplaceCatalog.filterByCategory(MarketplaceCategory.SKILLS)
        val mcp = DeveloperMarketplaceCatalog.filterByCategory(MarketplaceCategory.MCP_SERVERS)
        val templates = DeveloperMarketplaceCatalog.filterByCategory(MarketplaceCategory.TEMPLATES)

        assertTrue("Should have agents", agents.isNotEmpty())
        assertTrue("Should have skills", skills.isNotEmpty())
        assertTrue("Should have MCP servers", mcp.isNotEmpty())
        assertTrue("Should have starter templates", templates.isNotEmpty())
    }

    @Test
    fun testSearchByNameAndDescriptionAndTags() {
        val postgresResults = DeveloperMarketplaceCatalog.search("PostgreSQL")
        assertTrue("Should find PostgreSQL item", postgresResults.any { it.name.contains("PostgreSQL") })

        val dockerResults = DeveloperMarketplaceCatalog.search("docker")
        assertTrue("Should find Docker item via description or tag", dockerResults.isNotEmpty())

        val kotlinTagResults = DeveloperMarketplaceCatalog.search("kotlin")
        assertTrue("Should find Kotlin tagged items", kotlinTagResults.isNotEmpty())

        val blankResults = DeveloperMarketplaceCatalog.search("")
        assertEquals(DeveloperMarketplaceCatalog.getAllItems().size, blankResults.size)
    }

    @Test
    fun testInstallationTracking() {
        val firstItem = DeveloperMarketplaceCatalog.curatedCatalog.first()
        val itemId = firstItem.id

        // Initially not installed
        DeveloperMarketplaceCatalog.uninstallItem(itemId)
        assertFalse(DeveloperMarketplaceCatalog.isInstalled(itemId))

        // Mark installed
        DeveloperMarketplaceCatalog.markInstalled(itemId)
        assertTrue(DeveloperMarketplaceCatalog.isInstalled(itemId))
        assertTrue(DeveloperMarketplaceCatalog.getInstalledItems().any { it.id == itemId })

        // Toggle install off
        val toggledOff = DeveloperMarketplaceCatalog.toggleInstall(itemId)
        assertFalse(toggledOff)
        assertFalse(DeveloperMarketplaceCatalog.isInstalled(itemId))

        // Toggle install on
        val toggledOn = DeveloperMarketplaceCatalog.toggleInstall(itemId)
        assertTrue(toggledOn)
        assertTrue(DeveloperMarketplaceCatalog.isInstalled(itemId))

        // Clean up
        DeveloperMarketplaceCatalog.uninstallItem(itemId)
        assertFalse(DeveloperMarketplaceCatalog.isInstalled(itemId))
    }

    @Test
    fun testCustomItemLifecycle() {
        val customItem = MarketplaceItem(
            name = "My Custom Rust Linter",
            category = MarketplaceCategory.SKILLS,
            author = "Dev Team",
            description = "Custom clippy integration skill",
            installUrl = "https://github.com/myteam/rust-linter",
            tags = listOf("rust", "linter", "custom"),
        )

        DeveloperMarketplaceCatalog.addCustomItem(customItem)
        val found = DeveloperMarketplaceCatalog.search("Custom Rust Linter")
        assertTrue("Should find custom item", found.isNotEmpty())
        assertTrue("Custom flag should be set", found.first().isCustom)

        DeveloperMarketplaceCatalog.removeCustomItem(found.first().id)
        val afterRemoval = DeveloperMarketplaceCatalog.search("Custom Rust Linter")
        assertTrue("Should no longer find removed custom item", afterRemoval.isEmpty())
    }

    @Test
    fun curatedItemIdsAreStableAndUnique() {
        // QA H32: curated ids must not regenerate per launch, or durable
        // install records keyed by id can never match after restart.
        val ids = DeveloperMarketplaceCatalog.curatedCatalog.map { it.id }
        assertEquals("Curated ids must be unique", ids.size, ids.toSet().size)
        assertTrue("Curated ids must be explicit stable ids", ids.all { it.startsWith("catalog-") })
        assertEquals(
            "Curated ids must be identical across reads (no regeneration)",
            ids,
            DeveloperMarketplaceCatalog.curatedCatalog.map { it.id }
        )
    }

    @Test
    fun curatedItemsCarryNoFabricatedSocialProof() {
        // QA H33: no rating/review submission exists, so curated items must
        // not carry invented ratings or download counts.
        DeveloperMarketplaceCatalog.curatedCatalog.forEach { item ->
            assertEquals("No fabricated rating for ${item.name}", 0.0, item.rating, 0.0)
            assertEquals("No fabricated downloads for ${item.name}", 0, item.downloads)
        }
    }

    @Test
    fun marketplaceMcpListingsDeriveFromSharedTemplates() {
        // QA M-SECMKT: the marketplace MCP catalog and the Skills MCP
        // catalog must not drift — listings derive from McpBuiltInCatalogTemplates.
        val mcpItems = DeveloperMarketplaceCatalog.curatedCatalog
            .filter { it.category == MarketplaceCategory.MCP_SERVERS }
        val templates = com.vayro.app.mcp.McpBuiltInCatalogTemplates
        assertEquals(templates.size, mcpItems.size)
        templates.forEach { template ->
            val item = mcpItems.find { it.id == "catalog-mcp-${template.id}" }
            assertNotNull("Marketplace listing missing for template ${template.id}", item)
            assertEquals(template.name, item!!.name)
            assertEquals(template.description, item.description)
            assertEquals(
                (listOf(template.defaultCommand) + template.defaultArgs).joinToString(" "),
                item.installUrl
            )
        }
    }

    @Test
    fun marketplaceItemDefaultsToNoSocialProof() {
        val item = MarketplaceItem(
            name = "X",
            category = MarketplaceCategory.SKILLS,
            author = "A",
            description = "d",
            installUrl = "npx foo",
        )
        assertEquals(0.0, item.rating, 0.0)
        assertEquals(0, item.downloads)
    }
}
