package com.vayro.app.ui

import com.vayro.app.AppCrashLogger

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import com.vayro.app.runtime.BackgroundProtectionHelper
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.BuildConfig
import com.vayro.app.data.ApiKeyInfo
import com.vayro.app.model.AgentKind
import com.vayro.app.model.DEEPSEEK_HARNESS_PROVIDERS
import com.vayro.app.model.DSH_PROTOCOL_PROVIDERS
import com.vayro.app.model.DevStack
import com.vayro.app.model.ProviderKind
import com.vayro.app.model.ProviderProfile
import com.vayro.app.model.providersForAgent
import com.vayro.app.network.ConnectionValidation
import com.vayro.app.network.DiscoveredModel
import com.vayro.app.network.ModelDiscoveryResult
import com.vayro.app.data.AppPreferences
import com.vayro.app.runtime.AntigravityAuthStatus
import com.vayro.app.runtime.EnvironmentComponentState
import com.vayro.app.runtime.EnvironmentManifestComponent
import com.vayro.app.runtime.EnvironmentUpdateManager
import com.vayro.app.runtime.RuntimeInstaller
import com.vayro.app.ui.theme.AppThemeMode
import com.vayro.app.ui.theme.PocketGreen
import com.vayro.app.ui.theme.PocketOrange
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayro.app.auth.model.AppUser
import com.vayro.app.auth.model.AuthState
import com.vayro.app.auth.model.AuthProviderType
import com.vayro.app.auth.ui.AuthViewModel
import com.vayro.app.auth.ui.ChangePasswordDialog
import com.vayro.app.auth.ui.DeleteAccountDialog
import com.vayro.app.auth.ui.EditDisplayNameDialog

