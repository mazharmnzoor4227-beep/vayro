package com.vayro.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import com.vayro.app.agent.team.AgentTeamOrchestrator
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.R
import com.vayro.app.data.ApiKeyInfo
import com.vayro.app.data.GEMINI_API_MODELS
import com.vayro.app.model.AgentKind
import com.vayro.app.model.DSH_PROTOCOL_PROVIDERS
import com.vayro.app.model.ProviderKind
import com.vayro.app.model.ProviderProfile
import com.vayro.app.model.defaultDshApiForProvider
import com.vayro.app.model.inferredDshApiForUrl
import com.vayro.app.model.providersForAgent
import com.vayro.app.network.ConnectionValidation
import com.vayro.app.network.DiscoveredModel
import com.vayro.app.network.ModelDiscoveryResult
import com.vayro.app.runtime.AntigravityAuthStatus
import com.vayro.app.ui.theme.PocketBlue
import com.vayro.app.ui.theme.PocketOrange
import com.vayro.app.ui.theme.PocketGreen
import kotlinx.coroutines.launch

private data class KeyConnectionStatus(
    val message: String,
    val successful: Boolean? = null,
    val providerMessage: String? = null,
    val label: String = if (successful == true) "Verified" else "Failed",
)

/** Formats Antigravity model identifiers into clean, human-friendly names. */
internal fun formatAntigravityModelName(id: String): String = when (id) {
    "gemini-3.8-flash-high" -> "Gemini 3.8 Flash (High)"
    "gemini-3.8-flash-medium" -> "Gemini 3.8 Flash"
    "gemini-3.8-flash-low" -> "Gemini 3.8 Flash (Low)"
    "gemini-3.6-flash-high" -> "Gemini 3.6 Flash (High)"
    "gemini-3.6-flash-medium" -> "Gemini 3.6 Flash"
    "gemini-3.6-flash-low" -> "Gemini 3.6 Flash (Low)"
    "gemini-3.1-pro-high" -> "Gemini 3.1 Pro (High)"
    "gemini-3.1-pro-low" -> "Gemini 3.1 Pro (Low)"
    "claude-sonnet-4-6" -> "Claude Sonnet 4.6"
    "claude-opus-4-6-thinking" -> "Claude Opus 4.6 (Thinking)"
    "gpt-oss-120b-medium" -> "GPT-OSS 120B"
    else -> id.split("-").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
    }
}

/** Returns tier classification badges for Antigravity models. */
internal fun formatAntigravityModelTier(id: String): String = when {
    id.contains("3.8") -> "Recommended"
    id.contains("3.6") -> "Stable"
    id.contains("3.1-pro") -> "Pro Reasoning"
    id.contains("claude") -> "Anthropic"
    id.contains("gpt") -> "Open Source"
    else -> ""
}

