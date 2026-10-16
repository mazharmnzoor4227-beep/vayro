package com.vayro.app.runtime

/**
 * MCP tool shapes shared by the UI state and the MCP coordinator.
 *
 * The live MCP implementation is [com.vayro.app.mcp.McpToolRegistry];
 * these types exist because the UI state and the coordinator's public API
 * are expressed in them.
 */
data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchemaJson: String = "{}",
    val serverId: String? = null
)

data class McpToolCallResult(
    val success: Boolean,
    val content: String,
    val error: String? = null,
    val durationMs: Long = 0L
)
