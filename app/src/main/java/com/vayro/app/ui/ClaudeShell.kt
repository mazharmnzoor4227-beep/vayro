package com.vayro.app.ui

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.vayro.app.BuildConfig
import com.vayro.app.R
import com.vayro.app.data.ApiKeyVault
import com.vayro.app.voice.ReadAloudPlayer
import com.vayro.app.model.AgentKind
import com.vayro.app.model.Project
import com.vayro.app.model.ProviderKind
import com.vayro.app.model.agentsForPicker
import com.vayro.app.ui.components.TikTokLinkDialog
import com.vayro.app.ui.components.WebLinkDialog
import com.vayro.app.ui.components.GitHubRepoDialog
import com.vayro.app.ui.components.fetchTikTokInfo
import com.vayro.app.ui.components.fetchWebPageText
import com.vayro.app.ui.theme.AppFontStyle
import com.vayro.app.ui.theme.AppThemeMode
import com.vayro.app.ui.theme.KodexClay
import com.vayro.app.ui.theme.SourceSerifFontFamily
import com.vayro.app.ui.voice.VoiceInputBar
import com.vayro.app.ui.voice.VoiceRecognizer
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Kodex Claude-style shell (v3.2.32).
//
// Presentation-only layer cloned from the Claude Android app: warm palette
// (tokens live in theme/Theme.kt), Source Serif display titles, hairline
// cards, content-sized sheets (~300 ms easeOutExpo family), small centered
// radio popups (150-200 ms). All actions are lambdas wired by
// RootScreenHost / ChatTab to the existing view-model — no backend logic
// lives here.
// ---------------------------------------------------------------------------

internal val ShellEase = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
internal const val VOICE_PREFS = "kodex_voice_settings"
internal const val VOICE_KEY_NAME = "voice_name"
internal const val VOICE_KEY_PACE = "pace_rate"
internal const val VOICE_KEY_SOURCE = "active_voice_source"

@Composable
internal fun SerifText(
    text: String,
    fontSize: Int = 24,
    color: Color = MaterialTheme.colorScheme.onBackground,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        fontFamily = SourceSerifFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = fontSize.sp,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** iOS-style pill switch, ON = Claude blue, as in the reference app. */
@Composable
internal fun ShellSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = KodexClay,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
internal fun ShellLogo(modifier: Modifier = Modifier, size: Int = 56) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 4).dp)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(id = R.drawable.kodex_mark),
            contentDescription = "Kodex",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** One Claude-style settings/nav row inside a card. */
@Composable
internal fun ShellRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(21.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(13.dp))
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = labelColor,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value != null) {
            Text(
                value,
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
        }
        if (onClick != null) {
            Icon(
                Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
internal fun ShellCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
    ) {
        Column { content() }
    }
}

@Composable
internal fun ShellCardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 50.dp),
        thickness = 0.7.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
    )
}

