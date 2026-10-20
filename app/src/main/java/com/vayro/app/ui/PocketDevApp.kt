package com.vayro.app.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.vayro.app.BuildConfig
import com.vayro.app.R
import com.vayro.app.data.AppPreferences
import com.vayro.app.livecall.LiveCallController
import com.vayro.app.livecall.LiveCallUiState
import com.vayro.app.ui.navigation.OverlayScreen
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import com.vayro.app.marketplace.MarketplaceCategory
import com.vayro.app.marketplace.SearchSort
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Speed
import com.vayro.app.ui.components.YouTubeBrandRed
import com.vayro.app.ui.components.YouTubeVideoDialog
import com.vayro.app.util.YouTubeUrlValidator
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayro.app.auth.model.AppUser
import com.vayro.app.auth.model.AuthState
import com.vayro.app.auth.ui.AuthScreen
import com.vayro.app.auth.ui.AuthViewModel
import com.vayro.app.auth.ui.EmailVerificationBanner
import com.vayro.app.auth.ui.EmailVerificationDialog
import com.vayro.app.model.ActivityItem
import com.vayro.app.model.AgentKind
import com.vayro.app.model.ChangeItem
import com.vayro.app.chat.ChatLinkExtractor
import com.vayro.app.model.ChatMessage
import com.vayro.app.model.ChatAttachment
import com.vayro.app.model.ExecutionMode
import com.vayro.app.model.ActiveRoadmap
import com.vayro.app.model.RoadmapStep
import com.vayro.app.model.RoadmapComment
import com.vayro.app.model.ScratchpadItem
import com.vayro.app.model.StepStatus
import com.vayro.app.model.TaskStatus
import com.vayro.app.model.DevStack
import com.vayro.app.model.DEEPSEEK_HARNESS_PROVIDERS
import com.vayro.app.model.DSH_PROTOCOL_PROVIDERS
import com.vayro.app.model.DiffLine
import com.vayro.app.model.DiffLineType
import com.vayro.app.model.Project
import com.vayro.app.model.ProjectKind
import com.vayro.app.model.ProjectChat
import com.vayro.app.model.ProviderKind
import com.vayro.app.model.ProviderProfile
import com.vayro.app.model.inferredDshApiForUrl
import com.vayro.app.model.agentsForPicker
import com.vayro.app.model.providersForAgent
import com.vayro.app.model.ToolRequest
import com.vayro.app.model.WorkspaceEntry
import com.vayro.app.model.projectSlug
import com.vayro.app.runtime.RuntimeExecutionService
import com.vayro.app.runtime.NotificationCoordinator
import com.vayro.app.runtime.RuntimeSetupService
import com.vayro.app.runtime.supportsArm64Runtime
import com.vayro.app.runtime.AntigravityAuthStatus
import com.vayro.app.runtime.BackgroundProtectionHelper
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.vayro.app.network.ConnectionValidation
import com.vayro.app.network.DiscoveredModel
import com.vayro.app.network.ModelDiscoveryResult
import com.vayro.app.network.GitHubRepository
import com.vayro.app.ui.theme.PocketBlue
import com.vayro.app.ui.theme.PocketGreen
import com.vayro.app.ui.theme.PocketOrange
import com.vayro.app.ui.voice.VoiceInputBar
import com.vayro.app.ui.voice.VoiceRecognizer
import java.io.ByteArrayInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


import com.vayro.app.ui.theme.AppThemeMode
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.documentfile.provider.DocumentFile

/**
 * v3.2.40 (Scope E): process-lifetime sentinel. True once this process
 * has composed the shell — a genuinely fresh process (cold start)
 * starts false, so a rememberSaveable-restored sub-screen can be
 * dropped back to the chat home exactly once per process; warm
 * resumes in the same process see it already true and are untouched.
 */
private var shellComposedInThisProcess = false

// Kodex branding is rendered in the shared setup headers.
private enum class RootScreen(val label: String, val icon: ImageVector) {
    CHAT_HOME("Kodex", Icons.AutoMirrored.Filled.Chat),
    CHATS("Chats", Icons.AutoMirrored.Filled.Chat),
    PROJECTS("Projects", Icons.Default.Folder),
    CODE("Code", Icons.Default.Code),
    AGENT("Providers & Agents", Icons.Default.SmartToy),
    SKILLS("Skills", Icons.Default.Extension),
    CONNECTORS("Connectors", Icons.Default.Hub),
    MARKETPLACE("Marketplace", Icons.Default.Storefront),
    SETTINGS("Settings", Icons.Default.Settings),
    LEGACY_SETTINGS("All settings", Icons.Default.Settings),
    CAPABILITIES("Capabilities", Icons.Default.Tune),
    VOICE_SETTINGS("Voice", Icons.Default.Mic),
    PROFILE("Profile", Icons.Default.Person),
    LICENSE_PAGE("License", Icons.Default.Lock),
    NOTIFICATIONS_PAGE("Notifications", Icons.Default.Notifications),
    SECTION_PAGE("Settings", Icons.Default.Settings),
}
private enum class WorkspaceTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    CHAT("Chat", Icons.Default.AutoAwesome),
    FILES("Files", Icons.Default.Folder),
    TERMINAL("Terminal", Icons.Default.Terminal),
    CHANGES("Changes", Icons.Default.Code),
    PREVIEW("Browser", Icons.Default.Language),
}

