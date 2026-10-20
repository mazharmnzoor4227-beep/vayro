package com.vayro.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

/**
 * v3.2.40 (Scope F): long-paste handling for both composers, cloned
 * from the user's Claude-app reference: pasting a long text does NOT
 * pour it into the field — it becomes a compact "TXT · Pasted
 * content" chip that rides with the message. On send, the pasted text
 * still goes to the model IN FULL, appended to the typed text (the
 * chip is a container/presentation change only — it saves no tokens
 * and truncates nothing).
 */

/**
 * A single paste longer than this becomes the chip instead of field
 * text. v3.2.40 (Scope F2): the user's two reference videos bracket
 * the value — a ~900-character paste stays text in the field, a
 * multi-thousand-character prompt becomes the chip.
 */
const val PASTE_CHIP_THRESHOLD_CHARS = 2000

/**
 * v3.2.40 (Scope F2): the composer field grows smoothly to this many
 * visible lines, then stops — further text scrolls INSIDE the field
 * with the caret line fully visible; lines are never half-clipped
 * because the field's height is a whole number of line heights.
 */
const val COMPOSER_MAX_LINES = 8

/**
 * The outcome of one composer text change: the text the FIELD keeps
 * and the chip content after the change (null = no chip).
 */
data class PasteSplit(val fieldText: String, val chipText: String?)

/**
 * Splits one text-field change into field text + chip content.
 *
 * A change whose INSERTED chunk (the part of [newText] that was not in
 * [oldText]) is longer than the threshold is a paste: the chunk leaves
 * the field and becomes the chip (appended to an existing chip with a
 * blank line — one chip per message, content is never dropped). Any
 * other change (typing, deleting, a short paste) just updates the field.
 */
fun splitPasteChange(
    oldText: String,
    newText: String,
    currentChip: String?,
    threshold: Int = PASTE_CHIP_THRESHOLD_CHARS,
): PasteSplit {
    if (newText.length - oldText.length <= threshold) {
        return PasteSplit(newText, currentChip)
    }
    // Longest common prefix/suffix isolate the inserted chunk.
    var prefix = 0
    val maxPrefix = minOf(oldText.length, newText.length)
    while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++
    var suffix = 0
    val maxSuffix = minOf(oldText.length - prefix, newText.length - prefix)
    while (suffix < maxSuffix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) {
        suffix++
    }
    val inserted = newText.substring(prefix, newText.length - suffix)
    if (inserted.length <= threshold) {
        return PasteSplit(newText, currentChip)
    }
    val chip = if (currentChip.isNullOrBlank()) {
        inserted
    } else {
        currentChip + "\n\n" + inserted
    }
    return PasteSplit(oldText, chip)
}

/** The full text the model receives: typed text + the pasted content, in full. */
fun combinedModelText(typedText: String, pastedText: String?): String =
    listOf(typedText.trim(), pastedText?.trim().orEmpty())
        .filter { it.isNotBlank() }
        .joinToString("\n\n")

/** The compact chip shown inside the composer while a paste is attached. */
@Composable
fun PastedContentChip(
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("TXT", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily)
                Text(
                    "Pasted content",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove pasted content", modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * v3.2.41 (Scope 3): the staged-document chip in the home composer —
 * the file's real name and kind, tap to read exactly what will be
 * sent, ✕ to unstage.
 */
@Composable
fun DocumentAttachmentChip(
    name: String,
    kindLabel: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    kindLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove document", modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** v3.2.41 (Scope 3): the document card rendered inside a sent home message. */
@Composable
fun DocumentAttachmentCard(
    name: String,
    kindLabel: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = modifier.clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(9.dp))
            Column {
                Text(
                    name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    kindLabel,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The "TXT · Pasted content" card rendered inside a sent message. */
@Composable
fun PastedContentCard(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = modifier.clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(9.dp))
            Column {
                Text("TXT", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = com.vayro.app.ui.theme.JetBrainsMonoFontFamily)
                Text(
                    "Pasted content",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Full-screen viewer for pasted content: close (✕) top-left, Copy at
 * the top, the entire text in clean full lines with smooth scrolling.
 */
@Composable
fun PastedContentViewerDialog(
    text: String,
    onDismiss: () -> Unit,
    // v3.2.41 (Scope 3): the same full-text viewer shows an attached
    // document's extracted content, titled with the file's name.
    title: String = "Pasted content",
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                    Text(
                        title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Copy", fontSize = 13.5.sp)
                    }
                }
                SelectionContainer {
                    Text(
                        text,
                        style = contentBodyStyle(),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
