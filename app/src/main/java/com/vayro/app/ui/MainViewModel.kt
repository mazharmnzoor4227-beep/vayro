package com.vayro.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.SystemClock
import android.os.Build
import android.system.Os
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.vayro.app.files.SharedExport
import com.vayro.app.BuildConfig
import com.vayro.app.AppCrashLogger
import com.vayro.app.data.ApiKeyCredential
import com.vayro.app.data.ApiKeyVault
import com.vayro.app.data.ApiKeyFailoverCoordinator
import com.vayro.app.network.ChatApiException
import com.vayro.app.data.ApiKeyInfo
import com.vayro.app.data.AppPreferences
import com.vayro.app.core.result.OperationResult
import com.vayro.app.core.result.OperationStatus
import com.vayro.app.model.ActivityItem
import com.vayro.app.model.AgentKind
import com.vayro.app.model.ChangeItem
import com.vayro.app.model.ChatMessage
import com.vayro.app.model.ChatAttachment
import com.vayro.app.model.ExecutionMode
import com.vayro.app.model.ActiveRoadmap
import com.vayro.app.model.RoadmapComment
import com.vayro.app.model.RoadmapStep
import com.vayro.app.model.ScratchpadItem
import com.vayro.app.model.StepStatus
import com.vayro.app.model.TaskStatus
import com.vayro.app.model.DevStack
import com.vayro.app.model.Project
import com.vayro.app.model.ProjectKind
import com.vayro.app.model.ProjectChat
import com.vayro.app.model.ProviderKind
import com.vayro.app.model.ProviderProfile
import com.vayro.app.model.RuntimeEvent
import com.vayro.app.model.ToolRequest
import com.vayro.app.model.WorkspaceEntry
import com.vayro.app.model.projectSlug
import com.vayro.app.model.generateQuickChatIdentity
import com.vayro.app.model.providerProtocolForAgent
import com.vayro.app.network.ConnectionValidation
import com.vayro.app.network.ModelDiscoveryResult
import com.vayro.app.network.ProviderApiClient
import com.vayro.app.network.GitHubRepository
import com.vayro.app.runtime.ClaudeRuntimeBridge
import com.vayro.app.runtime.CodexRuntimeBridge
import com.vayro.app.runtime.CustomAgentBridge
import com.vayro.app.runtime.DshRuntimeBridge
import com.vayro.app.runtime.AgentRegistry
import com.vayro.app.runtime.AgentUpdateInfo
import com.vayro.app.runtime.AntigravityAuthController
import com.vayro.app.runtime.AntigravityAuthState
import com.vayro.app.runtime.AntigravityAuthStatus
import com.vayro.app.runtime.AntigravityRuntimeBridge
import com.vayro.app.runtime.NativeSpawnProcess
import com.vayro.app.runtime.RuntimeInstallProgress
import com.vayro.app.runtime.RuntimeInstallEvent
import com.vayro.app.runtime.RuntimeInstaller
import com.vayro.app.runtime.RuntimeSetupController
import com.vayro.app.runtime.RuntimeSetupService
import com.vayro.app.runtime.readTailText
import com.vayro.app.runtime.truncateHeadAndTail
import com.vayro.app.runtime.supportsArm64Runtime
import com.vayro.app.runtime.RuntimeFailureClassifier
import com.vayro.app.runtime.WorkspaceCheckpoints
import com.vayro.app.runtime.ProjectIndex
import com.vayro.app.runtime.ContextEngine
import com.vayro.app.runtime.RuntimeRetryPolicy
import com.vayro.app.runtime.SkillInfo
import com.vayro.app.runtime.SkillScanReport
import com.vayro.app.runtime.SkillManager
import com.vayro.app.runtime.SkillSearchResult
import com.vayro.app.runtime.AndroidAppInstaller
import com.vayro.app.runtime.NotificationCoordinator
import com.vayro.app.runtime.ProjectBridgePool
import com.vayro.app.runtime.RuntimeExecutionService
import com.vayro.app.runtime.formatDurationText
import com.vayro.app.MobileHarnessApplication
import com.vayro.app.update.AppUpdateInfo
import com.vayro.app.update.AppUpdater
import java.io.File
import java.io.RandomAccessFile
import java.net.UnknownHostException
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import com.vayro.app.ui.coordinators.isClaudeRuntimeMetadata
import com.vayro.app.ui.coordinators.guestRootFor
import org.json.JSONArray
import org.json.JSONObject

enum class StartupStage { CHECKING, SETUP_REQUIRED, INSTALLING, MODEL_SETUP, INITIALIZING, READY, ERROR }

enum class ApiPingStatus { IDLE, PINGING, OK, FAILED }
enum class AppUpdateStatus { AVAILABLE, PERMISSION_REQUIRED, DOWNLOADING, INSTALLING, ERROR }
enum class GitHubAuthStatus { DISCONNECTED, STARTING, AWAITING_USER, CONNECTED, ERROR }

data class TerminalOutputLine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val command: String,
    val output: String,
    val exitCode: Int = 0,
)

data class TerminalSessionTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val isRunning: Boolean = false,
    val cwd: String = "/workspace",
    val lines: List<TerminalOutputLine> = emptyList(),
)

private val ANSI_TERMINAL_SEQUENCE = Regex("\\u001B(?:\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")

internal fun sanitizeTerminalOutput(text: String): String = text
    .replace(ANSI_TERMINAL_SEQUENCE, "")
    .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }

private val ANTIGRAVITY_MODEL_EFFORT = Regex("^(.*)-(low|medium|high)$")

private fun antigravityEffortFromModel(model: String): String? =
    ANTIGRAVITY_MODEL_EFFORT.matchEntire(model)?.groupValues?.get(2)

private fun antigravityModelWithEffort(model: String, effort: String): String? {
    val match = ANTIGRAVITY_MODEL_EFFORT.matchEntire(model) ?: return null
    return "${match.groupValues[1]}-$effort"
}

private data class RuntimeRetryRequest(
    val runtime: com.vayro.app.runtime.RuntimeBridge,
    val project: Project,
    val prompt: String,
    val history: List<ChatMessage>,
    val provider: ProviderProfile,
)

private data class TranscriptWrite(
    val projectId: String,
    val chatId: String,
    val messages: List<ChatMessage>,
)

/**
 * QA H29: the startup cleanup policy — a quick project is empty (and
 * safe to delete) only when it has no user messages AND no user files.
 * Non-quick projects are never cleaned. Pure function so the policy is
 * unit-tested directly; the scan itself needs disk I/O.
 */
internal fun isEmptyQuickProject(kind: ProjectKind, userMessages: Int, userFiles: Int): Boolean =
    kind == ProjectKind.QUICK_PROJECT && userMessages == 0 && userFiles == 0

/**
 * QA Medium: the agent-update-check message. A failed check must never
 * report "up to date": with no updates and any failed check, the message
 * says the check could not complete; partial failures are appended to
 * the updates line so a found update is not hidden either.
 */
internal fun agentUpdatesCheckMessage(
    updates: Map<AgentKind, AgentUpdateInfo>,
    failedChecks: List<AgentKind>,
): String {
    val failedSuffix = if (failedChecks.isNotEmpty()) {
        " · Could not check ${failedChecks.joinToString { it.title }}"
    } else {
        ""
    }
    return if (updates.isEmpty()) {
        if (failedChecks.isEmpty()) "All installed agents are up to date"
        else "Could not check for agent updates$failedSuffix"
    } else {
        "${updates.size} agent update${if (updates.size == 1) "" else "s"} available$failedSuffix"
    }
}

private data class ImportedZipProject(
    val project: Project,
    val sourceAttachment: ChatAttachment,
)

/**
 * QA H1 (Check Runtime Health): point-in-time runtime health, every field
 * from a live check — storage from the runtime health controller, rootfs
 * from the rootfs integrity verifier, memory from the app-process Runtime.
 */
data class RuntimeHealthSnapshot(
    val storageStatus: String = "Unknown",
    val storageMessage: String? = null,
    val storageUsableBytes: Long = 0L,
    val storageTotalBytes: Long = 0L,
    val rootfsStatus: String = "Unknown",
    val rootfsMissingPaths: List<String> = emptyList(),
    val rootfsCorruptedPaths: List<String> = emptyList(),
    val memoryUsedBytes: Long = 0L,
    val memoryMaxBytes: Long = 0L,
)

/**
 * Kodex: metadata read from a real built APK (package archive info, zip
 * contents, SHA-256) for the chat Delivered card. Nothing here is guessed.
 */
data class ApkDeliveryInfo(
    val appName: String,
    val packageId: String,
    val versionLabel: String,
    val sizeLabel: String,
    val stack: String,
    val sha256: String,
)

/**
 * U7a/U7c: one-shot state for the chat Delivered card — the resolved file
 * (null when the APK cannot be located in the project), its metadata when
 * readable, and an optional Preview target ("web" = local index.html path,
 * "app" = installed package name, "" = no preview available).
 */
data class DeliveredApkData(
    val resolvedPath: String?,
    val info: ApkDeliveryInfo?,
    val fileName: String,
    val sizeLabel: String?,
    val previewKind: String,
    val previewTarget: String?,
)

