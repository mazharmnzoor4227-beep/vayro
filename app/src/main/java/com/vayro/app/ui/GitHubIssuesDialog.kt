package com.vayro.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ModeComment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.network.GitHubClient
import com.vayro.app.network.GitHubIssue
import com.vayro.app.network.GitHubPullRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-App GitHub Issues & Pull Requests Viewer (Phase 10).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubIssuesBottomSheet(
    repoFullName: String,
    token: String,
    onDismiss: () -> Unit,
    onSolveWithAI: (String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Medium: the repo used to default to google/material-design-icons —
    // an unrelated project — and the only caller passes "" so the sheet
    // ALWAYS showed Google's issues. No default: blank means "ask".
    var currentRepo by remember(repoFullName) { mutableStateOf(repoFullName) }
    var selectedTab by remember { mutableStateOf(0) } // 0: Issues, 1: PRs
    var issues by remember { mutableStateOf<List<GitHubIssue>>(emptyList()) }
    var pullRequests by remember { mutableStateOf<List<GitHubPullRequest>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun loadData() {
        if (currentRepo.isBlank()) {
            // Medium: blank used to leave the spinner running forever
            // (early return with loading still true).
            loading = false
            issues = emptyList()
            pullRequests = emptyList()
            return
        }
        scope.launch {
            loading = true
            errorMessage = null
            try {
                withContext(Dispatchers.IO) {
                    val client = GitHubClient()
                    issues = client.issues(token, currentRepo.trim())
                    pullRequests = client.pullRequests(token, currentRepo.trim())
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to fetch repository issues"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(currentRepo) {
        delay(500)
        loadData()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "GitHub Issues & PRs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Browse issues & PRs or solve them directly with AI",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { loadData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(20.dp))
                    }
                }
            }

            OutlinedTextField(
                value = currentRepo,
                onValueChange = { currentRepo = it },
                label = { Text("Repository (owner/repo)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Tab Switcher
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("Issues (${issues.size})") },
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("Pull Requests (${pullRequests.size})") },
                )
            }

            if (currentRepo.isBlank()) {
                Text(
                    "Enter a repository above (owner/repo) to browse its issues and pull requests.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else if (loading) {
                Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            } else if (errorMessage != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = errorMessage ?: "",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selectedTab == 0) {
                        if (issues.isEmpty()) {
                            item {
                                Text("No open issues found in this repository.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(issues, key = { it.number }) { issue ->
                            IssueCard(
                                issue = issue,
                                onSolveWithAI = {
                                    onDismiss()
                                    onSolveWithAI("Fix GitHub Issue #${issue.number}: ${issue.title}")
                                },
                            )
                        }
                    } else {
                        if (pullRequests.isEmpty()) {
                            item {
                                Text("No pull requests found in this repository.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(pullRequests, key = { it.number }) { pr ->
                            PullRequestCard(pr = pr)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueCard(
    issue: GitHubIssue,
    onSolveWithAI: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        // Medium: hardcoded dark green/purple text on a
                        // 20% tint of itself was unreadable in dark theme.
                        // Theme containers pair correctly per theme.
                        color = if (issue.state == "open") MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    ) {
                        Text(
                            text = issue.state.uppercase(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (issue.state == "open") MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Text(
                        text = "#${issue.number}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ModeComment, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(3.dp))
                    Text(text = "${issue.comments}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text(
                text = issue.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "opened by ${issue.user}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onSolveWithAI,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("Solve with AI", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun PullRequestCard(
    pr: GitHubPullRequest,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        // Medium: hardcoded dark green/purple text on a
                        // 20% tint of itself was unreadable in dark theme.
                        // Theme containers pair correctly per theme.
                        color = if (pr.state == "open") MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    ) {
                        Text(
                            text = pr.state.uppercase(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (pr.state == "open") MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Text(
                        text = "#${pr.number}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = "${pr.baseBranch} ← ${pr.headBranch}",
                    modifier = Modifier.weight(1f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
            }

            Text(
                text = pr.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = "by ${pr.user}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
