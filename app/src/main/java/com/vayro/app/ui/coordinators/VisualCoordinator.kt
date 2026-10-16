package com.vayro.app.ui.coordinators

import com.vayro.app.model.Project
import com.vayro.app.visual.AiVisualDebugger
import com.vayro.app.visual.ConsoleLogEntry
import com.vayro.app.visual.DomElementInfo
import com.vayro.app.visual.LocalhostPortInfo
import com.vayro.app.visual.LocalhostPortManager
import com.vayro.app.visual.LogLevel
import com.vayro.app.visual.NetworkLogEntry
import com.vayro.app.visual.ViewportPreset
import com.vayro.app.visual.VisualCaptureEngine
import com.vayro.app.visual.VisualComparisonPair
import com.vayro.app.visual.VisualSnapshot
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Visual-inspection domain coordinator (Milestone 1).
 *
 * Explicit owner of the visual-inspection state machine: console/network log
 * buffers, inspected DOM element, snapshots, comparison pair, viewport preset
 * and localhost port detection. Port scanning and snapshot capture are real
 * operations; AI bug-report dispatch routes through the agent chat core via
 * the injected [sendPrompt] callback.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class VisualCoordinator(
    private val host: CoordinatorHost,
    private val workspaceRootFor: (Project) -> File,
    private val sendPrompt: (String) -> Unit,
) {
    private val portManager = LocalhostPortManager()

    private val _visualConsoleLogs = MutableStateFlow<List<ConsoleLogEntry>>(emptyList())
    val visualConsoleLogs: StateFlow<List<ConsoleLogEntry>> = _visualConsoleLogs.asStateFlow()

    private val _visualNetworkLogs = MutableStateFlow<List<NetworkLogEntry>>(emptyList())
    val visualNetworkLogs: StateFlow<List<NetworkLogEntry>> = _visualNetworkLogs.asStateFlow()

    private val _inspectedDomElement = MutableStateFlow<DomElementInfo?>(null)
    val inspectedDomElement: StateFlow<DomElementInfo?> = _inspectedDomElement.asStateFlow()

    private val _visualSnapshots = MutableStateFlow<List<VisualSnapshot>>(emptyList())
    val visualSnapshots: StateFlow<List<VisualSnapshot>> = _visualSnapshots.asStateFlow()

    private val _visualComparisonPair = MutableStateFlow<VisualComparisonPair?>(null)
    val visualComparisonPair: StateFlow<VisualComparisonPair?> = _visualComparisonPair.asStateFlow()

    private val _activeViewportPreset = MutableStateFlow(ViewportPreset.RESPONSIVE)
    val activeViewportPreset: StateFlow<ViewportPreset> = _activeViewportPreset.asStateFlow()

    private val _detectedPortInfos = MutableStateFlow<List<LocalhostPortInfo>>(emptyList())
    val detectedPortInfos: StateFlow<List<LocalhostPortInfo>> = _detectedPortInfos.asStateFlow()

    private val _detectedLocalhostPorts = MutableStateFlow<List<Int>>(emptyList())
    val detectedLocalhostPorts: StateFlow<List<Int>> = _detectedLocalhostPorts.asStateFlow()

    fun recordVisualConsoleLog(entry: ConsoleLogEntry) {
        _visualConsoleLogs.update { (listOf(entry) + it).take(200) }
    }

    fun recordVisualNetworkLog(entry: NetworkLogEntry) {
        _visualNetworkLogs.update { (listOf(entry) + it).take(100) }
    }

    fun setInspectedDomElement(element: DomElementInfo?) {
        _inspectedDomElement.value = element
    }

    fun clearVisualLogs() {
        _visualConsoleLogs.value = emptyList()
        _visualNetworkLogs.value = emptyList()
    }

    fun setViewportPreset(preset: ViewportPreset) {
        _activeViewportPreset.value = preset
    }

    fun scanActiveLocalhostPorts(onComplete: (List<Int>) -> Unit = {}) {
        host.scope.launch(Dispatchers.IO) {
            val commonPorts = listOf(3000, 5173, 8080, 8000, 4200, 5000, 9222)
            val openPorts = mutableListOf<Int>()
            for (port in commonPorts) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                        openPorts.add(port)
                    }
                } catch (_: Exception) {
                }
            }
            _detectedLocalhostPorts.value = openPorts
            withContext(Dispatchers.Main) {
                onComplete(openPorts)
            }
        }
    }

    fun scanLocalhostPortsWithHeuristics(
        customPorts: List<Int> = emptyList(),
        onComplete: (List<LocalhostPortInfo>) -> Unit = {},
    ) {
        host.scope.launch {
            val infos = portManager.scanPorts(customPorts)
            _detectedPortInfos.value = infos
            _detectedLocalhostPorts.value = infos.map { it.port }
            withContext(Dispatchers.Main) {
                onComplete(infos)
            }
        }
    }

    fun saveVisualSnapshot(
        bitmap: android.graphics.Bitmap,
        title: String,
        url: String,
        onSaved: (VisualSnapshot) -> Unit = {},
    ) {
        val project = host.state().activeProject ?: return
        val workspace = workspaceRootFor(project)
        host.scope.launch {
            val snapshot = VisualCaptureEngine.saveSnapshot(
                projectRoot = workspace,
                bitmap = bitmap,
                title = title,
                url = url,
                consoleErrorsCount = _visualConsoleLogs.value.count { it.level == LogLevel.ERROR },
                networkErrorsCount = _visualNetworkLogs.value.count { it.isFailed },
                domSummary = _inspectedDomElement.value?.displayName,
            )
            _visualSnapshots.update { listOf(snapshot) + it }
            withContext(Dispatchers.Main) {
                onSaved(snapshot)
            }
        }
    }

    fun setBaselineComparisonSnapshot(snapshot: VisualSnapshot) {
        val current = _visualComparisonPair.value
        _visualComparisonPair.value = if (current != null) {
            current.copy(beforeSnapshot = snapshot)
        } else {
            VisualComparisonPair(
                beforeSnapshot = snapshot,
                afterSnapshot = snapshot,
                title = "UI Comparison",
            )
        }
    }

    fun setAfterComparisonSnapshot(snapshot: VisualSnapshot) {
        val current = _visualComparisonPair.value
        _visualComparisonPair.value = if (current != null) {
            current.copy(afterSnapshot = snapshot)
        } else {
            VisualComparisonPair(
                beforeSnapshot = snapshot,
                afterSnapshot = snapshot,
                title = "UI Comparison",
            )
        }
    }

    fun dispatchAiVisualBugReport(
        url: String,
        userNotes: String = "",
        snapshot: VisualSnapshot? = null,
        onDispatched: () -> Unit = {},
    ) {
        val project = host.state().activeProject ?: return
        val workspace = workspaceRootFor(project)

        val prompt = AiVisualDebugger.generateBugReportPrompt(
            url = url,
            viewport = _activeViewportPreset.value,
            selectedElement = _inspectedDomElement.value,
            consoleLogs = _visualConsoleLogs.value,
            networkLogs = _visualNetworkLogs.value,
            snapshot = snapshot,
            userNotes = userNotes,
        )

        if (snapshot != null) {
            val attachment = AiVisualDebugger.createChatAttachment(snapshot, workspace)
            if (attachment != null) {
                host.updateState { it.copy(pendingAttachments = it.pendingAttachments + attachment) }
            }
        }

        sendPrompt(prompt)
        onDispatched()
    }

    fun attachVisualInspectionToAgent(url: String, note: String = "") {
        val prompt = "Visual Inspection Report for $url: ${note.ifBlank { "Please inspect this web page layout and verify all components are rendering properly." }}"
        sendPrompt(prompt)
    }
}
