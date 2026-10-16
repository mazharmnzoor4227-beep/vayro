@file:OptIn(ExperimentalMaterial3Api::class)

package com.vayro.app.agent.team.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.agent.team.AgentTeamOrchestrator
import com.vayro.app.agent.team.model.ArtifactType
import com.vayro.app.agent.team.model.ReviewVerdict
import com.vayro.app.agent.team.model.TeamArtifact
import com.vayro.app.agent.team.model.TeamMessage
import com.vayro.app.agent.team.model.TeamMessageType
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.model.TeamRolePermissions
import com.vayro.app.agent.team.model.TeamTask
import com.vayro.app.agent.team.model.TeamTaskPriority
import com.vayro.app.agent.team.model.TeamTaskStatus
import com.vayro.app.agent.team.runtime.LiveTaskInfo
import com.vayro.app.agent.team.runtime.LiveTaskStatus
import kotlinx.coroutines.launch

/**
 * Multi-Agent Engineering Studio Dashboard.
 * 5-tab visual studio covering Roster, Task DAG, Inter-Agent Comms, Shared Blackboard, and Review/Budget.
 */
@Composable
fun TeamStudioScreen(
    orchestrator: AgentTeamOrchestrator,
    onClose: () -> Unit = {}
) {
    val sessionState by orchestrator.sessionState.collectAsState()
    val liveTasks by orchestrator.liveTasks.collectAsState()
    val throttle by orchestrator.throttleState.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var inputGoal by remember { mutableStateOf(sessionState.activeGoal.ifBlank { "Build robust offline mobile feature with testing and CI" }) }

    val tabs = listOf("Roster", "Task DAG", "Comms", "Blackboard", "Reviews")

    // Antigravity-style header status: parallel count wins over single-role text.
    // When the adaptive scheduler throttles, the reason is shown inline.
    val runningLive = liveTasks.values.filter { it.status == LiveTaskStatus.RUNNING }
    val headerStatusText: String? = when {
        runningLive.isNotEmpty() -> {
            val countText = if (runningLive.size == 1) "1 agent running" else "${runningLive.size} agents running"
            val detail = if (runningLive.size == 1) "Executing: ${runningLive.first().role.title}" else countText
            val reason = throttle.throttleReason
            if (reason != null) "$countText ($reason)" else detail
        }
        sessionState.isRunning -> sessionState.activeRole?.let { "Executing: ${it.title}" } ?: "Running"
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Surface(
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Groups,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Multi-Agent Engineering Studio",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "10 Specialized Autonomous Agents",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            headerStatusText?.let { statusText ->
                                Text(
                                    text = statusText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Goal Input & Action Bar.
                // Plan creates the 10-task DAG + blueprint AND immediately
                // runs the whole team: one click must do the full autonomous
                // run (agents work, report, publish artifacts), not just
                // leave a task list sitting at 0/10. It used to also fire the
                // goal into the single-agent chat as a hidden background task
                // with a doubled prefix, which is gone.
                // The input gets its own full-width row: sharing one row with
                // Plan/Step/Stop squeezed it to "Build robu..." on narrow phones.
                OutlinedTextField(
                    value = inputGoal,
                    onValueChange = { inputGoal = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    singleLine = true,
                    placeholder = { Text("Enter engineering goal...", fontSize = 12.sp) },
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (inputGoal.isNotBlank()) {
                                scope.launch {
                                    if (sessionState.isRunning) orchestrator.cancelRealRun()
                                    orchestrator.planProject(inputGoal)
                                    runCatching { orchestrator.autoRunAllReal() }
                                        .onFailure { orchestrator.reportError(it.message ?: "Plan run failed.") }
                                }
                            }
                        },
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Plan", fontSize = 12.sp)
                    }

                    // Step runs the next READY task. It is shown whenever a
                    // READY task exists, not only while isRunning: after a
                    // failure the run stops (isRunning=false) but a retried
                    // task becomes READY again and must stay runnable.
                    if (sessionState.tasks.any { it.status == TeamTaskStatus.READY }) {
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching { orchestrator.executeNextReadyTask() }
                                        .onFailure { orchestrator.reportError(it.message ?: "Step failed.") }
                                }
                            },
                            modifier = Modifier.height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Step", fontSize = 12.sp)
                        }
                    }
                    if (sessionState.isRunning) {
                        OutlinedButton(
                            onClick = { orchestrator.cancelRealRun() },
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text("Stop", fontSize = 12.sp)
                        }
                    }
                    // A failed task blocks the whole DAG (dependents stay
                    // BLOCKED, Step becomes a dead button). Retry re-queues
                    // the failed tasks so the run can continue after a fix.
                    if (sessionState.tasks.any { it.status == TeamTaskStatus.FAILED }) {
                        OutlinedButton(
                            onClick = { orchestrator.retryFailedTasks() },
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Retry failed", fontSize = 12.sp)
                        }
                    }
                }

                // Execution error banner: a failed run used to go completely
                // silent (Roster showed no change, header kept "Executing").
                sessionState.lastError?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFFEBEE),
                        border = BorderStroke(1.dp, Color(0xFFE57373)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = error,
                                fontSize = 11.sp,
                                color = Color(0xFFB71C1C)
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 6.dp)
                            ) {
                                // Medium: this banner used to carry its own
                                // "Retry failed" button next to the identical
                                // one in the header row above. The header
                                // button is the single retry entry point.
                                OutlinedButton(
                                    onClick = { orchestrator.clearLastError() },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Dismiss", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Navigation Tabs
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        // Tab Content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            when (selectedTab) {
                0 -> TeamRosterTab(orchestrator = orchestrator)
                1 -> TeamDagTab(orchestrator = orchestrator)
                2 -> TeamCommsTab(orchestrator = orchestrator)
                3 -> TeamBlackboardTab(orchestrator = orchestrator)
                4 -> TeamReviewBudgetTab(orchestrator = orchestrator)
            }
        }
    }
}

