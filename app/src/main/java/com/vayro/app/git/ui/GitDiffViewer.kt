package com.vayro.app.git.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.git.model.GitDiffLine
import com.vayro.app.git.model.GitDiffLineType
import com.vayro.app.git.model.GitFileDiff

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitDiffViewer(
    fileDiffs: List<GitFileDiff>,
    onClose: () -> Unit,
    title: String = "Diff Viewer"
) {
    val expandedStates = remember { mutableStateMapOf<String, Boolean>() }
    var showOnlyChanges by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        val totalAdd = fileDiffs.sumOf { it.additions }
                        val totalDel = fileDiffs.sumOf { it.deletions }
                        Text(
                            "${fileDiffs.size} files changed • +$totalAdd / -$totalDel lines",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    FilterChip(
                        selected = showOnlyChanges,
                        onClick = { showOnlyChanges = !showOnlyChanges },
                        label = { Text("Only Changes", fontSize = 11.sp) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        if (fileDiffs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("No differences detected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(fileDiffs, key = { it.newPath ?: it.oldPath ?: "unknown" }) { fileDiff ->
                    val pathKey = fileDiff.newPath ?: fileDiff.oldPath ?: "unknown"
                    val isExpanded = expandedStates[pathKey] ?: true

                    FileDiffCard(
                        diff = fileDiff,
                        expanded = isExpanded,
                        showOnlyChanges = showOnlyChanges,
                        onToggleExpand = { expandedStates[pathKey] = !isExpanded }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileDiffCard(
    diff: GitFileDiff,
    expanded: Boolean,
    showOnlyChanges: Boolean,
    onToggleExpand: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            // File Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                onClick = onToggleExpand
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = diff.newPath ?: diff.oldPath ?: "unknown",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF2E7D32).copy(alpha = 0.15f)
                    ) {
                        Text(
                            "+${diff.additions}",
                            color = Color(0xFF2E7D32),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    ) {
                        Text(
                            "-${diff.deletions}",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (expanded) {
                if (diff.isBinary) {
                    Text(
                        "Binary file not shown",
                        fontSize = 12.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier.padding(14.dp)
                    )
                } else {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(scrollState)
                    ) {
                        for (hunk in diff.hunks) {
                            // Hunk header bar
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                            ) {
                                Text(
                                    text = hunk.header,
                                    fontSize = 11.sp,
                                    fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            for (line in hunk.lines) {
                                if (showOnlyChanges && line.type == GitDiffLineType.CONTEXT) continue
                                DiffLineRow(line)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: GitDiffLine) {
    val bgColor = when (line.type) {
        GitDiffLineType.ADDITION -> Color(0xFF2E7D32).copy(alpha = 0.12f)
        GitDiffLineType.DELETION -> Color(0xFFC62828).copy(alpha = 0.12f)
        GitDiffLineType.CONTEXT, GitDiffLineType.HEADER -> Color.Transparent
    }

    val symbol = when (line.type) {
        GitDiffLineType.ADDITION -> "+"
        GitDiffLineType.DELETION -> "-"
        GitDiffLineType.CONTEXT -> " "
        GitDiffLineType.HEADER -> "@"
    }

    // Dark green / dark red diff text is unreadable on dark backgrounds:
    // light variants in dark theme, dark variants in light theme.
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColor = when (line.type) {
        GitDiffLineType.ADDITION -> if (isDark) Color(0xFFA5D6A7) else Color(0xFF1B5E20)
        GitDiffLineType.DELETION -> if (isDark) Color(0xFFEF9A9A) else Color(0xFFB71C1C)
        GitDiffLineType.CONTEXT, GitDiffLineType.HEADER -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(vertical = 1.dp)
    ) {
        // Line numbers
        Text(
            text = (line.oldLineNumber?.toString() ?: "").padStart(4),
            fontSize = 10.sp,
            fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.width(36.dp)
        )
        Text(
            text = (line.newLineNumber?.toString() ?: "").padStart(4),
            fontSize = 10.sp,
            fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.width(36.dp)
        )
        // Indicator
        Text(
            text = symbol,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
            color = textColor,
            modifier = Modifier.width(16.dp)
        )
        // Content
        Text(
            text = line.text,
            fontSize = 11.sp,
            fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily,
            color = textColor,
            softWrap = false
        )
    }
}
