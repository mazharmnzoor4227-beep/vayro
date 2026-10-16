package com.vayro.app.ui.coordinators

import com.vayro.app.marketplace.CURATED_PRESEED_IDS
import com.vayro.app.marketplace.CURATED_SEED_VERSION
import com.vayro.app.marketplace.DeveloperMarketplaceCatalog
import com.vayro.app.marketplace.MarketplaceCategory
import com.vayro.app.marketplace.MarketplaceInstallManager
import com.vayro.app.marketplace.MarketplaceInstallResult
import com.vayro.app.marketplace.MarketplaceItem
import com.vayro.app.marketplace.MarketplacePublishedItemsStore
import com.vayro.app.marketplace.fetchBranchHeadSha
import com.vayro.app.marketplace.fetchRepoDefaultBranch
import com.vayro.app.marketplace.isUpdateAvailable
import com.vayro.app.marketplace.parseGitHubRepo
import com.vayro.app.marketplace.pendingSeedIds
import com.vayro.app.marketplace.resolveUpstreamHeadSha
import com.vayro.app.marketplace.updateCheckDue
import com.vayro.app.marketplace.validateMarketplaceItem
import com.vayro.app.mcp.McpRegistry
import com.vayro.app.mcp.McpServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Marketplace domain coordinator (Milestone 1).
 *
 * Explicit owner of the marketplace install state machine. All installs go
 * through the real [MarketplaceInstallManager] (durable records + on-disk
 * package materialization); results are honest (`Installed` / `Failed(reason)`
 * / `AlreadyInstalled`), never fake success.
 *
 * It also owns publishing: a published item is validated, persisted in
 * [MarketplacePublishedItemsStore], and added to the catalog — so it
 * survives restart instead of living only in the catalog's in-memory list.
 * Publishing is local-only: no registry is configured in the app, so a
 * published item is saved on this device, not shared (the dialog says so).
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class MarketplaceCoordinator(
    private val host: CoordinatorHost,
    private val installManager: MarketplaceInstallManager,
    private val publishedStore: MarketplacePublishedItemsStore,
    private val mcpRegistry: McpRegistry,
    /** Refreshes the MCP server list UI after a marketplace (un)install registers one. */
    private val onMcpServersChanged: () -> Unit = {},
) {
    private val _installedIds = MutableStateFlow(installManager.installedIds())
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

    private val _updatesAvailable = MutableStateFlow<Set<String>>(emptySet())
    val updatesAvailable: StateFlow<Set<String>> = _updatesAvailable.asStateFlow()

    init {
        publishedStore.load().forEach { DeveloperMarketplaceCatalog.addCustomItem(it) }
        host.scope.launch(Dispatchers.IO) { seedCuratedSkills() }
    }

    private fun seedCuratedSkills() {
        if (installManager.curatedSeedVersion() >= CURATED_SEED_VERSION) return
        val items = DeveloperMarketplaceCatalog.getAllItems().filter { it.id in CURATED_PRESEED_IDS }
        var seededAny = false
        for (item in pendingSeedIds(CURATED_PRESEED_IDS, installManager.installedIds()).mapNotNull { id ->
            items.find { it.id == id }
        }) {
            val sha = runCatching { resolveUpstreamHeadSha(item.installUrl) }.getOrNull().orEmpty()
            if (installManager.seedCurated(item, sha)) seededAny = true
        }
        installManager.setCuratedSeedVersion(CURATED_SEED_VERSION)
        if (seededAny) _installedIds.value = installManager.installedIds()
    }

    fun checkMarketplaceUpdates() {
        host.scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!updateCheckDue(installManager.lastUpdateCheckAt(), now)) return@launch
            val found = mutableSetOf<String>()
            var anySuccess = false
            for ((id, record) in installManager.installedPackages.value) {
                val item = DeveloperMarketplaceCatalog.getAllItems().find { it.id == id } ?: continue
                val repo = parseGitHubRepo(item.installUrl) ?: continue
                val head = runCatching {
                    val branch = fetchRepoDefaultBranch(repo.first, repo.second) ?: return@runCatching null
                    fetchBranchHeadSha(repo.first, repo.second, branch)
                }.getOrNull() ?: continue
                anySuccess = true
                if (isUpdateAvailable(record.upstreamSha, head)) {
                    found.add(id)
                } else if (record.upstreamSha.isBlank()) {
                    installManager.recordUpstreamSha(id, head)
                }
            }
            if (anySuccess) installManager.setLastUpdateCheckAt(now)
            _updatesAvailable.value = found
        }
    }

    fun updateMarketplaceItem(item: MarketplaceItem) {
        host.scope.launch(Dispatchers.IO) {
            val repo = parseGitHubRepo(item.installUrl)
            val outcome = if (repo == null) {
                "Cannot update ${item.name}: no GitHub repo linked"
            } else {
                val head = runCatching {
                    val branch = fetchRepoDefaultBranch(repo.first, repo.second) ?: return@runCatching null
                    fetchBranchHeadSha(repo.first, repo.second, branch)
                }.getOrNull()
                if (head.isNullOrBlank()) {
                    "Could not reach GitHub to update ${item.name}; try again later"
                } else {
                    when (val result = installManager.updateToLatest(item, head)) {
                        is MarketplaceInstallResult.Installed -> {
                            _updatesAvailable.value = _updatesAvailable.value - item.id
                            "Updated ${item.name} to the latest version"
                        }
                        is MarketplaceInstallResult.Failed -> "Update failed: ${result.reason}"
                        else -> "Update finished for ${item.name}"
                    }
                }
            }
            _installedIds.value = installManager.installedIds()
            withContext(Dispatchers.Main) {
                host.updateState { it.copy(toastMessage = outcome) }
            }
        }
    }

    /**
     * Publishes [item]: validates it, persists it durably, and adds it to
     * the catalog. Synchronous on purpose: everything here is in-memory
     * plus a SharedPreferences apply() (async disk write), so the screen's
     * recompose after this call already sees the new item.
     */
    fun publishMarketplaceItem(item: MarketplaceItem) {
        val validation = validateMarketplaceItem(item)
        val outcome = if (!validation.isValid) {
            "Could not publish: ${validation.errors.joinToString("; ")}"
        } else {
            publishedStore.add(item)
            DeveloperMarketplaceCatalog.addCustomItem(item)
            "Saved ${item.name} on this device"
        }
        host.updateState { it.copy(toastMessage = outcome) }
    }

    fun installMarketplaceItem(item: MarketplaceItem) {
        host.scope.launch(Dispatchers.IO) {
            val outcome = when (val result = installManager.install(item)) {
                is MarketplaceInstallResult.Installed -> {
                    val mcpNote = registerMarketplaceMcpServer(item)
                    "Installed ${item.name}$mcpNote"
                }
                is MarketplaceInstallResult.ReferenceRecorded ->
                    "Saved ${item.name} as a reference — no package was downloaded"
                is MarketplaceInstallResult.Failed ->
                    "Install failed: ${result.reason}"
                MarketplaceInstallResult.AlreadyInstalled ->
                    "${item.name} is already installed"
            }
            _installedIds.value = installManager.installedIds()
            withContext(Dispatchers.Main) {
                host.updateState { it.copy(toastMessage = outcome) }
            }
        }
    }

    /**
     * Registers an installed MCP marketplace item as a usable MCP server.
     * Returns the toast suffix describing what happened. Non-MCP items
     * return "" — nothing to register.
     */
    private fun registerMarketplaceMcpServer(item: MarketplaceItem): String {
        if (item.category != MarketplaceCategory.MCP_SERVERS) return ""
        val template = com.vayro.app.mcp.McpBuiltInCatalogTemplates
            .find { "catalog-mcp-${it.id}" == item.id }
        val server = marketplaceItemToMcpServer(item)?.copy(
            secretEnvNames = template?.requiredEnv ?: emptyList()
        )
            ?: return " (MCP server not registered: install value is not a command)"
        mcpRegistry.installServer(server)
        onMcpServersChanged()
        return " (MCP server registered)"
    }

    fun uninstallMarketplaceItem(item: MarketplaceItem) {
        host.scope.launch(Dispatchers.IO) {
            val removed = installManager.uninstall(item.id)
            if (item.category == MarketplaceCategory.MCP_SERVERS) {
                // Symmetric with install: an uninstalled MCP item must not
                // leave a registered server behind.
                if (mcpRegistry.removeServer("marketplace-${item.id}")) {
                    onMcpServersChanged()
                }
            }
            _installedIds.value = installManager.installedIds()
            val outcome = if (removed) "Uninstalled ${item.name}" else "${item.name} was not installed"
            withContext(Dispatchers.Main) {
                host.updateState { it.copy(toastMessage = outcome) }
            }
        }
    }
}

/**
 * Maps a marketplace MCP item to the [McpServerInfo] installing it
 * registers. Returns null when the item's install value cannot become a
 * server command (blank, or an http(s) URL — a URL is a download, not a
 * command to run). Server ids are namespaced `marketplace-<itemId>` so a
 * marketplace server never collides with a built-in template id.
 * Pure function, unit-tested.
 */
internal fun marketplaceItemToMcpServer(item: MarketplaceItem): McpServerInfo? {
    val commandLine = item.installUrl.trim()
    if (commandLine.isBlank()) return null
    if (commandLine.startsWith("http://") || commandLine.startsWith("https://")) return null
    val parts = commandLine.split(Regex("\\s+"))
    return McpServerInfo(
        id = "marketplace-${item.id}",
        name = item.name,
        description = item.description,
        command = parts.first(),
        args = parts.drop(1),
        enabled = true,
        isBuiltIn = false,
        projectId = null,
    )
}
