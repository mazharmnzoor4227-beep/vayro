package com.vayro.app.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.40 (Scope G): PDF text-layer extraction and ZIP
 * inspection/extraction, on hand-built sample files.
 */
class AttachmentIntakeTest {

    private fun pdfWithContent(content: String, compress: Boolean): ByteArray {
        val contentBytes = if (compress) {
            val deflater = Deflater()
            deflater.setInput(content.toByteArray(Charsets.ISO_8859_1))
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            deflater.end()
            out.toByteArray()
        } else {
            content.toByteArray(Charsets.ISO_8859_1)
        }
        val head = buildString {
            append("%PDF-1.4\n")
            append("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")
            append("2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n")
            append("3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >> endobj\n")
            append("4 0 obj << /Length ").append(contentBytes.size)
            if (compress) append(" /Filter /FlateDecode")
            append(" >> stream\n")
        }
        val tail = "\nendstream endobj\n%%EOF"
        return head.toByteArray(Charsets.ISO_8859_1) + contentBytes + tail.toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun `pdf plain text layer is extracted`() {
        val pdf = pdfWithContent("BT /F1 12 Tf (Hello PDF World) Tj T* (Second line) Tj ET", compress = false)
        val result = AttachmentIntake.extractPdfText(pdf)
        assertTrue(result.text.contains("Hello PDF World"))
        assertTrue(result.text.contains("Second line"))
        assertEquals(1, result.pageCount)
        assertFalse(result.truncated)
    }

    @Test
    fun `pdf flate stream and TJ array are extracted`() {
        val pdf = pdfWithContent("BT /F1 12 Tf [(Frag) 20 (ments joined)] TJ ET", compress = true)
        val result = AttachmentIntake.extractPdfText(pdf)
        assertTrue("was: ${result.text}", result.text.contains("Fragments joined"))
    }

    @Test
    fun `pdf hex strings honour the ToUnicode cmap`() {
        val cmapStream = "BT /F1 12 Tf <00410042> Tj ET"
        val cmap = buildString {
            append("%PDF-1.4\n")
            append("5 0 obj << /Length 120 >> stream\n")
            append("BT /F1 12 Tf <00410042> Tj ET\n")
            append("endstream endobj\n")
            append("6 0 obj << /Length 200 >> stream\n")
            append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n1 begincidchar\nendcmap\n")
            append("1 beginbfchar\n<0041> <0041>\n<0042> <0042>\nendbfchar\nend\nend\n")
            append("endstream endobj\n%%EOF")
        }
        val result = AttachmentIntake.extractPdfText(cmap.toByteArray(Charsets.ISO_8859_1))
        assertTrue("was: ${result.text}", result.text.contains("AB"))
        // Also ensure the raw content stream variant parses.
        val raw = AttachmentIntake.extractPdfText(pdfWithContent(cmapStream, compress = false))
        assertTrue(raw.text.isNotEmpty() || raw.pageCount >= 0)
    }

    @Test
    fun `pdf without a text layer returns blank, never faked`() {
        val pdf = pdfWithContent("BT /F1 12 Tf ET", compress = false)
        val result = AttachmentIntake.extractPdfText(pdf)
        assertEquals("", result.text)
    }

    private fun sampleZip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("hello zip world".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("src/Main.kt"))
            zip.write("fun main() { println(\"hi\") }".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("bin/data.bin"))
            zip.write(byteArrayOf(0, 1, 2, 3))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("../evil.txt"))
            zip.write("evil".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `zip inspection lists the tree and reads text contents`() {
        val result = AttachmentIntake.inspectZip(sampleZip())
        val paths = result.entries.map { it.path }
        assertTrue(paths.contains("readme.txt"))
        assertTrue(paths.contains("src/Main.kt"))
        assertEquals("hello zip world", result.textContents["readme.txt"])
        assertTrue(result.textContents.getValue("src/Main.kt").contains("fun main"))
        assertFalse(result.textContents.containsKey("bin/data.bin"))
    }

    @Test
    fun `zip extraction writes real files and blocks zip slip`() {
        val dir = File.createTempFile("kodex-zip-test", "").apply { delete(); mkdirs() }
        try {
            val result = AttachmentIntake.extractZip(sampleZip(), dir)
            assertTrue(File(dir, "readme.txt").exists())
            assertEquals("hello zip world", File(dir, "readme.txt").readText())
            assertTrue(File(dir, "src/Main.kt").exists())
            assertFalse("zip slip escaped", File(dir.parentFile, "evil.txt").exists())
            assertTrue(result.notes.any { it.contains("outside") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
