package com.vayro.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.ui.theme.PocketGreen
import com.vayro.app.ui.theme.PocketOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class BulletItem(val depth: Int, val text: String) : MarkdownBlock
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock
    data class BlockQuote(val text: String) : MarkdownBlock
    object HorizontalRule : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
}

private const val MAX_RENDER_BLOCKS = 400
private const val MAX_TABLE_COLUMNS = 12
private const val MAX_TABLE_ROWS = 200
private const val MAX_CODE_DISPLAY_CHARS = 40_000

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onRunCode: ((String) -> Unit)? = null,
    onOpenUri: ((String) -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val effectiveOpenUri = onOpenUri ?: remember(uriHandler, context) {
        { rawUrl: String ->
            val finalUrl = normalizeMarkdownUrl(rawUrl)
            try {
                uriHandler.openUri(finalUrl)
            } catch (_: Throwable) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Throwable) {
                }
            }
        }
    }

    // Large agent reports can contain hundreds of Markdown blocks. Parsing on the
    // main thread can stall Compose long enough to show a black frame or ANR.
    val blocks by produceState(initialValue = emptyList<MarkdownBlock>(), markdown) {
        value = withContext(Dispatchers.Default) { parseMarkdown(markdown) }
    }

    if (blocks.isEmpty() && markdown.isNotBlank()) {
        val fallbackText = if (markdown.length > 2_000) {
            markdown.take(2_000) + "\n\n… (content truncated)"
        } else {
            markdown
        }
        Text(
            text = fallbackText,
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = color,
        )
    } else Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val hiddenBlocks = (blocks.size - MAX_RENDER_BLOCKS).coerceAtLeast(0)
        val visibleBlocks = if (hiddenBlocks > 0) blocks.take(MAX_RENDER_BLOCKS) else blocks
        visibleBlocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Header -> HeaderBlock(block, effectiveOpenUri)
                is MarkdownBlock.CodeBlock -> CodeSnippetBlock(block, onRunCode)
                is MarkdownBlock.BulletItem -> BulletBlock(block, color, effectiveOpenUri)
                is MarkdownBlock.NumberedItem -> NumberedBlock(block, color, effectiveOpenUri)
                is MarkdownBlock.BlockQuote -> QuoteBlock(block, effectiveOpenUri)
                is MarkdownBlock.HorizontalRule -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = formatInlineMarkdown(block.text, effectiveOpenUri),
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = color,
                    )
                }
                is MarkdownBlock.Table -> TableBlock(block, color, effectiveOpenUri)
            }
        }
        if (hiddenBlocks > 0) {
            Text(
                text = "… ($hiddenBlocks more sections hidden to keep the app responsive; use Copy for the full text)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeaderBlock(header: MarkdownBlock.Header, onOpenUri: ((String) -> Unit)? = null) {
    val style = when (header.level) {
        1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp)
        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp)
        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
    Text(
        text = formatInlineMarkdown(header.text, onOpenUri),
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun BulletBlock(item: MarkdownBlock.BulletItem, color: Color, onOpenUri: ((String) -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (item.depth * 14).dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp, end = 8.dp)
                .size(5.dp)
                .background(PocketOrange, CircleShape),
        )
        Text(
            text = formatInlineMarkdown(item.text, onOpenUri),
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NumberedBlock(item: MarkdownBlock.NumberedItem, color: Color, onOpenUri: ((String) -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = item.number,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = PocketOrange),
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = formatInlineMarkdown(item.text, onOpenUri),
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuoteBlock(quote: MarkdownBlock.BlockQuote, onOpenUri: ((String) -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(PocketOrange, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = formatInlineMarkdown(quote.text, onOpenUri),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
private fun TableBlock(
    table: MarkdownBlock.Table,
    color: Color,
    onOpenUri: ((String) -> Unit)?,
) {
    val scrollState = rememberScrollState()
    val visibleHeaders = table.headers.take(MAX_TABLE_COLUMNS)
    val hiddenColumns = (table.headers.size - MAX_TABLE_COLUMNS).coerceAtLeast(0)
    val visibleRows = table.rows.take(MAX_TABLE_ROWS)
    val hiddenRows = (table.rows.size - MAX_TABLE_ROWS).coerceAtLeast(0)
    Surface(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                visibleHeaders.forEach { header ->
                    Box(
                        modifier = Modifier
                            .widthIn(min = 90.dp, max = 220.dp)
                            .padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = formatInlineMarkdown(header, onOpenUri),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (hiddenColumns > 0) {
                    Text(
                        text = "… +$hiddenColumns cols",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            visibleRows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    visibleHeaders.indices.forEach { colIndex ->
                        val cellText = row.getOrNull(colIndex) ?: ""
                        Box(
                            modifier = Modifier
                                .widthIn(min = 90.dp, max = 220.dp)
                                .padding(horizontal = 8.dp),
                        ) {
                            Text(
                                text = formatInlineMarkdown(cellText, onOpenUri),
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                color = color,
                            )
                        }
                    }
                }
                if (rowIndex < visibleRows.size - 1) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            if (hiddenRows > 0) {
                Text(
                    text = "… $hiddenRows more rows hidden",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CodeSnippetBlock(block: MarkdownBlock.CodeBlock, onRunCode: ((String) -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val displayCode = if (block.code.length > MAX_CODE_DISPLAY_CHARS) {
        block.code.take(MAX_CODE_DISPLAY_CHARS) + "\n… (truncated for display; Copy gives the full code)"
    } else {
        block.code
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.language.ifBlank { "code" },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onRunCode != null) {
                        IconButton(
                            onClick = { onRunCode(block.code) },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Run code",
                                tint = PocketOrange,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(block.code))
                            copied = true
                            scope.launch {
                                delay(2000)
                                copied = false
                            }
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = if (copied) "Copied" else "Copy code",
                            tint = if (copied) PocketGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    text = displayCode,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
internal fun formatInlineMarkdown(
    text: String,
    onOpenUri: ((String) -> Unit)? = null,
): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = PocketOrange
    val primaryColor = MaterialTheme.colorScheme.primary

    return remember(text, codeBg, codeColor, primaryColor, onOpenUri) {
        buildMarkdownAnnotatedString(
            text = text,
            primaryColor = primaryColor,
            codeColor = codeColor,
            codeBg = codeBg,
            onOpenUri = onOpenUri,
        )
    }
}

internal fun buildMarkdownAnnotatedString(
    text: String,
    primaryColor: Color,
    codeColor: Color,
    codeBg: Color,
    onOpenUri: ((String) -> Unit)? = null,
): AnnotatedString {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = primaryColor,
            textDecoration = TextDecoration.Underline,
            fontWeight = FontWeight.Medium,
        ),
        focusedStyle = SpanStyle(
            color = primaryColor,
            textDecoration = TextDecoration.Underline,
            fontWeight = FontWeight.Bold,
        ),
        hoveredStyle = SpanStyle(
            color = primaryColor,
            textDecoration = TextDecoration.Underline,
            fontWeight = FontWeight.Bold,
        ),
        pressedStyle = SpanStyle(
            color = codeColor,
            textDecoration = TextDecoration.Underline,
            fontWeight = FontWeight.Bold,
        ),
    )

    val linkListener: ((LinkAnnotation) -> Unit)? = onOpenUri?.let { action ->
        { annotation ->
            val url = (annotation as? LinkAnnotation.Url)?.url
            if (!url.isNullOrBlank()) {
                action(url)
            }
        }
    }

    return buildAnnotatedString {
        var i = 0
        val len = text.length

        while (i < len) {
            when {
                // Inline Code: `code`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBg,
                                color = codeColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        ) {
                            append(" ${text.substring(i + 1, end)} ")
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Bold & Italic: ***text***
                text.startsWith("***", i) -> {
                    val end = text.indexOf("***", i + 3)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 3, end))
                        }
                        i = end + 3
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Bold: **text** or __text__
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                text.startsWith("__", i) -> {
                    val end = text.indexOf("__", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Italic: *text* (avoiding single bullet at start)
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Strikethrough: ~~text~~
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Markdown link: [label](url)
                text[i] == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    val openParen = if (closeBracket != -1) text.indexOf('(', closeBracket) else -1
                    val closeParen = if (openParen == closeBracket + 1) text.indexOf(')', openParen) else -1

                    if (closeBracket != -1 && openParen == closeBracket + 1 && closeParen != -1) {
                        val label = text.substring(i + 1, closeBracket)
                        val rawUrl = text.substring(openParen + 1, closeParen).trim()
                        val finalUrl = normalizeMarkdownUrl(rawUrl)
                        withLink(
                            LinkAnnotation.Url(
                                url = finalUrl,
                                styles = linkStyles,
                                linkInteractionListener = linkListener,
                            ),
                        ) {
                            append(label)
                        }
                        i = closeParen + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Autolink: <http://...> or <https://...>
                text[i] == '<' && (text.startsWith("<http://", i, ignoreCase = true) || text.startsWith("<https://", i, ignoreCase = true)) -> {
                    val closeAngle = text.indexOf('>', i + 1)
                    if (closeAngle != -1) {
                        val rawUrl = text.substring(i + 1, closeAngle).trim()
                        val finalUrl = normalizeMarkdownUrl(rawUrl)
                        withLink(
                            LinkAnnotation.Url(
                                url = finalUrl,
                                styles = linkStyles,
                                linkInteractionListener = linkListener,
                            ),
                        ) {
                            append(rawUrl)
                        }
                        i = closeAngle + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Plain HTTP / HTTPS URL
                text.startsWith("http://", i, ignoreCase = true) || text.startsWith("https://", i, ignoreCase = true) -> {
                    var j = i
                    while (j < len && !text[j].isWhitespace() && text[j] !in setOf('"', '\'', '`', '<', '>', '[', ']', '(', ')', '|')) {
                        j++
                    }
                    while (j > i && text[j - 1] in setOf('.', ',', ';', ':', '!', '?')) {
                        j--
                    }
                    if (j > i) {
                        val rawUrl = text.substring(i, j)
                        val finalUrl = normalizeMarkdownUrl(rawUrl)
                        withLink(
                            LinkAnnotation.Url(
                                url = finalUrl,
                                styles = linkStyles,
                                linkInteractionListener = linkListener,
                            ),
                        ) {
                            append(rawUrl)
                        }
                        i = j
                    } else {
                        append(text[i])
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

internal fun normalizeMarkdownUrl(raw: String): String {
    val trimmed = raw.trim()
    return when {
        trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true) ||
        trimmed.startsWith("mailto:", ignoreCase = true) ||
        trimmed.startsWith("tel:", ignoreCase = true) -> trimmed
        else -> "https://$trimmed"
    }
}

private fun isTableDelimiterLine(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains("|") || !trimmed.contains("-")) return false
    return trimmed.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }
}

private fun isTableRowLine(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.startsWith("|") || (trimmed.contains("|") && trimmed.endsWith("|"))
}

private fun parseTableRow(line: String): List<String> {
    val trimmed = line.trim().removePrefix("|").removeSuffix("|")
    return trimmed.split("|").map { it.trim() }
}

internal fun parseMarkdown(raw: String): List<MarkdownBlock> {
    val lines = raw.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var inCodeBlock = false
    var codeLang = ""
    val codeLines = mutableListOf<String>()
    val currentParagraphLines = mutableListOf<String>()

    fun flushParagraph() {
        if (currentParagraphLines.isNotEmpty()) {
            val text = currentParagraphLines.joinToString("\n").trim()
            if (text.isNotEmpty()) {
                blocks.add(MarkdownBlock.Paragraph(text))
            }
            currentParagraphLines.clear()
        }
    }

    var idx = 0
    while (idx < lines.size) {
        val line = lines[idx]
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            if (inCodeBlock) {
                blocks.add(MarkdownBlock.CodeBlock(codeLang, codeLines.joinToString("\n")))
                codeLines.clear()
                codeLang = ""
                inCodeBlock = false
            } else {
                flushParagraph()
                inCodeBlock = true
                codeLang = trimmed.removePrefix("```").trim()
            }
            idx++
            continue
        }

        if (inCodeBlock) {
            codeLines.add(line)
            idx++
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            idx++
            continue
        }

        // Table check: line has row format and next line is delimiter
        if (isTableRowLine(trimmed) && idx + 1 < lines.size && isTableDelimiterLine(lines[idx + 1])) {
            flushParagraph()
            val headers = parseTableRow(trimmed)
            idx += 2 // skip header and delimiter

            val tableRows = mutableListOf<List<String>>()
            while (idx < lines.size) {
                val nextLine = lines[idx].trim()
                if (nextLine.isEmpty() || !nextLine.contains("|")) {
                    break
                }
                tableRows.add(parseTableRow(nextLine))
                idx++
            }
            if (headers.isNotEmpty()) {
                blocks.add(MarkdownBlock.Table(headers, tableRows))
            }
            continue
        }

        // Horizontal Rule
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            flushParagraph()
            blocks.add(MarkdownBlock.HorizontalRule)
            idx++
            continue
        }

        // Headings (#, ##, ###)
        if (trimmed.startsWith("#")) {
            flushParagraph()
            val level = trimmed.takeWhile { it == '#' }.length
            val text = trimmed.drop(level).trim()
            blocks.add(MarkdownBlock.Header(level, text))
            idx++
            continue
        }

        // Blockquotes (> text)
        if (trimmed.startsWith(">")) {
            flushParagraph()
            val text = trimmed.removePrefix(">").trim()
            blocks.add(MarkdownBlock.BlockQuote(text))
            idx++
            continue
        }

        // Unordered List (- item, * item, + item)
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            flushParagraph()
            val indent = line.takeWhile { it.isWhitespace() }.length / 2
            val text = trimmed.substring(2).trim()
            blocks.add(MarkdownBlock.BulletItem(indent, text))
            idx++
            continue
        }

        // Ordered List (1. item, 2. item)
        val orderedMatch = Regex("^([0-9]+[.)])\\s+(.*)").find(trimmed)
        if (orderedMatch != null) {
            flushParagraph()
            val num = orderedMatch.groupValues[1]
            val text = orderedMatch.groupValues[2]
            blocks.add(MarkdownBlock.NumberedItem(num, text))
            idx++
            continue
        }

        // Normal paragraph text
        currentParagraphLines.add(line)
        idx++
    }

    if (inCodeBlock) {
        blocks.add(MarkdownBlock.CodeBlock(codeLang, codeLines.joinToString("\n")))
    }
    flushParagraph()

    return blocks
}
