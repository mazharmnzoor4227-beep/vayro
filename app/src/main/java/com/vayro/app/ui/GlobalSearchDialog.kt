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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.workspace.GlobalSearchEngine
import com.vayro.app.workspace.ReplacePreview
import com.vayro.app.workspace.ReplaceTransaction
import com.vayro.app.workspace.SearchMatch
import com.vayro.app.workspace.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Project-wide search and replace bottom sheet dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchDialog(
    workspaceDir: File,
    initialQuery: String = "",
    onDismiss: () -> Unit,
    onNavigateToMatch: (filePath: String, lineNumber: Int) -> Unit,
    onFilesModified: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var replacement by rememberSaveable { mutableStateOf("") }
    var showReplace by rememberSaveable { mutableStateOf(false) }
    var isRegex by rememberSaveable { mutableStateOf(false) }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    var fileFilter by rememberSaveable { mutableStateOf("") }

    var searchResult by remember { mutableStateOf<SearchResult?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    // Milestone 2 — replace preview + undo state.
    var replacePreview by remember { mutableStateOf<ReplacePreview?>(null) }
    var selectedPreviewFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var lastReplaceTransaction by remember { mutableStateOf<ReplaceTransaction?>(null) }
    val scope = rememberCoroutineScope()

    fun performSearch() {
        if (query.isBlank()) {
            searchResult = null
            return
        }
        isSearching = true
        statusMessage = null
        // A new search invalidates any replace preview.
        replacePreview = null
        lastReplaceTransaction = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                GlobalSearchEngine.search(
                    workspaceDir = workspaceDir,
                    query = query,
                    isRegex = isRegex,
                    matchCase = matchCase,
                    wholeWord = wholeWord,
                    fileFilter = fileFilter.takeIf { it.isNotBlank() },
                )
            }
            searchResult = result
            isSearching = false
        }
    }

    fun performReplacePreview() {
        if (query.isBlank()) return
        isSearching = true
        statusMessage = null
        lastReplaceTransaction = null
        scope.launch {
            val preview = withContext(Dispatchers.IO) {
                GlobalSearchEngine.previewReplace(
                    workspaceDir = workspaceDir,
                    query = query,
                    replacement = replacement,
                    isRegex = isRegex,
                    matchCase = matchCase,
                    wholeWord = wholeWord,
                    fileFilter = fileFilter.takeIf { it.isNotBlank() },
                )
            }
            replacePreview = preview
            selectedPreviewFiles = preview.filePlans.map { it.filePath }.toSet()
            isSearching = false
            if (preview.totalFiles == 0) {
                statusMessage = "No matches to replace."
            }
        }
    }

    fun applyReplacePreview() {
        val preview = replacePreview ?: return
        isSearching = true
        scope.launch {
            val transaction = withContext(Dispatchers.IO) {
                GlobalSearchEngine.applyReplacePlan(
                    workspaceDir = workspaceDir,
                    preview = preview,
                    includeFiles = selectedPreviewFiles.ifEmpty { null },
                )
            }
            lastReplaceTransaction = transaction
            replacePreview = null
            statusMessage = "Replaced ${transaction.matchesReplaced} occurrence(s) in ${transaction.filesChanged} file(s)."
            isSearching = false
            onFilesModified()
            performSearch()
        }
    }

    fun undoLastReplace() {
        val transaction = lastReplaceTransaction ?: return
        isSearching = true
        scope.launch {
            val restored = withContext(Dispatchers.IO) {
                GlobalSearchEngine.revertReplace(workspaceDir, transaction)
            }
            lastReplaceTransaction = null
            statusMessage = if (restored > 0) {
                "Undo complete: $restored file(s) restored."
            } else {
                "Nothing to undo."
            }
            isSearching = false
            onFilesModified()
            performSearch()
        }
    }

    LaunchedEffect(query, isRegex, matchCase, wholeWord, fileFilter) {
        delay(300)
        performSearch()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        SheetWindowImeGuard()
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 360.dp, max = 620.dp)
                .padding(horizontal = 16.dp)
                // Medium: the keyboard used to cover the search field.
                .imePadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Project Search & Replace",
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
                placeholder = { Text("Search everywhere...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(12.dp),
            )

            if (showReplace) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Replace with...") },
                        leadingIcon = { Icon(Icons.Default.FindReplace, contentDescription = null) },
                        shape = RoundedCornerShape(12.dp),
                    )
                    // Milestone 2: preview-before-replace + undo.
                    Button(
                        onClick = { performReplacePreview() },
                        enabled = !isSearching && query.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Preview")
                    }
                }

                // Undo for the last applied replace.
                if (lastReplaceTransaction != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val undoAvailable = lastReplaceTransaction?.preview?.undoAvailable == true
                        TextButton(
                            onClick = { undoLastReplace() },
                            enabled = !isSearching && undoAvailable,
                        ) {
                            Text(if (undoAvailable) "Undo replace" else "Undo unavailable (large change)")
                        }
                    }
                }

                // Preview: affected files with per-file toggles.
                replacePreview?.let { preview ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${preview.totalMatches} occurrence(s) in ${preview.totalFiles} file(s). Uncheck files to skip them.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    // Medium: the preview list used to be unbounded and
                    // pushed the Replace button off-screen on many matches.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        preview.filePlans.forEach { plan ->
                        val checked = plan.filePath in selectedPreviewFiles
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedPreviewFiles = if (checked) {
                                        selectedPreviewFiles - plan.filePath
                                    } else {
                                        selectedPreviewFiles + plan.filePath
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selectedPreviewFiles = if (isChecked) {
                                        selectedPreviewFiles + plan.filePath
                                    } else {
                                        selectedPreviewFiles - plan.filePath
                                    }
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = plan.filePath,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${plan.matchCount} match(es)",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { applyReplacePreview() },
                        enabled = !isSearching && selectedPreviewFiles.isNotEmpty(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Replace in ${selectedPreviewFiles.size} file(s)")
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Options filter chips
            Row(
                // Medium: chips overflowed on narrow phones with no way to
                // reach the ones off-screen.
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = matchCase,
                    onClick = { matchCase = !matchCase },
                    label = { Text("Aa", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                )
                FilterChip(
                    selected = wholeWord,
                    onClick = { wholeWord = !wholeWord },
                    label = { Text("Word", fontSize = 11.sp) },
                )
                FilterChip(
                    selected = isRegex,
                    onClick = { isRegex = !isRegex },
                    label = { Text(".*", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                )
                FilterChip(
                    selected = showReplace,
                    onClick = { showReplace = !showReplace },
                    label = { Text("Replace", fontSize = 11.sp) },
                )
                Spacer(Modifier.weight(1f))
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            if (statusMessage != null) {
                Text(
                    text = statusMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            searchResult?.let { res ->
                val summary = "${res.totalMatchesCount} matches across ${res.matches.map { it.filePath }.distinct().size} files"
                Text(
                    text = summary + if (res.isTruncated) " (truncated)" else "",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(4.dp))

            val matches = searchResult?.matches.orEmpty()
            if (matches.isEmpty() && query.isNotBlank() && !isSearching) {
                Text(
                    "No matches found.",
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(matches, key = { "${it.filePath}:${it.lineNumber}:${it.matchStart}" }) { match ->
                        Surface(
                            onClick = {
                                onDismiss()
                                onNavigateToMatch(match.filePath, match.lineNumber)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = match.filePath,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = ":${match.lineNumber}",
                                        fontSize = 11.sp,
                                        fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = match.lineContent,
                                    fontSize = 11.sp,
                                    fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
