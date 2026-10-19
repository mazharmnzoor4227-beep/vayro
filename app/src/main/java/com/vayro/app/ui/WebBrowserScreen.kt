package com.vayro.app.ui

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.vayro.app.ui.theme.PocketGreen
import com.vayro.app.ui.theme.PocketOrange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import com.vayro.app.browser.AgentBrowserAttachState
import com.vayro.app.browser.clearAgentBrowserAttachState
import com.vayro.app.browser.readAgentBrowserAttachState
import com.vayro.app.browser.refreshWebViewTargetFile
import com.vayro.app.data.AppPreferences
import com.vayro.app.runtime.RuntimeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.vayro.app.visual.*
import com.vayro.app.visual.ui.VisualComparisonViewer
import com.vayro.app.visual.ui.VisualDevToolsSheet

private data class BrowserTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "New Tab",
    val url: String = "",
    val activeUrl: String? = null,
    val isDesktopMode: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

/**
 * BrowserTab is intentionally a plain data class, so it cannot be written directly
 * into Android's saved-instance-state Parcel. Persist each tab as a saveable JSON
 * string and restore defensively if Android restores a partially stale state.
 */
private fun saveTabsToJson(tabs: List<BrowserTab>): List<String> {
    return tabs.map { tab ->
        org.json.JSONObject()
            .put("id", tab.id)
            .put("title", tab.title)
            .put("url", tab.url)
            .put("activeUrl", tab.activeUrl ?: "")
            .put("isDesktopMode", tab.isDesktopMode)
            .put("canGoBack", tab.canGoBack)
            .put("canGoForward", tab.canGoForward)
            .toString()
    }
}

private fun restoreTabsFromJson(savedTabs: List<String>): List<BrowserTab> {
    return savedTabs.mapNotNull { encoded ->
        runCatching {
            val json = org.json.JSONObject(encoded)
            BrowserTab(
                id = json.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                title = json.optString("title", "New Tab"),
                url = json.optString("url"),
                activeUrl = json.optString("activeUrl").takeIf { it.isNotBlank() },
                isDesktopMode = json.optBoolean("isDesktopMode", false),
                canGoBack = json.optBoolean("canGoBack", false),
                canGoForward = json.optBoolean("canGoForward", false),
            )
        }.getOrNull()
    }.ifEmpty { listOf(BrowserTab(id = "tab_initial")) }
}

private val BrowserTabsSaver = listSaver<List<BrowserTab>, String>(
    save = { tabs -> saveTabsToJson(tabs) },
    restore = { saved -> restoreTabsFromJson(saved) },
)

/**
 * State wrapper around [BrowserTabsSaver]: rememberSaveable with a
 * Saver<List<BrowserTab>, ...> returns the plain list, not a MutableState,
 * so `var tabs by rememberSaveable(saver = BrowserTabsSaver)` does not compile.
 * This saver persists the MutableState itself, keeping `by` delegation working.
 */
private val BrowserTabsStateSaver = Saver<MutableState<List<BrowserTab>>, List<String>>(
    save = { state -> saveTabsToJson(state.value) },
    restore = { saved -> mutableStateOf(restoreTabsFromJson(saved)) },
)

private fun saveHistoryToJson(items: List<BrowserHistoryItem>): List<String> {
    return items.map { item ->
        org.json.JSONObject()
            .put("title", item.title)
            .put("url", item.url)
            .put("timestamp", item.timestamp)
            .toString()
    }
}

