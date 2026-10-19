package com.vayro.app.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * Transport type for Model Context Protocol communication.
 */
enum class McpTransportType {
    STDIO,
    SSE,
    STREAMABLE_HTTP
}

/**
 * Definition of an installed or configured MCP server.
 *
 * Secrets are NEVER stored in [env]: names listed in [secretEnvNames] resolve from
 * [McpCredentialStore] at process-start time. Remote servers additionally carry a
 * [remoteUrl] and [authType] whose credentials also live in the credential store.
 */
data class McpServerInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val workingDir: String? = null,
    val enabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val projectId: String? = null,
    val transport: McpTransportType = McpTransportType.STDIO,
    val installedAt: Long = System.currentTimeMillis(),
    val version: String = "1.0.0",
    val secretEnvNames: List<String> = emptyList(),
    val remoteUrl: String? = null,
    val authType: McpAuthType = McpAuthType.NONE,
    val credentialName: String? = null,
    val apiKeyHeaderName: String = "X-API-Key"
) {
    /** True when this server speaks to a remote endpoint instead of a local process. */
    val isRemote: Boolean
        get() = transport == McpTransportType.SSE || transport == McpTransportType.STREAMABLE_HTTP

    fun remoteConfig(): McpRemoteConfig? {
        val url = remoteUrl ?: return null
        return McpRemoteConfig(
            url = url,
            authType = authType,
            credentialName = credentialName,
            apiKeyHeaderName = apiKeyHeaderName
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("command", command)
        put("args", JSONArray(args))
        val envObj = JSONObject()
        env.forEach { (k, v) -> envObj.put(k, v) }
        put("env", envObj)
        workingDir?.let { put("workingDir", it) }
        put("enabled", enabled)
        put("isBuiltIn", isBuiltIn)
        projectId?.let { put("projectId", it) }
        put("transport", transport.name)
        put("installedAt", installedAt)
        put("version", version)
        put("secretEnvNames", JSONArray(secretEnvNames))
        remoteUrl?.let { put("remoteUrl", it) }
        put("authType", authType.name)
        credentialName?.let { put("credentialName", it) }
        put("apiKeyHeaderName", apiKeyHeaderName)
    }

    companion object {
        fun fromJson(json: JSONObject): McpServerInfo {
            val argsList = mutableListOf<String>()
            val argsArr = json.optJSONArray("args")
            if (argsArr != null) {
                for (i in 0 until argsArr.length()) {
                    argsList.add(argsArr.getString(i))
                }
            }

            val envMap = mutableMapOf<String, String>()
            val envObj = json.optJSONObject("env")
            envObj?.keys()?.forEach { k ->
                envMap[k] = envObj.getString(k)
            }

            val secretNames = mutableListOf<String>()
            val secretArr = json.optJSONArray("secretEnvNames")
            if (secretArr != null) {
                for (i in 0 until secretArr.length()) {
                    secretNames.add(secretArr.getString(i))
                }
            }

            val transportStr = json.optString("transport", McpTransportType.STDIO.name)
            val transport = runCatching { McpTransportType.valueOf(transportStr) }
                .getOrDefault(McpTransportType.STDIO)

            val authStr = json.optString("authType", McpAuthType.NONE.name)
            val authType = runCatching { McpAuthType.valueOf(authStr) }
                .getOrDefault(McpAuthType.NONE)

            return McpServerInfo(
                id = json.getString("id"),
                name = json.getString("name"),
                description = json.optString("description", ""),
                command = json.getString("command"),
                args = argsList,
                env = envMap,
                workingDir = json.optString("workingDir").takeIf { it.isNotBlank() },
                enabled = json.optBoolean("enabled", true),
                isBuiltIn = json.optBoolean("isBuiltIn", false),
                projectId = json.optString("projectId").takeIf { it.isNotBlank() },
                transport = transport,
                installedAt = json.optLong("installedAt", System.currentTimeMillis()),
                version = json.optString("version", "1.0.0").takeIf { it.isNotBlank() } ?: "1.0.0",
                secretEnvNames = secretNames,
                remoteUrl = json.optString("remoteUrl").takeIf { it.isNotBlank() },
                authType = authType,
                credentialName = json.optString("credentialName").takeIf { it.isNotBlank() },
                apiKeyHeaderName = json.optString("apiKeyHeaderName", "X-API-Key")
            )
        }
    }
}

/**
 * Curated catalog template for discovery and one-click installation.
 */
data class McpCatalogTemplate(
    val id: String,
    val name: String,
    val description: String,
    val defaultCommand: String,
    val defaultArgs: List<String> = emptyList(),
    val requiredEnv: List<String> = emptyList(),
    val category: String = "Utility"
)

/**
 * Single source of truth for the built-in MCP server catalog. Referenced
 * by [McpRegistry.catalogTemplates] (Skills screen) and derived into
 * marketplace listings by the developer marketplace, so descriptions and
 * install commands cannot drift between the two surfaces.
 */
val McpBuiltInCatalogTemplates: List<McpCatalogTemplate> = listOf(
    // v3.2.38 (Part Q): every preset re-verified against the real MCP
    // ecosystem. Two were simply wrong: the official SQLite and Fetch
    // servers are PYTHON (PyPI mcp-server-sqlite / mcp-server-fetch, run
    // via uvx) — the @modelcontextprotocol/server-sqlite and
    // server-fetch npm names are not the official servers. Commands are
    // now program-only with flags in defaultArgs, so the spawn argv is
    // correct by construction.
    McpCatalogTemplate(
        id = "sqlite",
        name = "SQLite Server",
        description = "Interact with and query SQLite databases directly via MCP tools.",
        defaultCommand = "uvx",
        defaultArgs = listOf("mcp-server-sqlite", "--db-path", "workspace.db"),
        category = "Database"
    ),
    McpCatalogTemplate(
        id = "filesystem",
        name = "Filesystem Explorer",
        description = "Read, write, and inspect local project directories with secure boundaries.",
        defaultCommand = "npx",
        defaultArgs = listOf("-y", "@modelcontextprotocol/server-filesystem", "/workspace"),
        category = "Storage"
    ),
    McpCatalogTemplate(
        id = "github",
        name = "GitHub Platform",
        description = "Search repos, create issues, view pull requests, and commit files via GitHub API.",
        defaultCommand = "npx",
        defaultArgs = listOf("-y", "@modelcontextprotocol/server-github"),
        requiredEnv = listOf("GITHUB_PERSONAL_ACCESS_TOKEN"),
        category = "Development"
    ),
    McpCatalogTemplate(
        id = "postgres",
        name = "PostgreSQL Server",
        description = "Inspect database schemas and query tables. Edit the server's connection URL argument to point at your database before first use.",
        defaultCommand = "npx",
        defaultArgs = listOf("-y", "@modelcontextprotocol/server-postgres", "postgresql://localhost:5432/postgres"),
        category = "Database"
    ),
    McpCatalogTemplate(
        id = "fetch",
        name = "Fetch & Markdown",
        description = "Fetch web pages, extract content, and convert HTML to readable Markdown for agents.",
        defaultCommand = "uvx",
        defaultArgs = listOf("mcp-server-fetch"),
        category = "Web"
    ),
    McpCatalogTemplate(
        id = "puppeteer",
        name = "Puppeteer Automation",
        description = "Automate browser interactions and capture screenshots. First start downloads Chromium (large) and can take several minutes.",
        defaultCommand = "npx",
        defaultArgs = listOf("-y", "@modelcontextprotocol/server-puppeteer"),
        category = "Web Automation"
    ),
    McpCatalogTemplate(
        id = "memory",
        name = "Knowledge Graph Memory",
        description = "Persistent graph-based memory for entities, relationships, and context recall.",
        defaultCommand = "npx",
        defaultArgs = listOf("-y", "@modelcontextprotocol/server-memory"),
        category = "Agent Memory"
    ),
    McpCatalogTemplate(
        id = "brave-search",
        name = "Brave Search",
        // v3.2.40 (Scope C2): Brave's official MCP server moved to
        // Brave's own package, @brave/brave-search-mcp-server — the
        // @modelcontextprotocol/server-brave-search reference package
        // is retired. Installed entries pick this up on next launch
        // through the registry's template-refresh migration (Part Q).
        description = "Perform private web searches and retrieve up-to-date web answers. Requires a Brave API key — add it from the server's card after installing.",
        defaultCommand = "npx",
        defaultArgs = listOf("-y", "@brave/brave-search-mcp-server"),
        requiredEnv = listOf("BRAVE_API_KEY"),
        category = "Search"
    ),
    McpCatalogTemplate(
        id = "sequential-thinking",
        name = "Sequential Thinking",
        description = "Structured step-by-step reasoning and hypothesis refinement server.",
        defaultCommand = "npx",
        defaultArgs = listOf("-y", "@modelcontextprotocol/server-sequential-thinking"),
        category = "Reasoning"
    )
)
