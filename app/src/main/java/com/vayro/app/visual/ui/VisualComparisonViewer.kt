package com.vayro.app.visual.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.ui.theme.PocketGreen
import com.vayro.app.visual.VisualComparisonPair
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualComparisonViewer(
    projectRoot: File,
    pair: VisualComparisonPair,
    onDismiss: () -> Unit,
) {
    var isSideBySide by remember { mutableStateOf(false) }
    var sliderRatio by remember { mutableFloatStateOf(0.5f) }

    val beforeFile = remember(pair.beforeSnapshot.screenshotPath) {
        File(projectRoot, pair.beforeSnapshot.screenshotPath)
    }
    val afterFile = remember(pair.afterSnapshot.screenshotPath) {
        File(projectRoot, pair.afterSnapshot.screenshotPath)
    }

    val beforeBitmap = remember(beforeFile.path) {
        if (beforeFile.exists()) BitmapFactory.decodeFile(beforeFile.absolutePath) else null
    }
    val afterBitmap = remember(afterFile.path) {
        if (afterFile.exists()) BitmapFactory.decodeFile(afterFile.absolutePath) else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Compare, contentDescription = null, tint = PocketGreen)
                    Text("Visual Comparison", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Header & Mode switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        pair.title.ifBlank { "Before vs After UI" },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = !isSideBySide,
                            onClick = { isSideBySide = false },
                            label = { Text("Split Slider", fontSize = 11.sp) },
                        )
                        FilterChip(
                            selected = isSideBySide,
                            onClick = { isSideBySide = true },
                            label = { Text("Side-by-Side", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.ViewColumn, null, Modifier.size(14.dp)) },
                        )
                    }
                }

                // Snapshots information cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text("BEFORE (Baseline)", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            Text(pair.beforeSnapshot.title, fontSize = 11.sp, maxLines = 1)
                            Text("Errors: ${pair.beforeSnapshot.consoleErrorsCount}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text("AFTER (Updated)", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = PocketGreen)
                            Text(pair.afterSnapshot.title, fontSize = 11.sp, maxLines = 1)
                            Text("Errors: ${pair.afterSnapshot.consoleErrorsCount}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Visual Diff Render Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSideBySide) {
                        // Side-by-side mode
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxHeight().border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (beforeBitmap != null) {
                                    Image(
                                        bitmap = beforeBitmap.asImageBitmap(),
                                        contentDescription = "Before",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                    )
                                } else {
                                    Text("No Before Image", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Box(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (afterBitmap != null) {
                                    Image(
                                        bitmap = afterBitmap.asImageBitmap(),
                                        contentDescription = "After",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                    )
                                } else {
                                    Text("No After Image", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else {
                        // Split Slider Mode
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val boxWidth = maxWidth
                            val splitX = boxWidth * sliderRatio

                            // After Image in full background
                            if (afterBitmap != null) {
                                Image(
                                    bitmap = afterBitmap.asImageBitmap(),
                                    contentDescription = "After",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
                            }

                            // Before Image clipped to left of slider
                            if (beforeBitmap != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(splitX)
                                        .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)),
                                ) {
                                    Image(
                                        bitmap = beforeBitmap.asImageBitmap(),
                                        contentDescription = "Before",
                                        modifier = Modifier.fillMaxHeight().width(boxWidth),
                                        contentScale = ContentScale.Fit,
                                        alignment = Alignment.CenterStart,
                                    )
                                }
                            }

                            // Interactive draggable vertical divider
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .offset(x = splitX - 1.dp)
                                    .width(2.dp)
                                    .background(PocketGreen),
                            )

                            // Draggable Handle
                            Box(
                                modifier = Modifier
                                    .offset(x = splitX - 16.dp, y = maxHeight / 2 - 16.dp)
                                    .size(32.dp)
                                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                                    .border(2.dp, PocketGreen, CircleShape)
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            val newRatio = ((splitX.toPx() + dragAmount.x) / boxWidth.toPx()).coerceIn(0.05f, 0.95f)
                                            sliderRatio = newRatio
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("↔", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PocketGreen)
                            }
                        }
                    }
                }
            }
        },
    )
}
