package com.vayro.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.model.WorkspaceEntry

enum class PaletteActionCategory {
    EDITOR,
    NAVIGATION,
    FILE,
    BUILD,
}

data class PaletteAction(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val category: PaletteActionCategory,
    val keywords: List<String> = emptyList(),
)

/**
 * Universal Mobile IDE Command Palette.
 * Supports running editor actions, workspace navigation, build commands, and fuzzy file navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPalette(
    workspaceFiles: List<WorkspaceEntry> = emptyList(),
    hasActiveEditor: Boolean = false,
    onDismiss: () -> Unit,
    onActionSelected: (String) -> Unit,
    onFileSelected: (String) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<PaletteActionCategory?>(null) }

    val allActions = buildList {
        if (hasActiveEditor) {
            add(PaletteAction("save_file", "Save File", "Save current changes to disk", Icons.Default.Save, PaletteActionCategory.EDITOR, listOf("write", "commit")))
            add(PaletteAction("find_in_file", "Find & Replace", "Search and replace within current file", Icons.Default.FindInPage, PaletteActionCategory.EDITOR, listOf("search", "regex")))
            add(PaletteAction("global_search", "Global Search & Replace", "Search across the entire workspace", Icons.Default.Search, PaletteActionCategory.EDITOR, listOf("grep", "all")))
            add(PaletteAction("goto_line", "Go to Line", "Jump to a specific line number", Icons.AutoMirrored.Filled.List, PaletteActionCategory.EDITOR, listOf("jump", "ln")))
            add(PaletteAction("symbol_outline", "Symbol Outline", "Inspect classes, functions, and symbols", Icons.Default.Code, PaletteActionCategory.EDITOR, listOf("ast", "declarations")))
            add(PaletteAction("toggle_word_wrap", "Toggle Word Wrap", "Wrap long lines in editor", Icons.AutoMirrored.Filled.WrapText, PaletteActionCategory.EDITOR, listOf("wrap", "scroll")))
            add(PaletteAction("close_tab", "Close Tab", "Close current editor tab", Icons.Default.Close, PaletteActionCategory.EDITOR, listOf("exit")))
            add(PaletteAction("close_other_tabs", "Close Other Tabs", "Keep only active file open", Icons.Default.Close, PaletteActionCategory.EDITOR, listOf("only")))
            add(PaletteAction("close_all_tabs", "Close All Tabs", "Close all open editor tabs", Icons.Default.Close, PaletteActionCategory.EDITOR, listOf("clear")))
        }

        // File operations
        add(PaletteAction("new_file", "New File", "Create a new file in workspace", Icons.Default.Description, PaletteActionCategory.FILE, listOf("create", "add", "touch")))
        add(PaletteAction("new_folder", "New Folder", "Create a new directory", Icons.Default.Folder, PaletteActionCategory.FILE, listOf("mkdir", "directory")))
        add(PaletteAction("refresh_files", "Refresh Workspace Files", "Reload file tree from filesystem", Icons.Default.Refresh, PaletteActionCategory.FILE, listOf("reload", "sync")))
        add(PaletteAction("global_search_workspace", "Global Search", "Search all workspace files", Icons.Default.Search, PaletteActionCategory.FILE, listOf("grep", "find")))

        // Navigation
        add(PaletteAction("nav_chat", "Open AI Chat", "Switch to AI conversation assistant", Icons.Default.Code, PaletteActionCategory.NAVIGATION, listOf("ask", "agent")))
        add(PaletteAction("nav_files", "Open File Explorer", "Browse project directory tree", Icons.Default.Folder, PaletteActionCategory.NAVIGATION, listOf("tree", "browse")))
        add(PaletteAction("nav_terminal", "Open Terminal", "Access interactive shell environment", Icons.Default.Terminal, PaletteActionCategory.NAVIGATION, listOf("shell", "bash", "cli")))
        add(PaletteAction("nav_changes", "View Git Changes", "Review pending edits and diffs", Icons.AutoMirrored.Filled.List, PaletteActionCategory.NAVIGATION, listOf("diff", "status")))
        add(PaletteAction("nav_preview", "Open Web Preview", "Launch local web preview", Icons.Default.Visibility, PaletteActionCategory.NAVIGATION, listOf("browser", "html", "server")))

        // Build & Runtime
        add(PaletteAction("run_build", "Run Build", "Execute project build command", Icons.Default.Build, PaletteActionCategory.BUILD, listOf("compile", "gradle", "make")))
        add(PaletteAction("run_tests", "Run Tests", "Execute test suite", Icons.Default.PlayArrow, PaletteActionCategory.BUILD, listOf("unit", "check")))
        add(PaletteAction("check_health", "Check Runtime Health", "Inspect storage, rootfs, and memory", Icons.Default.Build, PaletteActionCategory.BUILD, listOf("storage", "quota", "disk")))
    }

    val normalizedQuery = query.trim().lowercase()

    // Filter matching actions
    val matchingActions = allActions.filter { action ->
        (selectedCategory == null || action.category == selectedCategory) &&
            (normalizedQuery.isBlank() ||
                action.title.lowercase().contains(normalizedQuery) ||
                action.description.lowercase().contains(normalizedQuery) ||
                action.keywords.any { it.contains(normalizedQuery) })
    }

    // Filter matching files for quick open
    val matchingFiles = if (normalizedQuery.isNotBlank() && selectedCategory == null) {
        workspaceFiles.filter { !it.isDirectory && it.path.lowercase().contains(normalizedQuery) }.take(8)
    } else emptyList()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 560.dp)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Command Palette",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Type command or file name...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(8.dp))

            // Category filter chips
            Row(
                // Medium: the five chips overflowed on narrow phones with
                // no way to reach the ones off-screen.
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("All", fontSize = 11.sp) },
                )
                FilterChip(
                    selected = selectedCategory == PaletteActionCategory.EDITOR,
                    onClick = { selectedCategory = if (selectedCategory == PaletteActionCategory.EDITOR) null else PaletteActionCategory.EDITOR },
                    label = { Text("Editor", fontSize = 11.sp) },
                )
                FilterChip(
                    selected = selectedCategory == PaletteActionCategory.NAVIGATION,
                    onClick = { selectedCategory = if (selectedCategory == PaletteActionCategory.NAVIGATION) null else PaletteActionCategory.NAVIGATION },
                    label = { Text("Nav", fontSize = 11.sp) },
                )
                FilterChip(
                    selected = selectedCategory == PaletteActionCategory.FILE,
                    onClick = { selectedCategory = if (selectedCategory == PaletteActionCategory.FILE) null else PaletteActionCategory.FILE },
                    label = { Text("Files", fontSize = 11.sp) },
                )
                FilterChip(
                    selected = selectedCategory == PaletteActionCategory.BUILD,
                    onClick = { selectedCategory = if (selectedCategory == PaletteActionCategory.BUILD) null else PaletteActionCategory.BUILD },
                    label = { Text("Build", fontSize = 11.sp) },
                )
            }

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (matchingFiles.isNotEmpty()) {
                    item(key = "header_files") {
                        Text(
                            text = "Matching Files (${matchingFiles.size})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(matchingFiles, key = { "file_${it.path}" }) { file ->
                        Surface(
                            onClick = {
                                onDismiss()
                                onFileSelected(file.path)
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        file.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        file.path,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    "Open",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                    item(key = "divider_files") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Actions",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }

                if (matchingActions.isEmpty() && matchingFiles.isEmpty()) {
                    item {
                        Text(
                            "No actions or files match \"$query\"",
                            modifier = Modifier.padding(vertical = 24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    items(matchingActions, key = { it.id }) { action ->
                        Surface(
                            onClick = {
                                onDismiss()
                                onActionSelected(action.id)
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    action.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(action.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        action.description,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.padding(start = 6.dp),
                                ) {
                                    Text(
                                        action.category.name.lowercase(),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
