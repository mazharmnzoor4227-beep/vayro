package com.vayro.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Mobile-optimized coding symbol and action toolbar.
 * Provides instant access to coding symbols, brackets, indentation, and cursor movement
 * without needing to switch keyboards on mobile devices.
 */
@Composable
fun CodingToolbar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: (() -> Unit)? = null,
    onRedo: (() -> Unit)? = null,
) {
    val scrollState = rememberScrollState()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onUndo != null) {
                ToolbarButton(
                    label = "↶",
                    description = "Undo",
                    enabled = canUndo,
                    onClick = onUndo,
                )
            }
            if (onRedo != null) {
                ToolbarButton(
                    label = "↷",
                    description = "Redo",
                    enabled = canRedo,
                    onClick = onRedo,
                )
            }

            ToolbarButton(label = "Tab", description = "Indent") {
                onValueChange(applyEditorSymbol(value, "TAB"))
            }
            ToolbarButton(label = "Untab", description = "Unindent") {
                onValueChange(applyEditorSymbol(value, "UNTAB"))
            }
            ToolbarButton(label = "//", description = "Toggle Comment") {
                onValueChange(applyEditorSymbol(value, "//"))
            }
            ToolbarButton(label = "←", description = "Move Left") {
                onValueChange(applyEditorSymbol(value, "LEFT"))
            }
            ToolbarButton(label = "→", description = "Move Right") {
                onValueChange(applyEditorSymbol(value, "RIGHT"))
            }

            // Quick brackets and quotes with auto-wrap
            listOf("{", "}", "(", ")", "[", "]", "<", ">", "\"", "'", "`").forEach { sym ->
                ToolbarButton(label = sym) {
                    onValueChange(applyEditorSymbol(value, sym))
                }
            }

            // Quick operators
            listOf(";", ":", "=", "->", "=>", "+", "-", "*", "/", "!", "?", "&", "|", ".", ",", "$").forEach { sym ->
                ToolbarButton(label = sym) {
                    onValueChange(applyEditorSymbol(value, sym))
                }
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    label: String,
    description: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        color = if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .semantics { contentDescription = description ?: label },
        shadowElevation = 1.dp,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 7.dp),
        ) {
            Text(
                text = label,
                fontSize = if (label.length > 2) 11.sp else 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

/**
 * Applies a toolbar symbol or action to the current [TextFieldValue],
 * performing smart selection wrapping, bracket auto-pairing, indentation, or line commenting.
 */
fun applyEditorSymbol(value: TextFieldValue, symbol: String): TextFieldValue {
    val text = value.text
    val sel = value.selection
    val start = sel.min.coerceIn(0, text.length)
    val end = sel.max.coerceIn(0, text.length)
    val hasSelection = start != end

    when (symbol) {
        "LEFT" -> {
            val newPos = (sel.start - 1).coerceAtLeast(0)
            return value.copy(selection = TextRange(newPos))
        }
        "RIGHT" -> {
            val newPos = (sel.end + 1).coerceAtMost(text.length)
            return value.copy(selection = TextRange(newPos))
        }
        "TAB" -> {
            val indent = "    "
            if (!hasSelection) {
                val newText = text.substring(0, start) + indent + text.substring(start)
                return TextFieldValue(newText, TextRange(start + indent.length))
            } else {
                // Indent each line in the selection
                val before = text.substring(0, start)
                val lineStart = before.lastIndexOf('\n').let { if (it >= 0) it + 1 else 0 }
                val selectedBlock = text.substring(lineStart, end)
                val indentedBlock = selectedBlock.lines().joinToString("\n") { "$indent$it" }
                val newText = text.substring(0, lineStart) + indentedBlock + text.substring(end)
                return TextFieldValue(newText, TextRange(lineStart, lineStart + indentedBlock.length))
            }
        }
        "UNTAB" -> {
            val before = text.substring(0, start)
            val lineStart = before.lastIndexOf('\n').let { if (it >= 0) it + 1 else 0 }
            val blockEnd = if (hasSelection) end else text.indexOf('\n', start).let { if (it >= 0) it else text.length }
            val block = text.substring(lineStart, blockEnd)
            val unindentedBlock = block.lines().joinToString("\n") { line ->
                when {
                    line.startsWith("    ") -> line.removePrefix("    ")
                    line.startsWith("\t") -> line.removePrefix("\t")
                    line.startsWith("  ") -> line.removePrefix("  ")
                    line.startsWith(" ") -> line.removePrefix(" ")
                    else -> line
                }
            }
            val newText = text.substring(0, lineStart) + unindentedBlock + text.substring(blockEnd)
            val newCursor = (start - (block.length - unindentedBlock.length)).coerceIn(lineStart, newText.length)
            return TextFieldValue(newText, TextRange(newCursor))
        }
        "//" -> {
            val before = text.substring(0, start)
            val lineStart = before.lastIndexOf('\n').let { if (it >= 0) it + 1 else 0 }
            val blockEnd = if (hasSelection) end else text.indexOf('\n', start).let { if (it >= 0) it else text.length }
            val block = text.substring(lineStart, blockEnd)
            val lines = block.lines()
            val allCommented = lines.all { it.trimStart().startsWith("//") }
            val toggledBlock = lines.joinToString("\n") { line ->
                if (allCommented) {
                    val indent = line.takeWhile { it.isWhitespace() }
                    val content = line.dropWhile { it.isWhitespace() }
                    indent + content.removePrefix("//").removePrefix(" ")
                } else {
                    val indent = line.takeWhile { it.isWhitespace() }
                    val content = line.dropWhile { it.isWhitespace() }
                    "$indent// $content"
                }
            }
            val newText = text.substring(0, lineStart) + toggledBlock + text.substring(blockEnd)
            return TextFieldValue(newText, TextRange(lineStart, lineStart + toggledBlock.length))
        }
    }

    // Bracket & quote pairs
    val pair = when (symbol) {
        "{" -> "}"
        "[" -> "]"
        "(" -> ")"
        "<" -> ">"
        "\"" -> "\""
        "'" -> "'"
        "`" -> "`"
        else -> null
    }

    if (pair != null) {
        if (hasSelection) {
            val selectedText = text.substring(start, end)
            val wrapped = "$symbol$selectedText$pair"
            val newText = text.substring(0, start) + wrapped + text.substring(end)
            return TextFieldValue(newText, TextRange(start + 1, start + 1 + selectedText.length))
        } else {
            val inserted = "$symbol$pair"
            val newText = text.substring(0, start) + inserted + text.substring(start)
            return TextFieldValue(newText, TextRange(start + 1))
        }
    }

    // Default symbol insertion
    val newText = text.substring(0, start) + symbol + text.substring(end)
    return TextFieldValue(newText, TextRange(start + symbol.length))
}
