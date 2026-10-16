package com.vayro.app.factory.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.factory.blueprint.AppFactoryBlueprintEngine
import com.vayro.app.factory.model.AppBlueprint
import com.vayro.app.factory.model.AppFramework
import com.vayro.app.factory.model.BuildArtifactType
import com.vayro.app.factory.model.BuildMode
import com.vayro.app.factory.model.BuildResult
import com.vayro.app.factory.model.KeystoreConfig
import com.vayro.app.factory.model.StoreListingBundle
import com.vayro.app.factory.model.VersionBumpType
import java.io.File

@Composable
private fun adaptiveCheckGreen(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF2E7D32)

private enum class FactoryTab(val title: String, val icon: ImageVector) {
    BLUEPRINT("Blueprint", Icons.Default.AutoAwesome),
    SCAFFOLD("Code & DB", Icons.Default.Code),
    BUILD("Build & CI", Icons.Default.Build),
    SIGNING("Signing & Version", Icons.Default.Key),
    PLAY_STORE("Play Store", Icons.Default.Storefront)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFactoryScreen(
    currentBlueprint: AppBlueprint?,
    isGeneratingBlueprint: Boolean,
    isScaffolding: Boolean,
    scaffoldedFiles: List<File>,
    isBuilding: Boolean,
    buildResult: BuildResult?,
    buildLogs: List<String>,
    keystoreConfig: KeystoreConfig?,
    storeListingBundle: StoreListingBundle?,
    statusMessage: String?,
    onBack: () -> Unit,
    onGenerateBlueprint: (prompt: String, framework: AppFramework, mode: BuildMode, archetype: AppFactoryBlueprintEngine.Archetype?) -> Unit,
    onScaffoldProject: () -> Unit,
    onRunBuild: (artifactType: BuildArtifactType, buildMode: BuildMode) -> Unit,
    onInstallApk: (apkFile: File) -> Unit,
    onGenerateKeystore: (alias: String, pass: String) -> Unit,
    onBumpVersion: (type: VersionBumpType) -> Unit,
    onPreparePlayStore: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "App Factory",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                        Text(
                            text = "Prompt to native Android, Flutter & React Native with automated CI",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Status bar if active
            if (statusMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Tab bar
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 12.dp
            ) {
                FactoryTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tab.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        icon = { Icon(tab.icon, contentDescription = tab.title, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            when (FactoryTab.entries[selectedTab]) {
                FactoryTab.BLUEPRINT -> BlueprintTab(
                    currentBlueprint = currentBlueprint,
                    isGenerating = isGeneratingBlueprint,
                    onGenerate = { prompt, framework, mode, archetype ->
                        onGenerateBlueprint(prompt, framework, mode, archetype)
                    }
                )
                FactoryTab.SCAFFOLD -> ScaffoldTab(
                    blueprint = currentBlueprint,
                    isScaffolding = isScaffolding,
                    scaffoldedFiles = scaffoldedFiles,
                    onScaffold = onScaffoldProject
                )
                FactoryTab.BUILD -> BuildAutomationTab(
                    blueprint = currentBlueprint,
                    isBuilding = isBuilding,
                    buildResult = buildResult,
                    buildLogs = buildLogs,
                    onRunBuild = onRunBuild,
                    onInstallApk = onInstallApk
                )
                FactoryTab.SIGNING -> SigningTab(
                    blueprint = currentBlueprint,
                    keystoreConfig = keystoreConfig,
                    onGenerateKeystore = onGenerateKeystore,
                    onBumpVersion = onBumpVersion
                )
                FactoryTab.PLAY_STORE -> PlayStoreTab(
                    blueprint = currentBlueprint,
                    storeBundle = storeListingBundle,
                    onPrepare = onPreparePlayStore
                )
            }
        }
    }
}

@Composable
private fun BlueprintTab(
    currentBlueprint: AppBlueprint?,
    isGenerating: Boolean,
    onGenerate: (prompt: String, framework: AppFramework, mode: BuildMode, archetype: AppFactoryBlueprintEngine.Archetype?) -> Unit
) {
    var promptText by rememberSaveable { mutableStateOf("") }
    var selectedFramework by remember { mutableStateOf(AppFramework.ANDROID_COMPOSE) }
    var selectedBuildMode by remember { mutableStateOf(BuildMode.ON_DEVICE) }
    var selectedArchetype by remember { mutableStateOf<AppFactoryBlueprintEngine.Archetype?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("1. Describe Your Mobile Application", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                label = { Text("App concept, features, target users...") },
                placeholder = { Text("e.g. Build a food delivery app with restaurant menus, active delivery tracking, and offline cart...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                shape = RoundedCornerShape(12.dp)
            )
        }

        item {
            Text("Pre-Built Archetypes", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AppFactoryBlueprintEngine.Archetype.entries.toTypedArray()) { arch ->
                    FilterChip(
                        selected = selectedArchetype == arch,
                        onClick = {
                            if (selectedArchetype == arch) {
                                selectedArchetype = null
                            } else {
                                selectedArchetype = arch
                                if (promptText.isBlank()) {
                                    promptText = "Build a ${arch.title.lowercase()} with complete features."
                                }
                            }
                        },
                        label = { Text(arch.title, fontSize = 11.5.sp) }
                    )
                }
            }
        }

        item {
            Text("Target Framework", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            // Keep framework labels concise and use intrinsic chip widths: equal-width chips
            // squeeze "Fullstack" into multiple lines on a phone-sized viewport.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppFramework.entries.forEach { fw ->
                    val shortName = when (fw) {
                        AppFramework.ANDROID_COMPOSE -> "Android"
                        AppFramework.FLUTTER -> "Flutter"
                        AppFramework.REACT_NATIVE -> "React"
                        AppFramework.FULLSTACK_BACKEND -> "Fullstack"
                    }
                    FilterChip(
                        selected = selectedFramework == fw,
                        onClick = { selectedFramework = fw },
                        label = {
                            Text(
                                text = shortName,
                                maxLines = 1,
                                softWrap = false,
                                fontSize = 10.5.sp
                            )
                        },
                        modifier = Modifier.weight(
                            when (fw) {
                                AppFramework.ANDROID_COMPOSE -> 1.15f
                                AppFramework.FLUTTER -> 0.9f
                                AppFramework.REACT_NATIVE -> 0.9f
                                AppFramework.FULLSTACK_BACKEND -> 1.2f
                            }
                        ),
                    )
                }
            }
        }

        item {
            Text("Build Strategy (Low-End Phone Friendly)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                BuildMode.entries.forEach { bm ->
                    FilterChip(
                        selected = selectedBuildMode == bm,
                        onClick = { selectedBuildMode = bm },
                        label = {
                            Text(
                                if (bm == BuildMode.ON_DEVICE) "📱 On-Device" else "☁️ GitHub CI",
                                fontSize = 11.5.sp
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    onGenerate(promptText, selectedFramework, selectedBuildMode, selectedArchetype)
                },
                enabled = !isGenerating && (promptText.isNotBlank() || selectedArchetype != null),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Synthesizing Blueprint…")
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Synthesize App Blueprint")
                }
            }
        }

        if (currentBlueprint != null) {
            item {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Active Blueprint: ${currentBlueprint.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Package: ${currentBlueprint.packageName} • Framework: ${currentBlueprint.framework.displayName}", style = MaterialTheme.typography.bodySmall)
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Data Models (${currentBlueprint.entities.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        currentBlueprint.entities.forEach { e ->
                            Text("• ${e.name} (${e.fields.size} fields: ${e.fields.take(3).joinToString { it.name }}...)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Screens (${currentBlueprint.screens.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        currentBlueprint.screens.forEach { s ->
                            Text("• ${s.title} (${s.route})", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("REST API Endpoints (${currentBlueprint.endpoints.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        currentBlueprint.endpoints.forEach { ep ->
                            Text("• ${ep.method} ${ep.path} - ${ep.summary}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScaffoldTab(
    blueprint: AppBlueprint?,
    isScaffolding: Boolean,
    scaffoldedFiles: List<File>,
    onScaffold: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("2. Code & Database Scaffolding", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Generate complete compilable project files, UI screens, Room/SQLite database, and Retrofit/Dio clients.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (blueprint == null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(12.dp))
                        Text("No active blueprint found. Please create a blueprint in Step 1 first.", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        } else {
            item {
                Button(
                    onClick = onScaffold,
                    enabled = !isScaffolding,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isScaffolding) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Scaffolding Project Files…")
                    } else {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scaffold Project into Workspace")
                    }
                }
            }

            if (scaffoldedFiles.isNotEmpty()) {
                item {
                    Text("Scaffolded Files (${scaffoldedFiles.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                items(scaffoldedFiles, key = { it.absolutePath }) { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = adaptiveCheckGreen(), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(file.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(6.dp))
                        Text(file.parentFile?.name.orEmpty(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (!isScaffolding) {
                // Medium: no empty state — zero scaffolded files used to
                // show only the button, no guidance.
                item {
                    Text(
                        "No files scaffolded yet. Tap the button above to generate the project from the blueprint.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BuildAutomationTab(
    blueprint: AppBlueprint?,
    isBuilding: Boolean,
    buildResult: BuildResult?,
    buildLogs: List<String>,
    onRunBuild: (BuildArtifactType, BuildMode) -> Unit,
    onInstallApk: (File) -> Unit
) {
    var selectedArtifact by remember { mutableStateOf(BuildArtifactType.APK_DEBUG) }
    var selectedMode by remember { mutableStateOf(BuildMode.ON_DEVICE) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("3. Build Automation (On-Device & GitHub CI)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Build on your Android device or offload compilation to free GitHub Actions runners.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Text("Artifact Type", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                BuildArtifactType.entries.forEach { art ->
                    FilterChip(
                        selected = selectedArtifact == art,
                        onClick = { selectedArtifact = art },
                        label = { Text(art.displayName, fontSize = 11.5.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Text("Execution Strategy", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                BuildMode.entries.forEach { mode ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode },
                        label = { Text(if (mode == BuildMode.ON_DEVICE) "📱 On-Device" else "☁️ GitHub CI", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Button(
                onClick = { onRunBuild(selectedArtifact, selectedMode) },
                enabled = !isBuilding && blueprint != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isBuilding) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Building ${selectedArtifact.displayName}…")
                } else {
                    Icon(
                        if (selectedMode == BuildMode.ON_DEVICE) Icons.Default.PlayArrow else Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (selectedMode == BuildMode.ON_DEVICE) "Compile ${selectedArtifact.displayName}" else "Generate GitHub Actions CI")
                }
            }
        }

        if (buildResult != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (buildResult.success) Color(0xFF1B5E20).copy(alpha = 0.12f) else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (buildResult.success) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                // Dark green (0xFF2E7D32) on a dark background is unreadable;
                                // light green in dark theme, dark green in light theme.
                                tint = if (buildResult.success) {
                                    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF2E7D32)
                                } else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (buildResult.success) "Build Successful!" else "Build Failed",
                                fontWeight = FontWeight.Bold,
                                color = if (buildResult.success) {
                                    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF2E7D32)
                                } else MaterialTheme.colorScheme.error
                            )
                        }

                        if (buildResult.outputFile != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("Output: ${buildResult.outputFile.name}", fontWeight = FontWeight.Medium, fontSize = 12.5.sp)
                            Text("Path: ${buildResult.outputFile.absolutePath}", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)

                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { onInstallApk(buildResult.outputFile) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                            ) {
                                Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("📲 1-Tap Install on Device")
                            }
                        }

                        if (buildResult.ciWorkflowFile != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("Generated Workflow: ${buildResult.ciWorkflowFile.name}", fontWeight = FontWeight.Medium, fontSize = 12.5.sp)
                            Text("Push repo to trigger cloud build on GitHub Actions.", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (buildLogs.isNotEmpty()) {
            item {
                Text("Build Console Logs", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.85f)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        buildLogs.takeLast(25).forEach { line ->
                            Text(line, color = Color.Green, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        } else if (buildResult == null && !isBuilding) {
            // Medium: no empty state — no result and no logs used to show
            // nothing below the build button.
            item {
                Text(
                    "No build run yet. Choose an artifact above and tap Compile to run your first build.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SigningTab(
    blueprint: AppBlueprint?,
    keystoreConfig: KeystoreConfig?,
    onGenerateKeystore: (alias: String, pass: String) -> Unit,
    onBumpVersion: (VersionBumpType) -> Unit
) {
    var keyAlias by rememberSaveable { mutableStateOf("releaseKey") }
    // No default password: every keystore gets a password only the user chose.
    // Plain remember (not rememberSaveable) keeps the password out of the
    // Activity saved-state bundle.
    var keyPassword by remember { mutableStateOf("") }
    var keyPasswordVisible by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("4. Keystore & Version Management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Generate RSA 2048-bit keystores, inject signing configs into Gradle, and bump semantic versions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Release Keystore Setup", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = keyAlias,
                        onValueChange = { keyAlias = it },
                        label = { Text("Key Alias") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = keyPassword,
                        onValueChange = { keyPassword = it },
                        label = { Text("Keystore Password") },
                        visualTransformation = if (keyPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { keyPasswordVisible = !keyPasswordVisible }) {
                                Icon(
                                    if (keyPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (keyPasswordVisible) "Hide password" else "Show password",
                                )
                            }
                        },
                        supportingText = { Text("At least 6 characters. Keep it safe — a lost keystore password cannot be recovered.") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onGenerateKeystore(keyAlias, keyPassword) },
                        enabled = keyAlias.isNotBlank() && keyPassword.length >= 6,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Generate 2048-bit Keystore")
                    }

                    if (keystoreConfig != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = adaptiveCheckGreen(), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Keystore active: ${keystoreConfig.keystoreFile.name}", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Semantic Version Bumper", fontWeight = FontWeight.Bold)
                    if (blueprint != null) {
                        Text("Current Version: ${blueprint.buildConfig.versionName} (Code: ${blueprint.buildConfig.versionCode})", fontSize = 13.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { onBumpVersion(VersionBumpType.PATCH) }, modifier = Modifier.weight(1f)) {
                            Text("Patch (+0.0.1)", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { onBumpVersion(VersionBumpType.MINOR) }, modifier = Modifier.weight(1f)) {
                            Text("Minor (+0.1.0)", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { onBumpVersion(VersionBumpType.MAJOR) }, modifier = Modifier.weight(1f)) {
                            Text("Major (+1.0.0)", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayStoreTab(
    blueprint: AppBlueprint?,
    storeBundle: StoreListingBundle?,
    onPrepare: () -> Unit
) {
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("5. Google Play Store Readiness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Export store listing metadata, Privacy Policy, graphic asset guidelines, and target SDK compliance checklist.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Button(
                onClick = onPrepare,
                enabled = blueprint != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Storefront, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Generate Play Store Bundle")
            }
        }

        if (blueprint != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Store Listing Metadata", fontWeight = FontWeight.Bold)
                        Text("Title: ${blueprint.playStoreMetadata.title} (${blueprint.playStoreMetadata.title.length}/30)", fontSize = 12.sp)
                        Text("Short Description: ${blueprint.playStoreMetadata.shortDescription} (${blueprint.playStoreMetadata.shortDescription.length}/80)", fontSize = 12.sp)
                        Text("Category: ${blueprint.playStoreMetadata.category}", fontSize = 12.sp)
                        Text("Contact: ${blueprint.playStoreMetadata.contactEmail}", fontSize = 12.sp)
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Policy & Target SDK Compliance", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = adaptiveCheckGreen(), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Target SDK 35 (Meets Android 14/15 minimum of 34)", fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = adaptiveCheckGreen(), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("GDPR/CCPA Privacy Policy Generated", fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = adaptiveCheckGreen(), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("64-bit Native Architectures Enabled", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (storeBundle != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Exported Bundle", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Location: ${storeBundle.listingDir.absolutePath}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Text("• ${storeBundle.titleFile.name}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("• ${storeBundle.shortDescFile.name}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("• ${storeBundle.fullDescFile.name}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("• ${storeBundle.privacyPolicyFile.name}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("• ${storeBundle.complianceReportFile.name}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
    }
}