/** Centered rounded radio popup — the Claude Color mode / Font style pattern. */
@Composable
internal fun ShellRadioPopup(
    title: String,
    options: List<Triple<ImageVector?, String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 10.dp)) {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                options.forEach { (icon, label, key) ->
                    val selected = key == selectedKey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(key) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (icon != null) {
                            Icon(
                                icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (selected) KodexClay else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            label,
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) KodexClay else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = "Selected",
                                modifier = Modifier.size(19.dp),
                                tint = KodexClay,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** Small custom popup menu (never the default Android menu). */
@Composable
internal fun ShellPopupMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<Pair<String, () -> Unit>>,
) {
    if (!expanded) return
    // v3.2.37 (Part E): popups fade/scale in from their corner — transform
    // and alpha only, 160 ms, no layout work.
    val popAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        popAnim.animateTo(1f, tween(durationMillis = 160, easing = ShellEase))
    }
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, 52),
        onDismissRequest = onDismiss,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 6.dp,
            modifier = Modifier
                .padding(end = 12.dp)
                .graphicsLayer {
                    alpha = popAnim.value
                    scaleX = 0.96f + 0.04f * popAnim.value
                    scaleY = 0.96f + 0.04f * popAnim.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                },
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                items.forEach { (label, action) ->
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable {
                                onDismiss()
                                action()
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** Creates a content Uri for a camera capture inside the shared cache dir. */
internal fun createCameraImageUri(context: Context): Uri {
    val dir = File(context.cacheDir, "shared/camera").apply { mkdirs() }
    val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

// ---------------------------------------------------------------------------
// "Add to chat" sheet — Claude's structure: X top-left, centered title,
// three big tiles, then analyzer / work-action / project rows. Sized to its
// content only.
// ---------------------------------------------------------------------------

@Composable
private fun AttachTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(7.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AttachRow(icon: ImageVector, label: String, value: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(13.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            Icons.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToChatSheet(
    onDismiss: () -> Unit,
    showWorkActions: Boolean,
    workActionsEnabled: Boolean,
    showConnectors: Boolean,
    showCustomize: Boolean,
    customizeActive: Boolean,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onYouTube: () -> Unit,
    onTikTok: () -> Unit,
    onWebLink: () -> Unit,
    onGitHub: () -> Unit,
    onPushToGitHub: () -> Unit,
    onGitHubBuild: () -> Unit,
    onOpenFactory: () -> Unit,
    onOpenConnectors: () -> Unit,
    onCustomize: () -> Unit,
    onVoiceCall: () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Column(Modifier.padding(bottom = 26.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", modifier = Modifier.size(21.dp))
                }
                Text(
                    "Add to chat",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.weight(1f)) { AttachTile(Icons.Outlined.PhotoCamera, "Camera", onCamera) }
                Box(Modifier.weight(1f)) { AttachTile(Icons.Outlined.PhotoLibrary, "Photos", onPhotos) }
                Box(Modifier.weight(1f)) { AttachTile(Icons.Outlined.Folder, "Files", onFiles) }
            }
            AttachRow(Icons.Outlined.Call, "Voice call", onClick = onVoiceCall)
            AttachRow(Icons.Outlined.SmartDisplay, "YouTube Analyzer", onClick = onYouTube)
            AttachRow(Icons.Outlined.MusicNote, "TikTok Analyzer", onClick = onTikTok)
            AttachRow(Icons.Outlined.Link, "Web link", onClick = onWebLink)
            AttachRow(Icons.Outlined.Code, "GitHub repo", onClick = onGitHub)
            if (showWorkActions) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    thickness = 0.7.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                AttachRow(Icons.Outlined.CloudUpload, "Push to GitHub", onClick = { if (workActionsEnabled) onPushToGitHub() })
                AttachRow(Icons.Outlined.CloudUpload, "Build APK (GitHub)", onClick = onGitHubBuild)
                AttachRow(Icons.Outlined.Factory, "Open in App Factory", onClick = onOpenFactory)
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                thickness = 0.7.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            // v3.2.38 (J): the "Add to project" row was removed — it was a
            // dead tap (dismiss-only; the app has no chat-to-project
            // assignment store). It returns only when that feature exists.
            if (showConnectors) {
                AttachRow(Icons.Outlined.Hub, "Connectors", onClick = onOpenConnectors)
            }
            if (showCustomize) {
                AttachRow(
                    Icons.Outlined.Tune,
                    if (customizeActive) "Customize (on)" else "Customize",
                    onClick = onCustomize,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// "Select agent" sheet — Claude's Select-model pattern: name + one-line
// description per row, check on the current one, "More agents" below.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectAgentSheet(
    selected: AgentKind,
    freeModeEnabled: Boolean,
    onSelect: (AgentKind) -> Unit,
    onMoreAgents: (() -> Unit)?,
    onDismiss: () -> Unit,
    // v3.2.38 (Part P): the workspace sheet also carries the session's
    // provider + model choice in place (empty/null = section hidden, as
    // on the home agent sheet).
    providerChoices: List<MainViewModel.ProviderChoice> = emptyList(),
    onPickProvider: ((ProviderKind) -> Unit)? = null,
    fetchProviderModels: (suspend (ProviderKind) -> List<String>)? = null,
    onPickProviderModel: ((ProviderKind, String) -> Unit)? = null,
    onManageProviders: (() -> Unit)? = null,
) {
    val agents = remember(freeModeEnabled) { agentsForPicker(freeModeEnabled) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Column(Modifier.padding(bottom = 26.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", modifier = Modifier.size(21.dp))
                }
                Text(
                    "Select agent",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            agents.forEach { agent ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(agent) }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(agent.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            agent.subtitle,
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (agent == selected) {
                        Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = KodexClay, modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (onMoreAgents != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    thickness = 0.7.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                AttachRow(Icons.Outlined.SmartToy, "More agents", onClick = onMoreAgents)
            }
            if (providerChoices.isNotEmpty() && onPickProvider != null && onPickProviderModel != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    thickness = 0.7.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    "Provider & model",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                ProviderPickerRows(
                    choices = providerChoices,
                    fetchModels = fetchProviderModels,
                    onPickProvider = onPickProvider,
                    onPickModel = onPickProviderModel,
                )
                if (onManageProviders != null) {
                    AttachRow(Icons.Outlined.Hub, "Add or manage providers", onClick = onManageProviders)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// v3.2.38 (Part P): composer provider/model picker — the pill opens this
// sheet IN PLACE (never a page jump). Rows are the user's CONNECTED
// providers only; picking one switches the answering provider at once
// (home: the F2 "Chat replies use" state; workspace: the session's
// provider), expanding a row fetches its live model list (Part L path),
// and "Add or manage providers" is the only door to the Providers page.
// ---------------------------------------------------------------------------

@Composable
internal fun ProviderPickerRows(
    choices: List<MainViewModel.ProviderChoice>,
    fetchModels: (suspend (ProviderKind) -> List<String>)?,
    onPickProvider: (ProviderKind) -> Unit,
    onPickModel: (ProviderKind, String) -> Unit,
) {
    var expandedKind by remember { mutableStateOf<ProviderKind?>(null) }
    var modelsByKind by remember { mutableStateOf<Map<ProviderKind, List<String>>>(emptyMap()) }
    var failedKinds by remember { mutableStateOf<Set<ProviderKind>>(emptySet()) }
    var loadingKind by remember { mutableStateOf<ProviderKind?>(null) }
    val scope = rememberCoroutineScope()
    choices.forEach { choice ->
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPickProvider(choice.kind) }
                    .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(choice.kind.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "${choice.model} · Connected",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (choice.active) {
                    Icon(Icons.Outlined.Check, contentDescription = "Active", tint = KodexClay, modifier = Modifier.size(20.dp))
                }
                if (fetchModels != null) {
                    IconButton(onClick = {
                        if (expandedKind == choice.kind) {
                            expandedKind = null
                        } else {
                            expandedKind = choice.kind
                            if (choice.kind !in modelsByKind && choice.kind !in failedKinds && loadingKind != choice.kind) {
                                loadingKind = choice.kind
                                scope.launch {
                                    val models = runCatching { fetchModels(choice.kind) }.getOrDefault(emptyList())
                                    loadingKind = null
                                    if (models.isEmpty()) {
                                        failedKinds = failedKinds + choice.kind
                                    } else {
                                        modelsByKind = modelsByKind + (choice.kind to models)
                                    }
                                }
                            }
                        }
                    }) {
                        Icon(
                            if (expandedKind == choice.kind) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "Models",
                            modifier = Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (expandedKind == choice.kind) {
                if (loadingKind == choice.kind) {
                    Row(
                        Modifier.padding(start = 20.dp, top = 2.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ThinkingOrb(Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Loading models…", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    if (choice.kind in failedKinds) {
                        Text(
                            "Could not load the model list — showing the current model.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp, end = 20.dp),
                        )
                    }
                    val shown = (modelsByKind[choice.kind] ?: listOf(choice.model)).distinct()
                    shown.forEach { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPickModel(choice.kind, model) }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                model,
                                fontSize = 13.5.sp,
                                color = if (model == choice.model) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (model == choice.model) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (model == choice.model) {
                                Icon(Icons.Outlined.Check, contentDescription = "Current model", tint = KodexClay, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatModelPickerSheet(
    choices: List<MainViewModel.ProviderChoice>,
    fetchModels: suspend (ProviderKind) -> List<String>,
    onPickProvider: (ProviderKind) -> Unit,
    onPickModel: (ProviderKind, String) -> Unit,
    onManageProviders: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Column(Modifier.padding(bottom = 26.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", modifier = Modifier.size(21.dp))
                }
                Text(
                    "Choose model",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (choices.isEmpty()) {
                Text(
                    "No providers connected yet. Add a key and your providers will appear here.",
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            } else {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    item {
                        ProviderPickerRows(
                            choices = choices,
                            fetchModels = fetchModels,
                            onPickProvider = onPickProvider,
                            onPickModel = onPickModel,
                        )
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                thickness = 0.7.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            AttachRow(Icons.Outlined.Hub, "Add or manage providers", onClick = onManageProviders)
        }
    }
}

// ---------------------------------------------------------------------------
// Chat home — the app opens here: logo + serif greeting in the center, a
// Claude composer card docked at the bottom. Sending (or attaching) creates
// the quick project through the host callbacks and the existing workspace
// takes over.
// ---------------------------------------------------------------------------

// v3.2.38 (Part I): the Chat | Work mode switcher — one shared control for
// the home top bar and the Work (Projects) landing. The active segment is a
// raised surface thumb on a recessed track, shell tokens only. Both segments
// drive the host's single navigation state, so this control, the drawer
// "Work" row and the home handoff card can never disagree about the mode.
@Composable
internal fun ModeSwitcher(
    workSelected: Boolean,
    onSelectChat: () -> Unit,
    onSelectWork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Row(Modifier.padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeSegment(
                label = "Chat",
                selected = !workSelected,
                onClick = onSelectChat,
            )
            ModeSegment(
                label = "Work",
                selected = workSelected,
                onClick = onSelectWork,
            )
        }
    }
}

@Composable
private fun ModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = Modifier.clickable(onClick = onClick),
        ) {
            Text(
                label,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 17.dp, vertical = 6.dp),
            )
        }
    } else {
        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onClick),
        ) {
            Text(
                label,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 17.dp, vertical = 6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Per-message actions — SHARED by the workspace MessageBubble and the home
// thread (v3.2.39, Scope 5). Extracted from the workspace bubble so both
// surfaces get literally the same components and behaviour, never a copy:
// Copy is the small circle at the message's bottom-end (check + toast on
// tap); Listen is the row under an agent message, driven by
// ReadAloudPlayer.playingMessageId so the playing state matches everywhere
// and the user's selected voice (device or ElevenLabs) speaks.
// ---------------------------------------------------------------------------

@Composable
internal fun MessageCopyButton(
    text: String,
    fromUser: Boolean,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable {
                clipboardManager.setText(AnnotatedString(text))
                copied = true
                val label = if (fromUser) "Prompt copied to clipboard" else "Response copied to clipboard"
                Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = if (fromUser) "Copy prompt" else "Copy response",
            tint = if (copied) com.vayro.app.ui.theme.PocketGreen
            else if (fromUser) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
internal fun MessageListenRow(
    messageId: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val listeningThis = com.vayro.app.voice.ReadAloudPlayer.playingMessageId == messageId
    TextButton(
        onClick = {
            com.vayro.app.voice.ReadAloudPlayer.toggle(context, messageId, text)
        },
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (listeningThis) Icons.Default.Stop else Icons.Default.VolumeUp,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (listeningThis) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (listeningThis) "Stop" else "Listen",
                fontSize = 12.sp,
                color = if (listeningThis) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

// v3.2.37 (Part F): one row of the home thread.
@Composable
private fun HomeMessageRow(
    message: com.vayro.app.data.HomeChatMessage,
    movedProjectName: String?,
    onOpenInWork: (String) -> Unit,
) {
    when (message.kind) {
        "system" -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        message.text,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        "work_note" -> {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = KodexClay,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Moved to Work" + (message.projectName.takeIf { it.isNotBlank() }?.let { " \u00B7 $it" } ?: ""),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        "handoff" -> {
            ShellCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(19.dp), tint = KodexClay)
                        Spacer(Modifier.width(9.dp))
                        Text("This is a job for Work", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        message.taskText,
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (movedProjectName != null) {
                        Text(
                            "Moved to Work \u00B7 $movedProjectName",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = KodexClay,
                            modifier = Modifier.clickable { onOpenInWork(message.taskText) },
                        ) {
                            Text(
                                "Open in Work",
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Work has the terminal, files and builds for jobs like this. Chat stays here.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        else -> {
            if (message.fromUser) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 300.dp),
                    ) {
                        // v3.2.39 (Scope 5): the workspace renders user
                        // messages through MarkdownText with the copy circle
                        // at the bottom-end — home now does exactly the same.
                        Box {
                            SelectionContainer {
                                MarkdownText(
                                    markdown = message.text,
                                    modifier = Modifier.padding(start = 14.dp, end = 44.dp, top = 10.dp, bottom = 10.dp),
                                )
                            }
                            MessageCopyButton(
                                text = message.text,
                                fromUser = true,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
            } else {
                // v3.2.39 (Scope 5): home replies used to render as plain
                // Text — raw **bold** asterisks and list markers showed.
                // They now render through the SAME MarkdownText as the
                // workspace (serif, like the workspace's agent messages),
                // with the workspace's link cards and the shared
                // Copy + Listen actions.
                val messageLinks = remember(message.id, message.text) {
                    com.vayro.app.chat.ChatLinkExtractor.extract(message.text).take(3)
                }
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            MarkdownText(
                                markdown = message.text,
                                modifier = Modifier.fillMaxWidth().padding(end = 44.dp),
                                color = MaterialTheme.colorScheme.onBackground,
                                fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily,
                            )
                        }
                        MessageCopyButton(
                            text = message.text,
                            fromUser = false,
                            modifier = Modifier.align(Alignment.BottomEnd),
                        )
                    }
                    if (message.text.isNotBlank()) {
                        MessageListenRow(
                            messageId = message.id,
                            text = message.text,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (messageLinks.isNotEmpty()) {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            messageLinks.forEach { url ->
                                ChatLinkCard(url = url, onOpenLink = null)
                            }
                        }
                    }
                }
            }
        }
    }
}

// v3.2.37 (Part D): the home top-bar work menu — a custom app-styled sheet
// (never the system popup), wired to the same handlers as the "+" sheet
// and the drawer Quick actions.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkActionsSheet(
    onDismiss: () -> Unit,
    onPushToGitHub: () -> Unit,
    onGitHubBuild: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFactory: () -> Unit,
    // v3.2.38 (Part N3): the user's own door to Work — present only when
    // the home thread has messages to carry over.
    onOpenChatInWork: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Column(Modifier.padding(bottom = 26.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", modifier = Modifier.size(21.dp))
                }
                Text(
                    "Work",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (onOpenChatInWork != null) {
                AttachRow(Icons.Outlined.Folder, "Open this chat in Work", onClick = onOpenChatInWork)
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    thickness = 0.7.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            AttachRow(Icons.Outlined.CloudUpload, "Push to GitHub", onClick = onPushToGitHub)
            AttachRow(Icons.Outlined.CloudUpload, "Build APK (GitHub)", onClick = onGitHubBuild)
            AttachRow(Icons.Outlined.Terminal, "Terminal", onClick = onOpenTerminal)
            AttachRow(Icons.Outlined.Factory, "App Factory", onClick = onOpenFactory)
        }
    }
}

@Composable
internal fun ChatHomeScreen(
    agentKind: AgentKind,
    freeModeEnabled: Boolean,
    appUpdateAvailable: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenUpdate: () -> Unit,
    onSend: (String) -> Unit,
    onAttach: (List<Uri>) -> Unit,
    onSelectAgent: (AgentKind) -> Unit,
    onMoreAgents: () -> Unit,
    onOpenConnectors: () -> Unit,
    onPushToGitHub: () -> Unit = {},
    onVoiceCall: () -> Unit = {},
    // v3.2.37 (Parts D/F/F2): home thread + work actions.
    homeMessages: List<com.vayro.app.data.HomeChatMessage> = emptyList(),
    homeSending: Boolean = false,
    homeProviderName: String = "",
    onNewChat: () -> Unit = {},
    onOpenWork: () -> Unit = {},
    onOpenInWork: (String) -> Unit = {},
    // v3.2.38 (Part N3): manual "Open this chat in Work" from the ⋮ sheet.
    onOpenChatInWork: () -> Unit = {},
    // v3.2.38 (Part P): the composer pill's in-place provider/model picker.
    chatChoices: List<MainViewModel.ProviderChoice> = emptyList(),
    onPickChatProvider: (ProviderKind) -> Unit = {},
    onPickChatModel: (ProviderKind, String) -> Unit = { _, _ -> },
    fetchChatModels: suspend (ProviderKind) -> List<String> = { emptyList() },
    onManageProviders: () -> Unit = {},
    onGitHubBuild: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenAppFactory: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by rememberSaveable { mutableStateOf("") }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var workSheetOpen by rememberSaveable { mutableStateOf(false) }
    var agentSheetOpen by rememberSaveable { mutableStateOf(false) }
    var modelPickerOpen by rememberSaveable { mutableStateOf(false) }
    var showYouTubeDialog by rememberSaveable { mutableStateOf(false) }
    var showTikTokDialog by rememberSaveable { mutableStateOf(false) }
    var showWebLinkDialog by rememberSaveable { mutableStateOf(false) }
    var showGitHubDialog by rememberSaveable { mutableStateOf(false) }

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onAttach(uris)
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onAttach(uris)
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri
        cameraUri = null
        if (success && uri != null) onAttach(listOf(uri))
    }

    // Inline voice input (same in-app recognizer as the workspace chat).
    val voiceRecognizer = remember { VoiceRecognizer(context) }
    var voiceInputActive by remember { mutableStateOf(false) }
    DisposableEffect(voiceRecognizer) { onDispose { voiceRecognizer.release() } }
    fun beginVoiceSession() {
        if (!voiceRecognizer.isAvailable()) {
            Toast.makeText(context, "Voice recognition not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }
        voiceInputActive = true
        voiceRecognizer.start()
    }
    val recordAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginVoiceSession()
        else Toast.makeText(context, "Microphone permission is required for voice input", Toast.LENGTH_SHORT).show()
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

    if (showYouTubeDialog) {
        com.vayro.app.ui.components.YouTubeVideoDialog(
            initialUrl = if (com.vayro.app.util.YouTubeUrlValidator.isValid(prompt)) prompt.trim() else "",
            onDismiss = { showYouTubeDialog = false },
            onAnalyze = { videoInfo, userQuery, focusMode ->
                val videoPrompt = com.vayro.app.util.YouTubeUrlValidator.buildGeminiVideoPrompt(
                    info = videoInfo,
                    userQuery = userQuery,
                    focusMode = focusMode,
                )
                showYouTubeDialog = false
                prompt = ""
                onSend(videoPrompt)
            },
        )
    }
    if (showTikTokDialog) {
        TikTokLinkDialog(
            onDismiss = { showTikTokDialog = false },
            onAnalyze = { url, question ->
                showTikTokDialog = false
                scope.launch {
                    val info = fetchTikTokInfo(url)
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
                    onSend(message.trim())
                }
            },
        )
    }
    if (showWebLinkDialog) {
        WebLinkDialog(
            onDismiss = { showWebLinkDialog = false },
            onFetch = { url ->
                showWebLinkDialog = false
                scope.launch {
                    val page = fetchWebPageText(url)
                    if (page == null) {
                        Toast.makeText(context, "Could not fetch that page.", Toast.LENGTH_SHORT).show()
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
                        )
                    }
                }
            },
        )
    }
    if (showGitHubDialog) {
        GitHubRepoDialog(
            onDismiss = { showGitHubDialog = false },
            onInspect = { url ->
                showGitHubDialog = false
                onSend("Clone this GitHub repository into the workspace, inspect its structure (file tree, README, manifest/build files, entry points), and summarize what it does, its main features, and how it is built: $url")
            },
        )
    }
    if (workSheetOpen) {
        WorkActionsSheet(
            onDismiss = { workSheetOpen = false },
            onPushToGitHub = { workSheetOpen = false; onPushToGitHub() },
            onGitHubBuild = { workSheetOpen = false; onGitHubBuild() },
            onOpenTerminal = { workSheetOpen = false; onOpenTerminal() },
            onOpenFactory = { workSheetOpen = false; onOpenAppFactory() },
            onOpenChatInWork = if (homeMessages.isNotEmpty()) {
                { workSheetOpen = false; onOpenChatInWork() }
            } else {
                null
            },
        )
    }
    if (agentSheetOpen) {
        SelectAgentSheet(
            selected = agentKind,
            freeModeEnabled = freeModeEnabled,
            onSelect = { kind ->
                agentSheetOpen = false
                onSelectAgent(kind)
            },
            onMoreAgents = {
                agentSheetOpen = false
                onMoreAgents()
            },
            onDismiss = { agentSheetOpen = false },
        )
    }
    if (modelPickerOpen) {
        ChatModelPickerSheet(
            choices = chatChoices,
            fetchModels = fetchChatModels,
            onPickProvider = { kind ->
                modelPickerOpen = false
                onPickChatProvider(kind)
            },
            onPickModel = { kind, model ->
                modelPickerOpen = false
                onPickChatModel(kind, model)
            },
            onManageProviders = {
                modelPickerOpen = false
                onManageProviders()
            },
            onDismiss = { modelPickerOpen = false },
        )
    }
    if (sheetOpen) {
        AddToChatSheet(
            onDismiss = { sheetOpen = false },
            showWorkActions = false,
            workActionsEnabled = false,
            showConnectors = true,
            showCustomize = false,
            customizeActive = false,
            onCamera = {
                sheetOpen = false
                val uri = createCameraImageUri(context)
                cameraUri = uri
                cameraLauncher.launch(uri)
            },
            onPhotos = {
                sheetOpen = false
                photoLauncher.launch(arrayOf("image/*", "video/*"))
            },
            onFiles = {
                sheetOpen = false
                fileLauncher.launch(arrayOf("*/*"))
            },
            onYouTube = {
                sheetOpen = false
                showYouTubeDialog = true
            },
            onTikTok = {
                sheetOpen = false
                showTikTokDialog = true
            },
            onWebLink = {
                sheetOpen = false
                showWebLinkDialog = true
            },
            onGitHub = {
                sheetOpen = false
                showGitHubDialog = true
            },
            onPushToGitHub = onPushToGitHub,
            onGitHubBuild = {},
            onOpenFactory = {},
            onOpenConnectors = {
                sheetOpen = false
                onOpenConnectors()
            },
            onCustomize = {},
            onVoiceCall = {
                sheetOpen = false
                onVoiceCall()
            },
        )
    }

    // v3.2.38 (Part I): a committed leftward swipe on the chat surface
    // switches to Work — the same destination as the top switcher. The
    // detector only claims horizontal-dominant drags once they pass touch
    // slop (so vertical list scrolling is never stolen) and fires once, on
    // release, past a high threshold — casual scrolls cannot trigger it. It
    // is attached to the hero and the thread list only, never over the
    // composer, whose own drag handling stays private.
    val modeSwipeThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    val swipeToWorkModifier = Modifier.pointerInput(modeSwipeThresholdPx) {
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onDragEnd = {
                if (totalDrag < -modeSwipeThresholdPx) onOpenWork()
                totalDrag = 0f
            },
            onDragCancel = { totalDrag = 0f },
            onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
        )
    }
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Coffee and Kodex time?"
        }
    }
    val canSend = prompt.isNotBlank()
    val hasThread = homeMessages.isNotEmpty()
    // v3.2.38 (Part I): thread scroll survives leaving to Work and back,
    // like every other list in the shell (U8b pattern).
    val threadListState = rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
        androidx.compose.foundation.lazy.LazyListState()
    }
    LaunchedEffect(homeMessages.size, homeSending) {
        if (hasThread) {
            val last = (homeMessages.size - 1 + if (homeSending) 1 else 0).coerceAtLeast(0)
            threadListState.animateScrollToItem(last)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // v3.2.37 (Part D) + v3.2.38 (Part I): polished top bar — custom menu
        // chip on the left; the Chat | Work mode switcher centered; New Chat
        // + work menu (custom sheet, never the system popup) and the update
        // bell on the right. Status-bar inset preserved. The centered box is
        // weight-based, so the switcher (~140dp) keeps clear of both clusters
        // even on a 360dp screen with the bell visible.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 1.dp,
                modifier = Modifier.size(38.dp).clickable(onClick = onOpenDrawer),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            Modifier.width(17.dp).height(2.dp)
                                .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(1.dp)),
                        )
                        Box(
                            Modifier.width(11.dp).height(2.dp)
                                .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(1.dp)),
                        )
                    }
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ModeSwitcher(
                    workSelected = false,
                    onSelectChat = {},
                    onSelectWork = onOpenWork,
                )
            }
            if (appUpdateAvailable) {
                IconButton(onClick = onOpenUpdate) {
                    Box {
                        Icon(Icons.Outlined.Notifications, contentDescription = "Update available", modifier = Modifier.size(22.dp))
                        Box(
                            Modifier
                                .size(8.dp)
                                .align(Alignment.TopEnd)
                                .background(KodexClay, CircleShape),
                        )
                    }
                }
            }
            IconButton(onClick = onNewChat) {
                Icon(Icons.Outlined.Create, contentDescription = "New chat", modifier = Modifier.size(21.dp))
            }
            IconButton(onClick = { workSheetOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Work actions", modifier = Modifier.size(22.dp))
            }
        }
        if (!hasThread) {
            Box(
                Modifier.weight(1f).fillMaxWidth().then(swipeToWorkModifier),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ShellLogo(size = 60)
                    Spacer(Modifier.height(18.dp))
                    SerifText(greeting, fontSize = 28)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Ask me anything — projects and builds live in Work.",
                        fontSize = 14.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                state = threadListState,
                modifier = Modifier.weight(1f).fillMaxWidth().then(swipeToWorkModifier).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
            ) {
                items(homeMessages.size) { index ->
                    val message = homeMessages[index]
                    HomeMessageRow(
                        message = message,
                        movedProjectName = homeMessages.firstOrNull {
                            it.kind == "work_note" && it.taskText == message.taskText &&
                                it.createdAtMillis >= message.createdAtMillis
                        }?.projectName,
                        onOpenInWork = onOpenInWork,
                    )
                }
                if (homeSending) {
                    item {
                        // v3.2.38 (Part O): chat waits use the SAME Canvas
                        // ThinkingOrb as Work mode — no plain spinner here.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThinkingOrb(Modifier.size(17.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(
                                "Thinking…",
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (voiceInputActive) {
                VoiceInputBar(
                    recognizer = voiceRecognizer,
                    onCancel = { voiceInputActive = false },
                    onStop = { spoken ->
                        if (spoken.isNotBlank()) {
                            prompt = if (prompt.isBlank()) spoken else "$prompt $spoken"
                        }
                        voiceInputActive = false
                    },
                    onSend = { spoken ->
                        val merged = listOf(prompt, spoken).filter { it.isNotBlank() }.joinToString(" ").trim()
                        voiceInputActive = false
                        if (merged.isNotBlank()) {
                            prompt = ""
                            onSend(merged)
                        }
                    },
                    onError = { message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        voiceInputActive = false
                    },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        BasicTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .heightIn(min = 26.dp, max = 130.dp),
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.5.sp,
                                lineHeight = 21.sp,
                            ),
                            cursorBrush = SolidColor(KodexClay),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (prompt.isEmpty()) {
                                        Text(
                                            "Message Kodex…",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 15.5.sp,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { sheetOpen = true }, modifier = Modifier.size(38.dp)) {
                                Icon(Icons.Outlined.Add, contentDescription = "Add to chat", modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            // v3.2.37 (F2) + v3.2.38 (Part P): the pill
                            // names the provider + model that answer HOME
                            // chat; tapping opens the in-place picker sheet
                            // (same F2 state — no page jump). The label is
                            // width-capped and ellipsized so it can never
                            // push the mic/send buttons off a narrow screen.
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.clickable { modelPickerOpen = true },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        homeProviderName.ifBlank { "Connect a key" },
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 150.dp),
                                    )
                                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = onStartVoiceInput, modifier = Modifier.size(38.dp)) {
                                Icon(Icons.Outlined.Mic, contentDescription = "Voice input", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (canSend) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(KodexClay, CircleShape)
                                        .clickable {
                                            val text = prompt
                                            prompt = ""
                                            onSend(text)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Outlined.ArrowUpward, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(19.dp))
                                }
                            } else {
                                // Waveform button = voice call (unchanged
                                // behavior); the host now presents the call
                                // over THIS home thread (Part F2 routing).
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(KodexClay, CircleShape)
                                        .clickable(onClick = onVoiceCall),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Filled.GraphicEq, contentDescription = "Voice call", tint = Color.White, modifier = Modifier.size(19.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sidebar — Claude's pattern: serif app name, quick actions and nav rows,
// Pinned + Recents, and a bottom bar with the profile avatar (→ Settings)
// and a white "+ New chat" pill. Slides in ~300 ms; closes by scrim tap,
// Back, or swipe.
// ---------------------------------------------------------------------------

@Composable
private fun SidebarRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SidebarSectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun KodexSidebar(
    visible: Boolean,
    progressAnim: Animatable<Float, AnimationVector1D>,
    onOpenWork: () -> Unit = {},
    userInitial: String,
    userName: String,
    projects: List<Project>,
    pinnedIds: Set<String>,
    activeProjectId: String?,
    pushEnabled: Boolean,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenCode: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMarketplace: () -> Unit,
    onOpenConnectors: () -> Unit,
    onOpenAppFactory: () -> Unit,
    onOpenProject: (Project) -> Unit,
    onTogglePin: (Project) -> Unit,
    onRenameProject: (Project, String) -> Unit,
    onDeleteProject: (Project) -> Unit,
    onPushToGitHub: () -> Unit,
    onOpenGitHubBuild: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // v3.2.37 (Part E): the drawer stays COMPOSED after its first mount
    // (the host pre-warms it), and only transform/alpha animate — driven
    // by the host-owned Animatable so the content behind can scale/dim in
    // the same draw pass. No layout offsets, no first-frame composition
    // racing the slide (the old stutter).
    LaunchedEffect(visible) {
        progressAnim.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(durationMillis = 300, easing = ShellEase),
        )
    }
    fun requestClose() = onClose()
    BackHandler(enabled = visible, onBack = { requestClose() })

    var menuProject by remember { mutableStateOf<Project?>(null) }
    var renameProject by remember { mutableStateOf<Project?>(null) }
    var deleteProject by remember { mutableStateOf<Project?>(null) }

    menuProject?.let { project ->
        ModalBottomSheet(
            onDismissRequest = { menuProject = null },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = null,
        ) {
            Column(Modifier.padding(bottom = 26.dp, top = 8.dp)) {
                Text(
                    project.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AttachRow(
                    Icons.Outlined.PushPin,
                    if (project.id in pinnedIds) "Unpin" else "Pin",
                    onClick = {
                        menuProject = null
                        onTogglePin(project)
                    },
                )
                AttachRow(
                    Icons.Outlined.Edit,
                    "Rename",
                    onClick = {
                        menuProject = null
                        renameProject = project
                    },
                )
                AttachRow(
                    Icons.Outlined.Delete,
                    "Delete",
                    onClick = {
                        menuProject = null
                        deleteProject = project
                    },
                )
            }
        }
    }
    renameProject?.let { project ->
        var newName by rememberSaveable(project.id) { mutableStateOf(project.name) }
        Dialog(onDismissRequest = { renameProject = null }) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                Column(Modifier.padding(20.dp)) {
                    Text("Rename chat", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { renameProject = null }) { Text("Cancel") }
                        Button(
                            onClick = {
                                if (newName.isNotBlank()) onRenameProject(project, newName.trim())
                                renameProject = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = KodexClay),
                        ) { Text("Save", color = Color.White) }
                    }
                }
            }
        }
    }
    deleteProject?.let { project ->
        Dialog(onDismissRequest = { deleteProject = null }) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                Column(Modifier.padding(20.dp)) {
                    Text("Delete this chat?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "\"${project.name}\" and its workspace will be permanently deleted.",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { deleteProject = null }) { Text("Cancel") }
                        Button(
                            onClick = {
                                deleteProject = null
                                onDeleteProject(project)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("Delete", color = Color.White) }
                    }
                }
            }
        }
    }

    val pinned = remember(projects, pinnedIds) { projects.filter { it.id in pinnedIds } }
    val recents = remember(projects) { projects.sortedByDescending { it.updatedAtMillis }.take(8) }

    val progress = progressAnim.value
    val panelWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { 336.dp.toPx() }
    Box(if (!visible && progress == 0f) Modifier.size(0.dp) else Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.5f * progress }
                .background(Color.Black)
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onClick = { if (visible) requestClose() },
                ),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(320.dp)
                .graphicsLayer { translationX = -panelWidthPx * (1f - progress) }
                .pointerInput(Unit) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragEnd = { totalDrag = 0f },
                        onDragCancel = { totalDrag = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            totalDrag += dragAmount
                            if (totalDrag < -90f) requestClose()
                        },
                    )
                },
            shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)
                        .statusBarsPadding(),
                ) {
                    Spacer(Modifier.height(14.dp))
                    SerifText("Kodex", fontSize = 30, modifier = Modifier.padding(start = 10.dp))
                    Spacer(Modifier.height(10.dp))
                    SidebarSectionLabel("Quick actions")
                    SidebarRow(Icons.Outlined.CloudUpload, "Push to GitHub") {
                        if (pushEnabled) {
                            onClose()
                            onPushToGitHub()
                        }
                    }
                    SidebarRow(Icons.Outlined.CloudUpload, "GitHub Build") {
                        onClose()
                        onOpenGitHubBuild()
                    }
                    SidebarRow(Icons.Outlined.Terminal, "Terminal") {
                        onClose()
                        onOpenTerminal()
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        thickness = 0.7.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    SidebarRow(Icons.Outlined.Code, "Work") {
                        onClose()
                        onOpenWork()
                    }
                    SidebarRow(Icons.Outlined.ChatBubbleOutline, "Chats") {
                        onClose()
                        onOpenChats()
                    }
                    SidebarRow(Icons.Outlined.Folder, "Projects") {
                        onClose()
                        onOpenProjects()
                    }
                    SidebarRow(Icons.Outlined.Code, "Code") {
                        onClose()
                        onOpenCode()
                    }
                    SidebarRow(Icons.Outlined.SmartToy, "Providers & Agents") {
                        onClose()
                        onOpenProviders()
                    }
                    SidebarRow(Icons.Outlined.Extension, "Skills") {
                        onClose()
                        onOpenSkills()
                    }
                    SidebarRow(Icons.Outlined.Storefront, "Marketplace") {
                        onClose()
                        onOpenMarketplace()
                    }
                    SidebarRow(Icons.Outlined.Hub, "Connectors") {
                        onClose()
                        onOpenConnectors()
                    }
                    SidebarRow(Icons.Outlined.Factory, "App Factory") {
                        onClose()
                        onOpenAppFactory()
                    }
                    if (pinned.isNotEmpty()) {
                        SidebarSectionLabel("Pinned")
                        pinned.forEach { project ->
                            SidebarChatRow(
                                project = project,
                                active = project.id == activeProjectId,
                                onClick = {
                                    onClose()
                                    onOpenProject(project)
                                },
                                onLongClick = { menuProject = project },
                                onMenu = { menuProject = project },
                            )
                        }
                    }
                    if (recents.isNotEmpty()) {
                        SidebarSectionLabel("Recents")
                        recents.forEach { project ->
                            SidebarChatRow(
                                project = project,
                                active = project.id == activeProjectId,
                                onClick = {
                                    onClose()
                                    onOpenProject(project)
                                },
                                onLongClick = { menuProject = project },
                                onMenu = { menuProject = project },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onClose()
                                onOpenChats()
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "All chats",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(10.dp))
                }
                HorizontalDivider(thickness = 0.7.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(KodexClay, CircleShape)
                            .clickable { onOpenSettings() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(userInitial, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        userName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpenSettings() },
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.onBackground,
                        shadowElevation = 3.dp,
                        modifier = Modifier.clickable {
                            onClose()
                            onNewChat()
                        },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.background)
                            Spacer(Modifier.width(5.dp))
                            Text("New chat", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.background)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarChatRow(
    project: Project,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.ChatBubbleOutline,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(11.dp))
        Text(
            project.name,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onMenu, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Outlined.MoreHoriz, contentDescription = "Chat options", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------
// Chats screen — Claude's pattern: centered serif title, search field,
// chat rows with a right-aligned relative date, a floating "+ New chat"
// pill, and long-press multi-select with Delete.
// (In Kodex a top-level "chat" is a project workspace — quick chats and
// projects are listed together, labelled by kind.)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatsScreen(
    // v3.2.39 (Scope 2): hoisted list + filter state (host-owned).
    listState: LazyListState = rememberSaveable(saver = LazyListState.Saver, key = "chats-list-state") { LazyListState() },
    queryState: androidx.compose.runtime.MutableState<String> = rememberSaveable { mutableStateOf("") },
    filterPinnedState: androidx.compose.runtime.MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },
    projects: List<Project>,
    pinnedIds: Set<String>,
    onOpenDrawer: () -> Unit,
    onOpenProject: (Project) -> Unit,
    onNewChat: () -> Unit,
    onDeleteProjects: (List<String>) -> Unit,
    // v3.2.37 (Part F): home (normal chat) threads are listed among chats,
    // above the Work projects they are deliberately separate from.
    homeThreads: List<com.vayro.app.data.HomeThread> = emptyList(),
    onOpenHomeThread: (com.vayro.app.data.HomeThread) -> Unit = {},
    onDeleteHomeThread: (com.vayro.app.data.HomeThread) -> Unit = {},
) {
    var query by queryState
    var filterPinned by filterPinnedState
    var filterMenuOpen by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // v3.2.38 (J): home-thread delete asks first, like project delete does.
    var threadToDelete by remember { mutableStateOf<com.vayro.app.data.HomeThread?>(null) }
    val inSelection = selection.isNotEmpty()

    val visible = remember(projects, query, filterPinned, pinnedIds) {
        projects
            .filter { !filterPinned || it.id in pinnedIds }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .sortedByDescending { it.updatedAtMillis }
    }

    if (threadToDelete != null) {
        val target = threadToDelete!!
        Dialog(onDismissRequest = { threadToDelete = null }) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                Column(Modifier.padding(20.dp)) {
                    Text("Delete this chat?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "\"${target.title}\" and its messages will be permanently deleted.",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { threadToDelete = null }) { Text("Cancel") }
                        Button(
                            onClick = {
                                threadToDelete = null
                                onDeleteHomeThread(target)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("Delete", color = Color.White) }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        Dialog(onDismissRequest = { confirmDelete = false }) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                Column(Modifier.padding(20.dp)) {
                    Text("Delete ${selection.size} chat${if (selection.size == 1) "" else "s"}?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The selected chats and their workspaces will be permanently deleted.",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                        Button(
                            onClick = {
                                val ids = selection.toList()
                                selection = emptySet()
                                confirmDelete = false
                                onDeleteProjects(ids)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("Delete", color = Color.White) }
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 6.dp),
            ) {
                if (inSelection) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Cancel selection", modifier = Modifier.size(21.dp))
                        }
                        Text("${selection.size} selected", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete selected", modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    IconButton(onClick = onOpenDrawer, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Menu", modifier = Modifier.size(23.dp))
                    }
                    SerifText("Chats", fontSize = 21, modifier = Modifier.align(Alignment.Center))
                    IconButton(onClick = { filterMenuOpen = true }, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Filter", modifier = Modifier.size(21.dp))
                    }
                    ShellPopupMenu(
                        expanded = filterMenuOpen,
                        onDismiss = { filterMenuOpen = false },
                        items = listOf(
                            "All chats" to { filterPinned = false },
                            "Pinned" to { filterPinned = true },
                        ),
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.5.sp),
                        cursorBrush = SolidColor(KodexClay),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text("Search", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.5.sp)
                                }
                                inner()
                            }
                        },
                    )
                }
            }
            if (visible.isEmpty() && homeThreads.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isNotBlank()) "No chats match your search." else "No chats yet. Start a new chat to begin.",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    if (query.isBlank() && homeThreads.isNotEmpty()) {
                        item {
                            Text(
                                "Home chats",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        items(homeThreads, key = { "home-" + it.id }) { thread ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onOpenHomeThread(thread) }
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.ChatBubbleOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = KodexClay,
                                )
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        thread.title,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        thread.messages.lastOrNull { it.kind == "text" }?.text
                                            ?: "Empty chat",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { threadToDelete = thread }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "Delete chat",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        item {
                            Text(
                                "Work projects",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                    items(visible, key = { it.id }) { project ->
                        val selected = project.id in selection
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                .combinedClickable(
                                    onClick = {
                                        if (inSelection) {
                                            selection = if (selected) selection - project.id else selection + project.id
                                        } else {
                                            onOpenProject(project)
                                        }
                                    },
                                    onLongClick = {
                                        selection = selection + project.id
                                    },
                                )
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (inSelection) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .background(if (selected) KodexClay else Color.Transparent, CircleShape)
                                        .border(1.5.dp, if (selected) KodexClay else MaterialTheme.colorScheme.outline, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) {
                                        Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }
                                Spacer(Modifier.width(11.dp))
                            } else {
                                Icon(
                                    Icons.Outlined.ChatBubbleOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(11.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    project.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (project.id in pinnedIds) "Pinned" else if (project.kind.name == "QUICK_PROJECT") "Chat" else "Project",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                project.formattedUpdatedAt,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.onBackground,
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(18.dp)
                .clickable(onClick = onNewChat),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.background)
                Spacer(Modifier.width(5.dp))
                Text("New chat", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.background)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Code hub — one clean home for the workspace tools that used to be
// scattered: terminal, GitHub build/studio, project files & editor, and
// Team Studio.
// ---------------------------------------------------------------------------

@Composable
internal fun CodeHubScreen(
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenGitHubBuild: () -> Unit,
    onOpenGitHubStudio: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenTeamStudio: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(21.dp))
            }
            SerifText("Code", fontSize = 21)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ShellCard {
                ShellRow(Icons.Outlined.Terminal, "Terminal", onClick = onOpenTerminal)
                ShellCardDivider()
                ShellRow(Icons.Outlined.CloudUpload, "GitHub Build", onClick = onOpenGitHubBuild)
                ShellCardDivider()
                ShellRow(Icons.Outlined.Code, "GitHub Studio", onClick = onOpenGitHubStudio)
                ShellCardDivider()
                ShellRow(Icons.Outlined.Folder, "Project files & editor", onClick = onOpenProjects)
                ShellCardDivider()
                ShellRow(Icons.Outlined.SmartToy, "Team Studio", onClick = onOpenTeamStudio)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Files, the code editor and the browser preview live inside each project — open a project to use them. The terminal, GitHub Build and GitHub Studio work from here directly.",
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Settings home — Claude's layout: account card, license card in the
// upgrade-card position, then grouped rows. Every row opens its own page
// (the full existing settings live under "Developer tools" / the mapped
// rows, so nothing real is dropped).
// ---------------------------------------------------------------------------

@Composable
internal fun ClaudeSettingsHome(
    // v3.2.39 (Scope 2): hoisted by the host so the scroll position
    // survives every sub-page round trip (About → Back jumped to the top).
    listState: LazyListState = rememberSaveable(saver = LazyListState.Saver, key = "settings-home-list-state") { LazyListState() },
    accountName: String,
    accountEmail: String?,
    isGuest: Boolean,
    colorMode: AppThemeMode,
    fontStyle: AppFontStyle,
    hapticsEnabled: Boolean,
    connectorsValue: String,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenLicense: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onOpenConnectors: () -> Unit,
    onOpenPermissions: () -> Unit,
    onColorMode: (AppThemeMode) -> Unit,
    onFontStyle: (AppFontStyle) -> Unit,
    onOpenVoice: () -> Unit,
    onToggleHaptics: (Boolean) -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenDeveloperTools: () -> Unit,
    onOpenRuntime: () -> Unit = {},
    onOpenEnvironment: () -> Unit = {},
    onOpenDeveloper: () -> Unit = {},
    onOpenPrivacy: () -> Unit,
    onOpenAbout: () -> Unit,
    onLogout: () -> Unit,
    appUpdateAvailable: Boolean = false,
    currentVersion: String = "",
    onOpenAppUpdates: () -> Unit = {},
) {
    val context = LocalContext.current
    var showColorPopup by remember { mutableStateOf(false) }
    var showFontPopup by remember { mutableStateOf(false) }
    val licStore = remember { com.vayro.app.license.LicenseStore(context) }
    val licensed = licStore.token != null
    val licenseText = when {
        licStore.token == null -> "Not activated on this phone"
        licStore.expiresAt > 0 -> "Active · until " +
            java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date(licStore.expiresAt))
        else -> "Active · Lifetime · this phone is licensed"
    }
    val initial = (accountName.firstOrNull()?.uppercaseChar() ?: 'K').toString()

    if (showColorPopup) {
        ShellRadioPopup(
            title = "Color mode",
            options = listOf(
                Triple(null, "System", "SYSTEM"),
                Triple(null, "Light", "LIGHT"),
                Triple(null, "Dark", "DARK"),
            ),
            selectedKey = colorMode.name,
            onSelect = { key ->
                showColorPopup = false
                onColorMode(AppThemeMode.valueOf(key))
            },
            onDismiss = { showColorPopup = false },
        )
    }
    if (showFontPopup) {
        ShellRadioPopup(
            title = "Font style",
            options = listOf(
                Triple(null, "Default", "DEFAULT"),
                Triple(null, "System", "SYSTEM"),
            ),
            selectedKey = fontStyle.name,
            onSelect = { key ->
                showFontPopup = false
                onFontStyle(AppFontStyle.valueOf(key))
            },
            onDismiss = { showFontPopup = false },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 6.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(21.dp))
            }
            SerifText("Settings", fontSize = 21, modifier = Modifier.align(Alignment.Center))
        }
        // U8b + v3.2.39 (Scope 2): the list state is the HOST's — Back from
        // a settings page returns to the exact scroll position, not the top.
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ShellCard {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(44.dp).background(KodexClay, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(initial, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(accountName, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                accountEmail ?: if (isGuest) "Guest mode · Local only" else "Signed in",
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Text(
                                if (licensed) "Licensed" else "Free",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            item {
                ShellCard {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(20.dp), tint = KodexClay)
                            Spacer(Modifier.width(10.dp))
                            Text("Kodex License", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(licenseText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onOpenLicense,
                            colors = ButtonDefaults.buttonColors(containerColor = KodexClay),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Manage", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item {
                ShellCard {
                    ShellRow(Icons.Outlined.Person, "Profile", onClick = onOpenProfile)
                    ShellCardDivider()
                    ShellRow(Icons.Outlined.Lock, "License", value = if (licensed) "Active" else "Not activated", onClick = onOpenLicense)
                }
            }
            item {
                ShellCard {
                    ShellRow(Icons.Outlined.Tune, "Capabilities", onClick = onOpenCapabilities)
                    ShellCardDivider()
                    ShellRow(Icons.Outlined.Hub, "Connectors", value = connectorsValue, onClick = onOpenConnectors)
                    ShellCardDivider()
                    ShellRow(Icons.Outlined.Lock, "Permissions", onClick = onOpenPermissions)
                }
            }
            item {
                ShellCard {
                    ShellRow(
                        Icons.Outlined.Settings,
                        "Color mode",
                        value = when (colorMode) {
                            AppThemeMode.SYSTEM -> "System"
                            AppThemeMode.LIGHT -> "Light"
                            AppThemeMode.DARK -> "Dark"
                        },
                        onClick = { showColorPopup = true },
                    )
                    ShellCardDivider()
                    ShellRow(
                        Icons.Outlined.Edit,
                        "Font style",
                        value = if (fontStyle == AppFontStyle.DEFAULT) "Default" else "System",
                        onClick = { showFontPopup = true },
                    )
                    ShellCardDivider()
                    ShellRow(Icons.Outlined.RecordVoiceOver, "Voice", onClick = onOpenVoice)
                    ShellCardDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Notifications, contentDescription = null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(13.dp))
                        Text("Haptic feedback", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        ShellSwitch(checked = hapticsEnabled, onCheckedChange = onToggleHaptics)
                    }
                }
            }
            item {
                ShellCard {
                    ShellRow(Icons.Outlined.Notifications, "Notifications", onClick = onOpenNotifications)
                    ShellCardDivider()
                    ShellRow(
                        Icons.Outlined.SystemUpdate,
                        "App updates",
                        value = if (appUpdateAvailable) "Update available" else "v$currentVersion",
                        onClick = onOpenAppUpdates,
                    )
                }
            }
            // v3.2.38 (Part K): every function group that used to hide in
            // the classic monolith has its own row and its own page now —
            // there is no "All settings" escape hatch anymore.
            item {
                ShellCard {
                    ShellRow(Icons.Outlined.Code, "Developer tools", onClick = onOpenDeveloperTools)
                    ShellCardDivider()
                    ShellRow(Icons.Outlined.Terminal, "Linux runtime", onClick = onOpenRuntime)
                    ShellCardDivider()
                    ShellRow(Icons.Outlined.Refresh, "Environment updates", onClick = onOpenEnvironment)
                    ShellCardDivider()
                    ShellRow(Icons.Outlined.Person, "Developer", value = "The person behind Kodex", onClick = onOpenDeveloper)
                    ShellCardDivider()
                    ShellRow(Icons.Outlined.Lock, "Privacy", onClick = onOpenPrivacy)
                    ShellCardDivider()
                    ShellRow(Icons.Outlined.Info, "About", value = "v${BuildConfig.VERSION_NAME}", onClick = onOpenAbout)
                }
            }
            item {
                ShellCard {
                    ShellRow(
                        Icons.AutoMirrored.Filled.Logout,
                        if (isGuest) "Sign in" else "Log out",
                        labelColor = MaterialTheme.colorScheme.error,
                        onClick = if (isGuest) onLogin else onLogout,
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Capabilities — Claude's toggles page: each switch row carries a one-line
// description. Only real, working settings live here.
// ---------------------------------------------------------------------------

@Composable
internal fun CapabilitiesScreen(
    onBack: () -> Unit,
    autoUpdateSkills: Boolean,
    onToggleAutoUpdateSkills: (Boolean) -> Unit,
    freeMode: Boolean,
    onToggleFreeMode: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val componentPrefs = remember {
        context.getSharedPreferences(com.vayro.app.update.ComponentUpdateNotifier.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var autoUpdateComponents by remember {
        mutableStateOf(componentPrefs.getBoolean(com.vayro.app.update.ComponentUpdateNotifier.KEY_AUTO_UPDATE, false))
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(21.dp))
            }
            SerifText("Capabilities", fontSize = 21)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ShellCard {
                CapabilityToggleRow(
                    title = "Auto-update skills",
                    description = "Skills installed from GitHub or the Marketplace check for new versions in the background and update themselves.",
                    checked = autoUpdateSkills,
                    onCheckedChange = onToggleAutoUpdateSkills,
                )
                ShellCardDivider()
                CapabilityToggleRow(
                    title = "Auto-update components",
                    description = "Runtime components (Python, Android SDK, toolchains) install updates in the background — with backup and automatic restore if anything fails.",
                    checked = autoUpdateComponents,
                    onCheckedChange = { enabled ->
                        autoUpdateComponents = enabled
                        componentPrefs.edit()
                            .putBoolean(com.vayro.app.update.ComponentUpdateNotifier.KEY_AUTO_UPDATE, enabled)
                            .apply()
                    },
                )
                ShellCardDivider()
                CapabilityToggleRow(
                    title = "Free Mode",
                    description = "Show only free agents and free-tier API providers. Paid options stay hidden while Free Mode is on.",
                    checked = freeMode,
                    onCheckedChange = onToggleFreeMode,
                )
            }
            Text(
                "Agents themselves (Claude Code, Antigravity, DeepSeek) update from the Providers & Agents screen, not from here.",
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun CapabilityToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(description, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        ShellSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---------------------------------------------------------------------------
// Voice settings — Claude's pattern: a carousel of large voice cards
// (built from the best on-device TTS voices, up to 6, with friendly names),
// Language + Pace rows below, and the ElevenLabs premium card. This screen
// configures the same Listen feature: the choice is stored in
// "kodex_voice_settings" and ReadAloudPlayer applies it on every reply.
// ---------------------------------------------------------------------------

private val VoiceStyleNames = listOf("Rounded", "Glassy", "Mellow", "Airy", "Buttery", "Clear")

@Composable
internal fun VoiceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE) }
    var allVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                allVoices = engine.voices?.toList().orEmpty()
                ttsReady = true
            }
        }
        engine
    }
    DisposableEffect(tts) {
        onDispose {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Do not leave a voice TEST playing after leaving the screen;
            // a Listen started elsewhere is not ours to stop.
            if (ReadAloudPlayer.playingMessageId == "kodex-voice-test") ReadAloudPlayer.stop()
        }
    }

    var selectedVoiceName by remember { mutableStateOf(prefs.getString(VOICE_KEY_NAME, null)) }
    var pace by remember { mutableStateOf(prefs.getFloat(VOICE_KEY_PACE, 1.0f)) }
    // v3.2.34 (U5): ONE explicit "Active voice" selection. The persisted
    // source (device | elevenlabs) is what ReadAloudPlayer honors for
    // Listen and Voice Call; the device cards and the ElevenLabs card in
    // the row below are peers — tapping any of them makes it active.
    val vault = remember { ApiKeyVault(context) }
    var elevenLabsHasKey by remember { mutableStateOf(!vault.get("elevenlabs").isNullOrBlank()) }
    var elevenLabsVoiceId by remember { mutableStateOf(vault.get("elevenlabs_voice")?.takeIf { it.isNotBlank() }) }
    val elevenLabsConnected = elevenLabsHasKey && elevenLabsVoiceId != null
    var activeSource by remember { mutableStateOf(prefs.getString(VOICE_KEY_SOURCE, null)) }
    val elevenLabsActive = activeSource == "elevenlabs" || (activeSource == null && elevenLabsConnected)
    fun refreshElevenLabs() {
        elevenLabsHasKey = !vault.get("elevenlabs").isNullOrBlank()
        elevenLabsVoiceId = vault.get("elevenlabs_voice")?.takeIf { it.isNotBlank() }
    }
    var showLanguagePopup by remember { mutableStateOf(false) }
    var showPacePopup by remember { mutableStateOf(false) }

    // v3.2.39 (Scope 4): cards are DISTINCT real voices only — deduped by
    // the system voice name (the identity that is stored and applied), so
    // two entries that would speak with the same voice can never appear
    // as two cards. A phone with 2 real voices honestly shows 2 cards.
    val cards = remember(allVoices) {
        val bestByName = allVoices.groupBy { it.name }.mapValues { (_, sameName) ->
            sameName.maxWithOrNull(
                compareBy<Voice> { it.quality }
                    .thenBy { if (it.isNetworkConnectionRequired) 1 else 0 },
            ) ?: sameName.first()
        }
        com.vayro.app.voice.pickVoiceCards(
            bestByName.values.map { v ->
                com.vayro.app.voice.VoiceCardInfo(
                    name = v.name,
                    language = v.locale?.language,
                    quality = v.quality,
                    networkRequired = v.isNetworkConnectionRequired,
                )
            },
        ).mapNotNull { bestByName[it.name] }
    }
    val selectedVoice = remember(cards, allVoices, selectedVoiceName) {
        allVoices.firstOrNull { it.name == selectedVoiceName } ?: cards.firstOrNull()
    }

    fun previewSelectedVoice() {
        // v3.2.39 (Scope 4): a card's preview speaks through
        // ReadAloudPlayer — the SAME path Listen and voice calls use —
        // with the selection the tap just saved. (It used to speak through
        // this screen's private TTS engine, a second path that could sound
        // right here while Listen spoke differently.)
        ReadAloudPlayer.stop()
        ReadAloudPlayer.toggle(context, "kodex-voice-test", "Hi, I'm your Kodex voice. Ask me anything.")
    }

    fun selectVoice(voice: Voice, preview: Boolean) {
        selectedVoiceName = voice.name
        activeSource = "device"
        prefs.edit()
            .putString(VOICE_KEY_NAME, voice.name)
            .putString(VOICE_KEY_SOURCE, "device")
            .apply()
        if (preview) previewSelectedVoice()
    }

    if (showLanguagePopup && allVoices.isEmpty()) {
        // v3.2.38 (J): the Language row used to swallow the tap when the
        // device reports no TTS voices — say so instead of silence.
        ShellRadioPopup(
            title = "Language",
            options = listOf(Triple(null, "No voices available on this device", "none")),
            selectedKey = "",
            onSelect = { showLanguagePopup = false },
            onDismiss = { showLanguagePopup = false },
        )
    }
    if (showLanguagePopup && allVoices.isNotEmpty()) {
        val languages = remember(allVoices) {
            allVoices.mapNotNull { it.locale }.distinctBy { it.language + it.country }
        }
        ShellRadioPopup(
            title = "Language",
            options = languages.map { locale ->
                Triple(null, locale.getDisplayName(java.util.Locale.ENGLISH), locale.language + "|" + locale.country)
            },
            selectedKey = (selectedVoice?.locale?.language ?: "") + "|" + (selectedVoice?.locale?.country ?: ""),
            onSelect = { key ->
                showLanguagePopup = false
                val parts = key.split("|")
                val best = allVoices
                    .filter { it.locale?.language == parts.getOrNull(0) && it.locale?.country == parts.getOrNull(1) }
                    .maxByOrNull { it.quality }
                if (best != null) selectVoice(best, preview = true)
            },
            onDismiss = { showLanguagePopup = false },
        )
    }
    if (showPacePopup) {
        val paceOptions = listOf("Slow" to 0.85f, "Normal" to 1.0f, "Fast" to 1.18f)
        ShellRadioPopup(
            title = "Pace",
            options = paceOptions.map { (label, rate) -> Triple(null, label, rate.toString()) },
            selectedKey = paceOptions.minByOrNull { kotlin.math.abs(it.second - pace) }?.second?.toString() ?: "1.0",
            onSelect = { key ->
                showPacePopup = false
                val rate = key.toFloatOrNull() ?: 1.0f
                pace = rate
                prefs.edit().putFloat(VOICE_KEY_PACE, rate).apply()
                selectedVoice?.let { previewSelectedVoice() }
            },
            onDismiss = { showPacePopup = false },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(21.dp))
            }
            SerifText("Voice settings", fontSize = 21)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = rememberSaveable(saver = LazyListState.Saver, key = "voice-settings-list-state") { LazyListState() },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    "Choose the voice Kodex uses for Listen and voice calls. Tap a card to make it your active voice. On-device voices are free, work offline and never run out.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    "Active voice",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (cards.isEmpty() && !elevenLabsConnected) {
                    Text(
                        if (ttsReady) "No text-to-speech voices found on this device." else "Loading voices…",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(cards.withIndex().toList(), key = { it.value.name }) { (index, voice) ->
                            val isSelected = !elevenLabsActive && selectedVoice?.name == voice.name
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    if (isSelected) 2.dp else 1.dp,
                                    if (isSelected) KodexClay else MaterialTheme.colorScheme.outlineVariant,
                                ),
                                shadowElevation = if (isSelected) 4.dp else 1.dp,
                                modifier = Modifier
                                    .width(132.dp)
                                    .height(104.dp)
                                    .clickable { selectVoice(voice, preview = true) },
                            ) {
                                Column(Modifier.fillMaxSize().padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            VoiceStyleNames.getOrElse(index) { "Voice" },
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Icon(
                                            Icons.Outlined.PlayArrow,
                                            contentDescription = "Preview",
                                            modifier = Modifier.size(17.dp),
                                            tint = if (isSelected) KodexClay else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        voice.locale?.getDisplayName(java.util.Locale.ENGLISH) ?: "Voice",
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    // v3.2.39 (Scope 4): the REAL system
                                    // name of this voice, under the
                                    // friendly label — never hidden.
                                    Text(
                                        voice.name,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (voice.quality >= 400) "High quality" else "Standard",
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (elevenLabsConnected) {
                            item {
                                ElevenLabsSelectionCard(
                                    voiceIdMasked = "••••" + (elevenLabsVoiceId ?: "").takeLast(4),
                                    selected = elevenLabsActive,
                                    onClick = {
                                        activeSource = "elevenlabs"
                                        prefs.edit().putString(VOICE_KEY_SOURCE, "elevenlabs").apply()
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item {
                val testPlaying = ReadAloudPlayer.playingMessageId == "kodex-voice-test"
                Column {
                OutlinedButton(
                    onClick = {
                        // The EXACT player path Listen uses, so the test
                        // speaks with the ACTIVE voice (device card or
                        // ElevenLabs), never a settings-only preview.
                        ReadAloudPlayer.toggle(context, "kodex-voice-test", "Hello, this is your selected Kodex voice.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        if (testPlaying) Icons.Outlined.Close else Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (testPlaying) "Stop test" else "Test voice")
                }
                // v3.2.38 (M1): if the selected voice could not be used,
                // the reason shows here too (and as a toast at Listen).
                ReadAloudPlayer.lastNotice?.let { notice ->
                    Text(
                        notice,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                }
            }
            item {
                ShellCard {
                    ShellRow(
                        Icons.Outlined.RecordVoiceOver,
                        "Language",
                        value = selectedVoice?.locale?.getDisplayName(java.util.Locale.ENGLISH) ?: "—",
                        onClick = { showLanguagePopup = true },
                    )
                    ShellCardDivider()
                    ShellRow(
                        Icons.Outlined.PlayArrow,
                        "Pace",
                        value = when {
                            pace < 0.93f -> "Slow"
                            pace > 1.09f -> "Fast"
                            else -> "Normal"
                        },
                        onClick = { showPacePopup = true },
                    )
                }
            }
            item {
                ElevenLabsVoiceCard(onConnectionChanged = { refreshElevenLabs() })
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

/**
 * The ElevenLabs peer in the Active voice row (v3.2.34, U5): same card
 * style as the device voices. Shown only when a key + Voice ID are
 * saved; tapping it makes ElevenLabs the active voice.
 */
@Composable
private fun ElevenLabsSelectionCard(voiceIdMasked: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) KodexClay else MaterialTheme.colorScheme.outlineVariant,
        ),
        shadowElevation = if (selected) 4.dp else 1.dp,
        modifier = Modifier
            .width(132.dp)
            .height(104.dp)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ElevenLabs",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = if (selected) KodexClay else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                voiceIdMasked,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Connected",
                fontSize = 11.5.sp,
                color = if (selected) KodexClay else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
/**
 * v3.2.39 (Scope 3): the human part of an ElevenLabs error body —
 * {"detail":{"message":"…"}} or {"detail":"…"} — mirroring the extraction
 * ReadAloudPlayer does for playback failures.
 */
private fun elevenLabsErrorDetail(body: String): String =
    runCatching {
        val detail = org.json.JSONObject(body).opt("detail")
        when (detail) {
            is org.json.JSONObject -> detail.optString("message").ifBlank { detail.toString() }
            is String -> detail
            else -> body.take(200)
        }
    }.getOrDefault(body.take(200)).ifBlank { "no details" }

private fun ElevenLabsVoiceCard(onConnectionChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val vault = remember { ApiKeyVault(context) }
    var apiKey by remember { mutableStateOf("") }
    var voiceId by remember { mutableStateOf(vault.get("elevenlabs_voice") ?: "") }
    var hasKey by remember { mutableStateOf(vault.get("elevenlabs") != null) }
    var status by remember { mutableStateOf<String?>(null) }
    // v3.2.39 (Scope 3): pick the voice from the user's OWN ElevenLabs
    // account instead of hunting for an ID to paste.
    val scope = rememberCoroutineScope()
    var voicesLoading by remember { mutableStateOf(false) }
    var voiceChoices by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var voicesError by remember { mutableStateOf<String?>(null) }

    fun loadMyVoices() {
        val key = vault.get("elevenlabs") ?: run {
            voicesError = "Save your ElevenLabs API key first, then load your voices."
            return
        }
        voicesLoading = true
        voicesError = null
        scope.launch {
            val result = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val conn = (java.net.URL("https://api.elevenlabs.io/v1/voices").openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("xi-api-key", key)
                        connectTimeout = 15000
                        readTimeout = 15000
                    }
                    try {
                        val code = conn.responseCode
                        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                            ?.bufferedReader()?.use { it.readText() }.orEmpty()
                        if (code !in 200..299) {
                            // The real reason, not a guess — the same
                            // detail extraction the player uses.
                            throw IllegalStateException("HTTP $code — " + elevenLabsErrorDetail(body))
                        }
                        val voices = org.json.JSONObject(body).optJSONArray("voices")
                            ?: throw IllegalStateException("ElevenLabs sent an unexpected reply (no voices list).")
                        buildList {
                            for (i in 0 until voices.length()) {
                                val v = voices.optJSONObject(i) ?: continue
                                val id = v.optString("voice_id")
                                val name = v.optString("name")
                                if (id.isBlank() || name.isBlank()) continue
                                val category = when (v.optString("category").lowercase()) {
                                    "premade" -> "Premade"
                                    "generated" -> "Designed"
                                    "cloned" -> "Cloned"
                                    "professional" -> "Professional"
                                    else -> v.optString("category")
                                        .replaceFirstChar { it.uppercase() }
                                        .ifBlank { "Voice" }
                                }
                                add("$name · $category" to id)
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
            }
            voicesLoading = false
            result.onSuccess { list ->
                if (list.isEmpty()) {
                    voicesError = "Your ElevenLabs account has no voices yet. Add one in the ElevenLabs app or website, then try again."
                } else {
                    voiceChoices = list
                }
            }.onFailure { e ->
                voicesError = e.message ?: "Could not load your voices. Check your connection and try again."
            }
        }
    }

    ShellCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Premium voice — ElevenLabs", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Optional: paste your own ElevenLabs API key, then paste a Voice ID — or pick one of your own voices below. Saving adds an ElevenLabs card to the Active voice row above — tap it there to make it the voice Kodex speaks with. Both are stored encrypted on this device only and are never logged or shared.",
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("ElevenLabs API key") },
                placeholder = { Text(if (hasKey) "Saved — paste a new key to replace it" else "sk_…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            OutlinedTextField(
                value = voiceId,
                onValueChange = { voiceId = it },
                label = { Text("ElevenLabs Voice ID") },
                placeholder = { Text("e.g. 21m00Tcm4TlvDq8ikWAM") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            // v3.2.39 (Scope 3): …or pick one of your own voices — the list
            // comes from YOUR account, so every entry is a voice your plan
            // can actually use.
            if (voicesLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThinkingOrb(Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Loading your voices…",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                OutlinedButton(
                    onClick = { loadMyVoices() },
                    enabled = hasKey,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Choose from my voices")
                }
            }
            if (!hasKey) {
                Text(
                    "Save your API key first — then you can pick one of your own voices here.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            voicesError?.let {
                Text(it, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error)
            }
            voiceChoices?.let { choices ->
                ShellRadioPopup(
                    title = "Your ElevenLabs voices",
                    options = choices.map { (label, id) -> Triple(null, label, id) },
                    selectedKey = voiceId,
                    onSelect = { id ->
                        voiceChoices = null
                        // Fills the field AND saves — the same vault
                        // write a manual paste + Save performs.
                        voiceId = id
                        vault.put("elevenlabs_voice", id)
                        status = "Voice saved: ${choices.firstOrNull { it.second == id }?.first ?: id}. Tap the ElevenLabs card in Active voice above to speak with this voice."
                        onConnectionChanged()
                    },
                    onDismiss = { voiceChoices = null },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (apiKey.isNotBlank()) vault.put("elevenlabs", apiKey.trim())
                        if (voiceId.isNotBlank()) {
                            vault.put("elevenlabs_voice", voiceId.trim())
                        } else {
                            vault.remove("elevenlabs_voice")
                        }
                        hasKey = vault.get("elevenlabs") != null
                        status = if (hasKey && voiceId.isNotBlank()) {
                            "Saved. Tap the ElevenLabs card in Active voice above to speak with this voice."
                        } else if (hasKey) {
                            "Key saved. Add a Voice ID to enable the ElevenLabs voice card."
                        } else {
                            "Saved. Listen will use the free on-device voice."
                        }
                        apiKey = ""
                        onConnectionChanged()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KodexClay),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save", color = Color.White)
                }
                if (hasKey) {
                    OutlinedButton(
                        onClick = {
                            vault.remove("elevenlabs")
                            vault.remove("elevenlabs_voice")
                            apiKey = ""
                            voiceId = ""
                            hasKey = false
                            status = "Removed. Listen will use the free on-device voice."
                            onConnectionChanged()
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Remove key")
                    }
                }
            }
            status?.let {
                Text(it, fontSize = 11.5.sp, color = KodexClay)
            }
        }
    }
}


// ---------------------------------------------------------------------------
// Git push — picker sheet, progress dialog and result dialog. The host
// (PocketDevApp) owns the real push work; this is presentation only.
// ---------------------------------------------------------------------------

internal class GitPushUiState {
    var phase by mutableStateOf(0)          // 0 hidden, 1 picking, 2 pushing, 3 done
    var targetName by mutableStateOf("")
    var resultOk by mutableStateOf(false)
    var resultText by mutableStateOf("")
    fun showPicker() { phase = 1 }
    fun beginPush(name: String) { targetName = name; phase = 2 }
    fun finish(ok: Boolean, text: String) { resultOk = ok; resultText = text; phase = 3 }
    fun dismiss() { phase = 0 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitPushUi(
    state: GitPushUiState,
    projects: List<Project>,
    onPickProject: (Project) -> Unit,
) {
    when (state.phase) {
        1 -> {
            ModalBottomSheet(
                onDismissRequest = { state.dismiss() },
                containerColor = MaterialTheme.colorScheme.surface,
                dragHandle = null,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                Column(Modifier.padding(bottom = 26.dp)) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        IconButton(onClick = { state.dismiss() }, modifier = Modifier.align(Alignment.CenterStart)) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close", modifier = Modifier.size(21.dp))
                        }
                        Text(
                            "Push to GitHub",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    if (projects.isEmpty()) {
                        Text(
                            "No projects yet — create a project first, then push it.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
                        )
                    } else {
                        projects.forEach { project ->
                            AttachRow(Icons.Outlined.Folder, project.name, value = project.slug, onClick = { onPickProject(project) })
                        }
                    }
                }
            }
        }
        2 -> {
            Dialog(onDismissRequest = {}) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier.padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // v3.2.38 (Part O): the orb, not a plain spinner,
                        // on this home-reachable wait too.
                        ThinkingOrb(Modifier.size(28.dp))
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Pushing ${state.targetName} to GitHub…",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "This can take a moment — keep the app open.",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        3 -> {
            val message = state.resultText.trim().ifBlank {
                if (state.resultOk) "The latest changes are now on GitHub." else "The push did not go through. Check the GitHub connection and try again."
            }
            Dialog(onDismissRequest = { state.dismiss() }) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier.padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            if (state.resultOk) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                            tint = if (state.resultOk) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (state.resultOk) "Pushed to GitHub" else "Push failed",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (state.targetName.isBlank()) message else "${state.targetName}\n$message",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { state.dismiss() },
                            colors = ButtonDefaults.buttonColors(containerColor = KodexClay),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Done", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Profile page — dedicated Settings → Profile page: account card plus the
// full account section (or a sign-in row when auth is unavailable).
// ---------------------------------------------------------------------------

@Composable
internal fun ProfilePage(
    accountName: String,
    accountEmail: String?,
    isGuest: Boolean,
    authViewModel: com.vayro.app.auth.ui.AuthViewModel?,
    authState: com.vayro.app.auth.model.AuthState,
    onBack: () -> Unit,
    onNavigateToAuth: () -> Unit,
) {
    val initial = (accountName.firstOrNull()?.uppercaseChar() ?: 'K').toString()
    // v3.2.38 (Part K): Profile's Edit Display Name / Change Password /
    // Delete Account buttons flip AuthViewModel dialog flags — the dialogs
    // themselves must be composed here, on the page the user is looking at.
    if (authViewModel != null) {
        AuthAccountDialogs(authViewModel = authViewModel)
    }
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 6.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(21.dp))
            }
            SerifText("Profile", fontSize = 21, modifier = Modifier.align(Alignment.Center))
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ShellCard {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(44.dp).background(KodexClay, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(initial, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(accountName, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            accountEmail ?: if (isGuest) "Guest mode · Local only" else "Signed in",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (authViewModel != null) {
                ShellCard {
                    Column(Modifier.padding(16.dp)) {
                        AccountSectionContent(viewModel = authViewModel, authState = authState, onNavigateToAuth = onNavigateToAuth)
                    }
                }
            } else {
                ShellCard {
                    ShellRow(Icons.Outlined.Person, "Sign in", onClick = onNavigateToAuth)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// License page — dedicated Settings → License page over the local
// LicenseStore: status rows and license removal for this phone.
// ---------------------------------------------------------------------------

@Composable
internal fun LicensePage(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val lic = remember(refreshTick) { com.vayro.app.license.LicenseStore(context) }
    var showConfirm by remember { mutableStateOf(false) }
    val dateFmt = remember { java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()) }
    val dateTimeFmt = remember { java.text.SimpleDateFormat("MMM dd, yyyy · HH:mm", java.util.Locale.getDefault()) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Remove license?") },
            text = { Text("This removes the license stored on this phone. If license checks are on, Kodex will ask for a code the next time it starts.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        lic.clearLicense()
                        refreshTick++
                        showConfirm = false
                    },
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 6.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(21.dp))
            }
            SerifText("License", fontSize = 21, modifier = Modifier.align(Alignment.Center))
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ShellCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Status", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(if (lic.token == null) "Not activated on this phone" else "Active", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Type", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(lic.licenseType ?: "—", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Expires", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(
                            when {
                                lic.token == null -> "—"
                                lic.expiresAt > 0 -> dateFmt.format(java.util.Date(lic.expiresAt))
                                else -> "Lifetime"
                            },
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Activated", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(if (lic.activatedAt > 0) dateTimeFmt.format(java.util.Date(lic.activatedAt)) else "—", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Last verified", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(if (lic.lastOkAt > 0) dateTimeFmt.format(java.util.Date(lic.lastOkAt)) else "—", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (lic.token != null) {
                ShellCard {
                    ShellRow(Icons.Outlined.Lock, "Remove license from this phone", labelColor = MaterialTheme.colorScheme.error, onClick = { showConfirm = true })
                }
            } else {
                Text(
                    "No license is stored on this phone. If license checks are on, Kodex will ask for a code the next time it starts.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Notifications page — dedicated Settings → Notifications page: real
// system permission status, permission request and system settings link.
// Per-channel control lives in system settings, so there are no toggles.
// ---------------------------------------------------------------------------

@Composable
internal fun NotificationsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(false) }
    fun refresh() {
        enabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    LaunchedEffect(Unit) { refresh() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 6.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(21.dp))
            }
            SerifText("Notifications", fontSize = 21, modifier = Modifier.align(Alignment.Center))
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ShellCard {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Notifications, contentDescription = null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (enabled) "Notifications are on" else "Notifications are off", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (enabled) "Kodex can send task and update notifications." else "Turn notifications on so Kodex can tell you when a task finishes or an update is ready.",
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // v3.2.37 (Part G): background-work control + one-time battery hint.
            ShellCard {
                val prefs = remember { com.vayro.app.data.AppPreferences(context) }
                var keepWorking by remember { mutableStateOf(prefs.keepWorkingInBackground) }
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Keep working in background", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (keepWorking) {
                                "Kodex keeps working when you leave the app, and tells you when a task finishes."
                            } else {
                                "Off: Kodex may pause when you leave the app. Tasks resume from where they stopped when you come back."
                            },
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ShellSwitch(checked = keepWorking) { on ->
                        keepWorking = on
                        prefs.keepWorkingInBackground = on
                    }
                }
                if (keepWorking) {
                    val powerManager = remember {
                        context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                    }
                    var ignoringBattery by remember {
                        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
                    }
                    var hintSeen by remember { mutableStateOf(prefs.batteryHintSeen) }
                    if (!ignoringBattery && !hintSeen) {
                        ShellCardDivider()
                        Column(Modifier.padding(16.dp)) {
                            Text("Battery limit can stop long tasks", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Android is limiting Kodex's battery use. Allow unrestricted battery so long tasks can finish in the background.",
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = KodexClay,
                                    modifier = Modifier.clickable {
                                        runCatching {
                                            context.startActivity(
                                                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                                    .setData(Uri.parse("package:" + context.packageName))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                        prefs.batteryHintSeen = true
                                        hintSeen = true
                                        ignoringBattery =
                                            powerManager.isIgnoringBatteryOptimizations(context.packageName)
                                    },
                                ) {
                                    Text(
                                        "Allow",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    )
                                }
                                TextButton(onClick = {
                                    prefs.batteryHintSeen = true
                                    hintSeen = true
                                }) { Text("Not now") }
                            }
                        }
                    }
                }
            }
            ShellCard {
                if (!enabled && Build.VERSION.SDK_INT >= 33) {
                    ShellRow(Icons.Outlined.Notifications, "Allow notifications", onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) })
                    ShellCardDivider()
                }
                ShellRow(
                    Icons.Outlined.Settings,
                    "Open notification settings",
                    onClick = {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
            ShellCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        Text("Running coding tasks", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Progress while Kodex is working in the background", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column {
                        Text("Task results", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("When a coding task finishes or needs attention", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column {
                        Text("Kodex updates", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Remote updates for projects and agent sessions", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column {
                        Text("App updates", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("When a new Kodex version is ready", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}


// ---------------------------------------------------------------------------
// Connectors header — sits above the existing MCP surface (SkillsScreen's
// MCP tab): back, serif title, live server/tool counts, and a "+" menu
// (Browse connectors / Add custom connector).
// ---------------------------------------------------------------------------

@Composable
internal fun ConnectorsHeader(
    serverCount: Int,
    toolCount: Int,
    onBack: () -> Unit,
    onBrowse: () -> Unit,
    onAddCustom: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 6.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(21.dp))
            }
            Column {
                SerifText("Connectors", fontSize = 20)
                Text(
                    "$serverCount servers · $toolCount tools",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "Add connector", modifier = Modifier.size(22.dp))
            }
        }
        ShellPopupMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            items = listOf(
                "Browse connectors" to onBrowse,
                "Add custom connector" to onAddCustom,
            ),
        )
    }
}
