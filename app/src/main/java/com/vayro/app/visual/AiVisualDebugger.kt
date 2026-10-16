package com.vayro.app.visual

import com.vayro.app.model.ChatAttachment
import java.io.File
import java.util.UUID

object AiVisualDebugger {

    fun generateBugReportPrompt(
        url: String,
        viewport: ViewportPreset? = null,
        selectedElement: DomElementInfo? = null,
        consoleLogs: List<ConsoleLogEntry> = emptyList(),
        networkLogs: List<NetworkLogEntry> = emptyList(),
        snapshot: VisualSnapshot? = null,
        userNotes: String? = null,
    ): String = buildString {
        appendLine("<visual_bug_report>")
        appendLine("Target URL: $url")
        if (viewport != null) {
            appendLine("Viewport: ${viewport.name} (${if (viewport.widthDp > 0) "${viewport.widthDp}x${viewport.heightDp} dp" else "Responsive"})")
        }

        if (selectedElement != null) {
            appendLine()
            appendLine("### Selected DOM Element")
            appendLine("Tag: ${selectedElement.displayName}")
            if (selectedElement.selector.isNotBlank()) {
                appendLine("CSS Selector: ${selectedElement.selector}")
            }
            if (selectedElement.innerText.isNotBlank()) {
                appendLine("Text Content: \"${selectedElement.innerText}\"")
            }
            appendLine("Bounding Rect: left=${selectedElement.rect.left}, top=${selectedElement.rect.top}, width=${selectedElement.rect.width}, height=${selectedElement.rect.height}")

            if (selectedElement.computedStyles.isNotEmpty()) {
                appendLine("Computed Styles:")
                selectedElement.computedStyles.forEach { (prop, value) ->
                    if (value.isNotBlank()) appendLine("  - $prop: $value")
                }
            }
        }

        val errors = consoleLogs.filter { it.level == LogLevel.ERROR || it.level == LogLevel.WARN }
        if (errors.isNotEmpty()) {
            appendLine()
            appendLine("### Console Errors & Warnings (${errors.size})")
            errors.take(8).forEach { log ->
                val loc = if (log.source != null) " (${log.source}${if (log.lineNumber != null) ":${log.lineNumber}" else ""})" else ""
                appendLine("- [${log.level.name}] ${log.message}$loc")
            }
        }

        val failedRequests = networkLogs.filter { it.isFailed }
        if (failedRequests.isNotEmpty()) {
            appendLine()
            appendLine("### Failed Network Requests (${failedRequests.size})")
            failedRequests.take(6).forEach { net ->
                val code = net.statusCode?.toString() ?: "ERR"
                val err = if (!net.error.isNullOrBlank()) " (${net.error})" else ""
                appendLine("- [$code] ${net.method} ${net.url}$err")
            }
        }

        if (snapshot != null) {
            appendLine()
            appendLine("### Captured Screenshot")
            appendLine("File: ${snapshot.screenshotPath}")
        }

        if (!userNotes.isNullOrBlank()) {
            appendLine()
            appendLine("### User Notes")
            appendLine(userNotes.trim())
        }

        appendLine()
        appendLine("### Requested Action")
        appendLine("1. Locate the component, style, or logic files responsible for this layout or runtime issue.")
        appendLine("2. Apply the necessary code changes to fix the styling, runtime errors, or API handling.")
        appendLine("3. Build and verify the project, then inform the user to refresh the preview to inspect the fix.")
        appendLine("</visual_bug_report>")
    }

    fun createChatAttachment(
        snapshot: VisualSnapshot,
        projectRoot: File,
    ): ChatAttachment? {
        val file = File(projectRoot, snapshot.screenshotPath)
        if (!file.exists() || !file.isFile) return null

        return ChatAttachment(
            id = UUID.randomUUID().toString(),
            displayName = file.name,
            relativePath = snapshot.screenshotPath,
            mimeType = "image/png",
            sizeBytes = file.length(),
        )
    }
}
