package com.vayro.app.git.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.git.diff.GitDiffParser
import com.vayro.app.git.model.GitBranchInfo
import com.vayro.app.git.model.GitCommitInfo
import com.vayro.app.git.model.GitFileStatus
import com.vayro.app.git.model.GitResetMode
import com.vayro.app.git.model.GitStatusInfo
import com.vayro.app.git.workflow.WorkflowExecutionState
import com.vayro.app.git.workflow.WorkflowStage
import com.vayro.app.git.workflow.WorkflowStepStatus
import com.vayro.app.network.GitHubIssue
import com.vayro.app.network.GitHubPullRequest
import com.vayro.app.network.GitHubRelease
import com.vayro.app.network.GitHubWorkflowRun
import com.vayro.app.network.PullRequestMergeMethod

private enum class GitStudioTab(val title: String, val icon: ImageVector) {
    STAGING("Staging & Changes", Icons.Default.Code),
    BRANCHES("Branches & Remotes", Icons.Default.CallMerge),
    COMMITS("Commit History", Icons.Default.History),
    GITHUB("GitHub Hub", Icons.Default.Share),
    AUTONOMOUS("Autonomous Issue-to-PR", Icons.Default.AutoAwesome)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubStudioScreen(
    statusInfo: GitStatusInfo,
    branches: List<GitBranchInfo>,
    commits: List<GitCommitInfo>,
    issues: List<GitHubIssue>,
    pullRequests: List<GitHubPullRequest>,
    workflowRuns: List<GitHubWorkflowRun>,
    releases: List<GitHubRelease>,
    workflowState: WorkflowExecutionState,
    connectedRepo: String?,
    rawDiffOutput: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onStageFile: (String) -> Unit,
    onStageAll: () -> Unit,
    onUnstageFile: (String) -> Unit,
    onUnstageAll: () -> Unit,
    onDiscardFile: (String) -> Unit,
    onDiscardAll: () -> Unit,
    onCommit: (message: String, amend: Boolean, signOff: Boolean) -> Unit,
    onCreateBranch: (branch: String) -> Unit,
    onCheckoutBranch: (branch: String) -> Unit,
    onDeleteBranch: (branch: String) -> Unit,
    onMergeBranch: (branch: String) -> Unit,
    onRebaseBranch: (upstream: String) -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit,
    onResetToCommit: (hash: String, mode: GitResetMode) -> Unit,
    onCreateIssue: (title: String, body: String) -> Unit,
    onMergePullRequest: (prNumber: Int, method: PullRequestMergeMethod) -> Unit,
    onStartAutonomousWorkflow: (issue: GitHubIssue) -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var viewingDiff by rememberSaveable { mutableStateOf(false) }

    if (viewingDiff) {
        val fileDiffs = remember(rawDiffOutput) { GitDiffParser.parse(rawDiffOutput) }
        GitDiffViewer(
            fileDiffs = fileDiffs,
            onClose = { viewingDiff = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("GitHub Studio", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    statusInfo.currentBranch,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = connectedRepo ?: "Local Repository",
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
                actions = {
                    IconButton(onClick = { viewingDiff = true }) {
                        Icon(Icons.Default.Code, contentDescription = "View Diff")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 12.dp
            ) {
                GitStudioTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tab.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        icon = { Icon(tab.icon, contentDescription = tab.title, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            when (GitStudioTab.entries[selectedTab]) {
                GitStudioTab.STAGING -> StagingTab(
                    statusInfo = statusInfo,
                    onStageFile = onStageFile,
                    onStageAll = onStageAll,
                    onUnstageFile = onUnstageFile,
                    onUnstageAll = onUnstageAll,
                    onDiscardFile = onDiscardFile,
                    onDiscardAll = onDiscardAll,
                    onCommit = onCommit,
                    onViewDiff = { viewingDiff = true }
                )
                GitStudioTab.BRANCHES -> BranchesTab(
                    branches = branches,
                    currentBranch = statusInfo.currentBranch,
                    onCreateBranch = onCreateBranch,
                    onCheckoutBranch = onCheckoutBranch,
                    onDeleteBranch = onDeleteBranch,
                    onMergeBranch = onMergeBranch,
                    onRebaseBranch = onRebaseBranch,
                    onPush = onPush,
                    onPull = onPull
                )
                GitStudioTab.COMMITS -> CommitsTab(
                    commits = commits,
                    onResetToCommit = onResetToCommit
                )
                GitStudioTab.GITHUB -> GitHubHubTab(
                    repo = connectedRepo,
                    issues = issues,
                    pullRequests = pullRequests,
                    workflowRuns = workflowRuns,
                    releases = releases,
                    onCreateIssue = onCreateIssue,
                    onMergePullRequest = onMergePullRequest
                )
                GitStudioTab.AUTONOMOUS -> AutonomousWorkflowTab(
                    issues = issues,
                    workflowState = workflowState,
                    onStartWorkflow = onStartAutonomousWorkflow
                )
            }
        }
    }
}

@Composable
private fun StagingTab(
    statusInfo: GitStatusInfo,
    onStageFile: (String) -> Unit,
    onStageAll: () -> Unit,
    onUnstageFile: (String) -> Unit,
    onUnstageAll: () -> Unit,
    onDiscardFile: (String) -> Unit,
    onDiscardAll: () -> Unit,
    onCommit: (String, Boolean, Boolean) -> Unit,
    onViewDiff: () -> Unit
) {
    var commitMessage by rememberSaveable { mutableStateOf("") }
    var amend by rememberSaveable { mutableStateOf(false) }
    var signOff by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Commit Box
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Commit Composer", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it },
                        label = { Text("Commit message") },
                        placeholder = { Text("feat: add new feature...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = amend, onCheckedChange = { amend = it })
                        Text("Amend", fontSize = 12.sp)
                        Spacer(Modifier.width(16.dp))
                        Checkbox(checked = signOff, onCheckedChange = { signOff = it })
                        Text("Sign-off (-s)", fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                if (commitMessage.isNotBlank()) {
                                    onCommit(commitMessage, amend, signOff)
                                    commitMessage = ""
                                }
                            },
                            enabled = commitMessage.isNotBlank() || amend,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Commit")
                        }
                        OutlinedButton(onClick = onViewDiff) {
                            Text("Diff")
                        }
                    }
                }
            }
        }

        // Staged Files Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Staged Changes (${statusInfo.stagedFiles.size})", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                if (statusInfo.stagedFiles.isNotEmpty()) {
                    TextButton(onClick = onUnstageAll) {
                        Text("Unstage All", fontSize = 11.5.sp)
                    }
                }
            }
        }

        if (statusInfo.stagedFiles.isEmpty()) {
            item {
                Text("No changes staged.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(statusInfo.stagedFiles) { file ->
                FileRowItem(
                    file = file,
                    actionLabel = "Unstage",
                    onAction = { onUnstageFile(file.path) }
                )
            }
        }

        // Unstaged & Untracked Files Section
        val unstagedTotal = statusInfo.unstagedFiles.size + statusInfo.untrackedFiles.size
        item {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Unstaged Changes ($unstagedTotal)", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                if (unstagedTotal > 0) {
                    Row {
                        TextButton(onClick = onStageAll) {
                            Text("Stage All", fontSize = 11.5.sp)
                        }
                        TextButton(onClick = onDiscardAll) {
                            Text("Discard All", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        val worktreeFiles = statusInfo.unstagedFiles + statusInfo.untrackedFiles
        if (worktreeFiles.isEmpty()) {
            item {
                Text("Working tree clean.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(worktreeFiles) { file ->
                FileRowItem(
                    file = file,
                    actionLabel = "Stage",
                    onAction = { onStageFile(file.path) },
                    onSecondaryAction = { onDiscardFile(file.path) }
                )
            }
        }
    }
}

@Composable
private fun FileRowItem(
    file: GitFileStatus,
    actionLabel: String,
    onAction: () -> Unit,
    onSecondaryAction: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = when {
                    file.isConflict -> MaterialTheme.colorScheme.error
                    file.isStaged -> Color(0xFF2E7D32)
                    else -> MaterialTheme.colorScheme.primary
                }
            ) {
                Text(
                    text = if (file.isStaged) file.indexStatus.code else file.worktreeStatus.code,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(file.path, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))

            TextButton(onClick = onAction) {
                Text(actionLabel, fontSize = 11.sp)
            }
            if (onSecondaryAction != null) {
                IconButton(onClick = onSecondaryAction, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Discard", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun BranchesTab(
    branches: List<GitBranchInfo>,
    currentBranch: String,
    onCreateBranch: (String) -> Unit,
    onCheckoutBranch: (String) -> Unit,
    onDeleteBranch: (String) -> Unit,
    onMergeBranch: (String) -> Unit,
    onRebaseBranch: (String) -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit
) {
    var newBranchName by rememberSaveable { mutableStateOf("") }
    var selectedBranchForAction by remember { mutableStateOf<GitBranchInfo?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onPull, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pull")
                }
                Button(onClick = onPush, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Push")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newBranchName,
                        onValueChange = { newBranchName = it },
                        label = { Text("New branch name") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newBranchName.isNotBlank()) {
                                onCreateBranch(newBranchName.trim())
                                newBranchName = ""
                            }
                        },
                        enabled = newBranchName.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                }
            }
        }

        item {
            Text("Repository Branches (${branches.size})", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
        }

        items(branches) { branch ->
            val isCurrent = branch.isCurrent || branch.name == currentBranch
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CallMerge,
                        contentDescription = null,
                        tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(branch.name, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp, color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                            if (isCurrent) {
                                Spacer(Modifier.width(6.dp))
                                Text("(current)", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (!branch.latestCommitSubject.isNullOrBlank()) {
                            Text(branch.latestCommitSubject, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }

                    if (!isCurrent) {
                        TextButton(onClick = { onCheckoutBranch(branch.name) }) {
                            Text("Switch", fontSize = 11.sp)
                        }
                        IconButton(onClick = { onMergeBranch(branch.name) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.CallMerge, contentDescription = "Merge", modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { onDeleteBranch(branch.name) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitsTab(
    commits: List<GitCommitInfo>,
    onResetToCommit: (hash: String, mode: GitResetMode) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var selectedCommitForReset by remember { mutableStateOf<GitCommitInfo?>(null) }

    val commitForReset = selectedCommitForReset
    if (commitForReset != null) {
        val target = commitForReset
        AlertDialog(
            onDismissRequest = { selectedCommitForReset = null },
            title = { Text("Reset Branch to ${target.shortHash}") },
            text = { Text("Choose reset mode to move HEAD to commit: ${target.message.take(60)}...") },
            confirmButton = {
                Button(
                    onClick = {
                        onResetToCommit(target.hash, GitResetMode.SOFT)
                        selectedCommitForReset = null
                    }
                ) {
                    Text("Soft")
                }
            },
            dismissButton = {
                Row {
                    OutlinedButton(
                        onClick = {
                            onResetToCommit(target.hash, GitResetMode.MIXED)
                            selectedCommitForReset = null
                        }
                    ) {
                        Text("Mixed")
                    }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = {
                            onResetToCommit(target.hash, GitResetMode.HARD)
                            selectedCommitForReset = null
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Hard")
                    }
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Commit Log (${commits.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        items(commits, key = { it.hash }) { commit ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    commit.shortHash,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(commit.authorName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(commit.formattedDate, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Text(commit.message, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(commit.hash)) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Hash", modifier = Modifier.size(16.dp))
                        }
                        TextButton(onClick = { selectedCommitForReset = commit }) {
                            Text("Reset Here", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitHubHubTab(
    repo: String?,
    issues: List<GitHubIssue>,
    pullRequests: List<GitHubPullRequest>,
    workflowRuns: List<GitHubWorkflowRun>,
    releases: List<GitHubRelease>,
    onCreateIssue: (String, String) -> Unit,
    onMergePullRequest: (Int, PullRequestMergeMethod) -> Unit
) {
    var subTab by rememberSaveable { mutableIntStateOf(0) }
    val subTabs = listOf("Issues (${issues.size})", "PRs (${pullRequests.size})", "Actions (${workflowRuns.size})", "Releases (${releases.size})")

    Column(Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = subTab) {
            subTabs.forEachIndexed { idx, title ->
                Tab(
                    selected = subTab == idx,
                    onClick = { subTab = idx },
                    text = { Text(title, fontSize = 11.5.sp) }
                )
            }
        }

        when (subTab) {
            0 -> IssuesSection(issues, onCreateIssue)
            1 -> PullRequestsSection(pullRequests, onMergePullRequest)
            2 -> ActionsSection(workflowRuns)
            3 -> ReleasesSection(releases)
        }
    }
}

@Composable
private fun IssuesSection(issues: List<GitHubIssue>, onCreateIssue: (String, String) -> Unit) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newBody by remember { mutableStateOf("") }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Open New GitHub Issue") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newBody,
                        onValueChange = { newBody = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newTitle.isNotBlank()) {
                            onCreateIssue(newTitle, newBody)
                            showCreateDialog = false
                            newTitle = ""
                            newBody = ""
                        }
                    }
                ) {
                    Text("Submit Issue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Button(onClick = { showCreateDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("New Issue")
            }
        }

        items(issues, key = { it.number }) { issue ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (issue.state == "open") Color(0xFF2E7D32) else Color.Gray
                        ) {
                            Text(
                                "#${issue.number} ${issue.state.uppercase()}",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(issue.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    if (issue.body.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(issue.body, fontSize = 11.5.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PullRequestsSection(
    prs: List<GitHubPullRequest>,
    onMergePullRequest: (Int, PullRequestMergeMethod) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(prs, key = { it.number }) { pr ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("#${pr.number} ${pr.title}", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF1565C0)
                        ) {
                            Text(pr.state.uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                    }
                    Text("${pr.headBranch} → ${pr.baseBranch}", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)

                    if (pr.state == "open") {
                        Button(
                            onClick = { onMergePullRequest(pr.number, PullRequestMergeMethod.SQUASH) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("1-Tap Squash & Merge")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionsSection(runs: List<GitHubWorkflowRun>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(runs, key = { it.id }) { run ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (run.conclusion == "success") Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (run.conclusion == "success") Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${run.name} #${run.runNumber}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${run.event} on ${run.headBranch} • ${run.status} (${run.conclusion ?: "running"})", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleasesSection(releases: List<GitHubRelease>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(releases) { rel ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(rel.name, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    Text("Tag: ${rel.tagName} • ${rel.publishedAt}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (rel.body.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(rel.body, fontSize = 11.5.sp, maxLines = 3)
                    }
                }
            }
        }
    }
}

@Composable
private fun AutonomousWorkflowTab(
    issues: List<GitHubIssue>,
    workflowState: WorkflowExecutionState,
    onStartWorkflow: (GitHubIssue) -> Unit
) {
    var selectedIssue by remember { mutableStateOf<GitHubIssue?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Autonomous Issue-to-PR Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Executes the full cycle: Issue → Understand → Branch → Implement → Build → Test → Fix → Commit → Push → PR",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!workflowState.isRunning && workflowState.currentStage != WorkflowStage.COMPLETED) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Select Target Issue", fontWeight = FontWeight.Bold)
                        if (issues.isEmpty()) {
                            Text("No open issues found.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            issues.filter { it.state == "open" }.forEach { issue ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedIssue = issue },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selectedIssue?.number == issue.number) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                ) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("#${issue.number} ${issue.title}", fontSize = 12.5.sp, color = if (selectedIssue?.number == issue.number) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                        if (selectedIssue?.number == issue.number) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = { selectedIssue?.let { onStartWorkflow(it) } },
                            enabled = selectedIssue != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(Icons.Default.RocketLaunch, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Start Autonomous Issue Fix")
                        }
                    }
                }
            }
        }

        if (workflowState.steps.isNotEmpty()) {
            item {
                Text("Autonomous Execution Pipeline", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            items(workflowState.steps) { step ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when (step.status) {
                            WorkflowStepStatus.SUCCESS -> Color(0xFF2E7D32).copy(alpha = 0.12f)
                            WorkflowStepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            WorkflowStepStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surface
                        },
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (step.status) {
                            WorkflowStepStatus.IN_PROGRESS -> CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            WorkflowStepStatus.SUCCESS -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                            WorkflowStepStatus.FAILED -> Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            else -> Box(modifier = Modifier.size(18.dp).background(Color.Gray.copy(alpha = 0.3f), CircleShape))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(step.title, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(step.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (workflowState.prUrl != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF2E7D32).copy(alpha = 0.15f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("🎉 Autonomous Workflow Completed!", fontWeight = FontWeight.Bold, color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFA5D6A7) else Color(0xFF2E7D32))
                        Text("Pull Request: ${workflowState.prUrl}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