/**
 * Tab 1: Team Roster with all 10 specialized agent roles.
 */
@Composable
private fun TeamRosterTab(orchestrator: AgentTeamOrchestrator) {
    val sessionState by orchestrator.sessionState.collectAsState()
    val liveTasks by orchestrator.liveTasks.collectAsState()
    val usages = orchestrator.budgetTracker.getAllRoleUsages()
    var selectedRole by remember { mutableStateOf<TeamRole?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(TeamRole.entries) { role ->
            val isActive = sessionState.activeRole == role
            // Latest live task for this role (running now or most recent finished).
            val live = liveTasks.values
                .filter { it.role == role }
                .maxByOrNull { it.startedAtMs }
            val isRunning = live?.status == LiveTaskStatus.RUNNING
            val highlighted = isActive || isRunning
            val permissions = TeamRolePermissions.forRole(role)
            val usage = usages[role]

            Card(
                onClick = { selectedRole = role },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    width = if (highlighted) 1.5.dp else 1.dp,
                    color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                colors = CardDefaults.cardColors(
                    // Explicit content color: contentColorFor(primaryContainer)
                    // resolves to onPrimaryContainer (dark in dark theme), so
                    // the highlighted card's title rendered dark-on-dark and
                    // was unreadable. The container here is 85% transparent,
                    // so onSurface is the only readable choice.
                    containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = when (role) {
                                            TeamRole.MANAGER -> Icons.Default.Groups
                                            TeamRole.ARCHITECT -> Icons.Default.AccountTree
                                            TeamRole.SECURITY_REVIEWER -> Icons.Default.Security
                                            else -> Icons.Default.Engineering
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = role.title,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (isRunning) PulsingDot()
                                }
                                if (isRunning && live != null) {
                                    Text(
                                        text = live.taskTitle,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                } else {
                                    Text(
                                        text = role.name,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = when {
                                isRunning -> Color(0xFF2E7D32)
                                isActive -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                text = when {
                                    isRunning -> "RUNNING"
                                    isActive -> "ACTIVE"
                                    else -> "IDLE"
                                },
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isRunning || isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = role.description,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(8.dp))
                    // Permission Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (permissions.canWriteCode) ChipTag("Code Edit")
                        if (permissions.canRunTerminal) ChipTag("Terminal")
                        if (permissions.canAccessNetwork) ChipTag("Network")
                        if (permissions.canCommitGit) ChipTag("Git Release")
                        if (permissions.canApproveReviews) ChipTag("Review Sign-off")
                    }

                    if (usage != null && usage.tokensUsed > 0L) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Used: ${usage.tokensUsed} tokens • Tasks: ${usage.tasksCompleted} • Cost: $${usage.costUsd}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    // Antigravity-style drill-down: tap a role to inspect its live task.
    val role = selectedRole
    if (role != null) {
        val live = liveTasks.values
            .filter { it.role == role }
            .maxByOrNull { it.startedAtMs }
        ModalBottomSheet(
            onDismissRequest = { selectedRole = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            AgentDetailSheet(
                role = role,
                live = live,
                orchestrator = orchestrator
            )
        }
    }
}

/**
 * Pulsing green dot marking a role that is executing right now.
 */
@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "livePulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(Color(0xFF2E7D32), CircleShape)
    )
}

/**
 * Bottom-sheet drill-down for one agent role: current or last task, live
 * streaming output, stats, and produced artifacts.
 */
@Composable
private fun AgentDetailSheet(
    role: TeamRole,
    live: LiveTaskInfo?,
    orchestrator: AgentTeamOrchestrator
) {
    val sessionState by orchestrator.sessionState.collectAsState()
    val usages = orchestrator.budgetTracker.getAllRoleUsages()
    val usage = usages[role]
    val outputText = live?.outputTail.orEmpty()
    val scrollState = rememberScrollState()
    // QA H6: the outer sheet column gets its OWN scroll state. The inner
    // live-output box keeps `scrollState` (with its auto-pin-to-bottom
    // effect); sharing one ScrollState between two scrollables would fight.
    val sheetScrollState = rememberScrollState()
    val isRunning = live?.status == LiveTaskStatus.RUNNING

    // Keep the live output pinned to the bottom while it streams.
    LaunchedEffect(outputText.length) {
        if (scrollState.maxValue > 0) scrollState.scrollTo(scrollState.maxValue)
    }

    val roleArtifacts = remember(sessionState.artifacts, role) {
        sessionState.artifacts.filter { it.authorRole == role }.takeLast(5)
    }
    val statusLabel = when (live?.status) {
        LiveTaskStatus.RUNNING -> "Running now"
        LiveTaskStatus.COMPLETED -> "Last task completed"
        LiveTaskStatus.FAILED -> "Last task failed"
        null -> "Idle"
    }
    val durationMs = live?.let {
        if (it.status == LiveTaskStatus.RUNNING) System.currentTimeMillis() - it.startedAtMs else it.durationMs
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(sheetScrollState)
            .navigationBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when (role) {
                            TeamRole.MANAGER -> Icons.Default.Groups
                            TeamRole.ARCHITECT -> Icons.Default.AccountTree
                            TeamRole.SECURITY_REVIEWER -> Icons.Default.Security
                            else -> Icons.Default.Engineering
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = role.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isRunning) PulsingDot()
                }
                Text(
                    text = statusLabel,
                    fontSize = 11.sp,
                    color = if (isRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                // Which engine actually ran it: when the role's wired engine
                // was unusable, a fallback (e.g. Antigravity for a Claude
                // Code task) ran it instead. Shown so the fallback is visible.
                live?.agentKind?.let { engine ->
                    Text(
                        text = "via ${engine.title}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        if (live != null) {
            Text(
                text = if (isRunning) "Current task" else "Last task",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = live.taskTitle,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (live.taskDescription.isNotBlank()) {
                Text(
                    text = live.taskDescription,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Live output",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            if (outputText.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = outputText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(scrollState)
                            .padding(10.dp)
                    )
                }
            } else {
                Text(
                    text = if (isRunning) "Waiting for the first output..." else "No text output was produced.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                AgentStat("Tokens", live.tokensUsed?.toString() ?: usage?.tokensUsed?.toString() ?: "-")
                AgentStat("Duration", durationMs?.let { formatAgentDuration(it) } ?: "-")
                AgentStat("Tasks done", usage?.tasksCompleted?.toString() ?: "0")
            }
        } else {
            Text(
                text = "No task has run on this agent yet. Start a team run to see it work live here.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (roleArtifacts.isNotEmpty()) {
            Text(
                text = "Artifacts (${roleArtifacts.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            roleArtifacts.forEach { artifact ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Article,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artifact.title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = artifact.type.label,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun AgentStat(label: String, value: String) {
    Column {
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatAgentDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return when {
        totalSeconds < 60 -> "${totalSeconds}s"
        totalSeconds < 3600 -> "${totalSeconds / 60}m ${totalSeconds % 60}s"
        else -> "${totalSeconds / 3600}h ${(totalSeconds % 3600) / 60}m"
    }
}

/**
 * Tab 2: Task Dependency DAG & Kanban State.
 */
@Composable
private fun TeamDagTab(orchestrator: AgentTeamOrchestrator) {
    val sessionState by orchestrator.sessionState.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Summary Header & Action Bar: title on its own full-width row,
        // buttons on a row below. Cramming label + buttons into one Row
        // squeezed the label and the buttons on narrow phones.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "DAG Execution Pipeline (${sessionState.tasks.count { it.status == TeamTaskStatus.COMPLETED }}/${sessionState.tasks.size} Completed)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            runCatching { orchestrator.executeNextReadyTask() }
                                .onFailure { orchestrator.reportError(it.message ?: "Next Ready failed.") }
                        }
                    },
                    // Medium: this used to stay enabled with zero READY
                    // tasks — a dead tap that reported a confusing error.
                    enabled = sessionState.tasks.any { it.status == TeamTaskStatus.READY },
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        // QA H5: near-black text on theme primary was unreadable
                        // where primary is dark. onPrimary is the theme's
                        // guaranteed-contrast pairing.
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Next Ready", fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching { orchestrator.autoRunAllReal() }
                                .onFailure { orchestrator.reportError(it.message ?: "Auto Run failed.") }
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Auto Run", fontSize = 11.sp)
                }
                if (sessionState.tasks.any { it.status == TeamTaskStatus.FAILED }) {
                    OutlinedButton(
                        onClick = { orchestrator.retryFailedTasks() },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Retry failed", fontSize = 11.sp)
                    }
                }
            }
        }

        // Conflict Warning Banner if any
        if (sessionState.conflictWarnings.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFFFF3E0),
                border = BorderStroke(1.dp, Color(0xFFFFB74D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF57C00), modifier = Modifier.size(18.dp))
                    Text(
                        text = sessionState.conflictWarnings.first(),
                        fontSize = 11.sp,
                        color = Color(0xFFE65100)
                    )
                }
            }
        }

        // Task List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (sessionState.tasks.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No tasks yet",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Describe your goal above. Agents will break it into tasks here.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            items(sessionState.tasks, key = { it.id }) { task ->
                TaskDagCard(
                    task = task,
                    onToggleStatus = {
                        val next = when (task.status) {
                            TeamTaskStatus.PENDING -> TeamTaskStatus.READY
                            TeamTaskStatus.READY -> TeamTaskStatus.IN_PROGRESS
                            TeamTaskStatus.IN_PROGRESS -> TeamTaskStatus.COMPLETED
                            TeamTaskStatus.UNDER_REVIEW -> TeamTaskStatus.COMPLETED
                            TeamTaskStatus.COMPLETED -> TeamTaskStatus.READY
                            TeamTaskStatus.BLOCKED -> TeamTaskStatus.READY
                            TeamTaskStatus.FAILED -> TeamTaskStatus.READY
                        }
                        orchestrator.updateTaskStatus(task.id, next)
                    }
                )
            }
        }
    }
}

@Composable
private fun TaskDagCard(
    task: TeamTask,
    onToggleStatus: () -> Unit
) {
    // Theme-aware status containers with their matching on-colors. The old
    // hardcoded light containers (0xFFE8F5E9 / 0xFFE3F2FD / 0xFFFFEBEE) plus
    // a color-less title made contentColorFor resolve the title to BLACK on
    // dark backgrounds. Every text below carries an explicit color so no
    // text on a dark background can ever render black/dark again.
    val containerColor = when (task.status) {
        TeamTaskStatus.COMPLETED -> MaterialTheme.colorScheme.tertiaryContainer
        TeamTaskStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primaryContainer
        TeamTaskStatus.READY -> MaterialTheme.colorScheme.secondaryContainer
        TeamTaskStatus.BLOCKED, TeamTaskStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (task.status) {
        TeamTaskStatus.COMPLETED -> MaterialTheme.colorScheme.onTertiaryContainer
        TeamTaskStatus.IN_PROGRESS -> MaterialTheme.colorScheme.onPrimaryContainer
        TeamTaskStatus.READY -> MaterialTheme.colorScheme.onSecondaryContainer
        TeamTaskStatus.BLOCKED, TeamTaskStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = task.assignedRole.name,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    if (task.priority == TeamTaskPriority.CRITICAL) {
                        Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFD32F2F)) {
                            Text("CRITICAL", fontSize = 8.sp, color = Color.White, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                }

                IconButton(onClick = onToggleStatus, modifier = Modifier.size(48.dp)) {
                    val toggleDescription = "Task status: ${task.status.name.lowercase().replace('_', ' ')}. Activate to advance status."
                    when (task.status) {
                        TeamTaskStatus.COMPLETED -> Icon(Icons.Default.CheckCircle, contentDescription = toggleDescription, tint = contentColor)
                        TeamTaskStatus.IN_PROGRESS -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = contentColor)
                        TeamTaskStatus.READY -> Icon(Icons.Default.PlayArrow, contentDescription = toggleDescription, tint = contentColor)
                        else -> Icon(Icons.Default.HourglassEmpty, contentDescription = toggleDescription, tint = contentColor.copy(alpha = 0.7f))
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(text = task.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = contentColor)
            Text(text = task.description, fontSize = 11.sp, color = contentColor.copy(alpha = 0.8f))

            // FAILED tasks show their real failure reason inline: previously
            // the cause (e.g. "runtime not installed") only existed in the
            // Comms tab, so the DAG looked dead for no visible reason.
            if (task.status == TeamTaskStatus.FAILED && task.output.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = task.output.take(400),
                    fontSize = 11.sp,
                    color = contentColor
                )
            }

            if (task.affectedFiles.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    task.affectedFiles.forEach { file ->
                        Surface(shape = RoundedCornerShape(4.dp), color = contentColor.copy(alpha = 0.15f)) {
                            Text(file, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = contentColor, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tab 3: Inter-Agent Communication Bus.
 */
@Composable
private fun TeamCommsTab(orchestrator: AgentTeamOrchestrator) {
    val sessionState by orchestrator.sessionState.collectAsState()
    var filterRole by remember { mutableStateOf<TeamRole?>(null) }
    var inputMessage by remember { mutableStateOf("") }

    val filteredMessages = sessionState.messages.filter { msg ->
        filterRole == null || msg.fromRole == filterRole || msg.toRole == filterRole
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Role Filter Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = filterRole == null,
                onClick = { filterRole = null },
                label = { Text("All Messages", fontSize = 11.sp) }
            )
            TeamRole.entries.forEach { role ->
                FilterChip(
                    selected = filterRole == role,
                    onClick = { filterRole = if (filterRole == role) null else role },
                    label = { Text(role.title.substringBefore(' '), fontSize = 11.sp) }
                )
            }
        }

        // Messages List
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Medium: no empty state — a new run used to show a blank list.
            if (filteredMessages.isEmpty()) {
                item {
                    Text(
                        text = if (filterRole != null) "No messages from this role yet." else "No messages yet. Agent chatter will appear here once the run starts.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            items(filteredMessages, key = { it.id }) { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${msg.fromRole.name} → ${msg.toRole?.name ?: "TEAM"}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(msg.type.name, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(text = msg.content, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        // Send Message Field: same shape as the main chat composer — text field
        // on top, circular send button inside the field at the bottom-right.
        val sendMessage = {
            if (inputMessage.isNotBlank()) {
                orchestrator.broadcastMessage(TeamRole.MANAGER, null, inputMessage)
                inputMessage = ""
            }
        }
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = 1.dp,
                color = if (inputMessage.isNotBlank()) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant
            ),
            // Medium: the keyboard used to cover this composer.
            modifier = Modifier.fillMaxWidth().imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                BasicTextField(
                    value = inputMessage,
                    onValueChange = { inputMessage = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .heightIn(min = 28.dp, max = 120.dp),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        lineHeight = 19.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (inputMessage.isEmpty()) {
                                Text(
                                    text = "Broadcast note to team...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clickable(enabled = inputMessage.isNotBlank(), onClick = { sendMessage() }),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    color = if (inputMessage.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Send",
                                tint = if (inputMessage.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tab 4: Centralized Shared Blackboard Artifacts.
 */
@Composable
private fun TeamBlackboardTab(orchestrator: AgentTeamOrchestrator) {
    val sessionState by orchestrator.sessionState.collectAsState()
    var filterType by remember { mutableStateOf<ArtifactType?>(null) }

    val filteredArtifacts = sessionState.artifacts.filter {
        filterType == null || it.type == filterType
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Artifact Filter Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = filterType == null,
                onClick = { filterType = null },
                label = { Text("All Artifacts", fontSize = 11.sp) }
            )
            ArtifactType.entries.forEach { type ->
                FilterChip(
                    selected = filterType == type,
                    onClick = { filterType = if (filterType == type) null else type },
                    label = { Text(type.name, fontSize = 11.sp) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Medium: no empty state — a new run used to show a blank list.
            if (filteredArtifacts.isEmpty()) {
                item {
                    Text(
                        text = if (filterType != null) "No artifacts of this type yet." else "No artifacts yet. Finished work will be pinned here as the run progresses.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            items(filteredArtifacts, key = { it.id }) { artifact ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = artifact.title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Text("v${artifact.version}", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Author: ${artifact.authorRole.title} • Type: ${artifact.type.label}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = artifact.content,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tab 5: Review Gatekeeper Sign-offs.
 *
 * The old Resource Quota & Token Budget card is gone: it displayed a
 * hardcoded 100,000-token quota and a usage counter that could never move,
 * because the CLI bridges do not report token counts. A control that shows
 * numbers it cannot measure is decorative, so it was removed. The quota
 * safety gate in the orchestrator still exists; only the dead display is gone.
 */
@Composable
private fun TeamReviewBudgetTab(orchestrator: AgentTeamOrchestrator) {
    val sessionState by orchestrator.sessionState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Review Sign-Offs Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Review Gatekeeper Sign-offs (${sessionState.reviews.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                if (sessionState.reviews.isEmpty()) {
                    Text("No review records logged yet. Gates unlock upon peer review.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    sessionState.reviews.forEach { rev ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${rev.reviewerRole.title} (${rev.reviewerRole.name})",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(rev.comments, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = when (rev.verdict) {
                                    ReviewVerdict.APPROVED -> Color(0xFF2E7D32)
                                    ReviewVerdict.CHANGES_REQUESTED -> Color(0xFFF57C00)
                                    ReviewVerdict.REJECTED -> Color(0xFFD32F2F)
                                    ReviewVerdict.PENDING -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = rev.verdict.name,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (rev.verdict) {
                                        ReviewVerdict.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                                        else -> Color.White
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipTag(label: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
