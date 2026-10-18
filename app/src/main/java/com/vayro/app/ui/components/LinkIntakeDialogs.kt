package com.vayro.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * Kodex link intake dialogs (v3.2.31) — the composer's "+" menu options
 * that take a pasted link and turn it into agent context: TikTok, any web
 * page, and GitHub repositories. Same visual language as the YouTube
 * analyzer dialog. Fetching is honest: whatever public data exists is
 * reported exactly, and anything that could not be obtained is said out
 * loud in the message handed to the agent.
 */

data class TikTokInfo(
    val title: String,
    val authorName: String,
    val authorUrl: String,
    val thumbnailUrl: String,
    val pageUrl: String,
)

fun isValidTikTokUrl(raw: String): Boolean {
    val url = raw.trim()
    if (!url.startsWith("https://")) return false
    val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
    val hosts = setOf("tiktok.com", "www.tiktok.com", "m.tiktok.com", "vt.tiktok.com", "vm.tiktok.com")
    if (host !in hosts) return false
    return url.contains("/video/") || url.contains("/@") || host == "vt.tiktok.com" || host == "vm.tiktok.com"
}

/** TikTok's public oEmbed endpoint: title, author, thumbnail. No API key. */
suspend fun fetchTikTokInfo(rawUrl: String): TikTokInfo? = withContext(Dispatchers.IO) {
    runCatching {
        val encoded = URLEncoder.encode(rawUrl.trim(), "UTF-8")
        val connection = URL("https://www.tiktok.com/oembed?url=$encoded")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("User-Agent", "Kodex-Android")
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            return@runCatching null
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        val json = JSONObject(body)
        TikTokInfo(
            title = json.optString("title"),
            authorName = json.optString("author_name"),
            authorUrl = json.optString("author_url"),
            thumbnailUrl = json.optString("thumbnail_url"),
            pageUrl = rawUrl.trim(),
        )
    }.getOrNull()
}

/** Fetches a web page and extracts its title + readable text. */
suspend fun fetchWebPageText(rawUrl: String): Pair<String, String>? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URL(rawUrl.trim()).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Kodex")
        connection.setRequestProperty("Accept", "text/html")
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            return@runCatching null
        }
        val html = connection.inputStream.bufferedReader().use { reader ->
            val builder = StringBuilder()
            val buffer = CharArray(8192)
            var total = 0
            while (total < 200_000) {
                val read = reader.read(buffer)
                if (read < 0) break
                builder.append(buffer, 0, read)
                total += read
            }
            builder.toString()
        }
        connection.disconnect()
        val title = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        var text = html
        text = text.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<[^>]+>"), " ")
        text = text.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        text = text.replace(Regex("\\s+"), " ").trim()
        title to text
    }.getOrNull()
}

fun isValidGitHubRepoUrl(raw: String): Boolean {
    val url = raw.trim()
    if (!url.startsWith("https://github.com/")) return false
    val parts = url.removePrefix("https://github.com/").trim('/').split("/")
    return parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()
}

@Composable
fun TikTokLinkDialog(
    onDismiss: () -> Unit,
    onAnalyze: (url: String, question: String?) -> Unit,
) {
    LinkIntakeDialog(
        title = "TikTok Analyzer",
        subtitle = "Public video details — no API key needed",
        icon = Icons.Default.MusicNote,
        iconTint = MaterialTheme.colorScheme.primary,
        urlLabel = "TikTok Video URL",
        urlPlaceholder = "https://www.tiktok.com/@user/video/…",
        validate = ::isValidTikTokUrl,
        invalidHint = "That does not look like a TikTok video link.",
        showQuestionField = true,
        actionLabel = "Analyze",
        onDismiss = onDismiss,
        onSubmit = onAnalyze,
    )
}

@Composable
fun WebLinkDialog(
    onDismiss: () -> Unit,
    onFetch: (url: String) -> Unit,
) {
    LinkIntakeDialog(
        title = "Web Link",
        subtitle = "Fetch a page and give its text to the agent",
        icon = Icons.Default.Language,
        iconTint = MaterialTheme.colorScheme.primary,
        urlLabel = "Page URL",
        urlPlaceholder = "https://example.com/article",
        validate = { it.trim().startsWith("https://") || it.trim().startsWith("http://") },
        invalidHint = "Enter a full URL starting with https://",
        showQuestionField = false,
        actionLabel = "Fetch page",
        onDismiss = onDismiss,
        onSubmit = { url, _ -> onFetch(url) },
    )
}

@Composable
fun GitHubRepoDialog(
    onDismiss: () -> Unit,
    onInspect: (url: String) -> Unit,
) {
    LinkIntakeDialog(
        title = "GitHub Repository",
        subtitle = "Clone into the workspace and inspect it",
        icon = Icons.Default.Code,
        iconTint = MaterialTheme.colorScheme.primary,
        urlLabel = "Repository URL",
        urlPlaceholder = "https://github.com/owner/repo",
        validate = ::isValidGitHubRepoUrl,
        invalidHint = "That does not look like a GitHub repository link.",
        showQuestionField = false,
        actionLabel = "Clone & inspect",
        onDismiss = onDismiss,
        onSubmit = { url, _ -> onInspect(url) },
    )
}

@Composable
private fun LinkIntakeDialog(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    urlLabel: String,
    urlPlaceholder: String,
    validate: (String) -> Boolean,
    invalidHint: String,
    showQuestionField: Boolean,
    actionLabel: String,
    onDismiss: () -> Unit,
    onSubmit: (url: String, question: String?) -> Unit,
) {
    var urlInput by rememberSaveable { mutableStateOf("") }
    var questionInput by rememberSaveable { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val isValid by remember(urlInput) {
        derivedStateOf { urlInput.isNotBlank() && validate(urlInput) }
    }
    val showError = urlInput.isNotBlank() && !isValid

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
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
            ) {
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
                                .background(iconTint.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
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

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(urlLabel) },
                    placeholder = { Text(urlPlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    isError = showError,
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (urlInput.isNotBlank()) {
                                IconButton(onClick = { urlInput = "" }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                            IconButton(
                                onClick = {
                                    val clip = clipboardManager.getText()?.text?.trim()
                                    if (!clip.isNullOrBlank()) urlInput = clip
                                },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = "Paste from clipboard",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                    },
                )

                AnimatedVisibility(visible = urlInput.isNotBlank()) {
                    Row(
                        modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isValid) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Valid",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                "Ready.",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                invalidHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                if (showQuestionField) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = questionInput,
                        onValueChange = { questionInput = it },
                        label = { Text("Specific Question / Instructions (Optional)") },
                        placeholder = { Text("e.g., What product is shown and how is it used?") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 2,
                        maxLines = 3,
                    )
                }

                Spacer(Modifier.height(18.dp))

                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = {
                        if (isValid) {
                            onSubmit(urlInput.trim(), questionInput.takeIf { it.isNotBlank() })
                        }
                    },
                    enabled = isValid,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(actionLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