private enum class SettingsSection { ACCOUNT, APPEARANCE, TOOLS, RUNTIME, ENVIRONMENT, CONTRIBUTORS, UPDATE_CHANNEL, PERMISSIONS }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: AppUiState,
    onSaveProvider: (ProviderProfile, String) -> Unit,
    onDiscoverModels: suspend (ProviderProfile, String) -> ModelDiscoveryResult,
    onValidateProvider: suspend (ProviderProfile, String, List<DiscoveredModel>) -> ConnectionValidation,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onPing: () -> Unit,
    onClearTerminal: () -> Unit,
    getSavedApiKey: (ProviderKind) -> String,
    getSavedApiKeys: (ProviderKind) -> List<ApiKeyInfo>,
    onAddApiKey: (ProviderKind, String, String) -> List<ApiKeyInfo>,
    onActivateApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onRemoveApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onInstallDevStack: (DevStack) -> Unit = {},
    onUpdateDevStack: (DevStack) -> Unit = {},
    onRemoveDevStack: (DevStack) -> Unit = {},
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
    initialUpdateManifestUrl: String = "",
    initialUpdateAuthToken: String = "",
    onSetUpdateManifestUrl: (String) -> Unit = {},
    onClearUpdateManifestUrl: () -> Unit = {},
    onSetUpdateAuthToken: (String) -> Unit = {},
    onCheckForUpdate: () -> Unit = {},
    initialDebugUpdateManifestUrl: String = "",
    onSetDebugUpdateManifestUrl: (String) -> Unit = {},
    onClearDebugUpdateManifestUrl: () -> Unit = {},
    onToggleAutoUpdateToolsAndSkills: (Boolean) -> Unit = {},
    onUpdateAllToolsAndSkills: () -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
    authViewModel: AuthViewModel? = null,
    onNavigateToAuth: () -> Unit = {},
    onBackupWorkspaces: () -> Unit = {},
    onRestoreWorkspaces: () -> Unit = {},
    onTogglePermissionPolicy: (com.vayro.app.security.PermissionCategory, Boolean) -> Unit = { _, _ -> },
    onClearSecurityAuditLogs: () -> Unit = {},
    onReplayAppTour: () -> Unit = {},
) {
    val context = LocalContext.current
    val runtimeInstaller = remember { RuntimeInstaller(context) }
    val appPrefs = remember { AppPreferences(context) }
    var agentBrowserDriveEnabled by remember { mutableStateOf(appPrefs.agentBrowserDriveEnabled) }
    val installedEnvVersions = remember(state.installedDevStacks) {
        runCatching { runtimeInstaller.installedEnvironmentVersions() }.getOrDefault(emptyMap())
    }
    var expanded by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var terminalCleared by remember { mutableStateOf(false) }
    var showReliabilityHelp by rememberSaveable { mutableStateOf(false) }
    var stackPendingRemoval by remember { mutableStateOf<DevStack?>(null) }
    var showAuditLogSheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { onRefreshInstalledAgents() }

    stackPendingRemoval?.let { stack ->
        AlertDialog(
            onDismissRequest = { stackPendingRemoval = null },
            title = { Text("Remove ${stack.label}?") },
            text = {
                Text("This removes the toolchain and its runtime caches to free storage. Your projects and source files will not be deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        stackPendingRemoval = null
                        onRemoveDevStack(stack)
                    },
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { stackPendingRemoval = null }) { Text("Cancel") } },
        )
    }

    authViewModel?.let { authVm ->
        val authUiState by authVm.uiState.collectAsStateWithLifecycle()
        val authStateVal by authVm.authState.collectAsStateWithLifecycle()
        val userVal = when (authStateVal) {
            is AuthState.Authenticated -> (authStateVal as AuthState.Authenticated).user
            is AuthState.EmailVerificationRequired -> (authStateVal as AuthState.EmailVerificationRequired).user
            else -> null
        }

        if (authUiState.showEditNameDialog && userVal != null) {
            EditDisplayNameDialog(
                currentName = userVal.displayName ?: "",
                isLoading = authUiState.isLoading,
                onDismiss = { authVm.showEditName(false) },
                onSubmit = { newName -> authVm.updateDisplayName(newName) },
            )
        }
        if (authUiState.showChangePasswordDialog) {
            ChangePasswordDialog(
                isLoading = authUiState.isLoading,
                onDismiss = { authVm.showChangePassword(false) },
                onSubmit = { cur, new, confirm -> authVm.changePassword(cur, new, confirm) },
            )
        }
        if (authUiState.showDeleteAccountDialog) {
            val isEmail = userVal?.provider == AuthProviderType.EMAIL
            DeleteAccountDialog(
                isEmailProvider = isEmail,
                isLoading = authUiState.isLoading,
                onDismiss = { authVm.showDeleteAccount(false) },
                onConfirm = { pass -> authVm.deleteAccount(pass) },
            )
        }
    }

    fun toggle(section: SettingsSection) {
        expanded = if (expanded == section) null else section
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(top = 8.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(9.dp),
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                                    shape = RoundedCornerShape(9.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy((-1).dp),
                        ) {
                            Text(
                                "Settings",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge,
                                lineHeight = 23.sp,
                            )
                            Text(
                                "Preferences & Configuration",
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            item {
                val currentAuth = authViewModel?.authState?.collectAsStateWithLifecycle()?.value ?: AuthState.Unauthenticated
                val user = when (currentAuth) {
                    is AuthState.Authenticated -> currentAuth.user
                    is AuthState.EmailVerificationRequired -> currentAuth.user
                    else -> null
                }
                SettingsAccordion(
                    title = "Account",
                    subtitle = user?.displayName?.ifBlank { null } ?: user?.email ?: if (user != null) "Signed in" else "Guest mode · Local only",
                    icon = Icons.Default.Person,
                    expanded = expanded == SettingsSection.ACCOUNT,
                    onClick = { toggle(SettingsSection.ACCOUNT) },
                ) {
                    if (authViewModel != null) {
                        AccountSectionContent(
                            viewModel = authViewModel,
                            authState = currentAuth,
                            onNavigateToAuth = onNavigateToAuth,
                        )
                    } else {
                        Text(
                            "Authentication service not initialized.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SettingsAccordion(
                    title = "Appearance",
                    subtitle = when (state.themeMode) { AppThemeMode.DARK -> "Dark theme"; AppThemeMode.LIGHT -> "Light theme"; AppThemeMode.SYSTEM -> "Follow system" },
                    icon = Icons.Default.Tune,
                    expanded = expanded == SettingsSection.APPEARANCE,
                    onClick = { toggle(SettingsSection.APPEARANCE) },
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModernThemeChoice("Dark", Icons.Default.DarkMode, state.themeMode == AppThemeMode.DARK, { onSetThemeMode(AppThemeMode.DARK) }, Modifier.weight(1f))
                        ModernThemeChoice("Light", Icons.Default.LightMode, state.themeMode == AppThemeMode.LIGHT, { onSetThemeMode(AppThemeMode.LIGHT) }, Modifier.weight(1f))
                        ModernThemeChoice("System", Icons.Default.PhoneAndroid, state.themeMode == AppThemeMode.SYSTEM, { onSetThemeMode(AppThemeMode.SYSTEM) }, Modifier.weight(1f))
                    }
                }
            }

            item {
                val installedCount = state.installedDevStacks.count { it != DevStack.WEB }
                SettingsAccordion(
                    title = "Developer tools",
                    subtitle = "Core tools + $installedCount optional toolchain${if (installedCount == 1) "" else "s"}",
                    icon = Icons.Default.Code,
                    expanded = expanded == SettingsSection.TOOLS,
                    onClick = { toggle(SettingsSection.TOOLS) },
                ) {
                    // Top Overview Card matching EnvironmentUpdateCenter
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Runtime resources", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Core Linux tooling is included. Optional development stacks are isolated in PRoot userspace.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            listOf(
                                "Ubuntu 20.04 ARM64" to "Included",
                                "Node.js + npm" to "Included",
                                "Git + OpenSSL + curl" to "Included",
                                "Claude Code Engine" to "Included",
                            ).forEach { (label, detail) ->
                                RuntimeInfoRow(label, detail)
                            }
                        }
                    }

                    // Auto-update Card matching EnvironmentUpdateCenter card style
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text("Auto-update tools & skills", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(
                                        state.toolsAutoUpdateMessage ?: "Automatically keep installed toolchains, tools, and skills up to date",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = state.autoUpdateToolsAndSkills,
                                    onCheckedChange = onToggleAutoUpdateToolsAndSkills,
                                    enabled = !state.autoUpdatingToolsOrSkills,
                                )
                            }
                            if (state.autoUpdatingToolsOrSkills) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                    color = PocketOrange,
                                )
                            }
                        }
                    }

                    // Linear-style list rows with dividers: one line per toolchain.
                    // Tap a row to expand install / update / remove actions.
                    val devStacks = DevStack.entries
                    devStacks.forEachIndexed { index, stack ->
                        val installed = stack in state.installedDevStacks
                        val installing = state.devStackInstalling == stack
                        val removing = installing && state.devStackRemoving
                        var expanded by remember(stack) { mutableStateOf(false) }

                        val versionBadge = if (installed) {
                            when (stack) {
                                DevStack.WEB -> "v24"
                                // Lookup miss = unknown, never an invented version.
                                DevStack.PYTHON -> installedEnvVersions["python"]?.substringBefore(" (pip")?.let { "v$it" } ?: "Unknown version"
                                DevStack.ANDROID -> installedEnvVersions["android"]?.let { "v$it" } ?: "Unknown version"
                                DevStack.CPP -> installedEnvVersions["cpp"]?.let { "v$it" } ?: "Unknown version"
                                DevStack.PHP -> installedEnvVersions["php"]?.let { "v$it" } ?: "Unknown version"
                                DevStack.BROWSER -> installedEnvVersions["browser"]?.let { "v$it" } ?: "Unknown version"
                            }
                        } else null

                        val statusLabel = when {
                            removing -> "REMOVING"
                            installing -> "INSTALLING"
                            installed && stack == DevStack.WEB -> "INCLUDED"
                            installed -> "INSTALLED"
                            else -> "AVAILABLE"
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stack.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(
                                        when {
                                            !installed -> "Not installed"
                                            versionBadge != null -> "$versionBadge · Ready in PRoot"
                                            stack == DevStack.WEB -> "Included in base rootfs"
                                            else -> "Installed & ready in PRoot"
                                        },
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    statusLabel,
                                    fontSize = 11.sp,
                                    color = if (installed) PocketGreen else PocketOrange,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (expanded) "Collapse" else "Expand",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            AnimatedVisibility(visible = expanded) {
                                Column(
                                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        stack.installsSummary,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "Target environment: PRoot Debian/Ubuntu userspace",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    when {
                                        removing -> {
                                            Text("Removing toolchain…", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                        installing -> {
                                            Text("${(state.devStackProgress * 100).toInt()}%", color = PocketOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            LinearProgressIndicator(
                                                progress = { state.devStackProgress.coerceIn(0f, 1f) },
                                                modifier = Modifier.fillMaxWidth().height(7.dp),
                                                color = PocketOrange,
                                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                            )
                                            state.devStackBytes?.let { (downloaded, total) ->
                                                Surface(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                                ) {
                                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                            Text(
                                                                "${formatTransferMb(downloaded)} of ${formatTransferMb(total)}",
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                fontFamily = FontFamily.Monospace,
                                                            )
                                                            state.devStackBytesPerSecond?.takeIf { it > 0L }?.let { speed ->
                                                                Text(
                                                                    "${formatTransferSpeed(speed)} · ${formatTransferEta(downloaded, total, speed)} left",
                                                                    fontSize = 11.sp,
                                                                    color = PocketOrange,
                                                                    fontFamily = FontFamily.Monospace,
                                                                )
                                                            }
                                                        }
                                                        Text(
                                                            state.devStackMessage ?: "Downloading…",
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            } ?: Text(
                                                state.devStackMessage ?: "Processing…",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        installed && stack == DevStack.WEB -> {
                                            Text(
                                                "Bundled in Core Rootfs",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                        installed -> {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Button(
                                                    onClick = { onUpdateDevStack(stack) },
                                                    enabled = state.devStackInstalling == null,
                                                    modifier = Modifier.weight(1f),
                                                ) {
                                                    Text("Update", fontSize = 12.sp)
                                                }
                                                OutlinedButton(
                                                    onClick = { stackPendingRemoval = stack },
                                                    enabled = state.devStackInstalling == null,
                                                    modifier = Modifier.weight(1f),
                                                ) {
                                                    Text("Remove", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                        else -> {
                                            Button(
                                                onClick = { onInstallDevStack(stack) },
                                                enabled = state.devStackInstalling == null,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Text("Install Stack", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (index < devStacks.size - 1) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }

                    state.devStackMessage?.takeIf { state.devStackInstalling == null && it.isNotBlank() }?.let { statusMsg ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (statusMsg.contains("could not", ignoreCase = true) || statusMsg.contains("failed", ignoreCase = true))
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (statusMsg.contains("could not", ignoreCase = true) || statusMsg.contains("failed", ignoreCase = true))
                                        Icons.Default.Info
                                    else
                                        Icons.Default.Check,
                                    null,
                                    modifier = Modifier.size(15.dp),
                                    tint = if (statusMsg.contains("could not", ignoreCase = true) || statusMsg.contains("failed", ignoreCase = true))
                                        MaterialTheme.colorScheme.error
                                    else
                                        PocketOrange,
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    statusMsg,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (statusMsg.contains("could not", ignoreCase = true) || statusMsg.contains("failed", ignoreCase = true))
                                        MaterialTheme.colorScheme.onErrorContainer
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingsAccordion(
                    title = "Linux runtime",
                    subtitle = "Ubuntu 20.04 PRoot · ARM64",
                    icon = Icons.Default.Terminal,
                    expanded = expanded == SettingsSection.RUNTIME,
                    onClick = { toggle(SettingsSection.RUNTIME) },
                ) {
                    RuntimeInfoRow("Architecture", "ARM64 (aarch64)")
                    RuntimeInfoRow("Environment", "Ubuntu 20.04 PRoot")
                    RuntimeInfoRow(
                        "Active agent",
                        state.agentKind.title + if (state.installedAgentVersions.containsKey(state.agentKind)) "" else " · Not installed",
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        "Installed agents",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.installedAgentVersions.isEmpty()) {
                        RuntimeInfoRow("Status", "No verified agent installation")
                    } else {
                        AgentKind.entries.forEach { agent ->
                            state.installedAgentVersions[agent]?.let { version ->
                                RuntimeInfoRow(agent.title, "v$version")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onClearTerminal(); terminalCleared = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.DeleteSweep, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(if (terminalCleared) "Terminal history cleared" else "Clear terminal history")
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        "Workspace Data Protection & Rollback",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onBackupWorkspaces,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Upload, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Backup all projects to storage")
                    }
                    OutlinedButton(
                        onClick = onRestoreWorkspaces,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Restore projects from backup")
                    }
                    OutlinedButton(
                        onClick = { showReliabilityHelp = !showReliabilityHelp },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Advanced runtime reliability")
                    }
                    AnimatedVisibility(showReliabilityHelp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Ensure tasks never pause when switching to recent apps or locking the phone.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = {
                                    val intent = BackgroundProtectionHelper.getBatteryOptimizationIntent(context)
                                    runCatching { context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
                                        .onFailure {
                                            runCatching {
                                                context.startActivity(BackgroundProtectionHelper.getAppDetailsSettingsIntent(context).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                                            }.onFailure {
                                                Toast.makeText(context, "Could not open battery settings", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.BatterySaver, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Battery optimization (Unrestricted)")
                            }
                            if (BackgroundProtectionHelper.isOemDeviceWithAggressiveKiller()) {
                                OutlinedButton(
                                    onClick = { BackgroundProtectionHelper.openOemBackgroundSettings(context) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Default.Settings, null, Modifier.size(17.dp))
                                    Spacer(Modifier.width(7.dp))
                                    Text("Configure ${BackgroundProtectionHelper.getOemName()} Autostart")
                                }
                            }
                            Text(
                                "If large builds stop unexpectedly, Android Developer options may provide a child-process restriction toggle.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                                        .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Open Developer options") }
                        }
                    }
                }
            }

            item {
                SettingsAccordion(
                    title = "Environment updates",
                    subtitle = "Core, Python, Android & AI agent runtimes",
                    icon = Icons.Default.Refresh,
                    expanded = expanded == SettingsSection.ENVIRONMENT,
                    onClick = { toggle(SettingsSection.ENVIRONMENT) },
                ) {
                    EnvironmentUpdateCenter()
                }
            }

            item {
                AppUpdateChannelSection(
                    initialUrl = initialUpdateManifestUrl.ifBlank { initialDebugUpdateManifestUrl },
                    initialToken = initialUpdateAuthToken,
                    onSave = { url, token ->
                        onSetUpdateManifestUrl(url)
                        onSetUpdateAuthToken(token)
                    },
                    onClear = {
                        onClearUpdateManifestUrl()
                        onSetUpdateAuthToken("")
                        onClearDebugUpdateManifestUrl()
                    },
                    onCheckNow = onCheckForUpdate,
                )
            }

            item {
                SettingsAccordion(
                    title = "Permissions & Safety",
                    subtitle = "Granular execution boundaries & audit trails",
                    icon = Icons.Default.Lock,
                    expanded = expanded == SettingsSection.PERMISSIONS,
                    onClick = { toggle(SettingsSection.PERMISSIONS) },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "These toggles are enforced for Claude Code only. Antigravity and DeepSeek run headless with their CLI defaults (full access, no per-action prompts). The emergency kill switch below blocks new sessions for all agents.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Shell execution", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text("Allow agent to execute terminal commands", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = state.allowShellExec,
                                onCheckedChange = { onTogglePermissionPolicy(com.vayro.app.security.PermissionCategory.SHELL_EXEC, it) },
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Network access", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text("Allow agent outbound HTTP and curl requests", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = state.allowNetworkAccess,
                                onCheckedChange = { onTogglePermissionPolicy(com.vayro.app.security.PermissionCategory.NETWORK, it) },
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Auto-delete files", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text("Require explicit user prompt before deleting files", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = state.allowFileDeletion,
                                onCheckedChange = { onTogglePermissionPolicy(com.vayro.app.security.PermissionCategory.FILE_DELETE, it) },
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Git push", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text("Require prompt before pushing commits to remote", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = state.allowGitPush,
                                onCheckedChange = { onTogglePermissionPolicy(com.vayro.app.security.PermissionCategory.GIT_PUSH, it) },
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Install device APKs", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text("Allow background APK installations without prompt", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = state.allowApkInstall,
                                onCheckedChange = { onTogglePermissionPolicy(com.vayro.app.security.PermissionCategory.INSTALL_APK, it) },
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("External services", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text("Allow calls to external APIs without prompt", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = state.allowExternalServices,
                                onCheckedChange = { onTogglePermissionPolicy(com.vayro.app.security.PermissionCategory.EXTERNAL_SERVICES, it) },
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Access environment variables", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text("Allow reading environment variables without prompt", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = state.allowAccessEnv,
                                onCheckedChange = { onTogglePermissionPolicy(com.vayro.app.security.PermissionCategory.ACCESS_ENV, it) },
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Let agents drive the browser tab", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text("Agents can control the visible browser tab with pocket-browser attach. You watch live and can take over anytime.", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = agentBrowserDriveEnabled,
                                onCheckedChange = {
                                    appPrefs.agentBrowserDriveEnabled = it
                                    agentBrowserDriveEnabled = it
                                    if (it) {
                                        runCatching { android.webkit.WebView.setWebContentsDebuggingEnabled(true) }
                                    } else {
                                        runCatching {
                                            runtimeInstaller.installedRuntime().rootfs
                                        }.getOrNull()?.let { rootfs ->
                                            java.io.File(rootfs, "tmp/pocket-browser-attach.json").delete()
                                        }
                                    }
                                },
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        Text(
                            "Scope: per-tool approval above is enforced for Claude Code. Other agents run with their CLI defaults. The emergency kill switch blocks new agent sessions and MCP calls.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(4.dp))

                        Button(
                            onClick = { showAuditLogSheet = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("View Security Audit Log", fontSize = 12.sp)
                        }
                    }
                }
            }

            item {
                SettingsAccordion(
                    title = "Contributors",
                    subtitle = "People building Kodex",
                    icon = Icons.Default.Person,
                    expanded = expanded == SettingsSection.CONTRIBUTORS,
                    onClick = { toggle(SettingsSection.CONTRIBUTORS) },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CONTRIBUTORS.forEach { contributor ->
                            ContributorCard(contributor)
                        }
                    }
                }
            }

            item {
                Surface(
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Settings, null, Modifier.size(20.dp), tint = PocketOrange)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Kodex", fontWeight = FontWeight.SemiBold)
                            Text("Local AI coding workspace", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPrivacyPolicy() }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.PrivacyTip,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Privacy policy", fontWeight = FontWeight.Medium)
                        Text(
                            "How local data and AI provider requests are handled",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "View privacy policy",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }

    if (showAuditLogSheet) {
        var searchQuery by rememberSaveable { mutableStateOf("") }
        var selectedCategory by rememberSaveable { mutableStateOf<com.vayro.app.security.PermissionCategory?>(null) }
        var selectedDecision by rememberSaveable { mutableStateOf<com.vayro.app.security.AuditDecision?>(null) }

        val filteredEntries = remember(state.securityAuditLogs, searchQuery, selectedCategory, selectedDecision) {
            val q = searchQuery.trim().lowercase(java.util.Locale.ROOT)
            state.securityAuditLogs.filter { entry ->
                if (selectedCategory != null && entry.category != selectedCategory) return@filter false
                if (selectedDecision != null && entry.decision != selectedDecision) return@filter false
                if (q.isNotEmpty()) {
                    val match = entry.action.lowercase(java.util.Locale.ROOT).contains(q) ||
                        entry.target.lowercase(java.util.Locale.ROOT).contains(q) ||
                        entry.reason.lowercase(java.util.Locale.ROOT).contains(q) ||
                        entry.category.name.lowercase(java.util.Locale.ROOT).contains(q) ||
                        entry.decision.name.lowercase(java.util.Locale.ROOT).contains(q)
                    if (!match) return@filter false
                }
                true
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showAuditLogSheet = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(horizontal = 18.dp)
                    .imePadding(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Security Audit Trail", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Recent access evaluations, policy checks, and blocked dangerous actions",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.securityAuditLogs.isNotEmpty()) {
                        TextButton(
                            onClick = { onClearSecurityAuditLogs() },
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear logs", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text("Clear", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search action, target, reason...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    item {
                        FilterChip(
                            selected = selectedCategory == null && selectedDecision == null,
                            onClick = {
                                selectedCategory = null
                                selectedDecision = null
                            },
                            label = { Text("All", fontSize = 11.sp) },
                        )
                    }
                    val filterCategories = listOf(
                        com.vayro.app.security.PermissionCategory.SHELL_EXEC,
                        com.vayro.app.security.PermissionCategory.NETWORK,
                        com.vayro.app.security.PermissionCategory.FILE_DELETE,
                        com.vayro.app.security.PermissionCategory.GIT_PUSH,
                        com.vayro.app.security.PermissionCategory.INSTALL_APK,
                        com.vayro.app.security.PermissionCategory.EXTERNAL_SERVICES,
                        com.vayro.app.security.PermissionCategory.ACCESS_ENV,
                        com.vayro.app.security.PermissionCategory.SECRETS,
                    )
                    items(filterCategories) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                            label = { Text(cat.name, fontSize = 11.sp) },
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedDecision == com.vayro.app.security.AuditDecision.BLOCKED_DANGEROUS,
                            onClick = {
                                selectedDecision = if (selectedDecision == com.vayro.app.security.AuditDecision.BLOCKED_DANGEROUS) null else com.vayro.app.security.AuditDecision.BLOCKED_DANGEROUS
                            },
                            label = { Text("Blocked Only", fontSize = 11.sp) },
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                if (filteredEntries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (state.securityAuditLogs.isEmpty()) "No security events recorded yet." else "No matching security events found.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filteredEntries, key = { it.id }) { entry ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.surface,
                                            ) {
                                                Text(
                                                    entry.category.name,
                                                    fontSize = 9.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                            Text(entry.action, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                        Text(
                                            entry.decision.name,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = when (entry.decision) {
                                                com.vayro.app.security.AuditDecision.ALLOWED -> MaterialTheme.colorScheme.tertiary
                                                com.vayro.app.security.AuditDecision.BLOCKED_DANGEROUS, com.vayro.app.security.AuditDecision.DENIED -> MaterialTheme.colorScheme.error
                                                else -> PocketOrange
                                            },
                                        )
                                    }
                                    if (entry.target.isNotBlank()) {
                                        Spacer(Modifier.height(3.dp))
                                        Text("Target: " + entry.target, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                    }
                                    if (entry.reason.isNotBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(entry.reason, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun EnvironmentUpdateCenter() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtimeInspector = remember { RuntimeInstaller(context) }
    val manager = remember { runCatching { EnvironmentUpdateManager(context, runtimeInspector) }.getOrNull() }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var components by remember { mutableStateOf(emptyList<EnvironmentManifestComponent>()) }
    var componentStates by remember { mutableStateOf(emptyList<EnvironmentComponentState>()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var updateJob by remember { mutableStateOf<Job?>(null) }
    var operationMessage by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        val updateManager = manager ?: run {
            message = "Runtime is not installed yet."
            return
        }
        loading = true
        scope.launch {
            try {
                val candidateUrls = listOf(
                    BuildConfig.APP_UPDATE_MANIFEST_URL,
                ).filter { it.isNotBlank() && it.startsWith("https://") }

                var remoteBody: String? = null
                withContext(Dispatchers.IO) {
                    for (urlString in candidateUrls) {
                        try {
                            val connection = (java.net.URL(urlString).openConnection() as java.net.HttpURLConnection)
                            connection.connectTimeout = 8_000
                            connection.readTimeout = 12_000
                            connection.instanceFollowRedirects = true
                            connection.setRequestProperty("Accept", "application/json")
                            connection.setRequestProperty("User-Agent", "Kodex/" + BuildConfig.VERSION_NAME)
                            val code = connection.responseCode
                            if (code in 200..299) {
                                val text = connection.inputStream.bufferedReader().use { it.readText() }
                                connection.disconnect()
                                if (text.isNotBlank()) {
                                    remoteBody = text
                                    break
                                }
                            } else {
                                connection.disconnect()
                            }
                        } catch (_: Throwable) {
                            // Try next candidate or fall back to bundled catalog
                        }
                    }
                }

                val manifestComponents = withContext(Dispatchers.IO) {
                    if (remoteBody != null) {
                        val parsed = updateManager.parseManifest(remoteBody!!)
                        if (parsed.isNotEmpty()) parsed else null
                    } else null
                } ?: withContext(Dispatchers.IO) {
                    val assetJson = runCatching {
                        context.assets.open("runtime/manifest.json").bufferedReader().use { it.readText() }
                    }.getOrNull()
                    if (assetJson != null) {
                        updateManager.parseManifest(assetJson)
                    } else {
                        updateManager.defaultComponents()
                    }
                }

                val live = withContext(Dispatchers.IO) {
                    runCatching { updateManager.refreshManifest(manifestComponents) }.getOrDefault(manifestComponents)
                }

                val environmentComponents = live.filterNot { it.id in setOf("claude", "deepseek", "antigravity") }
                val inspected = withContext(Dispatchers.IO) { updateManager.inspect(environmentComponents) }

                components = environmentComponents
                componentStates = inspected
                message = if (remoteBody != null) "Catalog up to date." else "Loaded verified environment catalog."
            } catch (error: Throwable) {
                val fallbackComponents = updateManager.defaultComponents().filterNot { it.id in setOf("claude", "deepseek", "antigravity") }
                val inspected = withContext(Dispatchers.IO) { updateManager.inspect(fallbackComponents) }
                components = fallbackComponents
                componentStates = inspected
                message = "Showing verified local catalog."
            } finally {
                loading = false
            }
        }
    }

    fun refreshAfterEnvironmentAction() {
        scope.launch {
            components.takeIf { it.isNotEmpty() }?.let { current ->
                componentStates = withContext(Dispatchers.IO) { manager?.inspect(current).orEmpty() }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Agents are managed only from the Agents screen. This page manages the Linux runtime, toolchains and development resources.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = {
                runtimeInspector.invalidateEnvironmentVersionCache()
                refresh()
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, null, Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(if (loading) "Checking…" else "Check for updates")
        }
        message?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        operationMessage?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        // Medium: the "Runtime resources" card + DevStack list used to be
        // rendered twice on this one screen — here and in the Developer
        // tools accordion. The accordion's interactive list is the single
        // home; this section keeps only the update components below.
        components.forEach { component ->
            val installedState = componentStates.firstOrNull { it.id == component.id }
            val hasUpdate = installedState?.latestVersion != null && installedState.latestVersion != installedState.currentVersion
            val canInstallOrUpdate = component.id in setOf("core", "claude", "deepseek", "antigravity", "python", "android", "php", "cpp") || component.packageUrl.startsWith("https://")
            val updateAvailable = canInstallOrUpdate && (installedState?.currentVersion == null || hasUpdate)
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(component.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    installedState?.currentVersion == null -> "Not installed · latest " + component.version
                                    hasUpdate -> "Installed " + installedState.currentVersion + " · latest " + component.version
                                    else -> "Up to date · v" + installedState.currentVersion
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(component.status.uppercase(), fontSize = 10.sp, color = PocketOrange, fontWeight = FontWeight.Bold)
                    }
                    if (component.notes.isNotBlank()) {
                        Text(component.notes, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (canInstallOrUpdate) {
                        Text("Package verification: SHA-256", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    busyId = component.id
                                    val job = scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                manager?.update(component) { progress ->
                                                    withContext(Dispatchers.Main.immediate) {
                                                        operationMessage = progress.message
                                                    }
                                                }
                                            }
                                            if (!isActive) return@launch
                                            operationMessage = component.label + if (installedState?.currentVersion == null) " installed successfully." else " updated successfully."
                                        } catch (error: Throwable) {
                                            operationMessage = if (error is CancellationException) "Update cancelled."
                                            else error.message ?: "Update failed."
                                        } finally {
                                            busyId = null
                                            refreshAfterEnvironmentAction()
                                        }
                                    }
                                    updateJob = job
                                    job.invokeOnCompletion { if (updateJob === job) updateJob = null }
                                },
                                enabled = busyId == null && updateAvailable,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (busyId == component.id) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Text(if (installedState?.currentVersion == null) "Install" else "Update")
                            }
                            if (busyId == component.id) {
                                OutlinedButton(
                                    onClick = {
                                        updateJob?.cancel(CancellationException("Environment update cancelled"))
                                        updateJob = null
                                        manager?.cancelActiveDownloads()
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Cancel", color = MaterialTheme.colorScheme.error)
                                }
                            } else if (installedState?.rollbackAvailable == true) {
                                OutlinedButton(
                                    onClick = {
                                        busyId = component.id
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    manager?.rollback(component) { progress ->
                                                        withContext(Dispatchers.Main.immediate) {
                                                            operationMessage = progress.message
                                                        }
                                                    }
                                                }
                                                operationMessage = component.label + " rollback completed."
                                            } catch (error: Throwable) {
                                                operationMessage = error.message ?: "Rollback unavailable."
                                            } finally {
                                                busyId = null
                                                refreshAfterEnvironmentAction()
                                            }
                                        }
                                    },
                                    enabled = busyId == null,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Rollback")
                                }
                            }
                        }
                    } else {
                        Text(
                            "Managed by the bundled runtime. No standalone update package is published for this component.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun formatTransferMb(bytes: Long): String = "%.1f MB".format(bytes.coerceAtLeast(0L) / 1_048_576.0)

private fun formatTransferSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_048_576L -> "%.1f MB/s".format(bytesPerSecond / 1_048_576.0)
    else -> "%.0f KB/s".format(bytesPerSecond / 1_024.0)
}

private fun formatTransferEta(downloaded: Long, total: Long, bytesPerSecond: Long): String {
    val seconds = ((total - downloaded).coerceAtLeast(0L) / bytesPerSecond.coerceAtLeast(1L)).coerceAtLeast(1L)
    return if (seconds >= 60L) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
}

@Composable
private fun SettingsAccordion(
    title: String,
    subtitle: String,
    icon: ImageVector,
    expanded: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
                }
            }
        }
    }
}





@Composable
private fun SelectionDot(selected: Boolean) {
    Box(
        Modifier.size(20.dp).border(if (selected) 2.dp else 1.dp, if (selected) PocketOrange else MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(9.dp).background(PocketOrange, CircleShape))
    }
}

@Composable
private fun ModernThemeChoice(title: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) PocketOrange.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) PocketOrange else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, title, Modifier.size(20.dp), tint = if (selected) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(5.dp))
            Text(title, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun RuntimeInfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f, fill = false).padding(end = 8.dp),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = when {
                value.startsWith("Installed") -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                value.startsWith("Included") -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Text(
                value,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                color = when {
                    value.startsWith("Installed") -> MaterialTheme.colorScheme.primary
                    value.startsWith("Included") -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun AppUpdateChannelSection(
    initialUrl: String = "",
    initialToken: String = "",
    onSave: (String, String) -> Unit = { _, _ -> },
    onClear: () -> Unit = {},
    onCheckNow: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val isOnline = BuildConfig.APP_VARIANT.equals("online", ignoreCase = true)

    SettingsAccordion(
        title = "App updates",
        subtitle = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}) · ${if (isOnline) "Online" else "Offline"}",
        icon = Icons.Default.CloudDownload,
        expanded = expanded,
        onClick = { expanded = !expanded },
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Single pill: version first, then ONLINE / OFFLINE. Fully
                // rounded like the button beside it, and smaller than before.
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isOnline) Color(0xFF2874F0) else Color(0xFF2E7D32),
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} ${if (isOnline) "ONLINE" else "OFFLINE"}",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }

                // Play builds never self-update from a GitHub APK (Play policy):
                // no Check-now button, just the honest statement.
                if (BuildConfig.IS_PLAY_BUILD) {
                    Text(
                        "Managed by Google Play",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onCheckNow),
                    ) {
                        Text(
                            text = "Check now",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
private data class ContributorLink(val label: String, val url: String)

private data class ContributorInfo(
    val name: String,
    val role: String,
    val handle: String,
    val avatarUrl: String,
    val initials: String,
    val links: List<ContributorLink>,
)

private val CONTRIBUTORS = listOf(
    ContributorInfo(
        name = "Conzyx \u2014 Upstream Project",
        role = "Kodex is built on the open-source Conzyx (Mobile Harness) project, used under the MIT License. Full license text ships with the app.",
        handle = "@sunmughan/Conzyx",
        avatarUrl = "https://github.com/sunmughan.png?size=256",
        initials = "CX",
        links = listOf(
            ContributorLink("GitHub", "https://github.com/sunmughan/Conzyx"),
        ),
    ),
)

@Composable
private fun ContributorCard(info: ContributorInfo) {
    var avatar by remember(info.avatarUrl) { mutableStateOf<Bitmap?>(null) }

    androidx.compose.runtime.LaunchedEffect(info.avatarUrl) {
        avatar = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(info.avatarUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 12_000
                connection.instanceFollowRedirects = true
                connection.inputStream.use { BitmapFactory.decodeStream(it) }.also { connection.disconnect() }
            }.getOrNull()
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(58.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    avatar?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = info.name,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } ?: Box(contentAlignment = Alignment.Center) {
                        Text(info.initials, fontWeight = FontWeight.Bold, color = PocketOrange)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(info.role, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(info.handle, fontSize = 11.sp, color = PocketOrange, fontFamily = FontFamily.Monospace)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                info.links.forEach { link ->
                    ContributorLinkButton(link.label, link.url, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ContributorLinkButton(label: String, url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            // Medium: a failed open used to fail silently (runCatching
            // swallowed the error). The user now gets told it failed.
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.onFailure {
                Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
            }
        },
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
    ) {
        Text(label, fontSize = 10.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun AccountSectionContent(
    viewModel: AuthViewModel,
    authState: AuthState,
    onNavigateToAuth: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val user = when (authState) {
        is AuthState.Authenticated -> authState.user
        is AuthState.EmailVerificationRequired -> authState.user
        else -> null
    }

    uiState.errorMessage?.let { error ->
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                IconButton(onClick = viewModel::dismissError, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(14.dp))
                }
            }
        }
    }

    uiState.successMessage?.let { success ->
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF2E9D72).copy(alpha = 0.15f),
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Check, null, tint = Color(0xFF2E9D72), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(success, fontSize = 12.sp, color = Color(0xFF2E9D72), modifier = Modifier.weight(1f))
                IconButton(onClick = viewModel::dismissSuccess, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(14.dp))
                }
            }
        }
    }

    if (user != null) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(PocketOrange.copy(alpha = 0.15f), shape = CircleShape)
                            .border(1.dp, PocketOrange.copy(alpha = 0.4f), shape = CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = (user.displayName?.take(1) ?: user.email?.take(1) ?: "U").uppercase(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = PocketOrange,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            user.displayName ?: "No display name set",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        Text(
                            user.email ?: "No email registered",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Provider", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    ) {
                        Text(
                            user.provider.title,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Email Verification", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (user.emailVerified) Color(0xFF2E9D72).copy(alpha = 0.12f) else MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    ) {
                        Text(
                            if (user.emailVerified) "Verified" else "Unverified",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (user.emailVerified) Color(0xFF2E9D72) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }

                if (!user.emailVerified && user.provider == AuthProviderType.EMAIL) {
                    OutlinedButton(
                        onClick = viewModel::resendEmailVerification,
                        enabled = !uiState.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Send Verification Email", fontSize = 12.sp)
                    }
                }

                user.createdAt?.let { created ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Member Since", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val dateStr = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date(created))
                        Text(dateStr, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        OutlinedButton(
            onClick = { viewModel.showEditName(true) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Edit Display Name")
        }

        if (user.provider == AuthProviderType.EMAIL) {
            OutlinedButton(
                onClick = { viewModel.showChangePassword(true) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Lock, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Change Password")
            }
        }

        OutlinedButton(
            onClick = viewModel::signOut,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        ) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sign Out")
        }

        TextButton(
            onClick = { viewModel.showDeleteAccount(true) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.DeleteOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text("Delete Account", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = PocketOrange.copy(alpha = 0.12f),
                        ) {
                            Text(
                                "Offline / Guest Mode",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PocketOrange,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        "You are currently using Kodex in local guest mode. All your projects, tools, and code run privately on this device.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = onNavigateToAuth,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = PocketOrange),
            ) {
                Text("Sign In / Create Account", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}



