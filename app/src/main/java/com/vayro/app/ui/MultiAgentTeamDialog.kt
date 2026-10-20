package com.vayro.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.agent.team.AgentTeamOrchestrator
import com.vayro.app.agent.team.TeamRole
import com.vayro.app.agent.team.TeamTask
import com.vayro.app.agent.team.TeamTaskStatus
import kotlinx.coroutines.launch

/**
 * Multi-Agent Engineering Team UI Dashboard (Phase 11).
 * Displays visual role delegation, task decomposition, and collaboration events across 10 specialized agent roles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiAgentTeamBottomSheet(
    orchestrator: AgentTeamOrchestrator,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sessionState by orchestrator.sessionState.collectAsState()
    val scope = rememberCoroutineScope()
    var inputGoal by remember { mutableStateOf(sessionState.activeGoal.ifBlank { "Create full application architecture and implementation" }) }
    var fullStudioMode by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        SheetWindowImeGuard()
        if (fullStudioMode) {
            com.vayro.app.agent.team.ui.TeamStudioScreen(
                orchestrator = orchestrator,
                onClose = { fullStudioMode = false }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Groups,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Column {
                            Text(
                                text = "Multi-Agent Engineering Team",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "10 Specialized Autonomous Agents",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(onClick = { fullStudioMode = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Studio View", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                        }
                    }
                }

            // Roles Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TeamRole.entries.forEach { role ->
                    val isActive = sessionState.activeRole == role
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (isActive) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (isActive) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                            Text(
                                text = role.title,
                                fontSize = 11.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            // Goal Input & Decompose Action
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = inputGoal,
                    onValueChange = { inputGoal = it },
                    label = { Text("Team Engineering Goal") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            // Plan creates the DAG AND immediately runs the
                            // whole team: one click must do the full
                            // autonomous run, not just leave a task list
                            // sitting at 0/10. It used to also fire the goal
                            // into the single-agent chat as a hidden task.
                            if (inputGoal.isNotBlank()) {
                                scope.launch {
                                    if (sessionState.isRunning) orchestrator.cancelRealRun()
                                    orchestrator.planProject(inputGoal)
                                    runCatching { orchestrator.autoRunAllReal() }
                                        .onFailure { orchestrator.reportError(it.message ?: "Plan run failed.") }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Plan Team")
                    }

                    if (sessionState.isRunning) {
                        Button(
                            onClick = { scope.launch { orchestrator.executeNextReadyTask() } },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Step")
                        }

                        OutlinedButton(
                            onClick = { scope.launch { orchestrator.autoRunAllReal() } },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Run All")
                        }

                        OutlinedButton(
                            onClick = { orchestrator.cancelRealRun() },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Stop")
                        }
                    }

                    if (!sessionState.isRunning && sessionState.tasks.any { it.status == TeamTaskStatus.FAILED }) {
                        OutlinedButton(
                            onClick = { orchestrator.retryFailedTasks() },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Retry Failed")
                        }
                    }
                }
            }

            // Active Tasks
            if (sessionState.tasks.isNotEmpty()) {
                Text(
                    text = "Decomposed Subtasks (${sessionState.tasks.count { it.status == TeamTaskStatus.COMPLETED }}/${sessionState.tasks.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessionState.tasks, key = { it.id }) { task ->
                        TeamTaskCard(
                            task = task,
                            onToggleStatus = {
                                val nextStatus = when (task.status) {
                                    TeamTaskStatus.PENDING -> TeamTaskStatus.READY
                                    TeamTaskStatus.READY -> TeamTaskStatus.IN_PROGRESS
                                    TeamTaskStatus.IN_PROGRESS -> TeamTaskStatus.COMPLETED
                                    TeamTaskStatus.UNDER_REVIEW -> TeamTaskStatus.COMPLETED
                                    TeamTaskStatus.COMPLETED -> TeamTaskStatus.PENDING
                                    TeamTaskStatus.BLOCKED -> TeamTaskStatus.READY
                                    TeamTaskStatus.FAILED -> TeamTaskStatus.IN_PROGRESS
                                }
                                orchestrator.updateTaskStatus(task.id, nextStatus)
                            },
                        )
                    }
                }
            }
        }
    }
}
}

@Composable
private fun TeamTaskCard(
    task: TeamTask,
    onToggleStatus: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = task.assignedRole.name,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Text(
                        text = task.title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = task.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                )
            }

            IconButton(onClick = onToggleStatus) {
                when (task.status) {
                    TeamTaskStatus.COMPLETED -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        tint = Color(0xFF4CAF50),
                    )
                    TeamTaskStatus.IN_PROGRESS -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    else -> Icon(
                        Icons.Default.HourglassEmpty,
                        contentDescription = "Pending",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
