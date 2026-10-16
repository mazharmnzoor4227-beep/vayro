package com.vayro.app.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.model.WorkspaceEntry

/**
 * Context action menu for file tree items.
 */
@Composable
fun FileEntryContextMenu(
    entry: WorkspaceEntry,
    onNewFileInDir: (String) -> Unit = {},
    onNewFolderInDir: (String) -> Unit = {},
    onRename: (WorkspaceEntry) -> Unit,
    onMove: (WorkspaceEntry) -> Unit = {},
    onDuplicate: (WorkspaceEntry) -> Unit,
    onDelete: (WorkspaceEntry) -> Unit,
    onCopyPath: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    IconButton(
        onClick = { expanded = true },
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            Icons.Default.MoreVert,
            contentDescription = "Options",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        if (entry.isDirectory) {
            DropdownMenuItem(
                text = { Text("New file inside") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null, Modifier.size(18.dp)) },
                onClick = {
                    expanded = false
                    onNewFileInDir(entry.path)
                },
            )
            DropdownMenuItem(
                text = { Text("New folder inside") },
                leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null, Modifier.size(18.dp)) },
                onClick = {
                    expanded = false
                    onNewFolderInDir(entry.path)
                },
            )
        } else {
            DropdownMenuItem(
                text = { Text("Duplicate") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, Modifier.size(18.dp)) },
                onClick = {
                    expanded = false
                    onDuplicate(entry)
                },
            )
        }

        DropdownMenuItem(
            text = { Text("Rename") },
            leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null, Modifier.size(18.dp)) },
            onClick = {
                expanded = false
                onRename(entry)
            },
        )

        DropdownMenuItem(
            text = { Text("Move to...") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, Modifier.size(18.dp)) },
            onClick = {
                expanded = false
                onMove(entry)
            },
        )

        DropdownMenuItem(
            text = { Text("Copy relative path") },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, Modifier.size(18.dp)) },
            onClick = {
                expanded = false
                onCopyPath(entry.path)
            },
        )

        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
            onClick = {
                expanded = false
                onDelete(entry)
            },
        )
    }
}

/**
 * Dialog to create a new file with parent folder context.
 */
@Composable
fun CreateFileDialog(
    parentDirectory: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var fileName by rememberSaveable { mutableStateOf("") }
    val displayParent = parentDirectory?.takeIf { it.isNotBlank() } ?: "project root"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New File") },
        text = {
            // Medium: dialog content scrolls above the keyboard.
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Creating in: $displayParent",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it.trim() },
                    singleLine = true,
                    label = { Text("File name (e.g. Main.kt)") },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val fullPath = if (!parentDirectory.isNullOrBlank()) {
                        "$parentDirectory/$fileName"
                    } else {
                        fileName
                    }
                    onConfirm(fullPath)
                },
                enabled = fileName.isNotBlank() && !fileName.contains("//"),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Dialog to create a new folder with parent folder context.
 */
@Composable
fun CreateFolderDialog(
    parentDirectory: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var folderName by rememberSaveable { mutableStateOf("") }
    val displayParent = parentDirectory?.takeIf { it.isNotBlank() } ?: "project root"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Creating in: $displayParent",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it.trim() },
                    singleLine = true,
                    label = { Text("Folder name (e.g. models)") },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val fullPath = if (!parentDirectory.isNullOrBlank()) {
                        "$parentDirectory/$folderName"
                    } else {
                        folderName
                    }
                    onConfirm(fullPath)
                },
                enabled = folderName.isNotBlank() && !folderName.contains("//"),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Dialog to rename an existing file or directory.
 */
@Composable
fun RenameEntryDialog(
    entry: WorkspaceEntry,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var newName by rememberSaveable { mutableStateOf(entry.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.isDirectory) "Rename Folder" else "Rename File") },
        text = {
            // Medium: dialog content scrolls above the keyboard.
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Path: ${entry.path}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.trim() },
                    singleLine = true,
                    label = { Text("New name") },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parent = entry.path.substringBeforeLast('/', "")
                    val newRelativePath = if (parent.isNotBlank()) "$parent/$newName" else newName
                    onConfirm(newRelativePath)
                },
                enabled = newName.isNotBlank() && newName != entry.name && !newName.contains('/'),
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Dialog to confirm deletion of a file or folder.
 */
