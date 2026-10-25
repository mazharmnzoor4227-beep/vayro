package com.vayro.app.diagnostics.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private enum class LogSource(val label: String) {
    APP("App Log"),
    SYSTEM("System Log"),
    RUNTIME_SETUP("Runtime Setup"),
    LATEST_COMMAND("Latest Command Output"),
}

private const val MAX_LINES = 800

@Composable
fun DiagnosticsLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var source by remember { mutableStateOf(LogSource.APP) }
    var logText by remember { mutableStateOf("Loading...") }
    var autoScroll by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()

    fun resolveFile(): File? {
        return when (source) {
            LogSource.APP -> File(context.filesDir, "crash.log")
            LogSource.SYSTEM -> null
            LogSource.RUNTIME_SETUP -> File(context.filesDir, "setup/runtime-setup.log")
            LogSource.LATEST_COMMAND -> context.cacheDir.listFiles()
                ?.filter { it.name.endsWith(".log") }
                ?.maxByOrNull { it.lastModified() }
        }
    }

    fun readSystemLog(): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime"))
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val lines = out.lines()
            if (lines.isEmpty()) "(empty system log)"
            else lines.takeLast(MAX_LINES).joinToString("\n")
        } catch (e: Exception) {
            "Could not read system log: ${e.message}"
        }
    }

    fun readTail(): String {
        if (source == LogSource.SYSTEM) return readSystemLog()
        val file = resolveFile()
        if (file == null || !file.isFile) return "No log file yet for \"${source.label}\"."
        return try {
            val lines = file.readLines()
            if (lines.isEmpty()) "(empty log)"
            else lines.takeLast(MAX_LINES).joinToString("\n")
        } catch (e: Exception) {
            "Could not read log: ${e.message}"
        }
    }

    LaunchedEffect(source) {
        withContext(Dispatchers.IO) { logText = readTail() }
        while (isActive) {
            delay(1000)
            val fresh = withContext(Dispatchers.IO) { readTail() }
            if (fresh != logText) logText = fresh
        }
    }

    LaunchedEffect(logText) {
        if (autoScroll && scrollState.maxValue > 0) scrollState.scrollTo(scrollState.maxValue)
    }

    fun copyLog() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("diagnostics-${source.name}", logText))
        Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
    }

    fun shareLog() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Kodex diagnostics: ${source.label}")
            putExtra(Intent.EXTRA_TEXT, logText)
        }
        context.startActivity(Intent.createChooser(send, "Share log"))
    }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = ::copyLog) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy log")
                    }
                    IconButton(onClick = ::shareLog) {
                        Icon(Icons.Default.Share, contentDescription = "Share log")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogSource.entries.forEach { entry ->
                    FilterChip(
                        selected = source == entry,
                        onClick = { source = entry },
                        label = { Text(entry.label, fontSize = 13.sp) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Live",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "refreshes every second",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text("Auto-scroll", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = logText,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp),
                    )
                    .verticalScroll(scrollState)
                    .padding(10.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = ::copyLog,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
                OutlinedButton(
                    onClick = ::shareLog,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
