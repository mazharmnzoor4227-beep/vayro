package com.vayro.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.model.ActiveRoadmap
import com.vayro.app.model.ExecutionMode
import com.vayro.app.model.RoadmapComment
import com.vayro.app.model.RoadmapStep
import com.vayro.app.model.ScratchpadItem
import com.vayro.app.model.StepStatus
import com.vayro.app.model.TaskStatus
import com.vayro.app.session.EngineeringState
import com.vayro.app.session.OrchestratorSnapshot
import com.vayro.app.ui.theme.PocketGreen
import com.vayro.app.ui.theme.PocketOrange

/**
 * Compact, modern Mode Selector bar (Plan, Build, Unified)
 * Designed to take minimum vertical space (~32dp) while remaining highly usable.
 */
@Composable
fun ModeSelectorBar(
    currentMode: ExecutionMode,
    onModeSelected: (ExecutionMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExecutionMode.entries.forEach { mode ->
            val isSelected = mode == currentMode
            val (icon, label) = when (mode) {
                ExecutionMode.PLAN -> Icons.Default.Lightbulb to "Plan"
                ExecutionMode.BUILD -> Icons.Default.Build to "Build"
                ExecutionMode.UNIFIED -> Icons.Default.RocketLaunch to "Unified"
            }

            Surface(
                onClick = { onModeSelected(mode) },
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
                border = BorderStroke(
                    1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Antigravity / Cursor-like Scratchpad Pill:
 * Displays running, finished, and pending task statuses in an ultra-compact, elegant pill.
 */
@Composable
fun ScratchpadPill(
    items: List<ScratchpadItem>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    isRunning: Boolean = false,
) {
    if (items.isEmpty()) return

    val finishedCount = items.count { it.status == TaskStatus.FINISHED }
    val runningCount = if (isRunning) items.count { it.status == TaskStatus.RUNNING } else 0
    val pendingCount = items.count { it.status == TaskStatus.PENDING }

    Surface(
        onClick = onToggleExpand,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "📋 Scratchpad",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (runningCount > 0) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(PocketOrange, CircleShape)
                )
                Text(
                    "$runningCount running",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = PocketOrange,
                )
            }

            if (finishedCount > 0) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(PocketGreen, CircleShape)
                )
                Text(
                    "$finishedCount done",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = PocketGreen,
                )
            }

            if (pendingCount > 0 && runningCount == 0 && finishedCount == 0) {
                Text(
                    "$pendingCount pending",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse scratchpad" else "Expand scratchpad",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Expanded Scratchpad Card:
 * Elegantly lists individual tasks with live status badges.
 */
@Composable
fun ScratchpadCard(
    items: List<ScratchpadItem>,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Task Scratchpad",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "(${items.size})",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Collapse",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEach { item ->
                    ScratchpadItemRow(item)
                }
            }
        }
    }
}

@Composable
private fun ScratchpadItemRow(item: ScratchpadItem) {
    val (statusBg, statusFg, statusText) = when (item.status) {
        TaskStatus.RUNNING -> Triple(
            PocketOrange.copy(alpha = 0.18f),
            PocketOrange,
            "RUNNING",
        )
        TaskStatus.FINISHED -> Triple(
            PocketGreen.copy(alpha = 0.18f),
            PocketGreen,
            "DONE",
        )
        TaskStatus.FAILED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
            "FAILED",
        )
        TaskStatus.PENDING -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "PENDING",
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = statusBg,
            modifier = Modifier.padding(vertical = 1.dp),
        ) {
            Text(
                text = statusText,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = statusFg,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.detail.isNotBlank()) {
                Text(
                    text = item.detail,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        item.durationSeconds?.let { sec ->
            Text(
                text = "${sec}s",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Interactive Antigravity / Unified Roadmap Card
 * Displays the steps checklist, user comments/annotations, and approval actions.
 */
@Composable
fun RoadmapCard(
    roadmap: ActiveRoadmap,
    onAddComment: (stepId: String?) -> Unit,
    onApproveAndBuild: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(true) }
    val isAllCompleted = roadmap.steps.isNotEmpty() && roadmap.steps.all { it.status == StepStatus.COMPLETED }
    val isApproved = roadmap.isApproved || isAllCompleted

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            if (isAllCompleted || isApproved) PocketGreen.copy(alpha = 0.6f) else PocketOrange.copy(alpha = 0.4f)
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (isAllCompleted || isApproved) Icons.Default.CheckCircle else Icons.Default.RocketLaunch,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isAllCompleted || isApproved) PocketGreen else PocketOrange,
                    )
                    Text(
                        text = roadmap.title.ifBlank { "Implementation Roadmap" },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isAllCompleted || isApproved) PocketGreen.copy(alpha = 0.15f) else PocketOrange.copy(alpha = 0.15f),
                ) {
                    Text(
                        text = when {
                            isAllCompleted -> "Completed"
                            isApproved -> "Approved"
                            else -> "Awaiting Approval"
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isAllCompleted || isApproved) PocketGreen else PocketOrange,
                    )
                }
                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse roadmap" else "Expand roadmap",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isExpanded) {
            if (roadmap.summary.isNotBlank()) {
                Text(
                    text = roadmap.summary,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                )
            }

            // Steps Checklist
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                roadmap.steps.forEachIndexed { index, step ->
                    RoadmapStepRow(
                        index = index + 1,
                        step = step,
                        onAddComment = { onAddComment(step.id) },
                    )
                }
            }

            // User Comments Section
            if (roadmap.comments.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "User Notes & Guidance (${roadmap.comments.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    roadmap.comments.forEach { comment ->
                        RoadmapCommentRow(comment)
                    }
                }
            }

            // Action Buttons / Completion Status
            Spacer(Modifier.height(2.dp))
            if (isAllCompleted) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = PocketGreen,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "All steps completed",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PocketGreen,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { onAddComment(null) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddComment,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Add Note", fontSize = 11.sp, maxLines = 1)
                    }

                    if (!roadmap.isApproved) {
                        Button(
                            onClick = onApproveAndBuild,
                            modifier = Modifier.heightIn(min = 48.dp).weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Approve & Build", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    } else {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = PocketGreen,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Approved & Building",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = PocketGreen,
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
private fun RoadmapStepRow(
    index: Int,
    step: RoadmapStep,
    onAddComment: () -> Unit,
) {
    val isCompleted = step.status == StepStatus.COMPLETED
    val isInProgress = step.status == StepStatus.IN_PROGRESS

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Step number / check badge
        Surface(
            shape = CircleShape,
            color = when {
                isCompleted -> PocketGreen.copy(alpha = 0.2f)
                isInProgress -> PocketOrange.copy(alpha = 0.2f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.size(20.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isCompleted) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = PocketGreen,
                    )
                } else {
                    Text(
                        text = "$index",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isInProgress) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None,
            )

            if (step.description.isNotBlank()) {
                Text(
                    text = step.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                )
            }

            if (step.filesAffected.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    step.filesAffected.forEach { path ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = path.substringAfterLast('/'),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (!isCompleted) {
            IconButton(
                onClick = onAddComment,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Comment,
                    contentDescription = "Comment on step",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RoadmapCommentRow(comment: RoadmapComment) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    comment.author,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                comment.text,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Dialog to add comments or custom guidance to a roadmap or specific step.
 */
@Composable
fun AddRoadmapCommentDialog(
    stepTitle: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (stepTitle != null) "Comment on: $stepTitle" else "Add Guidance to Roadmap",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Provide custom instructions, edge cases, or adjustments. The AI will strictly follow your guidance when building.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("e.g. Ensure backward compatibility with existing API...", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        onSubmit(text.trim())
                    }
                },
                enabled = text.isNotBlank(),
            ) {
                Text("Add Note")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Live Engineering State Machine Banner (Phase 1 & Phase 5)
 * Surfaces real-time autonomous loop state, self-healing iterations,
 * and 1-tap pre-task checkpoint rollback.
 */
@Composable
fun EngineeringStateBanner(
    snapshot: OrchestratorSnapshot?,
    onRollbackToBaseline: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // The Analyzing status pill is removed by user order: no "Analyzing Requirements"
    // banner while the agent is analyzing. Other states (plan, build, fix, failure)
    // still show.
    if (snapshot == null || snapshot.state == EngineeringState.IDLE || snapshot.state == EngineeringState.ANALYZING) return

    val state = snapshot.state
    val (label, color, icon) = when (state) {
        EngineeringState.IDLE -> Triple("Idle", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.Pause)
        EngineeringState.ANALYZING -> Triple("Analyzing Requirements", MaterialTheme.colorScheme.primary, null)
        EngineeringState.PLANNING -> Triple("Formulating Plan", MaterialTheme.colorScheme.primary, Icons.Default.EditNote)
        EngineeringState.IMPLEMENTING -> Triple("Implementing Changes", PocketOrange, Icons.Default.Code)
        EngineeringState.BUILDING -> Triple("Building Project", PocketOrange, Icons.Default.Settings)
        EngineeringState.TESTING -> Triple("Running Tests", PocketOrange, Icons.Default.Science)
        EngineeringState.ANALYZING_FAILURE -> Triple("Analyzing Failure", MaterialTheme.colorScheme.error, Icons.Default.Warning)
        EngineeringState.FIXING -> Triple("Self-Healing (${snapshot.currentIteration}/${snapshot.maxIterations})", PocketOrange, Icons.Default.Build)
        EngineeringState.RETESTING -> Triple("Retesting Fix", PocketOrange, Icons.Default.Refresh)
        EngineeringState.REVIEWING -> Triple("Reviewing Changes", MaterialTheme.colorScheme.primary, Icons.Default.FactCheck)
        EngineeringState.WAITING_FOR_APPROVAL -> Triple("Waiting for Approval", MaterialTheme.colorScheme.primary, Icons.Default.Pending)
        EngineeringState.COMPLETED -> Triple("Verified & Completed", PocketGreen, Icons.Default.CheckCircle)
        EngineeringState.FAILED -> Triple("Task Failed", MaterialTheme.colorScheme.error, Icons.Default.Error)
        EngineeringState.ROLLED_BACK -> Triple("Rolled Back to Baseline", MaterialTheme.colorScheme.error, Icons.Default.History)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (icon != null) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
                }
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (snapshot.currentIteration > 1 && state != EngineeringState.COMPLETED) {
                    Text(
                        text = "Iter ${snapshot.currentIteration}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = PocketOrange,
                        modifier = Modifier
                            .background(PocketOrange.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }

            if (onRollbackToBaseline != null && (state == EngineeringState.FAILED || state == EngineeringState.FIXING)) {
                Surface(
                    onClick = onRollbackToBaseline,
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Revert Baseline", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}