/**
 * Dedicated Agent + AI connection hub.
 *
 * Replaces the old dashboard Terminal tab. Terminal is now a FAB
 * on the project/workspace screen; this screen owns:
 *  1. Status hero (active agent + model + connectivity)
 *  2. Coding agent picker + installs + updates
 *  3. AI connection (Antigravity OAuth OR provider + model + API keys)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    state: AppUiState,
    onSaveProvider: (ProviderProfile, String) -> Unit,
    onDiscoverModels: suspend (ProviderProfile, String) -> ModelDiscoveryResult,
    onValidateProvider: suspend (ProviderProfile, String, List<DiscoveredModel>) -> ConnectionValidation,
    // v3.2.38 (L/J): effective catalog profile per kind + persistence for
    // the endpoint Test & Connect validated.
    catalogChatDefaults: (ProviderKind) -> ProviderProfile = { ProviderProfile(it) },
    onChatEndpointCorrected: (ProviderKind, String, String) -> Unit = { _, _, _ -> },
    onPing: () -> Unit,
    // v3.2.52: persistent last-known connection state per agent — one
    // quiet line under each Test button, plus the result callback the
    // provider card's Test & Connect reports through.
    agentLastCheckLine: (AgentKind) -> String? = { null },
    onAgentTestResult: (AgentKind, Boolean, String?) -> Unit = { _, _, _ -> },
    getSavedApiKey: (ProviderKind) -> String,
    getSavedApiKeys: (ProviderKind) -> List<ApiKeyInfo>,
    onAddApiKey: (ProviderKind, String, String) -> List<ApiKeyInfo>,
    onActivateApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onRemoveApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onSelectAgent: (AgentKind) -> Unit = {},
    onInstallAgent: (AgentKind) -> Unit = {},
    onCancelAgentInstall: () -> Unit = {},
    onCheckAgentUpdates: () -> Unit = {},
    onUpdateAgent: (AgentKind) -> Unit = {},
    onCancelAgentUpdate: (AgentKind) -> Unit = {},
    onRefreshInstalledAgents: () -> Unit = {},
    onStartAntigravityLogin: () -> Unit = {},
    onSubmitAntigravityCode: (String) -> Unit = {},
    onLogoutAntigravity: () -> Unit = {},
    onRefreshAntigravityModels: () -> Unit = {},
    onSetAntigravityModel: (String) -> Unit = {},
    onSetAntigravityEffort: (String) -> Unit = {},
    onStartAntigravityAddAccount: () -> Unit = {},
    onSwitchAntigravityAccount: (String) -> Unit = {},
    onRemoveAntigravityAccount: (String) -> Unit = {},
    onToggleAntigravityAutoRoundRobin: (Boolean) -> Unit = {},
    onToggleGeminiApiMode: (Boolean) -> Unit = {},
    // v3.2.37 (Parts C + F2): API-key catalog wiring.
    chatProviderKind: ProviderKind? = null,
    onSelectChatProvider: (ProviderKind) -> Unit = {},
    autoSwitchFor: (ProviderKind) -> Boolean = { true },
    onToggleAutoSwitch: (ProviderKind, Boolean) -> Unit = { _, _ -> },
    keyExhaustedAt: (ProviderKind, String) -> Long? = { _, _ -> null },
    onSaveGeminiApiKey: (String) -> Unit = {},
    onClearGeminiApiKey: () -> Unit = {},
    onSetGeminiApiModel: (String) -> Unit = {},
    teamOrchestrator: AgentTeamOrchestrator? = null,
    // v3.2.39 (Scope 2): hoisted catalog scroll state (host-owned). The
    // catalog list used to get a fresh default state on every visit, so
    // it always opened at the top.
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    LaunchedEffect(Unit) { onRefreshInstalledAgents() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showTeamDialog by rememberSaveable { mutableStateOf(false) }
    // NO silent fallback: if the caller did not wire the real orchestrator
    // (RealTeamAgentExecutor), the Team Studio must not open. It stays closed
    // and the button below reports "not configured" instead.
    val teamOrchestratorResolved = teamOrchestrator
    var selectedKind by rememberSaveable(state.provider.kind) { mutableStateOf(state.provider.kind) }
    var baseUrl by rememberSaveable(state.provider.baseUrl) { mutableStateOf(state.provider.baseUrl) }
    var model by rememberSaveable(state.provider.model) { mutableStateOf(state.provider.model) }
    var dshApi by rememberSaveable(state.provider.dshApi) { mutableStateOf(state.provider.dshApi) }
    var openRouterProviderOrder by rememberSaveable(state.provider.openRouterProviderOrder) {
        mutableStateOf(state.provider.openRouterProviderOrder)
    }
    var openRouterAllowFallbacks by rememberSaveable(state.provider.openRouterAllowFallbacks) {
        mutableStateOf(state.provider.openRouterAllowFallbacks)
    }
    var apiKey by remember(selectedKind) { mutableStateOf(getSavedApiKey(selectedKind)) }
    var savedKeys by remember(selectedKind, state.activeApiKeyName) {
        mutableStateOf(getSavedApiKeys(selectedKind))
    }
    var newKeyName by rememberSaveable(selectedKind) { mutableStateOf("") }
    var newApiKey by remember(selectedKind) { mutableStateOf("") }
    var newKeyVisible by rememberSaveable(selectedKind, savedKeys.isEmpty()) { mutableStateOf(savedKeys.isEmpty()) }
    var models by remember(selectedKind, baseUrl) { mutableStateOf(emptyList<DiscoveredModel>()) }
    var modelSearch by rememberSaveable(selectedKind) { mutableStateOf("") }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var isDiscovering by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }
    var statusProviderMessage by remember { mutableStateOf<String?>(null) }
    var keyConnectionStatuses by remember(selectedKind) {
        mutableStateOf<Map<String, KeyConnectionStatus>>(emptyMap())
    }
    // Antigravity model sheet state
    var showAntigravityModelSheet by rememberSaveable { mutableStateOf(false) }
    var antigravitySearch by rememberSaveable { mutableStateOf("") }
    var antigravityCode by rememberSaveable { mutableStateOf("") }
    var viewedAgent by rememberSaveable { mutableStateOf(state.agentKind) }

    val orderedAgents = remember(state.primaryAgentKind, state.freeModeEnabled) {
        val primary = state.primaryAgentKind
        buildList {
            add(AgentKind.CUSTOM)
            if (primary != AgentKind.CUSTOM) add(primary)
            AgentKind.entries.forEach { if (it != AgentKind.CUSTOM && it != primary) add(it) }
        }.filter { !state.freeModeEnabled || it in com.vayro.app.model.FREE_MODE_AGENTS }
    }
    val viewedAgentInstalled = viewedAgent == AgentKind.CUSTOM ||
        viewedAgent == state.agentKind ||
        state.installedAgentVersions.containsKey(viewedAgent)

    val providerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val antigravitySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filteredModels = remember(models, modelSearch) {
        val q = modelSearch.trim()
        if (q.isBlank()) models else models.filter {
            it.id.contains(q, true) || it.displayName.contains(q, true)
        }
    }

    val antigravityModelList = remember(state.antigravityModels) {
        if (state.antigravityModels.isNotEmpty()) state.antigravityModels
        else listOf(
            "gemini-3.8-flash-high",
            "gemini-3.8-flash-medium",
            "gemini-3.6-flash-high",
            "gemini-3.6-flash-medium",
            "gemini-3.6-flash-low",
            "gemini-3.1-pro-high",
            "gemini-3.1-pro-low",
            "claude-sonnet-4-6",
            "claude-opus-4-6-thinking",
            "gpt-oss-120b-medium",
        )
    }

    val filteredAntigravityModels = remember(antigravityModelList, antigravitySearch) {
        val q = antigravitySearch.trim()
        if (q.isBlank()) antigravityModelList
        else antigravityModelList.filter {
            it.contains(q, true) || formatAntigravityModelName(it).contains(q, true)
        }
    }

    fun discoverModels() {
        val effectiveKey = apiKey.trim().ifBlank { newApiKey.trim() }
        val supportsPublicDiscovery = selectedKind == ProviderKind.LLM_ROUTER ||
            selectedKind == ProviderKind.OPENCODE_ZEN
        if (effectiveKey.isBlank() && !supportsPublicDiscovery) {
            status = "Please enter or save an API key first to discover models."
            statusOk = false
            statusProviderMessage = null
            newKeyVisible = true
            showModels = true
            return
        }
        scope.launch {
            isDiscovering = true
            status = "Discovering models from ${selectedKind.title}…"
            statusOk = true
            statusProviderMessage = null
            val kind = selectedKind
            val url = if (kind.fixedBaseUrl) kind.defaultBaseUrl else baseUrl.trim()
            val profile = ProviderProfile(
                kind,
                url,
                model.trim(),
                dshApi = dshApi,
                openRouterProviderOrder = openRouterProviderOrder,
                openRouterAllowFallbacks = openRouterAllowFallbacks,
            )
            when (val result = onDiscoverModels(profile, effectiveKey)) {
                is ModelDiscoveryResult.Success -> {
                    models = result.models
                    if (apiKey.isBlank() && newApiKey.isNotBlank()) {
                        val keyName = newKeyName.trim().ifBlank { "${kind.title} Key" }
                        savedKeys = onAddApiKey(kind, keyName, newApiKey.trim())
                        apiKey = getSavedApiKey(kind)
                        newKeyName = ""
                        newApiKey = ""
                        newKeyVisible = false
                    }
                    status = "Discovered ${result.models.size} models from ${selectedKind.title}."
                    statusOk = true
                    statusProviderMessage = null
                    showModels = true
                }
                is ModelDiscoveryResult.Failure -> {
                    status = result.message
                    statusOk = false
                    statusProviderMessage = result.providerMessage
                }
            }
            isDiscovering = false
        }
    }

    // ── Live connection status calculations ──
    val isAntigravity = state.agentKind == AgentKind.ANTIGRAVITY
    val antigravityTesting = isAntigravity && state.apiPingStatus == ApiPingStatus.PINGING
    val antigravityHelloFailed = isAntigravity && state.apiPingStatus == ApiPingStatus.FAILED

    val (dot, label, pillBg) = if (isAntigravity) {
        when {
            antigravityTesting -> Triple(PocketOrange, "Testing…", PocketOrange.copy(alpha = 0.13f))
            state.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN || antigravityHelloFailed ->
                Triple(MaterialTheme.colorScheme.error, "Attention", MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            else -> Triple(PocketGreen, "Online", PocketGreen.copy(alpha = 0.13f))
        }
    } else {
        when (state.apiPingStatus) {
            ApiPingStatus.OK -> Triple(PocketGreen, "Online", PocketGreen.copy(alpha = 0.13f))
            ApiPingStatus.FAILED -> Triple(MaterialTheme.colorScheme.error, "Attention", MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            ApiPingStatus.PINGING -> Triple(PocketOrange, "Testing…", PocketOrange.copy(alpha = 0.13f))
            ApiPingStatus.IDLE -> Triple(MaterialTheme.colorScheme.onSurfaceVariant, "Not tested", MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        }
    }

    // ── Antigravity Model Modal Bottom Sheet ──
    if (showAntigravityModelSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAntigravityModelSheet = false },
            sheetState = antigravitySheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SheetWindowImeGuard()
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(horizontal = 20.dp)
                    .imePadding()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Select Model", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "${filteredAntigravityModels.size} available for Antigravity",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onRefreshAntigravityModels, enabled = !state.antigravityModelsLoading) {
                        if (state.antigravityModelsLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "Refresh models")
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = antigravitySearch,
                    onValueChange = { antigravitySearch = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search model or series") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(12.dp))

                if (filteredAntigravityModels.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No matching models found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filteredAntigravityModels, key = { it }) { modelId ->
                            val isSelected = state.antigravityModel == modelId
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) PocketOrange.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) PocketOrange.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSetAntigravityModel(modelId)
                                        showAntigravityModelSheet = false
                                    },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                formatAntigravityModelName(modelId),
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                color = if (isSelected) PocketOrange else MaterialTheme.colorScheme.onSurface,
                                            )
                                            val tier = formatAntigravityModelTier(modelId)
                                            if (tier.isNotEmpty()) {
                                                Spacer(Modifier.width(8.dp))
                                                Surface(
                                                    color = if (isSelected) PocketOrange.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = RoundedCornerShape(4.dp),
                                                ) {
                                                    Text(
                                                        tier,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            modelId,
                                            fontSize = 11.sp,
                                            fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    AgentSelectionDot(selected = isSelected)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Provider Models Modal Bottom Sheet ──
    if (showModels) {
        ModalBottomSheet(
            onDismissRequest = { showModels = false },
            sheetState = providerSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SheetWindowImeGuard()
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(horizontal = 20.dp)
                    .imePadding()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Available Models", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            if (models.isEmpty()) selectedKind.title else "${filteredModels.size} of ${models.size}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = ::discoverModels, enabled = !isDiscovering) {
                        if (isDiscovering) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = PocketOrange)
                        } else {
                            Icon(Icons.Default.Refresh, "Refresh models")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = modelSearch,
                    onValueChange = { modelSearch = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search model or type custom ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(12.dp))

                if (status != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (statusOk) PocketOrange.copy(alpha = 0.09f)
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        border = BorderStroke(
                            1.dp,
                            if (statusOk) PocketOrange.copy(alpha = 0.28f)
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isDiscovering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 1.6.dp,
                                    color = PocketOrange,
                                )
                            } else {
                                Icon(
                                    if (statusOk) Icons.Default.Info else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (statusOk) PocketOrange else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                status.orEmpty(),
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = if (statusOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            }
                            statusProviderMessage?.let { providerMessage ->
                                Text(
                                    "Provider: $providerMessage",
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 23.dp, top = 5.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (isDiscovering) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(32.dp), color = PocketOrange, strokeWidth = 3.dp)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "Discovering models from ${selectedKind.title}…",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (filteredModels.isEmpty()) {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (modelSearch.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = PocketOrange.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        model = modelSearch.trim()
                                        modelSearch = ""
                                        showModels = false
                                    },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Check, null, tint = PocketOrange, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Use custom model ID:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(modelSearch.trim(), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = PocketOrange)
                                    }
                                }
                            }
                        }


                        Button(
                            onClick = ::discoverModels,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Discover Models from API")
                        }

                        val recommended = remember(selectedKind) { defaultModelsForProvider(selectedKind) }
                        if (recommended.isNotEmpty()) {
                            Text(
                                "Recommended Models",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            recommended.forEach { opt ->
                                val isSelected = model == opt.id
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isSelected) PocketOrange.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) PocketOrange.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            model = opt.id
                                            modelSearch = ""
                                            showModels = false
                                        },
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                opt.displayName,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                color = if (isSelected) PocketOrange else MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                opt.id,
                                                fontSize = 11.sp,
                                                fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        AgentSelectionDot(selected = isSelected)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (modelSearch.isNotBlank() && filteredModels.none { it.id.equals(modelSearch.trim(), ignoreCase = true) }) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = PocketOrange.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            model = modelSearch.trim()
                                            modelSearch = ""
                                            showModels = false
                                        },
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.Check, null, tint = PocketOrange, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Use custom model ID:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(modelSearch.trim(), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = PocketOrange)
                                        }
                                    }
                                }
                            }
                        }
                        items(filteredModels, key = { it.id }) { option ->
                            val isSelected = model == option.id
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) PocketOrange.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) PocketOrange.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        model = option.id
                                        modelSearch = ""
                                        showModels = false
                                    },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                option.displayName,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                // Medium: raw PocketOrange text is
                                                // low-contrast in light theme; the
                                                // theme primary is the same hue,
                                                // darker in light mode.
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (option.isFree) {
                                                Spacer(Modifier.width(6.dp))
                                                // Medium: hardcoded mint was low-contrast
                                                // on light surfaces; theme tertiary is
                                                // the same hue, darker in light mode.
                                                Text("FREE", color = MaterialTheme.colorScheme.tertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        if (option.displayName != option.id) {
                                            Text(
                                                option.id,
                                                fontSize = 11.sp,
                                                fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    AgentSelectionDot(selected = isSelected)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(top = 4.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                            modifier = Modifier.size(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.SmartToy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy((-1).dp),
                        ) {
                            Text(
                                "AI Agent",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                lineHeight = 21.sp,
                            )
                            Text(
                                if (isAntigravity) {
                                    "Antigravity · ${formatAntigravityModelName(state.antigravityModel)}"
                                } else {
                                    "${state.agentKind.title} · ${model.ifBlank { selectedKind.title }}"
                                },
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    // Top Bar Live Status Pill
                    Surface(
                        color = pillBg,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, dot.copy(alpha = 0.35f)),
                    ) {
                        Box(
                            Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(7.dp).background(dot, CircleShape))
                        }
                    }
                    IconButton(onClick = {
                        if (teamOrchestratorResolved != null) {
                            showTeamDialog = true
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                "Team execution is not configured in this build.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }) {
                        Icon(
                            Icons.Default.Groups,
                            contentDescription = "Multi-Agent Engineering Team",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 1. Compact 3-Way Segmented Engine Selector ──
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // Square icon tabs, equal spacing: one 56.dp square
                        // tile per agent with its official brand icon (Custom
                        // keeps its word). The selected tab gets a white tile
                        // behind its icon; unselected tabs are transparent.
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            orderedAgents.forEach { agent ->
                                val isSelected = viewedAgent == agent
                                // Custom is bring-your-own-API: always available, no install needed.
                                val isInstalled = agent == AgentKind.CUSTOM ||
                                    agent == state.agentKind || state.installedAgentVersions.containsKey(agent)
                                val updateAvailable = state.agentUpdates.containsKey(agent)
                                val isHeroTab = agent == AgentKind.CUSTOM
                                val tabIconRes = when (agent) {
                                    AgentKind.ANTIGRAVITY -> R.drawable.ic_agent_antigravity
                                    AgentKind.CLAUDE_CODE -> R.drawable.ic_agent_claude
                                    AgentKind.CODEX -> R.drawable.ic_agent_codex
                                    AgentKind.DEEPSEEK_HARNESS -> R.drawable.ic_agent_deepseek
                                    AgentKind.CUSTOM -> null
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                                    border = if (isHeroTab && !isSelected) BorderStroke(1.dp, PocketOrange.copy(alpha = 0.6f)) else null,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .alpha(if (state.agentInstalling == null) 1f else 0.4f)
                                        .clickable(enabled = state.agentInstalling == null) {
                                            viewedAgent = agent
                                            if (isInstalled) onSelectAgent(agent)
                                        },
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (tabIconRes != null) {
                                            Image(
                                                painter = painterResource(tabIconRes),
                                                contentDescription = agent.title,
                                                modifier = Modifier.size(32.dp),
                                            )
                                        } else {
                                            // Custom keeps its word, first in tab order.
                                            // The hero word is orange; on the white
                                            // selected tile it stays readable in
                                            // either theme.
                                            Text(
                                                "Your API",
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                fontSize = 11.sp,
                                                color = if (isHeroTab) PocketOrange else if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                        if (updateAvailable) {
                                            Box(
                                                Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(top = 4.dp, end = 4.dp)
                                                    .size(6.dp)
                                                    .background(PocketOrange, CircleShape)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Medium: progress used to render only when the
                    // installing agent was also the viewed tab, so it
                    // vanished whenever the viewed tab differed.
                    if (state.agentInstalling != null) {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        state.agentMessage ?: "Installing ${state.agentInstalling?.title} binary…",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { state.agentProgress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                val downloaded = state.agentDownloadedBytes
                                val total = state.agentTotalBytes
                                Spacer(Modifier.height(6.dp))
                                Row(Modifier.fillMaxWidth()) {
                                    Text(
                                        if (downloaded != null || total != null) {
                                            buildString {
                                                append(formatAgentBytes(downloaded ?: 0L))
                                                total?.let { append(" / ${formatAgentBytes(it)}") }
                                            }
                                        } else {
                                            "Processing files"
                                        },
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        buildString {
                                            append("${(state.agentProgress * 100).toInt()}%")
                                            state.agentBytesPerSecond?.takeIf { it > 0L }?.let {
                                                append(" · ${formatAgentBytes(it)}/s")
                                            }
                                        },
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = onCancelAgentInstall) {
                                        Text("Cancel install", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    } else if (!viewedAgentInstalled) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "${viewedAgent.title} is not installed",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Install its ${viewedAgent.downloadNote} agent package to use it with your existing projects.",
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = { onInstallAgent(viewedAgent) },
                                    enabled = state.agentInstalling == null,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text("Install ${viewedAgent.title}", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // ── 2. Primary Configuration Card (Antigravity OR Custom OR Provider) ──
            item {
                // Cards are keyed on the VIEWED tab, not the active agent. The old
                // gating (viewedAgent == state.agentKind) hid the Antigravity
                // multi-account card whenever another agent was active, which
                // looked like the feature had been removed. It was never removed.
                if (viewedAgent == AgentKind.ANTIGRAVITY && viewedAgentInstalled) {
                    AgentAntigravityCard(
                        state = state,
                        code = antigravityCode,
                        onCode = { antigravityCode = it },
                        onStartLogin = onStartAntigravityLogin,
                        onSubmitCode = { onSubmitAntigravityCode(antigravityCode); antigravityCode = "" },
                        onLogout = onLogoutAntigravity,
                        onRefreshModels = onRefreshAntigravityModels,
                        onOpenModelSheet = { showAntigravityModelSheet = true },
                        onSetEffort = onSetAntigravityEffort,
                        onTest = onPing,
                        lastCheckLine = agentLastCheckLine(AgentKind.ANTIGRAVITY),
                        onStartAddAccount = onStartAntigravityAddAccount,
                        onSwitchAccount = onSwitchAntigravityAccount,
                        onRemoveAccount = onRemoveAntigravityAccount,
                        onToggleAutoRoundRobin = onToggleAntigravityAutoRoundRobin,
                        onToggleGeminiApiMode = onToggleGeminiApiMode,
                        onSaveGeminiApiKey = onSaveGeminiApiKey,
                        onClearGeminiApiKey = onClearGeminiApiKey,
                        onSetGeminiApiModel = onSetGeminiApiModel,
                    )
                } else if (viewedAgent == AgentKind.CODEX && viewedAgentInstalled) {
                    AgentCodexCard(
                        state = state,
                        onSaveProvider = onSaveProvider,
                        getSavedApiKey = getSavedApiKey,
                    )
                } else if (viewedAgent == AgentKind.CUSTOM) {
                    AgentCustomCard(
                        state = state,
                        onSaveProvider = onSaveProvider,
                        getSavedApiKey = getSavedApiKey,
                    )
                } else if (viewedAgent != state.agentKind) {
                    // Viewing an installed but inactive agent: its provider card
                    // belongs to the active agent, so offer to make this one
                    // active instead of showing a blank card. (If it is not
                    // installed, the install card above already covers it.)
                    if (viewedAgentInstalled) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "${viewedAgent.title} is installed",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Make it your active agent to configure it here.",
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = { onSelectAgent(viewedAgent) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text("Make ${viewedAgent.title} active", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    AgentProviderCard(
                        state = state,
                        lastCheckLine = agentLastCheckLine(state.agentKind),
                        selectedKind = selectedKind,
                        baseUrl = baseUrl,
                        model = model,
                        dshApi = dshApi,
                        openRouterProviderOrder = openRouterProviderOrder,
                        openRouterAllowFallbacks = openRouterAllowFallbacks,
                        apiKey = apiKey,
                        models = models,
                        isDiscovering = isDiscovering,
                        isValidating = isValidating,
                        status = status,
                        statusOk = statusOk,
                        statusProviderMessage = statusProviderMessage,
                        keyConnectionStatuses = keyConnectionStatuses,
                        savedKeys = savedKeys,
                        newKeyName = newKeyName,
                        newApiKey = newApiKey,
                        newKeyVisible = newKeyVisible,
                        onProvider = { kind ->
                            selectedKind = kind
                            baseUrl = kind.defaultBaseUrl
                            model = kind.defaultModel
                            dshApi = defaultDshApiForProvider(kind)
                            openRouterProviderOrder = ""
                            openRouterAllowFallbacks = true
                            models = emptyList()
                            modelSearch = ""
                            showModels = false
                            newKeyName = ""
                            newApiKey = ""
                            status = null
                            statusProviderMessage = null
                        },
                        onBaseUrl = {
                            baseUrl = it
                            // upstream a2b5ec61: infer the gateway protocol from the
                            // URL for Custom API on every agent.
                            if (selectedKind == ProviderKind.CUSTOM) {
                                dshApi = inferredDshApiForUrl(it)
                            }
                            models = emptyList()
                            status = null
                            statusProviderMessage = null
                            keyConnectionStatuses = emptyMap()
                        },
                        onModel = { model = it; status = null; statusProviderMessage = null; keyConnectionStatuses = emptyMap() },
                        onDshApi = { dshApi = it; status = null; statusProviderMessage = null; keyConnectionStatuses = emptyMap() },
                        onOpenRouterProviderOrder = {
                            openRouterProviderOrder = it
                            status = null
                            statusProviderMessage = null
                            keyConnectionStatuses = emptyMap()
                        },
                        onOpenRouterAllowFallbacks = {
                            openRouterAllowFallbacks = it
                            status = null
                            statusProviderMessage = null
                            keyConnectionStatuses = emptyMap()
                        },
                        onNewKeyName = { newKeyName = it },
                        onNewApiKey = { newApiKey = it },
                        onToggleNewKey = { newKeyVisible = !newKeyVisible },
                        onAddKey = {
                            savedKeys = onAddApiKey(selectedKind, newKeyName, newApiKey.trim())
                            newKeyName = ""
                            newApiKey = ""
                            apiKey = getSavedApiKey(selectedKind)
                            status = "API key added for ${selectedKind.title}."
                            statusOk = true
                        },
                        onActivateKey = { keyId ->
                            savedKeys = onActivateApiKey(selectedKind, keyId)
                            apiKey = getSavedApiKey(selectedKind)
                            status = "Active API key changed for ${selectedKind.title}."
                            statusOk = true
                        },
                        onRemoveKey = { keyId ->
                            savedKeys = onRemoveApiKey(selectedKind, keyId)
                            apiKey = getSavedApiKey(selectedKind)
                            keyConnectionStatuses = keyConnectionStatuses - keyId
                            status = "API key removed from ${selectedKind.title}."
                            statusOk = true
                        },
                        onOpenModelSheet = {
                            showModels = true
                        },
                        onDiscover = ::discoverModels,
                        onValidate = {
                            scope.launch {
                                isValidating = true
                                val activeKeyId = savedKeys.firstOrNull { it.isActive }?.id
                                if (activeKeyId != null) {
                                    keyConnectionStatuses = keyConnectionStatuses +
                                        (activeKeyId to KeyConnectionStatus("Checking connection…"))
                                    status = null
                                } else {
                                    status = "Checking connection…"
                                    statusOk = true
                                }
                                val kind = selectedKind
                                val url = if (kind.fixedBaseUrl) kind.defaultBaseUrl else baseUrl.trim()
                                val profile = ProviderProfile(
                                    kind,
                                    url,
                                    model.trim(),
                                    dshApi = dshApi,
                                    openRouterProviderOrder = openRouterProviderOrder,
                                    openRouterAllowFallbacks = openRouterAllowFallbacks,
                                )
                                if (kind == ProviderKind.CLAUDE) {
                                    onSaveProvider(profile, apiKey.trim())
                                    status = "Claude subscription token saved securely. Send a message to verify your subscription."
                                    statusOk = true
                                    activeKeyId?.let {
                                        keyConnectionStatuses = keyConnectionStatuses +
                                            (it to KeyConnectionStatus("Subscription token saved", true, label = "Saved"))
                                    }
                                } else when (val result = onValidateProvider(profile, apiKey.trim(), models)) {
                                    is ConnectionValidation.Success -> {
                                        // v3.2.52: persist the verdict as the
                                        // agent's last-known connection state.
                                        onAgentTestResult(state.agentKind, true, null)
                                        onSaveProvider(profile, apiKey.trim())
                                        if (activeKeyId != null) {
                                            keyConnectionStatuses = keyConnectionStatuses +
                                                (activeKeyId to KeyConnectionStatus(result.message, true, label = "Verified"))
                                        } else {
                                            status = result.message
                                            statusOk = true
                                        }
                                    }
                                    is ConnectionValidation.Failure -> {
                                        onAgentTestResult(state.agentKind, false, result.message)
                                        if (activeKeyId != null) {
                                            keyConnectionStatuses = keyConnectionStatuses +
                                                (activeKeyId to KeyConnectionStatus(result.message, false, result.providerMessage, result.label))
                                        } else {
                                            status = result.message
                                            statusOk = false
                                            statusProviderMessage = result.providerMessage
                                        }
                                    }
                                }
                                isValidating = false
                            }
                        },
                    )
                }
            }

            // ── 2.5 v3.2.37 (Part C): Connect with API key ──
            item {
                ApiKeyCatalogSection(
                    connectedKinds = com.vayro.app.data.ProviderCatalog.chatKinds.filter {
                        getSavedApiKeys(it).isNotEmpty()
                    },
                    chatProviderKind = chatProviderKind,
                    onSelectChatProvider = onSelectChatProvider,
                    getSavedApiKeys = getSavedApiKeys,
                    onAddApiKey = onAddApiKey,
                    onActivateApiKey = onActivateApiKey,
                    onRemoveApiKey = onRemoveApiKey,
                    onSaveProvider = onSaveProvider,
                    onValidateProvider = onValidateProvider,
                    onDiscoverModels = onDiscoverModels,
                    catalogChatDefaults = catalogChatDefaults,
                    onChatEndpointCorrected = onChatEndpointCorrected,
                    autoSwitchFor = autoSwitchFor,
                    onToggleAutoSwitch = onToggleAutoSwitch,
                    keyExhaustedAt = keyExhaustedAt,
                )
            }

            // ── 3. Runtime Updates Card (per-tab: only the viewed agent's updates) ──
            item {
                AgentUpdateBlock(
                    state = state,
                    viewedAgent = viewedAgent,
                    onCheck = onCheckAgentUpdates,
                    onUpdate = onUpdateAgent,
                    onCancelUpdate = onCancelAgentUpdate,
                )
            }

            // ── 4. Subtle Footer ──
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "API keys encrypted in Android secure storage · Terminal is accessible from any project",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }

    if (showTeamDialog && teamOrchestratorResolved != null) {
        MultiAgentTeamBottomSheet(
            orchestrator = teamOrchestratorResolved,
            onDismiss = { showTeamDialog = false },
        )
    }
}

/**
 * Modern Antigravity Configuration Bento Card.
 */