@Composable
fun DeleteEntryDialog(
    entry: WorkspaceEntry,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(if (entry.isDirectory) "Delete Folder?" else "Delete File?") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Are you sure you want to delete \"${entry.name}\"?",
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = entry.path,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.isDirectory) {
                    Text(
                        text = "This will delete all files and subdirectories inside this folder permanently.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Dialog to move a file or folder to a new path.
 */
@Composable
fun MoveEntryDialog(
    entry: WorkspaceEntry,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var targetPath by rememberSaveable { mutableStateOf(entry.path) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.isDirectory) "Move Folder" else "Move File") },
        text = {
            // Medium: dialog content scrolls above the keyboard.
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Current path: ${entry.path}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = targetPath,
                    onValueChange = { targetPath = it.trim() },
                    singleLine = true,
                    label = { Text("Target relative path") },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(targetPath) },
                enabled = targetPath.isNotBlank() && targetPath != entry.path,
            ) {
                Text("Move")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Dialog to inspect Git status, view visual color-coded diffs, switch branches, commit, push, and pull changes.
 */
@Composable
fun GitOperationsDialog(
    onDismiss: () -> Unit,
    onStatus: ((String) -> Unit) -> Unit,
    onCommit: (String, (Boolean, String) -> Unit) -> Unit,
    onCheckout: (String, Boolean, (Boolean, String) -> Unit) -> Unit,
    onDiff: ((String) -> Unit) -> Unit,
    onBranchList: ((List<String>) -> Unit) -> Unit,
    onPush: ((Boolean, String) -> Unit) -> Unit = { _ -> },
    onPull: ((Boolean, String) -> Unit) -> Unit = { _ -> },
    onOpenIssues: (() -> Unit)? = null,
) {
    var gitOutput by remember { mutableStateOf("Loading status...") }
    var commitMessage by rememberSaveable { mutableStateOf("") }
    var branchName by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Medium: the initial status load used to show no indicator —
        // the dialog just sat on "Loading status..." with dead buttons.
        loading = true
        onStatus {
            loading = false
            gitOutput = if (it.isBlank()) "Working directory clean" else it
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Git Operations") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Repository Status & Diff:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                // Medium: buttons disabled during Git work, but no spinner
                // was ever rendered — the dialog looked frozen.
                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Working…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    val lines = gitOutput.lines()
                    Column(modifier = Modifier.padding(8.dp)) {
                        lines.take(200).forEach { line ->
                            // Medium: diff greens/reds were hardcoded
                            // dark-only values, low-contrast in light mode.
                            // Theme containers pair correctly per theme.
                            val color = when {
                                line.startsWith("+++") || line.startsWith("---") -> MaterialTheme.colorScheme.onSurfaceVariant
                                line.startsWith("+") -> MaterialTheme.colorScheme.onTertiaryContainer
                                line.startsWith("-") -> MaterialTheme.colorScheme.onErrorContainer
                                line.startsWith("@@") -> MaterialTheme.colorScheme.secondary
                                line.startsWith("diff ") -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            val bg = when {
                                line.startsWith("+") && !line.startsWith("+++") -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                                line.startsWith("-") && !line.startsWith("---") -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                else -> Color.Transparent
                            }
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                color = color,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bg)
                                    .padding(vertical = 1.dp)
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            loading = true
                            onStatus {
                                loading = false
                                gitOutput = if (it.isBlank()) "Working tree clean" else it
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Status", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            loading = true
                            onDiff {
                                loading = false
                                gitOutput = if (it.isBlank()) "No diff" else it
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Diff", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            loading = true
                            onBranchList { branches ->
                                loading = false
                                gitOutput = "Branches:\n" + branches.joinToString("\n")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Branches", fontSize = 11.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            loading = true
                            onPush { success, out ->
                                loading = false
                                gitOutput = if (success) "Push completed successfully!\n$out" else "Push failed:\n$out"
                            }
                        },
                        enabled = !loading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Push", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            loading = true
                            onPull { success, out ->
                                loading = false
                                gitOutput = if (success) "Pull completed successfully!\n$out" else "Pull failed:\n$out"
                            }
                        },
                        enabled = !loading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Pull", fontSize = 11.sp)
                    }
                }

                if (onOpenIssues != null) {
                    OutlinedButton(
                        onClick = onOpenIssues,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("GitHub Issues & PRs", fontSize = 11.sp)
                    }
                }

                HorizontalDivider()

                Text("Commit Changes:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = { commitMessage = it },
                    label = { Text("Commit message") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (commitMessage.isNotBlank()) {
                            loading = true
                            onCommit(commitMessage) { success, out ->
                                loading = false
                                gitOutput = if (success) "Committed successfully!\n$out" else "Error: $out"
                                if (success) commitMessage = ""
                            }
                        }
                    },
                    enabled = commitMessage.isNotBlank() && !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Commit All Changes")
                }

                HorizontalDivider()

                Text("Branch / Checkout:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                OutlinedTextField(
                    value = branchName,
                    onValueChange = { branchName = it.trim() },
                    label = { Text("Branch name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = {
                            if (branchName.isNotBlank()) {
                                loading = true
                                onCheckout(branchName, false) { success, out ->
                                    loading = false
                                    gitOutput = if (success) "Switched to $branchName" else "Error: $out"
                                }
                            }
                        },
                        enabled = branchName.isNotBlank() && !loading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Checkout", fontSize = 11.sp)
                    }
                    Button(
                        onClick = {
                            if (branchName.isNotBlank()) {
                                loading = true
                                onCheckout(branchName, true) { success, out ->
                                    loading = false
                                    gitOutput = if (success) "Created & switched to $branchName" else "Error: $out"
                                }
                            }
                        },
                        enabled = branchName.isNotBlank() && !loading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("New Branch", fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