data class AppUiState(
    val startupStage: StartupStage = StartupStage.CHECKING,
    val startupProgress: Float = 0f,
    val startupMessage: String = "Checking this device…",
    val startupBytes: Pair<Long, Long>? = null,
    val startupLogs: List<String> = emptyList(),
    val startupIndeterminate: Boolean = false,
    val startupError: String? = null,
    val startupErrorIsOffline: Boolean = false,
    val showDetailedSetupProgress: Boolean = false,
    val onboardingComplete: Boolean = false,
    val backgroundSetupComplete: Boolean = false,
    val appTourCompleted: Boolean = false,
    val showAppTour: Boolean = false,
    val provider: ProviderProfile = ProviderProfile(ProviderKind.ANTHROPIC),
    val activeApiKeyName: String? = null,
    val themeMode: com.vayro.app.ui.theme.AppThemeMode = com.vayro.app.ui.theme.AppThemeMode.DARK,
    val fontStyle: com.vayro.app.ui.theme.AppFontStyle = com.vayro.app.ui.theme.AppFontStyle.DEFAULT,
    val apiPingStatus: ApiPingStatus = ApiPingStatus.IDLE,
    val apiPingMessage: String? = null,
    val projects: List<Project> = emptyList(),
    val projectImporting: Boolean = false,
    val projectImportMessage: String? = null,
    val gitCloneRunning: Boolean = false,
    val gitCloneMessage: String? = null,
    val githubAuthStatus: GitHubAuthStatus = GitHubAuthStatus.DISCONNECTED,
    val githubLogin: String? = null,
    val githubUserCode: String? = null,
    val githubVerificationUri: String? = null,
    val githubMessage: String? = null,
    val githubRepositories: List<GitHubRepository> = emptyList(),
    val githubRepositoriesLoading: Boolean = false,
    val activeProject: Project? = null,
    val workspaceVisible: Boolean = false,
    val readOnlyProject: Project? = null,
    val readOnlyProjectChats: List<ProjectChat> = emptyList(),
    val readOnlyChatId: String? = null,
    val readOnlyMessages: List<ChatMessage> = emptyList(),
    val projectChats: List<ProjectChat> = emptyList(),
    val activeChatId: String? = null,
    val workspaceFiles: List<WorkspaceEntry> = emptyList(),
    val androidProjectDetected: Boolean = false,
    val filesLoading: Boolean = false,
    // Medium: file-list load failure used to look identical to "empty
    // project" (and left filesLoading stuck on). Non-null = the last
    // refresh failed; the Files tab shows it with a Retry button.
    val filesError: String? = null,
    val openedFilePath: String? = null,
    val openedFileContent: String? = null,
    val openEditorTabs: List<String> = emptyList(),
    val fileContentLoading: Boolean = false,
    // Milestone 2 — persistent editor buffers: restored cursor/scroll per file,
    // split-editor state, and the set of tabs with unsaved changes.
    val editorViewStates: Map<String, com.vayro.app.editor.EditorViewState> = emptyMap(),
    val editorSplitState: com.vayro.app.editor.EditorSplitState =
        com.vayro.app.editor.EditorSplitState(),
    val editorDirtyPaths: Set<String> = emptySet(),
    val messages: List<ChatMessage> = listOf(
        ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change."),
    ),
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val pendingApproval: ToolRequest? = null,
    val changes: List<ChangeItem> = emptyList(),
    val activity: List<ActivityItem> = emptyList(),
    val liveProcess: List<ActivityItem> = emptyList(),
    val liveThinking: Boolean = false,
    val activeThinkingBlockId: Long? = null,
    val taskStartedAtMillis: Long? = null,
    val taskFinishedAtMillis: Long? = null,
    val workSegmentStartedAtMillis: Long? = null,
    val currentTaskRequest: String? = null,
    val previewReady: Boolean = false,
    val previewUrl: String? = null,
    val isRunning: Boolean = false,
    val streamingMessageId: String? = null,
    val activeSessionId: String? = null,
    val interruptedSession: com.vayro.app.session.PersistedAgentSession? = null,
    val runtimeRecoveryStatus: String? = null,
    val runtimeRecoveryAttempt: Int = 0,
    val runtimeRecoveryCanResume: Boolean = false,
    val skills: List<SkillInfo> = emptyList(),
    val skillSearchResults: List<SkillSearchResult> = emptyList(),
    val skillsBusy: Boolean = false,
    val skillsMessage: String? = null,
    val skillUpdates: Set<String> = emptySet(),
    val skillScanReport: SkillScanReport? = null,
    val skillScanBusy: Boolean = false,
    val toastMessage: String? = null,
    val projectTerminalLines: List<TerminalOutputLine> = emptyList(),
    val projectTerminalLiveOutput: String = "",
    val projectTerminalRunning: Boolean = false,
    val projectTerminalCwd: String = "/workspace",
    val projectTerminalCommand: String? = null,
    val projectTerminalDraft: String? = null,
    val pendingTerminalCommand: String? = null,
    val activeTerminalSessionId: String = "term-1",
    val terminalSessions: List<TerminalSessionTab> = listOf(TerminalSessionTab(id = "term-1", title = "Terminal 1")),
    val suggestedProjectRoot: String? = null,
    val selectedDevStacks: Set<DevStack> = emptySet(),
    val installedDevStacks: Set<DevStack> = emptySet(),
    val autoUpdateToolsAndSkills: Boolean = true,
    val freeModeEnabled: Boolean = false,
    val autoUpdatingToolsOrSkills: Boolean = false,
    val toolsAutoUpdateMessage: String? = null,
    val devStackInstalling: DevStack? = null,
    val devStackRemoving: Boolean = false,
    val devStackMessage: String? = null,
    val devStackProgress: Float = 0f,
    val devStackBytes: Pair<Long, Long>? = null,
    val devStackBytesPerSecond: Long? = null,
    val agentKind: AgentKind = AgentKind.CLAUDE_CODE,
    val primaryAgentKind: AgentKind = AgentKind.CLAUDE_CODE,
    val installedAgentVersions: Map<AgentKind, String> = emptyMap(),
    val agentInstalling: AgentKind? = null,
    val agentMessage: String? = null,
    val agentProgress: Float = 0f,
    val agentDownloadedBytes: Long? = null,
    val agentTotalBytes: Long? = null,
    val agentBytesPerSecond: Long? = null,
    val agentUpdates: Map<AgentKind, AgentUpdateInfo> = emptyMap(),
    val agentUpdatesChecking: Boolean = false,
    val agentUpdatesChecked: Boolean = false,
    val agentUpdatesCheckError: String? = null,
    val agentUpdating: AgentKind? = null,
    val agentUpdateMessage: String? = null,
    val agentUpdateProgress: Float = 0f,
    val agentUpdateDownloadedBytes: Long? = null,
    val agentUpdateTotalBytes: Long? = null,
    val agentUpdateBytesPerSecond: Long? = null,
    val antigravityAuth: AntigravityAuthState = AntigravityAuthState(),
    val antigravityModel: String = "",
    val antigravityEffort: String = "high",
    val antigravityModels: List<String> = emptyList(),
    val antigravityModelsLoading: Boolean = false,
    val geminiApiMode: Boolean = false,
    val geminiApiKeySaved: Boolean = false,
    val geminiApiModel: String = com.vayro.app.data.GEMINI_API_DEFAULT_MODEL,
    val androidBuildRunning: Boolean = false,
    val androidBuildMessage: String? = null,
    val appUpdate: AppUpdateInfo? = null,
    val appUpdateStatus: AppUpdateStatus? = null,
    val appUpdateDownloadedBytes: Long = 0L,
    val appUpdateTotalBytes: Long = -1L,
    val appUpdateError: String? = null,
    /**
     * Bumped on every MANUAL check that finds an update. The update dialog
     * keys its show-effect on this nonce as well as the version: without it,
     * re-checking while the same update is already in state changed nothing,
     * so the dialog never re-opened and "Check now" looked dead.
     */
    val appUpdatePromptNonce: Long = 0L,
    /**
     * Bumped when a voice call is requested from outside the workspace
     * chat (chat-home composer / "+" sheet). ChatTab observes the nonce
     * and presents the call once the workspace is open.
     */
    val voiceCallRequestNonce: Long = 0L,
    /** v3.2.37 (Part F): persisted home (normal chat) threads — separate from workspace projects. */
    val homeThreads: List<com.vayro.app.data.HomeThread> = emptyList(),
    val activeHomeThreadId: String = "",
    /** True while the home-chat engine is waiting for a reply. */
    val homeSending: Boolean = false,
    /** Title of the provider answering home chat ("" = none connected yet). */
    val homeChatProviderName: String = "",
    val executionMode: ExecutionMode = ExecutionMode.UNIFIED,
    val activeRoadmap: ActiveRoadmap? = null,
    val scratchpadItems: List<ScratchpadItem> = emptyList(),
    val scratchpadExpanded: Boolean = false,
    val orchestratorSnapshot: com.vayro.app.session.OrchestratorSnapshot? = null,
    val emergencyKillSwitchActive: Boolean = false,
    val allowShellExec: Boolean = true,
    val allowNetworkAccess: Boolean = true,
    val allowFileDeletion: Boolean = false,
    val allowGitPush: Boolean = false,
    val allowApkInstall: Boolean = false,
    val allowExternalServices: Boolean = false,
    val allowAccessEnv: Boolean = false,
    val securityAuditLogs: List<com.vayro.app.security.SecurityAuditEntry> = emptyList(),
    val mcpServers: List<com.vayro.app.mcp.McpServerInfo> = emptyList(),
    val mcpCatalog: List<com.vayro.app.mcp.McpCatalogTemplate> = emptyList(),
    val mcpTools: List<com.vayro.app.runtime.McpToolDefinition> = emptyList(),
    // v3.2.38 (Part Q): per-server discovered tool counts (the header total
    // is their sum — the real discovered total, not the last server's list)
    // and per-server last discovery error, shown on the server's own card.
    val mcpToolCounts: Map<String, Int> = emptyMap(),
    val mcpServerErrors: Map<String, String> = emptyMap(),
    val mcpAuditLogs: List<com.vayro.app.mcp.McpAuditEntry> = emptyList(),
    val mcpSelectedServerId: String? = null,
    val mcpBusy: Boolean = false,
    val mcpMessage: String? = null,
    val mcpRunResult: com.vayro.app.runtime.McpToolCallResult? = null,
    val mcpRunTarget: String? = null,
    val mcpRunRunning: Boolean = false,
    val appFactoryBlueprint: com.vayro.app.factory.model.AppBlueprint? = null,
    val appFactoryGeneratingBlueprint: Boolean = false,
    val appFactoryScaffolding: Boolean = false,
    val appFactoryScaffoldFiles: List<java.io.File> = emptyList(),
    val appFactoryBuilding: Boolean = false,
    val appFactoryBuildResult: com.vayro.app.factory.model.BuildResult? = null,
    val appFactoryLogs: List<String> = emptyList(),
    val appFactoryKeystoreConfig: com.vayro.app.factory.model.KeystoreConfig? = null,
    val appFactoryStoreBundle: com.vayro.app.factory.model.StoreListingBundle? = null,
    val appFactoryMessage: String? = null,
    val gitStatusInfo: com.vayro.app.git.model.GitStatusInfo = com.vayro.app.git.model.GitStatusInfo(),
    val gitBranches: List<com.vayro.app.git.model.GitBranchInfo> = emptyList(),
    val gitCommits: List<com.vayro.app.git.model.GitCommitInfo> = emptyList(),
    val gitRawDiff: String = "",
    val gitWorkflowState: com.vayro.app.git.workflow.WorkflowExecutionState = com.vayro.app.git.workflow.WorkflowExecutionState(),
    val gitWorkflowRuns: List<com.vayro.app.network.GitHubWorkflowRun> = emptyList(),
    val gitReleases: List<com.vayro.app.network.GitHubRelease> = emptyList(),
    val gitIssues: List<com.vayro.app.network.GitHubIssue> = emptyList(),
    val gitPullRequests: List<com.vayro.app.network.GitHubPullRequest> = emptyList(),
    val gitConnectedRepo: String? = null,
    val teamSessionState: com.vayro.app.agent.team.model.TeamSessionState = com.vayro.app.agent.team.model.TeamSessionState(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = ApiKeyVault(application)
    private val preferences = AppPreferences(application)
    private val geminiApiStore = com.vayro.app.data.GeminiApiStore(application)
    // One shared policy engine: the Settings toggles/kill switch and the
    // agent bridges must observe the same in-memory state. (Previously the
    // Claude bridge built its own engine, so the kill switch in Settings
    // could never reach it.)
    val permissionPolicyEngine = com.vayro.app.security.PermissionPolicyEngine(application)
    val mcpRegistry = com.vayro.app.mcp.McpRegistry(application)
    val mcpAuditLogger = com.vayro.app.mcp.McpAuditLogger(application)
    val mcpPermissionManager = com.vayro.app.mcp.McpPermissionManager(File(application.filesDir, "mcp"), permissionPolicyEngine)
    // The single safety gateway: kill switch + path audit + MCP consult +
    // policy engine. It is constructed and used (not decorative): it is the
    // choke point for Claude Code's per-tool approvals and the
    // session-start gates. Guest tool paths are rooted at /workspace/<slug>,
    // so per-project containment resolves the guest root from the project
    // id; a missing root fails closed inside the path auditor.
    val safetyGateway = com.vayro.app.security.SafetyGateway(
        policyEngine = permissionPolicyEngine,
        workspaceRoot = null,
        workspaceRootResolver = { slug ->
            slug?.takeIf { it.isNotBlank() }?.let { File("/workspace/$it") }
        },
        mcpPermissionManager = mcpPermissionManager,
        mcpServerResolver = { serverId -> mcpRegistry.getServer(serverId) },
    )
    private val claudeRuntime = ClaudeRuntimeBridge(application, { profile -> vault.get(profile.kind.name) }, safetyGateway)
    private val dshRuntime = DshRuntimeBridge(application, { profile -> vault.get(profile.kind.name) }, safetyGateway)
    private val installer = RuntimeInstaller(application)
    private var agentInstallJob: Job? = null
    private var agentUpdateJob: Job? = null
    private val antigravityRuntime = AntigravityRuntimeBridge(
        application,
        model = { _state.value.antigravityModel },
        effort = { _state.value.antigravityEffort },
        conversationId = { projectId ->
            _state.value.activeChatId?.let { preferences.loadAgentConversation(AgentKind.ANTIGRAVITY, projectId, it) }
        },
        saveConversationId = { projectId, id ->
            _state.value.activeChatId?.let { preferences.saveAgentConversation(AgentKind.ANTIGRAVITY, projectId, it, id) }
        },
        safetyGateway = safetyGateway,
        geminiApiConfig = { currentGeminiApiConfig() },
    )
    private fun currentGeminiApiConfig(): com.vayro.app.runtime.GeminiApiConfig? {
        val key = geminiApiStore.getApiKey()
        return if (geminiApiStore.apiModeEnabled && !key.isNullOrBlank()) {
            com.vayro.app.runtime.GeminiApiConfig(key, geminiApiStore.apiModel)
        } else null
    }
    private val customRuntime = CustomAgentBridge { profile -> vault.get(profile.kind.name) }
    private val codexRuntime = CodexRuntimeBridge(application) { profile -> vault.get(profile.kind.name) }
    private val agentRegistry = AgentRegistry.builtIns(claudeRuntime, dshRuntime, antigravityRuntime, customRuntime, codexRuntime)
    private fun createRuntimeBridge(kind: AgentKind, interactiveApprovals: Boolean = true): com.vayro.app.runtime.RuntimeBridge =
        when (kind) {
            AgentKind.CLAUDE_CODE -> ClaudeRuntimeBridge(getApplication<Application>(), { profile -> vault.get(profile.kind.name) }, safetyGateway)
            AgentKind.DEEPSEEK_HARNESS -> DshRuntimeBridge(
                getApplication<Application>(),
                { profile -> vault.get(profile.kind.name) },
                safetyGateway,
                interactiveApprovals = interactiveApprovals,
            )
            AgentKind.ANTIGRAVITY -> AntigravityRuntimeBridge(
                getApplication<Application>(),
                model = { _state.value.antigravityModel },
                effort = { _state.value.antigravityEffort },
                conversationId = { projectId ->
                    _state.value.activeChatId?.let { preferences.loadAgentConversation(AgentKind.ANTIGRAVITY, projectId, it) }
                },
                saveConversationId = { projectId, id ->
                    _state.value.activeChatId?.let { preferences.saveAgentConversation(AgentKind.ANTIGRAVITY, projectId, it, id) }
                },
                safetyGateway = safetyGateway,
                interactiveApprovals = interactiveApprovals,
                geminiApiConfig = { currentGeminiApiConfig() },
            )
            AgentKind.CUSTOM -> CustomAgentBridge { profile -> vault.get(profile.kind.name) }
            AgentKind.CODEX -> CodexRuntimeBridge(getApplication<Application>()) { profile -> vault.get(profile.kind.name) }
        }
    private val projectBridgePool = ProjectBridgePool { kind -> createRuntimeBridge(kind) }
    private fun sessionBridge(projectId: String, kind: AgentKind = _state.value.agentKind): com.vayro.app.runtime.RuntimeBridge {
        val rootPath = _state.value.projects.firstOrNull { it.id == projectId }?.rootPath.orEmpty()
        return projectBridgePool.getOrCreate(projectId, kind, rootPath, viewModelScope) { pid, event -> onRuntimeEvent(pid, event) }
    }
    private val providerApi = ProviderApiClient()
    // v3.2.37 (Parts C/F/F2/G): home chat + key health stores.
    private val homeChatStore = com.vayro.app.data.HomeChatStore(application)
    private val keyHealthStore = com.vayro.app.data.KeyHealthStore(application)
    private val homeFailover = ApiKeyFailoverCoordinator()
    private var homeChatJob: Job? = null
    private val skillManager = SkillManager(application)
    private val editorCheckpoints = WorkspaceCheckpoints(application.filesDir)
    val sessionManager = com.vayro.app.session.AgentSessionManager(application)
    val checkpointManager = com.vayro.app.workspace.WorkspaceCheckpointManager(application.filesDir)
    val runtimeHealthController = com.vayro.app.runtime.RuntimeHealthController(application)
    val agentSessionController = com.vayro.app.session.AgentSessionController(application, sessionManager)
    val terminalSessionController = com.vayro.app.terminal.TerminalSessionController()
    val agentChatController = com.vayro.app.agent.AgentChatController()
    val gitDomainController = com.vayro.app.workspace.GitDomainController { args, workingDir, guestPath ->
        if (!installer.isInstalled()) return@GitDomainController 1 to "Linux runtime is not ready."
        val installed = installer.installedRuntime()
        val outputFile = File(getApplication<Application>().cacheDir, "git-op-${System.nanoTime()}.log")
        try {
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workingDir,
                mapOf("GIT_TERMINAL_PROMPT" to "0"),
                listOf("git") + args,
                guestWorkspacePath = guestPath,
                outputFile = outputFile,
            )
            val exitCode = process.waitFor()
            val out = outputFile.readTailText(MAX_PROCESS_OUTPUT_BYTES).trim()
            exitCode to out
        } finally {
            outputFile.delete()
        }
    }
    val appErrorCoordinator = com.vayro.app.runtime.AppErrorCoordinator()
    val projectWorkspaceController = com.vayro.app.workspace.ProjectWorkspaceController(application)
    val fileOperationsController = com.vayro.app.workspace.FileOperationsController()
    val projectMemoryStore = com.vayro.app.runtime.ProjectMemoryStore(application.filesDir)
    val agentOrchestrator = com.vayro.app.session.AgentOrchestrator(checkpointManager, projectMemoryStore)
    val mcpCredentialStore = com.vayro.app.mcp.McpCredentialStore(File(application.filesDir, "mcp"))
    // The live MCP implementation: NDJSON wire protocol, permission gating,
    // audit logging, discovery cache, remote support, credential resolution.
    val mcpToolRegistry = com.vayro.app.mcp.McpToolRegistry(
        registry = mcpRegistry,
        permissionManager = mcpPermissionManager,
        auditLogger = mcpAuditLogger,
        credentialStore = mcpCredentialStore,
        // v3.2.39 (Scope 1): stdio servers spawn INSIDE the Linux guest —
        // the only place npx/uvx exist. Discovery and tool calls both run
        // over this live transport (see GuestMcpProcessFactory).
        sessionFactory = { server ->
            com.vayro.app.mcp.McpServerSession(
                server,
                processFactory = com.vayro.app.mcp.GuestMcpProcessFactory(
                    installer = installer,
                    workspaceDir = File(application.filesDir, "mcp-workspace"),
                    cacheDir = application.cacheDir,
                ),
            )
        },
    )
    val gitHubClient = com.vayro.app.network.GitHubClient()

    /**
     * Kodex: drives the GitHub Build (Cloud Hybrid) screen — dispatch a
     * workflow on GitHub Actions and watch the run, so heavy builds stay
     * off the phone. Built lazily from the same client/gateway the rest
     * of the GitHub features use.
     */
    val githubActionsCoordinator by lazy {
        com.vayro.app.actions.GitHubActionsDispatchCoordinator(
            gitHubClient = gitHubClient,
            workflowAutomation = com.vayro.app.git.automation.WorkflowAutomation(gitHubClient),
            safetyGateway = safetyGateway,
            ghAuthStatus = {
                val snapshot = _state.value
                (snapshot.githubAuthStatus == GitHubAuthStatus.CONNECTED) to snapshot.githubLogin
            },
            onStartDeviceFlow = { startGitHubLogin() },
            scope = viewModelScope,
        )
    }
    val gitWorkflowEngine = com.vayro.app.git.workflow.GitHubWorkflowEngine(
        gitDomainController,
        gitHubClient,
        com.vayro.app.security.GitSafetyAdapter(safetyGateway)
    )
    val marketplaceInstallManager = com.vayro.app.marketplace.MarketplaceInstallManager(application)
    val marketplacePublishedItemsStore = com.vayro.app.marketplace.MarketplacePublishedItemsStore(application)
    val teamConcurrencyController = com.vayro.app.agent.team.runtime.AdaptiveConcurrencyController(application)
    val teamOrchestrator = com.vayro.app.agent.team.AgentTeamOrchestrator(
        teamExecutor = com.vayro.app.agent.team.runtime.RealTeamAgentExecutor(
            workspaceBaseDir = File(application.filesDir, "team-workspaces"),
            bridgeFactory = { kind ->
                // Fresh bridge instance per execution: separate instance means a
                // separate OS process, which is what makes parallel agents real.
                when (kind) {
                    AgentKind.CLAUDE_CODE -> ClaudeRuntimeBridge(application, { profile -> vault.get(profile.kind.name) }, safetyGateway)
                    AgentKind.DEEPSEEK_HARNESS -> DshRuntimeBridge(
                        application,
                        { profile -> vault.get(profile.kind.name) },
                        safetyGateway,
                        interactiveApprovals = false,
                    )
                    AgentKind.ANTIGRAVITY -> AntigravityRuntimeBridge(
                        application,
                        model = { _state.value.antigravityModel },
                        effort = { _state.value.antigravityEffort },
                        conversationId = { projectId ->
                            _state.value.activeChatId?.let { preferences.loadAgentConversation(AgentKind.ANTIGRAVITY, projectId, it) }
                        },
                        saveConversationId = { projectId, id ->
                            _state.value.activeChatId?.let { preferences.saveAgentConversation(AgentKind.ANTIGRAVITY, projectId, it, id) }
                        },
                        safetyGateway = safetyGateway,
                        interactiveApprovals = false,
                        geminiApiConfig = { currentGeminiApiConfig() },
                    )
                    AgentKind.CUSTOM -> CustomAgentBridge { profile -> vault.get(profile.kind.name) }
                    AgentKind.CODEX -> CodexRuntimeBridge(application) { profile -> vault.get(profile.kind.name) }
                }
            },
            installer = installer,
            providerFor = { kind -> preferences.loadProvider(vault, kind) },
            secretFor = { profile -> vault.get(profile.kind.name) },
            // Antigravity authenticates with its own signed-in session
            // (e.g. Gemini), never with a vault API key. A signed-in session
            // counts as auth, so team tasks can run on it when the role's
            // wired engine (e.g. Claude Code) has no saved key.
            isSessionAuthAvailable = { kind ->
                kind == AgentKind.ANTIGRAVITY &&
                    (_state.value.antigravityAuth.status == AntigravityAuthStatus.SIGNED_IN ||
                        (geminiApiStore.apiModeEnabled && geminiApiStore.hasApiKey()))
            },
            preferredKind = { _state.value.agentKind },
            // The emergency kill switch blocks new team tasks too, not just
            // the main chat session.
            isKillSwitchEngaged = { permissionPolicyEngine.isEmergencyKillSwitchEngaged() },
        ),
        concurrencyController = teamConcurrencyController,
    )
    private val projectIndex = ProjectIndex(application.filesDir)
    private val contextEngine = ContextEngine(projectIndex, projectMemoryStore)
    private val runtimeRetryPolicy = RuntimeRetryPolicy()
    private val projectSessionStates = MutableStateFlow<Map<String, ProjectSessionState>>(emptyMap())
    private val sessionRuntimeRequests = ConcurrentHashMap<String, RuntimeRetryRequest>()
    private val sessionProjects = ConcurrentHashMap<String, String>()
    private val runningSessions = ConcurrentHashMap<String, String>()
    private data class SessionRecoveryState(var job: Job? = null, var attempt: Int = 0, var startedAtMillis: Long? = null)
    private val sessionRecoveryStates = ConcurrentHashMap<String, SessionRecoveryState>()
    private val pendingAssistantDeltas = ConcurrentHashMap<String, StringBuilder>()
    private val assistantDeltaFlushJobs = ConcurrentHashMap<String, Job>()
    private val lastAssistantDeltaPersistAtMillis = ConcurrentHashMap<String, Long>()
    private data class QueuedSessionStart(val projectId: String, val prompt: String, val attachments: List<ChatAttachment>)
    private val sessionStartQueue = ArrayDeque<QueuedSessionStart>()
    private val failedApiKeyIds = mutableSetOf<String>()
    @Volatile private var lastOpenedAntigravityAuthUrl: String? = null
    private fun appUpdater(): AppUpdater = AppUpdater(
        context = getApplication(),
        manifestUrlOverride = preferences.updateManifestUrlOverride.ifBlank {
            if (BuildConfig.DEBUG) preferences.debugUpdateManifestUrl else ""
        },
        authToken = preferences.updateAuthToken,
    )
    // Sessions killed by Stop or by switching agents. Their bridges may still
    // emit late events (a Custom HTTP stream finishing after the switch);
    // those events must never leak into the new agent's chat.
    private val ignoredSessionIds = mutableSetOf<String>()
    // Transcript persistence queue. Bounded with DROP_OLDEST (QA H27): the
    // old UNLIMITED channel let writes pile up in memory forever once the
    // consumer died. Dropping is safe here because every write is a FULL
    // snapshot — an older queued snapshot for a chat is superseded by a
    // newer one for the same chat, and the consumer collapses the queue
    // to the latest snapshot per chat before writing.
    private val transcriptWrites = Channel<TranscriptWrite>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    // Throttle for the "transcript save failed" toast: a stuck disk must
    // not spam a toast on every queued write.
    private var lastTranscriptSaveErrorToastAtMillis = 0L

    private val maxInMemoryMessages = 200
    private val initialAgentKind = AgentKind.fromStored(preferences.agentKind)
    private val initialPrimaryAgentKind = preferences.primaryAgentKind
        .takeIf(String::isNotBlank)
        ?.let(AgentKind::fromStored)
        ?: initialAgentKind

    // Startup must never die before Compose can render. Android Keystore state can
    // become temporarily unavailable/corrupt after OS upgrades, restore operations,
    // or an interrupted app reinstall. Treat provider/secret hydration as recoverable
    // state and let the UI continue into setup instead of crashing in MainViewModel's
    // constructor during the system splash screen.
    private val initialProvider: ProviderProfile = runCatching {
        preferences.loadProvider(vault, initialAgentKind)
    }.getOrElse {
        ProviderProfile(
            kind = if (initialAgentKind == AgentKind.DEEPSEEK_HARNESS) {
                ProviderKind.DEEPSEEK
            } else {
                ProviderKind.ANTHROPIC
            },
            hasSecret = false,
        )
    }

    private val initialActiveApiKeyName: String? = runCatching {
        vault.list(initialProvider.kind.name).firstOrNull(ApiKeyInfo::isActive)?.name
    }.getOrNull()

    private val antigravityAuthController = AntigravityAuthController(
        application,
        preferences.antigravitySignedIn,
        preferences.antigravityAccountEmail,
    ) { signedIn, email ->
        preferences.antigravitySignedIn = signedIn
        preferences.antigravityAccountEmail = email.orEmpty()
        if (!signedIn) preferences.clearAgentConversations(AgentKind.ANTIGRAVITY)
    }
    private val isWarmReady = preferences.runtimeSetupComplete && preferences.onboardingComplete
    private val initialProjects = preferences.loadProjects()
    private val initialActiveProject = if (isWarmReady) {
        preferences.lastActiveProjectId?.let { lastId -> initialProjects.firstOrNull { it.id == lastId } }
    } else null
    private val initialChats = initialActiveProject?.let { proj ->
        preferences.loadProjectChats(proj.id).ifEmpty {
            listOf(ProjectChat(title = "Main chat"))
        }
    } ?: emptyList()
    private val initialActiveChatId = initialActiveProject?.let { proj ->
        val lastChatId = preferences.getLastActiveChatId(proj.id)
        (initialChats.firstOrNull { it.id == lastChatId } ?: initialChats.firstOrNull())?.id
    }
    private val initialMessages = (if (initialActiveProject != null && initialActiveChatId != null) {
        preferences.loadMessages(initialActiveProject.id, initialActiveChatId).ifEmpty {
            listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change."))
        }
    } else {
        listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change."))
    }).map { msg ->
        if (!msg.fromUser && msg.roadmap == null && msg.text.isNotBlank()) {
            val parsed = RoadmapParser.parseFromText(msg.text, ExecutionMode.fromStored(preferences.executionMode))
            if (parsed != null) msg.copy(roadmap = parsed) else msg
        } else {
            msg
        }
    }
    private val initialActiveRoadmap = initialMessages.mapNotNull { it.roadmap }.lastOrNull()
    private val initialScratchpad = deriveScratchpadItems(initialActiveRoadmap, emptyList(), false)

    private val _state = MutableStateFlow(
        AppUiState(
            startupStage = if (isWarmReady) StartupStage.READY else StartupStage.CHECKING,
            startupProgress = if (isWarmReady) 1f else 0f,
            onboardingComplete = preferences.onboardingComplete,
            backgroundSetupComplete = preferences.backgroundSetupComplete,
            appTourCompleted = preferences.appTourCompleted,
            showAppTour = isWarmReady && !preferences.appTourCompleted,
            agentKind = initialAgentKind,
            primaryAgentKind = initialPrimaryAgentKind,
            provider = initialProvider,
            activeApiKeyName = initialActiveApiKeyName,
            antigravityAuth = AntigravityAuthState(
                status = if (preferences.antigravitySignedIn) AntigravityAuthStatus.SIGNED_IN else AntigravityAuthStatus.SIGNED_OUT,
                message = preferences.antigravityAccountEmail.takeIf(String::isNotBlank)?.let { "Connected as $it" },
                accountEmail = preferences.antigravityAccountEmail.takeIf(String::isNotBlank),
            ),
            antigravityModel = preferences.antigravityModel,
            antigravityEffort = preferences.antigravityEffort,
            geminiApiMode = geminiApiStore.apiModeEnabled,
            geminiApiKeySaved = geminiApiStore.hasApiKey(),
            geminiApiModel = geminiApiStore.apiModel,
            themeMode = runCatching { com.vayro.app.ui.theme.AppThemeMode.valueOf(preferences.themeMode.uppercase()) }
                .getOrDefault(com.vayro.app.ui.theme.AppThemeMode.DARK),
            fontStyle = runCatching { com.vayro.app.ui.theme.AppFontStyle.valueOf(preferences.fontStyle.uppercase()) }
                .getOrDefault(com.vayro.app.ui.theme.AppFontStyle.DEFAULT),
            projects = initialProjects,
            activeProject = initialActiveProject,
            workspaceVisible = isWarmReady && initialActiveProject != null,
            projectChats = initialChats,
            activeChatId = initialActiveChatId,
            messages = initialMessages,
            activeRoadmap = initialActiveRoadmap,
            scratchpadItems = initialScratchpad,
            githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
            githubLogin = preferences.githubLogin.takeIf(String::isNotBlank),
            skills = skillManager.installed(),
            autoUpdateToolsAndSkills = preferences.autoUpdateToolsAndSkills,
            freeModeEnabled = preferences.freeModeEnabled,
            selectedDevStacks = preferences.selectedDevStacks.mapNotNull { name ->
                runCatching { DevStack.valueOf(name) }.getOrNull()
            }.toSet() + DevStack.WEB,
            executionMode = ExecutionMode.fromStored(preferences.executionMode),
        ),
    )

    // ---- Milestone 1: moved above init — the init block deletes stale terminal history. ----
    private val coordinatorHost = com.vayro.app.ui.coordinators.CoordinatorHost(
        appContext = getApplication(),
        scope = viewModelScope,
        state = { _state.value },
        updateState = { transform -> _state.update(transform) },
        showToast = { message -> showToast(message) },
    )

    private val terminalCoordinator =
        com.vayro.app.ui.coordinators.TerminalCoordinator(
            host = coordinatorHost,
            installer = installer,
            terminalSessionController = terminalSessionController,
            workspaceRootFor = { project -> projectWorkspaceRoot(project) },
            refreshProjectFiles = { refreshProjectFiles() },
        )
    // ---- Moved above init: refreshGitHubConnection() is launched from init via
    // ---- Dispatchers.Main.immediate, which runs synchronously before later properties initialize.
    private val gitHubAuthCoordinator =
        com.vayro.app.ui.coordinators.github.GitHubAuthCoordinator(
            host = coordinatorHost,
            gitHubClient = gitHubClient,
            installer = installer,
            preferences = preferences,
            workspaceRootFor = { project -> projectWorkspaceRoot(project) },
            openProject = { project -> openProject(project) },
        )
    // ---- Moved above init: init block calls refreshPermissionPolicies() /
    // ---- refreshMcpServers() / loadMcpAuditLogs() synchronously via Dispatchers.Main.immediate,
    // ---- which runs before later-declared properties initialize.
    private val securityCoordinator =
        com.vayro.app.ui.coordinators.SecurityCoordinator(
            host = coordinatorHost,
            permissionPolicyEngine = permissionPolicyEngine,
            stopTask = { stopTask() },
        )
    private val mcpCoordinator =
        com.vayro.app.ui.coordinators.McpCoordinator(
            host = coordinatorHost,
            mcpRegistry = mcpRegistry,
            mcpAuditLogger = mcpAuditLogger,
            mcpPermissionManager = mcpPermissionManager,
            toolRegistry = mcpToolRegistry,
            credentialStore = mcpCredentialStore,
        )
    init {
        AppCrashLogger.log("MainViewModel.init entered")
        // v3.2.37 (Part F): restore home chat threads (never workspace projects).
        runCatching {
            val threads = homeChatStore.load()
            val activeId = preferences.activeHomeThreadId
                .takeIf { id -> threads.any { it.id == id } }
                ?: threads.firstOrNull()?.id.orEmpty()
            _state.update { it.copy(homeThreads = threads, activeHomeThreadId = activeId) }
        }
        refreshHomeChatProviderLabel()
        // GitHub's official CLI owns its OAuth credential. Remove credentials from
        // the retired custom OAuth implementation and discover the real CLI status.
        runCatching { vault.remove(LEGACY_GITHUB_TOKEN_KEY) }
        viewModelScope.launch { refreshGitHubConnection() }
        viewModelScope.launch {
            gitWorkflowEngine.state.collect { wfState ->
                _state.update { it.copy(gitWorkflowState = wfState) }
            }
        }
        viewModelScope.launch {
            teamOrchestrator.sessionState.collect { teamState ->
                _state.update { it.copy(teamSessionState = teamState) }
            }
        }
        runCatching { RuntimeSetupController.restore(application) }
        val storageHealth = runtimeHealthController.checkStorageHealth()
        if (storageHealth is com.vayro.app.runtime.StorageHealthResult.Critical) {
            _state.update { it.copy(toastMessage = storageHealth.message) }
        }
        val rootfsIntegrity = runtimeHealthController.verifyRootfsIntegrity()
        if (rootfsIntegrity is com.vayro.app.runtime.RootfsIntegrityResult.Corrupted) {
            AppCrashLogger.log("Rootfs corruption detected: ${rootfsIntegrity.reason}")
            _state.update { it.copy(toastMessage = "Runtime warning: ${rootfsIntegrity.reason}") }
        }
        val interrupted = sessionManager.checkInterruptedSessions()
        if (interrupted.isNotEmpty()) {
            val best = interrupted.firstOrNull { it.projectId == preferences.lastActiveProjectId }
                ?: interrupted.first()
            _state.update { it.copy(interruptedSession = best) }
            projectSessionStates.update { states ->
                var updated = states
                interrupted.groupBy { it.projectId }.forEach { (pid, sessions) ->
                    val latest = sessions.maxByOrNull { it.updatedAt } ?: return@forEach
                    val existing = updated[pid] ?: ProjectSessionState(projectId = pid)
                    updated = updated + (pid to existing.copy(interruptedSession = latest))
                }
                updated
            }
        }
        refreshPermissionPolicies()
        refreshMcpServers()
        loadMcpAuditLogs()
        viewModelScope.launch {
            agentOrchestrator.snapshot.collect { orchSnapshot ->
                _state.update { it.copy(orchestratorSnapshot = orchSnapshot) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runtimeHealthController.cleanupOrphanArtifacts()
            com.vayro.app.workspace.SafeFileOps.cleanStaleTempFiles(application.filesDir)
            for (write in transcriptWrites) {
                // Collapse: if more writes are already queued, keep only the
                // latest snapshot per (project, chat) — older full snapshots
                // for the same chat are superseded, so writing them is pure
                // wasted O(n) I/O (QA H28). One write per chat per drain.
                val pending = mutableListOf(write)
                while (true) {
                    pending.add(transcriptWrites.tryReceive().getOrNull() ?: break)
                }
                val latestPerChat = pending
                    .groupBy { it.projectId to it.chatId }
                    .mapValues { it.value.last() }
                    .values
                for (collapsed in latestPerChat) {
                    // QA H27: one I/O error used to kill this whole consumer
                    // loop (no try/catch), after which writes piled up in
                    // memory forever and the user lost everything since the
                    // failure with no error shown. A failed write is now
                    // logged, surfaced (throttled toast), and the loop keeps
                    // consuming — the next write retries persistence.
                    runCatching {
                        preferences.saveMessages(collapsed.projectId, collapsed.chatId, collapsed.messages)
                    }.onFailure { error ->
                        com.vayro.app.AppCrashLogger.log(
                            "Transcript save failed for ${collapsed.projectId}/${collapsed.chatId}: ${error.message}"
                        )
                        val now = System.currentTimeMillis()
                        if (now - lastTranscriptSaveErrorToastAtMillis > 30_000L) {
                            lastTranscriptSaveErrorToastAtMillis = now
                            _state.update {
                                it.copy(toastMessage = "Could not save chat transcript: ${error.message?.take(120) ?: "storage error"}. New messages may be lost — free up storage and reopen the chat.")
                            }
                        }
                    }
                }
            }
        }
        viewModelScope.launch { dshRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { antigravityRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { customRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch { codexRuntime.events.collect(::onRuntimeEvent) }
        viewModelScope.launch {
            antigravityAuthController.state.collect { auth ->
                _state.update { it.copy(antigravityAuth = auth) }
                auth.authorizationUrl?.takeIf { it != lastOpenedAntigravityAuthUrl }?.let { url ->
                    lastOpenedAntigravityAuthUrl = url
                    runCatching {
                        getApplication<Application>().startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        _state.update { state -> state.copy(toastMessage = "Could not open the browser. Copy the sign-in URL instead.") }
                    }
                }
            }
        }
        if (antigravityAuthController.hasOfficialCredential() &&
            (!preferences.antigravitySignedIn || preferences.antigravityAccountEmail.isBlank())
        ) {
            viewModelScope.launch { antigravityAuthController.beginLogin() }
        }
        if (!preferences.legacySeededCredentialRemoved) {
            vault.remove(ProviderKind.CUSTOM.name)
            preferences.legacySeededCredentialRemoved = true
            _state.update { current ->
                if (current.provider.kind == ProviderKind.CUSTOM) {
                    current.copy(provider = current.provider.copy(hasSecret = false))
                } else current
            }
        }
        if (
            BuildConfig.TEST_OPENROUTER_API_KEY.isNotBlank() &&
            preferences.testProviderDefaultsVersion < TEST_PROVIDER_DEFAULTS_VERSION
        ) {
            val testProvider = ProviderProfile(
                kind = ProviderKind.CUSTOM,
                baseUrl = TEST_OPENROUTER_BASE_URL,
                model = TEST_OPENROUTER_MODEL,
                hasSecret = true,
            )
            vault.put(ProviderKind.CUSTOM.name, BuildConfig.TEST_OPENROUTER_API_KEY)
            preferences.saveProvider(testProvider, _state.value.agentKind)
            preferences.testProviderDefaultsVersion = TEST_PROVIDER_DEFAULTS_VERSION
            _state.update { it.copy(provider = testProvider) }
        }

        // QA H29: the startup cleanup + warm restore used to run
        // synchronously on the main thread at the end of init: a SECOND
        // full loadProjects, loadMessages for EVERY chat of EVERY quick
        // project, and a full workspace walk per project — cold-start
        // jank / ANR on large transcripts. It now runs on
        // Dispatchers.IO and reuses the project list already loaded for
        // the initial state (no second loadProjects). The active project
        // is never cleaned, and each candidate is re-checked against the
        // current active project before deletion, so a project opened
        // while the scan runs cannot be wiped under the user.
        viewModelScope.launch(Dispatchers.IO) {
            val deletedIds = mutableListOf<String>()
            for (project in initialProjects) {
                if (project.kind != ProjectKind.QUICK_PROJECT) continue
                if (project.id == initialActiveProject?.id) continue
                if (_state.value.activeProject?.id == project.id) continue
                val chats = preferences.loadProjectChats(project.id)
                val userMessages = chats.sumOf { preferences.loadMessages(project.id, it.id).count { m -> m.fromUser } }
                val workspaceDir = File(application.filesDir, "workspaces/${project.id}")
                val userFiles = if (workspaceDir.isDirectory) {
                    workspaceDir.walkTopDown().filter { file ->
                        file.isFile && !file.name.startsWith(".claude") && file.name != ".pocket-dev-stacks.json"
                    }.count()
                } else 0
                if (!isEmptyQuickProject(project.kind, userMessages, userFiles)) continue
                // Re-check: the user may have opened this project while the
                // scan was running.
                if (_state.value.activeProject?.id == project.id) continue
                workspaceDir.deleteRecursively()
                terminalCoordinator.deleteTerminalHistory(project.id)
                preferences.deleteProjectChats(project.id)
                deletedIds.add(project.id)
            }
            if (deletedIds.isNotEmpty()) {
                // Apply to CURRENT state (not the stale initial list) so a
                // project created while the scan ran is never wiped.
                _state.update { current ->
                    current.copy(projects = current.projects.filter { it.id !in deletedIds })
                }
                preferences.saveProjects(_state.value.projects)
            }
            if (isWarmReady && initialActiveProject != null) {
                configureBridgeRoots(initialActiveProject.id, initialActiveProject.rootPath)
                val terminal = loadProjectTerminal(initialActiveProject)
                _state.update {
                    it.copy(
                        projectTerminalLines = terminal.lines,
                        projectTerminalCwd = terminal.cwd,
                    )
                }
                refreshProjectFiles()
            }
        }
    }

    val state: StateFlow<AppUiState> = _state.asStateFlow()

    // ---- Milestone 1: domain coordinators extracted from this ViewModel. ----
    // Each coordinator owns one domain's operations and state machine;
    // MainViewModel keeps a stable public API by delegating to them.
    private val marketplaceCoordinator =
        com.vayro.app.ui.coordinators.MarketplaceCoordinator(
            coordinatorHost,
            marketplaceInstallManager,
            marketplacePublishedItemsStore,
            mcpRegistry,
            onMcpServersChanged = { mcpCoordinator.refreshMcpServers() },
        )
    private val teamCoordinator =
        com.vayro.app.ui.coordinators.TeamCoordinator(teamOrchestrator)
    private val visualCoordinator =
        com.vayro.app.ui.coordinators.VisualCoordinator(
            host = coordinatorHost,
            workspaceRootFor = { project -> projectWorkspaceRoot(project) },
            sendPrompt = { prompt -> sendPrompt(prompt) },
        )
    private val skillsCoordinator =
        com.vayro.app.ui.coordinators.SkillsCoordinator(
            host = coordinatorHost,
            skillManager = skillManager,
            installer = installer,
            preferences = preferences,
        )
    private val appFactoryCoordinator =
        com.vayro.app.ui.coordinators.AppFactoryCoordinator(
            host = coordinatorHost,
            workspaceRootFor = { project -> projectWorkspaceRoot(project) },
            refreshProjectFiles = { refreshProjectFiles() },
            installer = installer,
            gitHubClient = gitHubClient,
            preferences = preferences,
            gitHubTokenProvider = { resolveGitHubToken() },
        )
    private val gitCoordinator =
        com.vayro.app.ui.coordinators.GitCoordinator(
            host = coordinatorHost,
            gitDomainController = gitDomainController,
            gitHubClient = gitHubClient,
            installer = installer,
            preferences = preferences,
            workspaceRootFor = { project -> projectWorkspaceRoot(project) },
            refreshProjectFiles = { refreshProjectFiles() },
            openProject = { project -> openProject(project) },
        )
    private val projectsCoordinator =
        com.vayro.app.ui.coordinators.ProjectsCoordinator(
            host = coordinatorHost,
            preferences = preferences,
            projectWorkspaceController = projectWorkspaceController,
            fileOperationsController = fileOperationsController,
            editorCheckpoints = editorCheckpoints,
            checkpointManager = checkpointManager,
            workspaceRootFor = { project -> projectWorkspaceRoot(project) },
            openProject = { project -> openProject(project) },
            refreshProjectFiles = { refreshProjectFiles() },
        )
    private val editorCoordinator =
        com.vayro.app.ui.coordinators.EditorCoordinator(
            host = coordinatorHost,
            preferences = preferences,
            fileOperationsController = fileOperationsController,
            editorCheckpoints = editorCheckpoints,
            workspaceRootFor = { project -> projectWorkspaceRoot(project) },
        )
    private val runtimeCoordinator =
        com.vayro.app.ui.coordinators.RuntimeCoordinator(
            host = coordinatorHost,
            installer = installer,
            preferences = preferences,
            vault = vault,
            appUpdater = { appUpdater() },
            pingApi = { pingApi() },
            autoUpdateToolsAndSkills = { autoUpdateToolsAndSkills() },
            openProject = { project -> openProject(project) },
            selectAgent = { kind -> selectAgent(kind) },
            refreshActiveApiKey = { kind -> refreshActiveApiKey(kind) },
        )

    val marketplaceInstalledIds: StateFlow<Set<String>> = marketplaceCoordinator.installedIds
    val marketplaceUpdatesAvailable: StateFlow<Set<String>> = marketplaceCoordinator.updatesAvailable
    val visualConsoleLogs: StateFlow<List<com.vayro.app.visual.ConsoleLogEntry>> = visualCoordinator.visualConsoleLogs
    val visualNetworkLogs: StateFlow<List<com.vayro.app.visual.NetworkLogEntry>> = visualCoordinator.visualNetworkLogs
    val inspectedDomElement: StateFlow<com.vayro.app.visual.DomElementInfo?> = visualCoordinator.inspectedDomElement
    val visualSnapshots: StateFlow<List<com.vayro.app.visual.VisualSnapshot>> = visualCoordinator.visualSnapshots
    val visualComparisonPair: StateFlow<com.vayro.app.visual.VisualComparisonPair?> = visualCoordinator.visualComparisonPair
    val activeViewportPreset: StateFlow<com.vayro.app.visual.ViewportPreset> = visualCoordinator.activeViewportPreset
    val detectedPortInfos: StateFlow<List<com.vayro.app.visual.LocalhostPortInfo>> = visualCoordinator.detectedPortInfos
    val detectedLocalhostPorts: StateFlow<List<Int>> = visualCoordinator.detectedLocalhostPorts
    val terminalLines: StateFlow<List<TerminalOutputLine>> = terminalCoordinator.terminalLines
    val isTerminalRunning: StateFlow<Boolean> = terminalCoordinator.isTerminalRunning
    val terminalLiveOutput: StateFlow<String> = terminalCoordinator.terminalLiveOutput
    val terminalCurrentCommand: StateFlow<String?> = terminalCoordinator.terminalCurrentCommand

    /**
     * Milestone 1: checks a project's workspace against the supported resource
     * budgets (file count, total size). Returns SUCCESS when within budget,
     * FAILED with an actionable message naming the exceeded budget otherwise.
     */
    fun checkProjectResources(projectRoot: File): com.vayro.app.core.result.OperationResult {
        return com.vayro.app.core.resources.ResourceGate().checkProject(
            projectRoot,
            com.vayro.app.core.resources.AndroidResourceProbe(getApplication()),
        )
    }

    // ---- Milestone 1: terminal domain moved to TerminalCoordinator. ----
    fun runTerminalCommand(cmd: String) =
        terminalCoordinator.runTerminalCommand(cmd)

    fun sendTerminalInput(text: String) =
        terminalCoordinator.sendTerminalInput(text)

    fun interruptTerminalCommand() =
        terminalCoordinator.interruptTerminalCommand()

    fun clearTerminal() =
        terminalCoordinator.clearTerminal()

    fun requestProjectTerminalCommand(command: String) =
        terminalCoordinator.requestProjectTerminalCommand(command)

    fun prepareProjectTerminalCommand(command: String) =
        terminalCoordinator.prepareProjectTerminalCommand(command)

    fun consumeProjectTerminalDraft() =
        terminalCoordinator.consumeProjectTerminalDraft()

    fun openProjectTerminal() =
        terminalCoordinator.openProjectTerminal()

    fun confirmProjectTerminalCommand() =
        terminalCoordinator.confirmProjectTerminalCommand()

    fun cancelProjectTerminalCommand() =
        terminalCoordinator.cancelProjectTerminalCommand()

    fun stopProjectTerminalCommand() =
        terminalCoordinator.stopProjectTerminalCommand()

    fun sendProjectTerminalInput(text: String) =
        terminalCoordinator.sendProjectTerminalInput(text)

    fun interruptProjectTerminalCommand() =
        terminalCoordinator.interruptProjectTerminalCommand()

    fun clearProjectTerminal() =
        terminalCoordinator.clearProjectTerminal()

    fun createTerminalSession() =
        terminalCoordinator.createTerminalSession()

    fun selectTerminalSession(sessionId: String) =
        terminalCoordinator.selectTerminalSession(sessionId)

    fun closeTerminalSession(sessionId: String) =
        terminalCoordinator.closeTerminalSession(sessionId)

    private fun loadProjectTerminal(project: Project) =
        terminalCoordinator.loadProjectTerminal(project)
    fun activeProjectWorkspaceRoot(): File? = _state.value.activeProject?.let { projectWorkspaceRoot(it) }

    fun projectWorkspaceRoot(project: Project): File {
        val base = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
            .apply { mkdirs() }
            .canonicalFile
        if (project.rootPath.isBlank()) return base
        val selected = File(base, project.rootPath).canonicalFile
        require(selected.toPath().startsWith(base.toPath())) { "Unsafe project root" }
        return selected.apply { mkdirs() }
    }

    fun buildAndRunAndroidApp() {
        val project = _state.value.activeProject ?: return
        if (_state.value.androidBuildRunning) return
        if (!installer.isStackInstalled(DevStack.ANDROID)) {
            _state.update {
                it.copy(toastMessage = "Android build tools are not installed. Add Android in Settings → Development stacks.")
            }
            return
        }
        val storage = runtimeHealthController.checkStorageHealth(com.vayro.app.runtime.RuntimeHealthController.MIN_BUILD_STORAGE_BYTES)
        if (storage is com.vayro.app.runtime.StorageHealthResult.Critical) {
            _state.update { it.copy(toastMessage = storage.message) }
            return
        }
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = "Wait for Claude to finish creating the project before building.") }
            return
        }
        if (_state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Wait for the project terminal command to finish before building.") }
            return
        }
        _state.update { it.copy(androidBuildRunning = true, androidBuildMessage = "Building debug APK…", toastMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val installed = installer.installedRuntime()
                val workspace = findAndroidGradleProjectRoot(projectWorkspaceRoot(project))
                    ?: error("No Android Gradle project found yet. Ask Claude to create it, then wait for the task to finish.")
                val process = installer.process(
                    installed.proot, installed.rootfs, workspace, emptyMap(),
                    listOf(
                        "/usr/bin/bash", "-lc",
                        "gradle --init-script /root/.gradle/init.d/pocketdev-android.gradle " +
                            "-Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 " +
                            "--no-daemon assembleDebug --console=plain",
                    ),
                    guestRootFor(project),
                )
                val exitCode = process.waitFor()
                val rawBuildOutput = (process as? NativeSpawnProcess)?.outputFile?.readTailText(MAX_PROCESS_OUTPUT_BYTES).orEmpty()
                val buildOutput = truncateHeadAndTail(rawBuildOutput, maxHeadLines = 10, maxTailLines = 30, maxTotalLines = 50)
                check(exitCode == 0) {
                    buildOutput.trim().takeLast(2_000).ifBlank { "Gradle build failed (exit code $exitCode)" }
                }
                val apk = workspace.walkTopDown()
                    .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) && it.path.contains("/outputs/apk/debug/") }
                    .maxByOrNull(File::lastModified)
                    ?: error("Gradle finished but no debug APK was found")
                AndroidAppInstaller.install(getApplication(), apk)
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            androidBuildRunning = false,
                            androidBuildMessage = "APK sent to Android installer",
                            toastMessage = "APK built. Complete Android's install prompt.",
                        )
                    }
                }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(androidBuildRunning = false, androidBuildMessage = null, toastMessage = error.message ?: "Could not build APK") }
                }
            }
        }
    }

    private fun findAndroidGradleProjectRoot(workspace: File): File? {
        val settingsNames = setOf("settings.gradle", "settings.gradle.kts", "settings.gradle.dcl")
        return workspace.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && it.name in settingsNames }
            .mapNotNull(File::getParentFile)
            .sortedBy { it.absolutePath.length }
            .firstOrNull { root ->
                root.walkTopDown()
                    .maxDepth(4)
                    .any { it.isFile && it.invariantSeparatorsPath.endsWith("src/main/AndroidManifest.xml") }
            }
    }

    private fun requiresAndroidToolchain(command: String): Boolean =
        Regex("(?m)(^|[;&|]\\s*)(?:\\./)?gradle(?:w)?(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(command)


    fun toggleTheme() {
        val next = if (_state.value.themeMode == com.vayro.app.ui.theme.AppThemeMode.DARK) {
            com.vayro.app.ui.theme.AppThemeMode.LIGHT
        } else {
            com.vayro.app.ui.theme.AppThemeMode.DARK
        }
        setThemeMode(next)
    }

    fun setThemeMode(mode: com.vayro.app.ui.theme.AppThemeMode) {
        preferences.themeMode = mode.name.lowercase()
        _state.update { it.copy(themeMode = mode) }
    }

    /** Font style (Settings): Default = bundled Claude pairing, System = device font. */
    fun setFontStyle(style: com.vayro.app.ui.theme.AppFontStyle) {
        preferences.fontStyle = style.name.lowercase()
        _state.update { it.copy(fontStyle = style) }
    }

    // ---- Milestone 1: security domain moved to SecurityCoordinator. ----
    fun engageEmergencyKillSwitch(reason: String = "User requested emergency stop") =
        securityCoordinator.engageEmergencyKillSwitch(reason)

    fun resetEmergencyKillSwitch() =
        securityCoordinator.resetEmergencyKillSwitch()

    fun isEmergencyKillSwitchEngaged(): Boolean =
        securityCoordinator.isEmergencyKillSwitchEngaged()

    fun refreshPermissionPolicies() =
        securityCoordinator.refreshPermissionPolicies()

    fun updatePermissionPolicy(category: com.vayro.app.security.PermissionCategory, allowed: Boolean) =
        securityCoordinator.updatePermissionPolicy(category, allowed)

    fun loadSecurityAuditLogs(limit: Int = 200) =
        securityCoordinator.loadSecurityAuditLogs(limit)

    fun searchSecurityAuditLogs(
        query: String = "",
        category: com.vayro.app.security.PermissionCategory? = null,
        decision: com.vayro.app.security.AuditDecision? = null,
        limit: Int = 200
    ) = securityCoordinator.searchSecurityAuditLogs(query, category, decision, limit)

    fun clearSecurityAuditLogs() =
        securityCoordinator.clearSecurityAuditLogs()
    // ---- Milestone 1: MCP domain moved to McpCoordinator. ----
    fun refreshMcpServers(projectId: String? = _state.value.activeProject?.id) =
        mcpCoordinator.refreshMcpServers(projectId)

    fun installMcpServer(server: com.vayro.app.mcp.McpServerInfo, secrets: Map<String, String> = emptyMap()) =
        mcpCoordinator.installMcpServer(server, secrets)

    fun installMcpTemplate(templateId: String, envOverrides: Map<String, String> = emptyMap(), projectId: String? = _state.value.activeProject?.id) =
        mcpCoordinator.installMcpTemplate(templateId, envOverrides, projectId)

    fun toggleMcpServer(serverId: String, enabled: Boolean) =
        mcpCoordinator.toggleMcpServer(serverId, enabled)

    fun removeMcpServer(serverId: String) =
        mcpCoordinator.removeMcpServer(serverId)

    fun updateMcpConfig(server: com.vayro.app.mcp.McpServerInfo) =
        mcpCoordinator.updateMcpConfig(server)

    fun discoverMcpTools(server: com.vayro.app.mcp.McpServerInfo) =
        mcpCoordinator.discoverMcpTools(server)

    suspend fun executeMcpTool(
        server: com.vayro.app.mcp.McpServerInfo,
        toolName: String,
        arguments: org.json.JSONObject,
        projectId: String? = _state.value.activeProject?.id
    ): com.vayro.app.runtime.McpToolCallResult =
        mcpCoordinator.executeMcpTool(server, toolName, arguments, projectId)

    fun setMcpProjectPermission(projectId: String, serverId: String, policy: com.vayro.app.mcp.McpPermissionPolicy) =
        mcpCoordinator.setMcpProjectPermission(projectId, serverId, policy)

    fun setMcpGlobalPermission(serverId: String, policy: com.vayro.app.mcp.McpPermissionPolicy) =
        mcpCoordinator.setMcpGlobalPermission(serverId, policy)

    fun mcpToolDecision(serverId: String, toolName: String): com.vayro.app.mcp.McpPermissionDecision =
        mcpCoordinator.mcpToolDecision(serverId, toolName)

    fun runMcpTool(
        server: com.vayro.app.mcp.McpServerInfo,
        toolName: String,
        argsJson: String,
        approval: com.vayro.app.ui.coordinators.McpToolApproval,
    ) {
        val projectId = _state.value.activeProject?.id
        val arguments = runCatching { org.json.JSONObject(argsJson) }.getOrElse { e ->
            _state.update { it.copy(toastMessage = "Invalid JSON arguments: ${e.message}") }
            return
        }
        val decision = mcpCoordinator.mcpToolDecision(server.id, toolName, projectId)
        if (!decision.allowed && !decision.requiresPrompt) {
            _state.update { it.copy(toastMessage = "Tool not allowed: ${decision.reason}") }
            return
        }
        if (approval == com.vayro.app.ui.coordinators.McpToolApproval.ALWAYS_ALLOW) {
            mcpCoordinator.allowMcpToolAlways(server.id, toolName, projectId)
        } else if (decision.requiresPrompt) {
            mcpCoordinator.grantOneTimeMcpToolApproval(server.id, toolName, projectId)
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    mcpRunRunning = true,
                    mcpRunTarget = "${server.name} -> $toolName",
                    mcpRunResult = null
                )
            }
            val result = mcpCoordinator.executeMcpTool(server, toolName, arguments, projectId)
            _state.update { it.copy(mcpRunRunning = false, mcpRunResult = result) }
        }
    }

    fun clearMcpRunResult() {
        _state.update { it.copy(mcpRunResult = null, mcpRunTarget = null) }
    }

    fun loadMcpAuditLogs(limit: Int = 100) =
        mcpCoordinator.loadMcpAuditLogs(limit)

    fun clearMcpAuditLogs() =
        mcpCoordinator.clearMcpAuditLogs()
    fun getSavedApiKey(kind: ProviderKind): String = vault.get(kind.name).orEmpty()

    fun getSavedApiKeys(kind: ProviderKind): List<ApiKeyInfo> = vault.list(kind.name)

    fun addApiKey(kind: ProviderKind, name: String, secret: String): List<ApiKeyInfo> {
        vault.add(kind.name, name, secret)
        val keys = vault.list(kind.name)
        refreshActiveApiKey(kind)
        return keys
    }

    fun activateApiKey(kind: ProviderKind, keyId: String): List<ApiKeyInfo> {
        vault.activate(kind.name, keyId)
        refreshActiveApiKey(kind)
        return vault.list(kind.name)
    }

    fun removeApiKey(kind: ProviderKind, keyId: String): List<ApiKeyInfo> {
        vault.remove(kind.name, keyId)
        refreshActiveApiKey(kind)
        return vault.list(kind.name)
    }

    private fun refreshActiveApiKey(kind: ProviderKind) {
        // v3.2.37 (F2): connecting/removing a catalog key changes what
        // answers home chat — keep the home provider label honest.
        refreshHomeChatProviderLabel()
        if (_state.value.provider.kind != kind) return
        val keys = vault.list(kind.name)
        _state.update { current ->
            current.copy(
                activeApiKeyName = keys.firstOrNull(ApiKeyInfo::isActive)?.name,
                provider = current.provider.copy(hasSecret = keys.isNotEmpty()),
            )
        }
    }

    /** Keeps both agent bridges mapped to the same workspace root; the active one is used. */
    private fun configureBridgeRoots(projectId: String, rootPath: String) {
        claudeRuntime.configureProjectRoot(projectId, rootPath)
        dshRuntime.configureProjectRoot(projectId, rootPath)
        antigravityRuntime.configureProjectRoot(projectId, rootPath)
        codexRuntime.configureProjectRoot(projectId, rootPath)
    }

    init {
        runtimeCoordinator.startSetupSnapshotCollection()
        viewModelScope.launch { claudeRuntime.events.collect(::onRuntimeEvent) }
        runtimeCoordinator.startBootstrap()
    }

    // ---- Milestone 1: runtime domain moved to RuntimeCoordinator. ----
    // (bootstrap is now started via runtimeCoordinator.startBootstrap() from init)

    fun startRuntimeSetup() =
        runtimeCoordinator.startRuntimeSetup()

    fun cancelRuntimeSetup() =
        runtimeCoordinator.cancelRuntimeSetup()

    fun retryStartup() =
        runtimeCoordinator.retryStartup()

    fun backToSetup() =
        runtimeCoordinator.backToSetup()




    fun checkForAppUpdate(force: Boolean = false) =
        runtimeCoordinator.checkForAppUpdate(force)

    /** Set or update the in-app update channel / manifest URL. */
    fun setUpdateManifestUrl(url: String) =
        runtimeCoordinator.setUpdateManifestUrl(url)

    /** Reset the in-app update manifest URL to the default release channel. */
    fun clearUpdateManifestUrl() =
        runtimeCoordinator.clearUpdateManifestUrl()

    /** Set or update the private GitHub Personal Access Token or Deploy Token. */
    fun setUpdateAuthToken(token: String) =
        runtimeCoordinator.setUpdateAuthToken(token)

    fun updateManifestUrl(): String =
        runtimeCoordinator.updateManifestUrl()
    fun updateAuthToken(): String =
        runtimeCoordinator.updateAuthToken()

    /** Debug builds only: persist a manifest URL override and re-check immediately. */
    fun setDebugUpdateManifestUrl(url: String) =
        runtimeCoordinator.setDebugUpdateManifestUrl(url)

    /** Debug builds only: clear the manifest URL override and re-check the default channel. */
    fun clearDebugUpdateManifestUrl() =
        runtimeCoordinator.clearDebugUpdateManifestUrl()

    /** Debug builds only: the currently-active manifest URL override (empty = default). */
    fun debugUpdateManifestUrl(): String =
        runtimeCoordinator.debugUpdateManifestUrl()

    fun installAppUpdate() =
        runtimeCoordinator.installAppUpdate()

    fun cancelAppUpdateDownload() =
        runtimeCoordinator.cancelAppUpdateDownload()

    fun dismissAppUpdateError() =
        runtimeCoordinator.dismissAppUpdateError()

    /**
     * Requests a voice call from outside the workspace chat (chat home).
     * Bumping the nonce is enough: once the workspace chat is composed it
     * sees the new nonce and presents the call (permission flow included).
     */
    fun requestVoiceCall() {
        _state.update { it.copy(voiceCallRequestNonce = System.currentTimeMillis()) }
    }

    fun finishOnboarding(profile: ProviderProfile, secret: String) =
        runtimeCoordinator.finishOnboarding(profile, secret)

    fun finishAntigravityOnboarding() =
        runtimeCoordinator.finishAntigravityOnboarding()

    /** Lets first-run users escape a provider/login failure without losing saved credentials. */
    fun chooseOnboardingAgent(kind: AgentKind) =
        runtimeCoordinator.chooseOnboardingAgent(kind)

    fun updateProvider(profile: ProviderProfile, secret: String) =
        runtimeCoordinator.updateProvider(profile, secret)

    fun finishBackgroundSetup() =
        runtimeCoordinator.finishBackgroundSetup()

    fun finishAppTour() =
        runtimeCoordinator.finishAppTour()

    fun replayAppTour() =
        runtimeCoordinator.replayAppTour()

    /** Called from the first-launch setup screen; persists the agent choice for setup and Settings. */
    fun refreshInstalledAgents() {
        viewModelScope.launch(Dispatchers.IO) {
            val versions = runCatching { installer.installedAgentVersions() }.getOrDefault(emptyMap())
            _state.update { it.copy(installedAgentVersions = versions) }
        }
    }

    fun selectAgent(kind: AgentKind) {
        if (_state.value.agentKind == kind) return
        // Switching agents is instant: tapping another agent stops the current
        // session (if any) and activates the tapped one. No Stop-first step,
        // no restart, no stuck flag.
        if (_state.value.isRunning) {
            stopTask()
        }
        val selectingInitialAgent = !preferences.runtimeSetupComplete
        preferences.agentKind = kind.stableId
        if (selectingInitialAgent) preferences.primaryAgentKind = kind.stableId
        _state.update { current ->
            preferences.saveProvider(current.provider, current.agentKind)
            val provider = preferences.loadProvider(vault, kind)
            current.copy(
                agentKind = kind,
                primaryAgentKind = if (selectingInitialAgent) kind else current.primaryAgentKind,
                provider = provider,
                activeApiKeyName = vault.list(provider.kind.name).firstOrNull(ApiKeyInfo::isActive)?.name,
                // Ping results belong to the previous agent; never leak them across.
                apiPingStatus = ApiPingStatus.IDLE,
                apiPingMessage = null,
            )
        }
        refreshInstalledAgents()
    }

    /** Installs the other agent on demand (Settings) with live progress, then switches to it. */
    fun installAgent(kind: AgentKind) {
        if (_state.value.agentInstalling != null) return
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = "Stop the current agent before switching.") }
            return
        }
        if (installer.isAgentInstalled(kind)) {
            selectAgent(kind)
            return
        }
        _state.update {
            it.copy(
                agentInstalling = kind,
                agentMessage = "Preparing ${kind.title}…",
                agentProgress = 0f,
                agentDownloadedBytes = null,
                agentTotalBytes = null,
                agentBytesPerSecond = null,
            )
        }
        agentInstallJob?.cancel()
        val installJob = viewModelScope.launch {
            var sampleBytes = 0L
            var sampleAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    agentRegistry.require(kind).install(installer) { progress ->
                        val now = SystemClock.elapsedRealtime()
                        val bytes = progress.downloadedBytes
                        val elapsed = now - sampleAt
                        val speed = if (bytes != null && elapsed >= 500L) {
                            ((bytes - sampleBytes).coerceAtLeast(0L) * 1_000L / elapsed.coerceAtLeast(1L)).also {
                                sampleBytes = bytes
                                sampleAt = now
                            }
                        } else _state.value.agentBytesPerSecond
                        _state.update { current ->
                            current.copy(
                                agentMessage = progress.message,
                                agentProgress = progress.fraction.coerceIn(0f, 1f),
                                agentDownloadedBytes = bytes ?: current.agentDownloadedBytes,
                                agentTotalBytes = progress.totalBytes ?: current.agentTotalBytes,
                                agentBytesPerSecond = speed,
                            )
                        }
                    }
                }
            }
            if (!isActive) return@launch
            result.onSuccess {
                if (kind == AgentKind.DEEPSEEK_HARNESS) preferences.dshVersion = installer.dshVersion
                selectAgent(kind)
            }
            _state.update { current ->
                current.copy(
                    installedAgentVersions = installer.installedAgentVersions(),
                    agentInstalling = null,
                    agentProgress = 0f,
                    agentDownloadedBytes = null,
                    agentTotalBytes = null,
                    agentBytesPerSecond = null,
                    agentMessage = result.fold(
                        onSuccess = { "${kind.title} is ready" },
                        onFailure = { _ -> result.exceptionOrNull()?.message?.take(200) ?: "Could not install ${kind.title}" },
                    ),
                )
            }
        }
        agentInstallJob = installJob
        installJob.invokeOnCompletion { if (agentInstallJob === installJob) agentInstallJob = null }
    }

    fun cancelAgentInstall() {
        agentInstallJob?.cancel(CancellationException("Agent install cancelled"))
        agentInstallJob = null
        installer.cancelActiveDownloads()
        _state.update {
            it.copy(
                agentInstalling = null,
                agentProgress = 0f,
                agentDownloadedBytes = null,
                agentTotalBytes = null,
                agentBytesPerSecond = null,
                agentMessage = "Install cancelled",
            )
        }
    }

    fun checkAgentUpdates() {
        if (_state.value.agentUpdatesChecking || _state.value.agentUpdating != null || _state.value.isRunning) return
        _state.update { it.copy(agentUpdatesChecking = true, agentUpdateMessage = "Checking official agent releases…", agentUpdatesCheckError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { installer.checkAgentUpdates() } }
            _state.update {
                it.copy(
                    agentUpdates = result.getOrNull()?.updates.orEmpty(),
                    agentUpdatesChecking = false,
                    agentUpdatesChecked = true,
                    agentUpdatesCheckError = result.exceptionOrNull()?.message?.take(200)
                        ?: result.getOrNull()?.failedChecks?.takeIf { failed -> failed.isNotEmpty() }
                            ?.let { failed -> "Could not check updates for ${failed.joinToString { kind -> kind.title }}" },
                    agentUpdateMessage = result.fold(
                        onSuccess = { check -> agentUpdatesCheckMessage(check.updates, check.failedChecks) },
                        onFailure = { error -> error.message?.take(200) ?: "Could not check agent updates" },
                    ),
                )
            }
        }
    }

    fun updateAgent(kind: AgentKind) {
        val update = _state.value.agentUpdates[kind] ?: return
        if (_state.value.agentUpdating != null || _state.value.agentInstalling != null || _state.value.isRunning) return
        _state.update {
            it.copy(
                agentUpdating = kind,
                agentUpdateMessage = "Preparing ${kind.title} ${update.latestVersion}…",
                agentUpdateProgress = 0f,
                agentUpdateDownloadedBytes = null,
                agentUpdateTotalBytes = null,
                agentUpdateBytesPerSecond = null,
            )
        }
        agentUpdateJob?.cancel()
        val updateJob = viewModelScope.launch {
            var sampleBytes = 0L
            var sampleAt = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    installer.updateAgent(kind, update.latestVersion) { progress ->
                        val now = SystemClock.elapsedRealtime()
                        val bytes = progress.downloadedBytes
                        val elapsed = now - sampleAt
                        val speed = if (bytes != null && elapsed >= 500L) {
                            ((bytes - sampleBytes).coerceAtLeast(0L) * 1_000L / elapsed.coerceAtLeast(1L)).also {
                                sampleBytes = bytes
                                sampleAt = now
                            }
                        } else _state.value.agentUpdateBytesPerSecond
                        _state.update {
                            it.copy(
                                agentUpdateMessage = progress.message,
                                agentUpdateProgress = progress.fraction.coerceIn(0f, 1f),
                                agentUpdateDownloadedBytes = bytes ?: it.agentUpdateDownloadedBytes,
                                agentUpdateTotalBytes = progress.totalBytes ?: it.agentUpdateTotalBytes,
                                agentUpdateBytesPerSecond = speed,
                            )
                        }
                    }
                }
            }
            if (!isActive) return@launch
            _state.update { current ->
                current.copy(
                    installedAgentVersions = installer.installedAgentVersions(),
                    agentUpdates = if (result.isSuccess) current.agentUpdates - kind else current.agentUpdates,
                    agentUpdating = null,
                    agentUpdateMessage = result.fold(
                        onSuccess = { "${kind.title} updated to ${update.latestVersion}" },
                        onFailure = { error -> error.message?.take(220) ?: "Could not update ${kind.title}" },
                    ),
                    agentUpdateProgress = if (result.isSuccess) 1f else 0f,
                    agentUpdateDownloadedBytes = null,
                    agentUpdateTotalBytes = null,
                    agentUpdateBytesPerSecond = null,
                )
            }
        }
        agentUpdateJob = updateJob
        updateJob.invokeOnCompletion { if (agentUpdateJob === updateJob) agentUpdateJob = null }
    }

    fun cancelAgentUpdate() {
        agentUpdateJob?.cancel(CancellationException("Agent update cancelled"))
        agentUpdateJob = null
        installer.cancelActiveDownloads()
        _state.update {
            it.copy(
                agentUpdating = null,
                agentUpdateProgress = 0f,
                agentUpdateDownloadedBytes = null,
                agentUpdateTotalBytes = null,
                agentUpdateBytesPerSecond = null,
                agentUpdateMessage = "Update cancelled",
            )
        }
    }

    fun startAntigravityLogin() {
        if (_state.value.agentInstalling != null || _state.value.isRunning) return
        lastOpenedAntigravityAuthUrl = null
        viewModelScope.launch { antigravityAuthController.beginLogin() }
    }

    fun submitAntigravityCode(code: String) {
        runCatching { antigravityAuthController.submitCode(code) }
            .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: "Could not submit the code") } }
    }

    fun logoutAntigravity() {
        viewModelScope.launch {
            runCatching { antigravityAuthController.logout() }
                .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: "Could not sign out") } }
        }
    }

    fun startAntigravityAddAccount() {
        if (_state.value.agentInstalling != null || _state.value.isRunning) return
        lastOpenedAntigravityAuthUrl = null
        viewModelScope.launch { antigravityAuthController.beginAddAccount() }
    }

    fun switchAntigravityAccount(email: String) {
        val success = antigravityAuthController.activateAccount(email)
        if (success) {
            _state.update { it.copy(toastMessage = "Switched to $email") }
        }
    }

    fun removeAntigravityAccount(email: String) {
        viewModelScope.launch {
            antigravityAuthController.removeAccount(email)
            _state.update { it.copy(toastMessage = "Removed $email") }
        }
    }

    fun toggleAntigravityAutoRoundRobin(enabled: Boolean) {
        preferences.antigravityAutoRoundRobin = enabled
        antigravityAuthController.setAutoRoundRobin(enabled)
    }

    fun setAntigravityModel(model: String) {
        preferences.antigravityModel = model
        val modelEffort = antigravityEffortFromModel(model)
        if (modelEffort != null) preferences.antigravityEffort = modelEffort
        _state.update {
            it.copy(
                antigravityModel = model,
                antigravityEffort = modelEffort ?: it.antigravityEffort,
            )
        }
    }

    fun setGeminiApiMode(enabled: Boolean) {
        geminiApiStore.apiModeEnabled = enabled
        _state.update { it.copy(geminiApiMode = enabled) }
    }

    fun saveGeminiApiKey(key: String) {
        if (key.isBlank()) return
        geminiApiStore.setApiKey(key)
        _state.update { it.copy(geminiApiKeySaved = true) }
    }

    fun clearGeminiApiKey() {
        geminiApiStore.clearApiKey()
        _state.update { it.copy(geminiApiKeySaved = false) }
    }

    fun setGeminiApiModel(model: String) {
        if (com.vayro.app.data.GEMINI_API_MODELS.none { it.first == model }) return
        geminiApiStore.apiModel = model
        _state.update { it.copy(geminiApiModel = model) }
    }

    fun setAntigravityEffort(effort: String) {
        if (effort !in setOf("low", "medium", "high")) return
        val current = _state.value
        val matchingModel = antigravityModelWithEffort(current.antigravityModel, effort)
            ?.takeIf { candidate -> current.antigravityModels.isEmpty() || candidate in current.antigravityModels }
        if (current.antigravityModel.isNotBlank() &&
            antigravityEffortFromModel(current.antigravityModel) != null &&
            matchingModel == null
        ) {
            _state.update { it.copy(toastMessage = "This model does not offer ${effort.replaceFirstChar(Char::uppercase)} reasoning") }
            return
        }
        preferences.antigravityEffort = effort
        matchingModel?.let { preferences.antigravityModel = it }
        _state.update {
            it.copy(
                antigravityEffort = effort,
                antigravityModel = matchingModel ?: it.antigravityModel,
            )
        }
    }

    fun refreshAntigravityModels() {
        if (_state.value.antigravityModelsLoading || !installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) return
        _state.update { it.copy(antigravityModelsLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val runtime = installer.installedRuntime()
                val workspace = File(getApplication<Application>().filesDir, "workspaces/antigravity-models").apply { mkdirs() }
                val process = installer.process(
                    runtime.proot,
                    runtime.rootfs,
                    workspace,
                    emptyMap(),
                    listOf(com.vayro.app.runtime.RuntimeInstaller.AGY_GUEST_PATH, "models"),
                    guestWorkspacePath = "/workspace/antigravity-models",
                    emulateHardLinks = false,
                    extraBinds = installer.antigravityCredentialBinds(),
                )
                while (process.isAlive) delay(50)
                check(process.waitFor() == 0) { "Could not list Antigravity models" }
                val output = (process as? NativeSpawnProcess)?.outputFile?.readTailText(MAX_PROCESS_OUTPUT_BYTES).orEmpty()
                output.lineSequence()
                    .map { sanitizeTerminalOutput(it).trim() }
                    .mapNotNull { line -> line.split(Regex("\\s+"), limit = 2).firstOrNull() }
                    .filter { it.matches(Regex("[a-z0-9][a-z0-9._-]+")) }
                    .distinct()
                    .toList()
                    .also { check(it.isNotEmpty()) { "Antigravity returned no models" } }
            }
            withContext(Dispatchers.Main) {
                _state.update { current ->
                    result.fold(
                        onSuccess = { models ->
                            val preferred = antigravityModelWithEffort(
                                current.antigravityModel,
                                current.antigravityEffort,
                            )?.takeIf(models::contains)
                            val selected = preferred
                                ?: current.antigravityModel.takeIf(models::contains)
                                ?: models.first()
                            val selectedEffort = antigravityEffortFromModel(selected) ?: current.antigravityEffort
                            preferences.antigravityModel = selected
                            preferences.antigravityEffort = selectedEffort
                            current.copy(
                                antigravityModelsLoading = false,
                                antigravityModels = models,
                                antigravityModel = selected,
                                antigravityEffort = selectedEffort,
                            )
                        },
                        onFailure = { error -> current.copy(
                            antigravityModelsLoading = false,
                            toastMessage = error.message ?: "Could not load Antigravity models",
                        ) },
                    )
                }
            }
        }
    }

    /** Called from the first-launch tool picker; persists the choice for setup and Settings. */
    fun toggleDevStack(stack: DevStack) =
        runtimeCoordinator.toggleDevStack(stack)

    /** Installs one development stack on demand (Settings) with live progress. */
    fun installDevStack(stack: DevStack) =
        runtimeCoordinator.installDevStack(stack)

    /** Updates an installed optional toolchain in place. */
    fun updateDevStack(stack: DevStack) =
        runtimeCoordinator.updateDevStack(stack)

    /** Removes an optional toolchain after the Settings confirmation dialog. */
    fun removeDevStack(stack: DevStack) =
        runtimeCoordinator.removeDevStack(stack)

    suspend fun discoverModels(profile: ProviderProfile, secret: String): ModelDiscoveryResult {
        val key = secret.ifBlank { vault.get(profile.kind.name).orEmpty() }
        return providerApi.discoverModels(profile.baseUrl, key, providerProtocolForAgent(profile, _state.value.agentKind))
    }

    suspend fun validateProvider(
        profile: ProviderProfile,
        secret: String,
        models: List<com.vayro.app.network.DiscoveredModel>,
    ): ConnectionValidation {
        val key = secret.ifBlank { vault.get(profile.kind.name).orEmpty() }
        return providerApi.validate(
            profile.baseUrl,
            profile.model,
            key,
            providerProtocolForAgent(profile, _state.value.agentKind),
            models,
            profile.openRouterProviderOrder,
            profile.openRouterAllowFallbacks,
        )
    }

    fun pingApi() {
        if (_state.value.agentKind == AgentKind.ANTIGRAVITY) {
            testAntigravityConnection()
            return
        }
        val profile = _state.value.provider
        if (profile.baseUrl.isBlank() || profile.model.isBlank()) return
        if (_state.value.apiPingStatus == ApiPingStatus.PINGING) return
        _state.update { it.copy(apiPingStatus = ApiPingStatus.PINGING, apiPingMessage = "Sending a minimal test request…") }
        viewModelScope.launch {
            val key = vault.get(profile.kind.name).orEmpty()
            val result = providerApi.validate(
                profile.baseUrl,
                profile.model,
                key,
                providerProtocolForAgent(profile, _state.value.agentKind),
                emptyList(),
                profile.openRouterProviderOrder,
                profile.openRouterAllowFallbacks,
            )
            when (result) {
                is ConnectionValidation.Success -> _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.OK, apiPingMessage = "API responded successfully")
                }
                is ConnectionValidation.Failure -> _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.FAILED, apiPingMessage = result.message)
                }
            }
        }
    }

    /** Sends a tiny hello to the agy CLI to prove it actually answers. Silent timeout inside. */
    fun testAntigravityConnection() {
        if (_state.value.agentKind != AgentKind.ANTIGRAVITY) return
        if (_state.value.apiPingStatus == ApiPingStatus.PINGING) return
        val apiConfig = currentGeminiApiConfig()
        if (apiConfig != null) {
            _state.update {
                it.copy(apiPingStatus = ApiPingStatus.PINGING, apiPingMessage = "Testing Gemini API…")
            }
            viewModelScope.launch {
                runCatching {
                    com.vayro.app.runtime.GeminiApiClient().probe(apiConfig.apiKey, apiConfig.model)
                }.onSuccess {
                    _state.update {
                        it.copy(apiPingStatus = ApiPingStatus.OK, apiPingMessage = "Gemini API is working!")
                    }
                }.onFailure { error ->
                    _state.update {
                        it.copy(
                            apiPingStatus = ApiPingStatus.FAILED,
                            apiPingMessage = error.message.orEmpty().ifBlank { "Gemini API test failed." },
                        )
                    }
                }
            }
            return
        }
        if (_state.value.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN) {
            _state.update {
                it.copy(
                    apiPingStatus = ApiPingStatus.FAILED,
                    apiPingMessage = "Antigravity needs Google sign-in",
                )
            }
            return
        }
        _state.update { it.copy(apiPingStatus = ApiPingStatus.PINGING, apiPingMessage = "Saying hello to Antigravity…") }
        viewModelScope.launch {
            val result = runCatching { antigravityRuntime.hello() }
            result.onSuccess {
                _state.update {
                    it.copy(
                        apiPingStatus = ApiPingStatus.OK,
                        apiPingMessage = "Antigravity is Working!",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.FAILED, apiPingMessage = helloFailureMessage(error.message.orEmpty()))
                }
            }
        }
    }

    private fun helloFailureMessage(raw: String): String {
        val value = raw.replace(Regex("\\s+"), " ").trim()
        return when {
            value.contains("not installed", true) -> "Antigravity CLI is not installed. Install it from the Coding agent section."
            value.contains("sign-in", true) || value.contains("not signed in", true) ||
                value.contains("authentication", true) -> "Antigravity needs Google sign-in. Reconnect from the Google connection section."
            value.contains("did not answer", true) -> "Antigravity did not answer. Try again."
            value.isBlank() -> "Antigravity did not answer. Try again."
            else -> value.take(200)
        }
    }

    fun openProject(project: Project) {
        val current = _state.value
        if (current.activeProject?.id == project.id) {
            _state.update {
                it.copy(
                    workspaceVisible = true,
                    readOnlyProject = null,
                    readOnlyProjectChats = emptyList(),
                    readOnlyChatId = null,
                    readOnlyMessages = emptyList(),
                )
            }
            return
        }
        current.activeProject?.let { persistSessionSlice(it.id) }
        preferences.lastActiveProjectId = project.id
        val terminal = loadProjectTerminal(project)
        // Milestone 2: restore the persisted editor session (dirty buffers,
        // cursor/scroll, split state) before the UI reads tab state.
        editorCoordinator.restoreEditorSession(project)
        val savedTabs = preferences.getOpenEditorTabs(project.id)
        val initialOpenedPath = savedTabs.firstOrNull()
        val suggestedRoot = if (project.rootPath.isBlank()) detectNestedProjectRoot(project) else null
        val chats = preferences.loadProjectChats(project.id).ifEmpty {
            listOf(ProjectChat(title = "Main chat")).also { preferences.saveProjectChats(project.id, it) }
        }
        val lastChatId = preferences.getLastActiveChatId(project.id)
        val activeChat = chats.firstOrNull { it.id == lastChatId } ?: chats.first()
        preferences.setLastActiveChatId(project.id, activeChat.id)
        val saved = preferences.loadMessages(project.id, activeChat.id)
        val msgs = saved.ifEmpty { listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")) }
        val loadedRoadmap = msgs.mapNotNull { it.roadmap }.lastOrNull()
        val loadedScratchpad = deriveScratchpadItems(loadedRoadmap, emptyList(), false)
        val slice = projectSessionStates.value[project.id]
        _state.update {
            it.copy(
                activeProject = project,
                workspaceVisible = true,
                readOnlyProject = null,
                readOnlyProjectChats = emptyList(),
                readOnlyChatId = null,
                readOnlyMessages = emptyList(),
                projectChats = chats,
                activeChatId = activeChat.id,
                messages = slice?.messages?.takeIf { m -> m.isNotEmpty() } ?: msgs,
                pendingApproval = slice?.pendingApproval,
                activeRoadmap = slice?.activeRoadmap ?: loadedRoadmap,
                scratchpadItems = slice?.scratchpadItems ?: loadedScratchpad,
                liveProcess = slice?.liveProcess ?: emptyList(),
                liveThinking = slice?.liveThinking ?: false,
                activeThinkingBlockId = slice?.activeThinkingBlockId,
                taskStartedAtMillis = slice?.taskStartedAtMillis,
                taskFinishedAtMillis = slice?.taskFinishedAtMillis,
                workSegmentStartedAtMillis = slice?.workSegmentStartedAtMillis,
                currentTaskRequest = slice?.currentTaskRequest,
                changes = slice?.changes ?: emptyList(),
                activity = slice?.activity ?: emptyList(),
                isRunning = slice?.isRunning ?: false,
                streamingMessageId = slice?.streamingMessageId,
                activeSessionId = slice?.activeSessionId,
                interruptedSession = slice?.interruptedSession,
                runtimeRecoveryStatus = slice?.runtimeRecoveryStatus,
                runtimeRecoveryAttempt = slice?.runtimeRecoveryAttempt ?: 0,
                runtimeRecoveryCanResume = slice?.runtimeRecoveryCanResume ?: false,
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = true,
                openedFilePath = initialOpenedPath,
                openedFileContent = null,
                openEditorTabs = savedTabs,
                fileContentLoading = initialOpenedPath != null,
                projectTerminalLines = terminal.lines,
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = terminal.cwd,
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                // Milestone 2: restore persisted terminal session tabs.
                terminalSessions = terminal.sessions.ifEmpty {
                    listOf(TerminalSessionTab(id = "term-1", title = "Terminal 1"))
                },
                activeTerminalSessionId = terminal.activeSessionId
                    ?: terminal.sessions.firstOrNull()?.id ?: "term-1",
                suggestedProjectRoot = suggestedRoot,
                previewReady = slice?.previewReady ?: false,
                previewUrl = slice?.previewUrl,
                pendingAttachments = emptyList(),
            )
        }
        slice?.pendingOrchestratorTransition?.let { transition ->
            agentOrchestrator.transition(transition.state, transition.stepTitle, transition.details)
            agentOrchestrator.saveState(getApplication<Application>().filesDir, project.id)
            projectSessionStates.update { states ->
                states + (project.id to (states[project.id] ?: ProjectSessionState(project.id)).copy(pendingOrchestratorTransition = null))
            }
        } ?: run {
            agentOrchestrator.restoreActiveSession(project.id, getApplication<Application>().filesDir)
        }
        if (initialOpenedPath != null) {
            loadFileContent(project, initialOpenedPath)
        }
        refreshProjectFiles()
        viewModelScope.launch {
            val pending = sessionBridge(project.id).loadPendingChanges(project.id)
            if (_state.value.activeProject?.id == project.id) _state.update { it.copy(changes = pending) }
        }
    }

    fun closeProject() {
        preferences.lastActiveProjectId = null
        val active = _state.value.activeProject
        persistMessages()
        if (_state.value.isRunning || _state.value.projectTerminalRunning) {
            _state.update {
                it.copy(
                    workspaceVisible = false,
                    toastMessage = if (it.isRunning) {
                        "Task continues in the background"
                    } else {
                        "Terminal command continues in the background"
                    },
                )
            }
            return
        }

        if (active != null) {
            val chats = preferences.loadProjectChats(active.id)
            val userMessages = chats.sumOf { preferences.loadMessages(active.id, it.id).count { m -> m.fromUser } }
            val workspaceDir = File(getApplication<Application>().filesDir, "workspaces/${active.id}")
            val userFiles = if (workspaceDir.isDirectory) {
                workspaceDir.walkTopDown().filter { file ->
                    file.isFile && !file.name.startsWith(".claude") && file.name != ".pocket-dev-stacks.json"
                }.count()
            } else 0

            if (userMessages == 0 && userFiles == 0 && !_state.value.isRunning && !_state.value.projectTerminalRunning) {
                // Unused empty project; delete immediately so it does not clutter the project list.
                _state.update { current -> current.copy(projects = current.projects.filterNot { it.id == active.id }) }
                preferences.saveProjects(_state.value.projects)
                viewModelScope.launch(Dispatchers.IO) {
                    workspaceDir.deleteRecursively()
                    terminalCoordinator.deleteTerminalHistory(active.id)
                    preferences.deleteProjectChats(active.id)
                }
            }
        }

        _state.update {
            it.copy(
                activeProject = null,
                workspaceVisible = false,
                projectChats = emptyList(),
                activeChatId = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = false,
                openedFilePath = null,
                openedFileContent = null,
                openEditorTabs = emptyList(),
                fileContentLoading = false,
                isRunning = false,
                streamingMessageId = null,
                activeSessionId = null,
                pendingApproval = null,
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = "/workspace",
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = null,
                previewReady = false,
                previewUrl = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun closeReadOnlyProject() {
        _state.update {
            it.copy(
                readOnlyProject = null,
                readOnlyProjectChats = emptyList(),
                readOnlyChatId = null,
                readOnlyMessages = emptyList(),
            )
        }
    }

    fun switchReadOnlyChat(chatId: String) {
        val project = _state.value.readOnlyProject ?: return
        if (_state.value.readOnlyProjectChats.none { it.id == chatId }) return
        _state.update {
            it.copy(
                readOnlyChatId = chatId,
                readOnlyMessages = preferences.loadMessages(project.id, chatId),
            )
        }
    }

    fun activateReadOnlyProject() {
        val project = _state.value.readOnlyProject ?: return
        closeReadOnlyProject()
        openProject(project)
    }

    /** Routes a notification tap into the existing project workspace instead of creating a second navigation stack. */
    fun handleNotificationRoute(route: String?) {
        val normalized = route?.trim().orEmpty()
        if (normalized.isBlank()) return

        if (normalized == "update" || normalized == "app-update") {
            // Update-notification tap: a forced check bumps
            // appUpdatePromptNonce when an update is available, which is
            // what opens the root update dialog (PocketDevApp). When the
            // app is already up to date the check answers with a toast.
            checkForAppUpdate(force = true)
            return
        }

        if (normalized.startsWith("browser:") || normalized.startsWith("http://") || normalized.startsWith("https://")) {
            val targetUrl = normalized.removePrefix("browser:").trim()
            if (targetUrl.isNotBlank()) {
                val hasProject = _state.value.activeProject != null
                if (!hasProject) {
                    val defaultProj = _state.value.projects.firstOrNull()
                    if (defaultProj != null) {
                        openProject(defaultProj)
                    } else {
                        createQuickProject()
                    }
                }
                _state.update {
                    it.copy(
                        previewReady = true,
                        previewUrl = targetUrl,
                        workspaceVisible = true,
                    )
                }
            }
            return
        }

        val segments = normalized.trim('/').split('/').filter(String::isNotBlank)
        val slug = segments.getOrNull(1).takeIf { segments.firstOrNull() == "project" }
        if (slug == null) return
        val project = _state.value.projects.firstOrNull { it.slug == slug }
        if (project == null) {
            _state.update { it.copy(toastMessage = "The project from this notification is no longer available") }
            return
        }
        openProject(project)
    }
    /**
     * Opens a link from an agent chat message in the workspace Browser tab.
     * Setting preview state is enough: the workspace watches it and switches
     * to the Preview tab on its own.
     */
    fun openPreviewUrl(url: String) {
        val target = url.trim()
        if (target.isBlank()) return
        _state.update { it.copy(previewReady = true, previewUrl = target) }
    }

    fun consumeToast() = _state.update { it.copy(toastMessage = null) }
    fun showToast(message: String) = _state.update { it.copy(toastMessage = message) }

    fun createProject(name: String, folderUri: Uri? = null) {
        if (name.isBlank() && folderUri == null) return
        if (folderUri != null) {
            _state.update { it.copy(projectImporting = true, projectImportMessage = "Creating project and binding folder…") }
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val app = getApplication<Application>()
                        val documentFile = DocumentFile.fromTreeUri(app, folderUri)
                        val folderName = documentFile?.name?.trim()?.takeIf { it.isNotBlank() } ?: "Project"
                        val effectiveName = if (name.isNotBlank()) name.trim() else folderName
                        val baseSlug = projectSlug(effectiveName)
                        val usedSlugs = _state.value.projects.mapTo(mutableSetOf()) { it.slug }
                        val slug = generateSequence(1) { it + 1 }
                            .map { number -> if (number == 1) baseSlug else "$baseSlug-$number" }
                            .first { it !in usedSlugs }
                        val projectId = UUID.randomUUID().toString()
                        val destination = File(app.filesDir, "workspaces/$projectId").apply { mkdirs() }
                        val copiedCount = copyTreeToDirectory(folderUri, destination)
                        val preliminary = Project(
                            id = projectId,
                            name = effectiveName,
                            description = "Imported from device storage",
                            language = "General",
                            slug = slug,
                        )
                        val nestedRoot = detectNestedProjectRoot(preliminary)
                        val projectRoot = nestedRoot?.let { File(destination, it) } ?: destination
                        val metadata = detectImportedProjectMetadata(projectRoot)
                        val project = preliminary.copy(
                            description = metadata.first.ifBlank { "Local device repository" },
                            language = metadata.second.ifBlank { "General" },
                            rootPath = nestedRoot.orEmpty(),
                        )
                        project to copiedCount
                    }
                }
                result.onSuccess { (project, count) ->
                    val firstChat = ProjectChat(title = "New chat")
                    preferences.saveProjectChats(project.id, listOf(firstChat))
                    _state.update { current ->
                        current.copy(
                            projects = listOf(project) + current.projects,
                            projectImporting = false,
                            projectImportMessage = null,
                            toastMessage = "${project.name} created ($count files bound)",
                        )
                    }
                    preferences.saveProjects(_state.value.projects)
                    openProject(project)
                }.onFailure { error ->
                    _state.update {
                        it.copy(
                            projectImporting = false,
                            projectImportMessage = null,
                            toastMessage = "Project creation failed: ${error.message?.take(180) ?: "Unknown error"}",
                        )
                    }
                }
            }
            return
        }
        val baseSlug = projectSlug(name)
        val usedSlugs = _state.value.projects.mapTo(mutableSetOf()) { it.slug }
        val slug = generateSequence(1) { it + 1 }
            .map { number -> if (number == 1) baseSlug else "$baseSlug-$number" }
            .first { it !in usedSlugs }
        val project = Project(
            name = name.trim(),
            description = "Starter web project",
            language = "TypeScript",
            slug = slug,
        )

        val current = _state.value
        if (current.isRunning || current.projectTerminalRunning) {
            // Create and persist the project without replacing the workspace state
            // owned by the currently running agent/terminal session.
            File(getApplication<Application>().filesDir, "workspaces/${project.id}").mkdirs()
            val firstChat = ProjectChat(title = "New chat")
            preferences.saveProjectChats(project.id, listOf(firstChat))
            val projects = listOf(project) + current.projects
            preferences.saveProjects(projects)
            _state.update {
                it.copy(
                    projects = projects,
                    toastMessage = "${project.name} created. The current task continues in the background.",
                )
            }
            return
        }

        configureBridgeRoots(project.id, project.rootPath)
        val guestRoot = guestRootFor(project)
        _state.update {
            it.copy(
                projects = listOf(project) + it.projects,
                activeProject = project,
                workspaceVisible = true,
                messages = listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")),
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = true,
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = guestRoot,
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = null,
                previewReady = false,
                previewUrl = null,
            )
        }
        preferences.saveProjects(_state.value.projects)
        File(getApplication<Application>().filesDir, "workspaces/${project.id}").mkdirs()
        val firstChat = ProjectChat(title = "New chat")
        preferences.saveProjectChats(project.id, listOf(firstChat))
        _state.update { it.copy(projectChats = listOf(firstChat), activeChatId = firstChat.id) }
        refreshProjectFiles()
    }

    // ---- Milestone 1: project lifecycle moved to ProjectsCoordinator. ----
    fun createQuickProject() =
        projectsCoordinator.createQuickProject()

    fun importZipProject(uri: Uri) {
        if (_state.value.projectImporting || _state.value.isRunning || _state.value.projectTerminalRunning) return
        _state.update { it.copy(projectImporting = true, projectImportMessage = "Reading project archive…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { extractImportedProject(uri) } }
            result.onSuccess { imported ->
                val project = imported.project
                val firstChat = ProjectChat(title = "New chat")
                preferences.saveProjectChats(project.id, listOf(firstChat))
                _state.update { current ->
                    current.copy(
                        projects = listOf(project) + current.projects,
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = "${project.name} imported successfully",
                    )
                }
                preferences.saveProjects(_state.value.projects)
                openProject(project)
                _state.update { it.copy(pendingAttachments = listOf(imported.sourceAttachment)) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = "Import failed: ${error.message?.take(180) ?: "Invalid ZIP archive"}",
                    )
                }
            }
        }
    }

    private fun extractImportedProject(uri: Uri): ImportedZipProject {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        var archiveName = "Imported project.zip"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                    archiveName = cursor.getString(index) ?: archiveName
                }
            }
        }
        val identity = generateQuickChatIdentity(_state.value.projects.mapTo(mutableSetOf()) { it.slug })
        val projectId = UUID.randomUUID().toString()
        val destination = File(app.filesDir, "workspaces/$projectId")
        destination.mkdirs()
        val destinationPath = destination.canonicalFile.toPath()
        val availableLimit = (destination.usableSpace * 8L / 10L).coerceAtMost(MAX_IMPORTED_PROJECT_BYTES)
        var extractedBytes = 0L
        var entries = 0
        try {
            val source = resolver.openInputStream(uri) ?: error("The selected ZIP could not be opened")
            source.buffered().use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= MAX_IMPORTED_ZIP_ENTRIES) { "The ZIP contains too many files" }
                        val entryName = entry.name.replace('\\', '/').trimStart('/')
                        require(entryName.isNotBlank() && '\u0000' !in entryName) { "The ZIP contains an invalid path" }
                        if (entryName.startsWith("__MACOSX/") || entryName.endsWith("/.DS_Store") || entryName == ".DS_Store") {
                            zip.closeEntry()
                            continue
                        }
                        val target = File(destination, entryName).canonicalFile
                        require(target.toPath().startsWith(destinationPath)) { "The ZIP contains an unsafe path" }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    extractedBytes += count
                                    require(extractedBytes <= availableLimit) { "The extracted project is too large for available storage" }
                                    output.write(buffer, 0, count)
                                }
                            }
                            if (entry.time > 0) target.setLastModified(entry.time)
                        }
                        zip.closeEntry()
                    }
                }
            }
            require(entries > 0 && destination.walkTopDown().any { it.isFile }) { "The ZIP does not contain project files" }
            val preliminary = Project(
                id = projectId,
                name = identity.displayName,
                description = "Imported project workspace",
                language = "General",
                slug = identity.slug,
                kind = ProjectKind.QUICK_PROJECT,
            )
            val nestedRoot = detectNestedProjectRoot(preliminary)
            val projectRoot = nestedRoot?.let { File(destination, it) } ?: destination
            val metadata = detectImportedProjectMetadata(projectRoot)
            val safeArchiveName = sanitizeAttachmentName(archiveName).let { name ->
                if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"
            }
            val archiveFolder = File(projectRoot, ".pocketdev/imports").apply { mkdirs() }
            val archivedSource = File(archiveFolder, safeArchiveName)
            var sourceBytes = 0L
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                archivedSource.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sourceBytes += count
                        require(extractedBytes + sourceBytes <= availableLimit) { "The imported project is too large for available storage" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("The selected ZIP could not be preserved")
            val project = preliminary.copy(
                description = metadata.first,
                language = metadata.second,
                rootPath = nestedRoot.orEmpty(),
            )
            return ImportedZipProject(
                project = project,
                sourceAttachment = ChatAttachment(
                    displayName = archiveName.take(120),
                    relativePath = archivedSource.relativeTo(projectRoot).invariantSeparatorsPath,
                    mimeType = "application/zip",
                    sizeBytes = sourceBytes,
                ),
            )
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw error
        }
    }

    fun importFolderProject(uri: Uri) {
        if (_state.value.projectImporting || _state.value.isRunning || _state.value.projectTerminalRunning) return
        _state.update { it.copy(projectImporting = true, projectImportMessage = "Importing folder from device…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { copyFolderToWorkspace(uri) } }
            result.onSuccess { project ->
                val firstChat = ProjectChat(title = "New chat")
                preferences.saveProjectChats(project.id, listOf(firstChat))
                _state.update { current ->
                    current.copy(
                        projects = listOf(project) + current.projects,
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = "${project.name} imported from device storage",
                    )
                }
                preferences.saveProjects(_state.value.projects)
                openProject(project)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = "Folder import failed: ${error.message?.take(180) ?: "Unknown error"}",
                    )
                }
            }
        }
    }

    private fun copyTreeToDirectory(treeUri: Uri, destinationDir: File): Int {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val documentFile = DocumentFile.fromTreeUri(app, treeUri)
            ?: error("Cannot access the selected device folder")
        var copiedCount = 0
        val maxFiles = 3000

        fun copyTree(doc: DocumentFile, targetDir: File) {
            targetDir.mkdirs()
            val files = doc.listFiles()
            for (f in files) {
                val name = f.name ?: continue
                if (name in setOf(".git", ".gradle", "build", "node_modules", ".idea", ".DS_Store", "__pycache__", ".next")) {
                    continue
                }
                val destFile = File(targetDir, name)
                if (f.isDirectory) {
                    copyTree(f, destFile)
                } else if (f.isFile) {
                    if (copiedCount++ > maxFiles) break
                    app.contentResolver.openInputStream(f.uri)?.use { inStream ->
                        destFile.outputStream().use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                }
            }
        }

        copyTree(documentFile, destinationDir)
        return copiedCount
    }

    private fun copyFolderToWorkspace(treeUri: Uri): Project {
        val app = getApplication<Application>()
        val documentFile = DocumentFile.fromTreeUri(app, treeUri)
            ?: error("Cannot access the selected device folder")
        val rawName = documentFile.name
        val folderName = if (rawName.isNullOrBlank()) "Device Project" else rawName.trim()
        val identity = generateQuickChatIdentity(_state.value.projects.mapTo(mutableSetOf()) { it.slug })
        val projectId = UUID.randomUUID().toString()
        val destination = File(app.filesDir, "workspaces/$projectId").apply { mkdirs() }

        copyTreeToDirectory(treeUri, destination)

        val preliminary = Project(
            id = projectId,
            name = folderName,
            description = "Imported from device storage",
            language = "General",
            slug = identity.slug,
            kind = ProjectKind.PROJECT,
        )
        val nestedRoot = detectNestedProjectRoot(preliminary)
        val projectRoot = nestedRoot?.let { File(destination, it) } ?: destination
        val metadata = detectImportedProjectMetadata(projectRoot)

        return preliminary.copy(
            description = metadata.first.ifBlank { "Local device repository" },
            language = metadata.second.ifBlank { "General" },
            rootPath = nestedRoot.orEmpty(),
        )
    }

    fun bindFolderToActiveProject(treeUri: Uri) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.projectImporting || current.isRunning || current.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Wait for active tasks to finish before binding a folder") }
            return
        }
        _state.update { it.copy(projectImporting = true, projectImportMessage = "Binding device folder to ${project.name}…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val app = getApplication<Application>()
                    val destination = File(app.filesDir, "workspaces/${project.id}").apply { mkdirs() }
                    val copiedCount = copyTreeToDirectory(treeUri, destination)
                    val nestedRoot = detectNestedProjectRoot(project)
                    val projectRoot = nestedRoot?.let { File(destination, it) } ?: destination
                    val metadata = detectImportedProjectMetadata(projectRoot)
                    val updated = project.copy(
                        description = if (project.description == "Starter web project") metadata.first.ifBlank { project.description } else project.description,
                        language = if (project.language == "TypeScript" && metadata.second != "TypeScript") metadata.second else project.language,
                        rootPath = nestedRoot.orEmpty(),
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                    updated to copiedCount
                }
            }
            result.onSuccess { (updated, count) ->
                configureBridgeRoots(updated.id, updated.rootPath)
                val projects = _state.value.projects.map { if (it.id == updated.id) updated else it }
                preferences.saveProjects(projects)
                val guestRoot = guestRootFor(updated)
                _state.update { state ->
                    state.copy(
                        projects = projects,
                        activeProject = updated,
                        projectImporting = false,
                        projectImportMessage = null,
                        projectTerminalCwd = guestRoot,
                        toastMessage = "Bound folder to ${updated.name} ($count files copied)",
                    )
                }
                refreshProjectFiles()
            }.onFailure { error ->
                _state.update { state ->
                    state.copy(
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = "Failed to bind folder: ${error.message?.take(180) ?: "Unknown error"}",
                    )
                }
            }
        }
    }

    fun setProjectRootDirectory(relativePath: String) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning || current.projectTerminalRunning) return
        val normalized = relativePath.trim().removePrefix("/").removeSuffix("/")
        val base = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
        val target = if (normalized.isBlank()) base else File(base, normalized)
        if (!target.isDirectory || !target.canonicalFile.toPath().startsWith(base.canonicalFile.toPath())) {
            _state.update { it.copy(toastMessage = "Invalid directory path") }
            return
        }
        val updated = project.copy(rootPath = normalized, updatedAtMillis = System.currentTimeMillis())
        configureBridgeRoots(updated.id, updated.rootPath)
        val projects = current.projects.map { if (it.id == updated.id) updated else it }
        val guestRoot = guestRootFor(updated)
        preferences.saveProjects(projects)
        terminalCoordinator.saveProjectTerminal(updated.id, guestRoot, current.projectTerminalLines)
        _state.update {
            it.copy(
                projects = projects,
                activeProject = updated,
                suggestedProjectRoot = null,
                projectTerminalCwd = guestRoot,
                changes = emptyList(),
                toastMessage = if (normalized.isBlank()) "Workspace root is now the project root" else "$normalized is now the project working directory",
            )
        }
        refreshProjectFiles()
    }

    // ---- Milestone 1: repository clone moved to GitHubAuthCoordinator. ----
    private fun detectImportedProjectMetadata(root: File): Pair<String, String> {
        val names = root.walkTopDown().maxDepth(3).filter(File::isFile).map { it.name.lowercase() }.toSet()
        return when {
            names.any { it == "settings.gradle.kts" || it == "build.gradle.kts" } -> "Imported Gradle project" to "Kotlin"
            names.any { it == "settings.gradle" || it == "build.gradle" } -> "Imported Gradle project" to "Java"
            "package.json" in names && names.any { it == "tsconfig.json" || it.endsWith(".ts") || it.endsWith(".tsx") } -> "Imported web project" to "TypeScript"
            "package.json" in names -> "Imported web project" to "JavaScript"
            names.any { it == "pyproject.toml" || it == "requirements.txt" || it.endsWith(".py") } -> "Imported Python project" to "Python"
            names.any { it == "cargo.toml" || it.endsWith(".rs") } -> "Imported Rust project" to "Rust"
            names.any { it == "go.mod" || it.endsWith(".go") } -> "Imported Go project" to "Go"
            else -> "Imported ZIP project" to "General"
        }
    }

    fun clonePublicGitRepository(url: String) =
        gitHubAuthCoordinator.clonePublicGitRepository(url)

    fun cloneGitHubRepository(repository: GitHubRepository) =
        gitHubAuthCoordinator.cloneGitHubRepository(repository)

    fun cancelGitClone() = gitHubAuthCoordinator.cancelGitClone()
    // ---- Milestone 1: GitHub auth moved to GitHubAuthCoordinator. ----
    fun startGitHubLogin() =
        gitHubAuthCoordinator.startGitHubLogin()

    fun generateNewGitHubCode() =
        gitHubAuthCoordinator.generateNewGitHubCode()

    fun refreshGitHubRepositories() =
        gitHubAuthCoordinator.refreshGitHubRepositories()

    fun disconnectGitHub() =
        gitHubAuthCoordinator.disconnectGitHub()

    suspend fun refreshGitHubConnection() =
        gitHubAuthCoordinator.refreshGitHubConnection()
    fun renameProject(projectId: String, newName: String) =
        projectsCoordinator.renameProject(projectId, newName)

    fun deleteProject(projectId: String) {
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        val runningHere = if (_state.value.activeProject?.id == projectId) {
            _state.value.isRunning
        } else {
            projectSessionStates.value[projectId]?.isRunning == true
        }
        if (_state.value.activeProject?.id == projectId || runningHere || _state.value.projectTerminalRunning) return
        projectBridgePool.release(projectId)
        projectSessionStates.update { it - projectId }
        _state.update { current -> current.copy(projects = current.projects.filterNot { it.id == projectId }) }
        preferences.saveProjects(_state.value.projects)
        viewModelScope.launch(Dispatchers.IO) {
            val filesDir = getApplication<Application>().filesDir
            File(filesDir, "workspaces/${project.id}").deleteRecursively()
            terminalCoordinator.deleteTerminalHistory(project.id)
            preferences.deleteProjectChats(project.id)
        }
    }

    private fun detectNestedProjectRoot(project: Project): String? {
        val base = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
        if (!base.isDirectory) return null
        val visible = base.listFiles().orEmpty().filterNot { file ->
            file.name == ".claude" || file.name == ".claude.json"
        }
        val onlyDirectory = visible.singleOrNull()?.takeIf(File::isDirectory) ?: return null
        val containsProjectFiles = onlyDirectory.walkTopDown()
            .maxDepth(2)
            .any { it.isFile && it.name !in setOf(".DS_Store", ".claude.json") }
        return onlyDirectory.name.takeIf { containsProjectFiles && !it.contains("..") }
    }

    fun useSuggestedProjectRoot() {
        val current = _state.value
        val project = current.activeProject ?: return
        val root = current.suggestedProjectRoot ?: return
        if (current.isRunning || current.projectTerminalRunning) return
        val updated = project.copy(rootPath = root)
        configureBridgeRoots(updated.id, updated.rootPath)
        projectBridgePool.configureRoot(updated.id, updated.rootPath)
        val projects = current.projects.map { if (it.id == updated.id) updated else it }
        val guestRoot = guestRootFor(updated)
        preferences.saveProjects(projects)
        terminalCoordinator.saveProjectTerminal(updated.id, guestRoot, current.projectTerminalLines)
        _state.update {
            it.copy(
                projects = projects,
                activeProject = updated,
                suggestedProjectRoot = null,
                projectTerminalCwd = guestRoot,
                changes = emptyList(),
                toastMessage = "$root is now the project root",
            )
        }
        refreshProjectFiles()
    }

    fun exportActiveProject(uri: Uri) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning || current.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Stop the running task before exporting") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val root = projectWorkspaceRoot(project)
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error("The selected location could not be opened")
                    output.buffered().use { stream -> writeProjectZip(project, root, stream) }
                }
            }
            _state.update {
                it.copy(
                    toastMessage = result.fold(
                        onSuccess = { "${project.slug}.zip exported" },
                        onFailure = { error -> "Export failed: ${error.message ?: "Unknown error"}" },
                    ),
                )
            }
        }
    }

    /** Writes [project]'s workspace tree as a ZIP (rooted at the project slug) to [stream]. */
    private fun writeProjectZip(project: Project, root: File, stream: java.io.OutputStream) {
        val rootPath = root.canonicalFile.toPath()
        ZipOutputStream(stream).use { zip ->
            zip.putNextEntry(ZipEntry("${project.slug}/"))
            zip.closeEntry()
            root.walkTopDown()
                .onEnter { directory ->
                    if (directory == root) {
                        true
                    } else {
                        val relative = directory.relativeTo(root).invariantSeparatorsPath
                        !isExportExcludedPath(relative) &&
                            !Files.isSymbolicLink(directory.toPath()) &&
                            runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                    }
                }
                .drop(1)
                .filter { file ->
                    !Files.isSymbolicLink(file.toPath()) &&
                        runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false) &&
                        !isExportExcludedPath(file.relativeTo(root).invariantSeparatorsPath)
                }
                .forEach { file ->
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    val entryName = "${project.slug}/$relative" + if (file.isDirectory) "/" else ""
                    zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                    if (file.isFile) file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    /**
     * Zips the active project into the app cache and opens the Android share
     * sheet for the archive (FileProvider authority `${applicationId}.files`,
     * cache path configured in res/xml/file_paths.xml).
     */
    fun shareActiveProject() {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning || current.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Stop the running task before sharing") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val app = getApplication<Application>()
                    val root = projectWorkspaceRoot(project)
                    val shareDir = File(app.cacheDir, "shared").apply { mkdirs() }
                    val zipFile = File(shareDir, "${project.slug}.zip")
                    zipFile.outputStream().buffered().use { stream -> writeProjectZip(project, root, stream) }
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", zipFile)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "${project.name} (project ZIP)")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(send, "Share project (ZIP)").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    app.startActivity(chooser)
                }
            }
            result.onFailure { error ->
                _state.update { it.copy(toastMessage = "Share failed: ${error.message ?: "Unknown error"}") }
            }
        }
    }

    fun setExecutionMode(mode: ExecutionMode) {
        preferences.executionMode = mode.name
        _state.update { it.copy(executionMode = mode) }
    }

    fun toggleScratchpadExpanded() {
        _state.update { it.copy(scratchpadExpanded = !it.scratchpadExpanded) }
    }

    fun addRoadmapComment(roadmapId: String, text: String, stepId: String? = null) {
        if (text.isBlank()) return
        val comment = RoadmapComment(
            id = UUID.randomUUID().toString(),
            author = "User",
            text = text.trim(),
            timestampMillis = System.currentTimeMillis(),
            stepId = stepId,
        )
        _state.update { current ->
            val updatedMessages = current.messages.map { msg ->
                if (msg.roadmap?.id == roadmapId) {
                    msg.copy(roadmap = msg.roadmap.copy(comments = msg.roadmap.comments + comment))
                } else {
                    msg
                }
            }
            val updatedActive = if (current.activeRoadmap?.id == roadmapId) {
                current.activeRoadmap.copy(comments = current.activeRoadmap.comments + comment)
            } else {
                current.activeRoadmap
            }
            current.copy(
                messages = updatedMessages,
                activeRoadmap = updatedActive,
            )
        }
        persistMessages()
    }

    fun approveAndBuildRoadmap(roadmap: ActiveRoadmap, additionalInstructions: String? = null) {
        val approved = roadmap.copy(isApproved = true)
        _state.update { current ->
            val updatedMessages = current.messages.map { msg ->
                if (msg.roadmap?.id == roadmap.id) {
                    msg.copy(roadmap = approved)
                } else {
                    msg
                }
            }
            current.copy(
                messages = updatedMessages,
                activeRoadmap = approved,
            )
        }
        persistMessages()

        val prompt = buildString {
            appendLine("<execution_mode name=\"UNIFIED_BUILD\">")
            appendLine("The user has APPROVED the roadmap and requested to begin building.")
            appendLine("Roadmap Title: ${roadmap.title}")
            if (roadmap.summary.isNotBlank()) {
                appendLine("Summary: ${roadmap.summary}")
            }
            appendLine("Approved Steps to execute:")
            roadmap.steps.forEachIndexed { idx, step ->
                appendLine("${idx + 1}. [ ] ${step.title}")
                if (step.description.isNotBlank()) {
                    appendLine("   Details: ${step.description}")
                }
                if (step.filesAffected.isNotEmpty()) {
                    appendLine("   Files: ${step.filesAffected.joinToString(", ")}")
                }
            }
            val allComments = roadmap.comments + (additionalInstructions?.takeIf(String::isNotBlank)?.let {
                listOf(RoadmapComment(text = it))
            } ?: emptyList())
            if (allComments.isNotEmpty()) {
                appendLine()
                appendLine("User Guidance & Instructions to strictly follow:")
                allComments.forEach { c ->
                    appendLine("- [${c.author}]: ${c.text}")
                }
            }
            appendLine("Start building now end-to-end. Implement code changes, build, verify, and complete all steps.")
            appendLine("Once finished, report verification details and conclude directly. Do NOT output a new roadmap or ask the user to Approve & Build again.")
            appendLine("</execution_mode>")
        }

        sendPrompt(prompt)
    }

    fun rollbackToBaseline(): Boolean {
        val proj = state.value.activeProject ?: return false
        val workspaceDir = File(getApplication<Application>().filesDir, "projects/${proj.id}")
        val success = agentOrchestrator.rollbackToBaseline(workspaceDir)
        if (success) {
            _state.update { it.copy(toastMessage = "Rolled back workspace to pre-task baseline") }
            refreshProjectFiles()
        } else {
            _state.update { it.copy(toastMessage = "Rollback failed: No baseline checkpoint available") }
        }
        return success
    }

    private fun deriveScratchpadItems(
        roadmap: ActiveRoadmap?,
        liveProcess: List<ActivityItem>,
        isRunning: Boolean,
    ): List<ScratchpadItem> = agentChatController.deriveScratchpadItems(roadmap, liveProcess, isRunning)

    fun createChat() {
        val current = _state.value
        val project = current.activeProject ?: return
        val chat = ProjectChat()
        val chats = listOf(chat) + current.projectChats
        preferences.saveProjectChats(project.id, chats)

        if (current.isRunning) {
            // Keep the running conversation attached to its active UI/session.
            // The new conversation is persisted immediately and can be opened when
            // the current task finishes, without mixing two chats' live output.
            _state.update {
                it.copy(
                    projectChats = chats,
                    toastMessage = "New conversation created. The running task will continue; switch chats when it finishes.",
                )
            }
            return
        }

        persistMessages()
        preferences.setLastActiveChatId(project.id, chat.id)
        _state.update {
            it.copy(
                projectChats = chats,
                activeChatId = chat.id,
                messages = listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")),
                activeRoadmap = null,
                scratchpadItems = emptyList(),
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                pendingApproval = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun switchChat(chatId: String) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning || current.activeChatId == chatId) return
        val chat = current.projectChats.firstOrNull { it.id == chatId } ?: return
        persistMessages()
        val saved = dropActiveSessionInterruptedRow(preferences.loadMessages(project.id, chat.id))
        val msgs = saved.ifEmpty { listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")) }
        val loadedRoadmap = msgs.mapNotNull { it.roadmap }.lastOrNull()
        val loadedScratchpad = deriveScratchpadItems(loadedRoadmap, emptyList(), false)
        preferences.setLastActiveChatId(project.id, chat.id)
        _state.update {
            it.copy(
                activeChatId = chat.id,
                messages = msgs,
                activeRoadmap = loadedRoadmap,
                scratchpadItems = loadedScratchpad,
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                pendingApproval = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun renameChat(chatId: String, newTitle: String) {
        val project = _state.value.activeProject ?: return
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return
        preferences.renameProjectChat(project.id, chatId, trimmed)
        _state.update { current ->
            val updatedChats = current.projectChats.map {
                if (it.id == chatId) it.copy(title = trimmed) else it
            }
            current.copy(projectChats = updatedChats)
        }
    }

    fun deleteChat(chatId: String) {
        val project = _state.value.activeProject ?: return
        if (_state.value.isRunning || _state.value.projectChats.size <= 1) return
        preferences.deleteProjectChat(project.id, chatId)
        val remainingChats = preferences.loadProjectChats(project.id)
        if (_state.value.activeChatId == chatId) {
            val nextChat = remainingChats.firstOrNull() ?: return
            val saved = dropActiveSessionInterruptedRow(preferences.loadMessages(project.id, nextChat.id))
            val msgs = saved.ifEmpty { listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")) }
            val loadedRoadmap = msgs.mapNotNull { it.roadmap }.lastOrNull()
            val loadedScratchpad = deriveScratchpadItems(loadedRoadmap, emptyList(), false)
            preferences.setLastActiveChatId(project.id, nextChat.id)
            _state.update {
                it.copy(
                    projectChats = remainingChats,
                    activeChatId = nextChat.id,
                    messages = msgs,
                    activeRoadmap = loadedRoadmap,
                    scratchpadItems = loadedScratchpad,
                )
            }
        } else {
            _state.update { it.copy(projectChats = remainingChats) }
        }
    }

    fun refreshProjectFiles() {
        val project = _state.value.activeProject ?: return
        _state.update { it.copy(filesLoading = true, filesError = null) }
        viewModelScope.launch {
            // Medium: a load failure used to kill this coroutine with no
            // catch — filesLoading stayed true forever (endless spinner)
            // and the failure looked exactly like an empty project.
            runCatching {
                withContext(Dispatchers.IO) {
                    Triple(
                        readWorkspace(project),
                        if (project.rootPath.isBlank()) detectNestedProjectRoot(project) else null,
                        findAndroidGradleProjectRoot(projectWorkspaceRoot(project)) != null,
                    )
                }
            }.onSuccess { (entries, suggestedRoot, androidProjectDetected) ->
                if (_state.value.activeProject?.id == project.id) {
                    _state.update {
                        it.copy(
                            workspaceFiles = entries,
                            filesLoading = false,
                            filesError = null,
                            suggestedProjectRoot = suggestedRoot,
                            androidProjectDetected = androidProjectDetected,
                        )
                    }
                }
            }.onFailure { error ->
                if (_state.value.activeProject?.id == project.id) {
                    _state.update {
                        it.copy(
                            filesLoading = false,
                            filesError = error.message?.take(200) ?: "Could not load project files",
                        )
                    }
                }
            }
        }
    }

    // ---- Milestone 1: editor state machine moved to EditorCoordinator. ----
    fun openFile(entry: WorkspaceEntry) =
        editorCoordinator.openFile(entry)

    fun openFileByPath(path: String) =
        editorCoordinator.openFileByPath(path)

    fun selectEditorTab(path: String) =
        editorCoordinator.selectEditorTab(path)

    fun closeEditorTab(path: String) =
        editorCoordinator.closeEditorTab(path)

    fun closeOtherEditorTabs(keepPath: String) =
        editorCoordinator.closeOtherEditorTabs(keepPath)

    fun closeAllEditorTabs() =
        editorCoordinator.closeAllEditorTabs()

    // Milestone 2 — dirty-buffer tracking and rename workflow delegates.
    fun onEditorContentChanged(path: String, content: String) =
        editorCoordinator.onBufferContentChanged(path, content)

    fun onEditorViewStateChanged(path: String, viewState: com.vayro.app.editor.EditorViewState) =
        editorCoordinator.onBufferViewStateChanged(path, viewState)

    fun onEditorSplitStateChanged(splitState: com.vayro.app.editor.EditorSplitState) =
        editorCoordinator.onSplitStateChanged(splitState)

    suspend fun planRenameSymbol(oldName: String, newName: String): com.vayro.app.workspace.ReplacePreview? =
        editorCoordinator.planRenameSymbol(oldName, newName)

    suspend fun applyRenameSymbol(
        preview: com.vayro.app.workspace.ReplacePreview,
        includeFiles: Set<String>? = null,
    ): Unit = editorCoordinator.applyRenameSymbol(preview, includeFiles)

    suspend fun undoLastRename(): Boolean = editorCoordinator.undoLastRename()

    fun closeFile() =
        editorCoordinator.closeFile()

    /**
     * QA H1: returns true only when the file was actually created, so callers
     * (Command Palette new-file dialog) can report success honestly.
     */
    fun createFile(relativePath: String, initialContent: String = ""): Boolean {
        val project = _state.value.activeProject ?: return false
        val root = projectWorkspaceRoot(project)
        val result = fileOperationsController.createFile(root, relativePath, initialContent)
        if (result.isSuccess) {
            refreshProjectFiles()
            openFileByPath(relativePath)
        } else {
            AppCrashLogger.log("Failed to create file: ${result.exceptionOrNull()?.message}")
        }
        return result.isSuccess
    }

    /**
     * QA H1: returns true only when the directory was actually created.
     */
    fun createFolder(relativePath: String): Boolean {
        val project = _state.value.activeProject ?: return false
        val root = projectWorkspaceRoot(project)
        val result = fileOperationsController.createDirectory(root, relativePath)
        if (result.isSuccess) {
            refreshProjectFiles()
        } else {
            AppCrashLogger.log("Failed to create directory: ${result.exceptionOrNull()?.message}")
        }
        return result.isSuccess
    }

    /**
     * QA H1 (Check Runtime Health): a point-in-time snapshot of the real
     * storage / rootfs / memory state, shown in the editor's health dialog.
     * Nothing here is estimated or invented — every field comes from a live
     * check at call time.
     */
    fun getRuntimeHealthSnapshot(): RuntimeHealthSnapshot {
        val storage = runtimeHealthController.checkStorageHealth()
        val storageStatus = when (storage) {
            is com.vayro.app.runtime.StorageHealthResult.Healthy -> "Healthy"
            is com.vayro.app.runtime.StorageHealthResult.Warning -> "Warning"
            is com.vayro.app.runtime.StorageHealthResult.Critical -> "Critical"
        }
        val storageMessage = when (storage) {
            is com.vayro.app.runtime.StorageHealthResult.Healthy -> null
            is com.vayro.app.runtime.StorageHealthResult.Warning -> storage.message
            is com.vayro.app.runtime.StorageHealthResult.Critical -> storage.message
        }
        val storageUsableBytes = when (storage) {
            is com.vayro.app.runtime.StorageHealthResult.Healthy -> storage.usableBytes
            is com.vayro.app.runtime.StorageHealthResult.Warning -> storage.usableBytes
            is com.vayro.app.runtime.StorageHealthResult.Critical -> storage.usableBytes
        }
        val storageTotalBytes = when (storage) {
            is com.vayro.app.runtime.StorageHealthResult.Healthy -> storage.totalBytes
            is com.vayro.app.runtime.StorageHealthResult.Warning -> storage.totalBytes
            is com.vayro.app.runtime.StorageHealthResult.Critical -> storage.totalBytes
        }
        val rootfs = com.vayro.app.runtime.RootfsIntegrityVerifier.verify(
            java.io.File(getApplication<Application>().filesDir, "runtime/ubuntu")
        )
        val rootfsStatus = if (rootfs.isValid) {
            "Intact"
        } else {
            "Issues found"
        }
        val runtime = Runtime.getRuntime()
        return RuntimeHealthSnapshot(
            storageStatus = storageStatus,
            storageMessage = storageMessage,
            storageUsableBytes = storageUsableBytes,
            storageTotalBytes = storageTotalBytes,
            rootfsStatus = rootfsStatus,
            rootfsMissingPaths = rootfs.missingPaths,
            rootfsCorruptedPaths = rootfs.corruptedPaths,
            memoryUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            memoryMaxBytes = runtime.maxMemory(),
        )
    }

    fun renameEntry(oldRelativePath: String, newRelativePath: String) {
        val project = _state.value.activeProject ?: return
        val root = projectWorkspaceRoot(project)
        val result = fileOperationsController.renameEntry(root, oldRelativePath, newRelativePath)
        if (result.isSuccess) {
            val currentTabs = _state.value.openEditorTabs
            if (oldRelativePath in currentTabs) {
                val updatedTabs = currentTabs.map { if (it == oldRelativePath) newRelativePath else it }
                preferences.setOpenEditorTabs(project.id, updatedTabs)
                _state.update {
                    it.copy(
                        openEditorTabs = updatedTabs,
                        openedFilePath = if (it.openedFilePath == oldRelativePath) newRelativePath else it.openedFilePath,
                    )
                }
            }
            refreshProjectFiles()
        } else {
            AppCrashLogger.log("Failed to rename: ${result.exceptionOrNull()?.message}")
        }
    }

    fun deleteEntry(relativePath: String) {
        val project = _state.value.activeProject ?: return
        val root = projectWorkspaceRoot(project)
        val result = fileOperationsController.deleteEntry(root, relativePath)
        if (result.isSuccess) {
            if (relativePath in _state.value.openEditorTabs) {
                closeEditorTab(relativePath)
            }
            refreshProjectFiles()
        } else {
            AppCrashLogger.log("Failed to delete: ${result.exceptionOrNull()?.message}")
        }
    }

    fun duplicateEntry(relativePath: String) {
        val project = _state.value.activeProject ?: return
        val root = projectWorkspaceRoot(project)
        val result = fileOperationsController.duplicateEntry(root, relativePath)
        if (result.isSuccess) {
            val duplicate = result.getOrNull()
            refreshProjectFiles()
            if (duplicate != null) {
                val rel = duplicate.relativeTo(root).invariantSeparatorsPath
                openFileByPath(rel)
            }
        } else {
            AppCrashLogger.log("Failed to duplicate: ${result.exceptionOrNull()?.message}")
        }
    }

    fun moveEntry(sourceRelativePath: String, targetRelativePath: String) {
        val project = _state.value.activeProject ?: return
        val root = projectWorkspaceRoot(project)
        val result = fileOperationsController.moveEntry(root, sourceRelativePath, targetRelativePath)
        if (result.isSuccess) {
            val moved = result.getOrNull()
            if (sourceRelativePath in _state.value.openEditorTabs && moved != null) {
                val newRel = moved.relativeTo(root).invariantSeparatorsPath
                val updatedTabs = _state.value.openEditorTabs.map { if (it == sourceRelativePath) newRel else it }
                val newOpenedPath = if (_state.value.openedFilePath == sourceRelativePath) newRel else _state.value.openedFilePath
                preferences.setOpenEditorTabs(project.id, updatedTabs)
                _state.update { it.copy(openEditorTabs = updatedTabs, openedFilePath = newOpenedPath) }
            }
            refreshProjectFiles()
        } else {
            AppCrashLogger.log("Failed to move: ${result.exceptionOrNull()?.message}")
            _state.update { it.copy(toastMessage = "Move failed: ${result.exceptionOrNull()?.message}") }
        }
    }

    // ---- Milestone 1: workspace Git commands moved to GitCoordinator. ----
    fun runWorkspaceGitCommand(args: List<String>, onResult: (Boolean, String) -> Unit = { _, _ -> }) =
        gitCoordinator.runWorkspaceGitCommand(args, onResult)

    fun gitStatus(onResult: (String) -> Unit) =
        gitCoordinator.gitStatus(onResult)

    fun gitDiff(onResult: (String) -> Unit) =
        gitCoordinator.gitDiff(onResult)

    fun gitBranchList(onResult: (List<String>) -> Unit) =
        gitCoordinator.gitBranchList(onResult)

    fun gitCheckout(branch: String, createNew: Boolean = false, onResult: (Boolean, String) -> Unit = { _, _ -> }) =
        gitCoordinator.gitCheckout(branch, createNew, onResult)

    fun gitCommit(message: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) =
        gitCoordinator.gitCommit(message, onResult)

    fun gitPush(remote: String = "origin", branch: String = "", onResult: (Boolean, String) -> Unit = { _, _ -> }) =
        gitCoordinator.gitPush(remote, branch, onResult)

    fun gitPull(remote: String = "origin", branch: String = "", onResult: (Boolean, String) -> Unit = { _, _ -> }) =
        gitCoordinator.gitPull(remote, branch, onResult)
    fun scaffoldProjectTemplate(template: com.vayro.app.workspace.ProjectTemplate) {
        val project = _state.value.activeProject ?: return
        val root = projectWorkspaceRoot(project)
        val result = fileOperationsController.scaffoldTemplate(root, template)
        if (result.isSuccess) {
            _state.update { it.copy(toastMessage = "Scaffolded ${template.displayName} starter template") }
            refreshProjectFiles()
        }
    }

    // ---- Milestone 1: port-detection state moved to VisualCoordinator. ----


    private fun loadFileContent(project: Project, path: String) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                val file = File(projectWorkspaceRoot(project), path)
                runCatching {
                    if (file.length() > 512_000L) {
                        file.inputStream().use { stream ->
                            val buf = ByteArray(512_000)
                            val read = stream.read(buf)
                            String(buf, 0, read)
                        } + "\n\n[File truncated — too large to display fully]"
                    } else {
                        file.readText()
                    }
                }.getOrElse { "Could not read file: ${it.message}" }
            }
            if (_state.value.openedFilePath == path) {
                _state.update { it.copy(openedFileContent = content, fileContentLoading = false) }
            }
        }
    }

    /**
     * Persists an edited workspace file as an explicit, reviewable change.
     * A checkpoint is created before the first manual edit so the existing
     * Changes tab can Undo/Keep the edit using the same durable mechanism as
     * agent-generated changes.
     */
    suspend fun saveFile(path: String, content: String): Boolean =
        editorCoordinator.saveFile(path, content)

    private fun readWorkspace(project: Project): List<WorkspaceEntry> {
        val root = projectWorkspaceRoot(project)
        if (!root.isDirectory) return emptyList()
        val rootPath = root.canonicalFile.toPath()
        return root.walkTopDown()
            .maxDepth(12)
            .onEnter { directory ->
                val relative = if (directory == root) "" else directory.relativeTo(root).invariantSeparatorsPath
                directory == root || (!isClaudeRuntimeMetadata(relative) &&
                    !Files.isSymbolicLink(directory.toPath()) &&
                    runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                    )
            }
            .drop(1)
            .filter { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                !isClaudeRuntimeMetadata(relative) &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            }
            .take(MAX_VISIBLE_WORKSPACE_ENTRIES)
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                WorkspaceEntry(
                    path = relative,
                    name = file.name,
                    isDirectory = file.isDirectory,
                    depth = relative.count { it == '/' },
                    sizeBytes = if (file.isFile) file.length() else 0,
                )
            }
            .sortedWith(compareBy<WorkspaceEntry> { it.path.lowercase() }.thenByDescending { it.isDirectory })
            .toList()
    }

    // Milestone 1: shared with EditorCoordinator — see coordinators package.

    private fun isExportExcludedPath(relativePath: String): Boolean {
        val excludedNames = setOf(
            ".git", ".claude", ".gradle", ".idea", ".next", ".cache",
            "node_modules", ".venv", "venv", "__pycache__", "build",
        )
        return relativePath.split('/').any { it in excludedNames } || isClaudeRuntimeMetadata(relativePath)
    }

    fun addChatAttachments(uris: List<Uri>) {
        val current = _state.value
        val project = current.activeProject ?: return
        val chatId = current.activeChatId ?: return
        if (current.isRunning || uris.isEmpty()) return
        val remaining = (MAX_ATTACHMENTS_PER_MESSAGE - current.pendingAttachments.size).coerceAtLeast(0)
        if (remaining == 0) {
            _state.update { it.copy(toastMessage = "You can attach up to $MAX_ATTACHMENTS_PER_MESSAGE files per message") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val added = mutableListOf<ChatAttachment>()
                val errors = mutableListOf<String>()
                uris.take(remaining).forEach { uri ->
                    runCatching { copyChatAttachment(project, chatId, uri) }
                        .onSuccess(added::add)
                        .onFailure { errors += (it.message ?: "Could not attach file") }
                }
                added to errors
            }
            val (added, errors) = result
            _state.update { state ->
                state.copy(
                    pendingAttachments = state.pendingAttachments + added,
                    toastMessage = errors.firstOrNull() ?: if (uris.size > remaining) "Only $remaining more file${if (remaining == 1) "" else "s"} could be added" else null,
                )
            }
            if (added.isNotEmpty()) refreshProjectFiles()
        }
    }

    fun removePendingAttachment(attachmentId: String) {
        val current = _state.value
        val project = current.activeProject ?: return
        val attachment = current.pendingAttachments.firstOrNull { it.id == attachmentId } ?: return
        _state.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { item -> item.id == attachmentId }) }
        viewModelScope.launch(Dispatchers.IO) {
            val root = projectWorkspaceRoot(project)
            val file = File(root, attachment.relativePath).canonicalFile
            if (file.toPath().startsWith(root.canonicalFile.toPath())) file.delete()
        }
    }

    fun openChatAttachment(attachment: ChatAttachment) {
        val project = _state.value.activeProject ?: return
        runCatching {
            val root = projectWorkspaceRoot(project).canonicalFile
            val file = File(root, attachment.relativePath).canonicalFile
            require(file.isFile && file.toPath().startsWith(root.toPath())) { "Attachment is unavailable" }
            val app = getApplication<Application>()
            val staged = SharedExport.stageFileForOpen(app.filesDir, file, attachment.displayName)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", staged)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, attachment.mimeType.ifBlank { "application/octet-stream" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        }.onFailure { error ->
            _state.update { it.copy(toastMessage = error.message ?: "No app can open this attachment") }
        }
    }

    fun saveChatAttachmentToStorage(attachment: ChatAttachment, destination: Uri) {
        val project = _state.value.activeProject
        if (project == null) {
            _state.update { it.copy(toastMessage = "No active project") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val root = projectWorkspaceRoot(project).canonicalFile
                val file = File(root, attachment.relativePath).canonicalFile
                require(file.isFile && file.toPath().startsWith(root.toPath())) { "Attachment is unavailable" }
                val resolver = getApplication<Application>().contentResolver
                resolver.openOutputStream(destination)?.buffered()?.use { output ->
                    file.inputStream().buffered().use { input -> input.copyTo(output) }
                } ?: error("Could not write to the chosen location")
            }.onSuccess {
                _state.update { it.copy(toastMessage = "\"${attachment.displayName}\" saved") }
            }.onFailure { error ->
                _state.update { it.copy(toastMessage = error.message ?: "Could not save attachment") }
            }
        }
    }

    private fun copyChatAttachment(project: Project, chatId: String, uri: Uri): ChatAttachment {
        val resolver = getApplication<Application>().contentResolver
        var displayName = "attachment"
        var declaredSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { displayName = cursor.getString(it) ?: displayName }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { declaredSize = cursor.getLong(it) }
            }
        }
        val mimeType = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val supportedTextExtensions = setOf(
            "txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml", "log",
            "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "htm",
            "css", "scss", "sass", "less", "c", "cc", "cpp", "h", "hpp", "sh", "bash", "zsh",
            "gradle", "properties", "toml", "ini", "conf", "sql", "pdf", "out", "err", "env",
        )
        val isPdf = mimeType == "application/pdf" || extension == "pdf"
        val isNoExtension = !displayName.contains('.') || extension.isBlank()
        val isLogOrText = mimeType.startsWith("text/") || extension in supportedTextExtensions ||
            isNoExtension || mimeType == "application/octet-stream" || mimeType.contains("log") || extension.contains("log")
        val supported = mimeType.startsWith("image/") || isPdf ||
            mimeType.startsWith("text/") || mimeType == "application/json" || mimeType == "application/xml" ||
            mimeType.endsWith("+json") || mimeType.endsWith("+xml") || isLogOrText
        require(supported) { "Only images, PDF documents, and text or log files are supported" }
        require(declaredSize <= MAX_ATTACHMENT_BYTES || declaredSize < 0) { "$displayName is larger than 25 MB" }
        val safeName = sanitizeAttachmentName(displayName)
        val root = projectWorkspaceRoot(project).canonicalFile
        val folder = File(root, "attachments/$chatId").apply { mkdirs() }.canonicalFile
        require(folder.toPath().startsWith(root.toPath())) { "Unsafe attachment folder" }
        val stem = safeName.substringBeforeLast('.', safeName)
        val safeExtension = safeName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var destination = File(folder, safeName)
        var suffix = 2
        while (destination.exists()) destination = File(folder, "$stem-${suffix++}$safeExtension")
        var copied = 0L
        try {
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_ATTACHMENT_BYTES) { "$displayName is larger than 25 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Could not read $displayName")
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
        return ChatAttachment(
            displayName = displayName.take(120),
            relativePath = destination.relativeTo(root).invariantSeparatorsPath,
            mimeType = mimeType,
            sizeBytes = copied,
        )
    }

    private fun sanitizeAttachmentName(name: String): String =
        agentChatController.sanitizeAttachmentName(name)

    /**
     * U9: conservative conversation classifier. A turn counts as plain talk
     * only when it clearly needs no tools and changes nothing: no
     * attachments, no @file mentions, short, no task verbs and no
     * file/project/code references. Anything ambiguous stays on the full
     * task pipeline (plan + permissions stay available for real work).
     */
    /**
     * v3.2.38 (Part N): delegates to [HomeTurnClassifier] — conversation is
     * the default, questions always win, and the handoff card is reserved
     * for unmistakable creation commands. (The old inline classifier is
     * gone; its English-only allowlist misfired on Hindi/Urdu questions.)
     */
    private fun isConversationalTurn(
        text: String,
        hasAttachments: Boolean,
        mentionedPaths: List<String>,
    ): Boolean = HomeTurnClassifier.isConversational(text, hasAttachments, mentionedPaths)

    // ------------------------------------------------------------------
    // v3.2.37 (Parts F + F2 + C): home chat — normal conversation only.
    //
    // Home threads live in HomeChatStore, NOT in the project store: they
    // have no folder, no runtime session and never appear as workspace
    // projects. A task typed at home is not teleported: it gets a
    // "This is a job for Work" handoff card, and only the user's tap on
    // "Open in Work" creates a real project (the host wires that through
    // the existing quick-project + pending-prompt flow) and calls
    // [noteHomeTaskMoved] to leave the receipt in this thread.
    //
    // Replies come from the API-key providers (Part C catalog chain):
    // the user-selected chat provider first, then the other connected
    // catalog providers in catalog order. Inside a provider, keys come
    // from the encrypted vault pool; with "Auto-switch keys" ON a
    // quota/rate/auth failure marks the key exhausted (KeyHealthStore,
    // persistent), activates the next key and the reply still lands —
    // with a visible "Switched to key N" line in the thread. With the
    // toggle OFF only the active key is tried and the error is reported.
    // If NO catalog provider is connected, the current workspace provider
    // is tried when it is key-backed (the honest fallback); otherwise a
    // plain message explains how to connect a key — chat is never blocked
    // silently.
    // ------------------------------------------------------------------

    private fun activeHomeThread(): com.vayro.app.data.HomeThread? =
        _state.value.homeThreads.firstOrNull { it.id == _state.value.activeHomeThreadId }
            ?: _state.value.homeThreads.firstOrNull()

    private fun persistHomeThreads() {
        val threads = _state.value.homeThreads
        viewModelScope.launch(Dispatchers.IO) { homeChatStore.save(threads) }
    }

    private fun appendHomeMessage(threadId: String, message: com.vayro.app.data.HomeChatMessage) {
        _state.update { st ->
            st.copy(
                homeThreads = st.homeThreads.map { thread ->
                    if (thread.id != threadId) thread else thread.copy(
                        messages = thread.messages + message,
                        updatedAtMillis = System.currentTimeMillis(),
                        title = if (message.fromUser && thread.messages.none { it.fromUser }) {
                            message.text.trim().take(42).ifBlank { thread.title }
                        } else {
                            thread.title
                        },
                    )
                },
            )
        }
        persistHomeThreads()
    }

    fun newHomeThread() {
        val thread = com.vayro.app.data.HomeThread()
        _state.update {
            it.copy(homeThreads = listOf(thread) + it.homeThreads, activeHomeThreadId = thread.id)
        }
        preferences.activeHomeThreadId = thread.id
        persistHomeThreads()
    }

    fun openHomeThread(id: String) {
        if (_state.value.homeThreads.none { it.id == id }) return
        _state.update { it.copy(activeHomeThreadId = id) }
        preferences.activeHomeThreadId = id
    }

    fun deleteHomeThread(id: String) {
        val remaining = _state.value.homeThreads.filterNot { it.id == id }
        _state.update { st ->
            st.copy(
                homeThreads = remaining,
                activeHomeThreadId = if (st.activeHomeThreadId == id) {
                    remaining.firstOrNull()?.id.orEmpty()
                } else {
                    st.activeHomeThreadId
                },
            )
        }
        preferences.activeHomeThreadId = _state.value.activeHomeThreadId
        persistHomeThreads()
    }

    /** Catalog providers (Part C) with at least one saved key, in catalog order. */
    fun connectedChatProviders(): List<ProviderKind> =
        com.vayro.app.data.ProviderCatalog.chatKinds.filter { vault.list(it.name).isNotEmpty() }

    /** The provider that answers home chat: the user's pick when connected, else the first connected. */
    fun resolvedChatProvider(): ProviderKind? {
        val connected = connectedChatProviders()
        if (connected.isEmpty()) return null
        val chosen = runCatching { ProviderKind.valueOf(preferences.chatProviderKind) }.getOrNull()
        return chosen?.takeIf { it in connected } ?: connected.first()
    }

    fun setChatProvider(kind: ProviderKind) {
        preferences.chatProviderKind = kind.name
        refreshHomeChatProviderLabel()
    }

    private fun refreshHomeChatProviderLabel() {
        // v3.2.38 (Part P): the composer pill names provider + model.
        val resolved = resolvedChatProvider()
        _state.update {
            it.copy(
                homeChatProviderName = resolved?.let { kind ->
                    "${kind.title} · ${catalogChatModel(kind)}"
                } ?: "",
            )
        }
    }

    /** v3.2.38 (Part P): one row of the composer provider/model picker sheet. */
    data class ProviderChoice(val kind: ProviderKind, val model: String, val active: Boolean)

    /** Home chat choices: connected catalog providers; active = the F2 "Chat replies use" pick (same state, two doors). */
    fun chatProviderChoices(): List<ProviderChoice> {
        val active = resolvedChatProvider()
        return connectedChatProviders().map { kind ->
            ProviderChoice(kind, catalogChatModel(kind), active = kind == active)
        }
    }

    /** Workspace choices for [agent]: the providers it can use that have a saved key (plus the current one). */
    fun workspaceProviderChoices(agent: AgentKind): List<ProviderChoice> {
        val current = _state.value.provider
        return com.vayro.app.model.providersForAgent(agent)
            .filter { kind -> kind == current.kind || vault.list(kind.name).isNotEmpty() }
            .map { kind ->
                ProviderChoice(
                    kind = kind,
                    model = if (kind == current.kind) current.model else catalogChatModel(kind),
                    active = kind == current.kind,
                )
            }
    }

    private val pickerModelCache = mutableMapOf<String, List<String>>()

    /** Live model list for the picker (the Part L discovery path), cached per provider; empty = fetch failed. */
    private suspend fun pickerModelsFor(profile: ProviderProfile): List<String> {
        pickerModelCache[profile.kind.name]?.let { return it }
        val secret = vault.credentials(profile.kind.name).firstOrNull { it.isActive }?.secret
            ?: vault.credentials(profile.kind.name).firstOrNull()?.secret
            ?: return emptyList()
        val result = discoverModels(profile, secret)
        val models = (result as? ModelDiscoveryResult.Success)?.models?.map { it.id }.orEmpty()
        if (models.isNotEmpty()) pickerModelCache[profile.kind.name] = models
        return models
    }

    suspend fun chatPickerModels(kind: ProviderKind): List<String> = pickerModelsFor(catalogChatDefaults(kind))

    suspend fun workspacePickerModels(kind: ProviderKind): List<String> {
        val current = _state.value.provider
        val profile = if (kind == current.kind) current else catalogChatDefaults(kind)
        return pickerModelsFor(profile)
    }

    /** Persists the picker sheet's model choice for home chat. */
    fun setChatModel(kind: ProviderKind, model: String) {
        if (model.isBlank()) return
        preferences.setChatModelOverride(kind.name, model)
        refreshHomeChatProviderLabel()
    }

    /** Workspace provider switch from the composer sheet (Part P). */
    fun pickWorkspaceProvider(kind: ProviderKind) {
        if (kind == _state.value.provider.kind) return
        updateProvider(
            ProviderProfile(kind, baseUrl = catalogChatBaseUrl(kind), model = catalogChatModel(kind)),
            "",
        )
    }

    /** Workspace provider + model switch from the composer sheet (Part P). */
    fun pickWorkspaceProviderModel(kind: ProviderKind, model: String) {
        if (model.isBlank()) return
        val current = _state.value.provider
        if (kind == current.kind && model == current.model) return
        updateProvider(
            ProviderProfile(kind, baseUrl = catalogChatBaseUrl(kind), model = model),
            "",
        )
    }

    fun autoSwitchKeys(kind: ProviderKind): Boolean = preferences.autoSwitchKeys(kind.name)

    fun setAutoSwitchKeys(kind: ProviderKind, enabled: Boolean) =
        preferences.setAutoSwitchKeys(kind.name, enabled)

    fun keyExhaustedAt(kind: ProviderKind, keyId: String): Long? =
        keyHealthStore.exhaustedAt(kind.name, keyId)

    fun keepWorkingInBackground(): Boolean = preferences.keepWorkingInBackground

    fun setKeepWorkingInBackground(enabled: Boolean) {
        preferences.keepWorkingInBackground = enabled
    }

    fun sendHomeMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || _state.value.homeSending) return
        val thread = activeHomeThread() ?: run {
            newHomeThread()
            activeHomeThread() ?: return
        }
        appendHomeMessage(thread.id, com.vayro.app.data.HomeChatMessage(fromUser = true, text = trimmed))
        if (!isConversationalTurn(trimmed, hasAttachments = false, mentionedPaths = emptyList())) {
            // A job, not a chat (Part N: only an unmistakable creation
            // command lands here). Acknowledge in words first; the card
            // below is the action. Nothing is created or sent anywhere
            // until the user taps "Open in Work".
            appendHomeMessage(
                thread.id,
                com.vayro.app.data.HomeChatMessage(
                    fromUser = false,
                    text = "Got it — that is a build job, so I have put it on a card below. " +
                        "Tap Open in Work and I will build it there with our full chat as context.",
                ),
            )
            appendHomeMessage(
                thread.id,
                com.vayro.app.data.HomeChatMessage(
                    fromUser = false,
                    text = "This is a job for Work",
                    kind = "handoff",
                    taskText = trimmed,
                ),
            )
            return
        }
        _state.update { it.copy(homeSending = true) }
        homeChatJob = viewModelScope.launch { runHomeChat(thread.id) }
    }

    fun stopHomeChat() {
        homeChatJob?.cancel()
        _state.update { it.copy(homeSending = false) }
    }

    /**
     * v3.2.38 (Part N4): the handoff prompt WITH the conversation as
     * context — the workspace agent receives the recent home-thread turns,
     * not just the bare task line.
     */
    fun homeThreadWorkPrompt(taskText: String): String {
        val thread = activeHomeThread() ?: return taskText
        val turns = thread.messages.filter { it.kind == "text" }.takeLast(10)
        if (turns.size <= 1) return taskText
        val digest = turns.joinToString("\n") { message ->
            (if (message.fromUser) "Me: " else "Kodex: ") + message.text
        }
        return "Context from my chat with you:\n$digest\n\nMy task: $taskText"
    }

    /** v3.2.38 (Part N3): the task text for a manual "Open this chat in Work". */
    fun activeHomeTaskText(): String =
        activeHomeThread()?.messages?.lastOrNull { it.fromUser && it.kind == "text" }?.text.orEmpty()

    // ------------------------------------------------------------------
    // v3.2.40: Live call (Gemini Live, speech-to-speech) — the home
    // call's second engine, alongside the pipeline Voice Call. The
    // engine draws keys from the Google AI Studio catalog vault pool
    // with the SAME failover semantics as home chat: Auto-switch ON +
    // a quota/auth failure marks the key (quota-class only, through
    // KeyHealthStore), activates the next key, and the switch is saved
    // into the thread as a "Switched to key N" system line. Gemini
    // quota is per Google PROJECT, not per key: rotating keys of one
    // project adds nothing — the pool only helps across accounts.
    // ------------------------------------------------------------------

    /** True when at least one Google AI Studio key is saved — the Live engine's availability gate. */
    fun hasGoogleAiStudioKey(): Boolean =
        vault.list(ProviderKind.GOOGLE_AI_STUDIO.name).isNotEmpty()

    /** The Live call's key pool, in vault order (active key first). */
    fun liveCallCredentials(): List<ApiKeyCredential> =
        vault.credentials(ProviderKind.GOOGLE_AI_STUDIO.name)

    /** Keys of the Live pool currently inside their quota cooldown — tried last, never hard-skipped. */
    fun liveCallRestingKeyIds(): Set<String> {
        val providerId = ProviderKind.GOOGLE_AI_STUDIO.name
        return keyHealthStore.snapshot(providerId).keys
            .filter { keyHealthStore.inCooldown(providerId, it) }
            .toSet()
    }

    fun liveCallAutoSwitch(): Boolean =
        preferences.autoSwitchKeys(ProviderKind.GOOGLE_AI_STUDIO.name)

    /** The prebuilt Live voice chosen in Voice settings. */
    fun liveCallVoiceName(): String = preferences.liveCallVoice

    /** Persists a Live key switch: quota-class failures mark the key "resting" (a rejected key is not labelled so). */
    fun markLiveKeyExhausted(keyId: String, quota: Boolean) {
        if (quota) keyHealthStore.markExhausted(ProviderKind.GOOGLE_AI_STUDIO.name, keyId)
    }

    fun activateLiveKey(keyId: String) {
        vault.activate(ProviderKind.GOOGLE_AI_STUDIO.name, keyId)
    }

    /** Saves one spoken Live-call turn into the active home thread, like any typed exchange. */
    fun appendLiveCallTurn(userText: String?, assistantText: String?) {
        val user = userText?.trim().orEmpty()
        val assistant = assistantText?.trim().orEmpty()
        if (user.isEmpty() && assistant.isEmpty()) return
        val thread = activeHomeThread() ?: run {
            newHomeThread()
            activeHomeThread() ?: return
        }
        if (user.isNotEmpty()) {
            appendHomeMessage(thread.id, com.vayro.app.data.HomeChatMessage(fromUser = true, text = user))
        }
        if (assistant.isNotEmpty()) {
            appendHomeMessage(thread.id, com.vayro.app.data.HomeChatMessage(fromUser = false, text = assistant))
        }
    }

    /** Saves a small centered line (e.g. a key switch) into the active home thread. */
    fun appendLiveCallSystemLine(text: String) {
        if (text.isBlank()) return
        val thread = activeHomeThread() ?: run {
            newHomeThread()
            activeHomeThread() ?: return
        }
        appendHomeMessage(
            thread.id,
            com.vayro.app.data.HomeChatMessage(fromUser = false, text = text, kind = "system"),
        )
    }

    /** Leaves the "Moved to Work" receipt in the home thread after a handoff tap. */
    fun noteHomeTaskMoved(taskText: String, projectName: String) {
        val thread = activeHomeThread() ?: return
        appendHomeMessage(
            thread.id,
            com.vayro.app.data.HomeChatMessage(
                fromUser = false,
                text = "Moved to Work",
                kind = "work_note",
                taskText = taskText,
                projectName = projectName,
            ),
        )
    }

    private fun homeProfileFor(kind: ProviderKind): ProviderProfile {
        val current = _state.value.provider
        // v3.2.38 (L/J): home chat uses the endpoint Test & Connect actually
        // validated for this kind (saved below), not bare enum defaults —
        // a tested custom Base URL/model must be what chat runs with.
        return if (current.kind == kind) current else catalogChatDefaults(kind)
    }

    /** v3.2.38 (L): the model home chat / the catalog card should use for [kind]. */
    fun catalogChatModel(kind: ProviderKind): String =
        preferences.chatModelOverride(kind.name)
            ?: com.vayro.app.data.ProviderCatalog.entry(kind)?.chatModel
            ?: kind.defaultModel

    /** v3.2.38 (J): the Base URL home chat / the catalog card should use for [kind]. */
    fun catalogChatBaseUrl(kind: ProviderKind): String =
        preferences.chatBaseUrlOverride(kind.name) ?: kind.defaultBaseUrl

    /** v3.2.38 (L/J): the effective catalog profile for [kind] — validated-endpoint overrides first. */
    fun catalogChatDefaults(kind: ProviderKind): ProviderProfile =
        ProviderProfile(kind, baseUrl = catalogChatBaseUrl(kind), model = catalogChatModel(kind))

    /** v3.2.38 (L/J): persists the endpoint Test & Connect validated, so chat actually uses it. */
    fun saveChatEndpointOverride(kind: ProviderKind, baseUrl: String, model: String) {
        if (baseUrl.isNotBlank()) {
            preferences.setChatBaseUrlOverride(kind.name, baseUrl)
        }
        if (model.isNotBlank()) {
            preferences.setChatModelOverride(kind.name, model)
        }
    }

    private suspend fun runHomeChat(threadId: String) {
        try {
            val thread = _state.value.homeThreads.firstOrNull { it.id == threadId } ?: return
            val turns = thread.messages.filter { it.kind == "text" }.takeLast(20).map {
                ProviderApiClient.ChatTurn(if (it.fromUser) "user" else "assistant", it.text)
            }
            val switches = mutableListOf<String>()
            var reply: String? = null
            var lastError: String? = null
            var anyConnected = false
            // v3.2.38 (J): remember who ACTUALLY answered so the thread can
            // say so when it was not the selected chat provider.
            var answeredBy: ProviderKind? = null
            var answeredByFallback = false
            var fallbackTriedKey = false
            var fallbackError: String? = null
            val chain = buildList {
                resolvedChatProvider()?.let { add(it) }
                addAll(connectedChatProviders())
            }.distinct()
            for (kind in chain) {
                if (reply != null) break
                val credentials = vault.credentials(kind.name)
                if (credentials.isEmpty()) continue
                anyConnected = true
                val profile = homeProfileFor(kind)
                val autoSwitch = preferences.autoSwitchKeys(kind.name)
                val ordered = if (!autoSwitch) {
                    listOf(credentials.first())
                } else {
                    val healthy = credentials.filterNot { keyHealthStore.inCooldown(kind.name, it.id) }
                    val resting = credentials.filter { keyHealthStore.inCooldown(kind.name, it.id) }
                    healthy + resting
                }
                for ((index, credential) in ordered.withIndex()) {
                    try {
                        val text = providerApi.chatCompletion(
                            baseUrl = profile.resolvedBaseUrl,
                            model = profile.model,
                            apiKey = credential.secret,
                            protocol = kind.protocol,
                            turns = turns,
                            systemPrompt = HOME_CHAT_SYSTEM_PROMPT,
                        )
                        if (!credential.isActive) vault.activate(kind.name, credential.id)
                        keyHealthStore.clear(kind.name, credential.id)
                        reply = text
                        answeredBy = kind
                        break
                    } catch (e: ChatApiException) {
                        val reason = "HTTP ${e.httpCode} " + (e.providerMessage ?: e.message ?: "")
                        lastError = e.providerMessage ?: e.message
                        if (autoSwitch && homeFailover.recordFailure(credential.id, reason)) {
                            // v3.2.38 (J): only quota-class failures mark the
                            // key "resting" — a rejected (401/403) key is not
                            // "limit reached" and must not be labelled so.
                            if (ApiKeyFailoverCoordinator.isQuotaFailure(reason)) {
                                keyHealthStore.markExhausted(kind.name, credential.id)
                            }
                            val next = ordered.getOrNull(index + 1)
                            if (next != null) {
                                vault.activate(kind.name, next.id)
                                val fromPos = credentials.indexOfFirst { it.id == credential.id } + 1
                                val toPos = credentials.indexOfFirst { it.id == next.id } + 1
                                val why = if (ApiKeyFailoverCoordinator.isQuotaFailure(reason)) {
                                    "key $fromPos limit reached"
                                } else {
                                    "key $fromPos was rejected"
                                }
                                switches += "Switched to key $toPos \u2014 $why"
                                continue
                            }
                        }
                        // Not a key failure (or no key left): this provider is done.
                        break
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        lastError = e.message
                        break
                    }
                }
            }
            if (reply == null) {
                val outcome = tryWorkspaceProviderFallback(turns, chain.toSet())
                fallbackTriedKey = outcome.triedKey
                fallbackError = outcome.error
                if (outcome.text != null) {
                    reply = outcome.text
                    answeredBy = outcome.kind
                    answeredByFallback = true
                }
            }
            // v3.2.38 (J): the composer pill names the SELECTED provider;
            // when a different one actually answered, the thread says so —
            // the substitution is never silent.
            val selectedKind = chain.firstOrNull()
            if (reply != null && answeredBy != null && answeredBy != selectedKind) {
                switches += if (answeredByFallback) {
                    "Answered by ${answeredBy.title} (your workspace provider)"
                } else {
                    "Answered by ${answeredBy.title}"
                }
            }
            switches.forEach { line ->
                appendHomeMessage(
                    threadId,
                    com.vayro.app.data.HomeChatMessage(fromUser = false, text = line, kind = "system"),
                )
            }
            val finalText = reply ?: if (!anyConnected && fallbackTriedKey) {
                // v3.2.38 (J): a workspace key exists and was tried \u2014 do not
                // claim no key is connected; report that attempt's failure.
                "I could not get a reply from your workspace provider" +
                    (fallbackError?.let { " ($it)" } ?: "") +
                    ". Check its key in Providers & Agents, or try again in a moment. " +
                    "For coding work, tap Work and the agent will handle it there."
            } else if (!anyConnected) {
                "I can chat here as soon as an API key is connected. Open Providers & Agents and " +
                    "connect a free key (OpenRouter or Groq, for example) \u2014 then ask me again. " +
                    "For coding work, tap Work and the agent will handle it there."
            } else {
                "I could not get a reply from any connected provider" +
                    (lastError?.let { " ($it)" } ?: "") +
                    ". Check the keys in Providers & Agents, or try again in a moment."
            }
            appendHomeMessage(
                threadId,
                com.vayro.app.data.HomeChatMessage(fromUser = false, text = finalText),
            )
        } finally {
            _state.update { it.copy(homeSending = false) }
        }
    }

    /**
     * F2 fallback: when no catalog provider is connected, the workspace's
     * current provider still answers home chat if it is key-backed (the
     * pre-v3.2.37 default path). One attempt, active key only.
     */
    /** v3.2.38 (J): the workspace fallback now reports whether a key was actually tried and why it failed. */
    private data class WorkspaceFallbackOutcome(
        val text: String?,
        val kind: ProviderKind?,
        val triedKey: Boolean,
        val error: String?,
    )

    private suspend fun tryWorkspaceProviderFallback(
        turns: List<ProviderApiClient.ChatTurn>,
        alreadyTried: Set<ProviderKind>,
    ): WorkspaceFallbackOutcome {
        val profile = _state.value.provider
        if (profile.kind == ProviderKind.CLAUDE || profile.kind in alreadyTried) {
            return WorkspaceFallbackOutcome(null, null, triedKey = false, error = null)
        }
        val secret = vault.get(profile.kind.name)
            ?: return WorkspaceFallbackOutcome(null, null, triedKey = false, error = null)
        return try {
            val text = providerApi.chatCompletion(
                baseUrl = profile.resolvedBaseUrl,
                model = profile.model,
                apiKey = secret,
                protocol = profile.kind.protocol,
                turns = turns,
                systemPrompt = HOME_CHAT_SYSTEM_PROMPT,
            )
            WorkspaceFallbackOutcome(text, profile.kind, triedKey = true, error = null)
        } catch (e: ChatApiException) {
            WorkspaceFallbackOutcome(null, profile.kind, triedKey = true, error = e.providerMessage ?: e.message)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            WorkspaceFallbackOutcome(null, profile.kind, triedKey = true, error = e.message)
        }
    }

    fun sendPrompt(prompt: String) {
        if (permissionPolicyEngine.isEmergencyKillSwitchEngaged()) {
            _state.update { it.copy(toastMessage = "Emergency kill switch is ACTIVE — agent sessions are blocked until it is turned off.") }
            return
        }
        val project = state.value.activeProject ?: return
        if (_state.value.agentKind == AgentKind.ANTIGRAVITY &&
            _state.value.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN) {
            _state.update { it.copy(toastMessage = "Sign in to Antigravity from Settings before starting a task.") }
            return
        }
        if (_state.value.agentKind == AgentKind.DEEPSEEK_HARNESS && _state.value.provider.kind == ProviderKind.CLAUDE) {
            _state.update { it.copy(toastMessage = "Claude subscription login is not supported by DeepSeek Harness — pick a key-based provider in Settings.") }
            return
        }
        val attachments = state.value.pendingAttachments
        if ((prompt.isBlank() && attachments.isEmpty()) || state.value.isRunning) return
        val requestText = prompt.trim().ifBlank { "Please review the attached files." }
        MobileHarnessApplication.appScope.launch { runCatching { teamConcurrencyController.evaluateNow() } }
        if (runningSessions.size >= teamConcurrencyController.currentMaxAgents()) {
            sessionStartQueue.addLast(QueuedSessionStart(project.id, requestText, attachments))
            val reason = teamConcurrencyController.state.value.throttleReason
            _state.update {
                it.copy(
                    toastMessage = "Waiting for resources" +
                        (reason?.let { r -> " ($r)" } ?: "") +
                        " - your task is queued and will start automatically.",
                )
            }
            return
        }
        beginSession(project, requestText, attachments)
    }

    private fun beginSession(project: Project, requestText: String, attachments: List<ChatAttachment>, admissionChecked: Boolean = false) {
        if (_state.value.activeProject?.id == project.id && _state.value.isRunning) return
        if (!admissionChecked) {
            MobileHarnessApplication.appScope.launch { runCatching { teamConcurrencyController.evaluateNow() } }
            if (runningSessions.size >= teamConcurrencyController.currentMaxAgents()) {
                sessionStartQueue.addLast(QueuedSessionStart(project.id, requestText, attachments))
                return
            }
        }
        val sessionId = UUID.randomUUID().toString()
        val mentionedPaths = resolveMentionedWorkspacePaths(requestText, project)
        updateActiveChatTitle(requestText)
        // U9: plain conversation (greetings, small talk, tool-free questions)
        // skips the whole task ceremony — no mode wrap, no delivery
        // contract, no plan item, no orchestrator task.
        val conversational = isConversationalTurn(requestText, attachments.isNotEmpty(), mentionedPaths)
        val mode = _state.value.executionMode
        val modeInstruction = if (conversational) "" else agentChatController.buildModeInstruction(mode, requestText)
        _state.update {
            val startedAt = System.currentTimeMillis()
            val thinkItem = ActivityItem("Think", requestPlanningSummary(requestText, it.agentKind), false)
            val initialScratchpad = deriveScratchpadItems(it.activeRoadmap, listOf(thinkItem), true)
            it.copy(
                messages = it.messages + ChatMessage(fromUser = true, text = requestText, attachments = attachments),
                pendingAttachments = emptyList(),
                isRunning = true,
                streamingMessageId = null,
                activity = if (conversational) {
                    listOf(ActivityItem("Thinking", "Replying to your message", false)) + it.activity
                } else {
                    listOf(ActivityItem("Understanding your request", "Preparing a safe plan", false)) + it.activity
                },
                liveProcess = if (conversational) emptyList() else listOf(thinkItem),
                scratchpadItems = if (conversational) it.scratchpadItems else initialScratchpad,
                liveThinking = true,
                activeThinkingBlockId = null,
                taskStartedAtMillis = startedAt,
                taskFinishedAtMillis = null,
                workSegmentStartedAtMillis = startedAt,
                currentTaskRequest = requestText,
            )
        }
        val activeProj = state.value.activeProject
        if (activeProj != null && !conversational) {
            val workspaceDir = File(getApplication<Application>().filesDir, "projects/${activeProj.id}")
            agentOrchestrator.startTask(
                projectId = activeProj.id,
                workspaceDir = workspaceDir,
                userGoal = requestText,
                plan = emptyList(),
            )
            agentOrchestrator.saveState(getApplication<Application>().filesDir, activeProj.id)
        }
        agentSessionController.startSession(
            sessionId = sessionId,
            projectId = project.id,
            projectTitle = project.name,
            chatId = _state.value.activeChatId.orEmpty(),
            prompt = requestText,
            roadmap = _state.value.activeRoadmap,
        )
        touchProject(project.id)
        persistMessages()
        val history = state.value.messages
        val runtimePrompt = agentChatController.buildEnrichedPrompt(
            requestText = requestText,
            modeInstruction = modeInstruction,
            mentionedPaths = mentionedPaths,
            attachments = attachments,
            guestRoot = guestRootFor(project),
            skillContext = skillManager.buildPromptContext(state.value.agentKind),
            conversational = conversational,
        )
        failedApiKeyIds.clear()
        val bridge = sessionBridge(project.id)
        sessionRuntimeRequests[sessionId] = RuntimeRetryRequest(
            runtime = bridge,
            project = project,
            prompt = runtimePrompt,
            history = history,
            provider = state.value.provider,
        )
        sessionProjects[sessionId] = project.id
        runningSessions[sessionId] = project.id
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                getApplication<Application>(),
                Intent(getApplication<Application>(), RuntimeExecutionService::class.java).apply {
                    action = RuntimeExecutionService.ACTION_START
                    putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, sessionId)
                    putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                    putExtra(RuntimeExecutionService.EXTRA_AGENT_NAME, _state.value.agentKind.title)
                    putExtra(RuntimeExecutionService.EXTRA_DETAIL, "${_state.value.agentKind.title} is starting work in ${project.name}")
                    putExtra(RuntimeExecutionService.EXTRA_ROUTE, "project/${project.slug}")
                },
            )
        }
        MobileHarnessApplication.appScope.launch {
            val request = sessionRuntimeRequests[sessionId] ?: return@launch
            val workspace = withContext(Dispatchers.IO) {
                val root = projectWorkspaceRoot(request.project)
                skillManager.syncToWorkspace(root)
                root
            }
            val enrichedPrompt = withContext(Dispatchers.IO) {
                val context = contextEngine.buildPromptContext(
                    projectId = request.project.id,
                    workspace = workspace,
                    request = requestText,
                    mentionedPaths = mentionedPaths,
                    maxReferences = 12,
                )
                if (context.isBlank()) request.prompt else request.prompt + "\n\n" + context
            }
            val enriched = request.copy(prompt = enrichedPrompt)
            sessionRuntimeRequests[sessionId] = enriched
            sessionRecoveryStates[sessionId] = SessionRecoveryState()
            runCatching {
                enriched.runtime.startSession(
                    enriched.project.id,
                    enriched.project.slug,
                    enriched.project.kind,
                    enriched.prompt,
                    enriched.history,
                    enriched.provider,
                    sessionId,
                )
            }.onFailure { error ->
                val failureReason = RuntimeFailureClassifier.friendlyNetworkErrorMessage(error.message.orEmpty())
                onRuntimeEvent(project.id, RuntimeEvent.SessionFailed(sessionId, failureReason))
            }
        }
    }

    private fun pumpSessionQueue() {
        viewModelScope.launch {
            if (sessionStartQueue.isEmpty()) return@launch
            runCatching { teamConcurrencyController.evaluateNow() }
            while (true) {
                if (runningSessions.size >= teamConcurrencyController.currentMaxAgents()) return@launch
                val queued = sessionStartQueue.removeFirstOrNull() ?: return@launch
                val project = _state.value.projects.firstOrNull { it.id == queued.projectId } ?: continue
                if (_state.value.activeProject?.id != project.id) openProject(project)
                beginSession(project, queued.prompt, queued.attachments, admissionChecked = true)
            }
        }
    }

    private fun resolveMentionedWorkspacePaths(prompt: String, project: Project): List<String> =
        agentChatController.resolveMentionedWorkspacePaths(prompt, projectWorkspaceRoot(project))
    fun answerApproval(approved: Boolean) {
        answerApprovalWithLevel(com.vayro.app.security.PermissionLevel.ONCE, approved)
    }

    fun answerApprovalWithLevel(level: com.vayro.app.security.PermissionLevel, approved: Boolean) {
        val request = state.value.pendingApproval ?: return
        val project = _state.value.activeProject
        val target = request.affectedPaths.firstOrNull() ?: request.toolName
        val category = when {
            request.toolName.contains("bash", true) || request.toolName.contains("exec", true) -> com.vayro.app.security.PermissionCategory.SHELL_EXEC
            request.toolName.contains("write", true) || request.toolName.contains("edit", true) -> com.vayro.app.security.PermissionCategory.FILE_WRITE
            request.toolName.contains("delete", true) || request.toolName.contains("remove", true) -> com.vayro.app.security.PermissionCategory.FILE_DELETE
            else -> com.vayro.app.security.PermissionCategory.FILE_READ
        }
        if (approved) {
            permissionPolicyEngine.grantPermission(
                category = category,
                level = level,
                targetPattern = target,
                projectId = project?.slug ?: project?.id,
                sessionId = _state.value.activeSessionId
            )
        }
        _state.update { it.copy(pendingApproval = null) }
        MobileHarnessApplication.appScope.launch {
            project?.let { sessionBridge(it.id).respondToApproval(request, approved) }
        }
    }

    fun stopTask() {
        val project = _state.value.activeProject ?: return
        val scoped = sessionScopeFor(project.id)
        if (!scoped.isRunning && !scoped.runtimeRecoveryCanResume) return
        val sessionId = scoped.activeSessionId
        sessionId?.let { sid ->
            sessionRecoveryStates.remove(sid)?.job?.cancel()
            ignoredSessionIds += sid
            if (ignoredSessionIds.size > 100) ignoredSessionIds.remove(ignoredSessionIds.first())
            sessionRuntimeRequests.remove(sid)
            runningSessions.remove(sid)
            sessionProjects.remove(sid)
            agentSessionController.cancelSession(sid)
        }
        updateSessionScoped(project.id) {
            it.copy(
                isRunning = false,
                streamingMessageId = null,
                activeSessionId = null,
                pendingApproval = null,
                runtimeRecoveryStatus = null,
                runtimeRecoveryAttempt = 0,
                runtimeRecoveryCanResume = false,
            )
        }
        val bridge = projectBridgePool.get(project.id, _state.value.agentKind)
        MobileHarnessApplication.appScope.launch {
            if (sessionId != null) bridge?.stopSession(sessionId)
            runCatching {
                val intent = Intent(getApplication<Application>(), RuntimeExecutionService::class.java).apply {
                    action = RuntimeExecutionService.ACTION_CANCELLED
                    putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, sessionId)
                }
                getApplication<Application>().startService(intent)
            }
            pumpSessionQueue()
        }
    }

    fun undoLastChanges() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val restored = sessionBridge(project.id).undoLastChanges(project.id)
            _state.update { current ->
                current.copy(
                    changes = if (restored) emptyList() else current.changes,
                    activity = listOf(
                        ActivityItem(
                            if (restored) "Changes undone" else "Undo unavailable",
                            if (restored) "Restored files to their state before the task" else "No restorable checkpoint was found",
                        ),
                    ) + current.activity,
                )
            }
            if (restored) refreshProjectFiles()
        }
    }

    fun keepLastChanges() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            sessionBridge(project.id).acceptLastChanges(project.id)
            _state.update {
                it.copy(
                    changes = emptyList(),
                    activity = listOf(ActivityItem("Changes kept", "Accepted the task's file changes")) + it.activity,
                )
            }
        }
    }

    fun undoFileChange(path: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            if (sessionBridge(project.id).undoFileChange(project.id, path)) {
                _state.update { current -> current.copy(changes = current.changes.filterNot { it.path == path }) }
                refreshProjectFiles()
            }
        }
    }

    fun keepFileChange(path: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            if (sessionBridge(project.id).acceptFileChange(project.id, path)) {
                _state.update { current -> current.copy(changes = current.changes.filterNot { it.path == path }) }
            }
        }
    }

    private fun isNoisyRuntimeItem(item: ActivityItem): Boolean =
        agentChatController.isNoisyRuntimeItem(item)

    private fun toolPlanSummary(toolName: String, detail: String): String =
        agentChatController.toolPlanSummary(toolName, detail)

    private fun requestPlanningSummary(
        request: String,
        agentKind: AgentKind,
        toolName: String? = null,
        detail: String = "",
    ): String = agentChatController.requestPlanningSummary(request, agentKind.title, toolName, detail)

    private fun finishWorkSegment(current: AppUiState, finishedAt: Long = System.currentTimeMillis()): AppUiState {
        val (updatedMessages, _) = agentChatController.finishWorkSegment(
            messages = current.messages,
            liveProcess = current.liveProcess,
            liveThinking = current.liveThinking,
            workSegmentStartedAtMillis = current.workSegmentStartedAtMillis,
            taskStartedAtMillis = current.taskStartedAtMillis,
            finishedAt = finishedAt,
        )
        val finishedInterruptedId = "interrupted-${current.activeSessionId ?: current.activeChatId}"
        return current.copy(
            messages = updatedMessages.filterNot { it.id == finishedInterruptedId },
            liveProcess = emptyList(),
            liveThinking = false,
            activeThinkingBlockId = null,
            workSegmentStartedAtMillis = null,
        )
    }

    private fun dropActiveSessionInterruptedRow(messages: List<ChatMessage>): List<ChatMessage> {
        val sessionId = _state.value.activeSessionId ?: return messages
        if (!_state.value.isRunning) return messages
        return messages.filterNot { it.id == "interrupted-$sessionId" }
    }

    /** Adds the complete request duration to the response produced after the latest user message. */
    private fun attachTaskDuration(current: AppUiState, finishedAt: Long): AppUiState =
        current.copy(messages = agentChatController.attachTaskDuration(current.messages, current.taskStartedAtMillis, finishedAt))

    private fun appendWorkItem(current: AppUiState, item: ActivityItem): AppUiState {
        if (isNoisyRuntimeItem(item)) return current
        return current.copy(
            liveProcess = current.liveProcess.map { if (!it.isComplete) it.copy(isComplete = true) else it } + item,
            liveThinking = false,
            workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
        )
    }

    private fun applySliceToState(base: AppUiState, slice: ProjectSessionState): AppUiState = base.copy(
        messages = slice.messages,
        projectChats = slice.projectChats,
        activeChatId = slice.activeChatId,
        pendingApproval = slice.pendingApproval,
        changes = slice.changes,
        activity = slice.activity,
        liveProcess = slice.liveProcess,
        liveThinking = slice.liveThinking,
        activeThinkingBlockId = slice.activeThinkingBlockId,
        taskStartedAtMillis = slice.taskStartedAtMillis,
        taskFinishedAtMillis = slice.taskFinishedAtMillis,
        workSegmentStartedAtMillis = slice.workSegmentStartedAtMillis,
        currentTaskRequest = slice.currentTaskRequest,
        previewReady = slice.previewReady,
        previewUrl = slice.previewUrl,
        isRunning = slice.isRunning,
        streamingMessageId = slice.streamingMessageId,
        activeSessionId = slice.activeSessionId,
        interruptedSession = slice.interruptedSession,
        runtimeRecoveryStatus = slice.runtimeRecoveryStatus,
        runtimeRecoveryAttempt = slice.runtimeRecoveryAttempt,
        runtimeRecoveryCanResume = slice.runtimeRecoveryCanResume,
        activeRoadmap = slice.activeRoadmap,
        scratchpadItems = slice.scratchpadItems,
    )

    private fun sliceFromState(
        projectId: String,
        state: AppUiState,
        previous: ProjectSessionState? = projectSessionStates.value[projectId],
    ): ProjectSessionState = ProjectSessionState(
        projectId = projectId,
        messages = state.messages,
        projectChats = state.projectChats,
        activeChatId = state.activeChatId,
        pendingApproval = state.pendingApproval,
        changes = state.changes,
        activity = state.activity,
        liveProcess = state.liveProcess,
        liveThinking = state.liveThinking,
        activeThinkingBlockId = state.activeThinkingBlockId,
        taskStartedAtMillis = state.taskStartedAtMillis,
        taskFinishedAtMillis = state.taskFinishedAtMillis,
        workSegmentStartedAtMillis = state.workSegmentStartedAtMillis,
        currentTaskRequest = state.currentTaskRequest,
        previewReady = state.previewReady,
        previewUrl = state.previewUrl,
        isRunning = state.isRunning,
        streamingMessageId = state.streamingMessageId,
        activeSessionId = state.activeSessionId,
        interruptedSession = state.interruptedSession,
        runtimeRecoveryStatus = state.runtimeRecoveryStatus,
        runtimeRecoveryAttempt = state.runtimeRecoveryAttempt,
        runtimeRecoveryCanResume = state.runtimeRecoveryCanResume,
        activeRoadmap = state.activeRoadmap,
        scratchpadItems = state.scratchpadItems,
        pendingOrchestratorTransition = previous?.pendingOrchestratorTransition,
    )

    private fun sessionScopeFor(projectId: String): AppUiState {
        val current = _state.value
        if (projectId == current.activeProject?.id) return current
        val slice = projectSessionStates.value[projectId] ?: ProjectSessionState(projectId = projectId)
        return applySliceToState(current, slice)
    }

    private fun updateSessionScoped(projectId: String, transform: (AppUiState) -> AppUiState) {
        if (projectId == _state.value.activeProject?.id) {
            _state.update(transform)
            return
        }
        val slice = projectSessionStates.value[projectId] ?: ProjectSessionState(projectId = projectId)
        val updated = transform(applySliceToState(_state.value, slice))
        projectSessionStates.update { it + (projectId to sliceFromState(projectId, updated, slice)) }
    }

    private fun persistSessionSlice(projectId: String) {
        val slice = sliceFromState(projectId, _state.value)
        projectSessionStates.update { it + (projectId to slice) }
    }

    private fun onRuntimeEvent(event: RuntimeEvent) {
        val projectId = sessionProjects[event.sessionId]
            ?: agentSessionController.sessionProjectId(event.sessionId)
            ?: _state.value.activeProject?.id
            ?: return
        onRuntimeEvent(projectId, event)
    }

    private fun onRuntimeEvent(projectId: String, event: RuntimeEvent) {
        if (event.sessionId in ignoredSessionIds) return
        if (event is RuntimeEvent.SessionStarted) {
            runningSessions[event.sessionId] = projectId
            sessionProjects[event.sessionId] = projectId
        }
        if (event is RuntimeEvent.AssistantDelta) {
            val buffer = pendingAssistantDeltas.getOrPut(event.sessionId) { StringBuilder() }
            synchronized(buffer) { buffer.append(event.text) }
            if (assistantDeltaFlushJobs[event.sessionId] == null) {
                assistantDeltaFlushJobs[event.sessionId] = viewModelScope.launch {
                    delay(100)
                    flushPendingAssistantDelta(projectId, event.sessionId)
                }
            }
            return
        }

        flushPendingAssistantDelta(projectId, event.sessionId)
        if (event is RuntimeEvent.SessionCompleted || event is RuntimeEvent.SessionFailed) {
            viewModelScope.launch(Dispatchers.Default) {
                processRuntimeEvent(projectId, event)
            }
            return
        }
        processRuntimeEvent(projectId, event)
    }

    private fun flushPendingAssistantDelta(projectId: String, sessionId: String) {
        assistantDeltaFlushJobs.remove(sessionId)?.cancel()
        val buffer = pendingAssistantDeltas[sessionId] ?: return
        val text = synchronized(buffer) {
            if (buffer.isEmpty()) return
            val t = buffer.toString()
            buffer.clear()
            t
        }
        processRuntimeEvent(projectId, RuntimeEvent.AssistantDelta(sessionId, text))
    }

    private fun persistProjectMessages(projectId: String) {
        val slice = projectSessionStates.value[projectId] ?: return
        val chatId = slice.activeChatId ?: return
        val liveItems = slice.liveProcess.filterNot(::isNoisyRuntimeItem)
        val messages = if (liveItems.isEmpty() && !slice.liveThinking) {
            slice.messages
        } else {
            val startedAt = slice.workSegmentStartedAtMillis ?: slice.taskStartedAtMillis ?: System.currentTimeMillis()
            val interruptedId = "interrupted-${slice.activeSessionId ?: chatId}"
            if (slice.messages.any { it.id == interruptedId }) {
                slice.messages
            } else {
                slice.messages + ChatMessage(
                    id = interruptedId,
                    fromUser = false,
                    text = "",
                    workItems = liveItems.map { it.copy(isComplete = true) } + ActivityItem(
                        "Task interrupted",
                        "The agent process stopped before reporting completion. Continue this chat to resume its official session.",
                    ),
                    workedMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
                )
            }
        }
        transcriptWrites.trySend(TranscriptWrite(projectId, chatId, messages))
    }

    private fun trimMessages(messages: List<ChatMessage>): List<ChatMessage> =
        if (messages.size > maxInMemoryMessages) messages.takeLast(maxInMemoryMessages) else messages

    private fun processRuntimeEvent(projectId: String, event: RuntimeEvent) {
        val scoped = sessionScopeFor(projectId)
        val isActiveProject = projectId == _state.value.activeProject?.id
        val lastUserIdx = scoped.messages.indexOfLast { it.fromUser }
        val hasResponseAlready = scoped.messages.indices.any {
            it > lastUserIdx && !scoped.messages[it].fromUser && scoped.messages[it].text.isNotBlank()
        }
        if (event is RuntimeEvent.SessionFailed && retryAntigravityWithNextAccount(projectId, event)) return
        if (event is RuntimeEvent.SessionFailed && scoped.agentKind == AgentKind.ANTIGRAVITY &&
            (event.reason.contains("sign-in", true) || event.reason.contains("authentication", true) || event.reason.contains("401", true))) {
            antigravityAuthController.invalidateSession(event.reason)
        }
        if (event is RuntimeEvent.SessionFailed && !hasResponseAlready && retryWithNextApiKey(projectId, event)) return
        if (event is RuntimeEvent.SessionFailed && !hasResponseAlready && recoverTransientRuntimeFailure(projectId, event)) return
        var backgroundTransition: OrchestratorTransition? = null
        updateSessionScoped(projectId) { current ->
            if (!current.isRunning) {
                current
            } else if (current.activeSessionId != null && current.activeSessionId != event.sessionId) {
                current
            } else when (event) {
                is RuntimeEvent.SessionStarted -> {
                    sessionRecoveryStates[event.sessionId]?.job?.cancel()
                    sessionRecoveryStates[event.sessionId] = SessionRecoveryState()
                    current.copy(
                        activeSessionId = event.sessionId,
                        runtimeRecoveryStatus = null,
                        runtimeRecoveryCanResume = false,
                        activity = current.activity.mapIndexed { index, item -> if (index == 0) item.copy(isComplete = true) else item },
                    )
                }
                is RuntimeEvent.AssistantDelta -> {
                    val timeline = if (current.liveThinking || current.liveProcess.any { !isNoisyRuntimeItem(it) }) {
                        finishWorkSegment(current)
                    } else {
                        current
                    }
                    val updatedMessages = agentChatController.appendAssistantDelta(timeline.messages, event.text)
                    val streamId = updatedMessages.lastOrNull()?.takeIf { !it.fromUser }?.id
                    timeline.copy(messages = trimMessages(updatedMessages), streamingMessageId = streamId)
                }
                is RuntimeEvent.ReasoningProgress -> {
                    val existingIndex = current.liveProcess.indexOfLast { it.title == "Think" }
                    // The request-level Think summary is seeded once in sendPrompt.
                    // After that segment has been committed to the timeline, later
                    // agent turns must not repeat the same request summary.
                    if (existingIndex < 0) return@updateSessionScoped current
                    val reasoning = ActivityItem(
                        title = "Think",
                        detail = current.liveProcess.getOrNull(existingIndex)?.detail
                            ?: requestPlanningSummary(current.currentTaskRequest.orEmpty(), current.agentKind),
                        isComplete = false,
                    )
                    val process = if (existingIndex >= 0) {
                        current.liveProcess.toMutableList().also { it[existingIndex] = reasoning }
                    } else {
                        current.liveProcess + reasoning
                    }
                    current.copy(
                        liveProcess = process,
                        liveThinking = true,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.ReasoningSummary -> {
                    val summary = event.summary.trim()
                    val process = current.liveProcess.toMutableList()
                    val existingIndex = process.indexOfLast { !it.isComplete && it.title == "Think" }
                    if (event.startsNewBlock) {
                        process.indices.forEach { index ->
                            if (!process[index].isComplete) process[index] = process[index].copy(isComplete = true)
                        }
                        val initial = summary.ifBlank { "Thinking…" }
                        val replaceFallback = current.activeThinkingBlockId == null &&
                            process.size == 1 && process.first().title == "Think"
                        if (replaceFallback) {
                            process[0] = ActivityItem("Think", initial, event.isFinal)
                        } else {
                            process += ActivityItem("Think", initial, event.isFinal)
                        }
                    } else if (current.activeThinkingBlockId == event.blockId && existingIndex >= 0 && summary.isNotBlank()) {
                        process[existingIndex] = process[existingIndex].copy(
                            detail = summary,
                            isComplete = event.isFinal,
                        )
                    } else {
                        return@updateSessionScoped current
                    }
                    current.copy(
                        liveProcess = process,
                        liveThinking = !event.isFinal,
                        activeThinkingBlockId = if (event.isFinal) null else event.blockId,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.ToolStarted -> {
                    val process = current.liveProcess.map { item ->
                        if (!item.isComplete) item.copy(isComplete = true) else item
                    }
                    val toolItem = ActivityItem("Running ${event.toolName}", event.detail, false, isCommand = event.toolName == "Bash")
                    val updatedProcess = process + toolItem
                    val scratchpad = deriveScratchpadItems(current.activeRoadmap, updatedProcess, true)
                    val planned = current.copy(
                        liveProcess = process,
                        scratchpadItems = scratchpad,
                        liveThinking = false,
                        activeThinkingBlockId = null,
                        activity = listOf(
                            ActivityItem("Running ${event.toolName}", event.detail, false, isCommand = event.toolName == "Bash"),
                        ) + current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it },
                    )
                    appendWorkItem(
                        planned,
                        toolItem,
                    )
                }
                is RuntimeEvent.RuntimeLog -> appendWorkItem(
                    current.copy(activity = listOf(ActivityItem(event.title, event.detail)) + current.activity),
                    ActivityItem(event.title, event.detail),
                )
                is RuntimeEvent.ToolRequested -> appendWorkItem(current.copy(
                    pendingApproval = event.request,
                    activity = listOf(ActivityItem("Waiting for approval", event.request.explanation, false)) + current.activity,
                ), ActivityItem("Waiting for approval", event.request.explanation, false))
                is RuntimeEvent.ToolApproved -> appendWorkItem(current.copy(
                    pendingApproval = null,
                    activity = listOf(ActivityItem("Applying approved changes", "Editing project files", false)) + current.activity,
                ), ActivityItem("Action approved", "${current.agentKind.title} is continuing the task", false))
                is RuntimeEvent.ToolRejected -> appendWorkItem(current.copy(
                    pendingApproval = null,
                ), ActivityItem("Action rejected", "${current.agentKind.title} will continue without this action"))
                is RuntimeEvent.ToolCompleted -> {
                    val runningIndex = current.liveProcess.indexOfLast {
                        !it.isComplete && it.title == "Running ${event.toolName}"
                    }
                    val process = if (runningIndex >= 0) {
                        current.liveProcess.toMutableList().also { items ->
                            val runningItem = items[runningIndex]
                            items[runningIndex] = ActivityItem(
                                "${event.toolName} completed",
                                runningItem.detail.ifBlank { event.summary },
                                isCommand = event.toolName == "Bash",
                            )
                        }
                    } else {
                        current.liveProcess + ActivityItem(
                            "${event.toolName} completed",
                            event.summary,
                            isCommand = event.toolName == "Bash",
                        )
                    }
                    val scratchpad = deriveScratchpadItems(current.activeRoadmap, process, true)
                    current.copy(
                        activity = listOf(ActivityItem(event.summary, event.toolName)) + current.activity,
                        liveProcess = process,
                        scratchpadItems = scratchpad,
                        liveThinking = false,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.FilesChanged -> current.copy(
                    changes = event.changes,
                    liveThinking = false,
                    liveProcess = if (event.paths.isEmpty()) current.liveProcess else current.liveProcess +
                        ActivityItem(
                            "Files changed",
                            event.paths.take(4).joinToString(", ") + if (event.paths.size > 4) " +${event.paths.size - 4} more" else "",
                        ),
                    workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                )
                is RuntimeEvent.PreviewStarted -> current.copy(
                    previewReady = true,
                    previewUrl = event.url,
                    activity = listOf(ActivityItem("Preview ready", event.url)) + current.activity,
                    liveProcess = current.liveProcess + ActivityItem("Preview ready", event.url),
                    liveThinking = false,
                    workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                )
                is RuntimeEvent.SessionCompleted -> {
                    val finishedAt = System.currentTimeMillis()
                    val totalDurationText = current.taskStartedAtMillis?.let { start ->
                        formatDurationText(((finishedAt - start) / 1000L).coerceAtLeast(1L))
                    } ?: "a few seconds"
                    sessionRecoveryStates.remove(event.sessionId)
                    val segmentFinishedState = finishWorkSegment(current, finishedAt)
                    val stateWithResponse = ensureCompletionMessage(segmentFinishedState, totalDurationText)
                    val completedActiveRoadmap = current.activeRoadmap?.let { rm ->
                        if (rm.isApproved) {
                            rm.copy(steps = rm.steps.map { if (it.status != StepStatus.SKIPPED) it.copy(status = StepStatus.COMPLETED) else it })
                        } else {
                            rm
                        }
                    }
                    val messagesWithRoadmap = stateWithResponse.messages.mapIndexed { index, msg ->
                        val isRecent = index >= stateWithResponse.messages.size - 20
                        if (msg.roadmap?.id == current.activeRoadmap?.id && completedActiveRoadmap != null) {
                            msg.copy(roadmap = completedActiveRoadmap)
                        } else if (isRecent && !msg.fromUser && msg.roadmap == null && msg.text.isNotBlank() &&
                            msg.text.length <= 30000 && current.executionMode != ExecutionMode.BUILD) {
                            val parsed = RoadmapParser.parseFromText(msg.text, stateWithResponse.executionMode)
                            if (parsed != null) msg.copy(roadmap = parsed) else msg
                        } else {
                            msg
                        }
                    }
                    val newlyParsedRoadmap = messagesWithRoadmap.mapNotNull { it.roadmap }.lastOrNull()
                    val latestRoadmap = if (current.executionMode != ExecutionMode.BUILD && newlyParsedRoadmap != null && newlyParsedRoadmap.id != current.activeRoadmap?.id) {
                        newlyParsedRoadmap
                    } else {
                        completedActiveRoadmap ?: newlyParsedRoadmap ?: current.activeRoadmap
                    }
                    val scratchpad = deriveScratchpadItems(latestRoadmap, emptyList(), false)
                    agentSessionController.completeSession(event.sessionId, "Task completed successfully")
                    if (isActiveProject) {
                        agentOrchestrator.transition(com.vayro.app.session.EngineeringState.COMPLETED, "Task completed successfully")
                    } else {
                        backgroundTransition = OrchestratorTransition(
                            com.vayro.app.session.EngineeringState.COMPLETED,
                            "Task completed successfully",
                        )
                    }
                    attachTaskDuration(stateWithResponse.copy(messages = trimMessages(messagesWithRoadmap)), finishedAt).copy(
                        isRunning = false,
                        streamingMessageId = null,
                        activeSessionId = null,
                        activeRoadmap = latestRoadmap,
                        scratchpadItems = scratchpad,
                        activity = listOf(ActivityItem("Task completed", "${current.agentKind.title} finished successfully in $totalDurationText")) +
                            current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it },
                        taskFinishedAtMillis = finishedAt,
                        currentTaskRequest = null,
                        runtimeRecoveryStatus = null,
                        runtimeRecoveryAttempt = 0,
                        runtimeRecoveryCanResume = false,
                    )
                }
                is RuntimeEvent.SessionFailed -> {
                    agentSessionController.failSession(event.sessionId, event.reason)
                    if (isActiveProject) {
                        agentOrchestrator.transition(com.vayro.app.session.EngineeringState.FAILED, "Task failed", event.reason)
                    } else {
                        backgroundTransition = OrchestratorTransition(
                            com.vayro.app.session.EngineeringState.FAILED,
                            "Task failed",
                            event.reason,
                        )
                    }
                    val lastUserIndex = current.messages.indexOfLast { it.fromUser }
                    val hasResponseForLastUser = current.messages.indices.any {
                        it > lastUserIndex && !current.messages[it].fromUser && current.messages[it].text.isNotBlank()
                    }
                    if (hasResponseForLastUser && !event.reason.contains("Stopped by user", ignoreCase = true)) {
                        // The assistant already completed and delivered its response for the current user turn.
                        // Trailing exit code or socket teardown must not fail a completed task or append "Task stopped".
                        val finishedAt = System.currentTimeMillis()
                        val totalDurationText = current.taskStartedAtMillis?.let { start ->
                            formatDurationText(((finishedAt - start) / 1000L).coerceAtLeast(1L))
                        } ?: "a few seconds"
                        sessionRecoveryStates.remove(event.sessionId)
                        val segmentFinishedState = finishWorkSegment(current, finishedAt)
                        val completedActiveRoadmap = current.activeRoadmap?.let { rm ->
                            if (rm.isApproved) {
                                rm.copy(steps = rm.steps.map { if (it.status != StepStatus.SKIPPED) it.copy(status = StepStatus.COMPLETED) else it })
                            } else {
                                rm
                            }
                        }
                        val messagesWithRoadmap = segmentFinishedState.messages.mapIndexed { index, msg ->
                            val isRecent = index >= segmentFinishedState.messages.size - 20
                            if (msg.roadmap?.id == current.activeRoadmap?.id && completedActiveRoadmap != null) {
                                msg.copy(roadmap = completedActiveRoadmap)
                            } else if (isRecent && !msg.fromUser && msg.roadmap == null && msg.text.isNotBlank() &&
                                msg.text.length <= 30000 && current.executionMode != ExecutionMode.BUILD) {
                                val parsed = RoadmapParser.parseFromText(msg.text, segmentFinishedState.executionMode)
                                if (parsed != null) msg.copy(roadmap = parsed) else msg
                            } else {
                                msg
                            }
                        }
                        val failedNewlyParsedRoadmap = messagesWithRoadmap.mapNotNull { it.roadmap }.lastOrNull()
                        val latestRoadmap = if (current.executionMode != ExecutionMode.BUILD && failedNewlyParsedRoadmap != null && failedNewlyParsedRoadmap.id != current.activeRoadmap?.id) {
                            failedNewlyParsedRoadmap
                        } else {
                            completedActiveRoadmap ?: failedNewlyParsedRoadmap ?: current.activeRoadmap
                        }
                        val scratchpad = deriveScratchpadItems(latestRoadmap, emptyList(), false)
                        attachTaskDuration(segmentFinishedState.copy(messages = trimMessages(messagesWithRoadmap)), finishedAt).copy(
                            isRunning = false,
                            streamingMessageId = null,
                            activeSessionId = null,
                            activeRoadmap = latestRoadmap,
                            scratchpadItems = scratchpad,
                            activity = listOf(ActivityItem("Task completed", "${current.agentKind.title} finished successfully in $totalDurationText")) +
                                current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it },
                            taskFinishedAtMillis = finishedAt,
                            currentTaskRequest = null,
                            runtimeRecoveryStatus = null,
                            runtimeRecoveryAttempt = 0,
                            runtimeRecoveryCanResume = false,
                        )
                    } else {
                        val finishedAt = System.currentTimeMillis()
                        sessionRecoveryStates.remove(event.sessionId)
                        val scratchpad = deriveScratchpadItems(current.activeRoadmap, emptyList(), false)
                        attachTaskDuration(
                            finishWorkSegment(
                                appendWorkItem(current, ActivityItem("Task stopped", event.reason)),
                                finishedAt,
                            ),
                            finishedAt,
                        ).copy(
                            isRunning = false,
                            streamingMessageId = null,
                            activeSessionId = null,
                            pendingApproval = null,
                            scratchpadItems = scratchpad,
                            toastMessage = sanitizeSessionFailedToast(event.reason),
                            activity = listOf(ActivityItem("Task stopped", event.reason)) + current.activity,
                            taskFinishedAtMillis = finishedAt,
                            currentTaskRequest = null,
                            runtimeRecoveryStatus = null,
                            runtimeRecoveryAttempt = 0,
                            runtimeRecoveryCanResume = false,
                        )
                    }
                }
            }
        }
        backgroundTransition?.let { transition ->
            projectSessionStates.update { current ->
                current + (projectId to (current[projectId] ?: ProjectSessionState(projectId)).copy(pendingOrchestratorTransition = transition))
            }
        }
        val scopedAfter = sessionScopeFor(projectId)
        if (event is RuntimeEvent.SessionCompleted || (event is RuntimeEvent.SessionFailed && !scopedAfter.runtimeRecoveryCanResume)) {
            sessionRuntimeRequests.remove(event.sessionId)
            failedApiKeyIds.clear()
            runningSessions.remove(event.sessionId)
            sessionProjects.remove(event.sessionId)
            pendingAssistantDeltas.remove(event.sessionId)
            assistantDeltaFlushJobs.remove(event.sessionId)?.cancel()
            lastAssistantDeltaPersistAtMillis.remove(event.sessionId)
            sessionRecoveryStates.remove(event.sessionId)?.job?.cancel()
            if (isActiveProject) {
                agentOrchestrator.saveState(getApplication<Application>().filesDir)
            }
            runCatching {
                val intent = Intent(getApplication<Application>(), RuntimeExecutionService::class.java).apply {
                    action = RuntimeExecutionService.ACTION_CANCELLED
                    putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, event.sessionId)
                }
                getApplication<Application>().startService(intent)
            }
            pumpSessionQueue()
        }
        if (event is RuntimeEvent.FilesChanged || event is RuntimeEvent.SessionCompleted ||
            (event is RuntimeEvent.SessionFailed && hasResponseAlready)) {
            touchProject(projectId)
            if (isActiveProject) refreshProjectFiles()
        }
        val lastPersistAt = lastAssistantDeltaPersistAtMillis[event.sessionId] ?: 0L
        if (event !is RuntimeEvent.AssistantDelta ||
            System.currentTimeMillis() - lastPersistAt >= 1_500L
        ) {
            if (isActiveProject) persistMessages(includeLiveProcess = true) else persistProjectMessages(projectId)
            if (event is RuntimeEvent.AssistantDelta) {
                lastAssistantDeltaPersistAtMillis[event.sessionId] = System.currentTimeMillis()
            }
        }
    }

    private fun ensureCompletionMessage(state: AppUiState, totalDurationText: String): AppUiState =
        state.copy(messages = agentChatController.ensureCompletionMessage(state.messages, totalDurationText))

    private fun recoverTransientRuntimeFailure(projectId: String, event: RuntimeEvent.SessionFailed): Boolean {
        val scoped = sessionScopeFor(projectId)
        val isActiveProject = projectId == _state.value.activeProject?.id
        val request = sessionRuntimeRequests[event.sessionId] ?: return false
        if (!scoped.isRunning || (scoped.activeSessionId != null && scoped.activeSessionId != event.sessionId)) return false
        if (!RuntimeFailureClassifier.isTransientNetworkFailure(event.reason)) return false

        val recovery = sessionRecoveryStates.getOrPut(event.sessionId) { SessionRecoveryState() }
        val now = System.currentTimeMillis()
        val recoveryStartedAt = recovery.startedAtMillis ?: now.also { recovery.startedAtMillis = it }
        val elapsedMillis = (now - recoveryStartedAt).coerceAtLeast(0L)
        val maxDurationMillis = runtimeRetryPolicy.maxTotalRetryDurationMillis

        if (elapsedMillis >= maxDurationMillis || recovery.attempt >= runtimeRetryPolicy.maxAutomaticRetries) {
            val friendlyError = "Network connection could not be restored after 3 minutes of retries. Your task and changes are preserved. Please check your internet connection and tap Resume."
            recovery.job?.cancel()
            sessionRecoveryStates.remove(event.sessionId)
            val finishedAt = System.currentTimeMillis()
            updateSessionScoped(projectId) { state ->
                attachTaskDuration(
                    finishWorkSegment(
                        appendWorkItem(state, ActivityItem("Task stopped", friendlyError)),
                        finishedAt,
                    ),
                    finishedAt,
                ).copy(
                    isRunning = false,
                    streamingMessageId = null,
                    activeSessionId = null,
                    runtimeRecoveryStatus = "Connection lost (3m timeout)",
                    runtimeRecoveryCanResume = true,
                    activity = listOf(ActivityItem("Task stopped", friendlyError)) + state.activity,
                    taskFinishedAtMillis = finishedAt,
                    currentTaskRequest = null,
                )
            }
            if (isActiveProject) persistMessages(includeLiveProcess = true) else persistProjectMessages(projectId)
            NotificationCoordinator.postResult(
                getApplication<Application>(),
                "Task paused · Network error",
                friendlyError,
                true,
            )
            runCatching {
                val intent = Intent(getApplication<Application>(), RuntimeExecutionService::class.java).apply {
                    action = RuntimeExecutionService.ACTION_CANCELLED
                    putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, event.sessionId)
                }
                getApplication<Application>().startService(intent)
            }
            return true
        }

        recovery.job?.cancel()
        recovery.attempt += 1
        val attempt = recovery.attempt
        val retryDelay = runtimeRetryPolicy.delayMillis(attempt, kotlin.random.Random.nextDouble())
            .coerceAtMost((maxDurationMillis - elapsedMillis).coerceAtLeast(1_000L))
        val remainingSeconds = (((maxDurationMillis - elapsedMillis) / 1000L).coerceAtLeast(1L))
        val statusText = "Reconnecting… (${remainingSeconds}s remaining)"

        updateSessionScoped(projectId) { it.copy(
            activeSessionId = null,
            runtimeRecoveryStatus = statusText,
            runtimeRecoveryAttempt = attempt,
            runtimeRecoveryCanResume = false,
            liveProcess = it.liveProcess + ActivityItem(
                "Connection interrupted",
                "Network disturbance detected. Reconnecting… (${remainingSeconds}s remaining, attempt $attempt)",
            ),
        ) }
        if (isActiveProject) persistMessages(includeLiveProcess = true) else persistProjectMessages(projectId)

        runCatching {
            val progressIntent = Intent(getApplication<Application>(), RuntimeExecutionService::class.java).apply {
                action = RuntimeExecutionService.ACTION_PROGRESS
                putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, event.sessionId)
                putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, request.project.slug)
                putExtra(RuntimeExecutionService.EXTRA_AGENT_NAME, scoped.agentKind.title)
                putExtra(RuntimeExecutionService.EXTRA_DETAIL, "${scoped.agentKind.title} reconnecting network… (${remainingSeconds}s remaining)")
            }
            getApplication<Application>().startService(progressIntent)
        }

        recovery.job = MobileHarnessApplication.appScope.launch {
            delay(retryDelay)
            val live = sessionScopeFor(projectId)
            if (!live.isRunning || sessionRuntimeRequests[event.sessionId] !== request) return@launch
            val updatedElapsed = System.currentTimeMillis() - recoveryStartedAt
            val currentRemaining = (((maxDurationMillis - updatedElapsed) / 1000L).coerceAtLeast(1L))
            updateSessionScoped(projectId) { it.copy(
                runtimeRecoveryStatus = "Reconnecting… (${currentRemaining}s remaining)",
                liveProcess = it.liveProcess + ActivityItem("Reconnecting", "Attempting to re-establish connection…"),
            ) }
            runCatching {
                request.runtime.startSession(request.project.id, request.project.slug, request.project.kind, request.prompt, request.history, request.provider)
            }.onFailure { error ->
                val failureReason = RuntimeFailureClassifier.friendlyNetworkErrorMessage(error.message.orEmpty())
                onRuntimeEvent(projectId, RuntimeEvent.SessionFailed(event.sessionId, failureReason))
            }
        }
        return true
    }

    fun resumeInterruptedTask() {
        val project = _state.value.activeProject
        val prompt = project?.let { p -> sessionRuntimeRequests.values.firstOrNull { it.project.id == p.id }?.prompt }
            ?: _state.value.currentTaskRequest
            ?: _state.value.messages.lastOrNull { it.fromUser }?.text
            ?: return
        resumeTaskExecution(prompt = prompt, roadmap = _state.value.activeRoadmap)
    }

    private fun resumeTaskExecution(prompt: String, roadmap: ActiveRoadmap? = null) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning) return

        sessionRecoveryStates.entries
            .filter { sessionProjects[it.key] == project.id }
            .forEach { it.value.job?.cancel() }
        sessionRecoveryStates.entries.removeIf { sessionProjects[it.key] == project.id }

        val lastUserMsg = current.messages.lastOrNull { it.fromUser }
        val resumePrompt = prompt.ifBlank {
            current.currentTaskRequest ?: lastUserMsg?.text.orEmpty()
        }
        if (resumePrompt.isBlank()) return

        // Strip trailing stopped/error messages so chat resumes cleanly
        val cleanedMessages = current.messages.filterNot { msg ->
            !msg.fromUser && msg.text.contains("Task stopped", ignoreCase = true)
        }

        val targetRoadmap = roadmap ?: current.activeRoadmap
        val isRoadmapBuild = targetRoadmap != null && targetRoadmap.isApproved &&
            targetRoadmap.steps.any { it.status == StepStatus.PENDING || it.status == StepStatus.IN_PROGRESS }

        val effectivePrompt = if (isRoadmapBuild && !resumePrompt.contains("<execution_mode")) {
            buildString {
                appendLine("<execution_mode name=\"UNIFIED_BUILD\">")
                appendLine("Resuming previously interrupted roadmap execution.")
                appendLine("Roadmap Title: ${targetRoadmap.title}")
                if (targetRoadmap.summary.isNotBlank()) {
                    appendLine("Summary: ${targetRoadmap.summary}")
                }
                appendLine("Steps to execute:")
                targetRoadmap.steps.forEachIndexed { idx, step ->
                    val statusMark = if (step.status == StepStatus.COMPLETED) "[x]" else "[ ]"
                    appendLine("${idx + 1}. $statusMark ${step.title}")
                    if (step.description.isNotBlank()) {
                        appendLine("   Details: ${step.description}")
                    }
                    if (step.filesAffected.isNotEmpty()) {
                        appendLine("   Files: ${step.filesAffected.joinToString(", ")}")
                    }
                }
                appendLine("Continue building now end-to-end. Implement remaining code changes, build, verify, and complete all steps.")
                appendLine("Once finished, report verification details and conclude directly. Do NOT output a new roadmap or ask the user to Approve & Build again.")
                appendLine("</execution_mode>")
            }
        } else {
            resumePrompt
        }

        val startedAt = System.currentTimeMillis()
        val thinkItem = ActivityItem("Think", "Resuming task: " + requestPlanningSummary(effectivePrompt, current.agentKind), false)
        val initialScratchpad = deriveScratchpadItems(targetRoadmap, listOf(thinkItem), true)

        _state.update { state ->
            state.copy(
                messages = cleanedMessages,
                isRunning = true,
                streamingMessageId = null,
                liveThinking = true,
                activeSessionId = null,
                runtimeRecoveryStatus = null,
                runtimeRecoveryAttempt = 0,
                runtimeRecoveryCanResume = false,
                interruptedSession = null,
                liveProcess = listOf(thinkItem),
                scratchpadItems = initialScratchpad,
                activeThinkingBlockId = null,
                taskStartedAtMillis = startedAt,
                taskFinishedAtMillis = null,
                workSegmentStartedAtMillis = startedAt,
                currentTaskRequest = effectivePrompt,
                activity = listOf(ActivityItem("Resuming task", "Reconnecting and continuing work…", false)) + state.activity,
            )
        }

        val activeProj = state.value.activeProject
        if (activeProj != null) {
            val workspaceDir = File(getApplication<Application>().filesDir, "projects/${activeProj.id}")
            agentOrchestrator.startTask(
                projectId = activeProj.id,
                workspaceDir = workspaceDir,
                userGoal = effectivePrompt,
                plan = targetRoadmap?.steps?.map { it.title } ?: emptyList(),
            )
        }
        val resumeSessionId = UUID.randomUUID().toString()
        agentSessionController.startSession(
            sessionId = resumeSessionId,
            projectId = project.id,
            projectTitle = project.name,
            chatId = _state.value.activeChatId.orEmpty(),
            prompt = effectivePrompt,
            roadmap = targetRoadmap,
        )
        touchProject(project.id)
        persistMessages(includeLiveProcess = true)

        val mode = _state.value.executionMode
        val modeInstruction = when {
            mode == ExecutionMode.PLAN -> """
<execution_mode name="PLAN">
You are running in PLAN mode.
Your objective: Inspect the project workspace and formulate a comprehensive, actionable execution plan without making any changes to files or code yet.
</execution_mode>
""".trimIndent()
            mode == ExecutionMode.BUILD -> """
<execution_mode name="BUILD">
You are running in BUILD mode.
Your objective: Directly understand the user request, plan internally, and immediately begin implementing all required changes end-to-end.
- Read relevant files, edit code, execute build/test commands, verify changes, and report progress.
- Once finished, summarize what was accomplished with full verification details.
</execution_mode>
""".trimIndent()
            mode == ExecutionMode.UNIFIED -> """
<execution_mode name="UNIFIED">
You are running in UNIFIED mode (Phase 1: Roadmap & Planning).
Your objective: Analyze the request and workspace, formulate the plan, and provide an interactive roadmap for user review and approval before any implementation begins.
</execution_mode>
""".trimIndent()
            else -> ""
        }

        val attachments = lastUserMsg?.attachments.orEmpty()
        val mentionedPaths = resolveMentionedWorkspacePaths(effectivePrompt, project)

        val runtimePrompt = buildString {
            appendLine(effectivePrompt)
            if (modeInstruction.isNotBlank() && !effectivePrompt.contains("<execution_mode")) {
                appendLine()
                appendLine(modeInstruction)
            }
            if (mentionedPaths.isNotEmpty() && !effectivePrompt.contains("<mentioned_files>")) {
                appendLine()
                appendLine("<mentioned_files>")
                appendLine("The user explicitly referenced these workspace files with @mentions. Inspect the referenced files before deciding what to change; they are context pointers, not permission to modify other files.")
                mentionedPaths.forEach { relativePath ->
                    appendLine("- " + guestRootFor(project) + "/" + relativePath)
                }
                appendLine("</mentioned_files>")
            }
            if (attachments.isNotEmpty() && !effectivePrompt.contains("<attached_files>")) {
                appendLine()
                appendLine("<attached_files>")
                attachments.forEach { attachment ->
                    appendLine("- ${attachment.displayName}: ${guestRootFor(project)}/${attachment.relativePath} (${attachment.mimeType})")
                }
                appendLine("These files were explicitly attached by the user. Inspect them only as needed for the request.")
                appendLine("</attached_files>")
            }
            val skillContext = skillManager.buildPromptContext(_state.value.agentKind)
            if (skillContext.isNotBlank() && !effectivePrompt.contains(skillContext.take(30))) {
                appendLine()
                appendLine(skillContext)
            }
        }

        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                getApplication<Application>(),
                Intent(getApplication<Application>(), RuntimeExecutionService::class.java).apply {
                    action = RuntimeExecutionService.ACTION_START
                    putExtra(RuntimeExecutionService.EXTRA_SESSION_ID, resumeSessionId)
                    putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                    putExtra(RuntimeExecutionService.EXTRA_AGENT_NAME, _state.value.agentKind.title)
                    putExtra(RuntimeExecutionService.EXTRA_DETAIL, "${_state.value.agentKind.title} is resuming task in ${project.name}")
                    putExtra(RuntimeExecutionService.EXTRA_ROUTE, "project/${project.slug}")
                },
            )
        }

        MobileHarnessApplication.appScope.launch {
            val workspace = withContext(Dispatchers.IO) {
                val root = projectWorkspaceRoot(project)
                skillManager.syncToWorkspace(root)
                root
            }
            val enrichedPrompt = withContext(Dispatchers.IO) {
                val context = contextEngine.buildPromptContext(
                    projectId = project.id,
                    workspace = workspace,
                    request = effectivePrompt,
                    mentionedPaths = mentionedPaths,
                    maxReferences = 12,
                )
                if (context.isBlank()) runtimePrompt else runtimePrompt + "\n\n" + context
            }
            val runtime = sessionBridge(project.id)
            val request = RuntimeRetryRequest(
                runtime = runtime,
                project = project,
                prompt = enrichedPrompt,
                history = cleanedMessages,
                provider = _state.value.provider,
            )
            sessionRuntimeRequests[resumeSessionId] = request
            sessionProjects[resumeSessionId] = project.id
            runningSessions[resumeSessionId] = project.id
            runCatching {
                runtime.startSession(
                    project.id,
                    project.slug,
                    project.kind,
                    enrichedPrompt,
                    cleanedMessages,
                    _state.value.provider,
                    resumeSessionId,
                )
            }.onFailure { error ->
                val failureReason = RuntimeFailureClassifier.friendlyNetworkErrorMessage(error.message.orEmpty())
                onRuntimeEvent(project.id, RuntimeEvent.SessionFailed(resumeSessionId, failureReason))
            }
        }
    }
    private fun restartSessionWithNewId(projectId: String, oldSessionId: String, request: RuntimeRetryRequest): String {
        val newSessionId = UUID.randomUUID().toString()
        ignoredSessionIds += oldSessionId
        if (ignoredSessionIds.size > 100) ignoredSessionIds.remove(ignoredSessionIds.first())
        agentSessionController.failSession(oldSessionId, "Superseded by retry")
        val scoped = sessionScopeFor(projectId)
        agentSessionController.startSession(
            sessionId = newSessionId,
            projectId = projectId,
            projectTitle = request.project.name,
            chatId = scoped.activeChatId.orEmpty(),
            prompt = scoped.currentTaskRequest ?: request.prompt,
            roadmap = scoped.activeRoadmap,
        )
        sessionRuntimeRequests[newSessionId] = request
        sessionProjects[newSessionId] = projectId
        runningSessions[newSessionId] = projectId
        sessionRuntimeRequests.remove(oldSessionId)
        sessionProjects.remove(oldSessionId)
        runningSessions.remove(oldSessionId)
        return newSessionId
    }

    private fun retryWithNextApiKey(projectId: String, event: RuntimeEvent.SessionFailed): Boolean {
        val scoped = sessionScopeFor(projectId)
        if (scoped.agentKind == AgentKind.ANTIGRAVITY) return false
        if (!scoped.isRunning || scoped.activeSessionId != event.sessionId) return false
        if (!isApiKeyFailure(event.reason)) return false
        val request = sessionRuntimeRequests[event.sessionId] ?: return false
        // v3.2.38 (J): one failover system — workspace rotation honors the
        // same per-provider Auto-switch toggle as home chat.
        if (!preferences.autoSwitchKeys(request.provider.kind.name)) return false
        val credentials = vault.credentials(request.provider.kind.name)
        val active = credentials.firstOrNull { it.isActive } ?: return false
        failedApiKeyIds += active.id
        // v3.2.38 (J): quota failures also mark the key resting in the
        // shared health store, so the catalog shows the truth.
        if (ApiKeyFailoverCoordinator.isQuotaFailure(event.reason)) {
            keyHealthStore.markExhausted(request.provider.kind.name, active.id)
        }
        val next = credentials.firstOrNull { it.id !in failedApiKeyIds } ?: return false
        if (!vault.activate(request.provider.kind.name, next.id)) return false
        updateSessionScoped(projectId) {
            it.copy(
                activeSessionId = null,
                runtimeRecoveryStatus = null,
                runtimeRecoveryAttempt = 0,
                runtimeRecoveryCanResume = false,
                activeApiKeyName = next.name,
                toastMessage = "${active.name} failed. Switched to ${next.name}.",
                liveProcess = it.liveProcess + ActivityItem("API key switched", "Using ${next.name}", true),
            )
        }
        val newSessionId = restartSessionWithNewId(projectId, event.sessionId, request)
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            request.runtime.startSession(
                request.project.id,
                request.project.slug,
                request.project.kind,
                request.prompt,
                request.history,
                request.provider,
                newSessionId,
            )
        }
        return true
    }

    private fun isApiKeyFailure(reason: String): Boolean {
        val value = reason.lowercase()
        return "api key" in value || "authentication" in value || "user not found" in value ||
            "http 401" in value || "http 403" in value || "http 429" in value ||
            "expired" in value || "quota" in value || "rate limit" in value
    }

    private fun sanitizeSessionFailedToast(reason: String): String? {
        val value = reason.lowercase()
        return when {
            "unauthenticated" in value || "401" in value || "invalid authentication credentials" in value ||
                "oauth 2 access token" in value || "token expired" in value ->
                "Google session expired (401). Reconnect your account in Settings → Google accounts."
            "user not found" in value ->
                "Account not found. Please sign in again."
            "api key" in value ->
                "API key authentication error. Check credentials in Settings."
            "out of credits" in value || "quota" in value || "429" in value ->
                "Usage quota limit reached. Auto-switching or check plan limits."
            "authentication" in value || "sign-in" in value ->
                "Authentication failed. Reconnect your account in Settings."
            else -> null
        }
    }

    private fun isAntigravityAuthFailure(reason: String): Boolean {
        val value = reason.lowercase()
        return "unauthenticated" in value ||
            "401" in value ||
            "invalid authentication credentials" in value ||
            "oauth 2 access token" in value ||
            "login cookie" in value ||
            "authentication required" in value ||
            "authentication failed" in value ||
            "auth expired" in value ||
            "sign-in" in value ||
            "not signed in" in value ||
            "token expired" in value
    }

    private fun isAntigravityQuotaFailure(reason: String): Boolean {
        val value = reason.lowercase()
        return "quota" in value ||
            "rate limit" in value ||
            "ratelimit" in value ||
            "resource_exhausted" in value ||
            "resource exhausted" in value ||
            "429" in value ||
            "out of credits" in value ||
            "credits" in value ||
            "limit exceeded" in value ||
            "exhausted" in value ||
            "too many requests" in value ||
            "60 minute" in value ||
            "minute limit" in value ||
            "hourly limit" in value ||
            "hour limit" in value ||
            "daily limit" in value ||
            "day limit" in value ||
            "weekly limit" in value ||
            "week limit" in value ||
            "usage limit" in value ||
            "plan limit" in value ||
            "account limit" in value ||
            "capacity" in value ||
            "overloaded" in value ||
            "quota_exceeded" in value ||
            "rate_limit_exceeded" in value ||
            "reached your limit" in value ||
            "hit your limit" in value ||
            "exceeded your limit" in value ||
            "exceeded your current quota" in value ||
            "insufficient quota" in value ||
            "free tier limit" in value ||
            "tokens per minute" in value ||
            "requests per minute" in value ||
            "requests per day" in value ||
            "requests per week" in value ||
            "billing" in value ||
            "credit balance" in value ||
            "quota limit" in value
    }

    private fun isAntigravityFailoverEligible(reason: String): Boolean {
        return isAntigravityQuotaFailure(reason) || isAntigravityAuthFailure(reason)
    }

    private fun retryAntigravityWithNextAccount(projectId: String, event: RuntimeEvent.SessionFailed): Boolean {
        val scoped = sessionScopeFor(projectId)
        if (scoped.agentKind != AgentKind.ANTIGRAVITY) return false
        if (!scoped.isRunning || scoped.activeSessionId != event.sessionId) return false
        if (!antigravityAuthController.isAutoRoundRobin()) return false
        if (!isAntigravityFailoverEligible(event.reason)) return false
        val request = sessionRuntimeRequests[event.sessionId] ?: return false

        val currentEmail = antigravityAuthController.state.value.accountEmail.orEmpty()
        val isAuthError = isAntigravityAuthFailure(event.reason)
        if (isAuthError) {
            antigravityAuthController.markCurrentAccountAuthExpired()
        } else {
            antigravityAuthController.markCurrentAccountQuotaExhausted()
        }
        val nextEmail = antigravityAuthController.switchToNextAccount() ?: return false

        val cleanedMessages = scoped.messages.let { msgs ->
            val lastUserIdx = msgs.indexOfLast { it.fromUser }
            if (lastUserIdx >= 0 && msgs.size > lastUserIdx + 1 && !msgs.last().fromUser) {
                msgs.dropLast(1)
            } else msgs
        }

        val reasonLabel = if (isAuthError) "session expired" else "usage limit reached"
        val failoverLabel = if (isAuthError) "auth expired failover" else "quota limit failover"
        updateSessionScoped(projectId) {
            it.copy(
                activeSessionId = null,
                runtimeRecoveryStatus = null,
                runtimeRecoveryAttempt = 0,
                runtimeRecoveryCanResume = false,
                messages = cleanedMessages,
                toastMessage = if (currentEmail.isNotBlank()) "Account $currentEmail $reasonLabel. Smartly switched to $nextEmail." else "Smartly switched to account $nextEmail.",
                liveProcess = it.liveProcess + ActivityItem("Google account switched", "Auto round-robin to $nextEmail ($failoverLabel)", true),
            )
        }
        val newSessionId = restartSessionWithNewId(projectId, event.sessionId, request)
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            request.runtime.startSession(
                request.project.id,
                request.project.slug,
                request.project.kind,
                request.prompt,
                request.history,
                request.provider,
                newSessionId,
            )
        }
        return true
    }

    // ---- Milestone 1: skills domain moved to SkillsCoordinator. ----
    fun refreshSkills() =
        skillsCoordinator.refreshSkills()

    fun checkSkillUpdates() =
        skillsCoordinator.checkSkillUpdates()

    fun updateSkill(name: String) =
        skillsCoordinator.updateSkill(name)

    fun searchSkills(query: String) =
        skillsCoordinator.searchSkills(query)

    fun importSkillFromGitHub(url: String) =
        skillsCoordinator.importSkillFromGitHub(url)

    fun scanGitHubSkill(url: String) =
        skillsCoordinator.scanGitHubSkill(url)

    fun installScannedSkill(useRelease: Boolean) =
        skillsCoordinator.installScannedSkill(useRelease)

    fun dismissSkillScan() =
        skillsCoordinator.dismissSkillScan()

    fun importSkillFromUri(uri: Uri) =
        skillsCoordinator.importSkillFromUri(uri)

    fun importSkillFromFolder(uri: Uri) =
        skillsCoordinator.importSkillFromFolder(uri)

    fun removeSkill(name: String) =
        skillsCoordinator.removeSkill(name)

    fun toggleSkill(name: String, enabled: Boolean) =
        skillsCoordinator.toggleSkill(name, enabled)

    // Milestone 1: restored — these were accidentally dropped by the
    // Security/MCP/Skills extraction (commit b4cd244); bodies verbatim
    // from the pre-extraction file.
    private fun touchProject(projectId: String) {
        val now = System.currentTimeMillis()
        _state.update { current ->
            val updatedProjects = current.projects.map { p ->
                if (p.id == projectId) p.copy(updatedAtMillis = now) else p
            }
            val active = if (current.activeProject?.id == projectId) current.activeProject?.copy(updatedAtMillis = now) else current.activeProject
            current.copy(projects = updatedProjects, activeProject = active)
        }
        preferences.saveProjects(_state.value.projects)
    }

    private fun persistMessages(includeLiveProcess: Boolean = true) {
        val current = _state.value
        val project = current.activeProject ?: return
        val chatId = current.activeChatId ?: return
        val liveItems = if (includeLiveProcess) current.liveProcess.filterNot(::isNoisyRuntimeItem) else emptyList()
        val messages = if (liveItems.isEmpty() && !current.liveThinking) {
            current.messages
        } else {
            val startedAt = current.workSegmentStartedAtMillis ?: current.taskStartedAtMillis ?: System.currentTimeMillis()
            // Crash fix (2026-10-13): never persist a second "interrupted-<session>"
            // row. persistMessages runs from many places; if the transcript already
            // holds one for this session, appending another gives the chat list two
            // items with the same key, and LazyColumn throws on duplicate keys.
            val interruptedId = "interrupted-${current.activeSessionId ?: chatId}"
            if (current.messages.any { it.id == interruptedId }) {
                current.messages
            } else {
            current.messages + ChatMessage(
                id = interruptedId,
                fromUser = false,
                text = "",
                workItems = liveItems.map { it.copy(isComplete = true) } + ActivityItem(
                    "Task interrupted",
                    "The agent process stopped before reporting completion. Continue this chat to resume its official session.",
                ),
                workedMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
            )
            }
        }
        transcriptWrites.trySend(TranscriptWrite(project.id, chatId, messages))
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            getApplication<Application>().startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            _state.update { state -> state.copy(toastMessage = "Could not open the browser. Copy the URL instead.") }
        }
    }

    private fun updateActiveChatTitle(prompt: String) {
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId ?: return
        val now = System.currentTimeMillis()
        val title = prompt.replace(Regex("\\s+"), " ").trim().let {
            if (it.length <= 42) it else it.take(39).trimEnd() + "…"
        }
        _state.update { current ->
            val chats = current.projectChats.map { chat ->
                if (chat.id == chatId) {
                    chat.copy(
                        title = if (chat.title == "New chat") title else chat.title,
                        updatedAtMillis = now,
                    )
                } else chat
            }.sortedByDescending { it.updatedAtMillis }
            current.copy(projectChats = chats)
        }
        preferences.saveProjectChats(project.id, _state.value.projectChats)
    }

    fun toggleAutoUpdateToolsAndSkills(enabled: Boolean) =
        skillsCoordinator.toggleAutoUpdateToolsAndSkills(enabled)

    /** Free Mode: persists the choice and narrows agent/provider pickers to free options. */
    fun setFreeModeEnabled(enabled: Boolean) {
        preferences.freeModeEnabled = enabled
        _state.update { it.copy(freeModeEnabled = enabled) }
    }

    fun autoUpdateToolsAndSkills(force: Boolean = false) =
        skillsCoordinator.autoUpdateToolsAndSkills(force)
    fun dismissInterruptedSession() {
        val sessionId = _state.value.interruptedSession?.sessionId ?: return
        agentSessionController.dismissInterruptedSession(sessionId)
        projectSessionStates.update { states ->
            val pid = _state.value.interruptedSession?.projectId
            if (pid != null) {
                states + (pid to (states[pid]?.copy(interruptedSession = null) ?: ProjectSessionState(pid)))
            } else states
        }
        _state.update { it.copy(interruptedSession = null) }
    }

    fun resumeInterruptedSession() {
        val session = _state.value.interruptedSession ?: return
        val project = _state.value.projects.firstOrNull { it.id == session.projectId } ?: return
        openProject(project)
        if (session.chatId.isNotBlank()) {
            switchChat(session.chatId)
        }
        projectSessionStates.update { states ->
            states + (session.projectId to (states[session.projectId]?.copy(interruptedSession = null) ?: ProjectSessionState(session.projectId)))
        }
        _state.update { it.copy(interruptedSession = null) }
        sessionManager.clearActiveSession(session.sessionId)

        val prompt = session.initialPrompt.ifBlank {
            _state.value.messages.lastOrNull { it.fromUser }?.text.orEmpty()
        }
        if (prompt.isNotBlank()) {
            resumeTaskExecution(prompt = prompt, roadmap = session.activeRoadmap)
        }
    }

    fun createWorkspaceCheckpoint(label: String): com.vayro.app.workspace.WorkspaceCheckpoint? =
        projectsCoordinator.createWorkspaceCheckpoint(label)

    fun rollbackWorkspaceCheckpoint(checkpointId: String): Boolean =
        projectsCoordinator.rollbackWorkspaceCheckpoint(checkpointId)

    fun listWorkspaceCheckpoints(): List<com.vayro.app.workspace.WorkspaceCheckpoint> =
        projectsCoordinator.listWorkspaceCheckpoints()

    fun backupAllWorkspaces(): Int =
        projectsCoordinator.backupAllWorkspaces()

    fun restoreAllWorkspaces(): Int =
        projectsCoordinator.restoreAllWorkspaces()

    fun reloadProjects() =
        projectsCoordinator.reloadProjects()

    fun runAutonomousVerification() {
        val project = _state.value.activeProject ?: return
        val workspace = projectWorkspaceRoot(project)
        val tool = com.vayro.app.agent.AutonomousLoopEngine.detectBuildTool(workspace)
        if (tool == null) {
            _state.update { it.copy(toastMessage = "No recognized build tool (Gradle/npm/Cargo/Go/Python) found in project") }
            return
        }
        _state.update { it.copy(toastMessage = "Running autonomous verification with ${tool.name}...") }
        terminalCoordinator.requestProjectTerminalCommand(tool.verificationCommand)
    }

    // ---- Milestone 1: visual-inspection state moved to VisualCoordinator. ----
    // ---- Milestone 1: visual-inspection domain moved to VisualCoordinator. ----
    fun recordVisualConsoleLog(entry: com.vayro.app.visual.ConsoleLogEntry) =
        visualCoordinator.recordVisualConsoleLog(entry)

    fun recordVisualNetworkLog(entry: com.vayro.app.visual.NetworkLogEntry) =
        visualCoordinator.recordVisualNetworkLog(entry)

    fun setInspectedDomElement(element: com.vayro.app.visual.DomElementInfo?) =
        visualCoordinator.setInspectedDomElement(element)

    fun clearVisualLogs() =
        visualCoordinator.clearVisualLogs()

    fun setViewportPreset(preset: com.vayro.app.visual.ViewportPreset) =
        visualCoordinator.setViewportPreset(preset)

    fun scanActiveLocalhostPorts(onComplete: (List<Int>) -> Unit = {}) =
        visualCoordinator.scanActiveLocalhostPorts(onComplete)

    fun scanLocalhostPortsWithHeuristics(
        customPorts: List<Int> = emptyList(),
        onComplete: (List<com.vayro.app.visual.LocalhostPortInfo>) -> Unit = {},
    ) = visualCoordinator.scanLocalhostPortsWithHeuristics(customPorts, onComplete)

    fun saveVisualSnapshot(
        bitmap: android.graphics.Bitmap,
        title: String,
        url: String,
        onSaved: (com.vayro.app.visual.VisualSnapshot) -> Unit = {},
    ) = visualCoordinator.saveVisualSnapshot(bitmap, title, url, onSaved)

    fun setBaselineComparisonSnapshot(snapshot: com.vayro.app.visual.VisualSnapshot) =
        visualCoordinator.setBaselineComparisonSnapshot(snapshot)

    fun setAfterComparisonSnapshot(snapshot: com.vayro.app.visual.VisualSnapshot) =
        visualCoordinator.setAfterComparisonSnapshot(snapshot)

    fun dispatchAiVisualBugReport(
        url: String,
        userNotes: String = "",
        snapshot: com.vayro.app.visual.VisualSnapshot? = null,
        onDispatched: () -> Unit = {},
    ) = visualCoordinator.dispatchAiVisualBugReport(url, userNotes, snapshot, onDispatched)

    fun attachVisualInspectionToAgent(url: String, note: String = "") =
        visualCoordinator.attachVisualInspectionToAgent(url, note)
    // ---- Milestone 1: App Factory domain moved to AppFactoryCoordinator. ----
    fun generateAppFactoryBlueprint(
        prompt: String,
        framework: com.vayro.app.factory.model.AppFramework = com.vayro.app.factory.model.AppFramework.ANDROID_COMPOSE,
        buildMode: com.vayro.app.factory.model.BuildMode = com.vayro.app.factory.model.BuildMode.ON_DEVICE,
        archetype: com.vayro.app.factory.blueprint.AppFactoryBlueprintEngine.Archetype? = null,
    ) = appFactoryCoordinator.generateAppFactoryBlueprint(prompt, framework, buildMode, archetype)

    fun scaffoldAppFactoryProject(
        targetDirectory: File? = null,
        blueprint: com.vayro.app.factory.model.AppBlueprint? = _state.value.appFactoryBlueprint
    ) = appFactoryCoordinator.scaffoldAppFactoryProject(targetDirectory, blueprint)

    fun buildAppFactoryApp(
        artifactType: com.vayro.app.factory.model.BuildArtifactType = com.vayro.app.factory.model.BuildArtifactType.APK_DEBUG,
        // Kodex speed default: build on GitHub CI when GitHub is connected
        // (keeps heavy Gradle work off the phone); on-device stays a choice.
        buildMode: com.vayro.app.factory.model.BuildMode = if (_state.value.githubAuthStatus == GitHubAuthStatus.CONNECTED) {
            com.vayro.app.factory.model.BuildMode.GITHUB_ACTIONS
        } else {
            com.vayro.app.factory.model.BuildMode.ON_DEVICE
        },
        targetDirectory: File? = null
    ) = appFactoryCoordinator.buildAppFactoryApp(artifactType, buildMode, targetDirectory)

    fun installBuiltApk(apkFile: File) =
        appFactoryCoordinator.installBuiltApk(apkFile)

    /**
     * Kodex delivery card: resolves an APK path the agent reported in chat
     * (workspace-relative, or a guest /workspace/<slug>/ path) to the real
     * file inside the active project's workspace.
     */
    private fun resolveReportedApk(path: String): File? {
        val project = _state.value.activeProject ?: return null
        val root = projectWorkspaceRoot(project)
        val cleaned = path.trim().trim('`', '"', '\'', ',', ')')
            .removePrefix("file://")
        val guestStripped = cleaned
            .removePrefix("/workspace/${project.slug}/")
            .removePrefix("workspace/${project.slug}/")
        val direct = listOf(File(cleaned), File(root, cleaned), File(root, guestStripped))
            .firstOrNull { it.isFile && it.extension.equals("apk", ignoreCase = true) }
        if (direct != null) return direct
        // U7a step 3: the agent often reports a bare file name (e.g. taken
        // from a markdown link label) or a path rooted in a nested project
        // folder. Search the project tree for the same file name, prefer
        // build-output locations, then the newest file.
        val fileName = cleaned.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        if (!fileName.endsWith(".apk", ignoreCase = true)) return null
        val matches = mutableListOf<File>()
        runCatching {
            root.walkTopDown()
                .maxDepth(10)
                .onEnter { dir -> dir.name != ".git" && dir.name != ".gradle" }
                .forEach { f ->
                    if (f.isFile && f.name.equals(fileName, ignoreCase = true)) matches += f
                }
        }
        return matches.maxWithOrNull(
            compareBy<File> { it.absolutePath.contains("/build/outputs/apk/") }
                .thenBy { it.lastModified() },
        )
    }

    /**
     * U7a/U7c: everything the Delivered card needs, resolved ONCE: the real
     * file (robust path resolution), its metadata (null when the archive
     * cannot be read — the card then falls back to name + size), and the
     * Preview target for this project (web entry point, else the delivered
     * app when it is installed, else none). Always returns — the card can
     * never hang on a loading state.
     */
    suspend fun loadDeliveredApk(path: String): DeliveredApkData = withContext(Dispatchers.IO) {
        val fileName = path.trim().trim('`', '"', '\'', ',', ')')
            .removePrefix("file://").substringAfterLast('/').ifBlank { "app.apk" }
        val resolved = runCatching { resolveReportedApk(path) }.getOrNull()
        val info = resolved?.let { runCatching { apkDeliveryInfo(it.absolutePath) }.getOrNull() }
        val sizeLabel = resolved?.let { String.format("%.1f MB", it.length() / 1_048_576.0) }
        var previewKind = ""
        var previewTarget: String? = null
        val project = _state.value.activeProject
        if (project != null) {
            val root = projectWorkspaceRoot(project)
            val webEntry = listOf(
                File(root, "index.html"),
                File(root, "assets/index.html"),
                File(root, "app/src/main/assets/index.html"),
            ).firstOrNull { it.isFile } ?: runCatching {
                root.walkTopDown().maxDepth(4)
                    .onEnter { dir -> dir.name != ".git" && dir.name != ".gradle" }
                    .filter { it.isFile && it.name.equals("index.html", ignoreCase = true) }
                    .minByOrNull { it.absolutePath.length }
            }.getOrNull()
            if (webEntry != null) {
                previewKind = "web"
                previewTarget = webEntry.absolutePath
            }
        }
        if (previewKind.isEmpty() && info != null && info.packageId.isNotBlank()) {
            val installed = runCatching {
                getApplication<android.app.Application>().packageManager
                    .getPackageInfo(info.packageId, 0)
            }.isSuccess
            if (installed) {
                previewKind = "app"
                previewTarget = info.packageId
            }
        }
        DeliveredApkData(
            resolvedPath = resolved?.absolutePath,
            info = info,
            fileName = fileName,
            sizeLabel = sizeLabel,
            previewKind = previewKind,
            previewTarget = previewTarget,
        )
    }

    /** U7c: runs the Delivered card's Preview action. */
    fun previewDelivered(kind: String, target: String) {
        when (kind) {
            "web" -> {
                // Reset first so re-previewing the same file still switches
                // the workspace to the Preview tab (its effect keys on the
                // URL changing).
                _state.update { it.copy(previewReady = false, previewUrl = null) }
                openPreviewUrl("file://$target")
            }
            "app" -> {
                val context = getApplication<android.app.Application>()
                val intent = runCatching {
                    context.packageManager.getLaunchIntentForPackage(target)
                }.getOrNull()
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }.onFailure {
                        _state.update { it.copy(toastMessage = "Could not open the app.") }
                    }
                } else {
                    _state.update { it.copy(toastMessage = "That app is not installed.") }
                }
            }
        }
    }

    fun installBuiltApkByPath(path: String) {
        val apk = resolveReportedApk(path) ?: run {
            _state.update { it.copy(toastMessage = "APK not found: $path") }
            return
        }
        installBuiltApk(apk)
    }

    fun shareBuiltApkByPath(path: String) {
        val apk = resolveReportedApk(path) ?: run {
            _state.update { it.copy(toastMessage = "APK not found: $path") }
            return
        }
        val context = getApplication<android.app.Application>()
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                apk,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, "Share ${apk.name}")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            _state.update { it.copy(toastMessage = "Could not share the APK.") }
        }
    }

    /** Reads real metadata (badging, size, stack, SHA-256) from a delivered APK. */
    suspend fun apkDeliveryInfo(path: String): ApkDeliveryInfo? = withContext(Dispatchers.IO) {
        val apk = resolveReportedApk(path) ?: return@withContext null
        runCatching {
            val context = getApplication<android.app.Application>()
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return@runCatching null
            val appInfo = info.applicationInfo
            if (appInfo != null) {
                appInfo.sourceDir = apk.absolutePath
                appInfo.publicSourceDir = apk.absolutePath
            }
            val appName = appInfo?.let { pm.getApplicationLabel(it).toString() }
                ?.takeIf { it.isNotBlank() } ?: apk.nameWithoutExtension
            val versionLabel = (info.versionName ?: "?") + " (" + info.longVersionCode + ")"
            val sizeLabel = String.format("%.1f MB", apk.length() / 1_048_576.0)
            var stack = "Android (Kotlin/Java)"
            runCatching {
                java.util.zip.ZipFile(apk).use { zip ->
                    val names = zip.entries().asSequence().map { it.name }.toList()
                    stack = when {
                        names.any { it.contains("libflutter.so") } -> "Flutter"
                        names.any { it == "assets/index.android.bundle" } -> "React Native"
                        names.any { it.startsWith("assets/www/") } -> "WebView (HTML/CSS/JS)"
                        else -> stack
                    }
                }
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            apk.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            ApkDeliveryInfo(appName, info.packageName ?: "", versionLabel, sizeLabel, stack, sha)
        }.getOrNull()
    }

    /** Saves a delivered APK into the device Downloads folder. */
    fun saveBuiltApkByPath(path: String) {
        val apk = resolveReportedApk(path) ?: run {
            _state.update { it.copy(toastMessage = "APK not found: $path") }
            return
        }
        val context = getApplication<android.app.Application>()
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, apk.name)
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val resolver = context.contentResolver
                        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: error("Downloads unavailable")
                        val copied = resolver.openOutputStream(uri)?.use { out ->
                            apk.inputStream().use { it.copyTo(out) }
                        }
                        if (copied == null) error("Could not open Downloads")
                        values.clear()
                        values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } else {
                        val dir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        )
                        dir.mkdirs()
                        apk.copyTo(java.io.File(dir, apk.name), overwrite = true)
                    }
                    true
                }.getOrDefault(false)
            }
            _state.update {
                it.copy(
                    toastMessage = if (saved) {
                        "Saved ${apk.name} to Downloads"
                    } else {
                        "Could not save the APK to Downloads."
                    }
                )
            }
        }
    }

    fun generateReleaseKeystore(
        targetDirectory: File? = null,
        alias: String = "releaseKey",
        pass: String
    ) = appFactoryCoordinator.generateReleaseKeystore(targetDirectory, alias, pass)

    fun bumpAppVersion(
        targetDirectory: File? = null,
        bumpType: com.vayro.app.factory.model.VersionBumpType = com.vayro.app.factory.model.VersionBumpType.PATCH
    ) = appFactoryCoordinator.bumpAppVersion(targetDirectory, bumpType)

    fun preparePlayStoreListing(
        targetDirectory: File? = null,
        blueprint: com.vayro.app.factory.model.AppBlueprint? = _state.value.appFactoryBlueprint
    ) = appFactoryCoordinator.preparePlayStoreListing(targetDirectory, blueprint)
    // ---- Milestone 1: Git Studio moved to GitCoordinator. ----
    fun refreshGitStudio(targetDirectory: File? = null) =
        gitCoordinator.refreshGitStudio(targetDirectory)

    fun gitStageFile(path: String, targetDirectory: File? = null) =
        gitCoordinator.gitStageFile(path, targetDirectory)

    fun gitStageAll(targetDirectory: File? = null) =
        gitCoordinator.gitStageAll(targetDirectory)

    fun gitUnstageFile(path: String, targetDirectory: File? = null) =
        gitCoordinator.gitUnstageFile(path, targetDirectory)

    fun gitUnstageAll(targetDirectory: File? = null) =
        gitCoordinator.gitUnstageAll(targetDirectory)

    fun gitDiscardFile(path: String, targetDirectory: File? = null) =
        gitCoordinator.gitDiscardFile(path, targetDirectory)

    fun gitDiscardAll(targetDirectory: File? = null) =
        gitCoordinator.gitDiscardAll(targetDirectory)

    fun gitCommitDetailed(message: String, amend: Boolean = false, signOff: Boolean = false, targetDirectory: File? = null) =
        gitCoordinator.gitCommitDetailed(message, amend, signOff, targetDirectory)

    fun gitCreateBranch(name: String, targetDirectory: File? = null) =
        gitCoordinator.gitCreateBranch(name, targetDirectory)

    fun gitCheckoutBranch(name: String, targetDirectory: File? = null) =
        gitCoordinator.gitCheckoutBranch(name, targetDirectory)

    fun gitDeleteBranch(name: String, targetDirectory: File? = null) =
        gitCoordinator.gitDeleteBranch(name, targetDirectory)

    fun gitMergeBranch(branch: String, targetDirectory: File? = null) =
        gitCoordinator.gitMergeBranch(branch, targetDirectory)

    fun gitRebaseBranch(upstream: String, targetDirectory: File? = null) =
        gitCoordinator.gitRebaseBranch(upstream, targetDirectory)

    fun gitResetBranch(hash: String, mode: com.vayro.app.git.model.GitResetMode, targetDirectory: File? = null) =
        gitCoordinator.gitResetBranch(hash, mode, targetDirectory)
    // ---- Milestone 1: GitHub issues/PRs moved to GitCoordinator. ----
    fun createGitHubIssue(title: String, body: String = "") =
        gitCoordinator.createGitHubIssue(title, body)

    fun mergeGitHubPullRequest(prNumber: Int, method: com.vayro.app.network.PullRequestMergeMethod = com.vayro.app.network.PullRequestMergeMethod.SQUASH) =
        gitCoordinator.mergeGitHubPullRequest(prNumber, method)
    private fun resolveGitHubToken(): String {
        return try {
            val (exit, output) = gitHubAuthCoordinator.runGitHubCli(listOf("auth", "token"))
            output.trim().takeIf { exit == 0 && it.isNotBlank() }.orEmpty()
        } catch (e: Exception) {
            AppCrashLogger.log("MainViewModel.resolveGitHubToken: ${e.message}")
            ""
        }
    }

    /**
     * Sends a prompt through the real agent pipeline and suspends until that
     * request genuinely ends (completed, failed, or timed out). This replaces the
     * old fire-and-forget callback: the returned [OperationResult] completes only
     * when the agent request ends, and it is FAILED unless the run verifiably
     * completed.
     */
    private suspend fun runAgentPromptAwaitingResult(prompt: String): OperationResult {
        val startedAt = System.currentTimeMillis()
        if (_state.value.activeProject == null) {
            return OperationResult.failure(
                error = "No active project for the autonomous agent run.",
                startedAt = startedAt,
            )
        }
        if (_state.value.isRunning) {
            return OperationResult.failure(
                error = "Another agent task is already running; refusing to overlap autonomous runs.",
                startedAt = startedAt,
            )
        }
        sendPrompt(prompt)
        // sendPrompt() silently no-ops when its guards fail; verify the task started.
        delay(1500)
        val launched = _state.value.isRunning || _state.value.currentTaskRequest != null
        if (!launched) {
            return OperationResult.failure(
                error = "Agent task did not start (agent sign-in or provider key missing?).",
                startedAt = startedAt,
            )
        }
        val timeoutMs = 30L * 60L * 1000L
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snapshot = _state.value
            val finishedAt = snapshot.taskFinishedAtMillis
            if (!snapshot.isRunning && finishedAt != null && finishedAt >= startedAt) break
            val approval = snapshot.pendingApproval
            if (approval != null) {
                return OperationResult.failure(
                    error = "Autonomous run paused for user approval (tool: ${approval.toolName}). " +
                        "Autonomous mode cannot approve on its own.",
                    startedAt = startedAt,
                )
            }
            delay(2000)
        }
        val end = _state.value
        val finishedAt = end.taskFinishedAtMillis
        if (end.isRunning || finishedAt == null || finishedAt < startedAt) {
            return OperationResult.failure(
                error = "Agent task timed out after ${timeoutMs / 60000} minutes without completing.",
                startedAt = startedAt,
            )
        }
        val headline = end.activity.firstOrNull()
        return if (headline != null && headline.title.equals("Task completed", ignoreCase = true)) {
            OperationResult.success(
                verificationEvidence = "Agent task finished (${headline.detail.ifBlank { "completed" }})",
                startedAt = startedAt,
            )
        } else {
            OperationResult.failure(
                error = "Agent task ended without success: ${headline?.title ?: "unknown"} " +
                    (headline?.detail?.take(300) ?: ""),
                startedAt = startedAt,
            )
        }
    }

    /**
     * Runs a REAL Gradle build + unit tests against the workspace using the
     * installed Linux runtime. Returns the genuine exit code and captured logs —
     * never a canned success.
     */
    private suspend fun runRealGradleBuildAndTest(
        workingDir: File,
        guestWorkspacePath: String,
    ): OperationResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        if (!installer.isInstalled()) {
            return@withContext OperationResult.notConfigured(
                "Linux runtime is not installed; cannot run Gradle build/test."
            )
        }
        if (!installer.isStackInstalled(DevStack.ANDROID)) {
            return@withContext OperationResult.notConfigured(
                "Android build tools are not installed (Settings -> Development stacks); cannot run Gradle build/test."
            )
        }
        val gradleRoot = findAndroidGradleProjectRoot(workingDir)
            ?: return@withContext OperationResult.failure(
                error = "No Android Gradle project found under ${workingDir.path}; cannot validate build/test.",
                startedAt = startedAt,
            )
        val installed = installer.installedRuntime()
        val outputFile = File(
            getApplication<Application>().cacheDir,
            "workflow-build-${System.nanoTime()}.log"
        )
        try {
            val command = "gradle --init-script /root/.gradle/init.d/pocketdev-android.gradle " +
                "-Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 " +
                "--no-daemon assembleDebug testDebugUnitTest --console=plain"
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                gradleRoot,
                emptyMap(),
                listOf("/usr/bin/bash", "-lc", command),
                guestWorkspacePath = guestWorkspacePath,
                outputFile = outputFile,
            )
            val exitCode = process.waitFor()
            val rawOutput = (process as? com.vayro.app.runtime.NativeSpawnProcess)
                ?.outputFile?.readTailText(MAX_PROCESS_OUTPUT_BYTES).orEmpty()
            val tail = truncateHeadAndTail(rawOutput, maxHeadLines = 10, maxTailLines = 40, maxTotalLines = 60)
            val logs = listOf(
                "Command: $command",
                "Working dir: ${gradleRoot.path}",
                "Exit code: $exitCode",
                tail,
            )
            return@withContext if (exitCode == 0) {
                OperationResult.success(
                    exitCode = exitCode,
                    logs = logs,
                    verificationEvidence = "gradle assembleDebug testDebugUnitTest exit=0 in ${gradleRoot.path}",
                    startedAt = startedAt,
                )
            } else {
                OperationResult.failure(
                    error = "Gradle build/test failed (exit code $exitCode). " +
                        tail.trim().takeLast(2000).ifBlank { "See logs." },
                    exitCode = exitCode,
                    logs = logs,
                    startedAt = startedAt,
                )
            }
        } catch (e: Exception) {
            return@withContext OperationResult.failure(
                error = "Gradle invocation crashed: ${e.message}",
                startedAt = startedAt,
            )
        } finally {
            runCatching { outputFile.delete() }
        }
    }

    fun startAutonomousIssueWorkflow(issue: com.vayro.app.network.GitHubIssue) {
        val project = _state.value.activeProject ?: return
        val dir = projectWorkspaceRoot(project)
        val repo = _state.value.gitConnectedRepo ?: project.name
        val guestPath = guestRootFor(project)

        viewModelScope.launch {
            // Explicit, validated token retrieval: blank token fails the workflow fast (engine-side).
            val token = withContext(Dispatchers.IO) { resolveGitHubToken() }
            val result = gitWorkflowEngine.runAutonomousWorkflow(
                issue = issue,
                token = token,
                repoFullName = repo,
                workingDir = dir,
                agentExecutor = { prompt -> runAgentPromptAwaitingResult(prompt) },
                buildAndTestValidator = { gradleDir -> runRealGradleBuildAndTest(gradleDir, guestPath) },
            )
            if (!result.isSuccess) {
                _state.update {
                    it.copy(toastMessage = "Autonomous workflow failed: ${result.error ?: result.status}")
                }
            }
            refreshGitStudio(dir)
        }
    }

    /**
     * Installs a marketplace item through the real [MarketplaceInstallManager]:
     * the package is materialized on disk and recorded durably. Failures are
     * reported honestly instead of a toast-only fake install (Milestone 0).
     */
    // ---- Milestone 1: delegated to domain coordinators (public API unchanged). ----
    fun installMarketplaceItem(item: com.vayro.app.marketplace.MarketplaceItem) =
        marketplaceCoordinator.installMarketplaceItem(item)

    fun publishMarketplaceItem(item: com.vayro.app.marketplace.MarketplaceItem) =
        marketplaceCoordinator.publishMarketplaceItem(item)

    fun uninstallMarketplaceItem(item: com.vayro.app.marketplace.MarketplaceItem) =
        marketplaceCoordinator.uninstallMarketplaceItem(item)

    fun checkMarketplaceUpdates() =
        marketplaceCoordinator.checkMarketplaceUpdates()

    fun updateMarketplaceItem(item: com.vayro.app.marketplace.MarketplaceItem) =
        marketplaceCoordinator.updateMarketplaceItem(item)

    fun planTeamProject(goal: String) =
        teamCoordinator.planTeamProject(goal)

    fun cancelTeamSession() =
        teamCoordinator.cancelTeamSession()

    override fun onCleared() {
        super.onCleared()
        AppCrashLogger.log("MainViewModel.onCleared: cleaning up running processes and sessions")
        terminalCoordinator.onCleared()
        gitHubAuthCoordinator.onCleared()
        teamOrchestrator.shutdown()
        if (_state.value.isRunning) {
            _state.value.activeSessionId?.let { sessionManager.recordSessionFailed(it, "App process terminated unexpectedly") }
        }
        projectSessionStates.value.forEach { (_, slice) ->
            if (slice.isRunning) {
                slice.activeSessionId?.let { sessionManager.recordSessionFailed(it, "App process terminated unexpectedly") }
            }
        }
    }

    companion object {
        /** v3.2.37 (F2): home chat answers as a chat assistant, never as a coding agent. */
        const val HOME_CHAT_SYSTEM_PROMPT: String =
            "You are Kodex, the friendly assistant inside the Kodex app. This is a normal chat: " +
                "answer helpfully and concisely, in the same language the user writes in. " +
                "Do not claim to have edited files, run commands or built anything \u2014 " +
                "coding work happens in the Work workspace, not here."

        private const val MAX_VISIBLE_WORKSPACE_ENTRIES = 2_000
        private const val MAX_ATTACHMENTS_PER_MESSAGE = 5
        private const val MAX_PROCESS_OUTPUT_BYTES = 512 * 1024
        private const val MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L
        private const val MAX_EDITABLE_FILE_CHARS = 512_000
        private const val MAX_IMPORTED_PROJECT_BYTES = 8L * 1024L * 1024L * 1024L
        private const val MAX_IMPORTED_ZIP_ENTRIES = 100_000
        private const val LEGACY_GITHUB_TOKEN_KEY = "GITHUB_APP"
        private const val GITHUB_DEVICE_URL = "https://github.com/login/device"
        private val GITHUB_DEVICE_CODE = Regex("\\b[A-Z0-9]{4}-[A-Z0-9]{4}\\b")
        private const val TEST_PROVIDER_DEFAULTS_VERSION = 1
        private const val TEST_OPENROUTER_BASE_URL = "https://openrouter.ai/api"
        private const val TEST_OPENROUTER_MODEL = "stealth/ox-alpha"
    }
}