@Composable
private fun AgentAntigravityCard(
    state: AppUiState,
    code: String,
    onCode: (String) -> Unit,
    onStartLogin: () -> Unit,
    onSubmitCode: () -> Unit,
    onLogout: () -> Unit,
    onRefreshModels: () -> Unit,
    onOpenModelSheet: () -> Unit,
    onSetEffort: (String) -> Unit,
    onTest: () -> Unit,
    lastCheckLine: String? = null,
    onStartAddAccount: () -> Unit = {},
    onSwitchAccount: (String) -> Unit = {},
    onRemoveAccount: (String) -> Unit = {},
    onToggleAutoRoundRobin: (Boolean) -> Unit = {},
    onToggleGeminiApiMode: (Boolean) -> Unit = {},
    onSaveGeminiApiKey: (String) -> Unit = {},
    onClearGeminiApiKey: () -> Unit = {},
    onSetGeminiApiModel: (String) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val auth = state.antigravityAuth
    var submittingCode by remember { mutableStateOf(false) }
    LaunchedEffect(code) { submittingCode = false }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GeminiApiModeSection(
                enabled = state.geminiApiMode,
                keySaved = state.geminiApiKeySaved,
                model = state.geminiApiModel,
                onToggle = onToggleGeminiApiMode,
                onSaveKey = onSaveGeminiApiKey,
                onClearKey = onClearGeminiApiKey,
                onSetModel = onSetGeminiApiModel,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            Text("Google account", fontSize = 20.sp, fontWeight = FontWeight.Bold)

            // Multiple Connected Google Accounts List
            if (auth.accounts.isNotEmpty()) {
                Text(
                    "Connected Google Accounts (${auth.accounts.size})",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    auth.accounts.forEach { account ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (account.isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(
                                1.dp,
                                if (account.isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(
                                                if (account.isActive) PocketGreen.copy(alpha = 0.15f)
                                                else MaterialTheme.colorScheme.surfaceVariant,
                                                CircleShape,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (account.isActive) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Active account",
                                                tint = PocketGreen,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), CircleShape),
                                            )
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            account.email,
                                            fontSize = 13.sp,
                                            fontWeight = if (account.isActive) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.clickable { clipboard.setText(AnnotatedString(account.email)) },
                                        )
                                        if (account.isQuotaExhausted) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                                modifier = Modifier.padding(top = 2.dp),
                                            ) {
                                                Text(
                                                    "Quota Limit Hit",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.error,
                                                    fontWeight = FontWeight.Medium,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (!account.isActive) {
                                        TextButton(
                                            onClick = { onSwitchAccount(account.email) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text("Use", fontSize = 11.sp, color = PocketOrange)
                                        }
                                    }
                                    IconButton(
                                        onClick = { onRemoveAccount(account.email) },
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove account",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Auto round-robin accounts", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Automatically switch to next connected Google account when quota or rate limit is reached",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = auth.autoRoundRobin,
                            onCheckedChange = onToggleAutoRoundRobin,
                        )
                    }
                }
            } else {
                // Fallback Single Account Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(PocketGreen.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                            .border(1.dp, PocketGreen.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("G", color = PocketGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        val email = auth.accountEmail
                        Text(
                            email ?: if (auth.status == AntigravityAuthStatus.SIGNED_IN) "Connected" else "Not signed in",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = email?.let { Modifier.clickable { clipboard.setText(AnnotatedString(it)) } } ?: Modifier,
                        )
                        Text(
                            if (auth.status == AntigravityAuthStatus.SIGNED_IN) "Connected with Google" else "Required for Antigravity",
                            fontSize = 11.sp,
                            color = if (auth.status == AntigravityAuthStatus.SIGNED_IN) PocketGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (auth.status == AntigravityAuthStatus.SIGNED_IN) {
                        Text(
                            "Disconnect",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable { onLogout() }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // Authentication actions
            when (auth.status) {
                AntigravityAuthStatus.STARTING, AntigravityAuthStatus.COMPLETING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                AntigravityAuthStatus.AWAITING_CODE -> {
                    auth.authorizationUrl?.let { url ->
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(url)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Copy Google Sign-in URL")
                        }
                    }
                    OutlinedTextField(
                        value = code,
                        onValueChange = onCode,
                        label = { Text("One-time authorization code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Button(
                        onClick = {
                            if (!submittingCode) {
                                submittingCode = true
                                onSubmitCode()
                            }
                        },
                        enabled = code.isNotBlank() && !submittingCode,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (submittingCode) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Signing in…")
                        } else {
                            Text("Complete Sign-in")
                        }
                    }
                }
                AntigravityAuthStatus.SIGNED_OUT, AntigravityAuthStatus.ERROR -> {
                    Button(
                        onClick = onStartLogin,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (auth.status == AntigravityAuthStatus.ERROR) "Reconnect with Google" else "Sign in with Google")
                    }
                }
                AntigravityAuthStatus.SIGNED_IN -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onStartAddAccount,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add Account", fontSize = 12.sp, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = onLogout,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Sign out all", fontSize = 12.sp)
                        }
                    }
                }
            }

            if (auth.status == AntigravityAuthStatus.SIGNED_IN) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                // Active Intelligence Model Tile
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Active Intelligence Model",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable(enabled = !state.antigravityModelsLoading) { onRefreshModels() }
                                .padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
                        ) {
                            if (state.antigravityModelsLoading) {
                                CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.4.dp)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                "Sync",
                                fontSize = 11.sp,
                                color = PocketOrange,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenModelSheet() },
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(PocketOrange.copy(alpha = 0.12f), RoundedCornerShape(9.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = PocketOrange,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                val currentModel = state.antigravityModel.ifBlank { "Select model" }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        formatAntigravityModelName(currentModel),
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    currentModel,
                                    fontSize = 11.sp,
                                    fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Choose model",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                // Reasoning Depth (Effort) Segmented Capsule
                Column(modifier = Modifier.fillMaxWidth()) {
                    val effortCaption = when (state.antigravityEffort) {
                        "low" -> "Fast response · light reasoning"
                        "medium" -> "Balanced speed & logic"
                        "high" -> "Maximum reasoning depth"
                        else -> "Balanced speed & logic"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Reasoning Depth",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            effortCaption,
                            fontSize = 11.sp,
                            color = PocketOrange,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            listOf("low", "medium", "high").forEach { effort ->
                                val isSelected = state.antigravityEffort == effort
                                Surface(
                                    shape = RoundedCornerShape(9.dp),
                                    color = if (isSelected) PocketOrange else Color.Transparent,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onSetEffort(effort) },
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 7.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            effort.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() },
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (auth.status == AntigravityAuthStatus.SIGNED_IN) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = state.apiPingStatus != ApiPingStatus.PINGING,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(13.dp),
                    border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.7f)),
                ) {
                    if (state.apiPingStatus == ApiPingStatus.PINGING) {
                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp, color = PocketOrange)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp), tint = PocketOrange)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (state.apiPingStatus == ApiPingStatus.PINGING) "Testing connection…" else "Test connection",
                        color = PocketOrange,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                state.apiPingMessage?.takeIf { state.apiPingStatus != ApiPingStatus.IDLE }?.let { message ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (state.apiPingStatus == ApiPingStatus.FAILED) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (state.apiPingStatus == ApiPingStatus.FAILED) MaterialTheme.colorScheme.error else PocketGreen,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            message,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = if (state.apiPingStatus == ApiPingStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // v3.2.52: the persistent verdict — survives leaving
                // the screen, unlike the transient message above.
                lastCheckLine?.let { line ->
                    Text(
                        line,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }

            Text(
                "Automatic tool approval · Runs inside the private Linux workspace",
                fontSize = 10.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeminiApiModeSection(
    enabled: Boolean,
    keySaved: Boolean,
    model: String,
    onToggle: (Boolean) -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onSetModel: (String) -> Unit,
) {
    val context = LocalContext.current
    var keyInput by remember { mutableStateOf("") }
    var modelMenuOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Use Gemini API", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Use your own Gemini API key instead of Google sign-in",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (enabled) {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(if (keySaved) "Replace API key" else "Gemini API key") },
                    placeholder = { Text("Paste your key here") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://aistudio.google.com/apikey"),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Get API key", fontSize = 12.sp, maxLines = 1)
                    }
                    Button(
                        onClick = {
                            onSaveKey(keyInput)
                            keyInput = ""
                        },
                        enabled = keyInput.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Save", fontSize = 12.sp)
                    }
                }
                if (keySaved) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = PocketGreen,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "API key saved on this device",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = onClearKey,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("Remove", fontSize = 11.sp)
                        }
                    }
                } else {
                    Text(
                        "Save an API key to turn on Gemini API mode",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Model",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExposedDropdownMenuBox(
                    expanded = modelMenuOpen,
                    onExpandedChange = { modelMenuOpen = !modelMenuOpen },
                ) {
                    OutlinedTextField(
                        value = GEMINI_API_MODELS.firstOrNull { it.first == model }?.second ?: model,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuOpen) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    ExposedDropdownMenu(
                        expanded = modelMenuOpen,
                        onDismissRequest = { modelMenuOpen = false },
                    ) {
                        GEMINI_API_MODELS.forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(label, fontSize = 13.sp) },
                                onClick = {
                                    onSetModel(id)
                                    modelMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentProviderCard(
    state: AppUiState,
    lastCheckLine: String? = null,
    selectedKind: ProviderKind,
    baseUrl: String,
    model: String,
    dshApi: String,
    openRouterProviderOrder: String,
    openRouterAllowFallbacks: Boolean,
    apiKey: String,
    models: List<DiscoveredModel>,
    isDiscovering: Boolean,
    isValidating: Boolean,
    status: String?,
    statusOk: Boolean,
    statusProviderMessage: String?,
    keyConnectionStatuses: Map<String, KeyConnectionStatus>,
    savedKeys: List<ApiKeyInfo>,
    newKeyName: String,
    newApiKey: String,
    newKeyVisible: Boolean,
    onProvider: (ProviderKind) -> Unit,
    onBaseUrl: (String) -> Unit,
    onModel: (String) -> Unit,
    onDshApi: (String) -> Unit,
    onOpenRouterProviderOrder: (String) -> Unit,
    onOpenRouterAllowFallbacks: (Boolean) -> Unit,
    onNewKeyName: (String) -> Unit,
    onNewApiKey: (String) -> Unit,
    onToggleNewKey: () -> Unit,
    onAddKey: () -> Unit,
    onActivateKey: (String) -> Unit,
    onRemoveKey: (String) -> Unit,
    onOpenModelSheet: () -> Unit,
    onDiscover: () -> Unit,
    onValidate: () -> Unit,
) {
    val visibleKinds = remember(state.agentKind, state.freeModeEnabled) { providersForAgent(state.agentKind, state.freeModeEnabled) }
    var connectionExpanded by rememberSaveable(selectedKind) { mutableStateOf(false) }
    // Keep this state across provider changes so selecting Custom API can
    // immediately reveal its required setup instead of resetting on recomposition.
    var endpointExpanded by rememberSaveable(state.agentKind) { mutableStateOf(false) }
    var keysExpanded by rememberSaveable(state.agentKind, selectedKind) { mutableStateOf(false) }
    var addKeyExpanded by rememberSaveable(savedKeys.isEmpty()) { mutableStateOf(savedKeys.isEmpty()) }
    val activeKey = savedKeys.firstOrNull { it.isActive }
    val activeKeyStatus = activeKey?.let { keyConnectionStatuses[it.id] }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            Text("AI provider", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            PremiumSummaryRow(
                icon = Icons.Default.Link,
                title = selectedKind.title,
                subtitle = selectedKind.subtitle,
                expanded = connectionExpanded,
                onClick = { connectionExpanded = !connectionExpanded },
            )

            AnimatedVisibility(connectionExpanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                    ) {
                        Column {
                            visibleKinds.forEachIndexed { index, kind ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onProvider(kind)
                                            connectionExpanded = false
                                            endpointExpanded = kind == ProviderKind.CUSTOM
                                        }
                                        .padding(horizontal = 13.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(kind.title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                        Text(kind.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    AgentSelectionDot(selectedKind == kind)
                                }
                                if (index != visibleKinds.lastIndex) {
                                    HorizontalDivider(Modifier.padding(start = 13.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                }
                            }
                        }
                    }

                }
            }

            if (selectedKind != ProviderKind.CLAUDE) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                PremiumSummaryRow(
                    icon = Icons.Default.Info,
                    title = if (selectedKind == ProviderKind.CUSTOM) "Custom API settings" else "Endpoint & protocol",
                    subtitle = buildString {
                        append(baseUrl.ifBlank { "Base URL required" })
                        if (state.agentKind == AgentKind.DEEPSEEK_HARNESS && selectedKind in DSH_PROTOCOL_PROVIDERS) {
                            append(" · ")
                            append(if (selectedKind.fixedProtocol) defaultDshApiForProvider(selectedKind) else dshApi)
                        }
                    },
                    expanded = endpointExpanded,
                    onClick = { endpointExpanded = !endpointExpanded },
                )

                AnimatedVisibility(endpointExpanded) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (selectedKind == ProviderKind.CUSTOM) {
                            Text(
                                "Enter the provider endpoint, then add its API key under Credentials.",
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { if (!selectedKind.fixedBaseUrl) onBaseUrl(it) },
                            label = { Text("Base URL") },
                            supportingText = if (selectedKind.fixedBaseUrl) ({ Text("Fixed by ${selectedKind.title}") }) else null,
                            readOnly = selectedKind.fixedBaseUrl,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )
                        if (selectedKind == ProviderKind.LLM_ROUTER) {
                            OutlinedTextField(
                                value = openRouterProviderOrder,
                                onValueChange = onOpenRouterProviderOrder,
                                label = { Text("Provider order") },
                                placeholder = { Text("exacto, together") },
                                supportingText = {
                                    Text("Optional OpenRouter provider slugs, separated by commas and tried in order.")
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Allow provider fallbacks", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        "Turn off to use only the providers listed above.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = openRouterAllowFallbacks,
                                    onCheckedChange = onOpenRouterAllowFallbacks,
                                    enabled = openRouterProviderOrder.isNotBlank(),
                                )
                            }
                        }
                        // upstream a2b5ec61: same picker fix as setup. Custom API
                        // shows the gateway protocol picker on every agent.
                        if ((selectedKind == ProviderKind.CUSTOM || (state.agentKind == AgentKind.DEEPSEEK_HARNESS && selectedKind in DSH_PROTOCOL_PROVIDERS)) && !selectedKind.fixedProtocol) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text("Gateway protocol", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(5.dp))
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)) {
                                    Column {
                                        listOf("anthropic-messages", "openai-completions", "openai-responses").forEach { option ->
                                            Row(
                                                Modifier.fillMaxWidth().clickable { onDshApi(option) }.padding(horizontal = 12.dp, vertical = 9.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(option, Modifier.weight(1f), fontSize = 12.sp, fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily)
                                                AgentSelectionDot(dshApi == option)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Model & access", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        if (isDiscovering) "Discovering…" else if (models.isEmpty()) "Discover models" else "${models.size} models",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PocketOrange,
                        modifier = Modifier.clickable(enabled = !isDiscovering, onClick = onDiscover).padding(6.dp),
                    )
                }

                PremiumSummaryRow(
                    icon = Icons.Default.AutoAwesome,
                    title = "AI model",
                    subtitle = model.ifBlank { "Select or type a model ID" },
                    expanded = false,
                    onClick = onOpenModelSheet,
                )
            } else {
                Text(
                    "Run `claude setup-token` on a computer signed in to your Claude subscription, then save the generated token below.",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            if (status != null) {
                Column(modifier = Modifier.padding(start = 48.dp, end = 8.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (statusOk) Icons.Default.Info else Icons.Default.Warning, null, tint = if (statusOk) PocketOrange else MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(status, fontSize = 10.sp, lineHeight = 14.sp, color = if (statusOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    }
                    statusProviderMessage?.let { providerMessage ->
                        Text("Provider: $providerMessage", fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 21.dp, top = 4.dp))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            PremiumSummaryRow(
                icon = Icons.Default.Key,
                title = if (selectedKind == ProviderKind.CLAUDE) "Subscription token" else "Credentials",
                subtitle = buildString {
                    append(activeKey?.name ?: if (selectedKind == ProviderKind.CLAUDE) "No subscription token saved" else "No API key saved")
                    if (activeKey != null) append(" · Active")
                    activeKeyStatus?.let {
                        append(" · ")
                        append(when (it.successful) { true -> "Verified"; false -> it.label; null -> "Checking" })
                    }
                },
                positive = activeKeyStatus?.successful == true,
                error = activeKeyStatus?.successful == false,
                expanded = keysExpanded,
                onClick = { keysExpanded = !keysExpanded },
            )

            activeKeyStatus?.let { keyStatus ->
                if (!keysExpanded) {
                    Text(
                        keyStatus.message,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = when (keyStatus.successful) {
                            true -> PocketGreen
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(start = 48.dp, end = 8.dp, bottom = 8.dp),
                    )
                    keyStatus.providerMessage?.let { providerMessage ->
                        Text(
                            "Provider: $providerMessage",
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 48.dp, end = 8.dp, bottom = 8.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(keysExpanded) {
                Column(
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (selectedKind == ProviderKind.CLAUDE) "Saved tokens (${savedKeys.size})" else "Saved keys (${savedKeys.size})", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(
                            if (addKeyExpanded) "Cancel" else "+ Add key",
                            fontSize = 11.sp,
                            color = PocketOrange,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { addKeyExpanded = !addKeyExpanded }.padding(6.dp),
                        )
                    }
                    savedKeys.forEach { key ->
                        val keyStatus = keyConnectionStatuses[key.id]
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)) {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth().clickable { onActivateKey(key.id) }.padding(start = 12.dp, top = 7.dp, bottom = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(key.name, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                        Text(if (key.isActive) "Active" else "Tap to activate", fontSize = 10.sp, color = if (key.isActive) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    AgentSelectionDot(key.isActive)
                                    IconButton(onClick = { onRemoveKey(key.id) }) {
                                        Icon(Icons.Default.DeleteSweep, "Remove", Modifier.size(17.dp))
                                    }
                                }
                                keyStatus?.let {
                                    Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (it.successful == null) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
                                        else Icon(if (it.successful) Icons.Default.CheckCircle else Icons.Default.Warning, null, tint = if (it.successful) PocketGreen else MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(it.message, fontSize = 10.sp, lineHeight = 14.sp, color = if (it.successful == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                            it.providerMessage?.let { providerMessage ->
                                                Text("Provider: $providerMessage", fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(addKeyExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = newKeyName,
                                onValueChange = { input ->
                                    if ((input.startsWith("sk-") || input.startsWith("ant-") || input.length > 30) && !input.contains(" ") && newApiKey.isBlank()) {
                                        onNewApiKey(input.trim())
                                        onNewKeyName("${selectedKind.title} Key")
                                    } else onNewKeyName(input)
                                },
                                label = { Text(if (selectedKind == ProviderKind.CLAUDE) "Token name" else "Key name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )
                            OutlinedTextField(
                                value = newApiKey,
                                onValueChange = onNewApiKey,
                                label = { Text(if (selectedKind == ProviderKind.CLAUDE) "Claude setup token" else "API key") },
                                singleLine = true,
                                visualTransformation = if (newKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = { IconButton(onClick = onToggleNewKey) { Icon(if (newKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle visibility") } },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )
                            Button(
                                onClick = { onAddKey(); addKeyExpanded = false },
                                enabled = newKeyName.isNotBlank() && newApiKey.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) { Text(if (selectedKind == ProviderKind.CLAUDE) "Save token" else "Save API key") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onValidate,
                enabled = apiKey.isNotBlank() && !isDiscovering && !isValidating &&
                    (selectedKind == ProviderKind.CLAUDE || (baseUrl.isNotBlank() && model.isNotBlank())),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.7f)),
            ) {
                if (isValidating) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp, color = PocketOrange)
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(if (selectedKind == ProviderKind.CLAUDE) Icons.Default.Check else Icons.Default.Refresh, null, Modifier.size(16.dp), tint = PocketOrange)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        isValidating && selectedKind == ProviderKind.CLAUDE -> "Saving token…"
                        isValidating -> "Testing connection…"
                        selectedKind == ProviderKind.CLAUDE -> "Save subscription token"
                        else -> "Test connection"
                    },
                    color = PocketOrange,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // v3.2.52: the persistent last-known connection state for
            // this agent — one quiet line under the Test button.
            lastCheckLine?.let { line ->
                Spacer(Modifier.height(10.dp))
                Text(
                    line,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun PremiumSummaryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    positive: Boolean = false,
    error: Boolean = false,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(38.dp).background(PocketOrange.copy(alpha = 0.10f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = PocketOrange, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                subtitle,
                fontSize = 11.sp,
                color = when {
                    error -> MaterialTheme.colorScheme.error
                    positive -> PocketGreen
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                // Medium: the subtitle stayed capped at 2 lines even when
                // the row was expanded — the full message was unreachable.
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Bring-your-own-API configuration card for the Custom agent.
 *
 * No binary install, no runtime updates — the user plugs in their own
 * OpenAI-compatible or Claude-compatible endpoint (base URL + API key + model)
 * and the Custom agent chats directly via that API.
 */
/**
 * Codex CLI configuration card. Same save pattern as the Custom card: base
 * URL + API key + model saved directly, with no provider-validation round
 * trip (an Anthropic-style ping would fail against an OpenAI endpoint).
 * Base URL and model are optional: blank means the OpenAI default and the
 * Codex default respectively. Install / update live in the generic
 * not-installed card and the per-tab Runtime updates block.
 */
@Composable
private fun AgentCodexCard(
    state: AppUiState,
    onSaveProvider: (ProviderProfile, String) -> Unit,
    getSavedApiKey: (ProviderKind) -> String,
) {
    val currentProfile = state.provider.takeIf { it.kind == ProviderKind.OPENAI }
    var model by rememberSaveable { mutableStateOf(currentProfile?.model.orEmpty()) }
    var apiKey by remember { mutableStateOf(getSavedApiKey(ProviderKind.OPENAI)) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var savedTick by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Codex CLI", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "OpenAI Codex CLI — full agentic coding (file edits, terminal) via your OpenAI API key. Installed with npm in the Linux guest.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; savedTick = false; saveError = null },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (keyVisible) "Hide key" else "Show key",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; savedTick = false },
            label = { Text("Model (optional)") },
            placeholder = { Text("gpt-5-codex (blank = Codex default)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )

        Button(
            onClick = {
                val profile = ProviderProfile(
                    kind = ProviderKind.OPENAI,
                    model = model.trim(),
                    dshApi = "openai-completions",
                )
                if (apiKey.isNotBlank()) {
                    // Medium: "Saved" used to show unconditionally, even if
                    // the Keystore write threw. The tick now reflects the
                    // actual save outcome; a failure shows its reason.
                    val result = runCatching { onSaveProvider(profile, apiKey.trim()) }
                    savedTick = result.isSuccess
                    saveError = result.exceptionOrNull()?.let { it.message ?: "Could not save the API key" }
                }
            },
            enabled = apiKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (savedTick) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(if (savedTick) "Saved" else "Save Codex API", fontWeight = FontWeight.Bold)
        }
        saveError?.let {
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }

        Text(
            "Your key is stored encrypted in Android secure storage and passed to the CLI through its process environment. Codex runs the official codex exec CLI in the Linux guest.",
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun AgentCustomCard(
    state: AppUiState,
    onSaveProvider: (ProviderProfile, String) -> Unit,
    getSavedApiKey: (ProviderKind) -> String,
) {
    val currentProfile = state.provider.takeIf { it.kind == ProviderKind.CUSTOM }
    var apiType by rememberSaveable {
        mutableStateOf(
            when (currentProfile?.dshApi) {
                "anthropic-messages" -> "anthropic-messages"
                else -> "openai-completions"
            }
        )
    }
    var baseUrl by rememberSaveable { mutableStateOf(currentProfile?.baseUrl.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(currentProfile?.model.orEmpty()) }
    var apiKey by remember { mutableStateOf(getSavedApiKey(ProviderKind.CUSTOM)) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var savedTick by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Your API", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "Plug in any API key and use the full agent power of the app with any provider. No download needed.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // API type selector
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "openai-completions" to "OpenAI-compatible",
                    "anthropic-messages" to "Claude-compatible",
                ).forEach { (value, label) ->
                    val selected = apiType == value
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = if (selected) PocketOrange.copy(alpha = 0.16f) else Color.Transparent,
                        border = if (selected) BorderStroke(1.dp, PocketOrange.copy(alpha = 0.5f)) else null,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { apiType = value },
                    ) {
                        Text(
                            label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            placeholder = {
                Text(
                    if (apiType == "anthropic-messages") "https://api.anthropic.com"
                    else "https://api.openai.com/v1"
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; savedTick = false; saveError = null },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (keyVisible) "Hide key" else "Show key",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            placeholder = {
                Text(
                    if (apiType == "anthropic-messages") "claude-sonnet-4-6"
                    else "gpt-4o-mini"
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )

        Button(
            onClick = {
                val profile = ProviderProfile(
                    kind = ProviderKind.CUSTOM,
                    baseUrl = baseUrl.trim(),
                    model = model.trim(),
                    dshApi = apiType,
                )
                if (apiKey.isNotBlank()) {
                    // Medium: "Saved" used to show unconditionally, even if
                    // the Keystore write threw. The tick now reflects the
                    // actual save outcome; a failure shows its reason.
                    val result = runCatching { onSaveProvider(profile, apiKey.trim()) }
                    savedTick = result.isSuccess
                    saveError = result.exceptionOrNull()?.let { it.message ?: "Could not save the API key" }
                }
            },
            enabled = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (savedTick) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(if (savedTick) "Saved" else "Save Custom API", fontWeight = FontWeight.Bold)
        }
        saveError?.let {
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }

        Text(
            "Custom chats directly via your API. For code execution (file edits, terminal), install Claude Code or DeepSeek.",
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

/**
 * Compact Runtime Updates Card.
 */
@Composable
private fun AgentUpdateBlock(
    state: AppUiState,
    viewedAgent: AgentKind,
    onCheck: () -> Unit,
    onUpdate: (AgentKind) -> Unit,
    onCancelUpdate: (AgentKind) -> Unit = {},
) {
    // Each tab shows ONLY its own agent's update checker — never another agent's.
    val update = state.agentUpdates[viewedAgent]
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The ViewModel silently ignores a check while a task runs, so
            // the button is disabled then — and, unlike before, it now
            // LOOKS disabled and says why instead of looking clickable.
            val canCheck = !state.agentUpdatesChecking && state.agentUpdating == null && state.agentInstalling == null && !state.isRunning
            Column(Modifier.weight(1f)) {
                Text("Runtime updates", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                // Never claim "up to date" without a check: agents that are not
                // installed, or were never checked, used to show that same text,
                // so Check updates looked like it did nothing.
                val isInstalled = viewedAgent == AgentKind.CUSTOM ||
                    viewedAgent == state.agentKind ||
                    state.installedAgentVersions.containsKey(viewedAgent)
                Text(
                    when {
                        viewedAgent == AgentKind.CUSTOM -> "Custom uses your API directly — no runtime to update"
                        update != null -> "1 update available for ${viewedAgent.title}"
                        !isInstalled -> "${viewedAgent.title} is not installed — install it first, then check for updates"
                        state.agentUpdatesChecking -> state.agentUpdateMessage?.takeIf { it.isNotBlank() } ?: "Checking for updates…"
                        state.agentUpdatesCheckError != null -> "Could not check updates: ${state.agentUpdatesCheckError}"
                        !canCheck -> "Update check unavailable while a task, install, or update is running"
                        !state.agentUpdatesChecked -> "Tap Check updates to see if a newer version is available"
                        else -> "${viewedAgent.title} is up to date"
                    },
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (viewedAgent != AgentKind.CUSTOM) {
                Row(
                    modifier = Modifier
                        .clickable(enabled = canCheck, onClick = onCheck)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.agentUpdatesChecking) {
                        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = PocketOrange.copy(alpha = if (canCheck) 1f else 0.38f), modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(5.dp))
                    Text("Check updates", color = PocketOrange.copy(alpha = if (canCheck) 1f else 0.38f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (update != null) {
            val agent = viewedAgent
            val updating = state.agentUpdating == agent
            val downloaded = state.agentUpdateDownloadedBytes
            val total = state.agentUpdateTotalBytes
            val fraction = if (updating && downloaded != null && total != null && total > 0L) {
                (downloaded.toFloat() / total).coerceIn(0f, 1f)
            } else state.agentUpdateProgress.coerceIn(0f, 1f)

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PocketOrange.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.28f)),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(agent.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("v${update.installedVersion} → v${update.latestVersion}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("UPDATE", color = PocketOrange, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    if (updating) {
                        state.agentUpdateMessage?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onCancelUpdate(agent) }) {
                                Text("Cancel update", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                            }
                        }
                    } else {
                        Button(
                            onClick = { onUpdate(agent) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.agentUpdating == null && state.agentInstalling == null,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Update ${agent.title}", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun formatAgentBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

@Composable
private fun AgentSelectionDot(selected: Boolean) {
    Box(
        Modifier.size(22.dp).border(if (selected) 2.dp else 1.5.dp, if (selected) PocketOrange else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).background(PocketOrange, CircleShape))
    }
}

/** Provides popular default models for providers when discovery hasn't been run or is unavailable. */
private fun defaultModelsForProvider(kind: ProviderKind): List<DiscoveredModel> = when (kind) {
    ProviderKind.DEEPSEEK -> listOf(
        DiscoveredModel("deepseek-chat", "DeepSeek V3.2 (chat)"),
        DiscoveredModel("deepseek-reasoner", "DeepSeek R1 (reasoning)"),
        DiscoveredModel("deepseek-v4-flash", "DeepSeek-V4 Flash (fast)"),
    )
    ProviderKind.OPENCODE_ZEN -> listOf(
        DiscoveredModel(ProviderKind.OPENCODE_ZEN.defaultModel, "OpenCode Zen default"),
    )
    ProviderKind.NVIDIA_NIM -> listOf(
        DiscoveredModel("nvidia/llama-3.1-nemotron-70b-instruct", "Nemotron 70B Instruct (flagship)"),
        DiscoveredModel("nvidia/llama-3.1-nemotron-ultra-253b-v1", "Nemotron Ultra 253B"),
        DiscoveredModel("nvidia/llama-3.1-nemotron-nano-8b-v1", "Nemotron Nano 8B (fast)"),
        DiscoveredModel("nvidia/nemotron-4-340b-instruct", "Nemotron-4 340B Instruct"),
        DiscoveredModel("meta/llama-3.3-70b-instruct", "Llama 3.3 70B Instruct"),
        DiscoveredModel("meta/llama-3.1-405b-instruct", "Llama 3.1 405B Instruct"),
        DiscoveredModel("meta/llama-3.1-70b-instruct", "Llama 3.1 70B Instruct"),
        DiscoveredModel("meta/llama-3.1-8b-instruct", "Llama 3.1 8B Instruct (fast)"),
        DiscoveredModel("mistralai/mistral-large-2-instruct", "Mistral Large 2"),
        DiscoveredModel("mistralai/mixtral-8x22b-instruct-v0.1", "Mixtral 8x22B"),
        DiscoveredModel("qwen/qwen2.5-coder-32b-instruct", "Qwen 2.5 Coder 32B"),
        DiscoveredModel("qwen/qwen3-235b-a22b", "Qwen3 235B MoE"),
        DiscoveredModel("deepseek-ai/deepseek-r1", "DeepSeek R1 (reasoning)"),
        DiscoveredModel("deepseek-ai/deepseek-v3.1", "DeepSeek V3.1"),
        DiscoveredModel("google/gemma-3-27b-it", "Gemma 3 27B"),
        DiscoveredModel("microsoft/phi-4", "Phi-4"),
    )
    ProviderKind.ANTHROPIC -> listOf(
        DiscoveredModel("claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet (Hybrid)"),
        DiscoveredModel("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet v2"),
        DiscoveredModel("claude-3-5-haiku-20241022", "Claude 3.5 Haiku"),
    )
    ProviderKind.LLM_ROUTER -> listOf(
        DiscoveredModel("deepseek/deepseek-r1", "DeepSeek R1 (via OpenRouter)"),
        DiscoveredModel("anthropic/claude-3.7-sonnet", "Claude 3.7 Sonnet"),
        DiscoveredModel("openai/gpt-4o", "GPT-4o"),
        DiscoveredModel("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B"),
    )
    ProviderKind.KIMI -> listOf(
        DiscoveredModel("kimi-k2.6", "Kimi K2.6"),
        DiscoveredModel("moonshot-v1-8k", "Moonshot v1 8K"),
        DiscoveredModel("moonshot-v1-32k", "Moonshot v1 32K"),
    )
    else -> if (kind.defaultModel.isNotBlank()) listOf(
        DiscoveredModel(kind.defaultModel, "${kind.title} Default (${kind.defaultModel})")
    ) else emptyList()
}

// ---------------------------------------------------------------------------
// v3.2.37 (Part C + F2): "Connect with API key" catalog.
//
// Every row is a real provider in the existing system: connecting writes
// the key into the encrypted vault pool (same storage the agent provider
// editor above uses), and the provider becomes selectable for agents and
// for home chat. Test & Connect runs the same real validation call as the
// main editor (a minimal chat/messages request). Pakistan availability of
// every listed provider was verified at build time; NVIDIA NIM is excluded
// on purpose (Pakistani phone verification fails there).
// ---------------------------------------------------------------------------

@Composable
private fun ApiKeyCatalogSection(
    connectedKinds: List<ProviderKind>,
    chatProviderKind: ProviderKind?,
    onSelectChatProvider: (ProviderKind) -> Unit,
    getSavedApiKeys: (ProviderKind) -> List<ApiKeyInfo>,
    onAddApiKey: (ProviderKind, String, String) -> List<ApiKeyInfo>,
    onActivateApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onRemoveApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onSaveProvider: (ProviderProfile, String) -> Unit,
    onValidateProvider: suspend (ProviderProfile, String, List<DiscoveredModel>) -> ConnectionValidation,
    onDiscoverModels: suspend (ProviderProfile, String) -> ModelDiscoveryResult,
    catalogChatDefaults: (ProviderKind) -> ProviderProfile,
    onChatEndpointCorrected: (ProviderKind, String, String) -> Unit,
    autoSwitchFor: (ProviderKind) -> Boolean,
    onToggleAutoSwitch: (ProviderKind, Boolean) -> Unit,
    keyExhaustedAt: (ProviderKind, String) -> Long?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(
                "Connect with API key",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Add a key once — it works for home chat and for the DeepSeek Harness and Custom agents. Keys are encrypted on this phone.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // F2 control: which connected provider answers home chat.
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Chat replies use", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                if (connectedKinds.isEmpty()) {
                    Text(
                        "No API provider is connected yet. Connect one below and home chat answers with it. Until then, chat uses your current agent provider when it has a key.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Home chat and voice-call replies come from this provider. Work tasks keep using your agent.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    connectedKinds.forEach { kind ->
                        val selected = kind == chatProviderKind
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectChatProvider(kind) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AgentSelectionDot(selected = selected)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                kind.title,
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Text(
                                    "Answering chat",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        com.vayro.app.data.ProviderCatalog.entries.forEach { entry ->
            CatalogProviderCard(
                entry = entry,
                getSavedApiKeys = getSavedApiKeys,
                onAddApiKey = onAddApiKey,
                onActivateApiKey = onActivateApiKey,
                onRemoveApiKey = onRemoveApiKey,
                onSaveProvider = onSaveProvider,
                onValidateProvider = onValidateProvider,
                onDiscoverModels = onDiscoverModels,
                catalogChatDefaults = catalogChatDefaults,
                onChatEndpointCorrected = onChatEndpointCorrected,
                autoSwitchFor = autoSwitchFor,
                onToggleAutoSwitch = onToggleAutoSwitch,
                keyExhaustedAt = keyExhaustedAt,
            )
        }
    }
}

/**
 * v3.2.38 (L/L2): plain-English headline for a catalog test failure. The
 * provider's real answer (HTTP status + its own error text) goes in the
 * small technical line — never settings jargon as the headline.
 */
private fun catalogFailureHeadline(result: ConnectionValidation.Failure, kind: ProviderKind): String = when {
    result.label == "Rejected" -> "This key was rejected by ${kind.title}. Check the key and try again."
    // v3.2.41 (Scope 2): a model-class 404 is the provider talking
    // about the MODEL — say so in its name, with its own words in the
    // technical line below; the address is never blamed first.
    result.modelError -> "${kind.title} says this model isn't available for your key."
    result.label == "Rate limited" -> "${kind.title} is rate-limiting right now — wait a moment and try again."
    result.label == "Provider error" -> "${kind.title} is having a problem right now — try again later."
    result.label == "Timed out" || result.label == "Network error" -> "Network problem — check your connection and try again."
    result.label == "Endpoint error" -> "Kodex could not reach ${kind.title} at that address."
    else -> result.message
}

/** v3.2.38 (L2): the diagnosability line — real HTTP status + the provider's own words. */
private fun catalogTechnicalLine(result: ConnectionValidation.Failure): String? {
    val parts = listOfNotNull(
        if (result.httpCode > 0) "HTTP ${result.httpCode}" else null,
        result.providerMessage,
    )
    return parts.joinToString(" · ").ifBlank { null }
}

/**
 * v3.2.38 (L): picks a working chat model from the provider's own list —
 * the verified default first, then a same-family chat model, skipping
 * embedding / speech / moderation / codex-only ids that cannot answer chat.
 */
private fun pickCatalogChatModel(
    models: List<DiscoveredModel>,
    kind: ProviderKind,
    exclude: Set<String> = emptySet(),
): String? {
    if (models.isEmpty()) return null
    // v3.2.41 (Scope 2): never "correct" onto the model that just
    // failed — a provider's list can still advertise a model its keys
    // may not call (Google lists the 2.5 series while new keys get a
    // 404 for it), so the failed id is excluded before any preference.
    val ids = models.map { it.id }.filter { it !in exclude }
    if (ids.isEmpty()) return null
    if (kind.defaultModel in ids) return kind.defaultModel
    val notChat = listOf("embed", "whisper", "tts", "transcri", "moderation", "rerank", "image", "audio", "codex", "safeguard", "guard")
    val chat = ids.filter { id -> notChat.none { id.contains(it, ignoreCase = true) } }
    val family = kind.defaultModel.substringAfterLast('/').split('-').take(2).joinToString("-")
    return chat.firstOrNull { it.contains(family, ignoreCase = true) }
        ?: chat.firstOrNull()
        ?: ids.firstOrNull()
}

@Composable
private fun CatalogProviderCard(
    entry: com.vayro.app.data.CatalogProvider,
    getSavedApiKeys: (ProviderKind) -> List<ApiKeyInfo>,
    onAddApiKey: (ProviderKind, String, String) -> List<ApiKeyInfo>,
    onActivateApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onRemoveApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onSaveProvider: (ProviderProfile, String) -> Unit,
    onValidateProvider: suspend (ProviderProfile, String, List<DiscoveredModel>) -> ConnectionValidation,
    onDiscoverModels: suspend (ProviderProfile, String) -> ModelDiscoveryResult,
    catalogChatDefaults: (ProviderKind) -> ProviderProfile,
    onChatEndpointCorrected: (ProviderKind, String, String) -> Unit,
    autoSwitchFor: (ProviderKind) -> Boolean,
    onToggleAutoSwitch: (ProviderKind, Boolean) -> Unit,
    keyExhaustedAt: (ProviderKind, String) -> Long?,
) {
    val kind = entry.kind
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keys by remember(kind) { mutableStateOf(getSavedApiKeys(kind)) }
    val connected = keys.isNotEmpty()
    var newKey by remember(kind) { mutableStateOf("") }
    var newKeyName by remember(kind) { mutableStateOf("") }
    var testing by remember(kind) { mutableStateOf(false) }
    var status by remember(kind) { mutableStateOf<String?>(null) }
    var statusDetail by remember(kind) { mutableStateOf<String?>(null) }
    var statusOk by remember(kind) { mutableStateOf(false) }
    var showAdvanced by remember(kind) { mutableStateOf(false) }
    var baseUrl by remember(kind) { mutableStateOf(catalogChatDefaults(kind).baseUrl) }
    var baseUrlError by remember(kind) { mutableStateOf<String?>(null) }
    var model by remember(kind) { mutableStateOf(catalogChatDefaults(kind).model) }
    var autoSwitch by remember(kind) { mutableStateOf(autoSwitchFor(kind)) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(kind.title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        entry.note,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (connected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(8.dp).background(PocketGreen, CircleShape),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Connected",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(entry.keyPageUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Get API key", fontWeight = FontWeight.SemiBold)
            }
            if (connected) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Saved keys (${keys.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                keys.forEach { info ->
                    val exhaustedAt = keyExhaustedAt(kind, info.id)
                    val resting = exhaustedAt != null &&
                        System.currentTimeMillis() - exhaustedAt < com.vayro.app.data.ApiKeyFailoverCoordinator.QUOTA_COOLDOWN_MS
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !info.isActive) {
                                keys = onActivateApiKey(kind, info.id)
                            }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AgentSelectionDot(selected = info.isActive)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                info.name,
                                fontSize = 13.5.sp,
                                fontWeight = if (info.isActive) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                            )
                            val sub = when {
                                resting -> {
                                    val until = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                        .format(java.util.Date(exhaustedAt!! + com.vayro.app.data.ApiKeyFailoverCoordinator.QUOTA_COOLDOWN_MS))
                                    "Limit reached · resting until about $until"
                                }
                                info.isActive -> "Active key"
                                else -> "Tap to make active"
                            }
                            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = { keys = onRemoveApiKey(kind, info.id) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Remove key", modifier = Modifier.size(17.dp))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-switch keys", fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "When a key hits its limit, Kodex moves to the next key by itself and work continues.",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoSwitch,
                        onCheckedChange = { on ->
                            autoSwitch = on
                            onToggleAutoSwitch(kind, on)
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                label = { Text(if (connected) "Add another key" else "Paste your API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            )
            if (connected) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newKeyName,
                    onValueChange = { newKeyName = it },
                    label = { Text("Key name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )
            }
            status?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = if (statusOk) PocketGreen else MaterialTheme.colorScheme.error,
                )
            }
            statusDetail?.let { detail ->
                Spacer(Modifier.height(2.dp))
                Text(
                    detail,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                enabled = newKey.isNotBlank() && !testing,
                onClick = {
                    val keyValue = newKey.trim()
                    // v3.2.41 (Scope 2): the Base URL is checked at the
                    // field before anything is tested or saved — a
                    // pasted curl example stops here with the plain
                    // message instead of reaching the network layer.
                    val addressError = com.vayro.app.network.BaseUrlValidation.errorOrNull(baseUrl)
                    if (addressError != null) {
                        baseUrlError = addressError
                        if (!showAdvanced) showAdvanced = true
                        status = addressError
                        statusDetail = null
                        statusOk = false
                        return@Button
                    }
                    baseUrlError = null
                    fun finishConnect(usedModel: String, corrected: Boolean, testedBaseUrl: String) {
                        keys = onAddApiKey(
                            kind,
                            newKeyName.trim().ifBlank { "${kind.title} Key" },
                            keyValue,
                        )
                        // v3.2.38 (J): what was tested is what gets used —
                        // persist the validated endpoint so home chat runs
                        // with this exact Base URL + model, not defaults.
                        onChatEndpointCorrected(kind, testedBaseUrl, usedModel)
                        newKey = ""
                        newKeyName = ""
                        status = if (corrected) {
                            "Connected — using $usedModel. ${kind.title} is ready to use."
                        } else {
                            "Connected. ${kind.title} is ready to use."
                        }
                        statusDetail = null
                        statusOk = true
                    }
                    scope.launch {
                        testing = true
                        status = "Testing the key with ${kind.title}…"
                        statusDetail = null
                        statusOk = true
                        val profile = ProviderProfile(kind, baseUrl.trim(), model.trim())
                        // v3.2.38 (L): if the provider says the model is gone,
                        // do not fail at the user — fetch the provider's own
                        // model list with the pasted key, pick a working
                        // chat model, and retry once with it.
                        when (val first = onValidateProvider(profile, keyValue, emptyList())) {
                            is ConnectionValidation.Success -> finishConnect(profile.model, corrected = false, testedBaseUrl = profile.baseUrl)
                            is ConnectionValidation.Failure -> {
                                if (first.modelError) {
                                    status = "That model is gone — finding one that works…"
                                    val discovery = onDiscoverModels(profile, keyValue)
                                    val found = (discovery as? ModelDiscoveryResult.Success)?.models.orEmpty()
                                    // v3.2.41 (Scope 2): candidates in
                                    // order — the provider-list pick with
                                    // the failed model EXCLUDED (the list
                                    // can still advertise a model new
                                    // keys may not call), then this
                                    // provider's known-good default.
                                    // Each is tried once; the first that
                                    // answers is what gets connected.
                                    val candidates = buildList {
                                        pickCatalogChatModel(found, kind, exclude = setOf(profile.model))?.let { add(it) }
                                        if (kind.defaultModel != profile.model && kind.defaultModel !in this) {
                                            add(kind.defaultModel)
                                        }
                                    }.distinct()
                                    var connectedWith: String? = null
                                    var lastFailure: ConnectionValidation.Failure = first
                                    for (candidate in candidates) {
                                        when (val attempt = onValidateProvider(profile.copy(model = candidate), keyValue, emptyList())) {
                                            is ConnectionValidation.Success -> {
                                                connectedWith = candidate
                                                break
                                            }
                                            is ConnectionValidation.Failure -> lastFailure = attempt
                                        }
                                    }
                                    if (connectedWith != null) {
                                        model = connectedWith
                                        finishConnect(connectedWith, corrected = true, testedBaseUrl = profile.baseUrl)
                                    } else if (candidates.isEmpty()) {
                                        status = "The model Kodex picked is not available on ${kind.title}, and no other model could be found."
                                        statusDetail = catalogTechnicalLine(first)
                                        statusOk = false
                                    } else {
                                        status = catalogFailureHeadline(lastFailure, kind)
                                        statusDetail = catalogTechnicalLine(lastFailure)
                                        statusOk = false
                                    }
                                } else {
                                    status = catalogFailureHeadline(first, kind)
                                    statusDetail = catalogTechnicalLine(first)
                                    statusOk = false
                                }
                            }
                        }
                        testing = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (connected) "Test & add key" else "Test & Connect", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Hide advanced" else "Advanced — endpoint & model", fontSize = 12.5.sp)
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; baseUrlError = null },
                    label = { Text("Base URL") },
                    singleLine = true,
                    isError = baseUrlError != null,
                    supportingText = baseUrlError?.let { error ->
                        { Text(error, color = MaterialTheme.colorScheme.error) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        // v3.2.41 (Scope 2): garbage never gets saved —
                        // the same field check as Test & Connect.
                        val addressError = com.vayro.app.network.BaseUrlValidation.errorOrNull(baseUrl)
                        if (addressError != null) {
                            baseUrlError = addressError
                            status = addressError
                            statusDetail = null
                            statusOk = false
                            return@OutlinedButton
                        }
                        onSaveProvider(ProviderProfile(kind, baseUrl.trim(), model.trim()), "")
                        // v3.2.38 (L/J): an endpoint saved here is also the endpoint home chat uses for this provider.
                        onChatEndpointCorrected(kind, baseUrl.trim(), model.trim())
                        status = "Endpoint and model saved."
                        statusDetail = null
                        statusOk = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Save endpoint & model", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