@Composable
fun PocketDevApp(
    viewModel: MainViewModel = viewModel(),
    authViewModel: AuthViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val marketplaceInstalledIds by viewModel.marketplaceInstalledIds.collectAsStateWithLifecycle()
    val marketplaceUpdatesAvailable by viewModel.marketplaceUpdatesAvailable.collectAsStateWithLifecycle()
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    val detectedPorts by viewModel.detectedLocalhostPorts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Kodex: quietly check for a newer release at app start and post a
    // notification when one exists (throttled inside UpdateNotifier).
    // Same throttled pattern for runtime component updates: the result
    // drives the in-app "Update available" banner on the root screens.
    LaunchedEffect(Unit) {
        com.vayro.app.update.UpdateNotifier.checkAndNotify(context)
        com.vayro.app.update.ComponentUpdateNotifier.check(context)
        viewModel.autoUpdateToolsAndSkills()
    }
    val projectsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    // v3.2.39 (Scope 2): the root screens' list + filter states live HERE,
    // in the root composable that never leaves composition, as plain
    // remember. They used to be rememberSaveable INSIDE each screen — and
    // on the user's phone the Settings list still jumped to the top after
    // a sub-page round trip (About → Back). A state held at this level
    // cannot be dropped by any branch switch, host swap or registry
    // restore below it: the screens just read what they are handed.
    val settingsListState = remember { LazyListState() }
    val settingsFocusListState = remember { LazyListState() }
    val chatsListState = remember { LazyListState() }
    val chatsQueryState = remember { mutableStateOf("") }
    val chatsFilterPinnedState = remember { mutableStateOf(false) }
    val providersListState = remember { LazyListState() }
    val skillsListState = remember { LazyListState() }
    val mcpListState = remember { LazyListState() }
    val skillsQueryState = remember { mutableStateOf("") }
    val marketplaceListState = remember { LazyListState() }
    val marketplaceQueryState = remember { mutableStateOf("") }
    val marketplaceCategoryState = remember { mutableStateOf<MarketplaceCategory?>(null) }
    val marketplaceInstalledOnlyState = remember { mutableStateOf(false) }
    val marketplaceSortState = remember { mutableStateOf(SearchSort.RELEVANCE) }
    var guestDismissedAuth by rememberSaveable { mutableStateOf(false) }
    var showAuthModal by rememberSaveable { mutableStateOf(false) }
    // Milestone 1: overlay navigation separated into PocketDevNavigator.
    val navigator = remember { com.vayro.app.ui.navigation.PocketDevNavigator() }
    val appPreferences = remember { AppPreferences(context) }
    // One-time per-studio onboarding: first open of a studio shows its
    // explainer; Skip/Exit marks it seen so it never shows again.
    var pendingStudioOnboarding by remember { mutableStateOf<OverlayScreen?>(null) }
    val openStudio: (OverlayScreen) -> Unit = { studio ->
        val seen = when (studio) {
            OverlayScreen.APP_FACTORY -> appPreferences.appFactoryOnboardingSeen
            OverlayScreen.TEAM_STUDIO -> appPreferences.teamStudioOnboardingSeen
            OverlayScreen.CLOUD_HYBRID -> appPreferences.cloudHybridOnboardingSeen
            else -> true
        }
        if (seen) {
            when (studio) {
                OverlayScreen.APP_FACTORY -> navigator.openAppFactory()
                OverlayScreen.TEAM_STUDIO -> navigator.openTeamStudio()
                OverlayScreen.CLOUD_HYBRID -> navigator.openCloudHybrid()
                else -> Unit
            }
        } else {
            pendingStudioOnboarding = studio
        }
    }
    val markStudioOnboardingSeen: (OverlayScreen) -> Unit = { studio ->
        when (studio) {
            OverlayScreen.APP_FACTORY -> appPreferences.appFactoryOnboardingSeen = true
            OverlayScreen.TEAM_STUDIO -> appPreferences.teamStudioOnboardingSeen = true
            OverlayScreen.CLOUD_HYBRID -> appPreferences.cloudHybridOnboardingSeen = true
            else -> Unit
        }
    }
    pendingStudioOnboarding?.let { studio ->
        StudioOnboardingDialog(
            studio = studio,
            onProceed = {
                markStudioOnboardingSeen(studio)
                pendingStudioOnboarding = null
                openStudio(studio)
            },
            onExit = {
                markStudioOnboardingSeen(studio)
                pendingStudioOnboarding = null
            },
        )
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.consumeToast()
        }
    }

    // v3.2.34 (U1): the app-update dialog lives at the app ROOT. It used
    // to be trapped inside ProjectsScreen, which the Claude-style shell
    // never shows on the normal path (the app lands on CHAT_HOME), so a
    // found update could never be installed from anywhere else. Hoisted
    // here, it opens over ANY screen; the Projects banner, the chat-home
    // bell, Settings -> App updates and the update notification all
    // point at this one dialog. A manual check bumps the prompt nonce,
    // which re-opens the dialog even for an already-known update.
    var showAppUpdateDialog by rememberSaveable { mutableStateOf(false) }
    val appUpdatePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.installAppUpdate() }
    LaunchedEffect(state.appUpdate?.versionCode, state.appUpdatePromptNonce) {
        if (state.appUpdate != null) showAppUpdateDialog = true
    }
    val onShowAppUpdate: () -> Unit = { showAppUpdateDialog = true }

    val showAuthScreen = showAuthModal || (!guestDismissedAuth && authState is AuthState.Unauthenticated && state.startupStage != StartupStage.CHECKING)

    if (showAuthScreen) {
        AuthScreen(
            viewModel = authViewModel,
            onContinueAsGuest = {
                guestDismissedAuth = true
                showAuthModal = false
            },
        )
        return
    }

    if (navigator.showAppFactoryScreen) {
        BackHandler(onBack = { navigator.closeOverlay() })
        com.vayro.app.factory.ui.AppFactoryScreen(
            currentBlueprint = state.appFactoryBlueprint,
            isGeneratingBlueprint = state.appFactoryGeneratingBlueprint,
            isScaffolding = state.appFactoryScaffolding,
            scaffoldedFiles = state.appFactoryScaffoldFiles,
            isBuilding = state.appFactoryBuilding,
            buildResult = state.appFactoryBuildResult,
            buildLogs = state.appFactoryLogs,
            keystoreConfig = state.appFactoryKeystoreConfig,
            storeListingBundle = state.appFactoryStoreBundle,
            statusMessage = state.appFactoryMessage,
            githubConnected = state.githubAuthStatus == GitHubAuthStatus.CONNECTED,
            onBack = { navigator.closeOverlay() },
            onGenerateBlueprint = { prompt, fw, mode, arch ->
                viewModel.generateAppFactoryBlueprint(prompt, fw, mode, arch)
            },
            onScaffoldProject = {
                viewModel.scaffoldAppFactoryProject()
            },
            onRunBuild = { art, mode ->
                viewModel.buildAppFactoryApp(art, mode)
            },
            onInstallApk = { apk ->
                viewModel.installBuiltApk(apk)
            },
            onGenerateKeystore = { alias, pass ->
                viewModel.generateReleaseKeystore(alias = alias, pass = pass)
            },
            onBumpVersion = { type ->
                viewModel.bumpAppVersion(bumpType = type)
            },
            onPreparePlayStore = {
                viewModel.preparePlayStoreListing()
            }
        )
        return
    }

    if (navigator.showTeamStudioScreen) {
        BackHandler(onBack = { navigator.closeOverlay() })
        com.vayro.app.agent.team.ui.TeamStudioScreen(
            orchestrator = viewModel.teamOrchestrator,
            onClose = { navigator.closeOverlay() }
        )
        return
    }

    if (navigator.showCloudHybridScreen) {
        BackHandler(onBack = { navigator.closeOverlay() })
        com.vayro.app.actions.ui.GitHubActionsBuildScreen(
            coordinator = viewModel.githubActionsCoordinator,
            onClose = { navigator.closeOverlay() },
            onBuildLocal = {
                navigator.closeOverlay()
                navigator.openAppFactory()
            },
        )
        return
    }

    if (navigator.showGitHubStudioScreen) {
        BackHandler(onBack = { navigator.closeOverlay() })
        LaunchedEffect(Unit) { viewModel.refreshGitStudio() }
        com.vayro.app.git.ui.GitHubStudioScreen(
            statusInfo = state.gitStatusInfo,
            branches = state.gitBranches,
            commits = state.gitCommits,
            issues = state.gitIssues,
            pullRequests = state.gitPullRequests,
            workflowRuns = state.gitWorkflowRuns,
            releases = state.gitReleases,
            workflowState = state.gitWorkflowState,
            connectedRepo = state.gitConnectedRepo,
            rawDiffOutput = state.gitRawDiff,
            onBack = { navigator.closeOverlay() },
            onRefresh = { viewModel.refreshGitStudio() },
            onStageFile = { path -> viewModel.gitStageFile(path) },
            onStageAll = { viewModel.gitStageAll() },
            onUnstageFile = { path -> viewModel.gitUnstageFile(path) },
            onUnstageAll = { viewModel.gitUnstageAll() },
            onDiscardFile = { path -> viewModel.gitDiscardFile(path) },
            onDiscardAll = { viewModel.gitDiscardAll() },
            onCommit = { message, amend, signOff -> viewModel.gitCommitDetailed(message, amend, signOff) },
            onCreateBranch = { branch -> viewModel.gitCreateBranch(branch) },
            onCheckoutBranch = { branch -> viewModel.gitCheckoutBranch(branch) },
            onDeleteBranch = { branch -> viewModel.gitDeleteBranch(branch) },
            onMergeBranch = { branch -> viewModel.gitMergeBranch(branch) },
            onRebaseBranch = { upstream -> viewModel.gitRebaseBranch(upstream) },
            onPush = { viewModel.gitPush() },
            onPull = { viewModel.gitPull() },
            onResetToCommit = { hash, mode -> viewModel.gitResetBranch(hash, mode) },
            onCreateIssue = { title, body -> viewModel.createGitHubIssue(title, body) },
            onMergePullRequest = { number, method -> viewModel.mergeGitHubPullRequest(number, method) },
            onStartAutonomousWorkflow = { issue -> viewModel.startAutonomousIssueWorkflow(issue) },
        )
        return
    }

    if (navigator.showMarketplaceScreen) {
        BackHandler(onBack = { navigator.closeOverlay() })
        MarketplaceScreen(
            onBack = { navigator.closeOverlay() },
            listState = marketplaceListState,
            searchQueryState = marketplaceQueryState,
            categoryState = marketplaceCategoryState,
            installedOnlyState = marketplaceInstalledOnlyState,
            sortState = marketplaceSortState,
            onInstallItem = { item -> viewModel.installMarketplaceItem(item) },
            onUninstallItem = { item -> viewModel.uninstallMarketplaceItem(item) },
            onPublishItem = { item -> viewModel.publishMarketplaceItem(item) },
            initiallyInstalledIds = marketplaceInstalledIds,
            updatesAvailable = marketplaceUpdatesAvailable,
            onUpdateItem = { item -> viewModel.updateMarketplaceItem(item) },
            onScreenOpened = { viewModel.checkMarketplaceUpdates() },
        )
        return
    }

    // Chat-home hand-off (v3.2.32): a prompt/attachments sent from the
    // Claude chat home create the quick project first; once the workspace
    // is visible they are delivered through the normal send path.
    var pendingHomePrompt by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingHomeAttachments by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingHomeVoiceCall by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.workspaceVisible, state.activeProject?.id) {
        if (state.workspaceVisible && state.activeProject != null &&
            state.projects.firstOrNull()?.id == state.activeProject?.id
        ) {
            if (pendingHomeAttachments.isNotEmpty()) {
                viewModel.addChatAttachments(pendingHomeAttachments)
                pendingHomeAttachments = emptyList()
            }
            if (pendingHomeVoiceCall) {
                pendingHomeVoiceCall = false
                viewModel.requestVoiceCall()
            }
            pendingHomePrompt?.let { homePrompt ->
                pendingHomePrompt = null
                viewModel.sendPrompt(homePrompt)
            }
        }
    }

    val pushState = remember { GitPushUiState() }
    var pendingPushProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    val startGitPush: () -> Unit = {
        val active = state.activeProject
        if (active != null) {
            pushState.beginPush(active.name)
            viewModel.gitPush { ok, msg -> pushState.finish(ok, msg) }
        } else {
            pushState.showPicker()
        }
    }
    LaunchedEffect(state.activeProject?.id) {
        val pid = pendingPushProjectId
        if (pid != null && state.activeProject?.id == pid) {
            pendingPushProjectId = null
            viewModel.gitPush { ok, msg -> pushState.finish(ok, msg) }
        }
    }

    when {
        state.startupStage == StartupStage.CHECKING -> StartupLoadingScreen(
            state = state,
            themeMode = state.themeMode,
            onToggleTheme = viewModel::toggleTheme,
        )
        !state.backgroundSetupComplete && state.startupStage == StartupStage.SETUP_REQUIRED ->
            BackgroundTaskSetupScreen(
                themeMode = state.themeMode,
                onToggleTheme = viewModel::toggleTheme,
                onContinue = viewModel::finishBackgroundSetup,
            )
        state.startupStage == StartupStage.SETUP_REQUIRED -> RuntimeSetupPromptScreen(
            selectedStacks = state.selectedDevStacks,
            selectedAgent = state.agentKind,
            themeMode = state.themeMode,
            onToggleTheme = viewModel::toggleTheme,
            onToggleStack = viewModel::toggleDevStack,
            onSelectAgent = viewModel::selectAgent,
            onDownload = viewModel::startRuntimeSetup,
        )
        state.startupStage == StartupStage.INSTALLING && state.showDetailedSetupProgress ->
            RuntimeInstallationScreen(
                state = state,
                themeMode = state.themeMode,
                onToggleTheme = viewModel::toggleTheme,
                onCancelSetup = viewModel::cancelRuntimeSetup,
            )
        state.startupStage == StartupStage.INSTALLING || state.startupStage == StartupStage.INITIALIZING ->
            StartupLoadingScreen(
                state = state,
                themeMode = state.themeMode,
                onToggleTheme = viewModel::toggleTheme,
            )
        state.startupStage == StartupStage.ERROR -> StartupErrorScreen(
            message = state.startupError,
            isOffline = state.startupErrorIsOffline,
            logs = state.startupLogs,
            themeMode = state.themeMode,
            onToggleTheme = viewModel::toggleTheme,
            onRetry = viewModel::retryStartup,
            onBackToSetup = viewModel::backToSetup,
        )
        state.startupStage == StartupStage.MODEL_SETUP && state.agentKind == AgentKind.ANTIGRAVITY ->
            AntigravityOnboardingScreen(
                state = state,
                onStartLogin = viewModel::startAntigravityLogin,
                onSubmitCode = viewModel::submitAntigravityCode,
                onContinue = viewModel::finishAntigravityOnboarding,
                onSelectAgent = viewModel::chooseOnboardingAgent,
                onToggleTheme = viewModel::toggleTheme,
            )
        state.startupStage == StartupStage.MODEL_SETUP -> ProviderSetupScreen(
            initial = state.provider,
            onboarding = true,
            agentKind = state.agentKind,
            initialStep = 1,
            onSave = viewModel::finishOnboarding,
            onDiscover = viewModel::discoverModels,
            onValidate = viewModel::validateProvider,
            onSelectAgent = viewModel::chooseOnboardingAgent,
            onToggleTheme = viewModel::toggleTheme,
            themeMode = state.themeMode,
            freeModeEnabled = state.freeModeEnabled,
        )
        state.startupStage == StartupStage.READY && !state.backgroundSetupComplete ->
            BackgroundTaskSetupScreen(
                themeMode = state.themeMode,
                onToggleTheme = viewModel::toggleTheme,
                onContinue = viewModel::finishBackgroundSetup,
            )
        state.showAppTour && state.startupStage == StartupStage.READY ->
            ProductOnboardingScreen(
                onComplete = viewModel::finishAppTour,
            )
        state.readOnlyProject != null -> ReadOnlyProjectScreen(
            state = state,
            onBack = viewModel::closeReadOnlyProject,
            onSwitchChat = viewModel::switchReadOnlyChat,
            onContinueHere = viewModel::activateReadOnlyProject,
        )
        state.activeProject != null && state.workspaceVisible -> WorkspaceScreen(
            state = state,
            onBack = viewModel::closeProject,
            onSend = { text, pasted -> viewModel.sendPrompt(text, pasted) },
            onStop = viewModel::stopTask,
            onApproval = viewModel::answerApproval,
            onRefreshFiles = viewModel::refreshProjectFiles,
            onOpenFile = viewModel::openFile,
            onCloseFile = viewModel::closeFile,
            onSaveFile = viewModel::saveFile,
            onUndoChanges = viewModel::undoLastChanges,
            onKeepChanges = viewModel::keepLastChanges,
            onUndoFileChange = viewModel::undoFileChange,
            onKeepFileChange = viewModel::keepFileChange,
            onResumeTask = viewModel::resumeInterruptedTask,
            onCreateChat = viewModel::createChat,
            onSwitchChat = viewModel::switchChat,
            onRenameChat = viewModel::renameChat,
            onDeleteChat = viewModel::deleteChat,
            onTerminalRun = viewModel::requestProjectTerminalCommand,
            onTerminalInput = viewModel::sendProjectTerminalInput,
            onTerminalInterrupt = viewModel::interruptProjectTerminalCommand,
            onTerminalPrepare = viewModel::prepareProjectTerminalCommand,
            onTerminalDraftConsumed = viewModel::consumeProjectTerminalDraft,
            onTerminalOpened = viewModel::openProjectTerminal,
            onTerminalStop = viewModel::stopProjectTerminalCommand,
            onTerminalClear = viewModel::clearProjectTerminal,
            onTerminalConfirm = viewModel::confirmProjectTerminalCommand,
            onTerminalCancel = viewModel::cancelProjectTerminalCommand,
            onUseSuggestedProjectRoot = viewModel::useSuggestedProjectRoot,
            onExportProject = viewModel::exportActiveProject,
            onShareProject = viewModel::shareActiveProject,
            onAddAttachments = viewModel::addChatAttachments,
            onRemoveAttachment = viewModel::removePendingAttachment,
            onOpenAttachment = viewModel::openChatAttachment,
            onSaveAttachmentToUri = viewModel::saveChatAttachmentToStorage,
            onBuildAndRunAndroid = viewModel::buildAndRunAndroidApp,
            onSelectExecutionMode = viewModel::setExecutionMode,
            onToggleScratchpad = viewModel::toggleScratchpadExpanded,
            onAddRoadmapComment = viewModel::addRoadmapComment,
            onApproveAndBuildRoadmap = viewModel::approveAndBuildRoadmap,
            onBindFolder = viewModel::bindFolderToActiveProject,
            onSetProjectRootDirectory = viewModel::setProjectRootDirectory,
            onSelectTab = viewModel::selectEditorTab,
            onCloseTab = viewModel::closeEditorTab,
            onCloseOtherTabs = viewModel::closeOtherEditorTabs,
            onCloseAllTabs = viewModel::closeAllEditorTabs,
            onSelectTerminalSession = viewModel::selectTerminalSession,
            onNewTerminalSession = viewModel::createTerminalSession,
            onCloseTerminalSession = viewModel::closeTerminalSession,
            onResumeInterruptedSession = viewModel::resumeInterruptedSession,
            onDismissInterruptedSession = viewModel::dismissInterruptedSession,
            onRunAutonomousVerification = viewModel::runAutonomousVerification,
            onCreateFile = viewModel::createFile,
            onCreateFolder = viewModel::createFolder,
            onRenameFile = viewModel::renameEntry,
            onDeleteFile = viewModel::deleteEntry,
            onDuplicateFile = viewModel::duplicateEntry,
            onOpenFileByPath = viewModel::openFileByPath,
            workspaceDir = state.activeProject?.let { viewModel.projectWorkspaceController.projectWorkspaceRoot(it) },
            onRollbackToBaseline = viewModel::rollbackToBaseline,
            onEngageEmergencyKillSwitch = viewModel::engageEmergencyKillSwitch,
            onResetEmergencyKillSwitch = viewModel::resetEmergencyKillSwitch,
            onApprovalWithLevel = viewModel::answerApprovalWithLevel,
            onMoveEntry = viewModel::moveEntry,
            onGitStatus = viewModel::gitStatus,
            onGitCommit = viewModel::gitCommit,
            onGitCheckout = viewModel::gitCheckout,
            onGitDiff = viewModel::gitDiff,
            onGitBranchList = viewModel::gitBranchList,
            onGitPush = { cb -> viewModel.gitPush(onResult = cb) },
            onGitPull = { cb -> viewModel.gitPull(onResult = cb) },
            detectedPorts = detectedPorts,
            onScanPorts = viewModel::scanActiveLocalhostPorts,
            onAttachVisualInspection = viewModel::attachVisualInspectionToAgent,
            viewModel = viewModel,
            onOpenAppFactory = { openStudio(OverlayScreen.APP_FACTORY) },
            onOpenTeamStudio = { openStudio(OverlayScreen.TEAM_STUDIO) },
            onOpenCloudHybrid = { openStudio(OverlayScreen.CLOUD_HYBRID) },
            onOpenMarketplace = { navigator.openMarketplace() },
            onPushToGitHubRequest = startGitPush,
        )
        else -> RootScreenHost(
            state = state,
            viewModel = viewModel,
            projectsListState = projectsListState,
            settingsListState = settingsListState,
            settingsFocusListState = settingsFocusListState,
            chatsListState = chatsListState,
            chatsQueryState = chatsQueryState,
            chatsFilterPinnedState = chatsFilterPinnedState,
            providersListState = providersListState,
            skillsListState = skillsListState,
            mcpListState = mcpListState,
            skillsQueryState = skillsQueryState,
            marketplaceListState = marketplaceListState,
            marketplaceQueryState = marketplaceQueryState,
            marketplaceCategoryState = marketplaceCategoryState,
            marketplaceInstalledOnlyState = marketplaceInstalledOnlyState,
            marketplaceSortState = marketplaceSortState,
            authViewModel = authViewModel,
            onNavigateToAuth = { showAuthModal = true },
            onPushRequest = startGitPush,
            onHomeSend = { homeText ->
                pendingHomePrompt = homeText
                viewModel.createQuickProject()
            },
            onHomeAttach = { homeUris ->
                pendingHomeAttachments = homeUris
                viewModel.createQuickProject()
            },
            onShowAppUpdate = onShowAppUpdate,
            onHomeVoiceCall = {
                pendingHomeVoiceCall = true
                viewModel.createQuickProject()
            },
            onOpenAppFactory = { openStudio(OverlayScreen.APP_FACTORY) },
            onOpenTeamStudio = { openStudio(OverlayScreen.TEAM_STUDIO) },
            onOpenCloudHybrid = { openStudio(OverlayScreen.CLOUD_HYBRID) },
            onOpenGitHubStudio = { navigator.openGitHubStudio() },
        )
    }
    // Root app-update dialog (U1): composed at the very end so it opens
    // over whichever branch above is on screen.
    val rootAppUpdate = state.appUpdate
    if (showAppUpdateDialog && rootAppUpdate != null) {
        AppUpdateDialog(
            update = rootAppUpdate,
            status = state.appUpdateStatus,
            downloadedBytes = state.appUpdateDownloadedBytes,
            totalBytes = state.appUpdateTotalBytes,
            error = state.appUpdateError,
            onDismiss = { showAppUpdateDialog = false },
            onInstall = viewModel::installAppUpdate,
            onCancelDownload = viewModel::cancelAppUpdateDownload,
            onGrantPermission = {
                appUpdatePermissionLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                )
            },
        )
    }
    GitPushUi(
        state = pushState,
        projects = state.projects,
        onPickProject = { project ->
            pushState.beginPush(project.name)
            pendingPushProjectId = project.id
            viewModel.openProject(project)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AntigravityOnboardingScreen(
    state: AppUiState,
    onStartLogin: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onContinue: () -> Unit,
    onSelectAgent: (AgentKind) -> Unit,
    onToggleTheme: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var code by rememberSaveable { mutableStateOf("") }
    var showAgentPicker by rememberSaveable { mutableStateOf(false) }
    if (showAgentPicker) {
        AgentSwitchSheet(
            selected = AgentKind.ANTIGRAVITY,
            onSelect = { agent ->
                showAgentPicker = false
                onSelectAgent(agent)
            },
            onDismiss = { showAgentPicker = false },
            freeModeEnabled = state.freeModeEnabled,
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set up Antigravity") },
                actions = { IconButton(onClick = onToggleTheme) { Icon(Icons.Default.DarkMode, "Toggle theme") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Connect your Google account", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily), fontWeight = FontWeight.Normal)
            Text(
                "Kodex runs Google's official agy CLI inside its private Linux environment. Google handles authentication and agy owns the saved session.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state.antigravityAuth.status) {
                AntigravityAuthStatus.SIGNED_OUT, AntigravityAuthStatus.ERROR -> {
                    state.antigravityAuth.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = onStartLogin, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Sign in with Google")
                    }
                }
                AntigravityAuthStatus.STARTING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Starting the official Antigravity login…")
                }
                AntigravityAuthStatus.COMPLETING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Completing Google sign-in…")
                }
                AntigravityAuthStatus.AWAITING_CODE -> {
                    Text("Google sign-in opened in your browser. Copy the one-time code shown after approval.")
                    state.antigravityAuth.authorizationUrl?.let { url ->
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(url)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copy sign-in URL")
                        }
                    }
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Authorization code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onSubmitCode(code); code = "" },
                        enabled = code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Complete sign-in") }
                }
                AntigravityAuthStatus.SIGNED_IN -> {
                    Surface(color = PocketGreen.copy(alpha = 0.12f), shape = RoundedCornerShape(14.dp)) {
                        Text(
                            state.antigravityAuth.accountEmail?.let { "Connected as $it" } ?: "Google account connected",
                            Modifier.fillMaxWidth().padding(16.dp),
                            color = PocketGreen,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Continue")
                    }
                }
            }
            TextButton(
                onClick = { showAgentPicker = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Use another coding agent", fontSize = 12.sp)
            }
            Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f), shape = RoundedCornerShape(14.dp)) {
                Text(
                    "Automatic tool approval is enabled for Antigravity. It can edit project files and run commands without confirmation. Changes remain reviewable in Kodex.",
                    Modifier.fillMaxWidth().padding(14.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackgroundTaskSetupScreen(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    onToggleTheme: () -> Unit = {},
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val powerManager = context.getSystemService(PowerManager::class.java)
    fun notificationsAllowed(): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return runtimeGranted && androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    fun batteryUnrestricted(): Boolean = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    var notificationGranted by remember { mutableStateOf(notificationsAllowed()) }
    var batteryGranted by remember { mutableStateOf(batteryUnrestricted()) }
    var notificationDenied by rememberSaveable { mutableStateOf(false) }
    var taskProtectionConfirmed by rememberSaveable { mutableStateOf(false) }

    // v3.2.38 (J): system Back steps the wizard back instead of exiting
    // the app mid-setup (mirrors RuntimeSetupPromptScreen).
    BackHandler(enabled = currentStep > 0) { currentStep-- }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationGranted = notificationsAllowed()
        notificationDenied = !granted
        if (notificationGranted) currentStep = 1
    }
    val notificationSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        notificationGranted = notificationsAllowed()
        if (notificationGranted) currentStep = 1
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryGranted = batteryUnrestricted()
        if (batteryGranted) currentStep = 2
    }

    LaunchedEffect(Unit) {
        notificationGranted = notificationsAllowed()
        batteryGranted = batteryUnrestricted()
    }

    val currentIcon = when (currentStep) {
        0 -> Icons.Default.Notifications
        1 -> Icons.Default.BatterySaver
        else -> Icons.Default.Shield
    }
    val currentTitle = when (currentStep) {
        0 -> "Task notifications"
        1 -> "Background reliability"
        else -> "Task protection"
    }
    val currentDescription = when (currentStep) {
        0 -> "See live progress and receive an alert when Claude finishes or needs your attention."
        1 -> if (BackgroundProtectionHelper.isOemDeviceWithAggressiveKiller()) {
            "Allow Kodex to continue tasks without pausing when switching to recents. On ${BackgroundProtectionHelper.getOemName()} devices, also enable Autostart."
        } else {
            "Allow Kodex to continue a task when you lock the phone or switch to another app."
        }
        else -> if (BackgroundProtectionHelper.isOemDeviceWithAggressiveKiller()) {
            "Keep the CPU & network active while tasks run. Make sure battery optimization is set to Unrestricted."
        } else {
            "Keep the CPU awake only while a visible coding task is running, then release it automatically."
        }
    }
    val currentPrivacyNote = when (currentStep) {
        0 -> "Only task progress, completion, and error notifications are sent."
        1 -> "You remain in control and can stop every task from its notification."
        else -> "The screen stays off. Protection is capped at 90 minutes and stops with the task."
    }
    val currentGranted = when (currentStep) {
        0 -> notificationGranted
        1 -> batteryGranted
        else -> taskProtectionConfirmed
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(compact = true)
                        Spacer(Modifier.width(9.dp))
                        Text("Kodex", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            if (themeMode == AppThemeMode.DARK) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle theme",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            Text("Prepare for reliable setup", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily), fontWeight = FontWeight.Normal)
            Spacer(Modifier.height(8.dp))
            Text(
                "Setup time depends on the toolchains you choose next. You may leave Kodex in the background while it works.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(18.dp))
            StepDots(currentStep)
            Spacer(Modifier.height(18.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    PermissionSummaryRow(Icons.Default.Notifications, "Notifications", notificationGranted, currentStep == 0)
                    HorizontalDivider(modifier = Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    PermissionSummaryRow(Icons.Default.BatterySaver, "Background", batteryGranted, currentStep == 1)
                    HorizontalDivider(modifier = Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    PermissionSummaryRow(Icons.Default.Shield, "Task protection", taskProtectionConfirmed, currentStep == 2)
                }
            }

            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(11.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(currentIcon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("STEP ${currentStep + 1} OF 3", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                            Text(currentTitle, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (currentGranted) Icon(Icons.Default.Check, "Granted", tint = PocketGreen)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(currentDescription, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(currentPrivacyNote, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            when (currentStep) {
                                0 -> when {
                                    notificationGranted -> currentStep = 1
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationDenied -> {
                                        // Targets below API 33 can have notification prompts tied to
                                        // channel creation. Create channels only after this explicit tap.
                                        NotificationCoordinator.ensureChannels(context)
                                        RuntimeSetupService.ensureNotificationChannel(context)
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    else -> notificationSettingsLauncher.launch(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                                            Settings.EXTRA_APP_PACKAGE,
                                            context.packageName,
                                        ),
                                    )
                                }
                                1 -> if (batteryGranted) {
                                    currentStep = 2
                                } else {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    runCatching { batteryLauncher.launch(intent) }
                                        .onFailure {
                                            batteryLauncher.launch(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:${context.packageName}"),
                                                ),
                                            )
                                        }
                                }
                                else -> {
                                    taskProtectionConfirmed = true
                                    onContinue()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(
                            when (currentStep) {
                                0 -> if (notificationGranted) "Next" else if (notificationDenied) "Open notification settings" else "Allow notifications"
                                1 -> if (batteryGranted) "Next" else "Open battery settings"
                                else -> "Enable and finish"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                    }
                    if (BackgroundProtectionHelper.isOemDeviceWithAggressiveKiller() && (currentStep == 1 || currentStep == 2)) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { BackgroundProtectionHelper.openOemBackgroundSettings(context) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(13.dp),
                        ) {
                            Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Open ${BackgroundProtectionHelper.getOemName()} Autostart Settings",
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (currentStep < 2 && !currentGranted) {
                        TextButton(
                            onClick = { currentStep += 1 },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (currentStep == 0) "Continue without notifications" else "Continue without battery exemption")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "You can change these settings later. Android may still stop exceptionally heavy work when the device is low on memory.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun PermissionSummaryRow(
    icon: ImageVector,
    title: String,
    complete: Boolean,
    active: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (active) MaterialTheme.colorScheme.primary else if (complete) PocketGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        )
        when {
            complete -> Icon(Icons.Default.Check, "Complete", tint = PocketGreen, modifier = Modifier.size(18.dp))
            active -> Text("Required", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            else -> Text("Next", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

private data class DevStackVisuals(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accentColor: Color,
    val tag: String,
)

private fun getDevStackVisuals(stack: DevStack): DevStackVisuals = when (stack) {
    DevStack.WEB -> DevStackVisuals(
        icon = Icons.Default.Language,
        accentColor = Color(0xFF38BDF8),
        tag = "HTML · CSS · JS · TS",
    )
    DevStack.PYTHON -> DevStackVisuals(
        icon = Icons.Default.Terminal,
        accentColor = Color(0xFFFBBF24),
        tag = "python3 + pip + venv",
    )
    DevStack.ANDROID -> DevStackVisuals(
        icon = Icons.Default.Android,
        accentColor = Color(0xFF4ADE80),
        tag = "OpenJDK build tools",
    )
    DevStack.CPP -> DevStackVisuals(
        icon = Icons.Default.Memory,
        accentColor = Color(0xFFA78BFA),
        tag = "gcc + g++ + cmake",
    )
    DevStack.PHP -> DevStackVisuals(
        icon = Icons.Default.Dns,
        accentColor = Color(0xFF818CF8),
        tag = "php-cli + Composer",
    )
    DevStack.BROWSER -> DevStackVisuals(
        icon = Icons.Default.Language,
        accentColor = Color(0xFF60A5FA),
        tag = "Chromium + Puppeteer",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuntimeSetupPromptScreen(
    selectedStacks: Set<DevStack>,
    selectedAgent: AgentKind = AgentKind.CLAUDE_CODE,
    themeMode: AppThemeMode = AppThemeMode.DARK,
    onToggleTheme: () -> Unit = {},
    onToggleStack: (DevStack) -> Unit,
    onSelectAgent: (AgentKind) -> Unit = {},
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val memoryInfo = remember { ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo) }
    val totalRamGb = memoryInfo.totalMem.toDouble() / 1_073_741_824.0
    val totalRamLabel = String.format(java.util.Locale.US, "%.1f", totalRamGb)
    val arm64 = supportsArm64Runtime(Build.SUPPORTED_ABIS, System.getProperty("os.arch"))
    // Android reports usable physical memory after hardware/GPU reservations.
    // RAM is therefore informational; it must not reject nominal 4 GB phones.
    val compatible = arm64

    var currentStep by remember { mutableIntStateOf(0) }
    val setupScrollState = rememberScrollState()

    LaunchedEffect(currentStep) {
        setupScrollState.scrollTo(0)
    }

    if (currentStep > 0) {
        BackHandler { currentStep = 0 }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(compact = true)
                        Spacer(Modifier.width(9.dp))
                        Text("Kodex", fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    if (currentStep > 0) {
                        IconButton(onClick = { currentStep = 0 }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            if (themeMode == AppThemeMode.DARK) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle theme",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 22.dp)
                .verticalScroll(setupScrollState),
        ) {
            Spacer(Modifier.height(8.dp))

            if (currentStep == 0) {
                // Device Compatibility & Verification
                Text(
                    text = "DEVICE CHECK",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Ready to build on this phone",
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily),
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Your phone meets the requirements. Choose your coding tools next and Kodex will handle the setup.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                )

                Spacer(Modifier.height(20.dp))

                // Hardware & Compatibility Specs Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Speed,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "System compatibility",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        if (compatible) "Your device is ready" else "This device is unsupported",
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (compatible) PocketGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                border = BorderStroke(0.5.dp, if (compatible) PocketGreen.copy(alpha = 0.35f) else MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                            ) {
                                Text(
                                    text = if (compatible) "Ready" else "Unsupported",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (compatible) PocketGreen else MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 1.dp)

                        SpecRow(
                            icon = Icons.Default.Memory,
                            label = "Memory (RAM)",
                            value = "$totalRamLabel GB usable",
                            statusOk = true,
                        )

                        SpecRow(
                            icon = Icons.Default.Code,
                            label = "Processor",
                            value = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
                            statusOk = arm64,
                        )

                        SpecRow(
                            icon = Icons.Default.Storage,
                            label = "Required download",
                            value = "149–774 MB",
                            statusOk = true,
                        )
                        Text(
                            "Based on the tools you select",
                            modifier = Modifier.padding(start = 26.dp),
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))

                Button(
                    onClick = { currentStep = 1 },
                    enabled = compatible,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = if (compatible) "Continue to tool setup" else "Device not supported",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "You can change tools later",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "TOOLCHAIN SETUP",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Choose your tools",
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily),
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Start lightweight. You can install more toolchains later from Settings.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )

                Spacer(Modifier.height(18.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (BuildConfig.OFFLINE_RUNTIME_BUNDLES) "Core runtime · 68.8 MB" else "Core runtime · 68.8 MB download",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp,
                            )
                            Text("Ubuntu  ·  Node.js  ·  npm  ·  Git", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        Icon(Icons.Default.Check, "Included", tint = PocketGreen, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text("CODING AGENT", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp)
                Spacer(Modifier.height(8.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column {
                        AgentKind.entries.forEachIndexed { index, agent ->
                            AgentChoiceRow(
                                agent = agent,
                                selected = selectedAgent == agent,
                                onClick = { onSelectAgent(agent) },
                            )
                            if (index != AgentKind.entries.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 62.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
                Text(
                    "Only the selected optional agent is downloaded. You can install or switch agents later from Settings.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
                )

                Spacer(Modifier.height(18.dp))
                Text("OPTIONAL TOOLCHAINS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp)
                Spacer(Modifier.height(8.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column {
                        DevStack.entries.forEachIndexed { index, stack ->
                            val isOfflineUnbundled = BuildConfig.OFFLINE_RUNTIME_BUNDLES && stack in setOf(DevStack.CPP, DevStack.PHP, DevStack.BROWSER)
                            DevStackChoiceRow(
                                stack = stack,
                                selected = !isOfflineUnbundled && (stack == DevStack.WEB || stack in selectedStacks),
                                locked = stack == DevStack.WEB || isOfflineUnbundled,
                                lockedReason = when {
                                    stack == DevStack.WEB -> "Web is included with the Core runtime — no separate download needed."
                                    isOfflineUnbundled -> "${stack.label} is available in the online build only."
                                    else -> null
                                },
                                onClick = { if (!isOfflineUnbundled) onToggleStack(stack) },
                            )
                            if (index != DevStack.entries.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(start = 62.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        toolchainDownloadSummary(selectedStacks, selectedAgent),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = onDownload,
                    enabled = compatible,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (compatible) "Install Kodex" else "Device not supported",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                        if (compatible) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

private const val CORE_RUNTIME_DOWNLOAD_MB = 69
private const val CLAUDE_RUNTIME_DOWNLOAD_MB = 72
private const val DSH_RUNTIME_DOWNLOAD_MB = 27
private const val AGY_RUNTIME_DOWNLOAD_MB = 40
// Codex installs via npm: wrapper + @openai/codex-linux-arm64 platform
// package (389 MB unpacked at 0.162.1 per the npm registry). ~150 MB is
// the compressed download estimate for that platform tarball.
private const val CODEX_RUNTIME_DOWNLOAD_MB = 150
private const val PYTHON_RUNTIME_DOWNLOAD_MB = 55
private const val ANDROID_RUNTIME_DOWNLOAD_MB = 570

private fun setupTimeEstimate(selected: Set<DevStack>): String {
    var minimumMinutes = 3
    var maximumMinutes = 5
    if (DevStack.PYTHON in selected) {
        minimumMinutes += 1
        maximumMinutes += 2
    }
    if (DevStack.ANDROID in selected) {
        minimumMinutes += 7
        maximumMinutes += 10
    }
    if (DevStack.CPP in selected) {
        minimumMinutes += 3
        maximumMinutes += 5
    }
    if (DevStack.PHP in selected) {
        minimumMinutes += 2
        maximumMinutes += 4
    }
    return "$minimumMinutes–$maximumMinutes minutes"
}

private fun stackDownloadLabel(stack: DevStack): String = when {
    stack == DevStack.WEB -> " · included"
    BuildConfig.OFFLINE_RUNTIME_BUNDLES && stack in setOf(DevStack.PYTHON, DevStack.ANDROID) -> " · included"
    BuildConfig.OFFLINE_RUNTIME_BUNDLES && stack in setOf(DevStack.CPP, DevStack.PHP, DevStack.BROWSER) -> " · Online only"
    !BuildConfig.OFFLINE_RUNTIME_BUNDLES && stack == DevStack.PYTHON -> " · 55 MB"
    !BuildConfig.OFFLINE_RUNTIME_BUNDLES && stack == DevStack.ANDROID -> " · 570 MB"
    else -> ""
}

private fun toolchainDownloadSummary(selected: Set<DevStack>, agent: AgentKind): String {
    if (BuildConfig.OFFLINE_RUNTIME_BUNDLES) return "All selected bundles are included in this offline app"
    val total = CORE_RUNTIME_DOWNLOAD_MB +
        when (agent) {
            AgentKind.CLAUDE_CODE -> CLAUDE_RUNTIME_DOWNLOAD_MB
            AgentKind.DEEPSEEK_HARNESS -> DSH_RUNTIME_DOWNLOAD_MB
            AgentKind.ANTIGRAVITY -> AGY_RUNTIME_DOWNLOAD_MB
            AgentKind.CODEX -> CODEX_RUNTIME_DOWNLOAD_MB
            // Custom is bring-your-own-API: zero download.
            AgentKind.CUSTOM -> 0
        } +
        (if (DevStack.PYTHON in selected) PYTHON_RUNTIME_DOWNLOAD_MB else 0) +
        (if (DevStack.ANDROID in selected) ANDROID_RUNTIME_DOWNLOAD_MB else 0)
    val laterPackages = selected.intersect(setOf(DevStack.CPP, DevStack.PHP))
    return buildString {
        append("Download: ")
        append(total)
        append(" MB")
        if (laterPackages.isNotEmpty()) append(" · C/PHP packages download later")
        if (total >= 500) append(" · Wi-Fi recommended")
    }
}

@Composable
private fun DevStackChoiceRow(
    stack: DevStack,
    selected: Boolean,
    locked: Boolean,
    lockedReason: String? = null,
    onClick: () -> Unit,
) {
    val visuals = getDevStackVisuals(stack)
    val conciseDescription = when (stack) {
        DevStack.WEB -> "Included with the Core runtime"
        DevStack.PYTHON -> "Scripts, automation and backends"
        DevStack.ANDROID -> "Java and Kotlin build tools"
        DevStack.CPP -> "Native apps and command-line tools"
        DevStack.PHP -> "PHP sites and Laravel projects"
        DevStack.BROWSER -> "Chromium & Puppeteer automation"
    }

    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            // Medium: locked rows used to swallow taps with no reason.
            // They now explain why they are locked, and look dimmed.
            .clickable {
                if (locked) {
                    lockedReason?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                } else {
                    onClick()
                }
            }
            .alpha(if (locked) 0.6f else 1f)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(visuals.icon, null, tint = visuals.accentColor, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stack.label + stackDownloadLabel(stack),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(1.dp))
            Text(conciseDescription, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(21.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(6.dp))
                .border(
                    1.5.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun AgentChoiceRow(
    agent: AgentKind,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = when (agent) {
        AgentKind.CLAUDE_CODE -> Color(0xFFD97757)
        AgentKind.DEEPSEEK_HARNESS -> Color(0xFF4D6BFE)
        AgentKind.ANTIGRAVITY -> Color(0xFF4285F4)
        AgentKind.CODEX -> Color(0xFF7C3AED)
        AgentKind.CUSTOM -> Color(0xFF9C6BFF)
    }
    val mark = when (agent) {
        AgentKind.CLAUDE_CODE -> "CC"
        AgentKind.DEEPSEEK_HARNESS -> "DS"
        AgentKind.ANTIGRAVITY -> "AG"
        AgentKind.CODEX -> "CX"
        AgentKind.CUSTOM -> "CU"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(mark, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    agent.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                if (agent == AgentKind.DEEPSEEK_HARNESS) {
                    Spacer(Modifier.width(7.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            "Recommended",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            Spacer(Modifier.height(1.dp))
            Text(agent.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(1.dp))
            Text(agent.downloadNote, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(21.dp)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(9.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSwitchSheet(
    selected: AgentKind,
    onSelect: (AgentKind) -> Unit,
    onDismiss: () -> Unit,
    freeModeEnabled: Boolean = false,
) {
    val pickerAgents = remember(freeModeEnabled) { agentsForPicker(freeModeEnabled) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                "Choose coding agent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Switch if the current service is unavailable. Your existing login and credentials stay saved.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    pickerAgents.forEachIndexed { index, agent ->
                        AgentChoiceRow(
                            agent = agent,
                            selected = agent == selected,
                            onClick = { onSelect(agent) },
                        )
                        if (index != pickerAgents.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 62.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SpecRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    statusOk: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (statusOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RuntimeInstallationScreen(
    state: AppUiState,
    themeMode: AppThemeMode = AppThemeMode.DARK,
    onToggleTheme: () -> Unit = {},
    onCancelSetup: () -> Unit = {},
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(compact = true)
                        Spacer(Modifier.width(9.dp))
                        Text("Set up Kodex", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            if (themeMode == AppThemeMode.DARK) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle theme",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            StepDots(0)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "STEP 1 OF 3",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PocketOrange,
                    letterSpacing = 1.1.sp,
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(50),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Local setup", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Build your workspace",
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily),
                fontWeight = FontWeight.Normal,
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 42.dp), contentAlignment = Alignment.CenterStart) {
                // Medium: the box used to be a fixed 42dp with maxLines=2,
                // so a long startup status was cut off with no way to read
                // it. It now grows to fit the message.
                Text(
                    state.startupMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Installation progress", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("${(state.startupProgress * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.startupProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Estimated ${setupTimeEstimate(state.selectedDevStacks)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.5.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        state.startupBytes?.let { (downloaded, total) ->
                            Text(
                                "${formatMegabytes(downloaded)} / ${formatMegabytes(total)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.5.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            SetupLogPanel(
                logs = state.startupLogs.ifEmpty { listOf("$ ${state.startupMessage}") },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "You can leave Kodex in the background and follow setup from the notification.",
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onCancelSetup,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Stop setup", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun StartupLoadingScreen(
    state: AppUiState,
    themeMode: AppThemeMode = AppThemeMode.DARK,
    onToggleTheme: () -> Unit = {},
) {
    val view = LocalView.current
    // Runtime download + install can take 10+ minutes; keep the screen on while this
    // screen is visible. Released automatically when setup finishes or leaves.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val messages = remember {
        listOf(
            "Setting up your workspace",
            "Preparing your coding tools",
            "Almost ready",
        )
    }
    var messageIndex by remember(state.startupStage) { mutableIntStateOf(0) }
    LaunchedEffect(messages) {
        while (true) {
            delay(3_000)
            messageIndex = (messageIndex + 1) % messages.size
        }
    }
    val logoTransition = rememberInfiniteTransition(label = "startup logo")
    val logoPulse by logoTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "startup logo pulse",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(
                Modifier.graphicsLayer {
                    scaleX = logoPulse
                    scaleY = logoPulse
                    alpha = 0.82f + ((logoPulse - 0.96f) / 0.08f) * 0.18f
                },
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Kodex",
                fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily,
                fontSize = 30.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            AnimatedContent(
                targetState = messages[messageIndex],
                label = "startup message",
            ) { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(14.dp))
            AnimatedThinkingDots(dotColor = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun LaunchStatusRow(icon: ImageVector, label: String, status: String, complete: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(28.dp)
                .background(
                    if (complete) PocketGreen.copy(alpha = 0.11f) else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(9.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (complete) Icons.Default.Check else icon,
                contentDescription = null,
                tint = if (complete) PocketGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(
            status,
            color = if (complete) PocketGreen else MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SetupLogPanel(logs: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var followLatest by rememberSaveable { mutableStateOf(true) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(logs.size, logs.lastOrNull()) {
        if (expanded && followLatest) {
            delay(20)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    LaunchedEffect(scrollState.isScrollInProgress) {
        if (!scrollState.isScrollInProgress && expanded) {
            followLatest = scrollState.maxValue - scrollState.value < 32
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (expanded) "Live setup terminal" else logs.lastOrNull().orEmpty(),
                    modifier = Modifier.weight(1f),
                    fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse setup details" else "Expand setup details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 170.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    logs.forEach { line ->
                        Text(
                            text = line,
                            fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = "▌",
                        fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (!followLatest) {
                    TextButton(
                        onClick = {
                            followLatest = true
                            scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Jump to latest") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartupErrorScreen(
    message: String?,
    isOffline: Boolean,
    logs: List<String>,
    themeMode: AppThemeMode = AppThemeMode.DARK,
    onToggleTheme: () -> Unit = {},
    onRetry: () -> Unit,
    onBackToSetup: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackToSetup) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to setup",
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(compact = true)
                        Spacer(Modifier.width(9.dp))
                        Text("Kodex", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            if (themeMode == AppThemeMode.DARK) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle theme",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            // Medium: on short screens the retry buttons used to be pushed
            // off-screen with no way to reach them.
            Modifier.fillMaxSize().padding(padding).padding(28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Warning, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(20.dp))
            Text(
                if (isOffline) "You're offline" else "Kodex couldn't finish starting",
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily),
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                message ?: "Please try again.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (logs.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                        Toast.makeText(context, "Setup log copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy setup logs")
                }
            }
            Spacer(Modifier.height(24.dp))
            if (isOffline) {
                Button(
                    onClick = {
                        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                        } else {
                            Settings.ACTION_WIRELESS_SETTINGS
                        }
                        context.startActivity(Intent(action))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open internet settings")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text("Try again")
                }
            } else {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onBackToSetup, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("Change tools & setup options")
            }
        }
    }
}

private fun formatMegabytes(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootScreenHost(
    state: AppUiState,
    viewModel: MainViewModel,
    projectsListState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    // v3.2.39 (Scope 2): root-screen list/filter states, hoisted to
    // PocketDevApp (never disposed). Defaults keep the old in-screen
    // behaviour for any other caller.
    settingsListState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    settingsFocusListState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    chatsListState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    chatsQueryState: androidx.compose.runtime.MutableState<String> = rememberSaveable { mutableStateOf("") },
    chatsFilterPinnedState: androidx.compose.runtime.MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },
    providersListState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    skillsListState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    mcpListState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    skillsQueryState: androidx.compose.runtime.MutableState<String> = rememberSaveable { mutableStateOf("") },
    marketplaceListState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    marketplaceQueryState: androidx.compose.runtime.MutableState<String> = rememberSaveable { mutableStateOf("") },
    marketplaceCategoryState: androidx.compose.runtime.MutableState<MarketplaceCategory?> = rememberSaveable { mutableStateOf<MarketplaceCategory?>(null) },
    marketplaceInstalledOnlyState: androidx.compose.runtime.MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },
    marketplaceSortState: androidx.compose.runtime.MutableState<SearchSort> = rememberSaveable { mutableStateOf(SearchSort.RELEVANCE) },
    authViewModel: AuthViewModel? = null,
    onNavigateToAuth: () -> Unit = {},
    onPushRequest: () -> Unit = {},
    onOpenAppFactory: () -> Unit = {},
    onOpenTeamStudio: () -> Unit = {},
    onOpenCloudHybrid: () -> Unit = {},
    onOpenGitHubStudio: () -> Unit = {},
    onHomeSend: (String) -> Unit = {},
    onHomeAttach: (List<Uri>) -> Unit = {},
    onShowAppUpdate: () -> Unit = {},
    onHomeVoiceCall: () -> Unit = {},
) {
    var screen by rememberSaveable { mutableStateOf(RootScreen.CHAT_HOME) }
    var settingsFocus by rememberSaveable { mutableStateOf("tools") }
    // v3.2.40 (Scope E): cold start lands on the chat home even when
    // saved-instance state restored a sub-screen (e.g. Settings) from
    // a killed process. Runs once per process; warm resumes keep
    // whatever screen the user is on.
    LaunchedEffect(Unit) {
        if (!shellComposedInThisProcess) {
            shellComposedInThisProcess = true
            if (screen != RootScreen.CHAT_HOME) screen = RootScreen.CHAT_HOME
        }
    }
    // Kodex drawer shell: the side drawer is the single home for every
    // section; Back from a section returns here with the drawer re-opened
    // (inventory rule R1), and Back with the drawer open just closes it.
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    // v3.2.37 (Part E): the drawer is pre-composed (mounted at idle, then
    // kept mounted) and animated by this shared Animatable — transform and
    // alpha only. The content behind reads the same value in a graphicsLayer
    // to scale/dim, so one draw-phase value drives the whole transition.
    val drawerProgressAnim = remember { Animatable(0f) }
    var drawerMounted by remember { mutableStateOf(false) }
    LaunchedEffect(drawerOpen) { if (drawerOpen) drawerMounted = true }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(700)
        drawerMounted = true
    }
    var skillsInitialTab by rememberSaveable { mutableIntStateOf(0) }
    var skillsTabEpoch by rememberSaveable { mutableIntStateOf(0) }
    // v3.2.38 (J): origin-aware Back for cross-section flows. Primary
    // entries (drawer, home switcher, handoff) clear the origin;
    // cross-section openers (Settings -> Connectors, Connectors ->
    // Marketplace / Skills, Code hub -> Projects) record it, and Back
    // returns to the recorded origin before the default rule applies.
    val screenOrigins = remember { mutableMapOf<RootScreen, RootScreen>() }
    val navigateBack: () -> Unit = {
        val origin = screenOrigins.remove(screen)
        when {
            origin != null -> screen = origin
            screen == RootScreen.CAPABILITIES || screen == RootScreen.VOICE_SETTINGS ||
                screen == RootScreen.LEGACY_SETTINGS || screen == RootScreen.PROFILE ||
                screen == RootScreen.LICENSE_PAGE || screen == RootScreen.NOTIFICATIONS_PAGE ||
                screen == RootScreen.SECTION_PAGE -> screen = RootScreen.SETTINGS
            screen != RootScreen.CHAT_HOME -> {
                screen = RootScreen.CHAT_HOME
                drawerOpen = true
            }
        }
    }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }
    BackHandler(enabled = !drawerOpen && screen != RootScreen.CHAT_HOME) { navigateBack() }
    var showQuickTerminal by rememberSaveable { mutableStateOf(false) }
    var showPrivacyPolicy by rememberSaveable { mutableStateOf(false) }
    val shellContext = LocalContext.current
    val appPreferences = remember { AppPreferences(shellContext) }
    var pinnedProjectIds by remember { mutableStateOf(appPreferences.pinnedProjectIds) }
    var hapticsEnabled by remember { mutableStateOf(appPreferences.hapticsEnabled) }
    val togglePin: (Project) -> Unit = { project ->
        pinnedProjectIds = if (project.id in pinnedProjectIds) {
            pinnedProjectIds - project.id
        } else {
            pinnedProjectIds + project.id
        }
        appPreferences.pinnedProjectIds = pinnedProjectIds
    }
    val hostAuthState = authViewModel?.authState?.collectAsStateWithLifecycle()?.value
        ?: AuthState.Unauthenticated
    val hostUser = when (hostAuthState) {
        is AuthState.Authenticated -> hostAuthState.user
        is AuthState.EmailVerificationRequired -> hostAuthState.user
        else -> null
    }
    val marketplaceInstalledIds by viewModel.marketplaceInstalledIds.collectAsStateWithLifecycle()
    val marketplaceUpdatesAvailable by viewModel.marketplaceUpdatesAvailable.collectAsStateWithLifecycle()
    // v3.2.42: the v3.2.41 derived IME boolean that used to be read
    // here is GONE — nothing ever consumed its value (the workspace
    // hid its bottom bar through its own read, now focus-driven;
    // the chat screens own their IME handling at the composer).
    // The root reads no IME state at all.
    val terminalLines by viewModel.terminalLines.collectAsStateWithLifecycle()
    val isTerminalRunning by viewModel.isTerminalRunning.collectAsStateWithLifecycle()
    val terminalLiveOutput by viewModel.terminalLiveOutput.collectAsStateWithLifecycle()
    val terminalCurrentCommand by viewModel.terminalCurrentCommand.collectAsStateWithLifecycle()

    // v3.2.37 (Parts F/F2): the home thread + its voice call. The call is
    // hosted HERE, over the home thread — its replies come from the same
    // API-provider routing as typed home chat, never from a workspace.
    val activeHomeThread = state.homeThreads.firstOrNull { it.id == state.activeHomeThreadId }
        ?: state.homeThreads.firstOrNull()
    val homeCallRecognizer = remember { VoiceRecognizer(shellContext) }
    DisposableEffect(homeCallRecognizer) { onDispose { homeCallRecognizer.release() } }
    var homeCallActive by remember { mutableStateOf(false) }
    // v3.2.40: the home call has two engines — Live (Gemini Live,
    // speech-to-speech, needs a Google AI Studio key) and Standard
    // (the pipeline call below). Live is the default when a key is
    // connected; the switcher in the call header flips between them.
    var homeCallEngineLive by remember { mutableStateOf(false) }
    fun openHomeCall() {
        homeCallEngineLive = viewModel.hasGoogleAiStudioKey()
        homeCallActive = true
    }
    val homeCallPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            openHomeCall()
        } else {
            Toast.makeText(shellContext, "Microphone permission is required for voice calls", Toast.LENGTH_SHORT).show()
        }
    }
    fun startHomeCall() {
        // The speech-recognizer gate applies only to the Standard
        // engine — a Live call never touches the recognizer.
        if (!viewModel.hasGoogleAiStudioKey() && !homeCallRecognizer.isAvailable()) {
            Toast.makeText(shellContext, "Voice recognition not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            shellContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            homeCallPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            openHomeCall()
        }
    }
    val homeCallMessages = (activeHomeThread?.messages ?: emptyList()).mapNotNull { message ->
        when (message.kind) {
            "text" -> ChatMessage(
                id = message.id,
                fromUser = message.fromUser,
                text = message.text,
                createdAt = java.time.Instant.ofEpochMilli(message.createdAtMillis),
            )
            "handoff" -> ChatMessage(
                id = message.id,
                fromUser = false,
                text = "This sounds like a job for Work. I put a card in the chat — tap Open in Work when you are ready.",
                createdAt = java.time.Instant.ofEpochMilli(message.createdAtMillis),
            )
            else -> null
        }
    }

    if (showPrivacyPolicy) {
        PrivacyPolicyScreen(
            onBack = { showPrivacyPolicy = false },
        )
    } else {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val authState = authViewModel?.authState?.collectAsStateWithLifecycle()?.value ?: AuthState.Unauthenticated
        val authUiState = authViewModel?.uiState?.collectAsStateWithLifecycle()?.value
        if (authState is AuthState.EmailVerificationRequired) {
            EmailVerificationDialog(
                email = authState.user.email,
                isLoading = authUiState?.isLoading ?: false,
                onResend = { authViewModel?.resendEmailVerification() },
                onRefresh = { authViewModel?.refreshVerificationStatus() },
                onSignOut = { authViewModel?.signOut() },
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .graphicsLayer {
                    // Part E depth: content settles back a touch as the
                    // drawer opens. Draw-phase only — no recomposition.
                    val p = drawerProgressAnim.value
                    scaleX = 1f - 0.03f * p
                    scaleY = 1f - 0.03f * p
                },
        ) {
            val componentUpdates = com.vayro.app.update.ComponentUpdateNotifier.updatesAvailable
            if (componentUpdates.isNotEmpty() && screen != RootScreen.SETTINGS) {
                val bannerText = "Update available for " + componentUpdates.first() +
                    (if (componentUpdates.size > 1) " +" + (componentUpdates.size - 1) + " more" else "") +
                    " — tap to install"
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        // v3.2.38 (Part K): land directly on the
                        // Environment updates page, where the installs
                        // actually happen (was: the Settings home).
                        .clickable { settingsFocus = "environment"; screen = RootScreen.SECTION_PAGE },
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            bannerText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { com.vayro.app.update.ComponentUpdateNotifier.dismiss() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            when (screen) {
                RootScreen.CHAT_HOME -> ChatHomeScreen(
                    agentKind = state.agentKind,
                    freeModeEnabled = state.freeModeEnabled,
                    appUpdateAvailable = state.appUpdate != null,
                    onOpenDrawer = { drawerOpen = true },
                    onOpenUpdate = onShowAppUpdate,
                    // v3.2.37 (Part F): home sends stay HOME. Conversational
                    // turns are answered in this thread by the API providers
                    // (Part F2); a task gets the "Open in Work" handoff card
                    // instead of a silent teleport into a quick project.
                    // Other attachments still go to Work (they need a workspace).
                    onSend = { text, pasted -> viewModel.sendHomeMessage(text, pasted) },
                    onAttach = onHomeAttach,
                    // v3.2.41 (Scope 3): PDFs/ZIPs picked at home are
                    // read at home; anything else falls back to the
                    // Work route unchanged.
                    onAttachDocuments = { uris ->
                        viewModel.attachHomeDocuments(uris) { leftover -> onHomeAttach(leftover) }
                    },
                    onVoiceCall = { startHomeCall() },
                    onSelectAgent = viewModel::selectAgent,
                    onMoreAgents = { screen = RootScreen.AGENT },
                    onOpenConnectors = { screen = RootScreen.CONNECTORS },
                    onPushToGitHub = onPushRequest,
                    homeMessages = activeHomeThread?.messages ?: emptyList(),
                    homeThreadId = activeHomeThread?.id ?: "",
                    homePendingImages = state.homePendingImages,
                    homeImageNote = state.homeImageNote,
                    onAttachImages = viewModel::attachHomeImages,
                    onRemoveHomeImage = viewModel::removeHomePendingImage,
                    homePendingDocName = state.homePendingDocName,
                    homePendingDocText = state.homePendingDocText,
                    homeDocNote = state.homeDocNote,
                    onRemoveHomeDoc = viewModel::removeHomePendingDoc,
                    homeSending = state.homeSending,
                    onRegenerateHomeReply = viewModel::regenerateHomeReply,
                    homeProviderName = state.homeChatProviderName,
                    onNewChat = { viewModel.newHomeThread() },
                    onOpenWork = {
                        screenOrigins.remove(RootScreen.PROJECTS)
                        screen = RootScreen.PROJECTS
                    },
                    onOpenInWork = { task ->
                        // The one deliberate bridge home -> Work: reuse the
                        // quick-project + pending-prompt delivery, then
                        // leave the receipt in the home thread.
                        // v3.2.38 (N4): the prompt carries the conversation.
                        onHomeSend(viewModel.homeThreadWorkPrompt(task))
                        val projectName = viewModel.state.value.activeProject?.name
                            ?: viewModel.state.value.projects.firstOrNull()?.name
                            ?: "Work"
                        viewModel.noteHomeTaskMoved(task, projectName)
                    },
                    onOpenChatInWork = {
                        // v3.2.38 (N3): the user's manual door — same
                        // bridge as the card, task = his latest message.
                        val task = viewModel.activeHomeTaskText()
                        if (task.isNotBlank()) {
                            onHomeSend(viewModel.homeThreadWorkPrompt(task))
                            val projectName = viewModel.state.value.activeProject?.name
                                ?: viewModel.state.value.projects.firstOrNull()?.name
                                ?: "Work"
                            viewModel.noteHomeTaskMoved(task, projectName)
                        }
                    },
                    // v3.2.38 (Part P): the pill's in-place picker — one
                    // shared state with "Chat replies use" (setChatProvider).
                    chatChoices = viewModel.chatProviderChoices(),
                    onPickChatProvider = viewModel::setChatProvider,
                    onPickChatModel = { kind, model ->
                        viewModel.setChatProvider(kind)
                        viewModel.setChatModel(kind, model)
                    },
                    fetchChatModels = viewModel::chatPickerModels,
                    onManageProviders = { screen = RootScreen.AGENT },
                    onGitHubBuild = onOpenCloudHybrid,
                    onOpenTerminal = { showQuickTerminal = true },
                    onOpenAppFactory = onOpenAppFactory,
                )
                RootScreen.CHATS -> ChatsScreen(
                    listState = chatsListState,
                    queryState = chatsQueryState,
                    filterPinnedState = chatsFilterPinnedState,
                    projects = state.projects,
                    pinnedIds = pinnedProjectIds,
                    onOpenDrawer = { drawerOpen = true },
                    onOpenProject = { project -> viewModel.openProject(project) },
                    onNewChat = { viewModel.createQuickProject() },
                    onDeleteProjects = { ids -> ids.forEach { viewModel.deleteProject(it) } },
                    homeThreads = state.homeThreads,
                    onOpenHomeThread = { thread ->
                        viewModel.openHomeThread(thread.id)
                        screen = RootScreen.CHAT_HOME
                    },
                    onDeleteHomeThread = { thread -> viewModel.deleteHomeThread(thread.id) },
                )
                RootScreen.CODE -> CodeHubScreen(
                    onBack = navigateBack,
                    onOpenTerminal = { showQuickTerminal = true },
                    onOpenGitHubBuild = onOpenCloudHybrid,
                    onOpenGitHubStudio = onOpenGitHubStudio,
                    onOpenProjects = {
                        screenOrigins[RootScreen.PROJECTS] = RootScreen.CODE
                        screen = RootScreen.PROJECTS
                    },
                    onOpenTeamStudio = onOpenTeamStudio,
                )
                RootScreen.PROJECTS -> ProjectsScreen(
                    state = state,
                    listState = projectsListState,
                    onOpen = viewModel::openProject,
                    onCreate = viewModel::createProject,
                    onScaffoldTemplate = viewModel::scaffoldProjectTemplate,
                    onCreateQuickProject = viewModel::createQuickProject,
                    onImportZip = viewModel::importZipProject,
                    onImportFolder = viewModel::importFolderProject,
                    onCloneGit = viewModel::clonePublicGitRepository,
                    onCancelGitClone = viewModel::cancelGitClone,
                    onStartGitHubLogin = viewModel::startGitHubLogin,
                    onGenerateNewGitHubCode = viewModel::generateNewGitHubCode,
                    onRefreshGitHub = viewModel::refreshGitHubRepositories,
                    onDisconnectGitHub = viewModel::disconnectGitHub,
                    onCloneGitHub = viewModel::cloneGitHubRepository,
                    onRenameProject = viewModel::renameProject,
                    onDeleteProject = viewModel::deleteProject,
                    onSettings = { screen = RootScreen.SETTINGS },
                    onPing = viewModel::pingApi,
                    onToggleTheme = viewModel::toggleTheme,
                    onShowUpdateDialog = onShowAppUpdate,
                    onOpenAppFactory = onOpenAppFactory,
                    onOpenTeamStudio = onOpenTeamStudio,
                    onOpenCloudHybrid = onOpenCloudHybrid,
                    onOpenChatHome = { screen = RootScreen.CHAT_HOME },
                )
                RootScreen.AGENT -> AgentScreen(
                    listState = providersListState,
                    state = state,
                    onSaveProvider = { profile, key ->
                        viewModel.updateProvider(profile, key)
                    },
                    onDiscoverModels = viewModel::discoverModels,
                    onValidateProvider = viewModel::validateProvider,
                    catalogChatDefaults = viewModel::catalogChatDefaults,
                    onChatEndpointCorrected = viewModel::saveChatEndpointOverride,
                    onPing = viewModel::pingApi,
                    getSavedApiKey = viewModel::getSavedApiKey,
                    getSavedApiKeys = viewModel::getSavedApiKeys,
                    onAddApiKey = viewModel::addApiKey,
                    onActivateApiKey = viewModel::activateApiKey,
                    onRemoveApiKey = viewModel::removeApiKey,
                    chatProviderKind = viewModel.resolvedChatProvider(),
                    onSelectChatProvider = viewModel::setChatProvider,
                    autoSwitchFor = { kind -> viewModel.autoSwitchKeys(kind) },
                    onToggleAutoSwitch = { kind, on -> viewModel.setAutoSwitchKeys(kind, on) },
                    keyExhaustedAt = { kind, id -> viewModel.keyExhaustedAt(kind, id) },
                    onSelectAgent = viewModel::selectAgent,
                    onInstallAgent = viewModel::installAgent,
                    onCancelAgentInstall = viewModel::cancelAgentInstall,
                    onCheckAgentUpdates = viewModel::checkAgentUpdates,
                    onUpdateAgent = viewModel::updateAgent,
                    onCancelAgentUpdate = { viewModel.cancelAgentUpdate() },
                    onRefreshInstalledAgents = viewModel::refreshInstalledAgents,
                    onStartAntigravityLogin = viewModel::startAntigravityLogin,
                    onSubmitAntigravityCode = viewModel::submitAntigravityCode,
                    onLogoutAntigravity = viewModel::logoutAntigravity,
                    onRefreshAntigravityModels = viewModel::refreshAntigravityModels,
                    onSetAntigravityModel = viewModel::setAntigravityModel,
                    onSetAntigravityEffort = viewModel::setAntigravityEffort,
                    onStartAntigravityAddAccount = viewModel::startAntigravityAddAccount,
                    onSwitchAntigravityAccount = viewModel::switchAntigravityAccount,
                    onRemoveAntigravityAccount = viewModel::removeAntigravityAccount,
                    onToggleAntigravityAutoRoundRobin = viewModel::toggleAntigravityAutoRoundRobin,
                    onToggleGeminiApiMode = viewModel::setGeminiApiMode,
                    onSaveGeminiApiKey = viewModel::saveGeminiApiKey,
                    onClearGeminiApiKey = viewModel::clearGeminiApiKey,
                    onSetGeminiApiModel = viewModel::setGeminiApiModel,
                    teamOrchestrator = viewModel.teamOrchestrator,
                )
                RootScreen.SKILLS -> key(skillsTabEpoch) { SkillsScreen(
                    skillsListState = skillsListState,
                    mcpListState = mcpListState,
                    queryState = skillsQueryState,
                    onBack = navigateBack,
                    installed = state.skills,
                    results = state.skillSearchResults,
                    busy = state.skillsBusy,
                    message = state.skillsMessage,
                    skillUpdates = state.skillUpdates,
                    onSearch = viewModel::searchSkills,
                    onImportGitHub = viewModel::importSkillFromGitHub,
                    onScanGitHub = viewModel::scanGitHubSkill,
                    scanReport = state.skillScanReport,
                    scanBusy = state.skillScanBusy,
                    onInstallScanned = viewModel::installScannedSkill,
                    onDismissScan = viewModel::dismissSkillScan,
                    onImportZip = viewModel::importSkillFromUri,
                    onImportFolder = viewModel::importSkillFromFolder,
                    onRemove = viewModel::removeSkill,
                    onToggleSkill = viewModel::toggleSkill,
                    onCheckUpdates = viewModel::checkSkillUpdates,
                    onUpdate = viewModel::updateSkill,
                    autoUpdateEnabled = state.autoUpdateToolsAndSkills,
                    onToggleAutoUpdate = viewModel::toggleAutoUpdateToolsAndSkills,
                    autoUpdateStatus = state.toolsAutoUpdateMessage,
                    mcpServers = state.mcpServers,
                    mcpCatalog = state.mcpCatalog,
                    mcpTools = state.mcpTools,
                    mcpServerErrors = state.mcpServerErrors,
                    mcpServersMissingKeys = state.mcpServersMissingKeys,
                    mcpDiscoveringServerIds = state.mcpDiscoveringServerIds,
                    onSaveMcpServerSecrets = viewModel::saveMcpServerSecrets,
                    mcpAuditLogs = state.mcpAuditLogs,
                    mcpBusy = state.mcpBusy,
                    mcpMessage = state.mcpMessage,
                    onInstallMcpTemplate = viewModel::installMcpTemplate,
                    onInstallCustomMcp = { server, secrets -> viewModel.installMcpServer(server, secrets) },
                    onToggleMcpServer = viewModel::toggleMcpServer,
                    onRemoveMcpServer = viewModel::removeMcpServer,
                    onDiscoverMcpTools = viewModel::discoverMcpTools,
                    onClearMcpAuditLogs = viewModel::clearMcpAuditLogs,
                    onCheckMcpToolPermission = viewModel::mcpToolDecision,
                    onRunMcpTool = viewModel::runMcpTool,
                    mcpRunResult = state.mcpRunResult,
                    mcpRunTarget = state.mcpRunTarget,
                    mcpRunRunning = state.mcpRunRunning,
                    onClearMcpRunResult = viewModel::clearMcpRunResult,
                    initialTab = skillsInitialTab,
                ) }
                RootScreen.CONNECTORS -> Column(Modifier.fillMaxSize()) {
                    ConnectorsHeader(
                        serverCount = state.mcpServers.size,
                        toolCount = state.mcpToolCounts.values.sum(),
                        onBack = navigateBack,
                        onBrowse = {
                            screenOrigins[RootScreen.MARKETPLACE] = RootScreen.CONNECTORS
                            screen = RootScreen.MARKETPLACE
                        },
                        onAddCustom = {
                            skillsInitialTab = 1
                            skillsTabEpoch++
                            screenOrigins[RootScreen.SKILLS] = RootScreen.CONNECTORS
                            screen = RootScreen.SKILLS
                        },
                    )
                    key(skillsTabEpoch) { SkillsScreen(
                    skillsListState = skillsListState,
                    mcpListState = mcpListState,
                    queryState = skillsQueryState,
                    installed = state.skills,
                    results = state.skillSearchResults,
                    busy = state.skillsBusy,
                    message = state.skillsMessage,
                    skillUpdates = state.skillUpdates,
                    onSearch = viewModel::searchSkills,
                    onImportGitHub = viewModel::importSkillFromGitHub,
                    onScanGitHub = viewModel::scanGitHubSkill,
                    scanReport = state.skillScanReport,
                    scanBusy = state.skillScanBusy,
                    onInstallScanned = viewModel::installScannedSkill,
                    onDismissScan = viewModel::dismissSkillScan,
                    onImportZip = viewModel::importSkillFromUri,
                    onImportFolder = viewModel::importSkillFromFolder,
                    onRemove = viewModel::removeSkill,
                    onToggleSkill = viewModel::toggleSkill,
                    onCheckUpdates = viewModel::checkSkillUpdates,
                    onUpdate = viewModel::updateSkill,
                    autoUpdateEnabled = state.autoUpdateToolsAndSkills,
                    onToggleAutoUpdate = viewModel::toggleAutoUpdateToolsAndSkills,
                    autoUpdateStatus = state.toolsAutoUpdateMessage,
                    mcpServers = state.mcpServers,
                    mcpCatalog = state.mcpCatalog,
                    mcpTools = state.mcpTools,
                    mcpServerErrors = state.mcpServerErrors,
                    mcpServersMissingKeys = state.mcpServersMissingKeys,
                    mcpDiscoveringServerIds = state.mcpDiscoveringServerIds,
                    onSaveMcpServerSecrets = viewModel::saveMcpServerSecrets,
                    mcpAuditLogs = state.mcpAuditLogs,
                    mcpBusy = state.mcpBusy,
                    mcpMessage = state.mcpMessage,
                    onInstallMcpTemplate = viewModel::installMcpTemplate,
                    onInstallCustomMcp = { server, secrets -> viewModel.installMcpServer(server, secrets) },
                    onToggleMcpServer = viewModel::toggleMcpServer,
                    onRemoveMcpServer = viewModel::removeMcpServer,
                    onDiscoverMcpTools = viewModel::discoverMcpTools,
                    onClearMcpAuditLogs = viewModel::clearMcpAuditLogs,
                    onCheckMcpToolPermission = viewModel::mcpToolDecision,
                    onRunMcpTool = viewModel::runMcpTool,
                    mcpRunResult = state.mcpRunResult,
                    mcpRunTarget = state.mcpRunTarget,
                    mcpRunRunning = state.mcpRunRunning,
                    onClearMcpRunResult = viewModel::clearMcpRunResult,
                    initialTab = 1,
                ) }
                }
                RootScreen.SETTINGS -> ClaudeSettingsHome(
                    listState = settingsListState,
                    accountName = hostUser?.displayName?.ifBlank { null }
                        ?: hostUser?.email
                        ?: if (hostUser != null) "Signed in" else "Guest mode",
                    accountEmail = hostUser?.email,
                    isGuest = hostUser == null,
                    colorMode = state.themeMode,
                    fontStyle = state.fontStyle,
                    hapticsEnabled = hapticsEnabled,
                    connectorsValue = "${state.mcpServers.size} connected",
                    onBack = {
                        screen = RootScreen.CHAT_HOME
                        drawerOpen = true
                    },
                    onLogin = onNavigateToAuth,
                    onOpenProfile = { screen = RootScreen.PROFILE },
                    onOpenLicense = { screen = RootScreen.LICENSE_PAGE },
                    onOpenCapabilities = { screen = RootScreen.CAPABILITIES },
                    onOpenConnectors = {
                        screenOrigins[RootScreen.CONNECTORS] = RootScreen.SETTINGS
                        screen = RootScreen.CONNECTORS
                    },
                    onOpenPermissions = { settingsFocus = "permissions"; screen = RootScreen.SECTION_PAGE },
                    onColorMode = viewModel::setThemeMode,
                    onFontStyle = viewModel::setFontStyle,
                    onOpenVoice = { screen = RootScreen.VOICE_SETTINGS },
                    onToggleHaptics = { enabled ->
                        hapticsEnabled = enabled
                        appPreferences.hapticsEnabled = enabled
                    },
                    onOpenNotifications = { screen = RootScreen.NOTIFICATIONS_PAGE },
                    onOpenDeveloperTools = { settingsFocus = "tools"; screen = RootScreen.SECTION_PAGE },
                    onOpenRuntime = { settingsFocus = "runtime"; screen = RootScreen.SECTION_PAGE },
                    onOpenEnvironment = { settingsFocus = "environment"; screen = RootScreen.SECTION_PAGE },
                    onOpenDeveloper = { settingsFocus = "developer"; screen = RootScreen.SECTION_PAGE },
                    onOpenPrivacy = { showPrivacyPolicy = true },
                    onOpenAbout = { settingsFocus = "about"; screen = RootScreen.SECTION_PAGE },
                    onLogout = { authViewModel?.signOut() },
                    appUpdateAvailable = state.appUpdate != null,
                    currentVersion = BuildConfig.VERSION_NAME,
                    // v3.2.38 (Part K): App updates is a real page now —
                    // the full update system (check + install) in place,
                    // not just the root dialog.
                    onOpenAppUpdates = { settingsFocus = "updates"; screen = RootScreen.SECTION_PAGE },
                )
                RootScreen.CAPABILITIES -> CapabilitiesScreen(
                    onBack = { screen = RootScreen.SETTINGS },
                    autoUpdateSkills = state.autoUpdateToolsAndSkills,
                    onToggleAutoUpdateSkills = viewModel::toggleAutoUpdateToolsAndSkills,
                    freeMode = state.freeModeEnabled,
                    onToggleFreeMode = viewModel::setFreeModeEnabled,
                )
                RootScreen.VOICE_SETTINGS -> VoiceSettingsScreen(
                    onBack = { screen = RootScreen.SETTINGS },
                )
                RootScreen.MARKETPLACE -> MarketplaceScreen(
                    listState = marketplaceListState,
                    searchQueryState = marketplaceQueryState,
                    categoryState = marketplaceCategoryState,
                    installedOnlyState = marketplaceInstalledOnlyState,
                    sortState = marketplaceSortState,
                    onBack = navigateBack,
                    onInstallItem = { item -> viewModel.installMarketplaceItem(item) },
                    onUninstallItem = { item -> viewModel.uninstallMarketplaceItem(item) },
                    onPublishItem = { item -> viewModel.publishMarketplaceItem(item) },
                    initiallyInstalledIds = marketplaceInstalledIds,
                    updatesAvailable = marketplaceUpdatesAvailable,
                    onUpdateItem = { item -> viewModel.updateMarketplaceItem(item) },
                    onScreenOpened = { viewModel.checkMarketplaceUpdates() },
                )
                RootScreen.PROFILE -> ProfilePage(
                    accountName = hostUser?.displayName?.ifBlank { null } ?: hostUser?.email ?: if (hostUser != null) "Signed in" else "Guest mode",
                    accountEmail = hostUser?.email,
                    isGuest = hostUser == null,
                    authViewModel = authViewModel,
                    authState = hostAuthState,
                    onBack = { screen = RootScreen.SETTINGS },
                    onNavigateToAuth = onNavigateToAuth,
                )
                RootScreen.LICENSE_PAGE -> LicensePage(onBack = { screen = RootScreen.SETTINGS })
                RootScreen.NOTIFICATIONS_PAGE -> NotificationsPage(onBack = { screen = RootScreen.SETTINGS })
                // v3.2.38 (Part K): the classic Settings monolith no
                // longer exists as a destination — every legacy function
                // group has its own page in the new Settings. This branch
                // only catches a navigation state saved by an older
                // version and lands it safely on the new Settings home.
                RootScreen.LEGACY_SETTINGS -> {
                    LaunchedEffect(Unit) { screen = RootScreen.SETTINGS }
                    Box(Modifier.fillMaxSize())
                }
                RootScreen.SECTION_PAGE -> SettingsScreen(
                    focusListState = settingsFocusListState,
                    state = state,
                    onSaveProvider = { profile, key ->
                        viewModel.updateProvider(profile, key)
                    },
                    onDiscoverModels = viewModel::discoverModels,
                    onValidateProvider = viewModel::validateProvider,
                    onSetThemeMode = viewModel::setThemeMode,
                    onPing = viewModel::pingApi,
                    onClearTerminal = viewModel::clearTerminal,
                    getSavedApiKey = viewModel::getSavedApiKey,
                    getSavedApiKeys = viewModel::getSavedApiKeys,
                    onAddApiKey = viewModel::addApiKey,
                    onActivateApiKey = viewModel::activateApiKey,
                    onRemoveApiKey = viewModel::removeApiKey,
                    onInstallDevStack = viewModel::installDevStack,
                    onUpdateDevStack = viewModel::updateDevStack,
                    onRemoveDevStack = viewModel::removeDevStack,
                    onRefreshInstalledAgents = viewModel::refreshInstalledAgents,
                    onStartAntigravityLogin = viewModel::startAntigravityLogin,
                    onSubmitAntigravityCode = viewModel::submitAntigravityCode,
                    onLogoutAntigravity = viewModel::logoutAntigravity,
                    onRefreshAntigravityModels = viewModel::refreshAntigravityModels,
                    onSetAntigravityModel = viewModel::setAntigravityModel,
                    onSetAntigravityEffort = viewModel::setAntigravityEffort,
                    onStartAntigravityAddAccount = viewModel::startAntigravityAddAccount,
                    onSwitchAntigravityAccount = viewModel::switchAntigravityAccount,
                    onRemoveAntigravityAccount = viewModel::removeAntigravityAccount,
                    onToggleAntigravityAutoRoundRobin = viewModel::toggleAntigravityAutoRoundRobin,
                    initialUpdateManifestUrl = viewModel.updateManifestUrl().ifBlank { viewModel.debugUpdateManifestUrl() },
                    initialUpdateAuthToken = viewModel.updateAuthToken(),
                    onSetUpdateManifestUrl = viewModel::setUpdateManifestUrl,
                    onClearUpdateManifestUrl = viewModel::clearUpdateManifestUrl,
                    onSetUpdateAuthToken = viewModel::setUpdateAuthToken,
                    onCheckForUpdate = { viewModel.checkForAppUpdate(force = true) },
                    onInstallAppUpdate = viewModel::installAppUpdate,
                    onShowAppUpdateDialog = onShowAppUpdate,
                    initialDebugUpdateManifestUrl = viewModel.debugUpdateManifestUrl(),
                    onSetDebugUpdateManifestUrl = viewModel::setDebugUpdateManifestUrl,
                    onClearDebugUpdateManifestUrl = viewModel::clearDebugUpdateManifestUrl,
                    onToggleAutoUpdateToolsAndSkills = viewModel::toggleAutoUpdateToolsAndSkills,
                    onToggleFreeMode = viewModel::setFreeModeEnabled,
                    onUpdateAllToolsAndSkills = { viewModel.autoUpdateToolsAndSkills(force = true) },
                    onOpenPrivacyPolicy = { showPrivacyPolicy = true },
                    authViewModel = authViewModel,
                    onNavigateToAuth = onNavigateToAuth,
                    onBackupWorkspaces = { viewModel.backupAllWorkspaces() },
                    onRestoreWorkspaces = { viewModel.restoreAllWorkspaces() },
                    onTogglePermissionPolicy = viewModel::updatePermissionPolicy,
                    onClearSecurityAuditLogs = viewModel::clearSecurityAuditLogs,
                    onReplayAppTour = viewModel::replayAppTour,
                    // v3.2.38 (Part K): one focus page per legacy function
                    // group — the only way SettingsScreen is ever composed.
                    focusSections = when (settingsFocus) {
                        "tools" -> setOf(SettingsSection.TOOLS)
                        "runtime" -> setOf(SettingsSection.RUNTIME)
                        "environment" -> setOf(SettingsSection.ENVIRONMENT)
                        "permissions" -> setOf(SettingsSection.PERMISSIONS)
                        "updates" -> setOf(SettingsSection.UPDATE_CHANNEL)
                        "developer" -> setOf(SettingsSection.CONTRIBUTORS)
                        else -> setOf(SettingsSection.ABOUT)
                    },
                    focusTitle = when (settingsFocus) {
                        "tools" -> "Developer tools"
                        "runtime" -> "Linux runtime"
                        "environment" -> "Environment updates"
                        "permissions" -> "Permissions"
                        "updates" -> "App updates"
                        "developer" -> "Developer"
                        else -> "About"
                    },
                    onFocusBack = { screen = RootScreen.SETTINGS },
                )
            }
        }
    }
}
    if (homeCallActive) {
        val liveKeyConnected = viewModel.hasGoogleAiStudioKey()
        when {
            homeCallEngineLive && liveKeyConnected -> LiveCallScreen(
                viewModel = viewModel,
                engineSwitcher = {
                    CallEngineSwitcher(
                        liveSelected = true,
                        onSelect = { live -> if (!live) homeCallEngineLive = false },
                    )
                },
                onClose = { homeCallActive = false },
            )
            homeCallEngineLive -> LiveCallUnavailableScreen(
                onClose = { homeCallActive = false },
                onUseStandard = { homeCallEngineLive = false },
                onOpenProviders = {
                    homeCallActive = false
                    screen = RootScreen.AGENT
                },
            )
            else -> VoiceCallScreen(
                recognizer = homeCallRecognizer,
                messages = homeCallMessages,
                isRunning = state.homeSending,
                approval = null,
                onSend = { text -> viewModel.sendHomeMessage(text) },
                onApproval = {},
                onApprovalWithLevel = { _, _ -> },
                onClose = { homeCallActive = false },
                onRequestMicPermission = { homeCallPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                fastReply = true,
                headerCenter = {
                    CallEngineSwitcher(
                        liveSelected = false,
                        onSelect = { live -> if (live) homeCallEngineLive = true },
                    )
                },
            )
        }
    }
    if (drawerMounted) {
        KodexSidebar(
            visible = drawerOpen,
            progressAnim = drawerProgressAnim,
            onOpenWork = {
                drawerOpen = false
                screenOrigins.remove(RootScreen.PROJECTS)
                screen = RootScreen.PROJECTS
            },
            userInitial = (hostUser?.displayName?.firstOrNull() ?: hostUser?.email?.firstOrNull() ?: 'K')
                .uppercaseChar().toString(),
            userName = hostUser?.displayName?.ifBlank { null } ?: hostUser?.email ?: "Guest",
            projects = state.projects,
            pinnedIds = pinnedProjectIds,
            activeProjectId = state.activeProject?.id,
            pushEnabled = true,
            onClose = { drawerOpen = false },
            onNewChat = {
                drawerOpen = false
                viewModel.createQuickProject()
            },
            onOpenChats = { screen = RootScreen.CHATS },
            onOpenProjects = {
                screenOrigins.remove(RootScreen.PROJECTS)
                screen = RootScreen.PROJECTS
            },
            onOpenCode = { screen = RootScreen.CODE },
            onOpenProviders = { screen = RootScreen.AGENT },
            onOpenSkills = {
                skillsInitialTab = 0
                skillsTabEpoch++
                screenOrigins.remove(RootScreen.SKILLS)
                screen = RootScreen.SKILLS
            },
            onOpenMarketplace = {
                screenOrigins.remove(RootScreen.MARKETPLACE)
                screen = RootScreen.MARKETPLACE
            },
            onOpenConnectors = {
                screenOrigins.remove(RootScreen.CONNECTORS)
                screen = RootScreen.CONNECTORS
            },
            onOpenAppFactory = onOpenAppFactory,
            onOpenProject = { project -> viewModel.openProject(project) },
            onTogglePin = togglePin,
            onRenameProject = { project, newName -> viewModel.renameProject(project.id, newName) },
            onDeleteProject = { project -> viewModel.deleteProject(project.id) },
            onPushToGitHub = onPushRequest,
            onOpenGitHubBuild = onOpenCloudHybrid,
            onOpenTerminal = { showQuickTerminal = true },
            onOpenSettings = {
                drawerOpen = false
                screen = RootScreen.SETTINGS
            },
        )
    }
    if (showQuickTerminal) {
        QuickTerminalSheet(
            onDismiss = { showQuickTerminal = false },
        ) {
            TerminalScreen(
                lines = terminalLines,
                isRunning = isTerminalRunning,
                onRun = viewModel::runTerminalCommand,
                onInput = viewModel::sendTerminalInput,
                onInterrupt = viewModel::interruptTerminalCommand,
                onClear = viewModel::clearTerminal,
                onToggleTheme = viewModel::toggleTheme,
                themeMode = state.themeMode,
                liveOutput = terminalLiveOutput,
                currentCommand = terminalCurrentCommand,
                showThemeAction = false,
                showQuickCommands = true,
                compactHeader = true,
            )
        }
    }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun QuickTerminalSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    // v3.2.39 (Scope 6): this sheet used to be hand-rolled — it appeared
    // with NO open animation, its height lived in a plain float the drag
    // rewrote directly, an imePadding container shrank it from outside,
    // and a LaunchedEffect SNAPPED it from 45% to 85% the moment the
    // keyboard appeared. Every keyboard change was a visible jump.
    // It is a Material ModalBottomSheet now: one remembered sheet state,
    // a FIXED content height the keyboard cannot change, and M3's own
    // smooth open / close / drag physics. The terminal keeps both
    // resting heights (partial + expanded), like the old 45% / 85%.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSetupScreen(
    initial: ProviderProfile,
    onboarding: Boolean,
    agentKind: AgentKind = AgentKind.CLAUDE_CODE,
    initialStep: Int = if (onboarding) 0 else 1,
    onBack: (() -> Unit)? = null,
    onSave: (ProviderProfile, String) -> Unit,
    onDiscover: suspend (ProviderProfile, String) -> ModelDiscoveryResult,
    onValidate: suspend (ProviderProfile, String, List<DiscoveredModel>) -> ConnectionValidation,
    onSelectAgent: (AgentKind) -> Unit,
    onToggleTheme: (() -> Unit)? = null,
    themeMode: AppThemeMode = AppThemeMode.DARK,
    freeModeEnabled: Boolean = false,
) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(initialStep) }
    var selected by rememberSaveable { mutableStateOf(initial.kind) }
    var baseUrl by rememberSaveable { mutableStateOf(initial.baseUrl.ifBlank { initial.kind.defaultBaseUrl }) }
    var model by rememberSaveable { mutableStateOf(initial.model.ifBlank { initial.kind.defaultModel }) }
    var dshApi by rememberSaveable { mutableStateOf(initial.dshApi.ifBlank { "anthropic-messages" }) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var showAgentPicker by rememberSaveable { mutableStateOf(false) }

    if (showAgentPicker) {
        AgentSwitchSheet(
            selected = agentKind,
            onSelect = { agent ->
                showAgentPicker = false
                onSelectAgent(agent)
            },
            onDismiss = { showAgentPicker = false },
            freeModeEnabled = freeModeEnabled,
        )
    }

    val handleBack: (() -> Unit)? = when {
        step > 1 -> { { step = 1 } }
        !onboarding && onBack != null -> onBack
        else -> null
    }

    if (handleBack != null) {
        BackHandler(onBack = handleBack)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (onboarding) "Set up Kodex" else "AI Provider & Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (handleBack != null) {
                        IconButton(onClick = handleBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    if (onToggleTheme != null) {
                        IconButton(onClick = onToggleTheme) {
                            Icon(
                                if (themeMode == AppThemeMode.DARK) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Toggle theme",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            // Medium: the provider form used to clip on short screens; the
            // keyboard also covered the API-key field.
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).imePadding(),
        ) {
            if (onboarding) StepDots(step)
            Spacer(Modifier.height(16.dp))
            when (step) {
                0 -> DeviceCheckStep(context, onContinue = { step = 1 })
                1 -> ProviderChoiceStep(
                    selected = selected,
                    agentKind = agentKind,
                    onSelected = {
                        if (selected != it) {
                            selected = it
                            baseUrl = it.defaultBaseUrl
                            model = it.defaultModel
                            apiKey = ""
                        }
                    },
                    onContinue = { step = 2 },
                    onChangeAgent = { showAgentPicker = true },
                    freeModeEnabled = freeModeEnabled,
                )
                else -> ProviderCredentialsStep(
                    provider = selected,
                    agentKind = agentKind,
                    baseUrl = baseUrl,
                    model = model,
                    dshApi = dshApi,
                    apiKey = apiKey,
                    onBaseUrl = {
                        baseUrl = it
                        if (agentKind == AgentKind.DEEPSEEK_HARNESS && selected == ProviderKind.CUSTOM) {
                            dshApi = inferredDshApiForUrl(it)
                        }
                    },
                    onModel = { model = it },
                    onDshApi = { dshApi = it },
                    onApiKey = { apiKey = it },
                    hasStoredSecret = initial.kind == selected && initial.hasSecret,
                    onDiscover = {
                        val url = if (selected.fixedBaseUrl) selected.defaultBaseUrl else baseUrl.trim()
                        onDiscover(ProviderProfile(selected, url, model.trim(), dshApi = dshApi), apiKey)
                    },
                    onValidate = { models ->
                        val url = if (selected.fixedBaseUrl) selected.defaultBaseUrl else baseUrl.trim()
                        onValidate(ProviderProfile(selected, url, model.trim(), dshApi = dshApi), apiKey, models)
                    },
                    onSave = {
                        val url = if (selected.fixedBaseUrl) selected.defaultBaseUrl else baseUrl.trim()
                        onSave(ProviderProfile(selected, url, model.trim(), dshApi = dshApi), apiKey)
                    },
                    onChangeAgent = { showAgentPicker = true },
                )
            }
        }
    }
}

@Composable
private fun DshApiProtocolPicker(selected: String, onSelected: (String) -> Unit) {
    val options = listOf("anthropic-messages", "openai-completions", "openai-responses")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Gateway protocol",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        options.forEach { option ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onSelected(option) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(19.dp)
                        .border(
                            width = if (selected == option) 2.dp else 1.dp,
                            color = if (selected == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected == option) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                }
                Spacer(Modifier.width(10.dp))
                Text(option, fontSize = 13.sp)
            }
        }
        Text(
            "Pick the protocol your gateway speaks; DeepSeek Harness routes it directly.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun StepDots(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { index ->
            Box(
                Modifier.height(5.dp).weight(1f)
                    .background(if (index <= step) PocketOrange else MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
        }
    }
}

@Composable
private fun DeviceCheckStep(context: Context, onContinue: () -> Unit) {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val memoryInfo = remember { ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo) }
    val totalRamGb = memoryInfo.totalMem.toDouble() / 1_073_741_824.0
    val totalRamLabel = String.format(java.util.Locale.US, "%.1f", totalRamGb)
    val arm64 = supportsArm64Runtime(Build.SUPPORTED_ABIS, System.getProperty("os.arch"))
    val compatible = arm64
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        BrandMark()
        Text("Your phone is the workspace", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily), fontWeight = FontWeight.Normal)
        Text("Kodex checks compatibility before downloading the private Linux runtime.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        CheckRow(Icons.Default.Memory, "Memory", "$totalRamLabel GB usable · ${if (totalRamGb >= 7.5) "Full mode" else "Lite mode"}", true)
        CheckRow(Icons.Default.Code, "Processor", Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown", arm64)
        CheckRow(Icons.Default.Storage, "Android", "Android ${Build.VERSION.RELEASE}", true)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
            Text(
                "Only open projects you trust. The local Linux environment is a compatibility layer, not a hardened security sandbox.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onContinue, enabled = compatible, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (compatible) "Continue" else "This device is not supported")
        }
    }
}

@Composable
private fun CheckRow(icon: ImageVector, title: String, value: String, passed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Icon(icon, null, Modifier.padding(11.dp).size(22.dp), tint = if (passed) PocketGreen else MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Icon(if (passed) Icons.Default.Check else Icons.Default.Warning, null, tint = if (passed) PocketGreen else MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ProviderChoiceStep(
    selected: ProviderKind,
    agentKind: AgentKind,
    onSelected: (ProviderKind) -> Unit,
    onContinue: () -> Unit,
    onChangeAgent: () -> Unit,
    freeModeEnabled: Boolean = false,
) {
    val visibleProviders = remember(agentKind, freeModeEnabled) { providersForAgent(agentKind, freeModeEnabled) }
    Column(Modifier.fillMaxHeight()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "STEP 2 OF 3",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = PocketOrange,
                letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.weight(1f))
            Surface(
                color = PocketGreen.copy(alpha = 0.10f),
                shape = RoundedCornerShape(50),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Shield, null, tint = PocketGreen, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Secure setup", color = PocketGreen, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Connect your AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Choose how Kodex should access your coding model.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(visibleProviders) { index, provider ->
                    ProviderChoiceRow(
                        provider = provider,
                        selected = selected == provider,
                        onClick = { onSelected(provider) },
                    )
                    if (index != visibleProviders.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 12.dp, start = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                "API keys are encrypted in Android secure storage.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text("Continue", fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
        }
        TextButton(
            onClick = onChangeAgent,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp),
        ) {
            Text("Use another coding agent", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProviderChoiceRow(
    provider: ProviderKind,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = when (provider) {
        ProviderKind.CLAUDE -> Color(0xFFD97757)
        ProviderKind.ANTHROPIC -> Color(0xFFE7A26D)
        ProviderKind.LLM_ROUTER -> Color(0xFF5B8DEF)
        ProviderKind.DEEPSEEK -> Color(0xFF4D6BFE)
        ProviderKind.KIMI -> Color(0xFF8B7CF6)
        ProviderKind.OPENCODE_ZEN -> Color(0xFF22C55E)
        ProviderKind.NVIDIA_NIM -> Color(0xFF76B900)
        ProviderKind.OPENAI -> Color(0xFF10A37F)
        ProviderKind.GROQ -> Color(0xFFE4572E)
        ProviderKind.GOOGLE_AI_STUDIO -> Color(0xFF7C9FF5)
        ProviderKind.MISTRAL -> Color(0xFFFF7000)
        ProviderKind.CEREBRAS -> Color(0xFF6290C8)
        ProviderKind.COHERE -> Color(0xFF39594D)
        ProviderKind.XAI -> Color(0xFFB8B8B8)
        ProviderKind.Z_AI -> Color(0xFF3E7CB1)
        ProviderKind.REQUESTY -> Color(0xFF7A6FF0)
        ProviderKind.OLLAMA_CLOUD -> Color(0xFF9A9A92)
        ProviderKind.HUGGING_FACE -> Color(0xFFD9A404)
        ProviderKind.LLM7 -> Color(0xFF4C9F70)
        ProviderKind.CUSTOM -> PocketOrange
    }
    val mark = when (provider) {
        ProviderKind.CLAUDE -> "C"
        ProviderKind.ANTHROPIC -> "A"
        ProviderKind.LLM_ROUTER -> "OR"
        ProviderKind.DEEPSEEK -> "DS"
        ProviderKind.KIMI -> "K"
        ProviderKind.OPENCODE_ZEN -> "Z"
        ProviderKind.NVIDIA_NIM -> "NV"
        ProviderKind.OPENAI -> "AI"
        ProviderKind.GROQ -> "GQ"
        ProviderKind.GOOGLE_AI_STUDIO -> "GM"
        ProviderKind.MISTRAL -> "M"
        ProviderKind.CEREBRAS -> "CB"
        ProviderKind.COHERE -> "CO"
        ProviderKind.XAI -> "X"
        ProviderKind.Z_AI -> "ZA"
        ProviderKind.REQUESTY -> "RQ"
        ProviderKind.OLLAMA_CLOUD -> "OL"
        ProviderKind.HUGGING_FACE -> "HF"
        ProviderKind.LLM7 -> "L7"
        ProviderKind.CUSTOM -> "<>"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(accent.copy(alpha = 0.15f), RoundedCornerShape(9.dp))
                .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(mark, color = accent, fontSize = if (mark.length > 1) 9.sp else 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    provider.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (provider.experimental) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(5.dp),
                    ) {
                        Text(
                            "Beta",
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(1.dp))
            Text(
                provider.subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(19.dp)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCredentialsStep(
    provider: ProviderKind,
    agentKind: AgentKind = AgentKind.CLAUDE_CODE,
    baseUrl: String,
    model: String,
    dshApi: String = "anthropic-messages",
    apiKey: String,
    onBaseUrl: (String) -> Unit,
    onModel: (String) -> Unit,
    onDshApi: (String) -> Unit = {},
    onApiKey: (String) -> Unit,
    hasStoredSecret: Boolean,
    onDiscover: suspend () -> ModelDiscoveryResult,
    onValidate: suspend (List<DiscoveredModel>) -> ConnectionValidation,
    onSave: () -> Unit,
    onChangeAgent: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var models by remember(baseUrl) { mutableStateOf(emptyList<DiscoveredModel>()) }
    var isDiscovering by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusDetails by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var modelSearch by rememberSaveable { mutableStateOf("") }
    val modelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hasKey = apiKey.isNotBlank() || hasStoredSecret
    val filteredModels = remember(models, modelSearch) {
        val query = modelSearch.trim()
        if (query.isEmpty()) models else models.filter {
            it.id.contains(query, ignoreCase = true) || it.displayName.contains(query, ignoreCase = true)
        }
    }

    if (provider == ProviderKind.CLAUDE) {
        ClaudeSubscriptionCredentialsStep(
            token = apiKey,
            hasStoredToken = hasStoredSecret,
            onToken = onApiKey,
            onSave = onSave,
            onChangeAgent = onChangeAgent,
        )
        return
    }

    fun discoverModels(openWhenReady: Boolean = true) {
        scope.launch {
            isDiscovering = true
            status = null
            statusDetails = null
            when (val result = onDiscover()) {
                is ModelDiscoveryResult.Success -> {
                    models = result.models
                    statusOk = true
                    status = "Found ${result.models.size} available model${if (result.models.size == 1) "" else "s"}."
                    if (model.isBlank() && result.models.isNotEmpty()) onModel(result.models.first().id)
                    if (openWhenReady && result.models.isNotEmpty()) showModels = true
                }
                is ModelDiscoveryResult.Failure -> {
                    statusOk = false
                    status = result.message
                    statusDetails = result.providerMessage
                }
            }
            isDiscovering = false
        }
    }

    if (showModels) {
        ModalBottomSheet(
            onDismissRequest = { showModels = false },
            sheetState = modelSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f).padding(horizontal = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Available models", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "${filteredModels.size} of ${models.size} models",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                    IconButton(
                        onClick = { discoverModels(openWhenReady = false) },
                        enabled = !isDiscovering,
                    ) {
                        if (isDiscovering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "Refresh models")
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = modelSearch,
                    onValueChange = { modelSearch = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search model name or ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                if (filteredModels.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No matching models", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(filteredModels, key = { it.id }) { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onModel(option.id)
                                        status = null
                                        modelSearch = ""
                                        showModels = false
                                    }
                                    .padding(vertical = 14.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(option.displayName, modifier = Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (option.isFree) Text("  FREE", color = MaterialTheme.colorScheme.tertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    if (option.displayName != option.id) {
                                        Text(option.id, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Box(
                                    Modifier.size(20.dp).border(
                                        if (model == option.id) 2.dp else 1.dp,
                                        if (model == option.id) PocketOrange else MaterialTheme.colorScheme.outline,
                                        CircleShape,
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (model == option.id) Box(Modifier.size(9.dp).background(PocketOrange, CircleShape))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("STEP 3 OF 3", color = PocketOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text("Encrypted locally", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(provider.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(
                when {
                    agentKind == AgentKind.DEEPSEEK_HARNESS -> "DeepSeek Harness will connect through this API endpoint."
                    provider.protocol.name.startsWith("OPENAI") -> "Kodex will translate Claude Code requests for this provider."
                    else -> "Claude Code will connect through this API endpoint."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        baseUrl,
                        { onBaseUrl(it); status = null; statusDetails = null; models = emptyList() },
                        label = { Text("Base URL") },
                        supportingText = {
                            if (provider.fixedBaseUrl) Text("Fixed by ${provider.title}")
                        },
                        readOnly = provider.fixedBaseUrl,
                        enabled = !provider.fixedBaseUrl,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (agentKind == AgentKind.DEEPSEEK_HARNESS && provider in DSH_PROTOCOL_PROVIDERS && !provider.fixedProtocol) {
                        DshApiProtocolPicker(selected = dshApi, onSelected = { onDshApi(it); status = null })
                    }
                    OutlinedTextField(
                        apiKey,
                        { onApiKey(it); status = null; statusDetails = null },
                        label = { Text("API key") },
                        placeholder = { Text(if (hasStoredSecret) "Saved securely — leave blank to keep it" else "Enter your API key") },
                        supportingText = {
                            if (hasStoredSecret && apiKey.isBlank()) Text("A saved key is ready to use")
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        model,
                        { onModel(it); status = null; statusDetails = null },
                        label = { Text("Model name") },
                        supportingText = { Text("Select an available model or enter an exact model ID.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    if (models.isEmpty()) discoverModels() else showModels = true
                },
                enabled = baseUrl.isNotBlank() && hasKey && !isDiscovering && !isValidating,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (isDiscovering) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Icon(if (models.isEmpty()) Icons.Default.Search else Icons.Default.KeyboardArrowDown, null, Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (models.isEmpty()) "Find available models" else "Available models (${models.size})")
            }
        }
        if (status != null) {
            item {
                Text(
                    status.orEmpty(),
                    color = if (statusOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )
                statusDetails?.takeIf(String::isNotBlank)?.let { details ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        details,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
        }
        item {
            Button(
                    onClick = {
                        scope.launch {
                            isValidating = true
                            status = "Checking API key, model, and Claude Code settings…"
                            statusDetails = null
                            statusOk = true
                            when (val result = onValidate(models)) {
                                is ConnectionValidation.Success -> {
                                    status = result.message
                                    statusOk = true
                                    onSave()
                                }
                                is ConnectionValidation.Failure -> {
                                    status = result.message
                                    statusDetails = result.providerMessage
                                    statusOk = false
                                }
                            }
                            isValidating = false
                        }
                    },
                    enabled = baseUrl.isNotBlank() && model.isNotBlank() && hasKey && !isDiscovering && !isValidating,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    if (isValidating) {
                        CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(if (isValidating) "Checking" else "Continue")
            }
        }
        item {
            TextButton(
                onClick = onChangeAgent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use another coding agent", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ClaudeSubscriptionCredentialsStep(
    token: String,
    hasStoredToken: Boolean,
    onToken: (String) -> Unit,
    onSave: () -> Unit,
    onChangeAgent: () -> Unit,
) {
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    val hasToken = token.isNotBlank() || hasStoredToken

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("STEP 3 OF 3", color = PocketOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text("Encrypted locally", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Claude subscription", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Connect a Claude Pro, Max, Team, or Enterprise subscription to Claude Code.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("1. On a computer where Claude Code is installed, run:", fontSize = 13.sp)
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                        Text(
                            "claude setup-token",
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                            color = PocketOrange,
                        )
                    }
                    Text("2. Sign in to Claude and paste the generated token here.", fontSize = 13.sp)
                    OutlinedTextField(
                        value = token,
                        onValueChange = onToken,
                        label = { Text("Claude setup token") },
                        placeholder = { Text(if (hasStoredToken) "Saved securely — leave blank to keep it" else "Paste token") },
                        supportingText = if (hasStoredToken && token.isBlank()) ({ Text("A saved subscription token is ready to use") }) else null,
                        singleLine = true,
                        visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                Icon(if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle token visibility")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            Button(
                onClick = onSave,
                enabled = hasToken,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text("Save and continue")
            }
        }
        item {
            TextButton(onClick = onChangeAgent, modifier = Modifier.fillMaxWidth()) {
                Text("Use another coding agent", fontSize = 12.sp)
            }
        }
    }
}

/**
 * One-time explainer shown on the first open of each studio. One page per
 * tab/screen inside that studio, so the user learns every screen before
 * using it. Any dismissal (Got it, Skip, back press, outside tap) marks it
 * seen; the caller decides whether to proceed into the studio (Got it /
 * Skip) or stay put (exit).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioOnboardingDialog(
    studio: OverlayScreen,
    onProceed: () -> Unit,
    onExit: () -> Unit,
) {
    val pages = when (studio) {
        OverlayScreen.APP_FACTORY -> listOf(
            "App Factory" to "App Factory turns one idea into a Play Store-ready app: blueprint, code, build, signing, and listing, all from your phone.",
            "Blueprint" to "Describe your app idea. AI turns it into a blueprint: features, screens, and data model.",
            "Code & DB" to "AI writes the app code and builds the SQLite database from your blueprint.",
            "Build & CI" to "Build a real APK on your device, or with CI when you need more power.",
            "Signing & Version" to "Sign your APK with your keystore and manage the version code and version name.",
            "Play Store" to "Fill in your Play listing and ship the release to the Play Store.",
        )
        OverlayScreen.TEAM_STUDIO -> listOf(
            "Multi-Agent Studio" to "A team of up to 10 AI engineering agents works on one goal together. Every tab below shows one part of how the team works.",
            "Roster" to "Your team: 10 specialized agent roles. See who is on the team and what each one does.",
            "Task DAG" to "Plan turns your goal into a task pipeline. Agents run it, and Retry failed re-runs only the failed tasks.",
            "Comms" to "Messages between agents, and between you and the team, in one place.",
            "Blackboard" to "Every artifact the agents produce: files, notes, and results, pinned on one shared board.",
            "Reviews" to "Reviewer sign-offs on finished work, before anything is marked done.",
        )
        OverlayScreen.CLOUD_HYBRID -> listOf(
            "Cloud Hybrid" to "Phone and cloud work as one team: heavy jobs move to fast cloud runners while your phone stays light.",
            "Telemetry & Stats" to "Live hardware stats from your phone: battery, CPU, and network, refreshed while you watch.",
            "Workers Fleet" to "Your cloud runners. Add your own workers to take the heavy jobs.",
            "Job Pipeline" to "Every job in one pipeline: running, completed, and where each one ran.",
            "Routing Policies" to "Rules that decide what runs on your phone and what goes to the cloud.",
        )
        else -> listOf("" to "")
    }
    var page by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onExit,
        title = { Text(pages[page].first, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(pages[page].second, fontSize = 14.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${page + 1} / ${pages.size}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    pages.forEachIndexed { index, _ ->
                        Box(
                            modifier = Modifier
                                .size(if (index == page) 8.dp else 6.dp)
                                .background(
                                    color = if (index == page) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (page < pages.lastIndex) {
                Button(onClick = { page++ }) { Text("Next") }
            } else {
                Button(onClick = onProceed) { Text("Got it") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (page > 0) {
                    TextButton(onClick = { page-- }) { Text("Back") }
                }
                TextButton(onClick = onProceed) { Text("Skip") }
            }
        },
    )
}

@Composable
private fun StudioCardsSection(
    onOpenAppFactory: () -> Unit,
    onOpenTeamStudio: () -> Unit,
    onOpenCloudHybrid: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Studios",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(112.dp)
                    .clickable { onOpenAppFactory() },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = "App Factory",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "App Factory",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(112.dp)
                    .clickable { onOpenTeamStudio() },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Groups,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Multi Agent",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(112.dp)
                    .clickable { onOpenCloudHybrid() },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Cloud Hybrid",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectsScreen(
    state: AppUiState,
    listState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    onOpen: (Project) -> Unit,
    onCreate: (String, Uri?) -> Unit,
    onScaffoldTemplate: (com.vayro.app.workspace.ProjectTemplate) -> Unit = {},
    onCreateQuickProject: () -> Unit,
    onImportZip: (Uri) -> Unit,
    onImportFolder: (Uri) -> Unit = {},
    onCloneGit: (String) -> Unit,
    onCancelGitClone: () -> Unit = {},
    onStartGitHubLogin: () -> Unit,
    onGenerateNewGitHubCode: () -> Unit,
    onRefreshGitHub: () -> Unit,
    onDisconnectGitHub: () -> Unit,
    onCloneGitHub: (GitHubRepository) -> Unit,
    onRenameProject: (String, String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onSettings: () -> Unit,
    onPing: () -> Unit,
    onToggleTheme: () -> Unit,
    onShowUpdateDialog: () -> Unit = {},
    onOpenAppFactory: () -> Unit = {},
    onOpenTeamStudio: () -> Unit = {},
    onOpenCloudHybrid: () -> Unit = {},
    // v3.2.38 (Part I): the shared Chat | Work switcher's Chat side.
    onOpenChatHome: () -> Unit = {},
) {
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showGitDialog by rememberSaveable { mutableStateOf(false) }
    var showGitHubDialog by rememberSaveable { mutableStateOf(false) }
    var importExpanded by rememberSaveable { mutableStateOf(false) }
    var gitUrl by rememberSaveable { mutableStateOf("") }
    var repositorySearch by rememberSaveable { mutableStateOf("") }
    var repositoryOwnerFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var selectedFolderUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var selectedFolderName by rememberSaveable { mutableStateOf<String?>(null) }
    val projects = state.projects
    val context = LocalContext.current
    val selectFolderForNewProjectLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            selectedFolderUri = uri
            val doc = DocumentFile.fromTreeUri(context, uri)
            selectedFolderName = doc?.name ?: "Selected folder"
            if (name.isBlank()) {
                name = doc?.name.orEmpty()
            }
        }
    }
    val importZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImportZip(uri)
    }
    val importFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onImportFolder(uri)
    }
    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 18.dp, end = 18.dp, bottom = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandMark(compact = false)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Kodex",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.weight(1f))
                    // v3.2.38 (Part I): the same mode switcher as home,
                    // reflecting Work while in the workspace world.
                    ModeSwitcher(
                        workSelected = true,
                        onSelectChat = onOpenChatHome,
                        onSelectWork = {},
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Build Apps Anywhere", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Plan, code, test, and ship projects right from your phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onCreateQuickProject,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Quick project",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    OutlinedButton(
                        onClick = { showCreate = true },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "New project",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                val isImportExpanded = importExpanded || state.projectImporting || state.gitCloneRunning
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { importExpanded = !importExpanded }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Import: ZIP, Git repo, or GitHub", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (isImportExpanded) "Import files or clone complete Git history" else "ZIP file, Git repository, or GitHub",
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = if (isImportExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isImportExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        AnimatedVisibility(visible = isImportExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ImportSourceButton(
                                        icon = Icons.Default.Folder,
                                        title = if (state.projectImporting) "Importing…" else "Folder",
                                        enabled = !state.projectImporting && !state.gitCloneRunning,
                                        modifier = Modifier.weight(1f),
                                        onClick = { importFolderLauncher.launch(null) },
                                        loading = state.projectImporting,
                                    )
                                    ImportSourceButton(
                                        icon = Icons.Default.Download,
                                        title = if (state.projectImporting) "Importing…" else "ZIP",
                                        enabled = !state.projectImporting && !state.gitCloneRunning,
                                        modifier = Modifier.weight(1f),
                                        onClick = { importZipLauncher.launch("*/*") },
                                        loading = state.projectImporting,
                                    )
                                    ImportSourceButton(
                                        icon = Icons.Default.Code,
                                        title = if (state.gitCloneRunning) "Cloning…" else "Git URL",
                                        enabled = !state.projectImporting && !state.gitCloneRunning,
                                        modifier = Modifier.weight(1f),
                                        onClick = { showGitDialog = true },
                                        loading = state.gitCloneRunning,
                                    )
                                }
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = !state.gitCloneRunning) {
                                        showGitHubDialog = true
                                        if (state.githubAuthStatus == GitHubAuthStatus.CONNECTED && state.githubRepositories.isEmpty()) onRefreshGitHub()
                                    },
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Code, null, tint = PocketOrange, modifier = Modifier.size(19.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                state.githubLogin?.let { "GitHub · @$it" } ?: "Connect GitHub",
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                if (state.githubLogin != null) "Browse public and private repositories" else "Sign in to access your repositories",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                (state.projectImportMessage ?: state.gitCloneMessage)?.let { message ->
                                    Text(message, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
            state.appUpdate?.let { update ->
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onShowUpdateDialog() },
                        shape = RoundedCornerShape(18.dp),
                        color = PocketOrange.copy(alpha = 0.11f),
                        border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.45f)),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = PocketOrange.copy(alpha = 0.18f), modifier = Modifier.size(46.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Download, null, tint = PocketOrange) }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Kodex ${update.versionName}", fontWeight = FontWeight.Bold)
                                Text("A new update is ready", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("Update", color = PocketOrange, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
            item { Text("Your projects", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (projects.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = PocketOrange.copy(alpha = 0.15f),
                                modifier = Modifier.size(56.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = PocketOrange,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "No projects yet",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                "Create a named project or start instantly with a Quick Project.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp,
                            )
                        }
                    }
                }
            } else {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        taskRunning = state.isRunning && state.activeProject?.id == project.id,
                        terminalRunning = state.projectTerminalRunning && state.activeProject?.id == project.id,
                        onOpen = { onOpen(project) },
                        onRename = { onRenameProject(project.id, it) },
                        onDelete = { onDeleteProject(project.id) },
                    )
                }
            }
            item {
                StudioCardsSection(
                    onOpenAppFactory = onOpenAppFactory,
                    onOpenTeamStudio = onOpenTeamStudio,
                    onOpenCloudHybrid = onOpenCloudHybrid,
                )
            }
        }
    }
    var selectedTemplate by rememberSaveable { mutableStateOf(com.vayro.app.workspace.ProjectTemplate.NONE) }
    if (showCreate) AlertDialog(
        onDismissRequest = {
            showCreate = false
            selectedFolderUri = null
            selectedFolderName = null
            selectedTemplate = com.vayro.app.workspace.ProjectTemplate.NONE
        },
        title = { Text("Create project") },
        text = {
            // Medium: dialog content scrolls above the keyboard.
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project name") },
                    placeholder = { Text(selectedFolderName ?: "My project") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Starter Template:", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    com.vayro.app.workspace.ProjectTemplate.values().forEach { tmpl ->
                        val isSel = tmpl == selectedTemplate
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isSel) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.clickable { selectedTemplate = tmpl },
                        ) {
                            Text(
                                tmpl.displayName,
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (selectedFolderUri != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Binding device folder:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(selectedFolderName ?: "Selected folder", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                            }
                            IconButton(
                                onClick = {
                                    selectedFolderUri = null
                                    selectedFolderName = null
                                },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Default.Close, "Remove folder", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { selectFolderForNewProjectLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Select folder from device (Optional)", fontSize = 12.sp)
                    }
                }
                if (name.isNotBlank() || selectedFolderName != null) {
                    Text(
                        "Terminal folder: /workspace/${projectSlug(name.ifBlank { selectedFolderName.orEmpty() })}",
                        fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val tmpl = selectedTemplate
                    onCreate(name, selectedFolderUri)
                    if (tmpl != com.vayro.app.workspace.ProjectTemplate.NONE) {
                        onScaffoldTemplate(tmpl)
                    }
                    showCreate = false
                    name = ""
                    selectedTemplate = com.vayro.app.workspace.ProjectTemplate.NONE
                    selectedFolderUri = null
                    selectedFolderName = null
                },
                enabled = name.isNotBlank() || selectedFolderUri != null,
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = {
                showCreate = false
                selectedFolderUri = null
                selectedFolderName = null
                selectedTemplate = com.vayro.app.workspace.ProjectTemplate.NONE
            }) { Text("Cancel") }
        },
    )
    if (showGitDialog) AlertDialog(
        // Dismiss is always allowed: the clone runs detached in the
        // ViewModel scope, so dismissing only hides this dialog and never
        // stops or orphans the clone. "Cancel clone" below is the real stop.
        onDismissRequest = { showGitDialog = false },
        icon = { Icon(Icons.Default.Code, null, tint = PocketOrange) },
        title = { Text("Clone Git repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Paste a public HTTPS repository URL. Its complete Git history and current branch will be kept.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp)
                OutlinedTextField(
                    value = gitUrl,
                    onValueChange = { gitUrl = it },
                    label = { Text("HTTPS Git URL") },
                    placeholder = { Text("https://github.com/owner/repository.git") },
                    singleLine = true,
                )
                state.gitCloneMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(
                enabled = gitUrl.isNotBlank() && !state.gitCloneRunning,
                onClick = { onCloneGit(gitUrl); showGitDialog = false; gitUrl = "" },
            ) { Text(if (state.gitCloneRunning) "Cloning…" else "Clone project") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (state.gitCloneRunning) onCancelGitClone()
                    showGitDialog = false
                },
            ) { Text(if (state.gitCloneRunning) "Cancel clone" else "Cancel") }
        },
    )
    if (showGitHubDialog) {
        val clipboard = LocalClipboardManager.current
        val myLogin = state.githubLogin.orEmpty()
        val repositoryOwners = state.githubRepositories
            .map { it.ownerLogin }
            .filter { it.isNotBlank() && !it.equals(myLogin, ignoreCase = true) }
            .distinct()
            .sorted()
        val filteredRepositories = state.githubRepositories.filter { repository ->
            val matchesSearch = repositorySearch.isBlank() || repository.fullName.contains(repositorySearch, ignoreCase = true)
            val matchesOwner = when (repositoryOwnerFilter) {
                null -> true
                "personal" -> repository.ownerLogin.isBlank() || repository.ownerLogin.equals(myLogin, ignoreCase = true)
                else -> repository.ownerLogin.equals(repositoryOwnerFilter, ignoreCase = true)
            }
            matchesSearch && matchesOwner
        }
        AlertDialog(
            // Dismiss is always allowed (see the clone dialog above): the
            // clone runs detached, dismissing only hides this dialog.
            onDismissRequest = { showGitHubDialog = false },
            icon = { Icon(Icons.Default.Code, null, tint = PocketOrange) },
            title = { Text(state.githubLogin?.let { "GitHub · @$it" } ?: "Connect GitHub") },
            text = {
                when (state.githubAuthStatus) {
                    GitHubAuthStatus.DISCONNECTED, GitHubAuthStatus.ERROR -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            state.githubMessage ?: "Sign in with GitHub's official CLI to browse public and private repositories.",
                            color = if (state.githubAuthStatus == GitHubAuthStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                        Button(onClick = onStartGitHubLogin, modifier = Modifier.fillMaxWidth()) { Text("Sign in with GitHub") }
                    }
                    GitHubAuthStatus.STARTING -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(state.githubMessage ?: "Starting GitHub sign-in…")
                    }
                    GitHubAuthStatus.AWAITING_USER -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Enter this one-time code in the GitHub page opened in your browser.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { state.githubUserCode?.let { clipboard.setText(AnnotatedString(it)) } },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                state.githubUserCode.orEmpty(),
                                modifier = Modifier.padding(16.dp),
                                textAlign = TextAlign.Center,
                                fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                letterSpacing = 2.sp,
                            )
                        }
                        Text("Tap the code to copy it. Kodex will connect automatically after approval.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(
                            onClick = onGenerateNewGitHubCode,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Generate new code")
                        }
                    }
                    GitHubAuthStatus.CONNECTED -> Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(state.githubMessage ?: "Select a repository", modifier = Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = onRefreshGitHub, enabled = !state.githubRepositoriesLoading) {
                                Icon(Icons.Default.Refresh, "Refresh repositories")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = repositoryOwnerFilter == null,
                                onClick = { repositoryOwnerFilter = null },
                                label = { Text("All", fontSize = 12.sp) },
                            )
                            FilterChip(
                                selected = repositoryOwnerFilter == "personal",
                                onClick = { repositoryOwnerFilter = "personal" },
                                label = { Text("Personal", fontSize = 12.sp) },
                            )
                            repositoryOwners.forEach { owner ->
                                FilterChip(
                                    selected = repositoryOwnerFilter == owner,
                                    onClick = { repositoryOwnerFilter = owner },
                                    label = { Text(owner, fontSize = 12.sp, maxLines = 1) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = repositorySearch,
                            onValueChange = { repositorySearch = it },
                            placeholder = { Text("Search repositories") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (state.githubRepositoriesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 350.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(filteredRepositories, key = { it.fullName }) { repository ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = !state.gitCloneRunning) {
                                        showGitHubDialog = false
                                        onCloneGitHub(repository)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                ) {
                                    Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (repository.private) Icons.Default.Key else Icons.Default.Code, null, modifier = Modifier.size(17.dp), tint = PocketOrange)
                                        Spacer(Modifier.width(9.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(repository.fullName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${if (repository.private) "Private" else "Public"} · ${repository.defaultBranch}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (state.gitCloneRunning) {
                    TextButton(onClick = { onCancelGitClone(); showGitHubDialog = false }) { Text("Cancel clone") }
                } else if (state.githubAuthStatus == GitHubAuthStatus.CONNECTED) {
                    TextButton(onClick = { showGitHubDialog = false }) { Text("Close") }
                }
            },
            dismissButton = {
                // While a clone runs, the only action is "Cancel clone" above:
                // Disconnect/Cancel here would leave the clone's fate unclear.
                if (!state.gitCloneRunning) {
                    if (state.githubAuthStatus == GitHubAuthStatus.CONNECTED) {
                        TextButton(onClick = { onDisconnectGitHub(); showGitHubDialog = false }) { Text("Disconnect") }
                    } else if (state.githubAuthStatus != GitHubAuthStatus.STARTING) {
                        TextButton(onClick = { showGitHubDialog = false }) { Text("Cancel") }
                    }
                }
            },
        )
    }
}

/**
 * The app-update dialog (v3.2.34, U1: hoisted out of ProjectsScreen to the
 * app root). Content and behavior are the ones ProjectsScreen used to
 * host: release notes, size, the unknown-apps permission warning and
 * grant flow, live download progress, and the detached install.
 */
@Composable
private fun AppUpdateDialog(
    update: com.vayro.app.update.AppUpdateInfo,
    status: AppUpdateStatus?,
    downloadedBytes: Long,
    totalBytes: Long,
    error: String?,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onGrantPermission: () -> Unit,
) {
    val context = LocalContext.current
    val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    val downloading = status == AppUpdateStatus.DOWNLOADING
    val installing = status == AppUpdateStatus.INSTALLING
    val progress = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    AlertDialog(
        // Dismiss is always allowed: the download/install runs detached
        // (the Android installer takes over at the end), so dismissing
        // only hides this dialog. Reopen it from Settings -> App updates.
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Download, null, tint = PocketOrange, modifier = Modifier.size(34.dp)) },
        title = { Text("Update to ${update.versionName}", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(update.notes.ifBlank { "Get the latest improvements and fixes for Kodex." })
                if (update.sizeBytes > 0) Text("Download size: ${formatMegabytes(update.sizeBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (!canInstall) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Allow \u2018Install unknown apps\u2019 for Kodex. Without this permission, Android will not install the update.", fontSize = 13.sp)
                        }
                    }
                }
                if (downloading) {
                    if (totalBytes > 0) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        if (totalBytes > 0) "Downloading ${formatMegabytes(downloadedBytes)} / ${formatMegabytes(totalBytes)} \u00b7 ${(progress * 100).toInt()}%" else "Downloading ${formatMegabytes(downloadedBytes)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (installing) Text("Download verified. Opening Android installer\u2026", color = PocketGreen, fontSize = 13.sp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Button(
                enabled = !downloading && !installing,
                onClick = {
                    if (!canInstall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        onGrantPermission()
                    } else {
                        onInstall()
                    }
                },
            ) {
                Text(when { !canInstall -> "Grant permission"; downloading -> "Downloading\u2026"; installing -> "Installing\u2026"; else -> "Download and install" })
            }
        },
        dismissButton = {
            Row {
                if (downloading) {
                    TextButton(onClick = onCancelDownload) {
                        Text("Cancel", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        },
    )
}

@Composable
private fun ImportSourceButton(
    icon: ImageVector,
    title: String,
    enabled: Boolean,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Icon(icon, null, Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ApiStatusChip(state: AppUiState, onSettings: () -> Unit, onPing: () -> Unit) {
    val dotColor = when (state.apiPingStatus) {
        ApiPingStatus.OK -> PocketGreen
        ApiPingStatus.FAILED -> MaterialTheme.colorScheme.error
        ApiPingStatus.PINGING -> PocketOrange
        ApiPingStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    val providerLabel = when {
        state.agentKind == AgentKind.ANTIGRAVITY ->
            state.antigravityModel.ifBlank { state.agentKind.title }
        state.provider.model.isNotBlank() -> state.provider.model
        state.provider.baseUrl.isNotBlank() -> {
            runCatching { java.net.URI(state.provider.baseUrl).host ?: state.provider.kind.title }
                .getOrDefault(state.provider.kind.title)
        }
        else -> state.provider.kind.title
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onSettings).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            // Model / provider name
            Text(
                text = providerLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            // Ping button
            IconButton(
                onClick = onPing,
                modifier = Modifier.size(48.dp),
            ) {
                if (state.apiPingStatus == ApiPingStatus.PINGING) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = PocketOrange)
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Ping API",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}



@Composable
private fun ProjectCard(
    project: Project,
    taskRunning: Boolean,
    terminalRunning: Boolean,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by rememberSaveable(project.id) { mutableStateOf(false) }
    var showRename by rememberSaveable(project.id) { mutableStateOf(false) }
    var showDelete by rememberSaveable(project.id) { mutableStateOf(false) }
    var renameText by rememberSaveable(project.id) { mutableStateOf(project.name) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Icon(Icons.Default.Folder, null, Modifier.padding(13.dp), tint = PocketOrange)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        project.name,
                        modifier = Modifier.weight(1f, fill = false),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (taskRunning || terminalRunning) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (taskRunning) "Task running" else "Terminal running",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Text(
                    if (project.kind == ProjectKind.QUICK_PROJECT) "Quick project" else project.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("/workspace/${project.slug}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily)
                Text("${project.language} · ${project.formattedUpdatedAt}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Project options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename project") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; renameText = project.name; showRename = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete project") },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; showDelete = true },
                    )
                }
            }
        }
    }
    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename project") },
            text = { OutlinedTextField(renameText, { renameText = it }, label = { Text("Project name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(renameText); showRename = false }, enabled = renameText.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete this project?") },
            text = { Text("Its chats, files, attachments, changes, and terminal history will be permanently removed.") },
            confirmButton = { TextButton(onClick = { onDelete(); showDelete = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadOnlyProjectScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onSwitchChat: (String) -> Unit,
    onContinueHere: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val project = state.readOnlyProject ?: return
    val activeChat = state.readOnlyProjectChats.firstOrNull { it.id == state.readOnlyChatId }
    val listState = rememberLazyListState()
    var showChats by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.readOnlyChatId) {
        // v3.2.42: ChatTab's list is reversed — the newest message
        // is index 0, where a fresh state already sits.
        if (state.readOnlyMessages.isNotEmpty()) listState.scrollToItem(ScrollFollowGate.NEWEST_INDEX)
    }

    if (showChats) {
        ChatSwitcherDialog(
            chats = state.readOnlyProjectChats,
            activeChatId = state.readOnlyChatId,
            switchingEnabled = true,
            allowCreate = false,
            onDismiss = { showChats = false },
            onCreate = {},
            onSwitch = { chatId ->
                onSwitchChat(chatId)
                showChats = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(project.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${activeChat?.title ?: "Chat"} · History",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Projects") }
                },
                actions = {
                    IconButton(onClick = { showChats = true }) { Icon(Icons.Default.History, "Project chats") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            ChatTab(
                messages = state.readOnlyMessages,
                approval = null,
                liveProcess = emptyList(),
                isRunning = false,
                streamingMessageId = null,
                onSend = { _, _ -> },
                onStop = {},
                onApproval = {},
                listState = listState,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                thinkingActive = false,
                agentKind = state.agentKind,
                workspaceFiles = emptyList(),
                pendingAttachments = emptyList(),
                onAttach = { _ -> },
                onRemoveAttachment = {},
                onOpenAttachment = {},
                onRunInTerminal = {},
                readOnly = true,
                readOnlyBlocked = false,
                onContinueHere = onContinueHere,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onSend: (String, String?) -> Unit,
    onStop: () -> Unit,
    onApproval: (Boolean) -> Unit,
    onRefreshFiles: () -> Unit,
    onOpenFile: (WorkspaceEntry) -> Unit,
    onCloseFile: () -> Unit,
    onSaveFile: suspend (String, String) -> Boolean,
    onUndoChanges: () -> Unit,
    onKeepChanges: () -> Unit,
    onUndoFileChange: (String) -> Unit,
    onKeepFileChange: (String) -> Unit,
    onResumeTask: () -> Unit,
    onCreateChat: () -> Unit,
    onSwitchChat: (String) -> Unit,
    onRenameChat: (String, String) -> Unit = { _, _ -> },
    onDeleteChat: (String) -> Unit = {},
    onTerminalRun: (String) -> Unit,
    onTerminalInput: (String) -> Unit,
    onTerminalInterrupt: () -> Unit,
    onTerminalPrepare: (String) -> Unit,
    onTerminalDraftConsumed: () -> Unit,
    onTerminalOpened: () -> Unit,
    onTerminalStop: () -> Unit,
    onTerminalClear: () -> Unit,
    onTerminalConfirm: () -> Unit,
    onTerminalCancel: () -> Unit,
    onUseSuggestedProjectRoot: () -> Unit,
    onExportProject: (Uri) -> Unit,
    onShareProject: () -> Unit,
    onAddAttachments: (List<Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onSaveAttachmentToUri: (ChatAttachment, Uri) -> Unit = { _, _ -> },
    onBuildAndRunAndroid: () -> Unit,
    onSelectExecutionMode: (ExecutionMode) -> Unit = {},
    onToggleScratchpad: () -> Unit = {},
    onAddRoadmapComment: (roadmapId: String, text: String, stepId: String?) -> Unit = { _, _, _ -> },
    onApproveAndBuildRoadmap: (roadmap: ActiveRoadmap, additionalInstructions: String?) -> Unit = { _, _ -> },
    onBindFolder: (Uri) -> Unit = {},
    onSetProjectRootDirectory: (String) -> Unit = {},
    onSelectTab: (String) -> Unit = {},
    onCloseTab: (String) -> Unit = {},
    onCloseOtherTabs: (String) -> Unit = {},
    onCloseAllTabs: () -> Unit = {},
    onSelectTerminalSession: (String) -> Unit = {},
    onNewTerminalSession: () -> Unit = {},
    onCloseTerminalSession: (String) -> Unit = {},
    onResumeInterruptedSession: () -> Unit = {},
    onDismissInterruptedSession: () -> Unit = {},
    onRunAutonomousVerification: () -> Unit = {},
    onCreateFile: (String) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onRenameFile: (String, String) -> Unit = { _, _ -> },
    onDeleteFile: (String) -> Unit = {},
    onDuplicateFile: (String) -> Unit = {},
    onOpenFileByPath: (String) -> Unit = {},
    workspaceDir: java.io.File? = null,
    onRollbackToBaseline: () -> Unit = {},
    onEngageEmergencyKillSwitch: () -> Unit = {},
    onResetEmergencyKillSwitch: () -> Unit = {},
    onApprovalWithLevel: (com.vayro.app.security.PermissionLevel, Boolean) -> Unit = { _, _ -> },
    onMoveEntry: (String, String) -> Unit = { _, _ -> },
    onGitStatus: ((String) -> Unit) -> Unit = {},
    onGitCommit: (String, (Boolean, String) -> Unit) -> Unit = { _, _ -> },
    onGitCheckout: (String, Boolean, (Boolean, String) -> Unit) -> Unit = { _, _, _ -> },
    onGitDiff: ((String) -> Unit) -> Unit = {},
    onGitBranchList: ((List<String>) -> Unit) -> Unit = {},
    onGitPush: (((Boolean, String) -> Unit) -> Unit) = {},
    onGitPull: (((Boolean, String) -> Unit) -> Unit) = {},
    detectedPorts: List<Int> = emptyList(),
    onScanPorts: ((List<Int>) -> Unit) -> Unit = {},
    onAttachVisualInspection: (String, String) -> Unit = { _, _ -> },
    viewModel: MainViewModel? = null,
    onOpenAppFactory: () -> Unit = {},
    onOpenTeamStudio: () -> Unit = {},
    onOpenCloudHybrid: () -> Unit = {},
    onOpenMarketplace: () -> Unit = {},
    onPushToGitHubRequest: () -> Unit = {},
) {
    val context = LocalContext.current
    val isAndroidProject = state.androidProjectDetected
    // v3.2.42: no IME boolean is read in this screen anymore. The
    // bottom bar below hides while the chat composer is being
    // edited; that session state now arrives from ChatTab as field
    // FOCUS (onInputFocusChange → chatInputFocused), the same
    // discrete signal that drives the composer's controls row.
    val bindFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> if (uri != null) onBindFolder(uri) },
    )
    val exportProjectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri -> if (uri != null) onExportProject(uri) },
    )
    val attachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = onAddAttachments,
    )
    var saveTargetAttachment by remember { mutableStateOf<ChatAttachment?>(null) }
    val saveAttachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(),
        onResult = { uri ->
            val attachment = saveTargetAttachment
            saveTargetAttachment = null
            if (uri != null && attachment != null) onSaveAttachmentToUri(attachment, uri)
        },
    )
    val unknownAppsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) {
                onBuildAndRunAndroid()
            } else {
                Toast.makeText(context, "Allow app installs to run Android projects", Toast.LENGTH_LONG).show()
            }
        },
    )
    val chatListState = rememberLazyListState()
    // U8a: true while a sheet/dialog covers the chat (reported by ChatTab).
    // The follow-scroll below pauses then, so the list is exactly where
    // the user left it when the sheet closes.
    var chatOverlayOpen by remember { mutableStateOf(false) }
    // v3.2.42: true while the chat composer field holds focus
    // (reported by ChatTab) — the bottom bar hides for the session.
    var chatInputFocused by remember { mutableStateOf(false) }

    // Item count of the chat LazyColumn: messages + the in-flow working
    // row (v3.2.34, U4 — it is a list item now, not a docked bar) + the
    // approval card + the end-of-run changes review card (U6d).
    val chatItemCount = state.messages.size +
        (if (state.isRunning) 1 else 0) +
        (if (state.pendingApproval != null) 1 else 0) +
        (if (!state.isRunning && state.changes.isNotEmpty()) 1 else 0)

    // v3.2.40 (Scope I/I2): the chat's scroll position belongs to the
    // user. It is captured continuously into the process-level
    // ChatScrollStore (per project+chat), because the events that kept
    // throwing the chat to the top — the Activity sheet cycle, an app
    // background/foreground round trip, a Chat<->Work round trip —
    // tear down or rebuild this screen's composition, and a state held
    // only in this screen's `remember` (chatListState above) cannot
    // survive all of them.
    val chatScrollKey = "ws:${state.activeProject?.id ?: ""}:${state.activeChatId ?: ""}"
    // v3.2.43 (D1): true once THIS entry into the chat has run its
    // restore pass below. The save effect waits for it — before the
    // restore, the list state can still hold the position of the
    // chat the user just left, and saving that under this chat's
    // key is exactly how the store got poisoned (the foreign
    // position was then restored on a later return and clamped by
    // the list onto the oldest message — the visual top).
    var chatScrollRestoreDone by remember(chatScrollKey) { mutableStateOf(false) }
    LaunchedEffect(chatListState, chatScrollKey, chatScrollRestoreDone) {
        if (!chatScrollRestoreDone) return@LaunchedEffect
        snapshotFlow {
            chatListState.firstVisibleItemIndex to chatListState.firstVisibleItemScrollOffset
        }
            .collect { (index, offset) -> ChatScrollStore.save(chatScrollKey, index, offset) }
    }

    LaunchedEffect(chatScrollKey) {
        // A saved position for THIS chat wins: returning to the chat
        // (re-entry, background return, Work round trip) restores
        // exactly where the user left it. Only a chat with no saved
        // position starts at its end, as before.
        // v3.2.43 (D1): the read is range-validated
        // (ChatScrollStore.validatedPosition) — a saved index that
        // does not exist in THIS chat is untrustworthy and is
        // dropped instead of being clamped onto the oldest message.
        val savedScroll = ChatScrollStore.validatedPosition(chatScrollKey, chatItemCount)
        if (savedScroll != null && (savedScroll.first > 0 || savedScroll.second > 0)) {
            chatListState.scrollToItem(savedScroll.first, savedScroll.second)
        } else if (chatItemCount > 0) {
            // v3.2.42: the chat list is REVERSED — index 0 is the
            // newest item at the visual bottom, where a fresh list
            // state already sits; this covers a state carried over
            // from another chat.
            chatListState.scrollToItem(ScrollFollowGate.NEWEST_INDEX)
        }
        chatScrollRestoreDone = true
    }

    // Scope I: closing a sheet/dialog over the chat must hand back the
    // exact position from the moment it opened — the store captured
    // it continuously while the sheet was up (scrolling was locked).
    // v3.2.41 (Scope 1): the restore decision goes through
    // ScrollFollowGate.shouldRestore — a saved position is applied
    // ONLY when the live list demonstrably lost its position (0/0)
    // and no scroll gesture is in flight. The gate is the single
    // rule both chat lists share, unit-tested, so no future effect
    // can reintroduce a mid-read yank by hand-rolling the checks.
    LaunchedEffect(chatOverlayOpen) {
        if (!chatOverlayOpen) {
            // v3.2.43 (D1): same range-validated read as the entry
            // restore — an out-of-range saved index is dropped here
            // too, never clamped onto the oldest message.
            val savedScroll = ChatScrollStore.validatedPosition(chatScrollKey, chatItemCount)
            val storeHasPosition = savedScroll != null &&
                (savedScroll.first > 0 || savedScroll.second > 0)
            if (savedScroll != null &&
                ScrollFollowGate.shouldRestore(
                    storeHasPosition = storeHasPosition,
                    liveIndex = chatListState.firstVisibleItemIndex,
                    liveOffset = chatListState.firstVisibleItemScrollOffset,
                    userScrollInProgress = chatListState.isScrollInProgress,
                )
            ) {
                chatListState.scrollToItem(savedScroll.first, savedScroll.second)
            }
        }
    }

    // v3.2.42: the v3.2.41 streaming follow effect — and the
    // userScrolledUp tracking that fed it — are DELETED. The follow
    // effect existed because a forward list had to be scrolled to
    // its end on every data change; with reverseLayout the list
    // anchors at the newest item (index 0) by construction — while
    // the user is parked there, streaming growth simply extends the
    // list upward from the anchor, and while he is scrolled up
    // reading, nothing may move the list at all. The gate's rules
    // are preserved by the structure itself (auto-follow only at
    // the bottom, never mid-gesture, never under an overlay — the
    // list is also gesture-locked while an overlay is open, U8a)
    // and by ScrollFollowGate.shouldRestore for position restore.

    // v3.2.43 (D2): the one follow the structure does NOT provide —
    // the user's OWN send while he is scrolled up reading. The
    // scroll law has always allowed exactly this move, the home
    // list does it, and its absence here left the workspace with
    // no deterministic landing on send at all. Detection is by the
    // newest message's identity, per chat (never a count, which
    // went stale across chats on the home side): the first
    // observation of a chat only initializes the tracker, so
    // entering a chat never fires this — the restore pass above
    // owns that landing. Assistant/streaming growth never fires
    // it (the newest message is not the user's), and the gate
    // still vetoes mid-gesture and under an overlay. The landing
    // is the D4 glide, ending exactly on the newest item (the
    // working row while the run streams, index 0 either way).
    var lastNewestWorkspaceMessageId by remember(state.activeChatId) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.messages.lastOrNull { it.fromUser }?.id) {
        // Keyed and tracked on the newest USER message: a send
        // registers even if an assistant streaming row lands in
        // the same state emission, and assistant growth alone
        // never re-fires the effect.
        val newestUser = state.messages.lastOrNull { it.fromUser }
        val previousNewestId = lastNewestWorkspaceMessageId
        if (newestUser != null) lastNewestWorkspaceMessageId = newestUser.id
        if (previousNewestId != null && newestUser != null &&
            newestUser.id != previousNewestId
        ) {
            val follow = ScrollFollowGate.shouldFollow(
                ownSend = true,
                atBottom = ScrollFollowGate.isParkedAtNewest(chatListState.canScrollBackward),
                userScrollInProgress = chatListState.isScrollInProgress,
                userScrolledUp = false,
                overlayOpen = chatOverlayOpen,
            )
            if (follow) chatListState.glideToNewest()
        }
    }

    var selectedTab by rememberSaveable { mutableStateOf(WorkspaceTab.CHAT) }
    // v3.2.37 (A2): while the voice call is up, the workspace chrome hides
    // so the call is a clean full-screen surface (no project header or
    // bottom bar bleeding through). Workspace state itself is untouched.
    var voiceCallOverlayActive by remember { mutableStateOf(false) }
    // Medium: the recovery + interrupted-session banners truncate their
    // status text; tapping a banner expands it to the full message.
    var recoveryExpanded by remember { mutableStateOf(false) }
    var interruptedExpanded by remember { mutableStateOf(false) }
    // System back: from any tab other than Chat, back returns to Chat first;
    // only on Chat does back leave the project. Declared AFTER selectedTab
    // so the handler reads live tab state. Dialogs/sheets (chats, palette)
    // register their own handlers later in this composition, so back still
    // dismisses them before this handler runs.
    // v3.2.35 (U6d): ONE step-back used by BOTH the system BackHandler and
    // the header back arrow. The header arrow used to call onBack()
    // (= closeProject) directly from every tab, so Back from the Changes
    // review closed the whole project and wiped the pending changes from
    // state. That bypass is the root cause of "Back throws everything
    // away" — both back paths now share this exact behavior.
    val stepBackInWorkspace: () -> Unit = {
        if (selectedTab != WorkspaceTab.CHAT) selectedTab = WorkspaceTab.CHAT else onBack()
    }
    BackHandler(onBack = stepBackInWorkspace)
    var lastHandledPreviewUrl by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.previewUrl, state.previewReady) {
        if (!state.previewReady || state.previewUrl.isNullOrBlank()) {
            if (state.previewUrl.isNullOrBlank()) lastHandledPreviewUrl = null
        } else if (state.previewUrl != lastHandledPreviewUrl) {
            lastHandledPreviewUrl = state.previewUrl
            selectedTab = WorkspaceTab.PREVIEW
        }
    }
    var showChats by rememberSaveable { mutableStateOf(false) }
    var showCommandPalette by rememberSaveable { mutableStateOf(false) }

    // If a file is open, switch to the production code editor surface
    if (state.openedFilePath != null) {
        BackHandler(onBack = {
            onCloseFile()
            selectedTab = WorkspaceTab.FILES
        })
        CodeEditorScreen(
            filePath = state.openedFilePath,
            content = state.openedFileContent,
            loading = state.fileContentLoading,
            openTabs = state.openEditorTabs.ifEmpty { listOf(state.openedFilePath) },
            onSelectTab = onSelectTab,
            onCloseTab = onCloseTab,
            onCloseOtherTabs = onCloseOtherTabs,
            onCloseAllTabs = {
                onCloseAllTabs()
                selectedTab = WorkspaceTab.FILES
            },
            onClose = {
                onCloseFile()
                selectedTab = WorkspaceTab.FILES
            },
            onSave = { updatedContent ->
                onSaveFile(state.openedFilePath, updatedContent)
            },
            workspaceDir = workspaceDir,
            onNavigateToFileLine = { path, _ ->
                onOpenFileByPath(path)
            },
            // Milestone 2 — persistent buffers: restore cursor, track edits.
            initialViewState = state.openedFilePath?.let { state.editorViewStates[it] },
            onContentChanged = { updatedText ->
                state.openedFilePath?.let { viewModel?.onEditorContentChanged(it, updatedText) }
            },
            onViewStateChanged = { viewState ->
                state.openedFilePath?.let { viewModel?.onEditorViewStateChanged(it, viewState) }
            },
            // Milestone 2 — rename with preview + undo.
            onPlanRename = { oldName, newName -> viewModel?.planRenameSymbol(oldName, newName) },
            onApplyRename = { preview -> viewModel?.applyRenameSymbol(preview) },
            onUndoRename = { viewModel?.undoLastRename() ?: false },
            // QA H1: palette actions that leave the editor get real behavior.
            onPaletteNavigate = { tabId ->
                onCloseFile()
                selectedTab = when (tabId) {
                    "chat" -> WorkspaceTab.CHAT
                    "terminal" -> WorkspaceTab.TERMINAL
                    "changes" -> WorkspaceTab.CHANGES
                    "preview" -> WorkspaceTab.PREVIEW
                    else -> WorkspaceTab.FILES
                }
            },
            onCreateFile = { name -> viewModel?.createFile(name) ?: false },
            onCreateFolder = { name -> viewModel?.createFolder(name) ?: false },
            onRefreshFiles = { viewModel?.refreshProjectFiles() },
            onPrepareTerminalCommand = { command -> viewModel?.prepareProjectTerminalCommand(command) },
            onShowToast = { message -> viewModel?.showToast(message) },
            onSaveSecondary = { path, text -> onSaveFile(path, text) },
            onCheckRuntimeHealth = { viewModel?.getRuntimeHealthSnapshot() ?: RuntimeHealthSnapshot() },
        )
        return
    }

    if (showChats) {
        ChatSwitcherDialog(
            chats = state.projectChats,
            activeChatId = state.activeChatId,
            switchingEnabled = !state.isRunning,
            onDismiss = { showChats = false },
            onCreate = {
                onCreateChat()
                // While a task is running, keep the switcher open so the newly
                // created conversation is visible without detaching live task output.
                if (!state.isRunning) {
                    showChats = false
                    selectedTab = WorkspaceTab.CHAT
                }
            },
            onSwitch = { chatId ->
                onSwitchChat(chatId)
                showChats = false
                selectedTab = WorkspaceTab.CHAT
            },
            onRename = onRenameChat,
            onDelete = onDeleteChat,
        )
    }
    if (showCommandPalette) {
        WorkspaceCommandPalette(
            onDismiss = { showCommandPalette = false },
            onSelect = { command ->
                showCommandPalette = false
                when (command) {
                    WorkspaceCommand.CHAT -> selectedTab = WorkspaceTab.CHAT
                    WorkspaceCommand.FILES -> {
                        selectedTab = WorkspaceTab.FILES
                        onRefreshFiles()
                    }
                    WorkspaceCommand.TERMINAL -> {
                        selectedTab = WorkspaceTab.TERMINAL
                        onTerminalOpened()
                    }
                    WorkspaceCommand.CHANGES -> selectedTab = WorkspaceTab.CHANGES
                    WorkspaceCommand.PREVIEW -> selectedTab = WorkspaceTab.PREVIEW
                    WorkspaceCommand.REFRESH_FILES -> onRefreshFiles()
                    WorkspaceCommand.BIND_FOLDER -> bindFolderLauncher.launch(null)
                    WorkspaceCommand.APP_FACTORY -> onOpenAppFactory()
                    WorkspaceCommand.MULTI_AGENT_TEAM -> onOpenTeamStudio()
                    WorkspaceCommand.CLOUD_HYBRID -> onOpenCloudHybrid()
                    WorkspaceCommand.MARKETPLACE -> onOpenMarketplace()
                }
            },
        )
    }

    state.pendingTerminalCommand?.let { command ->
        AlertDialog(
            onDismissRequest = onTerminalCancel,
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Run potentially destructive command?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This command can delete files, rewrite Git history, or change the project significantly.")
                    // Medium: the preview used to be a hardcoded dark slab
                    // (dark island in light mode). Theme surfaces now.
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            command,
                            Modifier.fillMaxWidth().padding(10.dp),
                            fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = { Button(onClick = onTerminalConfirm) { Text("Run anyway") } },
            dismissButton = { TextButton(onClick = onTerminalCancel) { Text("Cancel") } },
        )
    }
    Scaffold(
        topBar = {
            if (!voiceCallOverlayActive && selectedTab != WorkspaceTab.PREVIEW && selectedTab != WorkspaceTab.TERMINAL) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.statusBarsPadding(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            IconButton(onClick = stepBackInWorkspace, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(21.dp))
                            }
                            Text(
                                text = state.activeProject?.name?.takeIf { it.isNotBlank() } ?: "IDE",
                                fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (isAndroidProject) {
                                IconButton(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                            !context.packageManager.canRequestPackageInstalls()) {
                                            unknownAppsLauncher.launch(
                                                Intent(
                                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                    Uri.parse("package:${context.packageName}"),
                                                ),
                                            )
                                        } else {
                                            onBuildAndRunAndroid()
                                        }
                                    },
                                    enabled = !state.androidBuildRunning && !state.isRunning && !state.projectTerminalRunning,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    if (state.androidBuildRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Default.PlayArrow, "Build and run Android app", modifier = Modifier.size(20.dp))
                                }
                            }
                            IconButton(
                                onClick = { showChats = true },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(Icons.Default.History, "Project chats", modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = { showCommandPalette = true },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(Icons.Default.MoreVert, "Command palette", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (!chatInputFocused && !voiceCallOverlayActive) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                WorkspaceTab.entries.filter { it != WorkspaceTab.CHANGES }.forEach { tab ->
                    NavigationBarItem(
                        selected = tab != WorkspaceTab.HOME && selectedTab == tab,
                        onClick = {
                            if (tab == WorkspaceTab.HOME) {
                                onBack()
                            } else {
                                selectedTab = tab
                                if (tab == WorkspaceTab.FILES) onRefreshFiles()
                                if (tab == WorkspaceTab.TERMINAL) onTerminalOpened()
                            }
                        },
                        icon = { Icon(tab.icon, tab.label) },
                        label = { Text(tab.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.runtimeRecoveryStatus != null) {
                Surface(
                    onClick = { recoveryExpanded = !recoveryExpanded },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(state.runtimeRecoveryStatus, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = if (recoveryExpanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (state.runtimeRecoveryCanResume) "Your task state is preserved." else "Kodex is reconnecting without discarding the task.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = if (recoveryExpanded) Int.MAX_VALUE else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (state.runtimeRecoveryCanResume) {
                            Button(
                                onClick = onResumeTask,
                                modifier = Modifier.heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(8.dp),
                                // Medium: this used to read "Resume", identical
                                // to the interrupted-session banner below it.
                            ) { Text("Resume task", fontSize = 12.sp, maxLines = 1) }
                        } else {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
            if (state.interruptedSession != null) {
                Surface(
                    onClick = { interruptedExpanded = !interruptedExpanded },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Interrupted session", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Task: \"${if (interruptedExpanded) state.interruptedSession.initialPrompt else state.interruptedSession.initialPrompt.take(60)}\" in ${state.interruptedSession.projectTitle}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                maxLines = if (interruptedExpanded) Int.MAX_VALUE else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onResumeInterruptedSession,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Text("Resume", fontSize = 12.sp, maxLines = 1)
                        }
                        IconButton(
                            onClick = onDismissInterruptedSession,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (selectedTab == WorkspaceTab.CHAT && state.changes.isNotEmpty()) {
                val added = state.changes.sumOf { it.additions }
                val deleted = state.changes.sumOf { it.deletions }
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { selectedTab = WorkspaceTab.CHANGES },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Code, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Files changed", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("+$added", color = PocketGreen, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Text("-$deleted", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Review changes", Modifier.size(18.dp))
                        }
                        Text(
                            state.changes.take(4).joinToString(" · ") { it.path } +
                                if (state.changes.size > 4) " +${state.changes.size - 4} more" else "",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                WorkspaceTab.CHAT, WorkspaceTab.HOME -> ChatTab(
                    onCallActiveChange = { voiceCallOverlayActive = it },
                    messages = state.messages,
                    approval = state.pendingApproval,
                    liveProcess = state.liveProcess,
                    changes = state.changes,
                    isRunning = state.isRunning,
                    streamingMessageId = state.streamingMessageId,
                    onSend = onSend,
                    onStop = onStop,
                    onApproval = onApproval,
                    listState = chatListState,
                    taskStartedAtMillis = state.workSegmentStartedAtMillis ?: state.taskStartedAtMillis,
                    taskFinishedAtMillis = state.taskFinishedAtMillis,
                    thinkingActive = state.liveThinking,
                    agentKind = state.agentKind,
                    workspaceFiles = state.workspaceFiles,
                    pendingAttachments = state.pendingAttachments,
                    onAttach = { types ->
                        attachmentLauncher.launch(types)
                    },
                    onRemoveAttachment = onRemoveAttachment,
                    onOpenAttachment = onOpenAttachment,
                    onSaveAttachment = {
                        saveTargetAttachment = it
                        saveAttachmentLauncher.launch(it.displayName)
                    },
                    onRunInTerminal = { command ->
                        selectedTab = WorkspaceTab.TERMINAL
                        onTerminalOpened()
                        onTerminalPrepare(command)
                    },
                    onOpenLink = viewModel?.let { vm -> { url: String -> vm.openPreviewUrl(url) } },
                    onInstallApkPath = { path -> viewModel?.installBuiltApkByPath(path) },
                    onShareApkPath = { path -> viewModel?.shareBuiltApkByPath(path) },
                    onSaveApkPath = { path -> viewModel?.saveBuiltApkByPath(path) },
                    onLoadDeliveredApk = { path -> viewModel?.loadDeliveredApk(path) },
                    onPreviewDelivered = { kind, target -> viewModel?.previewDelivered(kind, target) },
                    onLocateApk = {
                        selectedTab = WorkspaceTab.FILES
                        onRefreshFiles()
                    },
                    onKeepChanges = onKeepChanges,
                    onUndoChanges = onUndoChanges,
                    onModalOverlayChange = { chatOverlayOpen = it },
                    onInputFocusChange = { chatInputFocused = it },
                    executionMode = state.executionMode,
                    onSelectExecutionMode = onSelectExecutionMode,
                    scratchpadItems = state.scratchpadItems,
                    scratchpadExpanded = state.scratchpadExpanded,
                    onToggleScratchpad = onToggleScratchpad,
                    onAddRoadmapComment = onAddRoadmapComment,
                    onApproveAndBuildRoadmap = onApproveAndBuildRoadmap,
                    orchestratorSnapshot = state.orchestratorSnapshot,
                    onRollbackToBaseline = onRollbackToBaseline,
                    emergencyKillSwitchActive = state.emergencyKillSwitchActive,
                    onResetEmergencyKillSwitch = onResetEmergencyKillSwitch,
                    onApprovalWithLevel = onApprovalWithLevel,
                    onAttachUris = onAddAttachments,
                    onSelectAgent = { kind -> viewModel?.selectAgent(kind) },
                    // v3.2.38 (Part P): the workspace pill's sheet also
                    // switches provider + model in place.
                    providerChoices = viewModel?.workspaceProviderChoices(state.agentKind) ?: emptyList(),
                    onPickProvider = { kind -> viewModel?.pickWorkspaceProvider(kind) },
                    fetchProviderModels = { kind -> viewModel?.workspacePickerModels(kind) ?: emptyList() },
                    onPickProviderModel = { kind, model -> viewModel?.pickWorkspaceProviderModel(kind, model) },
                    freeModeEnabled = state.freeModeEnabled,
                    projectName = state.activeProject?.name.orEmpty(),
                    onPushToGitHub = onPushToGitHubRequest,
                    onOpenGitHubBuild = onOpenCloudHybrid,
                    onOpenAppFactory = onOpenAppFactory,
                    voiceCallRequestNonce = state.voiceCallRequestNonce,
                )
                WorkspaceTab.FILES -> FilesTab(
                    files = state.workspaceFiles,
                    loading = state.filesLoading,
                    filesError = state.filesError,
                    suggestedProjectRoot = state.suggestedProjectRoot,
                    onRefresh = onRefreshFiles,
                    onOpenFile = onOpenFile,
                    onUseSuggestedProjectRoot = onUseSuggestedProjectRoot,
                    onExport = {
                        exportProjectLauncher.launch("${state.activeProject?.slug ?: "project"}.zip")
                    },
                    onShareProject = onShareProject,
                    onBindFolder = { bindFolderLauncher.launch(null) },
                    onSetProjectRootDirectory = onSetProjectRootDirectory,
                    onCreateFile = onCreateFile,
                    onCreateFolder = onCreateFolder,
                    onRenameEntry = onRenameFile,
                    onMoveEntry = onMoveEntry,
                    onDeleteEntry = onDeleteFile,
                    onDuplicateEntry = onDuplicateFile,
                    onGitStatus = onGitStatus,
                    onGitCommit = onGitCommit,
                    onGitCheckout = onGitCheckout,
                    onGitDiff = onGitDiff,
                    onGitBranchList = onGitBranchList,
                    onGitPush = onGitPush,
                    onGitPull = onGitPull,
                    onSendPrompt = { text -> onSend(text, null) },
                )
                WorkspaceTab.TERMINAL -> TerminalScreen(
                    lines = state.projectTerminalLines,
                    isRunning = state.projectTerminalRunning,
                    onRun = onTerminalRun,
                    onInput = onTerminalInput,
                    onInterrupt = onTerminalInterrupt,
                    onClear = onTerminalClear,
                    onToggleTheme = { viewModel?.toggleTheme() },
                    themeMode = state.themeMode,
                    title = "Project Terminal",
                    subtitle = "${state.projectTerminalCwd} · Ubuntu PRoot",
                    liveOutput = state.projectTerminalLiveOutput,
                    currentCommand = state.projectTerminalCommand,
                    commandDraft = state.projectTerminalDraft,
                    onCommandDraftConsumed = onTerminalDraftConsumed,
                    promptPath = state.projectTerminalCwd,
                    onStop = onTerminalStop,
                    showThemeAction = false,
                    showQuickCommands = false,
                    compactHeader = true,
                    sessions = state.terminalSessions,
                    activeSessionId = state.activeTerminalSessionId,
                    onSelectSession = onSelectTerminalSession,
                    onNewSession = onNewTerminalSession,
                    onCloseSession = onCloseTerminalSession,
                )
                WorkspaceTab.CHANGES -> ChangesTab(
                    state.changes,
                    onUndoChanges,
                    onKeepChanges,
                    onUndoFileChange,
                    onKeepFileChange,
                    onOpenFile = onOpenFile,
                )
                WorkspaceTab.PREVIEW -> PreviewTab(
                    ready = state.previewReady,
                    url = state.previewUrl,
                    detectedPorts = detectedPorts,
                    onScanPorts = onScanPorts,
                    onAttachVisualInspection = onAttachVisualInspection,
                    projectRoot = workspaceDir ?: state.activeProject?.let { viewModel?.projectWorkspaceRoot(it) },
                    visualConsoleLogs = viewModel?.visualConsoleLogs?.collectAsStateWithLifecycle()?.value ?: emptyList(),
                    visualNetworkLogs = viewModel?.visualNetworkLogs?.collectAsStateWithLifecycle()?.value ?: emptyList(),
                    inspectedDomElement = viewModel?.inspectedDomElement?.collectAsStateWithLifecycle()?.value,
                    detectedPortInfos = viewModel?.detectedPortInfos?.collectAsStateWithLifecycle()?.value ?: emptyList(),
                    visualSnapshots = viewModel?.visualSnapshots?.collectAsStateWithLifecycle()?.value ?: emptyList(),
                    visualComparisonPair = viewModel?.visualComparisonPair?.collectAsStateWithLifecycle()?.value,
                    activeViewportPreset = viewModel?.activeViewportPreset?.collectAsStateWithLifecycle()?.value ?: com.vayro.app.visual.ViewportPreset.RESPONSIVE,
                    onRecordConsoleLog = { viewModel?.recordVisualConsoleLog(it) },
                    onRecordNetworkLog = { viewModel?.recordVisualNetworkLog(it) },
                    onSetInspectedDomElement = { viewModel?.setInspectedDomElement(it) },
                    onClearVisualLogs = { viewModel?.clearVisualLogs() },
                    onSetViewportPreset = { viewModel?.setViewportPreset(it) },
                    onSaveVisualSnapshot = { bmp, title, u, cb -> viewModel?.saveVisualSnapshot(bmp, title, u, cb) },
                    onSetBaselineSnapshot = { viewModel?.setBaselineComparisonSnapshot(it) },
                    onSetComparisonSnapshot = { viewModel?.setAfterComparisonSnapshot(it) },
                    onDispatchAiVisualBugReport = { u, notes, snap, cb -> viewModel?.dispatchAiVisualBugReport(u, notes, snap, cb) },
                    onRequestSwitchToChat = { selectedTab = WorkspaceTab.CHAT },
                )
                }
            }
        }
    }
}

private enum class WorkspaceCommand(val title: String, val keywords: String) {
    CHAT("Go to Chat", "chat conversation agent"),
    FILES("Go to Files", "files explorer project"),
    TERMINAL("Open Terminal", "terminal shell command"),
    CHANGES("Review Changes", "changes diff undo keep"),
    PREVIEW("Open Preview", "preview browser localhost"),
    REFRESH_FILES("Refresh Project Files", "refresh files reload"),
    BIND_FOLDER("Bind Folder from Device", "open folder bind directory storage import external"),
    APP_FACTORY("Android App Factory", "app factory blueprint scaffold build create generator"),
    MULTI_AGENT_TEAM("Multi-Agent Engineering Team", "agent team manager architect qa devops multi-agent studio"),
    CLOUD_HYBRID("Cloud + Phone Hybrid", "cloud worker offload remote build gpu runner hybrid"),
    MARKETPLACE("AI Developer Marketplace", "marketplace agents mcp skills templates catalog install store"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceCommandPalette(
    onDismiss: () -> Unit,
    onSelect: (WorkspaceCommand) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val normalized = query.trim().lowercase()
    val commands = WorkspaceCommand.entries.filter { command ->
        normalized.isBlank() ||
            command.title.lowercase().contains(normalized) ||
            command.keywords.contains(normalized)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp, max = 520.dp)
                .padding(horizontal = 18.dp),
        ) {
            Text("Command palette", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Jump between engineering tools without leaving the workspace.",
                Modifier.padding(top = 3.dp, bottom = 12.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search commands") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            )
            Spacer(Modifier.height(10.dp))
            if (commands.isEmpty()) {
                Text(
                    "No commands match your search.",
                    Modifier.padding(vertical = 18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(commands, key = { it.name }) { command ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(command) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // No right-side label: it only repeated the enum
                                // name in lowercase ("chat", "files", ...), the
                                // same identity as the title, and stole width
                                // from it. Search still matches on keywords.
                                Text(command.title, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatSwitcherDialog(
    chats: List<ProjectChat>,
    activeChatId: String?,
    switchingEnabled: Boolean,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onSwitch: (String) -> Unit,
    onRename: (String, String) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit = {},
    allowCreate: Boolean = true,
) {
    var renamingChatId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameTitle by rememberSaveable { mutableStateOf("") }
    var deletingChatId by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project chats") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (allowCreate) {
                    Button(onClick = onCreate, enabled = true, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("New chat")
                    }
                }
                if (!switchingEnabled) {
                    Text("New conversations can be created now. Switch to them after the running task finishes.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Medium: no empty state — zero chats used to show only the
                // "New chat" button with no explanation.
                if (chats.isEmpty()) {
                    Text("No chats yet. Tap New chat to start your first conversation.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(chats, key = { it.id }) { chat ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = switchingEnabled) { onSwitch(chat.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (chat.id == activeChatId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(chat.title, fontWeight = if (chat.id == activeChatId) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (chat.id == activeChatId) "Current chat" else "Saved conversation",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (allowCreate && switchingEnabled) {
                                    IconButton(
                                        onClick = {
                                            renamingChatId = chat.id
                                            renameTitle = chat.title
                                        },
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Rename chat", modifier = Modifier.size(16.dp))
                                    }
                                    if (chats.size > 1) {
                                        IconButton(
                                            onClick = {
                                                deletingChatId = chat.id
                                            },
                                            modifier = Modifier.size(48.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete chat",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                                if (chat.id == activeChatId) {
                                    Icon(Icons.Default.Check, "Current", tint = PocketGreen, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    if (renamingChatId != null) {
        AlertDialog(
            onDismissRequest = { renamingChatId = null },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = renameTitle,
                    onValueChange = { renameTitle = it },
                    label = { Text("Chat title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renamingChatId?.let { id -> onRename(id, renameTitle) }
                        renamingChatId = null
                    },
                    enabled = renameTitle.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingChatId = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (deletingChatId != null) {
        AlertDialog(
            onDismissRequest = { deletingChatId = null },
            title = { Text("Delete this chat?") },
            text = { Text("All messages and roadmap progress in this conversation will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingChatId?.let { id -> onDelete(id) }
                        deletingChatId = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingChatId = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun FilesTab(
    files: List<WorkspaceEntry>,
    loading: Boolean,
    filesError: String? = null,
    suggestedProjectRoot: String?,
    onRefresh: () -> Unit,
    onOpenFile: (WorkspaceEntry) -> Unit,
    onUseSuggestedProjectRoot: () -> Unit,
    onExport: () -> Unit,
    onShareProject: () -> Unit = {},
    onBindFolder: () -> Unit = {},
    onSetProjectRootDirectory: (String) -> Unit = {},
    onCreateFile: (String) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onRenameEntry: (String, String) -> Unit = { _, _ -> },
    onMoveEntry: (String, String) -> Unit = { _, _ -> },
    onDeleteEntry: (String) -> Unit = {},
    onDuplicateEntry: (String) -> Unit = {},
    onGitStatus: ((String) -> Unit) -> Unit = {},
    onGitCommit: (String, (Boolean, String) -> Unit) -> Unit = { _, _ -> },
    onGitCheckout: (String, Boolean, (Boolean, String) -> Unit) -> Unit = { _, _, _ -> },
    onGitDiff: ((String) -> Unit) -> Unit = {},
    onGitBranchList: ((List<String>) -> Unit) -> Unit = {},
    onGitPush: (((Boolean, String) -> Unit) -> Unit) = {},
    onGitPull: (((Boolean, String) -> Unit) -> Unit) = {},
    onSendPrompt: (String) -> Unit = {},
) {
    var expandedDirectories by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var fileQuery by rememberSaveable { mutableStateOf("") }
    var showCreateFileDialog by rememberSaveable { mutableStateOf(false) }
    var showCreateFolderDialog by rememberSaveable { mutableStateOf(false) }
    var showGitDialog by rememberSaveable { mutableStateOf(false) }
    var showGitHubIssues by rememberSaveable { mutableStateOf(false) }
    var targetParentDir by rememberSaveable { mutableStateOf<String?>(null) }
    var entryToRename by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var entryToMove by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var entryToDelete by remember { mutableStateOf<WorkspaceEntry?>(null) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    LaunchedEffect(files.map { it.path }) {
        val directories = files.asSequence().filter { it.isDirectory }.map { it.path }.toSet()
        expandedDirectories = expandedDirectories.filter { it in directories }
    }
    val expandedSet = expandedDirectories.toSet()
    val normalizedQuery = fileQuery.trim().lowercase()
    val visibleFiles = if (normalizedQuery.isBlank()) {
        files.filter { entry ->
            val segments = entry.path.split('/')
            segments.size == 1 || (1 until segments.size).all { depth ->
                segments.take(depth).joinToString("/") in expandedSet
            }
        }
    } else {
        files.filter { entry ->
            !entry.isDirectory && entry.path.lowercase().contains(normalizedQuery)
        }
    }
    val directChildCounts = files.filter { candidate ->
        candidate.path.contains('/')
    }.groupingBy { candidate -> candidate.path.substringBeforeLast('/') }.eachCount()

    // v3.2.38 (J): file-list scroll survives tab round-trips like its filters do.
    LazyColumn(
        state = rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
            androidx.compose.foundation.lazy.LazyListState()
        },
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                ) {
                    Text(
                        "Files",
                        modifier = Modifier.padding(start = 14.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Row(
                        // Medium: 7 buttons + Collapse-all overflowed on
                        // narrow phones; the row now scrolls horizontally.
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { targetParentDir = null; showCreateFileDialog = true }) {
                            Icon(Icons.Default.Add, "New file")
                        }
                        IconButton(onClick = { targetParentDir = null; showCreateFolderDialog = true }) {
                            Icon(Icons.Default.Folder, "New folder")
                        }
                        if (expandedDirectories.isNotEmpty()) {
                            TextButton(onClick = { expandedDirectories = emptyList() }) {
                                Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Collapse all", fontSize = 11.sp)
                            }
                        }
                        IconButton(onClick = onBindFolder) { Icon(Icons.Default.Folder, "Bind folder from device") }
                        if (!loading && files.any { !it.isDirectory }) {
                            IconButton(onClick = onExport) { Icon(Icons.Default.Download, "Export project as ZIP") }
                            IconButton(onClick = onShareProject) { Icon(Icons.Default.Share, "Share project (ZIP)") }
                        }
                        IconButton(onClick = { showGitDialog = true }) {
                            Icon(Icons.Default.Code, "Git operations")
                        }
                        if (loading) {
                            CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh files") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = fileQuery,
                onValueChange = { fileQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search project files") },
                placeholder = { Text("Search by path or filename") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (fileQuery.isNotEmpty()) {
                        TextButton(onClick = { fileQuery = "" }) { Text("Clear") }
                    }
                },
            )
        }
        if (suggestedProjectRoot != null) {
            item(key = "suggested-project-root") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Project folder detected", fontWeight = FontWeight.Bold)
                        Text(
                            "Use $suggestedProjectRoot as the project root so Chat, Terminal, Changes, and Preview all run from the same folder.",
                            fontSize = 13.sp,
                        )
                        Button(onClick = onUseSuggestedProjectRoot, modifier = Modifier.fillMaxWidth()) {
                            Text("Use $suggestedProjectRoot as project root")
                        }
                    }
                }
            }
        }
        if (!loading && filesError != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.error)
                        Text("Could not load project files", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            filesError,
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onRefresh, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry")
                        }
                    }
                }
            }
        }
        if (!loading && files.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(36.dp), tint = PocketOrange)
                        Text("No files in workspace yet", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "Ask the agent to create files, or open and bind an existing project folder from your phone storage.",
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onBindFolder,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open / Bind folder from device")
                        }
                    }
                }
            }
        } else if (!loading && normalizedQuery.isNotBlank() && visibleFiles.isEmpty()) {
            item { EmptyState(Icons.Default.Search, "No matching files", "Try a different filename or path.") }
        }
        if (!loading && normalizedQuery.isNotBlank() && visibleFiles.isNotEmpty()) {
            item(key = "file-search-count") {
                val label = visibleFiles.size.toString() + " matching file" + if (visibleFiles.size == 1) "" else "s"
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(visibleFiles, key = { it.path }) { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (entry.isDirectory) {
                            expandedDirectories = if (entry.path in expandedSet) {
                                expandedDirectories.filterNot { it == entry.path || it.startsWith("${entry.path}/") }
                            } else {
                                expandedDirectories + entry.path
                            }
                        } else {
                            onOpenFile(entry)
                        }
                    }
                    .padding(start = (entry.depth * 20).dp)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entry.isDirectory) {
                    Icon(
                        if (entry.path in expandedSet) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        if (entry.path in expandedSet) "Collapse folder" else "Expand folder",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Icon(
                    if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    null,
                    tint = if (entry.isDirectory) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(11.dp))
                Text(
                    if (entry.isDirectory) "${entry.name} (${directChildCounts[entry.path] ?: 0})" else entry.name,
                    Modifier.weight(1f),
                    color = if (!entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (entry.isDirectory) {
                    IconButton(
                        onClick = { onSetProjectRootDirectory(entry.path) },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Set as working directory",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        )
                    }
                }
                if (!entry.isDirectory) {
                    Spacer(Modifier.width(8.dp))
                    Text(formatFileSize(entry.sizeBytes), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FileEntryContextMenu(
                    entry = entry,
                    onNewFileInDir = { dir ->
                        targetParentDir = dir
                        showCreateFileDialog = true
                    },
                    onNewFolderInDir = { dir ->
                        targetParentDir = dir
                        showCreateFolderDialog = true
                    },
                    onRename = { entryToRename = it },
                    onMove = { entryToMove = it },
                    onDuplicate = { onDuplicateEntry(it.path) },
                    onDelete = { entryToDelete = it },
                    onCopyPath = { path ->
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(path))
                    },
                )
            }
            if (!entry.isDirectory) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), modifier = Modifier.padding(start = (entry.depth * 20 + 42).dp))
            }
        }
    }

    if (showCreateFileDialog) {
        CreateFileDialog(
            parentDirectory = targetParentDir,
            onDismiss = { showCreateFileDialog = false },
            onConfirm = { fullPath ->
                showCreateFileDialog = false
                onCreateFile(fullPath)
            },
        )
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            parentDirectory = targetParentDir,
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = { fullPath ->
                showCreateFolderDialog = false
                onCreateFolder(fullPath)
            },
        )
    }

    entryToRename?.let { entry ->
        RenameEntryDialog(
            entry = entry,
            onDismiss = { entryToRename = null },
            onConfirm = { newRelativePath ->
                entryToRename = null
                onRenameEntry(entry.path, newRelativePath)
            },
        )
    }

    entryToMove?.let { entry ->
        MoveEntryDialog(
            entry = entry,
            onDismiss = { entryToMove = null },
            onConfirm = { targetPath ->
                entryToMove = null
                onMoveEntry(entry.path, targetPath)
            },
        )
    }

    entryToDelete?.let { entry ->
        DeleteEntryDialog(
            entry = entry,
            onDismiss = { entryToDelete = null },
            onConfirm = {
                entryToDelete = null
                onDeleteEntry(entry.path)
            },
        )
    }

    if (showGitDialog) {
        GitOperationsDialog(
            onDismiss = { showGitDialog = false },
            onStatus = onGitStatus,
            onCommit = onGitCommit,
            onCheckout = onGitCheckout,
            onDiff = onGitDiff,
            onBranchList = onGitBranchList,
            onPush = onGitPush,
            onPull = onGitPull,
            onOpenIssues = {
                showGitDialog = false
                showGitHubIssues = true
            },
        )
    }

    if (showGitHubIssues) {
        GitHubIssuesBottomSheet(
            repoFullName = "",
            token = "",
            onDismiss = { showGitHubIssues = false },
            onSolveWithAI = { issuePrompt ->
                showGitHubIssues = false
                onSendPrompt(issuePrompt)
            },
        )
    }
}

@Composable
private fun ChatTab(
    onCallActiveChange: (Boolean) -> Unit = {},
    messages: List<ChatMessage>,
    approval: ToolRequest?,
    liveProcess: List<ActivityItem>,
    changes: List<ChangeItem> = emptyList(),
    isRunning: Boolean,
    streamingMessageId: String?,
    onSend: (String, String?) -> Unit,
    onStop: () -> Unit,
    onApproval: (Boolean) -> Unit,
    listState: LazyListState,
    taskStartedAtMillis: Long?,
    taskFinishedAtMillis: Long?,
    thinkingActive: Boolean,
    agentKind: AgentKind,
    workspaceFiles: List<WorkspaceEntry>,
    pendingAttachments: List<ChatAttachment>,
    onAttach: (Array<String>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onSaveAttachment: (ChatAttachment) -> Unit = {},
    onRunInTerminal: (String) -> Unit,
    onOpenLink: ((String) -> Unit)? = null,
    onInstallApkPath: (String) -> Unit = {},
    onShareApkPath: (String) -> Unit = {},
    onSaveApkPath: (String) -> Unit = {},
    onLoadDeliveredApk: suspend (String) -> DeliveredApkData? = { null },
    onPreviewDelivered: (String, String) -> Unit = { _, _ -> },
    onLocateApk: (() -> Unit)? = null,
    // U6d: the end-of-run changes review lives in the chat as a card,
    // wired to the same keep/revert handlers as the Changes tab.
    onKeepChanges: () -> Unit = {},
    onUndoChanges: () -> Unit = {},
    // U8a: tells the workspace when a sheet/dialog covers the chat, so
    // the background list can be gesture-locked and follow-scroll paused.
    onModalOverlayChange: (Boolean) -> Unit = {},
    // v3.2.42: tells the workspace when the composer field holds
    // focus (the editing session) — the workspace hides its bottom
    // bar for the session, replacing its old derived IME boolean.
    onInputFocusChange: (Boolean) -> Unit = {},
    readOnly: Boolean = false,
    readOnlyBlocked: Boolean = false,
    onContinueHere: () -> Unit = {},
    executionMode: ExecutionMode = ExecutionMode.UNIFIED,
    onSelectExecutionMode: (ExecutionMode) -> Unit = {},
    scratchpadItems: List<ScratchpadItem> = emptyList(),
    scratchpadExpanded: Boolean = false,
    onToggleScratchpad: () -> Unit = {},
    onAddRoadmapComment: (roadmapId: String, text: String, stepId: String?) -> Unit = { _, _, _ -> },
    onApproveAndBuildRoadmap: (roadmap: ActiveRoadmap, additionalInstructions: String?) -> Unit = { _, _ -> },
    orchestratorSnapshot: com.vayro.app.session.OrchestratorSnapshot? = null,
    onRollbackToBaseline: () -> Unit = {},
    emergencyKillSwitchActive: Boolean = false,
    onResetEmergencyKillSwitch: () -> Unit = {},
    onApprovalWithLevel: (com.vayro.app.security.PermissionLevel, Boolean) -> Unit = { _, _ -> },
    onAttachUris: (List<Uri>) -> Unit = {},
    onSelectAgent: (AgentKind) -> Unit = {},
    // v3.2.38 (Part P): composer picker provider section.
    providerChoices: List<MainViewModel.ProviderChoice> = emptyList(),
    onPickProvider: (ProviderKind) -> Unit = {},
    fetchProviderModels: suspend (ProviderKind) -> List<String> = { emptyList() },
    onPickProviderModel: (ProviderKind, String) -> Unit = { _, _ -> },
    onManageProviders: (() -> Unit)? = null,
    freeModeEnabled: Boolean = false,
    projectName: String = "",
    onPushToGitHub: () -> Unit = {},
    onOpenGitHubBuild: () -> Unit = {},
    onOpenAppFactory: () -> Unit = {},
    voiceCallRequestNonce: Long = 0L,
) {
    val view = LocalView.current
    // Keep the screen on while the selected agent is working in this chat. Released automatically
    // when the task finishes or the user leaves the chat tab.
    var showLiveActivity by rememberSaveable { mutableStateOf(false) }
    var pastActivity by remember { mutableStateOf<Pair<List<ActivityItem>, String?>?>(null) }
    // U8a: any modal surface over the chat (Activity sheet, add-to-chat
    // sheet, agent picker, voice call) locks the background list. The
    // tool/agent sheet states are declared further below, so this is a
    // derived value computed at the list (see below).
    DisposableEffect(isRunning) {
        view.keepScreenOn = isRunning
        onDispose { view.keepScreenOn = false }
    }
    var prompt by rememberSaveable { mutableStateOf("") }
    // v3.2.40 (Scope F): the TXT chip's content + the viewer target.
    var pastedText by rememberSaveable { mutableStateOf<String?>(null) }
    var viewingPasted by remember { mutableStateOf<String?>(null) }
    var inputFocused by remember { mutableStateOf(false) }
    var inputLineCount by remember { mutableStateOf(1) }
    var inputSectionHeight by remember { mutableStateOf(0.dp) }
    var showWorkspaceControls by rememberSaveable { mutableStateOf(false) }
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    var toolsMenuHeightPx by remember { mutableStateOf(0) }
    val plusRotation by animateFloatAsState(
        targetValue = if (toolsOpen) 45f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tools_toggle_rotation",
    )
    val chatContext = LocalContext.current
    var showYouTubeDialog by rememberSaveable { mutableStateOf(false) }
    var showTikTokDialog by rememberSaveable { mutableStateOf(false) }
    var showWebLinkDialog by rememberSaveable { mutableStateOf(false) }
    var showGitHubDialog by rememberSaveable { mutableStateOf(false) }
    var showAgentSheet by rememberSaveable { mutableStateOf(false) }
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = cameraPhotoUri
        cameraPhotoUri = null
        if (success && uri != null) onAttachUris(listOf(uri))
    }
    var mentionQuery by remember { mutableStateOf<String?>(null) }
    var activeCommentTarget by remember { mutableStateOf<Triple<String, String?, String>?>(null) }
    val chatScope = rememberCoroutineScope()

    if (showYouTubeDialog) {
        YouTubeVideoDialog(
            initialUrl = if (YouTubeUrlValidator.isValid(prompt)) prompt.trim() else "",
            onDismiss = { showYouTubeDialog = false },
            onAnalyze = { videoInfo, userQuery, focusMode ->
                val videoPrompt = YouTubeUrlValidator.buildGeminiVideoPrompt(
                    info = videoInfo,
                    userQuery = userQuery,
                    focusMode = focusMode,
                )
                onSend(videoPrompt, null)
                prompt = ""
            },
        )
    }

    if (showTikTokDialog) {
        com.vayro.app.ui.components.TikTokLinkDialog(
            onDismiss = { showTikTokDialog = false },
            onAnalyze = { url, question ->
                showTikTokDialog = false
                chatScope.launch {
                    val info = com.vayro.app.ui.components.fetchTikTokInfo(url)
                    val message = buildString {
                        appendLine("TikTok video shared: $url")
                        if (info != null) {
                            appendLine("Title: ${info.title}")
                            appendLine("Author: ${info.authorName} (${info.authorUrl})")
                            appendLine("Thumbnail: ${info.thumbnailUrl}")
                            appendLine()
                            appendLine("Note: TikTok gives no public transcript. Base your analysis on the title, author and thumbnail above, and say clearly what else could not be obtained.")
                        } else {
                            appendLine("Note: the app could not fetch public details for this link (it may be private, removed, or region-locked). Only the link itself is available - say so clearly.")
                        }
                        if (!question.isNullOrBlank()) {
                            appendLine()
                            appendLine("My question: $question")
                        }
                    }
                    onSend(message.trim(), null)
                }
            },
        )
    }

    if (showWebLinkDialog) {
        com.vayro.app.ui.components.WebLinkDialog(
            onDismiss = { showWebLinkDialog = false },
            onFetch = { url ->
                showWebLinkDialog = false
                chatScope.launch {
                    val page = com.vayro.app.ui.components.fetchWebPageText(url)
                    if (page == null) {
                        Toast.makeText(chatContext, "Could not fetch that page.", Toast.LENGTH_SHORT).show()
                    } else {
                        val (pageTitle, pageText) = page
                        onSend(
                            buildString {
                                appendLine("Web page shared: $url")
                                if (pageTitle.isNotBlank()) appendLine("Title: $pageTitle")
                                appendLine()
                                appendLine(pageText.take(6000))
                                if (pageText.length > 6000) appendLine("\n… (page text truncated)")
                                appendLine()
                                appendLine("Use this page content as context for my request.")
                            },
                            null,
                        )
                    }
                }
            },
        )
    }

    if (showGitHubDialog) {
        com.vayro.app.ui.components.GitHubRepoDialog(
            onDismiss = { showGitHubDialog = false },
            onInspect = { url ->
                showGitHubDialog = false
                onSend("Clone this GitHub repository into the workspace, inspect its structure (file tree, README, manifest/build files, entry points), and summarize what it does, its main features, and how it is built: $url", null)
            },
        )
    }

    if (showLiveActivity) {
        ActivitySheet(
            items = liveProcess,
            changes = changes,
            isRunning = true,
            workedText = null,
            runStartedAtMillis = taskStartedAtMillis,
            onDismiss = { showLiveActivity = false },
        )
    }
    pastActivity?.let { (items, worked) ->
        ActivitySheet(
            items = items,
            changes = changes,
            isRunning = false,
            workedText = worked,
            onDismiss = { pastActivity = null },
        )
    }

    activeCommentTarget?.let { (roadmapId, stepId, stepTitle) ->
        AddRoadmapCommentDialog(
            stepTitle = stepTitle.takeIf(String::isNotBlank),
            onDismiss = { activeCommentTarget = null },
            onSubmit = { commentText ->
                onAddRoadmapComment(roadmapId, commentText, stepId)
                activeCommentTarget = null
            },
        )
    }

    val mentionCandidates = remember(workspaceFiles, mentionQuery) {
        val query = mentionQuery?.trim()?.lowercase() ?: return@remember emptyList<WorkspaceEntry>()
        workspaceFiles
            .asSequence()
            .filter { !it.isDirectory }
            .filter { query.isBlank() || it.path.lowercase().contains(query) }
            .take(8)
            .toList()
    }

    fun updateMentionQuery(nextPrompt: String) {
        val token = nextPrompt.substringAfterLast(' ').substringAfterLast('\n')
        mentionQuery = if (token.startsWith("@") && token.length <= 160) token.drop(1) else null
    }

    val context = LocalContext.current

    // Inline voice input: SpeechRecognizer runs in-app (see ui/voice), so the
    // mic never opens the system speech dialog. The composer swaps to
    // VoiceInputBar while a session is active.
    val voiceRecognizer = remember { VoiceRecognizer(context) }
    var voiceInputActive by remember { mutableStateOf(false) }
    DisposableEffect(voiceRecognizer) {
        onDispose { voiceRecognizer.release() }
    }

    fun beginVoiceSession() {
        if (!voiceRecognizer.isAvailable()) {
            Toast.makeText(context, "Voice recognition not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }
        toolsOpen = false
        voiceInputActive = true
        voiceRecognizer.start()
    }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            beginVoiceSession()
        } else {
            Toast.makeText(context, "Microphone permission is required for voice input", Toast.LENGTH_SHORT).show()
        }
    }

    val onStartVoiceInput = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            beginVoiceSession()
        }
    }

    var voiceCallActive by remember { mutableStateOf(false) }
    // v3.2.37 (A2): tell the workspace chrome when the call takes over the
    // screen, so the header/bottom bar stop bleeding through the overlay.
    LaunchedEffect(voiceCallActive) { onCallActiveChange(voiceCallActive) }
    DisposableEffect(Unit) { onDispose { onCallActiveChange(false) } }
    // v3.2.42: report the composer's editing session (field focus)
    // so the workspace can hide its bottom bar for the session.
    LaunchedEffect(inputFocused) { onInputFocusChange(inputFocused) }
    DisposableEffect(Unit) { onDispose { onInputFocusChange(false) } }

    fun startVoiceCall() {
        if (!voiceRecognizer.isAvailable()) {
            Toast.makeText(context, "Voice recognition not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }
        voiceRecognizer.cancel()
        voiceInputActive = false
        voiceCallActive = true
    }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            startVoiceCall()
        } else {
            Toast.makeText(context, "Microphone permission is required for voice calls", Toast.LENGTH_SHORT).show()
        }
    }

    val onStartVoiceCall = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            callPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startVoiceCall()
        }
    }

    // v3.2.34 (U2): a voice call requested from the chat home (composer
    // waveform button or the "+" sheet's "Voice call" row) arrives via
    // this nonce once the workspace chat exists — present the call here
    // so the mic-permission flow and recognizer are the workspace's own.
    var handledVoiceCallNonce by rememberSaveable { mutableStateOf(voiceCallRequestNonce) }
    LaunchedEffect(voiceCallRequestNonce) {
        if (voiceCallRequestNonce != 0L && voiceCallRequestNonce != handledVoiceCallNonce) {
            handledVoiceCallNonce = voiceCallRequestNonce
            onStartVoiceCall()
        }
    }

    fun appendSpokenText(spoken: String) {
        if (spoken.isBlank()) return
        prompt = if (prompt.isBlank()) spoken else "$prompt $spoken"
        updateMentionQuery(prompt)
    }
    // v3.2.42 (Architecture A — JetChat structure): this Column no
    // longer consumes the IME inset. The tab's ONE IME consumer is
    // the composer container at the bottom (imePadding there), so
    // the keyboard's own inset animation is the single driver of
    // motion: the banners and the message list keep the full height
    // and the list draws behind the keyboard while it slides.
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = showWorkspaceControls && scratchpadItems.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScratchpadPill(
                        items = scratchpadItems,
                        isRunning = isRunning,
                        isExpanded = scratchpadExpanded,
                        onToggleExpand = onToggleScratchpad,
                    )
                }
                if (scratchpadExpanded) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                    ) {
                        ScratchpadCard(
                            items = scratchpadItems,
                            onCollapse = onToggleScratchpad,
                        )
                    }
                }
            }
        }
        if (orchestratorSnapshot != null && orchestratorSnapshot.state != com.vayro.app.session.EngineeringState.IDLE) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
            ) {
                EngineeringStateBanner(
                    snapshot = orchestratorSnapshot,
                    onRollbackToBaseline = onRollbackToBaseline,
                )
            }
        }
        if (emergencyKillSwitchActive) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Emergency Kill Switch Active",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "All agent tools and processes are blocked.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                        )
                    }
                    TextButton(onClick = onResetEmergencyKillSwitch) {
                        Text("Reset", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    val anyOverlayOpen = showLiveActivity || pastActivity != null || toolsOpen ||
        showAgentSheet || voiceCallActive
    LaunchedEffect(anyOverlayOpen) { onModalOverlayChange(anyOverlayOpen) }
    // v3.2.42: tapping the conversation ends the editing session
    // (focus leaves the field, the keyboard hides, the composer
    // returns to its one-row state) — the workspace list's half of
    // the focus-session pattern the home list uses. Taps only;
    // scrolling is untouched.
    val chatFocusManager = LocalFocusManager.current
        Box(
            Modifier
                .weight(1f)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { chatFocusManager.clearFocus() })
                },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                // v3.2.42: reverseLayout — index 0 is the visual
                // bottom (the end-of-run changes card when present,
                // else the approval card, else the working row, else
                // the newest message) and the layout anchors there.
                // Items are emitted in that order below: tail cards
                // first, then the messages newest-first, the empty
                // state last (the visual top). The list needs NO ime
                // handling and NO follow effect: it draws behind the
                // keyboard while the composer rides it, and new
                // content grows upward from the anchor.
                reverseLayout = true,
                // U8a: while any sheet/dialog covers the chat, the list
                // ignores user gestures entirely — sheet drags and inner
                // scrolls can no longer leak into the conversation, and
                // the list stays exactly where the user left it.
                userScrollEnabled = !anyOverlayOpen,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // U6d: the end-of-run changes review is a card IN the
                // chat flow. Keep/Revert right here; the Changes tab
                // stays available for the detailed per-file diffs.
                // (Index 0 in the reversed list = the visual bottom,
                // directly under the most recent message.)
                if (!isRunning && changes.isNotEmpty()) {
                    item(key = "changes-review") {
                        ChangesReviewCard(
                            changes = changes,
                            onKeep = onKeepChanges,
                            onUndo = onUndoChanges,
                        )
                    }
                }
                approval?.let { request ->
                    item(key = "approval-card") { ApprovalCard(request, onApproval, onApprovalWithLevel) }
                }
                if (isRunning) {
                    // v3.2.34 (U4): the working row sits directly
                    // under the most recent message — Claude-style,
                    // in the conversation flow — with the approval
                    // card right below it (U6c). While a run streams,
                    // the reversed list's bottom anchor keeps this
                    // tail visible with no scroll calls at all.
                    item(key = "working-row") {
                        WorkingBar(
                            items = liveProcess,
                            thinkingActive = thinkingActive,
                            startedAtMillis = taskStartedAtMillis,
                            onClick = { showLiveActivity = true },
                        )
                    }
                }
                // Crash fix (2026-10-13): keys must be unique even if the message
                // list ever holds two rows with the same id (duplicate ids used
                // to throw IllegalArgumentException and kill the app). The index
                // suffix guarantees uniqueness; the id prefix keeps item identity
                // stable for the append-only chat list.
                // v3.2.42: the suffix is the message's CHRONOLOGICAL
                // index (not its reversed list position), so an
                // append — which shifts every reversed position by
                // one — leaves every existing key untouched. The
                // contentType lets the list recycle rows within a
                // message kind.
                itemsIndexed(
                    messages.asReversed(),
                    key = { revIndex, msg -> "${msg.id}#${messages.size - 1 - revIndex}" },
                    contentType = { _, msg ->
                        when {
                            msg.workItems.isNotEmpty() -> "work"
                            msg.fromUser -> "user"
                            else -> "assistant"
                        }
                    },
                ) { _, rawMessage ->
                    val message = remember(rawMessage.id, rawMessage.text) {
                        if (!isRunning && rawMessage.roadmap == null && !rawMessage.fromUser && rawMessage.text.length <= 30000 && rawMessage.text.contains("### Implementation Roadmap", ignoreCase = true)) {
                            val parsed = RoadmapParser.parseFromText(rawMessage.text, executionMode)
                            if (parsed != null) rawMessage.copy(roadmap = parsed) else rawMessage
                        } else {
                            rawMessage
                        }
                    }
                    if (message.workItems.isNotEmpty()) {
                        WorkBlockCard(message, onOpenActivity = { pastActivity = message.workItems to (if (message.workItems.lastOrNull()?.title?.startsWith("Task stopped", true) == true) "Task stopped" else "Task completed") })
                    } else {
                        MessageBubble(
                            message = message,
                            isStreaming = rawMessage.id == streamingMessageId,
                            onRunInTerminal = onRunInTerminal,
                            onOpenAttachment = onOpenAttachment,
                            onSaveAttachment = onSaveAttachment,
                            onOpenLink = onOpenLink,
                            onInstallApkPath = onInstallApkPath,
                            onShareApkPath = onShareApkPath,
                            onSaveApkPath = onSaveApkPath,
                            onLoadDeliveredApk = onLoadDeliveredApk,
                            onPreviewDelivered = onPreviewDelivered,
                            onLocateApk = onLocateApk,
                            onAddRoadmapComment = { stepId ->
                                val stepTitle = message.roadmap?.steps?.firstOrNull { it.id == stepId }?.title ?: ""
                                message.roadmap?.let { r ->
                                    activeCommentTarget = Triple(r.id, stepId, stepTitle)
                                }
                            },
                            onApproveAndBuildRoadmap = {
                                message.roadmap?.let { r ->
                                    onApproveAndBuildRoadmap(r, null)
                                }
                            },
                            onOpenPasted = { viewingPasted = it },
                            // v3.2.45 (Item 3): Regenerate resends the
                            // user's last instruction as a NEW turn
                            // through the normal send path, so the
                            // v3.2.44 completion gate applies as usual.
                            onRegenerate = {
                                if (!isRunning) {
                                    messages.lastOrNull { it.fromUser }?.let { lastUser ->
                                        val resend = listOf(lastUser.text, lastUser.pastedText.orEmpty())
                                            .filter { it.isNotBlank() }.joinToString("\n\n")
                                        if (resend.isNotBlank()) onSend(resend, null)
                                    }
                                }
                            },
                        )
                    }
                }
                if (messages.isEmpty() && !isRunning && liveProcess.isEmpty() && !thinkingActive && approval == null) {
                    item(key = "empty-state") {
                        EmptyState(
                            Icons.AutoMirrored.Filled.Chat,
                            "Start a conversation",
                            "Ask Kodex anything — describe what to build, or mention a file to discuss.",
                        )
                    }
                }
            }
            // v3.2.40 (Scope H): floating scroll-to-bottom arrow — in
            // the list layer only (under every sheet/dialog).
            // v3.2.43 (D3): shown as soon as the user has scrolled
            // away from the newest even a little (the shared
            // shouldShowScrollToNewest rule), not after a screenful;
            // one tap travels back with the D4 glide (ScrollGlide) —
            // swift and smooth, landing exactly on index 0, the
            // newest end (working row included). Streaming never
            // auto-yanks: this arrow is the way back.
            ScrollToBottomButton(
                visible = !anyOverlayOpen && shouldShowScrollToNewest(
                    firstVisibleIndex = listState.firstVisibleItemIndex,
                    firstVisibleOffsetPx = listState.firstVisibleItemScrollOffset,
                ),
                onClick = {
                    chatScope.launch {
                        listState.glideToNewest()
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            )
        }
        if (readOnly) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text("Read-only history", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (readOnlyBlocked) {
                                "Another project has a running task. You can read this chat, but cannot send a message."
                            } else {
                                "The other task finished. Open this project to continue chatting."
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!readOnlyBlocked) {
                        TextButton(onClick = onContinueHere) { Text("Open") }
                    }
                }
            }
        } else Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // v3.2.42: the tab's ONE IME consumer (see the
                    // ChatTab root Column's note). Layout-phase only,
                    // fed by the platform's animated insets — the
                    // composer rides the keyboard's own curve.
                    .imePadding()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                AnimatedVisibility(
                    visible = showWorkspaceControls,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    ModeSelectorBar(
                        currentMode = executionMode,
                        onModeSelected = onSelectExecutionMode,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                if (pendingAttachments.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pendingAttachments.forEach { attachment ->
                            AttachmentChip(
                                attachment = attachment,
                                onOpen = null,
                                onRemove = { onRemoveAttachment(attachment.id) },
                            )
                        }
                    }
                }

                val canSend = prompt.isNotBlank() || pendingAttachments.isNotEmpty() || pastedText != null

                if (mentionCandidates.isNotEmpty()) {
                    Surface(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                "Mention a file",
                                Modifier.padding(start = 14.dp, top = 10.dp, bottom = 5.dp),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            mentionCandidates.forEach { candidate ->
                                TextButton(
                                    onClick = {
                                        val currentToken = prompt.substringAfterLast(' ').substringAfterLast('\n')
                                        val prefix = prompt.dropLast(currentToken.length)
                                        prompt = prefix + "@" + candidate.path + " "
                                        mentionQuery = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(candidate.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            candidate.path,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (toolsOpen) {
                    AddToChatSheet(
                        onDismiss = { toolsOpen = false },
                        showWorkActions = true,
                        workActionsEnabled = true,
                        showConnectors = false,
                        showCustomize = true,
                        customizeActive = showWorkspaceControls,
                        onCamera = {
                            toolsOpen = false
                            val uri = createCameraImageUri(context)
                            cameraPhotoUri = uri
                            cameraLauncher.launch(uri)
                        },
                        onPhotos = {
                            toolsOpen = false
                            onAttach(arrayOf("image/*", "video/*"))
                        },
                        onFiles = {
                            toolsOpen = false
                            onAttach(arrayOf("*/*"))
                        },
                        onYouTube = {
                            toolsOpen = false
                            showYouTubeDialog = true
                        },
                        onTikTok = {
                            toolsOpen = false
                            showTikTokDialog = true
                        },
                        onWebLink = {
                            toolsOpen = false
                            showWebLinkDialog = true
                        },
                        onGitHub = {
                            toolsOpen = false
                            showGitHubDialog = true
                        },
                        onPushToGitHub = {
                            toolsOpen = false
                            onPushToGitHub()
                        },
                        onGitHubBuild = {
                            toolsOpen = false
                            onOpenGitHubBuild()
                        },
                        onOpenFactory = {
                            toolsOpen = false
                            onOpenAppFactory()
                        },
                        onOpenConnectors = {},
                        onCustomize = {
                            toolsOpen = false
                            showWorkspaceControls = !showWorkspaceControls
                        },
                        onVoiceCall = {
                            toolsOpen = false
                            onStartVoiceCall()
                        },
                    )
                }
                if (showAgentSheet) {
                    SelectAgentSheet(
                        selected = agentKind,
                        freeModeEnabled = freeModeEnabled,
                        onSelect = { kind ->
                            showAgentSheet = false
                            onSelectAgent(kind)
                        },
                        onMoreAgents = null,
                        onDismiss = { showAgentSheet = false },
                        providerChoices = providerChoices,
                        onPickProvider = { kind ->
                            showAgentSheet = false
                            onPickProvider(kind)
                        },
                        fetchProviderModels = fetchProviderModels,
                        onPickProviderModel = { kind, model ->
                            showAgentSheet = false
                            onPickProviderModel(kind, model)
                        },
                        onManageProviders = onManageProviders?.let { manage ->
                            { showAgentSheet = false; manage() }
                        },
                    )
                }

                if (voiceInputActive) {
                    VoiceInputBar(
                        recognizer = voiceRecognizer,
                        onCancel = { voiceInputActive = false },
                        onStop = { spoken ->
                            appendSpokenText(spoken)
                            voiceInputActive = false
                        },
                        onSend = { spoken ->
                            val merged = listOf(prompt, spoken).filter { it.isNotBlank() }.joinToString(" ").trim()
                            voiceInputActive = false
                            if (merged.isNotBlank() || pastedText != null) {
                                onSend(merged, pastedText)
                                prompt = ""
                                pastedText = null
                                mentionQuery = null
                            }
                        },
                        onError = { message ->
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            voiceInputActive = false
                        },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                // v3.2.40 (Scope F/F2): two-state bottom area, cloned
                // from the user's Claude reference — the same contract
                // as the home composer: the text field never changes
                // parent (focus and the keyboard survive the flip);
                // empty + keyboard closed shows ONE row (+, field,
                // mic, voice); editing or content present adds the
                // controls row (tools +, provider pill, mic,
                // send/stop) under the field.
                // v3.2.42 (Architecture A): same single-motion rule
                // as the home composer — the controls row belongs to
                // the field's editing session, driven by its FOCUS
                // (inputFocused, tracked by the field's own
                // onFocusChanged since Scope F2), not by an IME
                // inset read. A boolean IME read could only flip
                // discretely against the keyboard's continuous slide;
                // focus flips with the tap that opens the keyboard,
                // and the continuous motion is left entirely to the
                // one imePadding consumer above.
                val controlsVisible = inputFocused || prompt.isNotEmpty() || pastedText != null
                val sendStopButton: @Composable () -> Unit = {
                    if (isRunning) {
                        Box(
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable(onClick = onStop),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.error,
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop AI task",
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable(
                                    enabled = true,
                                    onClick = {
                                        if (canSend) {
                                            onSend(prompt, pastedText)
                                            prompt = ""
                                            pastedText = null
                                            mentionQuery = null
                                        } else {
                                            onStartVoiceCall()
                                        }
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (canSend) Icons.Default.ArrowUpward else Icons.Default.GraphicEq,
                                    contentDescription = if (canSend) "Send" else "Voice mode",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (canSend) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    // v3.2.42 (Architecture A): NO animateContentSize
                    // here — the v3.2.41 spring owned this card's
                    // height while the IME inset animation moved the
                    // same space on the platform's curve; two
                    // unsynchronised animations on one surface read
                    // on screen as a "double layer". The card's
                    // height now changes only with its CONTENT (a
                    // discrete state); every continuous motion comes
                    // from the keyboard via the composer container's
                    // imePadding.
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        if (pastedText != null) {
                            PastedContentChip(
                                onOpen = { viewingPasted = pastedText },
                                onRemove = { pastedText = null },
                                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            if (!controlsVisible) {
                                ComposerCircleButton(
                                    contentDescription = if (toolsOpen) "Hide tools" else "Show tools",
                                    onClick = { toolsOpen = !toolsOpen },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .graphicsLayer { rotationZ = plusRotation },
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            BasicTextField(
                                value = prompt,
                                onValueChange = { newText ->
                                    // Scope F: a >2000-char paste becomes
                                    // the TXT chip; the field keeps the
                                    // typed text only.
                                    val split = splitPasteChange(prompt, newText, pastedText)
                                    prompt = split.fieldText
                                    pastedText = split.chipText
                                    updateMentionQuery(split.fieldText)
                                },
                                onTextLayout = { inputLineCount = it.lineCount },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp)
                                    .onFocusChanged { inputFocused = it.isFocused },
                                // Scope F2: grows to 8 whole lines in
                                // EVERY state — the old rule (1 line
                                // unfocused, unlimited focused, 150dp
                                // cap) is what half-clipped lines and
                                // made scrolling jump. Past the cap the
                                // field scrolls inside, caret visible.
                                minLines = 1,
                                maxLines = COMPOSER_MAX_LINES,
                            // v3.2.45 (Item 1): includeFontPadding
                            // OFF + line-height trim — the same fix
                            // as the home composer. Source Serif 4's
                            // metrics (ascent+descent = 1.371 em)
                            // inflated the field's measured height,
                            // so typed text sat at the top of an
                            // over-tall box with a dead gap before
                            // the controls row. The field now hugs
                            // its actual lines in every state.
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily,
                                fontSize = 15.sp,
                                lineHeight = 20.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both,
                                ),
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (prompt.isEmpty()) {
                                        Text(
                                            text = "Message Kodex…",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = TextStyle(
                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                lineHeightStyle = LineHeightStyle(
                                                    alignment = LineHeightStyle.Alignment.Center,
                                                    trim = LineHeightStyle.Trim.Both,
                                                ),
                                            ),
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                            if (!controlsVisible) {
                                ComposerCircleButton(contentDescription = "Voice input", onClick = onStartVoiceInput) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                sendStopButton()
                            }
                        }
                        AnimatedVisibility(
                            visible = controlsVisible,
                            // Fade ONLY — and no size owner remains
                            // to fight (v3.2.42 removed the Surface's
                            // animateContentSize entirely); continuous
                            // motion is the keyboard's alone.
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ComposerCircleButton(
                                    contentDescription = if (toolsOpen) "Hide tools" else "Show tools",
                                    onClick = { toolsOpen = !toolsOpen },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .graphicsLayer { rotationZ = plusRotation },
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable { showAgentSheet = true },
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            agentKind.title.removeSuffix(" CLI").removeSuffix(" Harness"),
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                        )
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                ComposerCircleButton(contentDescription = "Voice input", onClick = onStartVoiceInput) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                sendStopButton()
                            }
                        }
                    }
                }
                }
            }
        }
    }
    // Scope F: full-screen viewer for a pasted-content card/chip.
    viewingPasted?.let { pasted ->
        PastedContentViewerDialog(text = pasted, onDismiss = { viewingPasted = null })
    }
    if (voiceCallActive) {
        VoiceCallScreen(
            recognizer = voiceRecognizer,
            messages = messages,
            isRunning = isRunning,
            approval = approval,
            onSend = { text -> onSend(text, null) },
            onApproval = onApproval,
            onApprovalWithLevel = onApprovalWithLevel,
            onClose = { voiceCallActive = false },
            onRequestMicPermission = { callPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )
    }
}

private enum class VoiceCallPhase { LISTENING, SENT, THINKING, SPEAKING }

/**
 * Hands-free voice call (v3.2.36 rewrite of the conversation loop).
 *
 * The v3.2.33 loop lost replies silently: a one-shot effect keyed only on
 * (isRunning, awaitingReply) evaluated once with the message list captured
 * at launch, so it either fired against the pre-send list (speaking a stale
 * reply and disarming itself) or never re-fired when the final message
 * landed in a later state emission than isRunning=false. It also had no
 * answer for a run parked on an approval (the card sat in the chat behind
 * this full-screen surface, isRunning stayed true, and the call waited in
 * "Thinking…" forever), and every failure was invisible.
 *
 * This version is an explicit state machine driven by live state:
 * LISTENING (live partials) -> SENT (the exact committed text, shown) ->
 * THINKING (agent working; a pending approval is surfaced HERE with the
 * chat's own handlers) -> SPEAKING (the reply line is shown AND spoken via
 * ReadAloudPlayer with the user's selected voice) -> LISTENING.
 * A reply only counts if it is a NEW assistant message (id recorded at
 * send time as the baseline), and every failure lands on a visible status
 * line instead of silence.
 */
@Composable
private fun VoiceCallScreen(
    recognizer: com.vayro.app.ui.voice.VoiceRecognizer,
    messages: List<ChatMessage>,
    isRunning: Boolean,
    approval: ToolRequest?,
    onSend: (String) -> Unit,
    onApproval: (Boolean) -> Unit,
    onApprovalWithLevel: (com.vayro.app.security.PermissionLevel, Boolean) -> Unit,
    onClose: () -> Unit,
    onRequestMicPermission: () -> Unit = {},
    // v3.2.38 (M2): the HOME call is relaxed conversation on the direct
    // API path — it uses tight reply timings. The workspace call keeps
    // the conservative beats (agent runs land whenever they land).
    fastReply: Boolean = false,
    // v3.2.40: the HOME host passes the Live | Standard engine switcher
    // for the header; the workspace call passes nothing and renders
    // exactly as before.
    headerCenter: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val voiceState by recognizer.state.collectAsState()
    var muted by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(VoiceCallPhase.LISTENING) }
    var lastHeard by remember { mutableStateOf("") }
    var sentText by remember { mutableStateOf("") }
    var replyText by remember { mutableStateOf("") }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var awaitingReply by remember { mutableStateOf(false) }
    var baselineAssistantId by remember { mutableStateOf<String?>(null) }
    var baselineUserId by remember { mutableStateOf<String?>(null) }
    var lastSpokenId by remember { mutableStateOf<String?>(null) }
    var speakingId by remember { mutableStateOf<String?>(null) }
    // v3.2.38 (M1): a voice notice (e.g. ElevenLabs failed -> device
    // fallback) raised during THIS reply stays on the status line.
    var replyNotice by remember { mutableStateOf(false) }
    val speakingNow = com.vayro.app.voice.ReadAloudPlayer.playingMessageId != null

    fun resumeListening(keepStatus: Boolean = false) {
        awaitingReply = false
        speakingId = null
        phase = VoiceCallPhase.LISTENING
        if (!keepStatus) {
            statusLine = null
            statusIsError = false
        }
        if (!muted) recognizer.start()
    }

    fun endCall() {
        recognizer.cancel()
        com.vayro.app.voice.ReadAloudPlayer.stop()
        onClose()
    }
    BackHandler { endCall() }

    val level by produceState(0f) {
        while (true) {
            value = recognizer.level
            kotlinx.coroutines.delay(50)
        }
    }

    LaunchedEffect(Unit) {
        phase = VoiceCallPhase.LISTENING
        recognizer.start()
    }
    DisposableEffect(Unit) {
        onDispose {
            recognizer.cancel()
            com.vayro.app.voice.ReadAloudPlayer.stop()
        }
    }

    // Recognizer failures are shown on the call screen, never swallowed.
    LaunchedEffect(voiceState.error) {
        val err = voiceState.error
        if (err != null && phase == VoiceCallPhase.LISTENING) {
            statusLine = err
            statusIsError = true
        }
    }

    // v3.2.37 (A2): while the recognizer heals itself (destroy + recreate +
    // backoff inside VoiceRecognizer), the status line says so — the user
    // watches it reconnect instead of staring at a frozen call.
    LaunchedEffect(voiceState.reconnecting, voiceState.reconnectMessage) {
        if (voiceState.reconnecting && phase == VoiceCallPhase.LISTENING) {
            statusLine = voiceState.reconnectMessage ?: "Reconnecting\u2026"
            statusIsError = false
        }
    }

    // v3.2.38 (M1): voice failures are visible on the call — when the
    // player reports the selected voice could not be used, the status
    // line says so (and stays after the turn ends).
    LaunchedEffect(com.vayro.app.voice.ReadAloudPlayer.noticeSeq) {
        if (com.vayro.app.voice.ReadAloudPlayer.noticeSeq == 0) return@LaunchedEffect
        if (awaitingReply || phase == VoiceCallPhase.SPEAKING) {
            com.vayro.app.voice.ReadAloudPlayer.lastNotice?.let { notice ->
                statusLine = notice
                statusIsError = true
                replyNotice = true
            }
        }
    }

    // A committed segment that stays stable for a short beat = the user
    // finished a turn. Commit on the recognizer's final result as soon as
    // it is stable (700ms, tightened from 1100ms) and show the exact text
    // on screen the moment it is sent (SENT phase).
    LaunchedEffect(voiceState.committed, phase, muted) {
        if (phase != VoiceCallPhase.LISTENING || muted) return@LaunchedEffect
        val committed = voiceState.committed
        if (committed.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(if (fastReply) 250 else 700)
        if (phase == VoiceCallPhase.LISTENING && !muted) {
            recognizer.finish { finalText ->
                val text = finalText.trim().ifBlank { committed.trim() }
                if (text.isNotBlank()) {
                    com.vayro.app.voice.ReadAloudPlayer.stop()
                    baselineAssistantId = messages.lastOrNull { !it.fromUser }?.id
                    baselineUserId = messages.lastOrNull { it.fromUser }?.id
                    lastHeard = text
                    sentText = text
                    replyText = ""
                    statusLine = null
                    statusIsError = false
                    replyNotice = false
                    awaitingReply = true
                    phase = VoiceCallPhase.SENT
                    onSend(text)
                } else {
                    recognizer.start()
                }
            }
        }
    }

    // SENT is a brief confirmation beat; the reply machine below keys off
    // awaitingReply and live chat state, not this phase, so nothing waits
    // on the beat finishing.
    LaunchedEffect(phase) {
        if (phase == VoiceCallPhase.SENT) {
            kotlinx.coroutines.delay(650)
            if (phase == VoiceCallPhase.SENT) phase = VoiceCallPhase.THINKING
        }
    }

    // Reply machine. Keyed on every value it reads, so it re-evaluates as
    // chat state lands instead of judging once from a stale snapshot.
    LaunchedEffect(awaitingReply, isRunning, approval, messages) {
        if (!awaitingReply) return@LaunchedEffect
        if (phase == VoiceCallPhase.SPEAKING) return@LaunchedEffect
        if (approval != null) return@LaunchedEffect // the approval card below owns this moment
        if (isRunning) return@LaunchedEffect
        // The run is over (or never visibly started). A short grace lets
        // the final message emission land before judging; if nothing new
        // exists at all, one longer beat covers a queued send.
        kotlinx.coroutines.delay(if (fastReply) 60 else 500)
        if (!awaitingReply || isRunning || approval != null) return@LaunchedEffect
        var candidate = messages.lastOrNull {
            !it.fromUser && it.id != baselineAssistantId && it.id != lastSpokenId
        }
        if (candidate == null) {
            kotlinx.coroutines.delay(if (fastReply) 600 else 1200)
            if (!awaitingReply || isRunning || approval != null) return@LaunchedEffect
            candidate = messages.lastOrNull {
                !it.fromUser && it.id != baselineAssistantId && it.id != lastSpokenId
            }
        }
        val reply = candidate
        if (reply == null) {
            val userLanded = messages.lastOrNull { it.fromUser }?.id != baselineUserId
            statusIsError = !userLanded
            statusLine = if (userLanded) {
                "The reply had no text to read out."
            } else {
                "No reply came back — the message may not have been sent."
            }
            resumeListening(keepStatus = true)
            return@LaunchedEffect
        }
        // Speakable text: the reply's own text; for a pure work/plan card
        // fall back to its visible status/title so the turn still answers.
        val speakText = reply.text.takeIf { it.isNotBlank() }
            ?: reply.workItems.lastOrNull()?.let { item ->
                listOf(item.title, item.detail).filter { it.isNotBlank() }.joinToString(". ")
            }?.takeIf { it.isNotBlank() }
            ?: reply.roadmap?.let { plan ->
                listOf("Plan ready: ${plan.title}", plan.summary)
                    .filter { it.isNotBlank() }.joinToString(". ")
            }?.takeIf { it.isNotBlank() }
        if (speakText == null) {
            lastSpokenId = reply.id
            statusLine = "The reply had no text to read out."
            statusIsError = false
            resumeListening(keepStatus = true)
            return@LaunchedEffect
        }
        lastSpokenId = reply.id
        replyText = speakText
        statusLine = null
        statusIsError = false
        awaitingReply = false
        speakingId = reply.id
        phase = VoiceCallPhase.SPEAKING
        // Actively start playback of THIS reply (the old loop's other
        // failure: nothing guaranteed the player was ever invoked for the
        // new message). toggle() stops when the same id is already
        // playing, so only call it when it is not.
        val player = com.vayro.app.voice.ReadAloudPlayer
        if (player.playingMessageId != reply.id) {
            if (player.playingMessageId != null) player.stop()
            player.toggle(context, reply.id, speakText)
        }
    }

    // Playback finished (or wedged) -> listen again. Polls the player's
    // observable state; the generous cap keeps a stuck player (e.g. a TTS
    // engine init failure, which never clears playingMessageId) from
    // parking the call on SPEAKING forever with no sound and no way out.
    LaunchedEffect(speakingId) {
        val id = speakingId ?: return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        val capMs = 30_000L + replyText.length * 120L
        var wedged = false
        while (phase == VoiceCallPhase.SPEAKING && speakingId == id) {
            if (com.vayro.app.voice.ReadAloudPlayer.playingMessageId == null) break
            if (System.currentTimeMillis() - startedAt > capMs) {
                com.vayro.app.voice.ReadAloudPlayer.stop()
                wedged = true
                break
            }
            kotlinx.coroutines.delay(200)
        }
        if (phase == VoiceCallPhase.SPEAKING && speakingId == id) {
            if (wedged) {
                statusLine = "The reply could not be played out loud."
                statusIsError = true
            }
            kotlinx.coroutines.delay(250)
            if (phase == VoiceCallPhase.SPEAKING && speakingId == id) {
                resumeListening(keepStatus = wedged || replyNotice)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Voice call",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                if (headerCenter != null) {
                    headerCenter()
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = { endCall() }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "End call",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (approval != null) {
                // The agent is parked on an approval in the chat behind
                // this screen. Surface the same card with the same
                // handlers so the call can continue from here.
                Text(
                    "Kodex needs your approval",
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(14.dp))
                ApprovalCard(
                    request = approval,
                    onApproval = onApproval,
                    onApprovalWithLevel = onApprovalWithLevel,
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    // One cheap radial wash behind the orb — no blur, no stack.
                    Box(
                        Modifier
                            .size(330.dp)
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                    colors = listOf(
                                        com.vayro.app.ui.theme.KodexClay.copy(alpha = 0.13f),
                                        Color.Transparent,
                                    ),
                                ),
                                shape = CircleShape,
                            ),
                    )
                    VoiceCallOrb(phase = phase, level = level, speaking = speakingNow)
                }
                Spacer(Modifier.height(30.dp))
                Text(
                    when (phase) {
                        VoiceCallPhase.LISTENING -> if (muted) "Muted" else "Listening…"
                        VoiceCallPhase.SENT -> "Sent"
                        VoiceCallPhase.THINKING -> "Thinking…"
                        VoiceCallPhase.SPEAKING -> "Speaking…"
                    },
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when (phase) {
                        VoiceCallPhase.LISTENING -> voiceState.partial.ifBlank { voiceState.committed }.ifBlank { lastHeard }.ifBlank { "Say something to Kodex" }
                        VoiceCallPhase.SENT -> sentText
                        VoiceCallPhase.THINKING -> sentText
                        VoiceCallPhase.SPEAKING -> replyText
                    },
                    fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (phase == VoiceCallPhase.SPEAKING) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (statusLine != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        statusLine!!,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (statusIsError && voiceState.errorCode != null) {
                        Text(
                            "Error ${voiceState.errorCode}",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (voiceState.error != null && phase == VoiceCallPhase.LISTENING) {
                        if (voiceState.errorCode == 9) {
                            // ERROR_INSUFFICIENT_PERMISSIONS: the fix is the
                            // system permission, not another restart.
                            TextButton(onClick = onRequestMicPermission) {
                                Text("Allow microphone")
                            }
                        } else {
                            TextButton(onClick = {
                                // A fresh session, not a plain restart of
                                // the broken one (A2/A3): tear down first,
                                // then start clean.
                                recognizer.cancel()
                                statusLine = null
                                statusIsError = false
                                recognizer.start()
                            }) {
                                Text("Try listening again")
                            }
                        }
                    }
                } else if (phase == VoiceCallPhase.LISTENING && !voiceState.reconnecting && voiceState.softMessage != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        voiceState.softMessage!!,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .clickable {
                            muted = !muted
                            if (muted) {
                                recognizer.cancel()
                            } else if (phase == VoiceCallPhase.LISTENING) {
                                recognizer.start()
                            }
                        },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (muted) "Unmute" else "Mute",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(23.dp),
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .clickable { endCall() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.CallEnd,
                            contentDescription = "End call",
                            tint = Color.White,
                            modifier = Modifier.size(27.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * v3.2.40: the home call's engine choice — Live (Gemini Live,
 * speech-to-speech) or Standard (the pipeline call). Rendered in the
 * call header by both engines so the user can flip mid-visit; the
 * surface behind it is replaced by the host.
 */
@Composable
private fun CallEngineSwitcher(
    liveSelected: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(true to "Live", false to "Standard").forEach { (live, label) ->
            val selected = liveSelected == live
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    )
                    .clickable { onSelect(live) }
                    .padding(horizontal = 13.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * v3.2.40: the Live call screen — the pipeline call's visual shell
 * (orb, states, End Call, mute) over the Gemini Live engine. The
 * conversation itself never passes through text generation: the model
 * hears the user and answers in its own voice; what lands in the home
 * thread afterwards is the call's in-stream transcription, saved by
 * the controller through the view model.
 */
@Composable
private fun LiveCallScreen(
    viewModel: MainViewModel,
    engineSwitcher: @Composable () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val controller = remember {
        LiveCallController(
            context = context.applicationContext,
            credentials = viewModel.liveCallCredentials(),
            autoSwitch = viewModel.liveCallAutoSwitch(),
            restingKeyIds = viewModel.liveCallRestingKeyIds(),
            voiceName = viewModel.liveCallVoiceName(),
            onTurn = viewModel::appendLiveCallTurn,
            onSystemLine = viewModel::appendLiveCallSystemLine,
            onKeyExhausted = viewModel::markLiveKeyExhausted,
            onKeyActivated = viewModel::activateLiveKey,
        )
    }
    val uiState by controller.uiState.collectAsState()

    fun endCall() {
        controller.stop()
        onClose()
    }
    BackHandler { endCall() }

    LaunchedEffect(controller) { controller.start() }
    DisposableEffect(controller) {
        onDispose { controller.stop() }
    }

    val level by produceState(0f) {
        while (true) {
            value = controller.orbLevel()
            kotlinx.coroutines.delay(50)
        }
    }
    val orbPhase = when (uiState.phase) {
        LiveCallUiState.Phase.CONNECTING -> VoiceCallPhase.THINKING
        LiveCallUiState.Phase.SPEAKING -> VoiceCallPhase.SPEAKING
        else -> VoiceCallPhase.LISTENING
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Live call",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                engineSwitcher()
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { endCall() }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "End call",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(330.dp)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(
                                    com.vayro.app.ui.theme.KodexClay.copy(alpha = 0.13f),
                                    Color.Transparent,
                                ),
                            ),
                            shape = CircleShape,
                        ),
                )
                VoiceCallOrb(
                    phase = orbPhase,
                    level = level,
                    speaking = uiState.phase == LiveCallUiState.Phase.SPEAKING,
                )
            }
            Spacer(Modifier.height(30.dp))
            Text(
                when (uiState.phase) {
                    LiveCallUiState.Phase.CONNECTING -> "Connecting…"
                    LiveCallUiState.Phase.LISTENING -> if (uiState.muted) "Muted" else "Listening…"
                    LiveCallUiState.Phase.SPEAKING -> "Speaking…"
                    LiveCallUiState.Phase.ERROR -> "Call failed"
                    LiveCallUiState.Phase.ENDED -> "Call ended"
                },
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when (uiState.phase) {
                    LiveCallUiState.Phase.CONNECTING -> "Setting up the Live call…"
                    LiveCallUiState.Phase.SPEAKING -> uiState.liveReplyText
                    LiveCallUiState.Phase.LISTENING ->
                        uiState.liveUserText.ifBlank { "Say something to Kodex" }
                    else -> uiState.liveReplyText.ifBlank { uiState.liveUserText }
                },
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (uiState.statusLine != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    uiState.statusLine!!,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = if (uiState.statusIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .clickable { controller.setMuted(!uiState.muted) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (uiState.muted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (uiState.muted) "Unmute" else "Mute",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(23.dp),
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .clickable { endCall() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.CallEnd,
                            contentDescription = "End call",
                            tint = Color.White,
                            modifier = Modifier.size(27.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * v3.2.40: Live chosen with no AI Studio key connected — an honest
 * unavailable state with the route to fix it, never a dead call.
 */
@Composable
private fun LiveCallUnavailableScreen(
    onClose: () -> Unit,
    onUseStandard: () -> Unit,
    onOpenProviders: () -> Unit,
) {
    BackHandler { onClose() }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Live call",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(330.dp)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(
                                    com.vayro.app.ui.theme.KodexClay.copy(alpha = 0.13f),
                                    Color.Transparent,
                                ),
                            ),
                            shape = CircleShape,
                        ),
                )
                VoiceCallOrb(phase = VoiceCallPhase.LISTENING, level = 0f, speaking = false)
            }
            Spacer(Modifier.height(30.dp))
            Text(
                "Live call needs a Google AI Studio key",
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "A Live call talks directly with Gemini Live, voice to voice. " +
                    "Add a free Google AI Studio key in Providers and this call becomes a real conversation. " +
                    "Your Standard voice call still works without it.",
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onOpenProviders,
                shape = RoundedCornerShape(999.dp),
            ) {
                Text("Open Providers")
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onUseStandard,
                shape = RoundedCornerShape(999.dp),
            ) {
                Text("Use Standard call")
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun VoiceCallOrb(
    phase: VoiceCallPhase,
    level: Float,
    speaking: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "voice-call-orb")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(
                when (phase) {
                    VoiceCallPhase.THINKING -> 5000
                    VoiceCallPhase.SPEAKING -> 9000
                    else -> 16000
                },
                easing = LinearEasing,
            ),
            RepeatMode.Restart,
        ),
        label = "call-spin",
    )
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "call-breathe",
    )
    val clay = com.vayro.app.ui.theme.KodexClay
    val soft = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(224.dp)) {
        val center = size.minDimension / 2f
        val baseR = size.minDimension * 0.30f
        // v3.2.37 (A3): state-driven energy — LISTENING follows the live mic
        // level, SPEAKING surges while the reply is actually playing,
        // THINKING keeps its faster weave, idle phases breathe slowly.
        val energy = when (phase) {
            VoiceCallPhase.LISTENING -> 0.30f + level.coerceIn(0f, 1f) * 1.15f
            VoiceCallPhase.SENT -> 0.45f
            VoiceCallPhase.THINKING -> 0.60f
            VoiceCallPhase.SPEAKING -> if (speaking) 0.95f else 0.35f
        }
        val speakingSurge = if (phase == VoiceCallPhase.SPEAKING && speaking) {
            0.07f * sin((breathe * 3f).toDouble()).toFloat()
        } else {
            0f
        }
        val pulse = 1f + 0.05f * sin(breathe.toDouble()).toFloat() +
            0.10f * energy * sin((breathe * 2f).toDouble()).toFloat() + speakingSurge
        val count = 110
        val golden = 2.399963f
        for (i in 0 until count) {
            val y = 1f - (i / (count - 1).toFloat()) * 2f
            val ring = sqrt((1f - y * y).coerceAtLeast(0f).toDouble()).toFloat()
            val theta = golden * i + spin * (PI / 180.0).toFloat()
            val wobble = 1f + 0.06f * sin((breathe * 2f + i * 0.7f).toDouble()).toFloat() * (0.5f + energy)
            val r = baseR * pulse * wobble
            val x = center + (ring * cos(theta.toDouble()) * r).toFloat()
            val yy = center + (y * r * 0.92f)
            val depth = (y + 1f) / 2f
            val dotR = (1.6f + 2.6f * depth + energy * 1.2f) * (size.minDimension / 190f)
            drawCircle(
                color = if (i % 5 == 0) clay else soft.copy(alpha = 0.35f + 0.5f * depth),
                radius = dotR,
                center = Offset(x, yy),
            )
        }
        drawCircle(
            color = clay.copy(alpha = 0.20f + 0.25f * energy),
            radius = baseR * 0.36f * pulse,
            center = Offset(center, center),
        )
        drawCircle(
            color = clay.copy(alpha = 0.85f),
            radius = baseR * 0.10f * pulse + 2f,
            center = Offset(center, center),
        )
    }
}

@Composable
private fun LiveClaudeProcess(
    processItems: List<ActivityItem>,
    isRunning: Boolean,
    startedAtMillis: Long?,
    finishedAtMillis: Long?,
    thinkingActive: Boolean,
) {
    val elapsedSeconds = if (processItems.isEmpty() && startedAtMillis != null) {
        rememberLiveElapsedSeconds(startedAtMillis).toLong()
    } else {
        startedAtMillis?.let { ((System.currentTimeMillis() - it) / 1000L).coerceAtLeast(0) } ?: 0L
    }
    ClaudeActivityDisclosure(
        items = processItems,
        headline = activityHeadline(processItems, elapsedSeconds, thinkingActive),
        isRunning = isRunning,
    )
}

@Composable
private fun WorkBlockCard(message: ChatMessage, onOpenActivity: () -> Unit = {}) {
    val seconds = (message.workedMillis / 1_000L).coerceAtLeast(1L)
    val lastTitle = message.workItems.lastOrNull()?.title.orEmpty()
    val isStopped = lastTitle.startsWith("Task stopped", ignoreCase = true)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpenActivity).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(9.dp))
        Text(
            if (isStopped) "Task stopped · Worked for ${formatDuration(seconds)}" else "Worked for ${formatDuration(seconds)}",
            fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        Icon(Icons.Default.KeyboardArrowUp, "View activity", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ThinkingOrb(modifier: Modifier = Modifier, color: Color = com.vayro.app.ui.theme.KodexClay) {
    val transition = rememberInfiniteTransition(label = "thinking_orb")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 1800, easing = LinearEasing), RepeatMode.Restart),
        label = "orb_phase",
    )
    Canvas(modifier.size(20.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val orbit = size.minDimension / 2f * 0.72f
        val dots = 12
        for (i in 0 until dots) {
            val a = phase + (2.0 * Math.PI * i / dots).toFloat()
            val wave = 0.5f + 0.5f * kotlin.math.sin(a * 2f)
            val r = size.minDimension * (0.045f + 0.05f * wave)
            drawCircle(
                color = color.copy(alpha = 0.35f + 0.65f * wave),
                radius = r,
                center = Offset(c.x + orbit * kotlin.math.cos(a), c.y + orbit * kotlin.math.sin(a)),
            )
        }
        drawCircle(color = color.copy(alpha = 0.5f + 0.3f * kotlin.math.sin(phase * 2f)), radius = size.minDimension * 0.07f, center = c)
    }
}

@Composable
private fun WorkingBar(
    items: List<ActivityItem>,
    thinkingActive: Boolean,
    startedAtMillis: Long?,
    onClick: () -> Unit,
) {
    val elapsed = startedAtMillis?.let { rememberLiveElapsedSeconds(it) } ?: 0
    val current = items.lastOrNull { !it.isComplete } ?: items.lastOrNull()
    val label = when {
        current != null -> compactActivityText(current)
        thinkingActive -> "Thinking · Analyzing the request"
        else -> "Working…"
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        // In-flow since v3.2.34 (U4): the list's own content padding
        // provides the horizontal inset, so the bar carries none itself.
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            ThinkingOrb()
            Spacer(Modifier.width(10.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (startedAtMillis != null) {
                Text(formatDuration(elapsed.toLong()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
            }
            Icon(Icons.Default.KeyboardArrowUp, "View activity", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivitySheet(
    items: List<ActivityItem>,
    changes: List<ChangeItem>,
    isRunning: Boolean,
    workedText: String?,
    runStartedAtMillis: Long? = null,
    onDismiss: () -> Unit,
) {
    // v3.2.35 (U6d): this sheet is VIEW-ONLY — thinking, steps and code.
    // Approvals live in the chat flow as cards, never in here.
    var expandedItems by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    // v3.2.40 (Scope D): the run's live clock, one hook for the whole
    // sheet; a running row's own elapsed is arithmetic from its start
    // stamp (ActivityItem.startedAtMillis) — no hooks inside list rows.
    // Fixed per sheet instance: the live sheet always passes a start,
    // the history sheet never does.
    val liveElapsedSeconds = if (isRunning && runStartedAtMillis != null) {
        rememberLiveElapsedSeconds(runStartedAtMillis)
    } else {
        0
    }
    // v3.2.39 (Scope 6) — why this sheet used to jerk, and the fix. The
    // old sheet had THREE geometry fights at once: (1) TWO resting
    // anchors (skipPartiallyExpanded = false), so it could snap between
    // half and full height; (2) a content height that GREW while it was
    // open — the live item list streams in and the column was sized by
    // its content (heightIn max 860dp), and every growth re-anchored
    // the sheet mid-animation; (3) it opened over the chat keyboard,
    // whose window kept resizing the sheet's frame — half-height under
    // the keyboard, then a snap when the frame changed. Net effect on
    // the user's phone: rises partway, snaps down, reopens half-height,
    // flickers as the run streams.
    // The fix: ONE anchor (skipPartiallyExpanded = true), a FIXED sheet
    // height that content growth can never change (the list scrolls
    // inside it), and the keyboard is dismissed the moment the sheet
    // opens so sheet and keyboard never fight. After opening, live
    // updates change only list rows — the sheet itself never moves.
    //
    // v3.2.40 (Scope B): the FIXED height itself is now gone — the user
    // filmed a one-line sheet ("Write · …/products.js") opening at the
    // full 92%. The sheet HUGS its content instead: the list is CAPPED,
    // not fixed (heightIn max ≈ 90% of the screen minus the sheet's own
    // chrome), and the column springs between measured content sizes
    // (animateContentSize), so a streaming row grows the sheet
    // continuously and the list scrolls only once the cap is reached.
    // Scope 6's actual root causes stay fixed: still ONE anchor, the
    // keyboard is still dismissed on open, and growth is a continuous
    // size animation of the content under that single anchor — never a
    // second resting height or a re-anchor. Same-size content changes
    // (the ticking elapsed text in a row) do not resize anything.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboardController?.hide() }
    val configuration = LocalConfiguration.current
    val listMaxHeight = (configuration.screenHeightDp.dp * 0.90f - 130.dp).coerceAtLeast(140.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
                .padding(bottom = 26.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Outlined.Close, "Close", Modifier.size(21.dp))
                }
                Text("Activity", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = listMaxHeight).padding(horizontal = 16.dp)) {
                itemsIndexed(items) { index, item ->
                    // v3.2.40 (Scope D): ANY incomplete row is a step that
                    // is running right now — orb + live elapsed, exactly
                    // like the chat working row. (Before, only the LAST
                    // incomplete row got the orb, and none showed time.)
                    val running = isRunning && !item.isComplete
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            expandedItems = if (index in expandedItems) expandedItems - index else expandedItems + index
                        }.padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            item.isComplete -> Icon(Icons.Default.CheckCircle, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.tertiary)
                            running -> ThinkingOrb(Modifier.size(17.dp))
                            else -> Icon(Icons.Outlined.Circle, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            compactActivityText(item),
                            fontSize = 13.sp,
                            color = if (item.isComplete || running) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        if (running) {
                            val stepElapsed = if (item.startedAtMillis > 0L && runStartedAtMillis != null) {
                                (liveElapsedSeconds - ((item.startedAtMillis - runStartedAtMillis) / 1000L).toInt()).coerceAtLeast(0)
                            } else {
                                liveElapsedSeconds
                            }
                            Text(
                                formatDuration(stepElapsed.toLong()),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Icon(if (index in expandedItems) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (index in expandedItems) ActivityExpandedDetail(item, activityDetail(item))
                }
                // v3.2.34 (U6): EVERY changed file gets its own code box
                // with its COMPLETE lines. The old code showed only the
                // last changed file and silently cut it at 60 lines
                // ("… N more lines" with no way to see them).
                val codeChanges = changes.filter { it.diffLines.isNotEmpty() }
                if (codeChanges.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text("Code", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    itemsIndexed(codeChanges, key = { index, change -> "code-$index-${change.path}" }) { _, change ->
                        Spacer(Modifier.height(6.dp))
                        ActivityCodeBox(change)
                    }
                }
                if (changes.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(14.dp))
                        Text("Files changed", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                    }
                    itemsIndexed(changes) { _, change ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(change.path, fontSize = 12.5.sp, fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("+${change.additions}", color = PocketGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(6.dp))
                            Text("−${change.deletions}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (workedText != null) {
                    item {
                        Spacer(Modifier.height(10.dp))
                        Text(workedText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * One file's code box inside the Activity sheet (v3.2.34, U6/U6b).
 * Shows the file's COMPLETE diff lines — nothing is dropped. The box
 * scrolls in BOTH directions: vertically within its own bounded height,
 * and horizontally because lines are never force-wrapped (JetBrains
 * Mono content preserved; the inner column is as wide as the longest
 * line, gutter included). Rendering starts with the first 100 lines so
 * very long files stay cheap on low-end phones; "More lines" reveals
 * the full remainder in place and "Show less" folds back — every line
 * is reachable, by scroll or by the expander.
 */
@Composable
private fun ActivityCodeBox(change: ChangeItem) {
    var showAll by remember(change.path, change.diffLines.size) { mutableStateOf(false) }
    val allLines = change.diffLines
    val visibleLines = if (showAll) allLines else allLines.take(100)
    val maxChars = remember(allLines) { allLines.maxOfOrNull { it.text.length } ?: 0 }
    // Gutter prefix ("1234 5678  + " = 13 chars) + text, ~7.2dp per
    // mono character at 11sp.
    val contentWidth = ((maxChars + 14) * 7.2f).dp.coerceAtLeast(240.dp)
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(change.path, fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("+${change.additions}", color = PocketGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text("−${change.deletions}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().horizontalScroll(horizontalScroll)) {
                Column(
                    Modifier
                        .width(contentWidth)
                        .heightIn(max = 320.dp)
                        .verticalScroll(verticalScroll),
                ) {
                    visibleLines.forEach { line -> DiffLineRow(line) }
                }
            }
            if (allLines.size > 100) {
                TextButton(
                    onClick = { showAll = !showAll },
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Text(
                        if (showAll) "Show less" else "More lines (${allLines.size - 100} more)",
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClaudeActivityDisclosure(
    items: List<ActivityItem>,
    headline: String,
    isRunning: Boolean = false,
) {
    var expandedItems by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        if (items.isEmpty()) {
            ActivitySummaryRow(
                item = null,
                text = headline,
                expanded = 0 in expandedItems,
                showProgress = isRunning,
                onToggle = {
                    expandedItems = if (0 in expandedItems) expandedItems - 0 else expandedItems + 0
                },
            )
            if (0 in expandedItems) ActivityExpandedDetail(null, "Reviewing the request and planning the next action.")
        } else {
            items.forEachIndexed { index, item ->
                val summaryText = remember(item) { compactActivityText(item) }
                ActivitySummaryRow(
                    item = item,
                    text = summaryText,
                    expanded = index in expandedItems,
                    showProgress = isRunning && !item.isComplete,
                    onToggle = {
                        expandedItems = if (index in expandedItems) expandedItems - index else expandedItems + index
                    },
                )
                if (index in expandedItems) ActivityExpandedDetail(item, activityDetail(item))
            }
        }
    }
}

@Composable
private fun AnimatedThinkingDots(
    modifier: Modifier = Modifier,
    dotColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val transition = rememberInfiniteTransition(label = "thinking_dots")
    val dot1Offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = -3.5f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1100
                0f at 0
                -3.5f at 220
                0f at 440
                0f at 1100
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(0),
        ),
        label = "dot1",
    )
    val dot2Offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = -3.5f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1100
                0f at 0
                -3.5f at 220
                0f at 440
                0f at 1100
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(180),
        ),
        label = "dot2",
    )
    val dot3Offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = -3.5f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1100
                0f at 0
                -3.5f at 220
                0f at 440
                0f at 1100
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(360),
        ),
        label = "dot3",
    )

    val dot1Alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1100
                0.35f at 0
                1f at 220
                0.35f at 440
                0.35f at 1100
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(0),
        ),
        label = "dot1_alpha",
    )
    val dot2Alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1100
                0.35f at 0
                1f at 220
                0.35f at 440
                0.35f at 1100
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(180),
        ),
        label = "dot2_alpha",
    )
    val dot3Alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1100
                0.35f at 0
                1f at 220
                0.35f at 440
                0.35f at 1100
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(360),
        ),
        label = "dot3_alpha",
    )

    Row(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(3.5.dp)
                .graphicsLayer { translationY = dot1Offset * density }
                .background(dotColor.copy(alpha = dot1Alpha), CircleShape),
        )
        Box(
            Modifier
                .size(3.5.dp)
                .graphicsLayer { translationY = dot2Offset * density }
                .background(dotColor.copy(alpha = dot2Alpha), CircleShape),
        )
        Box(
            Modifier
                .size(3.5.dp)
                .graphicsLayer { translationY = dot3Offset * density }
                .background(dotColor.copy(alpha = dot3Alpha), CircleShape),
        )
    }
}

@Composable
private fun ActivitySummaryRow(
    item: ActivityItem?,
    text: String,
    expanded: Boolean,
    showProgress: Boolean,
    onToggle: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).clickable(onClick = onToggle).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            activityIcon(item),
            null,
            Modifier.size(16.dp),
            tint = muted,
        )
        Spacer(Modifier.width(9.dp))
        Text(text, Modifier.weight(1f), fontSize = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showProgress) {
            AnimatedThinkingDots(dotColor = muted)
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            if (expanded) "Collapse activity" else "Expand activity",
            Modifier.size(18.dp),
            tint = muted,
        )
    }
}

private fun activityIcon(item: ActivityItem?): ImageVector {
    if (item == null) return Icons.Default.AutoAwesome
    val task = item.title
        .removePrefix("Running ")
        .removeSuffix(" completed")
        .trim()
    return when {
        item.isCommand || task.equals("Bash", ignoreCase = true) -> Icons.Default.Terminal
        task.equals("Write", ignoreCase = true) ||
            task.equals("Edit", ignoreCase = true) ||
            task.equals("NotebookEdit", ignoreCase = true) -> Icons.Default.Edit
        task.equals("Read", ignoreCase = true) -> Icons.Default.Description
        task.equals("Glob", ignoreCase = true) ||
            task.equals("Grep", ignoreCase = true) -> Icons.Default.Search
        task.contains("file", ignoreCase = true) -> Icons.Default.Description
        else -> Icons.Default.AutoAwesome
    }
}

@Composable
private fun ActivityExpandedDetail(item: ActivityItem?, detail: String) {
    if (item?.isCommand == true) {
        Text(
            detail,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 25.dp, end = 8.dp, bottom = 8.dp),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
        )
    } else {
        MarkdownText(
            markdown = detail,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 25.dp, end = 8.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun compactActivityText(item: ActivityItem): String = "${activityName(item)} · ${activityDetail(item).replace(Regex("\\s+"), " ").take(105)}"

private fun activityDetail(item: ActivityItem): String {
    if (item.title == "Think" && item.detail.contains("reasoning tokens processed", true)) {
        return "Reviewed the request and planned the next action"
    }
    return item.detail.ifBlank { item.title }
}

private fun activityHeadline(items: List<ActivityItem>, seconds: Long, thinking: Boolean): String {
    val latest = items.lastOrNull()
    if (latest == null) return "Think · Analyzing the request · ${formatDuration(seconds)}"
    if (thinking && latest.title == "Think") return "Think · ${latest.detail.replace(Regex("\\s+"), " ").trim().take(120)} · ${formatDuration(seconds)}"
    val detail = latest.detail.replace(Regex("\\s+"), " ").trim().ifBlank { latest.title }
    return "${activityName(latest)} · ${detail.take(100)} · ${formatDuration(seconds)}"
}

private fun activityName(item: ActivityItem): String = item.title
    .removePrefix("Running ")
    .removeSuffix(" completed")
    .replaceFirstChar { it.uppercase() }

private fun completedProcessSummary(
    processItems: List<ActivityItem>,
    startedAtMillis: Long?,
    finishedAtMillis: Long?,
): String {
    val stopped = processItems.lastOrNull()?.title?.startsWith("Task stopped") == true
    val outcome = if (stopped) "Task stopped" else "Task completed"
    val steps = "${processItems.size} step${if (processItems.size == 1) "" else "s"}"
    val duration = startedAtMillis?.let { start ->
        val end = finishedAtMillis ?: System.currentTimeMillis()
        formatDuration(((end - start) / 1000L).coerceAtLeast(0))
    }
    return if (duration != null) "$outcome · $duration · $steps" else "$outcome · $steps"
}

@Composable
private fun rememberLiveElapsedSeconds(startedAtMillis: Long): Int {
    var seconds by remember(startedAtMillis) {
        mutableIntStateOf(((System.currentTimeMillis() - startedAtMillis) / 1000L).toInt().coerceAtLeast(0))
    }
    LaunchedEffect(startedAtMillis) {
        while (true) {
            delay(1_000)
            seconds = ((System.currentTimeMillis() - startedAtMillis) / 1000L).toInt().coerceAtLeast(0)
        }
    }
    return seconds
}

private fun formatDuration(totalSeconds: Long): String = when {
    totalSeconds >= 3_600 -> "${totalSeconds / 3_600}h ${(totalSeconds % 3_600) / 60}m"
    totalSeconds >= 60 -> "${totalSeconds / 60}m ${totalSeconds % 60}s"
    else -> "${totalSeconds}s"
}

private const val MAX_STREAMING_DISPLAY_CHARS = 30_000

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isStreaming: Boolean = false,
    onOpenPasted: (String) -> Unit = {},
    // v3.2.45 (Item 3): Regenerate in the message action row (null =
    // no button, e.g. read-only history).
    onRegenerate: (() -> Unit)? = null,
    onRunInTerminal: (String) -> Unit,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onSaveAttachment: (ChatAttachment) -> Unit = {},
    onOpenLink: ((String) -> Unit)? = null,
    onAddRoadmapComment: (stepId: String?) -> Unit = {},
    onApproveAndBuildRoadmap: () -> Unit = {},
    onInstallApkPath: (String) -> Unit = {},
    onShareApkPath: (String) -> Unit = {},
    onSaveApkPath: (String) -> Unit = {},
    onLoadDeliveredApk: suspend (String) -> DeliveredApkData? = { null },
    onPreviewDelivered: (String, String) -> Unit = { _, _ -> },
    onLocateApk: (() -> Unit)? = null,
) {
    // Links in agent replies get Copy/Open cards under the bubble (max 3).
    val messageLinks = remember(message.id, message.text, message.fromUser) {
        if (message.fromUser) emptyList() else ChatLinkExtractor.extract(message.text).take(3)
    }
    // Kodex delivery card: an APK path in the agent's final message becomes
    // an Install/Share card (the delivery contract asks for the path).
    val deliveredApkPath = remember(message.id, message.text, message.fromUser) {
        if (message.fromUser) {
            null
        } else {
            // U7a: prefer the FULLEST path in the message — a markdown
            // label like [`app-debug.apk`](file:///…/app-debug.apk) puts
            // the bare name first, and resolving that name alone failed.
            Regex("""[A-Za-z0-9_./~-]+\.apk""").findAll(message.text)
                .map { it.value }
                .maxByOrNull { it.count { c -> c == '/' } }
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.fromUser) Alignment.End else Alignment.Start) {
        Surface(
            color = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(if (message.fromUser) .82f else .92f),
        ) {
            Column(Modifier.padding(top = 12.dp, bottom = 10.dp)) {
                val hugeText = message.text.length > 100000
                val displayText = if (hugeText) message.text.take(100000) + "\n\n… (message truncated for display)" else message.text
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                if (hugeText) {
                    MarkdownText(
                        markdown = displayText,
                        modifier = Modifier.padding(start = 14.dp, end = 44.dp),
                        color = if (message.fromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        fontFamily = if (message.fromUser) null else com.vayro.app.ui.theme.SourceSerifFontFamily,
                    )
                } else SelectionContainer {
                    if (message.fromUser) {
                        MarkdownText(
                            markdown = message.text,
                            modifier = Modifier.padding(start = 14.dp, end = 44.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else if (isStreaming) {
                        val streamDisplayText = if (message.text.length > MAX_STREAMING_DISPLAY_CHARS) {
                            message.text.take(MAX_STREAMING_DISPLAY_CHARS) + "\n… (output continues; full text renders on completion)"
                        } else {
                            message.text
                        }
                        Text(
                            text = streamDisplayText,
                            modifier = Modifier.padding(start = 14.dp, end = 44.dp),
                            style = contentBodyStyle(),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        MarkdownText(
                            markdown = message.text,
                            modifier = Modifier.padding(start = 14.dp, end = 44.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            onRunCode = onRunInTerminal,
                            fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily,
                        )
                    }
                }
                    // v3.2.39 (Scope 5): the shared copy button — the home
                    // thread uses this exact component too.
                    // v3.2.45 (Item 3): assistant replies carry Copy in
                    // the action row below instead; the overlay circle
                    // remains for user messages only.
                    if (message.fromUser) {
                        MessageCopyButton(
                            text = message.text,
                            fromUser = true,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 8.dp),
                        )
                    }
                }
                if (!message.fromUser && message.roadmap != null) {
                    RoadmapCard(
                        roadmap = message.roadmap,
                        onAddComment = onAddRoadmapComment,
                        onApproveAndBuild = onApproveAndBuildRoadmap,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                if (message.attachments.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        message.attachments.forEach { attachment ->
                            AttachmentChip(attachment = attachment, onOpen = { onOpenAttachment(attachment) }, onSave = { onSaveAttachment(attachment) }, onRemove = null)
                        }
                    }
                }

                if (!message.pastedText.isNullOrBlank()) {
                    // v3.2.40 (Scope F): the sent message shows the same
                    // TXT card as the home thread; tapping opens the
                    // full-screen viewer.
                    PastedContentCard(
                        onOpen = { onOpenPasted(message.pastedText!!) },
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    )
                }

                if (!message.fromUser && message.workedMillis > 0L) {
                    Text(
                        text = "Worked for ${formatDuration((message.workedMillis / 1_000L).coerceAtLeast(1L))}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 14.dp, top = 6.dp),
                    )
                }
            }
        }
        if (!message.fromUser && !isStreaming && message.text.isNotBlank()) {
            // v3.2.45 (Item 3): Copy - Share - Listen - Regenerate,
            // one muted row — the same shared row as the home thread
            // (was: Listen only, v3.2.39 Scope 5).
            MessageActionRow(
                messageId = message.id,
                text = message.text,
                onRegenerate = onRegenerate,
                modifier = Modifier.fillMaxWidth(.92f),
            )
        }
        if (!isStreaming && messageLinks.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth(if (message.fromUser) .82f else .92f)
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                messageLinks.forEach { url ->
                    ChatLinkCard(url = url, onOpenLink = onOpenLink)
                }
            }
        }
        if (!isStreaming && deliveredApkPath != null) {
            DeliveredApkCard(
                apkPath = deliveredApkPath,
                loadData = onLoadDeliveredApk,
                onInstall = onInstallApkPath,
                onShare = onShareApkPath,
                onSave = onSaveApkPath,
                onPreview = onPreviewDelivered,
                onLocate = onLocateApk,
                modifier = Modifier
                    .fillMaxWidth(if (message.fromUser) .82f else .92f)
                    .padding(top = 6.dp),
            )
        }
    }
}

@Composable
internal fun ChatLinkCard(
    url: String,
    onOpenLink: ((String) -> Unit)?,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Link,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = PocketOrange,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = ChatLinkExtractor.ellipsizeMiddle(url, 44),
                fontSize = 12.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(url))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                },
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy", fontSize = 12.sp)
            }
            IconButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(send, "Share link"))
                    }.onFailure {
                        Toast.makeText(context, "Could not share this link", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share link",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    if (onOpenLink != null) {
                        onOpenLink(url)
                    } else {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }.onFailure {
                            Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Open", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AttachmentChip(
    attachment: ChatAttachment,
    onOpen: (() -> Unit)?,
    onSave: (() -> Unit)? = null,
    onRemove: (() -> Unit)?,
) {
    val icon = when {
        attachment.mimeType.startsWith("image/") -> Icons.Default.Image
        else -> Icons.Default.Description
    }
    Surface(
        modifier = Modifier.then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(start = 9.dp, end = if (onRemove == null) 10.dp else 3.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(17.dp), tint = PocketOrange)
            Spacer(Modifier.width(7.dp))
            Column(Modifier.widthIn(max = 180.dp)) {
                Text(attachment.displayName, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatFileSize(attachment.sizeBytes), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onSave != null) {
                IconButton(onClick = onSave, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Save, "Save to", Modifier.size(15.dp))
                }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, "Remove attachment", Modifier.size(15.dp))
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    request: ToolRequest,
    onApproval: (Boolean) -> Unit,
    onApprovalWithLevel: (com.vayro.app.security.PermissionLevel, Boolean) -> Unit = { _, _ -> },
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = com.vayro.app.ui.theme.KodexClay)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (!request.commandPreview.isNullOrBlank()) "Allow this command?" else "Allow this action?",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
            if (request.risk.name != "SAFE") {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                ) {
                    Text(
                        "Risk: ${request.risk.name}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Text(request.explanation, fontSize = 13.5.sp, fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily)
            val preview = request.commandPreview
            if (!preview.isNullOrBlank()) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        preview,
                        fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            request.affectedPaths.forEach {
                Text("• $it", fontSize = 12.5.sp, fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // v3.2.34 (U3): one balanced row. The old equal-weight 13sp
            // row clipped "Allow for session" to "Allow for" and wrapped
            // "Allow once" onto two lines inside an uneven pill. Now:
            // weighted widths (session widest), 12sp single-line labels
            // with wrapping disabled, tight content padding, even 44dp
            // height — all three labels fit fully at 360dp width.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        onApproval(false)
                        onApprovalWithLevel(com.vayro.app.security.PermissionLevel.DENY, false)
                    },
                    modifier = Modifier.weight(0.95f).height(44.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text("Deny", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = {
                        onApprovalWithLevel(com.vayro.app.security.PermissionLevel.SESSION, true)
                    },
                    modifier = Modifier.weight(1.6f).height(44.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text("Allow for session", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = {
                        onApproval(true)
                        onApprovalWithLevel(com.vayro.app.security.PermissionLevel.ONCE, true)
                    },
                    modifier = Modifier.weight(1.05f).height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = com.vayro.app.ui.theme.KodexClay),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text("Allow once", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "%.1f MB".format(bytes / 1_048_576.0)
}

/**
 * U6d: end-of-run changes review as an in-chat card. Shows what changed
 * and takes the Keep/Revert decision right in the conversation — the
 * same handlers as the Changes tab, so both stay in sync through state.
 */
@Composable
private fun ChangesReviewCard(
    changes: List<ChangeItem>,
    onKeep: () -> Unit,
    onUndo: () -> Unit,
) {
    val added = changes.sumOf { it.additions }
    val deleted = changes.sumOf { it.deletions }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Changes to review", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("+$added", color = PocketGreen, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text("−$deleted", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            changes.take(6).forEach { change ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        change.path,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("+${change.additions}", color = PocketGreen, fontSize = 12.sp)
                    Spacer(Modifier.width(5.dp))
                    Text("−${change.deletions}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
            if (changes.size > 6) {
                Text(
                    "+${changes.size - 6} more files — open the Changes tab for every diff",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUndo, modifier = Modifier.weight(1f).height(44.dp)) {
                    Text("Revert", maxLines = 1, softWrap = false)
                }
                Button(onClick = onKeep, modifier = Modifier.weight(1f).height(44.dp)) {
                    Text("Keep changes", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun ChangesTab(
    changes: List<ChangeItem>,
    onUndo: () -> Unit,
    onKeep: () -> Unit,
    onUndoFile: (String) -> Unit,
    onKeepFile: (String) -> Unit,
    onOpenFile: (WorkspaceEntry) -> Unit,
) {
    var expandedPath by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text("Changes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Review everything the AI changed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (changes.isEmpty()) item { EmptyState(Icons.Default.Code, "No changes yet", "Ask Kodex to update your project.") }
        items(changes, key = { it.path }) { change ->
            val expanded = expandedPath == change.path
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { expandedPath = if (expanded) null else change.path }.padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Description, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(change.path, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (expanded) "Hide line-by-line diff" else "Tap to review diff",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            val depth = change.path.count { it == '/' }
                            onOpenFile(
                                WorkspaceEntry(
                                    path = change.path,
                                    name = change.path.substringAfterLast('/'),
                                    isDirectory = false,
                                    depth = depth,
                                )
                            )
                        }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open file")
                        }
                        Text("+${change.additions}", color = PocketGreen)
                        Spacer(Modifier.width(7.dp))
                        Text("-${change.deletions}", color = MaterialTheme.colorScheme.error)
                    }
                    if (expanded) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)).horizontalScroll(rememberScrollState()),
                        ) {
                            change.diffLines.forEach { line -> DiffLineRow(line) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    expandedPath = null
                                    onUndoFile(change.path)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Undo file") }
                            Button(
                                onClick = {
                                    expandedPath = null
                                    onKeepFile(change.path)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Keep file") }
                        }
                    }
                }
            }
        }
        if (changes.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(onClick = onUndo, Modifier.weight(1f)) { Text("Undo task") }
                Button(onClick = onKeep, Modifier.weight(1f)) { Text("Keep changes") }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val marker = when (line.type) {
        DiffLineType.ADDITION -> "+"
        DiffLineType.DELETION -> "-"
        DiffLineType.CONTEXT -> " "
        DiffLineType.INFO -> "·"
    }
    // Medium: diff colors were hardcoded dark-only values — a dark
    // island in light mode. Theme containers are the same hues with
    // a designed pairing for each theme.
    val background = when (line.type) {
        DiffLineType.ADDITION -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        DiffLineType.DELETION -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    val foreground = when (line.type) {
        DiffLineType.ADDITION -> MaterialTheme.colorScheme.onTertiaryContainer
        DiffLineType.DELETION -> MaterialTheme.colorScheme.onErrorContainer
        DiffLineType.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        DiffLineType.CONTEXT -> MaterialTheme.colorScheme.onSurface
    }
    val oldNumber = line.oldLine?.toString().orEmpty().padStart(4)
    val newNumber = line.newLine?.toString().orEmpty().padStart(4)
    Text(
        text = "$oldNumber $newNumber  $marker ${line.text}",
        modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 8.dp, vertical = 2.dp),
        color = foreground,
        fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        softWrap = false,
    )
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily)
        }
    }
}

@Composable
private fun BrandMark(modifier: Modifier = Modifier, compact: Boolean = false) {
    val size = if (compact) 32.dp else 42.dp
    val cornerRadius = if (compact) 9.dp else 12.dp

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(id = R.drawable.kodex_mark),
            contentDescription = "Kodex",
            modifier = Modifier.fillMaxSize(),
        )
    }
}


// ---------------------------------------------------------------------------
// Kodex side drawer: replaced in v3.2.32 by KodexSidebar (ClaudeShell.kt).
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Kodex Delivered card (v3.2.31): shown under an agent message that reports
// a built APK path. Metadata (app name, package, version, size, stack,
// SHA-256) is read from the real APK file — never invented. Buttons:
// Install, Save (to Downloads), Share.
// ---------------------------------------------------------------------------

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DeliveredApkCard(
    apkPath: String,
    loadData: suspend (String) -> DeliveredApkData?,
    onInstall: (String) -> Unit,
    onShare: (String) -> Unit,
    onSave: (String) -> Unit,
    onPreview: (String, String) -> Unit,
    onLocate: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    // U7a: the load ALWAYS completes — "loading" and "couldn't read" are
    // separate states now, so the card can never sit on "Reading APK
    // info…" forever when the file cannot be found.
    var data by remember(apkPath) { mutableStateOf<DeliveredApkData?>(null) }
    var loaded by remember(apkPath) { mutableStateOf(false) }
    LaunchedEffect(apkPath) {
        data = runCatching { loadData(apkPath) }.getOrNull()
        loaded = true
    }
    var showActions by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = PocketGreen,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Delivered", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Text(
                data?.fileName ?: apkPath.substringAfterLast('/'),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            val resolved = data?.resolvedPath
            when {
                !loaded -> Text(
                    "Reading APK info…",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                resolved == null -> {
                    // U7a honest not-found state: no live-looking Install
                    // button — a Locate action into the project Files
                    // instead.
                    Text(
                        "Kodex couldn't find this APK in the project files. It may have been moved or the build may not have finished.",
                        fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onLocate != null) {
                        OutlinedButton(
                            onClick = onLocate,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        ) {
                            Text("Locate APK")
                        }
                    }
                }
                else -> {
                    val meta = data?.info
                    if (meta != null) {
                        DeliveredMetaRow("App", meta.appName)
                        DeliveredMetaRow("Package", meta.packageId)
                        DeliveredMetaRow("Version", meta.versionLabel)
                        DeliveredMetaRow("Size", meta.sizeLabel)
                        DeliveredMetaRow("Stack", meta.stack)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "SHA-256",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(64.dp),
                            )
                            Text(
                                meta.sha256.take(16) + "…",
                                fontSize = 11.sp,
                                fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                                modifier = Modifier.clickable {
                                    clipboardManager.setText(AnnotatedString(meta.sha256))
                                    Toast.makeText(context, "SHA-256 copied", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    } else {
                        // Metadata unreadable — name + size from the file
                        // itself still beats a stuck loading label.
                        data?.sizeLabel?.let { DeliveredMetaRow("Size", it) }
                        Text(
                            "Full APK details couldn't be read, but the file is here and ready.",
                            fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { onInstall(resolved) },
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) {
                            Text("Install", maxLines = 1, softWrap = false)
                        }
                        val previewTarget = data?.previewTarget
                        if (!data?.previewKind.isNullOrEmpty() && previewTarget != null) {
                            OutlinedButton(
                                onClick = { onPreview(data?.previewKind.orEmpty(), previewTarget) },
                                modifier = Modifier.weight(1f).height(44.dp),
                            ) {
                                Text("Preview", maxLines = 1, softWrap = false)
                            }
                        }
                        IconButton(onClick = { showActions = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onSave(resolved) },
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) {
                            Text("Save", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = { onShare(resolved) },
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) {
                            Text("Share", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
    // U7c polish: card actions overflow opens the app's own sheet —
    // no default dropdown menus.
    if (showActions) {
        val resolvedPath = data?.resolvedPath
        ModalBottomSheet(
            onDismissRequest = { showActions = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            Column(Modifier.padding(bottom = 26.dp)) {
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        data?.fileName ?: "Delivered APK",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                if (resolvedPath != null) {
                    AttachMenuRow(
                        icon = Icons.Outlined.ContentCopy,
                        title = "Copy APK path",
                        subtitle = "The full path of the built file",
                        enabled = true,
                        onClick = {
                            clipboardManager.setText(AnnotatedString(resolvedPath))
                            Toast.makeText(context, "APK path copied", Toast.LENGTH_SHORT).show()
                            showActions = false
                        },
                    )
                }
                val sha = data?.info?.sha256
                if (sha != null) {
                    AttachMenuRow(
                        icon = Icons.Outlined.Fingerprint,
                        title = "Copy SHA-256",
                        subtitle = "Verify the file is exactly what was built",
                        enabled = true,
                        onClick = {
                            clipboardManager.setText(AnnotatedString(sha))
                            Toast.makeText(context, "SHA-256 copied", Toast.LENGTH_SHORT).show()
                            showActions = false
                        },
                    )
                }
                if (onLocate != null) {
                    AttachMenuRow(
                        icon = Icons.Outlined.FolderOpen,
                        title = "Locate in Files",
                        subtitle = "Open the project files",
                        enabled = true,
                        onClick = {
                            showActions = false
                            onLocate()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeliveredMetaRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------------------
// Kodex attach menu row (v3.2.31): one row of the composer's "+" menu.
// ---------------------------------------------------------------------------

@Composable
private fun AttachMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
