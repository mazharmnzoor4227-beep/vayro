package com.vayro.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vayro.app.util.YouTubeUrlValidator
import com.vayro.app.util.YouTubeVideoInfo

val YouTubeBrandRed = Color(0xFFE60000)

private val FOCUS_PRESETS = listOf(
    "Full Deep Summary",
    "Key Takeaways & Timestamps",
    "Action Items & Tutorial",
    "Code & Technical Walkthrough",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YouTubeVideoDialog(
    initialUrl: String = "",
    onDismiss: () -> Unit,
    onAnalyze: (info: YouTubeVideoInfo, userQuery: String?, focusMode: String) -> Unit,
) {
    var urlInput by rememberSaveable { mutableStateOf(initialUrl) }
    var inquiryInput by rememberSaveable { mutableStateOf("") }
    var selectedPreset by rememberSaveable { mutableStateOf(FOCUS_PRESETS.first()) }
    val clipboardManager = LocalClipboardManager.current

    val validationResult by remember(urlInput) {
        derivedStateOf {
            if (urlInput.isBlank()) null else YouTubeUrlValidator.validateAndExtract(urlInput)
        }
    }

    val isValid = validationResult?.isSuccess == true
    val videoInfo = validationResult?.getOrNull()
    val errorMessage = validationResult?.exceptionOrNull()?.message
    val validationGreen = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    // Medium: dialog content scrolls and lifts above the
                    // keyboard instead of clipping on small phones.
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
            ) {
                // Header with YouTube brand icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(YouTubeBrandRed.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartDisplay,
                                contentDescription = "YouTube Video",
                                tint = YouTubeBrandRed,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "YouTube Video Analyzer",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Powered by Google Gemini Multi-Modal",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        // Align the close target with the card's top/right inset while keeping
                        // a comfortable touch target and a full-size, consistent close glyph.
                        modifier = Modifier.size(48.dp).offset(x = 8.dp, y = (-8).dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // YouTube URL Input field
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("YouTube Video URL (Strictly YouTube only)") },
                    placeholder = { Text("https://www.youtube.com/watch?v=... or youtu.be/...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    isError = validationResult != null && !isValid,
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (urlInput.isNotBlank()) {
                                IconButton(onClick = { urlInput = "" }, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    val clip = clipboardManager.getText()?.text?.trim()
                                    if (!clip.isNullOrBlank()) {
                                        urlInput = clip
                                    }
                                },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Paste from clipboard",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (isValid) validationGreen else MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )

                // Validation Status Banner
                AnimatedVisibility(visible = validationResult != null) {
                    Column(modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)) {
                        if (isValid && videoInfo != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Valid",
                                    tint = validationGreen,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "Ready to analyze: Video ID [${videoInfo.videoId}]" +
                                            if (videoInfo.isShort) " (Short)" else if (videoInfo.isLive) " (Live)" else "",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = validationGreen,
                                )
                            }
                        } else if (errorMessage != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = "Error",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Focus presets
                Text(
                    text = "Analysis Preset",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FOCUS_PRESETS.forEach { preset ->
                        val isSelected = preset == selectedPreset
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            ),
                            modifier = Modifier.clickable { selectedPreset = preset },
                        ) {
                            Text(
                                text = preset,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Optional specific user question
                OutlinedTextField(
                    value = inquiryInput,
                    onValueChange = { inquiryInput = it },
                    label = { Text("Specific Question / Instructions (Optional)") },
                    placeholder = { Text("e.g., Explain the core arguments, or extract all code shown...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 2,
                    maxLines = 3,
                )

                Spacer(Modifier.height(18.dp))

                // Bottom-anchored primary action: full-width 52dp AI Analyze.
                // Cancel is a subtle secondary text action above it.
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = {
                        if (videoInfo != null) {
                            onAnalyze(videoInfo, inquiryInput.takeIf { it.isNotBlank() }, selectedPreset)
                            onDismiss()
                        }
                    },
                    // Medium: the button used to be enabled with no valid
                    // URL, and tapping it silently did nothing.
                    enabled = videoInfo != null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = YouTubeBrandRed,
                        contentColor = Color.White,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "AI Analyze",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
