package com.vayro.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.mcp.McpAuditEntry
import com.vayro.app.mcp.McpCatalogTemplate
import com.vayro.app.mcp.McpPermissionDecision
import com.vayro.app.mcp.McpServerInfo
import com.vayro.app.runtime.McpToolCallResult
import com.vayro.app.runtime.McpToolDefinition
import com.vayro.app.runtime.SkillInfo
import com.vayro.app.runtime.SkillScanReport
import com.vayro.app.runtime.SkillSearchResult
import com.vayro.app.ui.coordinators.McpToolApproval
import org.json.JSONObject

@Composable
private fun successGreen(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF2E7D32)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    initialTab: Int = 0,
    installed: List<SkillInfo>,
    results: List<SkillSearchResult>,
    busy: Boolean,
    message: String?,
    skillUpdates: Set<String> = emptySet(),
    onSearch: (String) -> Unit,
    onImportGitHub: (String) -> Unit,
    onScanGitHub: (String) -> Unit = {},
    scanReport: SkillScanReport? = null,
    scanBusy: Boolean = false,
    onInstallScanned: (Boolean) -> Unit = {},
    onDismissScan: () -> Unit = {},
    onImportZip: (Uri) -> Unit,
    onImportFolder: (Uri) -> Unit = {},
    onRemove: (String) -> Unit,
    onToggleSkill: (String, Boolean) -> Unit = { _, _ -> },
    onCheckUpdates: () -> Unit = {},
    onUpdate: (String) -> Unit = {},
    autoUpdateEnabled: Boolean = true,
    onToggleAutoUpdate: (Boolean) -> Unit = {},
    autoUpdateStatus: String? = null,
    mcpServers: List<McpServerInfo> = emptyList(),
    mcpCatalog: List<McpCatalogTemplate> = emptyList(),
    mcpTools: List<McpToolDefinition> = emptyList(),
    mcpAuditLogs: List<McpAuditEntry> = emptyList(),
    mcpBusy: Boolean = false,
    mcpMessage: String? = null,
    onInstallMcpTemplate: (String) -> Unit = {},
    onInstallCustomMcp: (McpServerInfo, Map<String, String>) -> Unit = { _, _ -> },
    onToggleMcpServer: (String, Boolean) -> Unit = { _, _ -> },
    onRemoveMcpServer: (String) -> Unit = {},
    onDiscoverMcpTools: (McpServerInfo) -> Unit = {},
    onClearMcpAuditLogs: () -> Unit = {},
    onCheckMcpToolPermission: (String, String) -> McpPermissionDecision =
        { _, _ -> McpPermissionDecision(allowed = false, requiresPrompt = false, reason = "") },
    onRunMcpTool: (McpServerInfo, String, String, McpToolApproval) -> Unit = { _, _, _, _ -> },
    mcpRunResult: McpToolCallResult? = null,
    mcpRunTarget: String? = null,
    mcpRunRunning: Boolean = false,
    onClearMcpRunResult: () -> Unit = {},
) {
    var selectedTab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab) }
    var query by rememberSaveable { mutableStateOf("") }
    var githubUrl by rememberSaveable { mutableStateOf("") }
    var showAddCustomMcpDialog by rememberSaveable { mutableStateOf(false) }
    var runDialogServer by remember { mutableStateOf<McpServerInfo?>(null) }
    var runDialogTool by remember { mutableStateOf<McpToolDefinition?>(null) }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportZip)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(onImportFolder)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (selectedTab == 0) "Skills Platform" else "MCP Platform",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (selectedTab == 0) "Reusable multi-folder agent skills" else "Model Context Protocol tools & servers",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        if (selectedTab == 1) {
                            IconButton(onClick = { showAddCustomMcpDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = "Add Custom MCP")
                            }
                        }
                    }
                )
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Agent Skills (${installed.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        icon = { Icon(Icons.Default.Extension, contentDescription = null, Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("MCP Servers (${mcpServers.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        icon = { Icon(Icons.Default.Terminal, contentDescription = null, Modifier.size(18.dp)) }
                    )
                }
            }
        },
    ) { padding ->
        if (selectedTab == 0) {
            // ==================== SKILLS TAB ====================
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                state = rememberSaveable(saver = LazyListState.Saver, key = "skills-tab-list-state") { LazyListState() },
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 1. Search GitHub Skills Card
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Search GitHub Skills", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(
                                        "Find and explore reusable capabilities from GitHub",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                                placeholder = { Text("e.g. android, react, devops, database…", fontSize = 12.sp) },
                            )
                            Button(
                                onClick = { onSearch(query) },
                                enabled = query.trim().length >= 2 && !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                } else {
                                    Icon(Icons.Default.Search, null, Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text("Search skills", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }
                }

                // 2. Direct Import (URL / ZIP) Card
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CloudDownload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Direct Import", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(
                                        "Scan a GitHub repo before installing, or import a local ZIP package or folder",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = githubUrl,
                                onValueChange = { githubUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp)) },
                                placeholder = { Text("https://github.com/owner/skill-repository", fontSize = 12.sp) },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        onScanGitHub(githubUrl)
                                        githubUrl = ""
                                    },
                                    enabled = githubUrl.startsWith("https://github.com/") && !busy && !scanBusy,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                ) {
                                    if (scanBusy) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    } else {
                                        Text("Scan URL", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                }
                                OutlinedButton(
                                    onClick = { zipLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                ) {
                                    Icon(Icons.Default.FileUpload, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Upload ZIP", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = { folderLauncher.launch(null) },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                ) {
                                    Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pick Folder", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                // 2b. Skill scan report (shown after scanning a GitHub repo, before install)
                if (scanReport != null) {
                    item {
                        SkillScanReportCard(
                            report = scanReport,
                            busy = busy,
                            onInstall = { onInstallScanned(false) },
                            onInstallRelease = { onInstallScanned(true) },
                            onDismiss = onDismissScan,
                        )
                    }
                }

                // 3. Auto-update Card
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Auto-update skills", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                                    Text("Automatically keep installed skills up to date", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = autoUpdateEnabled,
                                    onCheckedChange = onToggleAutoUpdate,
                                )
                            }
                            OutlinedButton(
                                onClick = onCheckUpdates,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text("Check updates", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                            message?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
                            autoUpdateStatus?.let {
                                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                if (installed.isNotEmpty()) {
                    item { Text("Installed Agent Skills (${installed.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items(installed, key = { it.name }) { skill ->
                        SkillCard(
                            skill = skill,
                            updateAvailable = skill.name in skillUpdates,
                            busy = busy,
                            onUpdate = { onUpdate(skill.name) },
                            onRemove = { onRemove(skill.name) },
                            onToggleEnabled = { onToggleSkill(skill.name, it) },
                        )
                    }
                }

                if (results.isNotEmpty()) {
                    item { Text("GitHub results", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items(results, key = { it.repositoryUrl }) { result ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(result.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(result.description, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Text(result.repositoryUrl, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Button(onClick = { onScanGitHub(result.repositoryUrl) }, enabled = !busy && !scanBusy) { Text("Scan") }
                            }
                        }
                    }
                }

                // Medium: no empty state — zero installed skills and zero
                // search results used to show a blank list. Not shown while
                // a search is running (results are still arriving).
                if (!busy && installed.isEmpty() && results.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (query.isNotBlank()) "No skills found for \"$query\"" else "No skills installed yet",
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = if (query.isNotBlank()) "Try different keywords, or import one directly below." else "Search GitHub above or import one directly.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        } else {
            // ==================== MCP PLATFORM TAB ====================
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                state = rememberSaveable(saver = LazyListState.Saver, key = "mcp-tab-list-state") { LazyListState() },
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // MCP Status Banner if busy
                if (mcpBusy || mcpMessage != null) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (mcpBusy) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(10.dp))
                                }
                                Text(
                                    mcpMessage ?: "Processing MCP request...",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                // 1. Installed MCP Servers
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Installed Servers (${mcpServers.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { showAddCustomMcpDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add Custom", fontSize = 12.sp)
                        }
                    }
                }

                if (mcpServers.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "No MCP servers installed yet. Choose a server from the catalog below or add a custom one.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(mcpServers, key = { it.id }) { server ->
                        McpServerCard(
                            server = server,
                            tools = mcpTools.filter { it.serverId == server.id },
                            busy = mcpBusy,
                            onToggle = { onToggleMcpServer(server.id, it) },
                            onDiscoverTools = { onDiscoverMcpTools(server) },
                            onRemove = { onRemoveMcpServer(server.id) },
                            onRunTool = { tool ->
                                runDialogServer = server
                                runDialogTool = tool
                            }
                        )
                    }
                }

                // 2. Discover MCP Catalog
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("MCP Catalog & Templates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "One-click install pre-configured Model Context Protocol servers",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                items(mcpCatalog, key = { it.id }) { template ->
                    val isInstalled = mcpServers.any { it.id == template.id }
                    McpCatalogCard(
                        template = template,
                        isInstalled = isInstalled,
                        busy = mcpBusy,
                        onInstall = { onInstallMcpTemplate(template.id) }
                    )
                }

                // 3. MCP Audit Trail
                if (mcpAuditLogs.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("MCP Execution Audit Trail", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            TextButton(onClick = onClearMcpAuditLogs) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(4.dp))
                                Text("Clear", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    items(mcpAuditLogs.take(20), key = { it.id }) { entry ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("${entry.serverId} → ${entry.toolName}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(
                                        if (entry.success) "SUCCESS (${entry.durationMs}ms)" else "ERROR",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (entry.success) successGreen() else MaterialTheme.colorScheme.error
                                    )
                                }
                                if (entry.argumentsJson != "{}") {
                                    Spacer(Modifier.height(2.dp))
                                    Text("Args: ${entry.argumentsJson}", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                if (entry.resultPreview.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text("Result: ${entry.resultPreview}", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                if (entry.error != null) {
                                    Spacer(Modifier.height(2.dp))
                                    Text("Error: ${entry.error}", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddCustomMcpDialog) {
        AddCustomMcpDialog(
            onDismiss = { showAddCustomMcpDialog = false },
            onAdd = { customServer, secrets ->
                onInstallCustomMcp(customServer, secrets)
                showAddCustomMcpDialog = false
            }
        )
    }

    val pendingServer = runDialogServer
    val pendingTool = runDialogTool
    if (pendingServer != null && pendingTool != null) {
        McpRunToolDialog(
            server = pendingServer,
            tool = pendingTool,
            onDismiss = {
                runDialogServer = null
                runDialogTool = null
            },
            onCheckPermission = { onCheckMcpToolPermission(pendingServer.id, pendingTool.name) },
            onConfirm = { argsJson, approval ->
                onRunMcpTool(pendingServer, pendingTool.name, argsJson, approval)
                runDialogServer = null
                runDialogTool = null
            }
        )
    }

    if (mcpRunRunning || mcpRunResult != null) {
        AlertDialog(
            onDismissRequest = { if (!mcpRunRunning) onClearMcpRunResult() },
            title = { Text(mcpRunTarget ?: "Tool result") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (mcpRunRunning) {
                        Text("Running...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val result = mcpRunResult
                        if (result != null) {
                            if (result.success) {
                                Text(
                                    result.content.ifBlank { "Done." },
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                Text(
                                    "Error: ${result.error ?: "unknown"}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Text(
                                "Finished in ${result.durationMs}ms",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onClearMcpRunResult, enabled = !mcpRunRunning) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun McpServerCard(
    server: McpServerInfo,
    tools: List<McpToolDefinition>,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onDiscoverTools: () -> Unit,
    onRemove: () -> Unit,
    onRunTool: (McpToolDefinition) -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(server.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (server.enabled) successGreen().copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                if (server.enabled) "ACTIVE" else "DISABLED",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (server.enabled) successGreen() else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (server.description.isNotBlank()) {
                        Text(server.description, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Text("Command: ${server.command} ${server.args.joinToString(" ")}", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onDiscoverTools,
                    enabled = !busy && server.enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Discover Tools", fontSize = 11.sp)
                }

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }

            if (tools.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Available Tools (${tools.size})", fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                        tools.forEach { tool ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("• ${tool.name}", fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                                if (tool.description.isNotBlank()) {
                                    Text(" — ${tool.description}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                                TextButton(
                                    onClick = { onRunTool(tool) },
                                    enabled = !busy && server.enabled,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("Run", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpRunToolDialog(
    server: McpServerInfo,
    tool: McpToolDefinition,
    onDismiss: () -> Unit,
    onCheckPermission: () -> McpPermissionDecision,
    onConfirm: (String, McpToolApproval) -> Unit
) {
    var argsText by remember { mutableStateOf("{\n\n}") }
    var confirming by remember { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf<String?>(null) }

    fun tryRun(approval: McpToolApproval) {
        if (runCatching { JSONObject(argsText) }.isFailure) {
            permissionError = "Arguments must be valid JSON."
            return
        }
        val decision = onCheckPermission()
        if (!decision.allowed && !decision.requiresPrompt) {
            permissionError = "Tool not allowed: ${decision.reason}"
            return
        }
        if (decision.requiresPrompt && !confirming && approval == McpToolApproval.ONE_TIME) {
            confirming = true
            return
        }
        onConfirm(argsText, approval)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirming) "Confirm tool run" else "Run ${tool.name}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (confirming) {
                    Text(
                        "This tool requires your confirmation before it can run on ${server.name}. " +
                            "Review the arguments, then choose how to approve it.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        argsText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (tool.description.isNotBlank()) {
                        Text(tool.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = argsText,
                        onValueChange = { argsText = it; permissionError = null },
                        label = { Text("Arguments (JSON)") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        shape = RoundedCornerShape(10.dp),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    )
                }
                if (permissionError != null) {
                    Text(permissionError!!, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (confirming) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { confirming = false }) { Text("Back") }
                    Button(onClick = { tryRun(McpToolApproval.ONE_TIME) }) { Text("Allow once") }
                    Button(onClick = { tryRun(McpToolApproval.ALWAYS_ALLOW) }) { Text("Always allow") }
                }
            } else {
                Button(onClick = { tryRun(McpToolApproval.ONE_TIME) }) { Text("Run") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun McpCatalogCard(
    template: McpCatalogTemplate,
    isInstalled: Boolean,
    busy: Boolean,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(template.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    ) {
                        Text(
                            template.category,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(template.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Text(template.defaultCommand, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
            }
            Spacer(Modifier.width(8.dp))
            if (isInstalled) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = successGreen().copy(alpha = 0.15f)
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = successGreen(), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Installed", fontSize = 11.sp, color = successGreen(), fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Button(
                    onClick = onInstall,
                    enabled = !busy,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text("Install", fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * Parses a `KEY=VAL, KEY2=VAL2` list into a map. Entries without `=` are
 * ignored; later entries win on duplicate keys. Pure, unit-tested.
 */
internal fun parseKeyValueList(text: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    text.split(",").map { it.trim() }.filter { it.contains("=") }.forEach {
        val parts = it.split("=", limit = 2)
        val key = parts[0].trim()
        if (key.isNotEmpty()) map[key] = parts[1].trim()
    }
    return map
}

@Composable
private fun AddCustomMcpDialog(
    onDismiss: () -> Unit,
    onAdd: (McpServerInfo, Map<String, String>) -> Unit
) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var argsText by remember { mutableStateOf("") }
    var envText by remember { mutableStateOf("") }
    var secretsText by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom MCP Server", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                // Medium: the dialog already scrolled; it also lifts above
                // the keyboard now so the lowest field stays reachable.
                modifier = Modifier.verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Warning: this command will run on your device with the app's full privileges. Only add servers from sources you trust.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("Server ID (e.g. my-server)", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Executable Command (e.g. node, python, npx)", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = argsText,
                    onValueChange = { argsText = it },
                    label = { Text("Arguments (space-separated)", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = envText,
                    onValueChange = { envText = it },
                    label = { Text("Environment (KEY=VAL, comma-separated, non-secret only)", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = secretsText,
                    onValueChange = { secretsText = it },
                    label = { Text("Secrets (KEY=VAL, comma-separated, stored encrypted)", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Secrets are stored encrypted in the credential store, never in the server config. Do not put secrets in Environment above.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                    )
                    Text(
                        "I understand this command runs with the app's full privileges.",
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val envMap = parseKeyValueList(envText)
                    val secretsMap = parseKeyValueList(secretsText)
                    val argsList = argsText.split(" ").map { it.trim() }.filter { it.isNotBlank() }
                    val serverId = id.ifBlank { "custom-" + System.currentTimeMillis() }
                    onAdd(
                        McpServerInfo(
                            id = serverId,
                            name = name.ifBlank { id },
                            command = command,
                            args = argsList,
                            env = envMap,
                            secretEnvNames = secretsMap.keys.toList(),
                            enabled = true,
                            isBuiltIn = false
                        ),
                        secretsMap,
                    )
                },
                enabled = command.isNotBlank() && acknowledged
            ) {
                Text("Add Server")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SkillCard(
    skill: SkillInfo,
    updateAvailable: Boolean,
    busy: Boolean,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(14.dp)
                .alpha(if (skill.enabled) 1f else 0.45f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    skill.name,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Switch(
                    checked = skill.enabled,
                    onCheckedChange = onToggleEnabled,
                    enabled = !busy,
                )
            }
            Text(skill.description, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            Text(skill.source, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            if (!skill.source.startsWith("https://github.com/", ignoreCase = true) && skill.source != "built-in") {
                Text(
                    "Manual — installed locally, no update source",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (updateAvailable && skill.source.startsWith("https://github.com/", ignoreCase = true)) {
                    TextButton(onClick = onUpdate, enabled = !busy) { Text("Update", fontSize = 12.sp) }
                }
                IconButton(onClick = onRemove, enabled = !busy, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove skill", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun SkillScanReportCard(
    report: SkillScanReport,
    busy: Boolean,
    onInstall: () -> Unit,
    onInstallRelease: () -> Unit,
    onDismiss: () -> Unit,
) {
    val verdictColor = if (report.isSkill) successGreen() else MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, verdictColor.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (report.isSkill) Icons.Default.CheckCircle else Icons.Default.Warning,
                    null,
                    tint = verdictColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Scan report", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        if (report.isSkill) "Real skill: ${report.skills.size} skill(s) found" else "Not a skill repository",
                        fontSize = 11.sp,
                        color = verdictColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss scan", modifier = Modifier.size(16.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(report.repoName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (report.repoDescription.isNotBlank()) {
                    Text(report.repoDescription, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "Branch ${report.defaultBranch} · ${report.stars} stars",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (report.skills.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Skills found", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    report.skills.forEach { skill ->
                        Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("• ${skill.name}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(skill.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            report.release?.let { release ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Latest release", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text(
                        "${release.tag} (${release.name})" + if (release.newerThanBranch) " · newer than ${report.defaultBranch}" else " · same as ${report.defaultBranch}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (report.suggestions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Suggested improvements", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    report.suggestions.forEach { suggestion ->
                        Text("• $suggestion", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (report.warnings.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    report.warnings.forEach { warning ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(warning, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (report.isSkill) {
                    Button(
                        onClick = onInstall,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Install", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    val release = report.release
                    if (release != null && release.newerThanBranch) {
                        OutlinedButton(
                            onClick = onInstallRelease,
                            enabled = !busy,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Install ${release.tag}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onInstall,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Install anyway", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}
