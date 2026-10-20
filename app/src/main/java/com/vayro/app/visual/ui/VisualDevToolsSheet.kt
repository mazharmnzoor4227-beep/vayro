package com.vayro.app.visual.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.ui.theme.PocketGreen
import com.vayro.app.ui.theme.PocketOrange
import com.vayro.app.visual.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DevToolsTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    CONSOLE("Console", Icons.Default.Terminal),
    NETWORK("Network", Icons.Default.Wifi),
    ELEMENTS("Elements", Icons.Default.Code),
    PORTS("Ports", Icons.Default.Lan),
    COMPARE("Compare", Icons.Default.Compare),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualDevToolsSheet(
    consoleLogs: List<ConsoleLogEntry>,
    networkLogs: List<NetworkLogEntry>,
    inspectedElement: DomElementInfo?,
    isInspectorActive: Boolean,
    onToggleInspector: (Boolean) -> Unit,
    detectedPorts: List<LocalhostPortInfo>,
    onRescanPorts: () -> Unit,
    onOpenPort: (Int) -> Unit,
    onAddCustomPort: (Int) -> Unit,
    snapshots: List<VisualSnapshot>,
    comparisonPair: VisualComparisonPair?,
    onCaptureSnapshot: (String) -> Unit,
    onSetBaseline: (VisualSnapshot) -> Unit,
    onSetComparison: (VisualSnapshot) -> Unit,
    onLaunchDiffViewer: () -> Unit,
    onClearLogs: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(DevToolsTab.CONSOLE) }
    var consoleSearch by remember { mutableStateOf("") }
    var selectedLogLevel by remember { mutableStateOf<LogLevel?>(null) }
    var customPortInput by remember { mutableStateOf("") }

    val clipboardManager = LocalClipboardManager.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.fillMaxHeight(0.85f),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            // Header bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.DeveloperMode, null, tint = PocketGreen)
                    Text("Embedded DevTools", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onClearLogs) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear logs", Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", Modifier.size(18.dp))
                    }
                }
            }

            // Tab navigation bar
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                edgePadding = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                DevToolsTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(tab.icon, null, Modifier.size(14.dp))
                                Text(tab.label, fontSize = 12.sp)
                                if (tab == DevToolsTab.CONSOLE) {
                                    val errCount = consoleLogs.count { it.level == LogLevel.ERROR }
                                    if (errCount > 0) {
                                        Badge(containerColor = MaterialTheme.colorScheme.error) {
                                            Text(errCount.toString(), fontSize = 9.sp)
                                        }
                                    }
                                }
                                if (tab == DevToolsTab.NETWORK) {
                                    val failCount = networkLogs.count { it.isFailed }
                                    if (failCount > 0) {
                                        Badge(containerColor = MaterialTheme.colorScheme.error) {
                                            Text(failCount.toString(), fontSize = 9.sp)
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Tab Content
            when (selectedTab) {
                DevToolsTab.CONSOLE -> {
                    // Filter bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = consoleSearch,
                            onValueChange = { consoleSearch = it },
                            placeholder = { Text("Filter console...", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f).height(44.dp),
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                            singleLine = true,
                        )
                        FilterChip(
                            selected = selectedLogLevel == null,
                            onClick = { selectedLogLevel = null },
                            label = { Text("All", fontSize = 10.sp) },
                        )
                        FilterChip(
                            selected = selectedLogLevel == LogLevel.ERROR,
                            onClick = { selectedLogLevel = if (selectedLogLevel == LogLevel.ERROR) null else LogLevel.ERROR },
                            label = { Text("Errors", fontSize = 10.sp, color = MaterialTheme.colorScheme.error) },
                        )
                        FilterChip(
                            selected = selectedLogLevel == LogLevel.WARN,
                            onClick = { selectedLogLevel = if (selectedLogLevel == LogLevel.WARN) null else LogLevel.WARN },
                            label = { Text("Warn", fontSize = 10.sp, color = PocketOrange) },
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    val filteredLogs = remember(consoleLogs, consoleSearch, selectedLogLevel) {
                        consoleLogs.filter { log ->
                            val matchesLevel = selectedLogLevel == null || log.level == selectedLogLevel
                            val matchesSearch = consoleSearch.isBlank() || log.message.contains(consoleSearch, ignoreCase = true)
                            matchesLevel && matchesSearch
                        }
                    }

                    if (filteredLogs.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No console logs captured", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(filteredLogs, key = { it.id }) { log ->
                                val (bgTint, textTint) = when (log.level) {
                                    LogLevel.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) to MaterialTheme.colorScheme.error
                                    LogLevel.WARN -> PocketOrange.copy(alpha = 0.15f) to PocketOrange
                                    LogLevel.DEBUG -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f) to MaterialTheme.colorScheme.onSurfaceVariant
                                    LogLevel.INFO -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
                                }
                                Surface(
                                    color = bgTint,
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(0.5.dp, textTint.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    SelectionContainer {
                                        Column(Modifier.padding(6.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Text(
                                                    "[${log.level.name}] ${timeFormat.format(Date(log.timestamp))}",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp,
                                                    color = textTint,
                                                )
                                                if (!log.source.isNullOrBlank()) {
                                                    Text(
                                                        "${log.source}${if (log.lineNumber != null) ":${log.lineNumber}" else ""}",
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                            Text(
                                                log.message,
                                                fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                DevToolsTab.NETWORK -> {
                    if (networkLogs.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No network requests captured yet", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(networkLogs, key = { it.id }) { net ->
                                val statusColor = when {
                                    net.isFailed -> MaterialTheme.colorScheme.error
                                    net.statusCode != null && net.statusCode in 200..299 -> PocketGreen
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(0.5.dp, statusColor.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(Modifier.padding(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Surface(
                                                    color = statusColor.copy(alpha = 0.15f),
                                                    shape = RoundedCornerShape(4.dp),
                                                ) {
                                                    Text(
                                                        net.method,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 10.sp,
                                                        color = statusColor,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                    )
                                                }
                                                Text(
                                                    net.statusCode?.toString() ?: (if (net.isFailed) "FAILED" else "..."),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    color = statusColor,
                                                )
                                            }
                                            Text("${net.durationMs}ms", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            net.url,
                                            fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                                            fontSize = 11.sp,
                                            maxLines = 2,
                                        )
                                        if (!net.error.isNullOrBlank()) {
                                            Text(
                                                "Error: ${net.error}",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                DevToolsTab.ELEMENTS -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Toggle inspector banner
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isInspectorActive) PocketGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            border = BorderStroke(1.dp, if (isInspectorActive) PocketGreen else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Touch DOM Inspector", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(
                                        if (isInspectorActive) "Active: Tap elements in preview to inspect" else "Tap Inspect to enable element selection",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(
                                    onClick = { onToggleInspector(!isInspectorActive) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isInspectorActive) MaterialTheme.colorScheme.error else PocketGreen,
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Text(if (isInspectorActive) "Stop" else "Inspect", fontSize = 11.sp)
                                }
                            }
                        }

                        if (inspectedElement == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No DOM element selected yet", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(inspectedElement.displayName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = PocketGreen)
                                                IconButton(
                                                    onClick = {
                                                        clipboardManager.setText(AnnotatedString(inspectedElement.selector))
                                                    },
                                                    modifier = Modifier.size(48.dp),
                                                ) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy selector", Modifier.size(14.dp))
                                                }
                                            }
                                            Text("Selector: ${inspectedElement.selector}", fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily, fontSize = 11.sp)
                                            if (inspectedElement.innerText.isNotBlank()) {
                                                Spacer(Modifier.height(4.dp))
                                                Text("Text: \"${inspectedElement.innerText}\"", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                "Bounds: ${inspectedElement.rect.width.toInt()}x${inspectedElement.rect.height.toInt()} px at (${inspectedElement.rect.left.toInt()}, ${inspectedElement.rect.top.toInt()})",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }

                                if (inspectedElement.computedStyles.isNotEmpty()) {
                                    item {
                                        Text("Computed Styles", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(Modifier.padding(8.dp)) {
                                                inspectedElement.computedStyles.forEach { (k, v) ->
                                                    if (v.isNotBlank()) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                        ) {
                                                            Text(k, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                            Text(v, fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                DevToolsTab.PORTS -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Scan & Custom Port Input
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = customPortInput,
                                onValueChange = { customPortInput = it },
                                placeholder = { Text("Port (e.g. 8000)", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f).height(44.dp),
                                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                                singleLine = true,
                            )
                            Button(
                                onClick = {
                                    val p = customPortInput.trim().toIntOrNull()
                                    if (p != null && p in 1..65535) {
                                        onAddCustomPort(p)
                                        customPortInput = ""
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text("Add", fontSize = 11.sp)
                            }
                            IconButton(onClick = onRescanPorts) {
                                Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                            }
                        }

                        if (detectedPorts.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No open localhost ports discovered", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(detectedPorts, key = { it.port }) { portInfo ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Box(Modifier.size(8.dp).background(PocketGreen, CircleShape))
                                                    Text("Port ${portInfo.port}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                    Text("${portInfo.responseTimeMs}ms", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                Text(portInfo.serviceGuess, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Button(
                                                onClick = { onOpenPort(portInfo.port) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            ) {
                                                Text("Open", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                DevToolsTab.COMPARE -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Button(
                                onClick = { onCaptureSnapshot("Snapshot #${snapshots.size + 1}") },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Icon(Icons.Default.CameraAlt, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Capture Viewport", fontSize = 11.sp)
                            }
                            if (comparisonPair != null) {
                                OutlinedButton(
                                    onClick = onLaunchDiffViewer,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    Icon(Icons.Default.Compare, null, Modifier.size(14.dp), tint = PocketGreen)
                                    Spacer(Modifier.width(4.dp))
                                    Text("View Diff", fontSize = 11.sp)
                                }
                            }
                        }

                        if (snapshots.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Capture snapshots to compare UI changes before and after edits", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(snapshots, key = { it.id }) { snap ->
                                    val isBefore = comparisonPair?.beforeSnapshot?.id == snap.id
                                    val isAfter = comparisonPair?.afterSnapshot?.id == snap.id
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isBefore) MaterialTheme.colorScheme.error else if (isAfter) PocketGreen else MaterialTheme.colorScheme.outlineVariant,
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(snap.title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                    if (isBefore) {
                                                        Badge(containerColor = MaterialTheme.colorScheme.error) { Text("BEFORE", fontSize = 9.sp) }
                                                    }
                                                    if (isAfter) {
                                                        Badge(containerColor = PocketGreen) { Text("AFTER", fontSize = 9.sp) }
                                                    }
                                                }
                                                Text(timeFormat.format(Date(snap.timestamp)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text("Errors: ${snap.consoleErrorsCount} | Path: ${snap.screenshotPath}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                TextButton(onClick = { onSetBaseline(snap) }) {
                                                    Text("Baseline", fontSize = 10.sp)
                                                }
                                                TextButton(onClick = { onSetComparison(snap) }) {
                                                    Text("Compare", fontSize = 10.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
