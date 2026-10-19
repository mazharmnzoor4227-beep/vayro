package com.vayro.app.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.41 (Scope 3): home-chat document intake — PDFs and ZIPs picked
 * at home become home message content with the same caps the
 * workspace uses, and a no-text (scanned) PDF is a plain failure,
 * never a faked read.
 */
class HomeDocumentIntakeTest {

    private fun pdfWithContent(content: String): ByteArray {
        val contentBytes = content.toByteArray(Charsets.ISO_8859_1)
        val head = buildString {
            append("%PDF-1.4\n")
            append("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")
            append("2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n")
            append("3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >> endobj\n")
            append("4 0 obj << /Length ").append(contentBytes.size).append(" >> stream\n")
        }
        val tail = "\nendstream endobj\n%%EOF"
        return head.toByteArray(Charsets.ISO_8859_1) + contentBytes + tail.toByteArray(Charsets.ISO_8859_1)
    }

    private fun zipWith(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `classify routes pdf and zip only`() {
        assertEquals(HomeDocumentIntake.Kind.PDF, HomeDocumentIntake.classify("application/pdf", "a.bin"))
        assertEquals(HomeDocumentIntake.Kind.PDF, HomeDocumentIntake.classify("", "Report.PDF"))
        assertEquals(HomeDocumentIntake.Kind.ZIP, HomeDocumentIntake.classify("application/zip", "a.bin"))
        assertEquals(HomeDocumentIntake.Kind.ZIP, HomeDocumentIntake.classify("application/octet-stream", "bundle.zip"))
        assertEquals(null, HomeDocumentIntake.classify("image/png", "photo.png"))
        assertEquals(null, HomeDocumentIntake.classify("text/plain", "notes.txt"))
    }

    @Test
    fun `pdf text becomes the staged message content`() {
        val pdf = pdfWithContent("BT /F1 12 Tf (Write a poem about Karachi rain) Tj ET")
        val result = HomeDocumentIntake.stagePdf("prompt.pdf", pdf)
        val ready = result as HomeDocumentIntake.Result.Ready
        assertTrue(ready.staged.contextText.contains("Write a poem about Karachi rain"))
        assertTrue(ready.staged.contextText.contains("prompt.pdf"))
        assertTrue(ready.staged.note.contains("will be sent"))
        assertEquals(HomeDocumentIntake.Kind.PDF, ready.staged.kind)
    }

    @Test
    fun `long pdf is capped and the cap is written into the context`() {
        val longLine = "x".repeat(500)
        val chunk = "BT /F1 12 Tf (" + longLine + ") Tj ET\n"
        val pdf = pdfWithContent(chunk.repeat(600)) // ~300k chars of text
        val ready = HomeDocumentIntake.stagePdf("big.pdf", pdf) as HomeDocumentIntake.Result.Ready
        assertTrue(ready.staged.contextText.contains("was shortened at that limit"))
        assertTrue(ready.staged.note.contains("shortened"))
    }

    @Test
    fun `scanned pdf with no text layer is a plain failure`() {
        val pdf = pdfWithContent("BT ET") // a page with no text-showing operators
        val result = HomeDocumentIntake.stagePdf("scan.pdf", pdf)
        val failed = result as HomeDocumentIntake.Result.Failed
        assertTrue(failed.reason.contains("no readable text"))
    }

    @Test
    fun `garbage bytes are a plain failure, never a crash`() {
        assertTrue(HomeDocumentIntake.stagePdf("x.pdf", byteArrayOf(1, 2, 3)) is HomeDocumentIntake.Result.Failed)
        assertTrue(HomeDocumentIntake.stageZip("x.zip", byteArrayOf(1, 2, 3)) is HomeDocumentIntake.Result.Failed)
    }

    @Test
    fun `zip stages its tree and key contents with stated bounds`() {
        val zip = zipWith(
            mapOf(
                "readme.txt" to "Hello from inside the zip",
                "src/Main.kt" to "fun main() = println(\"hi\")",
            ),
        )
        val ready = HomeDocumentIntake.stageZip("bundle.zip", zip) as HomeDocumentIntake.Result.Ready
        assertTrue(ready.staged.contextText.contains("readme.txt"))
        assertTrue(ready.staged.contextText.contains("Hello from inside the zip"))
        assertTrue(ready.staged.contextText.contains("src/Main.kt"))
        assertTrue(ready.staged.note.contains("file list"))
    }

    @Test
    fun `message model text includes the document content`() {
        val message = HomeChatMessage(
            fromUser = true,
            text = "Summarize this",
            docName = "report.pdf",
            docText = "Attached PDF \"report.pdf\" — its full text:\n\nTHE REPORT BODY",
        )
        val modelText = message.modelText()
        assertTrue(modelText.contains("Summarize this"))
        assertTrue(modelText.contains("THE REPORT BODY"))
    }
}