private fun restoreHistoryFromJson(saved: List<String>): List<BrowserHistoryItem> {
    return saved.mapNotNull { encoded ->
        runCatching {
            val json = org.json.JSONObject(encoded)
            BrowserHistoryItem(
                title = json.optString("title"),
                url = json.optString("url"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            )
        }.getOrNull()
    }
}

private fun saveBookmarksToJson(items: List<BrowserBookmark>): List<String> {
    return items.map { item ->
        org.json.JSONObject()
            .put("title", item.title)
            .put("url", item.url)
            .toString()
    }
}

private fun restoreBookmarksFromJson(saved: List<String>): List<BrowserBookmark> {
    return saved.mapNotNull { encoded ->
        runCatching {
            val json = org.json.JSONObject(encoded)
            BrowserBookmark(
                title = json.optString("title"),
                url = json.optString("url"),
            )
        }.getOrNull()
    }
}

private val BrowserHistoryStateSaver = Saver<MutableState<List<BrowserHistoryItem>>, List<String>>(
    save = { state -> saveHistoryToJson(state.value) },
    restore = { saved -> mutableStateOf(restoreHistoryFromJson(saved)) },
)

private val BrowserBookmarksStateSaver = Saver<MutableState<List<BrowserBookmark>>, List<String>>(
    save = { state -> saveBookmarksToJson(state.value) },
    restore = { saved -> mutableStateOf(restoreBookmarksFromJson(saved)) },
)

private data class BrowserHistoryItem(
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis(),
)

private data class BrowserBookmark(
    val title: String,
    val url: String,
)

private data class StandaloneSystemShortcut(
    val name: String,
    val tag: String,
    val description: String,
    val url: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val STANDALONE_SYSTEM_SHORTCUTS = listOf(
    StandaloneSystemShortcut(
        name = "Hermes 3D",
        tag = "Virtual Office",
        description = "3D AI Agent office simulator & spatial standups",
        url = "https://github.com/iamlukethedev/Hermes3D",
        icon = Icons.Default.ViewInAr,
    ),
    StandaloneSystemShortcut(
        name = "OpenGym",
        tag = "Fitness Tracker",
        description = "Self-hosted private workout & bodyweight logger",
        url = "https://github.com/DuarteSantos8/openGym",
        icon = Icons.Default.FitnessCenter,
    ),
    StandaloneSystemShortcut(
        name = "TikTok 5.6B",
        tag = "Video Dataset",
        description = "5.6B video metadata & ClickHouse analytics",
        url = "https://huggingface.co/datasets/datasocial/tiktok-5.6B-videos",
        icon = Icons.Default.Analytics,
    ),
    StandaloneSystemShortcut(
        name = "GitFrames",
        tag = "WebGPU VFX",
        description = "Code-first video compositing natively on WebGPU",
        url = "https://github.com/gatewai-dev/gitframes",
        icon = Icons.Default.MovieFilter,
    ),
    StandaloneSystemShortcut(
        name = "AutoSocial",
        tag = "Automation",
        description = "Local multi-account short-form video publisher",
        url = "https://github.com/Katzca/AutoSocial",
        icon = Icons.Default.RocketLaunch,
    ),
    StandaloneSystemShortcut(
        name = "Munder Difflin",
        tag = "Office Harness",
        description = "Local-first desktop multi-agent harness",
        url = "https://github.com/chaitanyagiri/munder-difflin",
        icon = Icons.Default.Groups,
    ),
)

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
private const val MOBILE_CHROME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

private val UnsetSaveVisualSnapshot: (android.graphics.Bitmap, String, String, (VisualSnapshot) -> Unit) -> Unit =
    { _, _, _, _ -> }

@Composable
private fun AgentDriveBanner(tabLabel: String, onTakeOver: () -> Unit) {
    Surface(
        color = PocketOrange.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                tint = PocketOrange,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "Agent is controlling this tab",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = PocketOrange,
                )
                Text(
                    tabLabel.ifBlank { "Watching live. Tap anywhere or Take over to reclaim control." },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = onTakeOver,
                colors = ButtonDefaults.buttonColors(containerColor = PocketOrange, contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("Take over", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewTab(
    ready: Boolean,
    url: String?,
    detectedPorts: List<Int> = emptyList(),
    onScanPorts: ((List<Int>) -> Unit) -> Unit = {},
    onAttachVisualInspection: (String, String) -> Unit = { _, _ -> },
    projectRoot: java.io.File? = null,
    visualConsoleLogs: List<ConsoleLogEntry> = emptyList(),
    visualNetworkLogs: List<NetworkLogEntry> = emptyList(),
    inspectedDomElement: DomElementInfo? = null,
    detectedPortInfos: List<LocalhostPortInfo> = emptyList(),
    visualSnapshots: List<VisualSnapshot> = emptyList(),
    visualComparisonPair: VisualComparisonPair? = null,
    activeViewportPreset: ViewportPreset = ViewportPreset.RESPONSIVE,
    onRecordConsoleLog: (ConsoleLogEntry) -> Unit = {},
    onRecordNetworkLog: (NetworkLogEntry) -> Unit = {},
    onSetInspectedDomElement: (DomElementInfo?) -> Unit = {},
    onClearVisualLogs: () -> Unit = {},
    onSetViewportPreset: (ViewportPreset) -> Unit = {},
    onSaveVisualSnapshot: (android.graphics.Bitmap, String, String, (VisualSnapshot) -> Unit) -> Unit = UnsetSaveVisualSnapshot,
    onSetBaselineSnapshot: (VisualSnapshot) -> Unit = {},
    onSetComparisonSnapshot: (VisualSnapshot) -> Unit = {},
    onDispatchAiVisualBugReport: (String, String, VisualSnapshot?, () -> Unit) -> Unit = { _, _, _, cb -> cb() },
    onRequestSwitchToChat: () -> Unit = {},
) {
    val context = LocalContext.current
    val effectiveOnSaveVisualSnapshot: (android.graphics.Bitmap, String, String, (VisualSnapshot) -> Unit) -> Unit =
        if (onSaveVisualSnapshot === UnsetSaveVisualSnapshot) {
            { bmp, title, url, cb ->
                val dir = java.io.File(context.cacheDir, "visual_snapshots").apply { mkdirs() }
                val file = java.io.File(dir, "snapshot_${System.currentTimeMillis()}.png")
                runCatching { file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                cb(VisualSnapshot(title = title.ifBlank { "Snapshot" }, url = url, screenshotPath = file.absolutePath))
            }
        } else onSaveVisualSnapshot
    LaunchedEffect(Unit) { onScanPorts {} }

    // Multi-tab state
    var tabs by rememberSaveable(saver = BrowserTabsStateSaver) {
        mutableStateOf(
            listOf(
                BrowserTab(
                    id = "tab_initial",
                    title = if (ready && !url.isNullOrBlank()) "Project" else "New Tab",
                    url = if (ready && !url.isNullOrBlank()) url else "",
                    activeUrl = if (ready && !url.isNullOrBlank()) normalizePreviewUrl(url) else null,
                ),
            ),
        )
    }
    var activeTabId by rememberSaveable { mutableStateOf(tabs.first().id) }
    val currentTab = tabs.find { it.id == activeTabId } ?: tabs.first()

    var address by remember(currentTab.id, currentTab.url) { mutableStateOf(currentTab.url) }
    var addressError by remember { mutableStateOf<String?>(null) }
    // Medium: a failed page load used to show only the bare WebView error
    // page. The main-frame failure is now surfaced as a banner + Retry.
    var pageError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val canGoBack = remember { mutableStateOf(false) }
    var lastLoadedUrl by remember(activeTabId) { mutableStateOf<String?>(null) }

    // UI overlays & sheets
    var showMoreMenu by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showExtensionsModal by remember { mutableStateOf(false) }
    var showTabsStrip by rememberSaveable { mutableStateOf(false) }
    var isInspectorActive by rememberSaveable { mutableStateOf(false) }
    var showDevToolsSheet by remember { mutableStateOf(false) }
    var showAiDebuggerDialog by remember { mutableStateOf(false) }
    var showDiffViewerDialog by remember { mutableStateOf(false) }
    var aiDebuggerNotes by remember { mutableStateOf("") }
    var aiDebuggerSnapshot by remember { mutableStateOf<VisualSnapshot?>(null) }
    var showViewportDropdown by remember { mutableStateOf(false) }
    var toolbarCollapsed by rememberSaveable { mutableStateOf(false) }

    val appPrefs = remember { AppPreferences(context) }
    val agentDriveAllowed = remember { appPrefs.agentBrowserDriveEnabled }
    val browserRootfsTmp = remember {
        runCatching { RuntimeInstaller(context).installedRuntime().rootfs }.getOrNull()
            ?.let { java.io.File(it, "tmp") }
    }
    var agentDrive by remember { mutableStateOf<AgentBrowserAttachState?>(null) }
    val takeOverBrowserTab: () -> Unit = {
        browserRootfsTmp?.let { clearAgentBrowserAttachState(it) }
        agentDrive = null
        Toast.makeText(context, "You took over the browser tab", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(agentDriveAllowed, currentTab.activeUrl) {
        while (isActive) {
            try {
                withContext(Dispatchers.IO) {
                    browserRootfsTmp?.let { tmp ->
                        if (agentDriveAllowed) {
                            tmp.mkdirs()
                            refreshWebViewTargetFile(tmp, currentTab.activeUrl)
                        }
                        agentDrive = readAgentBrowserAttachState(tmp)
                    }
                }
            } catch (_: Exception) {
            }
            delay(2500)
        }
    }

    // Find in Page state
    var showFindInPage by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findMatchCurrent by remember { mutableIntStateOf(0) }
    var findMatchTotal by remember { mutableIntStateOf(0) }

    // History & Bookmarks state
    var historyList by rememberSaveable(saver = BrowserHistoryStateSaver) { mutableStateOf(listOf<BrowserHistoryItem>()) }
    var bookmarksList by rememberSaveable(saver = BrowserBookmarksStateSaver) { mutableStateOf(listOf<BrowserBookmark>()) }

    // Custom Extension / User Script runner
    var customScriptText by remember { mutableStateOf("") }
    var scriptOutputText by remember { mutableStateOf<String?>(null) }

    val openNewTab: (String?) -> Unit = { initialUrl ->
        val newId = java.util.UUID.randomUUID().toString()
        val normalized = initialUrl?.let { normalizePreviewUrl(it) }
        val newTab = BrowserTab(
            id = newId,
            title = if (normalized != null) "Loading..." else "New Tab",
            url = normalized.orEmpty(),
            activeUrl = normalized,
        )
        tabs = tabs + newTab
        activeTabId = newId
        address = normalized.orEmpty()
        addressError = null
        showTabsStrip = true
    }

    val closeTab: (String) -> Unit = { tabIdToClose ->
        if (tabs.size <= 1) {
            val resetId = java.util.UUID.randomUUID().toString()
            tabs = listOf(BrowserTab(id = resetId))
            activeTabId = resetId
            address = ""
            addressError = null
        } else {
            val nextTabs = tabs.filter { it.id != tabIdToClose }
            if (activeTabId == tabIdToClose) {
                val closedIndex = tabs.indexOfFirst { it.id == tabIdToClose }
                val nextActiveIndex = (closedIndex - 1).coerceAtLeast(0)
                activeTabId = nextTabs[nextActiveIndex].id
                address = nextTabs[nextActiveIndex].url
            }
            tabs = nextTabs
        }
    }

    val navigateTo: (String) -> Unit = { targetInput ->
        val normalized = normalizePreviewUrl(targetInput)
        if (normalized == null) {
            addressError = "Enter a valid URL or port (e.g. 3000, localhost:8080, google.com)"
        } else {
            addressError = null
            address = normalized
            tabs = tabs.map { tab ->
                if (tab.id == activeTabId) tab.copy(url = normalized, activeUrl = normalized, title = normalized) else tab
            }
        }
    }

    val navigate = { navigateTo(address) }

    val toggleDesktopSite: () -> Unit = {
        val newDesktop = !currentTab.isDesktopMode
        tabs = tabs.map {
            if (it.id == activeTabId) it.copy(isDesktopMode = newDesktop) else it
        }
        webView?.settings?.let { s ->
            s.userAgentString = if (newDesktop) DESKTOP_USER_AGENT else MOBILE_CHROME_USER_AGENT
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
        }
        webView?.reload()
    }

    val toggleDomInspector: () -> Unit = {
        isInspectorActive = !isInspectorActive
        webView?.evaluateJavascript(DomInspectorEngine.buildInspectorScript(isInspectorActive), null)
        Toast.makeText(context, if (isInspectorActive) "DOM Inspector enabled: Tap element" else "DOM Inspector disabled", Toast.LENGTH_SHORT).show()
    }

    val isBookmarked = bookmarksList.any { it.url == currentTab.url && it.url.isNotBlank() }
    val toggleBookmark: () -> Unit = {
        if (currentTab.url.isNotBlank()) {
            bookmarksList = if (isBookmarked) {
                bookmarksList.filter { it.url != currentTab.url }
            } else {
                listOf(BrowserBookmark(currentTab.title.ifBlank { currentTab.url }, currentTab.url)) + bookmarksList
            }
        }
    }

    LaunchedEffect(ready, url) {
        if (ready && !url.isNullOrBlank()) {
            normalizePreviewUrl(url)?.let { normUrl ->
                if (tabs.firstOrNull()?.activeUrl == null) {
                    tabs = tabs.mapIndexed { idx, tab ->
                        if (idx == 0 && tab.activeUrl == null) tab.copy(url = normUrl, activeUrl = normUrl, title = "Project") else tab
                    }
                    address = normUrl
                }
            }
        }
    }

    BackHandler(enabled = canGoBack.value) {
        webView?.goBack()
    }

    // The workspace Scaffold already applies system-bar insets to this tab's content.
    // Applying statusBarsPadding() again adds a large blank strip above the browser toolbar.
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            tonalElevation = 1.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                // Top row: URL bar only, restored to the 2.2.2 slim pill layout.
                // The 3-dots menu lives in the second row, after the other icons.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Slim, modern pill URL bar (height 40dp) with embedded Reload and Go buttons (2.2.2 layout)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (addressError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Embedded Reload button on the left (2.2.2 layout)
                            IconButton(
                                onClick = { webView?.reload() ?: navigate() },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reload",
                                    modifier = Modifier.size(16.dp),
                                    tint = if (currentTab.activeUrl != null) PocketGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Center URL input
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (address.isEmpty()) {
                                    Text(
                                        text = "Search or enter address",
                                        style = TextStyle(
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                BasicTextField(
                                    value = address,
                                    onValueChange = {
                                        address = it
                                        addressError = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go,
                                    ),
                                    keyboardActions = KeyboardActions(onGo = { navigate() }),
                                )
                            }

                            if (address.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        address = ""
                                        addressError = null
                                    },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            // Embedded Go button on the right
                            Box(
                                modifier = Modifier
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .clip(CircleShape)
                                    .clickable(onClick = navigate),
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Go",
                                            modifier = Modifier.size(15.dp),
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    }
                                }
                            }
                        }
                    }

                }

                // Second row: browser action buttons (refresh, tabs, viewport, AI, desktop, inspector, devtools)
                AnimatedVisibility(
                    visible = !toolbarCollapsed,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                    // Tab Counter
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (showTabsStrip) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (showTabsStrip) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showTabsStrip = !showTabsStrip },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "${tabs.size}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (showTabsStrip) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    // Viewport / Fullscreen Switcher
                    Box {
                        IconButton(
                            onClick = { showViewportDropdown = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = if (activeViewportPreset.isMobile) Icons.Default.Smartphone else if (activeViewportPreset.id == "ipad") Icons.Default.Tablet else Icons.Default.Laptop,
                                contentDescription = "Viewport: ${activeViewportPreset.name}",
                                tint = if (activeViewportPreset.widthDp > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = showViewportDropdown,
                            onDismissRequest = { showViewportDropdown = false },
                        ) {
                            ViewportPreset.ALL.forEach { preset ->
                                DropdownMenuItem(
                                    text = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            if (preset == activeViewportPreset) {
                                                Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = PocketGreen)
                                            } else {
                                                Spacer(Modifier.size(16.dp))
                                            }
                                            Text(preset.name, fontSize = 12.sp)
                                        }
                                    },
                                    onClick = {
                                        onSetViewportPreset(preset)
                                        showViewportDropdown = false
                                    },
                                )
                            }
                        }
                    }

                    // AI Visual Debugger
                    IconButton(
                        onClick = {
                            val targetUrl = currentTab.activeUrl.orEmpty().ifBlank { address }
                            val bmp = webView?.let { VisualCaptureEngine.captureWebView(it) }
                            if (bmp != null) {
                                effectiveOnSaveVisualSnapshot(bmp, "Visual Bug Snapshot", targetUrl) { snap ->
                                    aiDebuggerSnapshot = snap
                                    showAiDebuggerDialog = true
                                }
                            } else {
                                showAiDebuggerDialog = true
                            }
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Debug with AI",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    // DOM Touch Inspector Toggle
                    IconButton(
                        onClick = toggleDomInspector,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.HighlightAlt,
                            contentDescription = "Inspect Element",
                            tint = if (isInspectorActive) PocketGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    // DevTools button with live error badge
                    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        IconButton(
                            onClick = { showDevToolsSheet = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeveloperMode,
                                contentDescription = "Open DevTools",
                                tint = if (visualConsoleLogs.any { it.level == LogLevel.ERROR } || visualNetworkLogs.any { it.isFailed }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        val errCount = visualConsoleLogs.count { it.level == LogLevel.ERROR } + visualNetworkLogs.count { it.isFailed }
                        if (errCount > 0) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp),
                            ) {
                                Text(errCount.coerceAtMost(99).toString(), fontSize = 8.sp)
                            }
                        }
                    }
                    // 3-Dots Menu button (MoreVert), after the other icons in this row
                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Browser options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // Chromium 3-Dots Options Menu
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            // Desktop Site toggle
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Desktop site", modifier = Modifier.weight(1f))
                                        if (currentTab.isDesktopMode) {
                                            Icon(Icons.Default.Check, contentDescription = "Active", tint = PocketGreen, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                },
                                onClick = {
                                    showMoreMenu = false
                                    toggleDesktopSite()
                                },
                            )

                            // New Tab
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("New tab")
                                    }
                                },
                                onClick = {
                                    showMoreMenu = false
                                    openNewTab(null)
                                },
                            )

                            // Close Tab
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Close tab")
                                    }
                                },
                                onClick = {
                                    showMoreMenu = false
                                    closeTab(activeTabId)
                                },
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // Find in page
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Default.FindInPage, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Find in page")
                                    }
                                },
                                onClick = {
                                    showMoreMenu = false
                                    showFindInPage = true
                                },
                            )

                            // Bookmarks toggle
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(
                                            imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = if (isBookmarked) PocketOrange else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(if (isBookmarked) "Remove bookmark" else "Bookmark page")
                                    }
                                },
                                onClick = {
                                    showMoreMenu = false
                                    toggleBookmark()
                                },
                            )

                            // History
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("History (${historyList.size})")
                                    }
                                },
                                onClick = {
                                    showMoreMenu = false
                                    showHistorySheet = true
                                },
                            )

                            // Extensions & User Scripts
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Extensions & Scripts")
                                    }
                                },
                                onClick = {
                                    showMoreMenu = false
                                    showExtensionsModal = true
                                },
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // Zoom In & Out
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Zoom in")
                                    }
                                },
                                onClick = {
                                    webView?.zoomIn()
                                    showMoreMenu = false
                                },
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Default.ZoomOut, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Zoom out")
                                    }
                                },
                                onClick = {
                                    webView?.zoomOut()
                                    showMoreMenu = false
                                },
                            )

                            // Clear browsing data
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Clear browsing data")
                                    }
                                },
                                onClick = {
                                    webView?.clearCache(true)
                                    webView?.clearHistory()
                                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                    historyList = emptyList()
                                    Toast.makeText(context, "Browser cache and data cleared", Toast.LENGTH_SHORT).show()
                                    showMoreMenu = false
                                },
                            )

                            // CDP Port 9222 status shortcut
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Chromium CDP (Port 9222)")
                                    }
                                },
                                onClick = {
                                    showMoreMenu = false
                                    navigateTo("http://127.0.0.1:9222")
                                },
                            )

                            // Inspect UI with AI
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        Text("Inspect UI with AI")
                                    }
                                },
                                onClick = {
                                    showMoreMenu = false
                                    val target = address.ifBlank { url.orEmpty() }
                                    onAttachVisualInspection(target, "Browser UI preview at $target")
                                    Toast.makeText(context, "Attached visual preview context to Agent", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }
                    IconButton(
                        onClick = { toolbarCollapsed = true },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ExpandLess,
                            contentDescription = "Hide toolbar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                }

                // Horizontal Tabs Strip (shown when toggled or multiple tabs open)
                if (showTabsStrip || tabs.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tabs.forEach { tab ->
                            val isSelected = tab.id == activeTabId
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                ),
                                modifier = Modifier.clickable {
                                    activeTabId = tab.id
                                    address = tab.url
                                    addressError = null
                                },
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Language,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = tab.title.ifBlank { "New Tab" },
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.widthIn(max = 110.dp),
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(
                                        onClick = { closeTab(tab.id) },
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close tab",
                                            modifier = Modifier.size(11.dp),
                                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        // Add new tab '+' button
                        IconButton(
                            onClick = { openNewTab(null) },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Default.Add, "New Tab", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // Native Find in Page search bar
                if (showFindInPage) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(38.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            BasicTextField(
                                value = findQuery,
                                onValueChange = {
                                    findQuery = it
                                    if (it.isNotBlank()) {
                                        webView?.findAllAsync(it)
                                    } else {
                                        webView?.clearMatches()
                                        findMatchCurrent = 0
                                        findMatchTotal = 0
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            )
                            if (findQuery.isNotBlank()) {
                                Text(
                                    text = if (findMatchTotal > 0) "$findMatchCurrent/$findMatchTotal" else "0/0",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { webView?.findNext(false) },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, "Previous match", modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = { webView?.findNext(true) },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, "Next match", modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = {
                                    showFindInPage = false
                                    findQuery = ""
                                    webView?.clearMatches()
                                },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Default.Close, "Dismiss find", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                if (addressError != null) {
                    Text(
                        addressError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                    )
                } else if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(2.dp),
                    )
                }
            }
        }

        // Active page rendering
        val targetUrl = currentTab.activeUrl
        if (targetUrl == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = "Chromium Browser & Preview",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Multiple tabs, Desktop view, DevTools inspection, and online/local web navigation.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp,
                )
                // Standalone Systems & Web Tools Shortcuts
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Self-hosted tools",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        Text(
                            text = "Explore self-hosted systems, multi-agent harnesses, and big data datasets:",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp,
                        )

                        STANDALONE_SYSTEM_SHORTCUTS.forEach { item ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navigateTo(item.url) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                        modifier = Modifier.size(34.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = item.icon,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = item.name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                            ) {
                                                Text(
                                                    text = item.tag,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        Text(
                                            text = item.description,
                                            fontSize = 10.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = "Open",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                    }
                }

            }
        } else {
            val isCustomViewport = activeViewportPreset.widthDp > 0
            Column(Modifier.fillMaxSize()) {
                if (agentDrive != null) {
                    AgentDriveBanner(
                        tabLabel = agentDrive?.tabTitle?.ifBlank { null } ?: agentDrive?.tabUrl.orEmpty(),
                        onTakeOver = takeOverBrowserTab,
                    )
                }
                pageError?.let { error ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                            Text(
                                error,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { pageError = null; webView?.reload() }) {
                                Text("Retry", fontSize = 12.sp)
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Medium: a custom viewport wider than the screen used
                        // to be clipped with no way to pan to it.
                        .horizontalScroll(rememberScrollState())
                        .then(
                        if (isCustomViewport) Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        else Modifier
                    ),
                contentAlignment = Alignment.TopCenter,
            ) {
                Surface(
                    shape = if (isCustomViewport) RoundedCornerShape(16.dp) else RoundedCornerShape(0.dp),
                    shadowElevation = if (isCustomViewport) 6.dp else 0.dp,
                    border = if (isCustomViewport) BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                    modifier = if (isCustomViewport) {
                        Modifier
                            .padding(vertical = 8.dp)
                            .width(activeViewportPreset.widthDp.dp)
                            .fillMaxHeight()
                    } else {
                        Modifier.fillMaxSize()
                    },
                ) {
                    AndroidView(
                        factory = { ctx ->
                            if (agentDriveAllowed) {
                                runCatching { WebView.setWebContentsDebuggingEnabled(true) }
                            }
                            WebView(ctx).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        // Hardened: the browser has no legitimate use for
                        // file:// URLs or http subresources on https pages.
                        // (allowContentAccess stays on: the file chooser
                        // hands the WebView content:// URIs for uploads.)
                        settings.allowFileAccess = false
                        settings.allowContentAccess = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.cacheMode = WebSettings.LOAD_DEFAULT

                        val initialUserAgent = if (currentTab.isDesktopMode) DESKTOP_USER_AGENT else MOBILE_CHROME_USER_AGENT
                        settings.userAgentString = initialUserAgent

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                            findMatchCurrent = activeMatchOrdinal + 1
                            findMatchTotal = numberOfMatches
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loading = newProgress < 100
                            }

                            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                val msg = consoleMessage?.message() ?: return super.onConsoleMessage(consoleMessage)
                                val domElement = DomInspectorEngine.parseDomElementMessage(msg)
                                if (domElement != null) {
                                    onSetInspectedDomElement(domElement)
                                    Toast.makeText(context, "Selected ${domElement.displayName}", Toast.LENGTH_SHORT).show()
                                    return true
                                }
                                val consoleLog = DomInspectorEngine.parseConsoleMessage(msg)
                                if (consoleLog != null) {
                                    onRecordConsoleLog(consoleLog)
                                    return true
                                }
                                val networkLog = DomInspectorEngine.parseNetworkMessage(msg)
                                if (networkLog != null) {
                                    onRecordNetworkLog(networkLog)
                                    return true
                                }
                                if (msg.startsWith("MH_DOM_INSPECT:")) {
                                    val inspected = msg.removePrefix("MH_DOM_INSPECT:").trim()
                                    Toast.makeText(context, inspected, Toast.LENGTH_SHORT).show()
                                    return true
                                }
                                return super.onConsoleMessage(consoleMessage)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val target = request?.url ?: return false
                                val scheme = target.scheme?.lowercase()
                                if (scheme == "http" || scheme == "https" || scheme == "about") {
                                    val targetStr = target.toString()
                                    address = targetStr
                                    lastLoadedUrl = targetStr
                                    tabs = tabs.map {
                                        if (it.id == activeTabId) it.copy(url = targetStr, activeUrl = targetStr) else it
                                    }
                                    return false
                                }
                                // Only well-known safe schemes may leave the
                                // WebView. intent:// and other exotic schemes
                                // used to fire ACTION_VIEW at arbitrary app
                                // components; they are now blocked.
                                if (scheme == "tel" || scheme == "mailto" || scheme == "sms") {
                                    return try {
                                        val intent = Intent(Intent.ACTION_VIEW, target)
                                        context.startActivity(intent)
                                        true
                                    } catch (_: Exception) {
                                        false
                                    }
                                }
                                return true
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                pageError = null
                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                super.onReceivedError(view, request, error)
                                // Main-frame failures only: a broken image or
                                // tracker must not blank the page with a banner.
                                if (request?.isForMainFrame == true) {
                                    pageError = "Could not load this page: ${error?.description ?: "Network error"}"
                                }
                                if (request?.url != null) {
                                    onRecordNetworkLog(
                                        NetworkLogEntry(
                                            url = request.url.toString(),
                                            method = request.method ?: "GET",
                                            error = error?.description?.toString() ?: "Network error",
                                            isFailed = true,
                                        )
                                    )
                                }
                            }

                            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                                super.onReceivedHttpError(view, request, errorResponse)
                                if (request?.isForMainFrame == true) {
                                    pageError = "Page failed to load (HTTP ${errorResponse?.statusCode ?: "error"})"
                                }
                                if (request?.url != null) {
                                    onRecordNetworkLog(
                                        NetworkLogEntry(
                                            url = request.url.toString(),
                                            method = request.method ?: "GET",
                                            statusCode = errorResponse?.statusCode,
                                            statusText = errorResponse?.reasonPhrase,
                                            isFailed = true,
                                        )
                                    )
                                }
                            }

                            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                super.onPageFinished(view, finishedUrl)
                                view?.evaluateJavascript(DomInspectorEngine.buildDiagnosticsInterceptorScript(), null)
                                if (isInspectorActive) {
                                    view?.evaluateJavascript(DomInspectorEngine.buildInspectorScript(true), null)
                                }
                                if (!finishedUrl.isNullOrBlank() && finishedUrl != "about:blank") {
                                    address = finishedUrl
                                    lastLoadedUrl = finishedUrl
                                    val title = view?.title.orEmpty().ifBlank { finishedUrl }
                                    tabs = tabs.map {
                                        if (it.id == activeTabId) it.copy(
                                            url = finishedUrl,
                                            activeUrl = finishedUrl,
                                            title = title.take(28),
                                            canGoBack = view?.canGoBack() == true,
                                            canGoForward = view?.canGoForward() == true,
                                        ) else it
                                    }
                                    if (historyList.firstOrNull()?.url != finishedUrl) {
                                        historyList = (listOf(BrowserHistoryItem(title, finishedUrl)) + historyList).take(50)
                                    }
                                }
                                canGoBack.value = view?.canGoBack() == true
                            }
                        }
                        lastLoadedUrl = targetUrl
                        loadUrl(targetUrl)
                    }
                },
                update = { current ->
                    webView = current
                    val desiredUserAgent = if (currentTab.isDesktopMode) DESKTOP_USER_AGENT else MOBILE_CHROME_USER_AGENT
                    if (current.settings.userAgentString != desiredUserAgent) {
                        current.settings.userAgentString = desiredUserAgent
                    }
                    if (targetUrl != lastLoadedUrl) {
                        lastLoadedUrl = targetUrl
                        current.loadUrl(targetUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
                }
                if (agentDrive != null) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(takeOverBrowserTab) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    takeOverBrowserTab()
                                }
                            },
                    )
                }
            }
        }
    }
}

    // History & Bookmarks Modal Sheet
    if (showHistorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showHistorySheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Browsing History", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (historyList.isNotEmpty()) {
                        TextButton(onClick = { historyList = emptyList() }) {
                            Text("Clear all", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (bookmarksList.isNotEmpty()) {
                    Text("Bookmarks", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = PocketGreen, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    bookmarksList.forEach { bm ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showHistorySheet = false
                                    navigateTo(bm.url)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = PocketOrange, modifier = Modifier.size(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(bm.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(bm.url, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                if (historyList.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No recent history", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                        items(historyList, key = { "${it.url}#${it.timestamp}" }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showHistorySheet = false
                                        navigateTo(item.url)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.url, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Extensions & User Scripts Modal
    if (showExtensionsModal) {
        AlertDialog(
            onDismissRequest = {
                showExtensionsModal = false
                scriptOutputText = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Extensions & Scripts")
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Inject developer tools, user styles, or automated scripts into the active page.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 1. Eruda Mobile DevTools
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Eruda Mobile DevTools", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Inspect element, DOM, console, & network", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(
                                onClick = {
                                    val erudaJs = """
                                        (function() {
                                            if (window.eruda) { window.eruda.show(); return; }
                                            var s = document.createElement('script');
                                            s.src = 'https://cdn.jsdelivr.net/npm/eruda';
                                            s.onload = function() { eruda.init(); eruda.show(); };
                                            document.body.appendChild(s);
                                        })();
                                    """.trimIndent()
                                    webView?.evaluateJavascript(erudaJs, null)
                                    Toast.makeText(context, "Eruda DevTools injected", Toast.LENGTH_SHORT).show()
                                    showExtensionsModal = false
                                },
                            ) {
                                Text("Inject", fontSize = 11.sp)
                            }
                        }
                    }

                    // Mobile Touch DOM Inspector
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("DOM Element Inspector", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Tap any page element to inspect tags & classes", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(
                                onClick = {
                                    toggleDomInspector()
                                    showExtensionsModal = false
                                },
                            ) {
                                Text(if (isInspectorActive) "Stop" else "Inspect", fontSize = 11.sp)
                            }
                        }
                    }

                    // 2. Dark Reader Mode
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Dark Reader CSS", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Inverts & styles page into dark mode", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(
                                onClick = {
                                    val darkJs = """
                                        (function() {
                                            var el = document.getElementById('mhe-dark-mode');
                                            if (el) { el.remove(); }
                                            else {
                                                var s = document.createElement('style');
                                                s.id = 'mhe-dark-mode';
                                                s.innerHTML = 'html { filter: invert(90%) hue-rotate(180deg) !important; background: #121212 !important; } img, video, canvas { filter: invert(100%) hue-rotate(180deg) !important; }';
                                                document.head.appendChild(s);
                                            }
                                        })();
                                    """.trimIndent()
                                    webView?.evaluateJavascript(darkJs, null)
                                    Toast.makeText(context, "Dark mode style toggled", Toast.LENGTH_SHORT).show()
                                    showExtensionsModal = false
                                },
                            ) {
                                Text("Toggle", fontSize = 11.sp)
                            }
                        }
                    }

                    // 3. Custom JavaScript Evaluator
                    Text("Run Custom Script:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    BasicTextField(
                        value = customScriptText,
                        onValueChange = { customScriptText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface),
                    )

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = {
                                if (customScriptText.isNotBlank()) {
                                    webView?.evaluateJavascript(customScriptText) { result ->
                                        scriptOutputText = result
                                    }
                                }
                            },
                        ) {
                            Text("Execute JS", fontSize = 11.sp)
                        }
                    }

                    if (scriptOutputText != null) {
                        Text("Output: $scriptOutputText", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = PocketGreen)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showExtensionsModal = false
                    scriptOutputText = null
                }) {
                    Text("Close")
                }
            },
        )
    }

    if (showDevToolsSheet) {
        VisualDevToolsSheet(
            consoleLogs = visualConsoleLogs,
            networkLogs = visualNetworkLogs,
            inspectedElement = inspectedDomElement,
            isInspectorActive = isInspectorActive,
            onToggleInspector = { active ->
                isInspectorActive = active
                webView?.evaluateJavascript(DomInspectorEngine.buildInspectorScript(active), null)
            },
            detectedPorts = detectedPortInfos,
            onRescanPorts = { onScanPorts {} },
            onOpenPort = { port ->
                navigateTo("http://localhost:$port")
                showDevToolsSheet = false
            },
            onAddCustomPort = { _ ->
                onScanPorts {}
            },
            snapshots = visualSnapshots,
            comparisonPair = visualComparisonPair,
            onCaptureSnapshot = { title ->
                val targetUrl = currentTab.activeUrl.orEmpty().ifBlank { address }
                val bmp = webView?.let { VisualCaptureEngine.captureWebView(it) }
                if (bmp != null) {
                    effectiveOnSaveVisualSnapshot(bmp, title, targetUrl) { snap ->
                        Toast.makeText(context, "Captured snapshot: ${snap.title}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSetBaseline = onSetBaselineSnapshot,
            onSetComparison = onSetComparisonSnapshot,
            onLaunchDiffViewer = {
                showDevToolsSheet = false
                showDiffViewerDialog = true
            },
            onClearLogs = onClearVisualLogs,
            onDismiss = { showDevToolsSheet = false },
        )
    }

    if (showDiffViewerDialog && projectRoot != null && visualComparisonPair != null) {
        VisualComparisonViewer(
            projectRoot = projectRoot,
            pair = visualComparisonPair,
            onDismiss = { showDiffViewerDialog = false },
        )
    }

    if (showAiDebuggerDialog) {
        AlertDialog(
            onDismissRequest = { showAiDebuggerDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        val targetUrl = currentTab.activeUrl.orEmpty().ifBlank { address }
                        onDispatchAiVisualBugReport(targetUrl, aiDebuggerNotes, aiDebuggerSnapshot) {
                            showAiDebuggerDialog = false
                            Toast.makeText(context, "Visual bug report dispatched to AI Agent", Toast.LENGTH_SHORT).show()
                            onRequestSwitchToChat()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PocketGreen),
                ) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Send to AI Agent")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAiDebuggerDialog = false }) {
                    Text("Cancel")
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AutoAwesome, null, tint = PocketGreen)
                    Text("AI Visual Debugger", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Target: ${currentTab.activeUrl ?: address}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (inspectedDomElement != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(6.dp)) {
                                Text("Selected: ${inspectedDomElement.displayName}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PocketGreen)
                                Text("Selector: ${inspectedDomElement.selector}", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    val errCount = visualConsoleLogs.count { it.level == LogLevel.ERROR }
                    val netFailCount = visualNetworkLogs.count { it.isFailed }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (errCount > 0) {
                            Text("$errCount console error(s)", fontSize = 11.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        }
                        if (netFailCount > 0) {
                            Text("$netFailCount failed request(s)", fontSize = 11.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        }
                        if (errCount == 0 && netFailCount == 0) {
                            Text("No runtime errors detected", fontSize = 11.sp, color = PocketGreen)
                        }
                    }
                    if (aiDebuggerSnapshot != null) {
                        Text("Screenshot: ${aiDebuggerSnapshot?.screenshotPath}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Describe problem or instruction for AI:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = aiDebuggerNotes,
                        onValueChange = { aiDebuggerNotes = it },
                        placeholder = { Text("e.g. Fix button height and resolve 404 endpoint", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().height(90.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                    )
                }
            },
        )
    }

        if (toolbarCollapsed) {
            Surface(
                onClick = { toolbarCollapsed = false },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 12.dp)
                    .size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = "Show toolbar",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private fun normalizePreviewUrl(input: String): String? {
    val raw = input.trim()
    if (raw.isBlank()) return null

    // Support entering just port number e.g. "3000" or ":3000"
    val portCandidate = raw.removePrefix(":")
    if (portCandidate.isNotEmpty() && portCandidate.all { it.isDigit() }) {
        val port = portCandidate.toIntOrNull()
        if (port != null && port in 1..65535) {
            return "http://localhost:$port"
        }
    }

    val hasScheme = raw.startsWith("http://", ignoreCase = true) ||
        raw.startsWith("https://", ignoreCase = true) ||
        raw.startsWith("about:", ignoreCase = true) ||
        raw.startsWith("file://", ignoreCase = true)

    val candidate = if (hasScheme) {
        raw
    } else {
        val lower = raw.lowercase()
        if (lower.startsWith("localhost") || lower.startsWith("127.0.0.1") || lower.startsWith("0.0.0.0")) {
            "http://$raw"
        } else {
            "https://$raw"
        }
    }

    val parsed = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
    val host = parsed.host
    if (host.isNullOrBlank() && !candidate.startsWith("about:")) return null

    if (host == "0.0.0.0") {
        return parsed.buildUpon().encodedAuthority(
            buildString {
                append("127.0.0.1")
                if (parsed.port >= 0) append(":${parsed.port}")
            },
        ).build().toString()
    }
    return parsed.toString()
}

