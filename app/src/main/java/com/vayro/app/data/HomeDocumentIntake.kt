package com.vayro.app.data

/**
 * v3.2.41 (Scope 3 — the Scope G remainder): home-chat document
 * intake. Until now the home composer's Files route sent every
 * document to Work. A PDF or ZIP picked at home now stays home: its
 * content is extracted with the SAME [AttachmentIntake] the workspace
 * uses (same caps, same stated bounds) and becomes part of the home
 * message the model receives — a full prompt written inside a PDF is
 * the message.
 *
 * Truth in the preview (G5): the staged [Staged.note] says exactly
 * what will be sent, and every bound that bites (the 200,000-char PDF
 * cap, the ZIP file/character limits) is written into the produced
 * context itself, never silently dropped. A PDF with no readable
 * text layer (scanned pages) is a plain visible failure here — home
 * does not fake a read; Work's page-image route remains the place
 * for those.
 */
object HomeDocumentIntake {

    enum class Kind { PDF, ZIP }

    /** A document staged in the home composer, ready to ride with the next message. */
    data class Staged(
        val name: String,
        val kind: Kind,
        /** The exact text block the model receives with the message. */
        val contextText: String,
        /** One plain line for the composer preview: what will be sent. */
        val note: String,
    )

    sealed interface Result {
        data class Ready(val staged: Staged) : Result
        data class Failed(val reason: String) : Result
    }

    /** True for the file types home chat reads itself (PDF, ZIP). */
    fun classify(mimeType: String, displayName: String): Kind? {
        val mime = mimeType.lowercase()
        val name = displayName.lowercase()
        return when {
            mime == "application/pdf" || name.endsWith(".pdf") -> Kind.PDF
            mime == "application/zip" || mime == "application/x-zip-compressed" ||
                mime == "application/x-zip" || name.endsWith(".zip") -> Kind.ZIP
            else -> null
        }
    }

    fun stagePdf(displayName: String, bytes: ByteArray): Result {
        if (bytes.isEmpty()) return Result.Failed("That PDF could not be read.")
        val result = runCatching { AttachmentIntake.extractPdfText(bytes) }.getOrNull()
            ?: return Result.Failed("That PDF could not be read.")
        if (result.text.isBlank()) {
            return Result.Failed(
                "That PDF has no readable text — it looks like scanned pages. " +
                    "Home chat can't read it yet; in Work its pages are read as images instead.",
            )
        }
        val context = buildString {
            appendLine("Attached PDF \"$displayName\" — its full text:")
            appendLine()
            append(result.text)
            if (result.truncated) {
                appendLine()
                appendLine()
                append("[Note: the PDF text was longer than ${AttachmentIntake.MAX_PDF_TEXT_CHARS} characters and was shortened at that limit.]")
            }
        }
        val note = if (result.truncated) {
            "PDF text ready — ${result.text.length} characters will be sent (the document was longer; it was shortened at the 200,000-character limit)."
        } else {
            "PDF text ready — ${result.text.length} characters will be sent with your message."
        }
        return Result.Ready(Staged(displayName, Kind.PDF, context, note))
    }

    fun stageZip(displayName: String, bytes: ByteArray): Result {
        if (bytes.isEmpty()) return Result.Failed("That ZIP could not be read.")
        val result = runCatching { AttachmentIntake.inspectZip(bytes) }.getOrNull()
            ?: return Result.Failed("That ZIP could not be read.")
        if (result.entries.isEmpty()) {
            return Result.Failed("That ZIP could not be read — it has no readable file list.")
        }
        val fileCount = result.entries.count { !it.isDirectory }
        val context = buildString {
            appendLine("Attached ZIP \"$displayName\" — its files were inspected. File list:")
            result.entries.take(MAX_CONTEXT_TREE_ENTRIES).forEach { entry ->
                appendLine("- ${entry.path}${if (entry.isDirectory) "/" else " (${entry.sizeBytes} bytes)"}")
            }
            if (result.entries.size > MAX_CONTEXT_TREE_ENTRIES) {
                appendLine("- … and ${result.entries.size - MAX_CONTEXT_TREE_ENTRIES} more entries")
            }
            result.notes.forEach { appendLine("Note: $it") }
            if (result.textContents.isNotEmpty()) {
                appendLine()
                appendLine("Key file contents:")
                result.textContents.forEach { (path, content) ->
                    appendLine("--- $path ---")
                    appendLine(content)
                }
            }
        }
        return Result.Ready(
            Staged(
                displayName,
                Kind.ZIP,
                context,
                "ZIP ready — the file list ($fileCount files) and key contents will be sent with your message.",
            ),
        )
    }

    /** Tree lines shown in the produced context; the rest are counted, not dropped silently. */
    private const val MAX_CONTEXT_TREE_ENTRIES = 300
}
