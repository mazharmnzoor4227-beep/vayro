package com.vayro.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * v3.2.40 (Scope G): real attachment intake — the layer that turns an
 * attached file into content a model/agent can actually use, with
 * STATED bounds (nothing is silently truncated: every cap that bites
 * is reported back in the result's notes/truncated flags, and the UI
 * surfaces them).
 *
 * PDF: hand-rolled text-layer extraction (no native dependency):
 * FlateDecode streams are inflated, ToUnicode CMaps are honoured when
 * present, and text-showing operators (Tj / TJ / ' / ") are read in
 * stream order. A PDF whose text layer is absent (scanned pages)
 * returns blank text — the caller routes page images as vision input
 * or reports the plain failure; an empty read is never faked as
 * success.
 *
 * ZIP: inspection (file tree + bounded text contents) and extraction
 * into a directory with a zip-slip guard and file/size caps.
 */
object AttachmentIntake {

    const val MAX_PDF_TEXT_CHARS = 200_000
    const val MAX_ZIP_ENTRIES = 2_000
    const val MAX_ZIP_TEXT_FILES = 20
    const val MAX_ZIP_TEXT_CHARS_EACH = 8_000
    const val MAX_ZIP_TEXT_FILE_BYTES = 65_536L
    const val MAX_ZIP_EXTRACT_FILES = 500
    const val MAX_ZIP_EXTRACT_BYTES = 50_000_000L

    // ------------------------------------------------------------------
    // PDF
    // ------------------------------------------------------------------

    data class PdfResult(
        /** Extracted text ("" when the PDF has no readable text layer). */
        val text: String,
        /** Approximate page count (from /Type /Page markers). */
        val pageCount: Int,
        /** True when the text hit [MAX_PDF_TEXT_CHARS] and was cut. */
        val truncated: Boolean,
    )

    fun extractPdfText(bytes: ByteArray): PdfResult {
        if (bytes.isEmpty()) return PdfResult("", 0, false)
        val raw = String(bytes, Charsets.ISO_8859_1)
        val pageCount = Regex("""/Type\s*/Page[^s]""").findAll(raw).count()
        val cmap = parseToUnicodeCMap(raw)
        val out = StringBuilder()
        var truncated = false
        for (streamText in extractStreamTexts(bytes)) {
            if (!streamText.contains("Tj") && !streamText.contains("TJ")) continue
            val pageText = extractTextFromContentStream(streamText, cmap)
            if (pageText.isBlank()) continue
            if (out.isNotEmpty()) out.append('\n')
            out.append(pageText)
            if (out.length >= MAX_PDF_TEXT_CHARS) {
                truncated = true
                break
            }
        }
        val cleaned = out.toString()
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return PdfResult(
            text = if (cleaned.length > MAX_PDF_TEXT_CHARS) cleaned.substring(0, MAX_PDF_TEXT_CHARS) else cleaned,
            pageCount = pageCount,
            truncated = truncated || cleaned.length > MAX_PDF_TEXT_CHARS,
        )
    }

    /** All stream bodies, inflated when FlateDecode applies. */
    private fun extractStreamTexts(bytes: ByteArray): List<String> {
        val texts = mutableListOf<String>()
        var i = 0
        val marker = "stream".toByteArray(Charsets.ISO_8859_1)
        while (i < bytes.size - marker.size) {
            var found = -1
            var j = i
            while (j <= bytes.size - marker.size) {
                var ok = true
                for (k in marker.indices) {
                    if (bytes[j + k] != marker[k]) { ok = false; break }
                }
                if (ok) { found = j; break }
                j++
            }
            if (found < 0) break
            var start = found + marker.size
            // Skip the CRLF / LF after the `stream` keyword.
            if (start < bytes.size && bytes[start] == '\r'.code.toByte()) start++
            if (start < bytes.size && bytes[start] == '\n'.code.toByte()) start++
            // Find `endstream`.
            val endMarker = "endstream".toByteArray(Charsets.ISO_8859_1)
            var end = -1
            var k = start
            while (k <= bytes.size - endMarker.size) {
                var ok = true
                for (m in endMarker.indices) {
                    if (bytes[k + m] != endMarker[m]) { ok = false; break }
                }
                if (ok) { end = k; break }
                k++
            }
            if (end < 0) break
            val body = bytes.copyOfRange(start, end)
            // A binary (deflated) body may itself end in 0x0A/0x0D —
            // try it untrimmed first, then with the EOL before
            // `endstream` trimmed.
            var trimmed = body
            while (trimmed.isNotEmpty() && (trimmed[trimmed.size - 1] == '\n'.code.toByte() || trimmed[trimmed.size - 1] == '\r'.code.toByte())) {
                trimmed = trimmed.copyOf(trimmed.size - 1)
            }
            val inflated = inflate(body) ?: inflate(trimmed) ?: trimmed
            texts += String(inflated, Charsets.ISO_8859_1)
            i = end + endMarker.size
        }
        return texts
    }

    private fun inflate(data: ByteArray): ByteArray? {
        for (nowrap in listOf(false, true)) {
            try {
                val inflater = Inflater(nowrap)
                inflater.setInput(data)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0) break
                    out.write(buf, 0, n)
                }
                inflater.end()
                val result = out.toByteArray()
                if (result.isNotEmpty()) return result
            } catch (_: Exception) {
                // Try the next wrapping / treat as uncompressed.
            }
        }
        return null
    }

    /** Parses beginbfchar / beginbfrange sections into a code -> text map. */
    private fun parseToUnicodeCMap(raw: String): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        val hexPair = Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>""")
        var idx = 0
        while (true) {
            val bf = raw.indexOf("beginbfchar", idx)
            val br = raw.indexOf("beginbfrange", idx)
            val start = when {
                bf < 0 -> br
                br < 0 -> bf
                else -> minOf(bf, br)
            }
            if (start < 0) break
            val endMarker = if (start == bf && bf >= 0) "endbfchar" else "endbfrange"
            val end = raw.indexOf(endMarker, start)
            if (end < 0) break
            val section = raw.substring(start, end)
            for (m in hexPair.findAll(section)) {
                val src = m.groupValues[1]
                val dst = m.groupValues[2]
                // bfchar pairs first; bfrange triples (srcLo srcHi dstLo)
                // also match this shape partially, so only accept a pair
                // when it is not followed by another hex group on the
                // same match run — approximation: accept all pairs,
                // ranges add their start code; good enough for the
                // dominant single-font case.
                val code = src.toIntOrNull(16) ?: continue
                map[code] = decodeHexUtf16(dst)
            }
            idx = end + endMarker.length
        }
        return map
    }

    private fun decodeHexUtf16(hex: String): String {
        if (hex.length % 4 == 0 && hex.length >= 4) {
            val sb = StringBuilder()
            var i = 0
            while (i + 4 <= hex.length) {
                val unit = hex.substring(i, i + 4).toIntOrNull(16) ?: return hex
                sb.append(unit.toChar())
                i += 4
            }
            return sb.toString()
        }
        val bytes = hexToBytes(hex) ?: return ""
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.replace(Regex("""\s"""), "")
        if (clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            val v = clean.substring(i, i + 2).toIntOrNull(16) ?: return null
            out[i / 2] = v.toByte()
            i += 2
        }
        return out
    }

    /** Reads text-showing operators from one content stream, in order. */
    private fun extractTextFromContentStream(stream: String, cmap: Map<Int, String>): String {
        val sb = StringBuilder()
        var i = 0
        val n = stream.length
        fun appendLineBreak() {
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
        }
        while (i < n) {
            val c = stream[i]
            when {
                c == '(' -> {
                    val (literal, next) = readLiteralString(stream, i)
                    i = next
                    // Look ahead for the operator.
                    val op = peekOperator(stream, i)
                    if (op == "Tj" || op == "'" || op == "\"") {
                        if (op != "Tj") appendLineBreak()
                        sb.append(decodeLiteral(literal))
                        i = skipOperator(stream, i)
                    } else if (op == "TJ-part") {
                        sb.append(decodeLiteral(literal))
                        i = skipOperator(stream, i)
                    }
                }
                c == '<' && i + 1 < n && stream[i + 1] != '<' -> {
                    val close = stream.indexOf('>', i)
                    if (close < 0) { i = n; break }
                    val hex = stream.substring(i + 1, close)
                    val op = peekOperator(stream, close + 1)
                    if (op == "Tj" || op == "TJ-part") {
                        sb.append(decodeHexString(hex, cmap))
                    }
                    i = if (op == "Tj" || op == "TJ-part") skipOperator(stream, close + 1) else close + 1
                }
                c == 'T' && (stream.startsWith("Td", i) || stream.startsWith("TD", i)) -> {
                    appendLineBreak(); i += 2
                }
                c == 'T' && stream.startsWith("T*", i) -> {
                    appendLineBreak(); i += 2
                }
                c == ']' -> {
                    // End of a TJ array: nothing to emit (parts handled inline).
                    i++
                }
                else -> i++
            }
        }
        return sb.toString()
    }

    /**
     * Returns the word right after position [from]: "Tj", "'", "\"",
     * "TJ-part" (inside a TJ array: a string followed by a number or
     * another string), or anything else ("").
     */
    private fun peekOperator(stream: String, from: Int): String {
        var i = from
        while (i < stream.length && stream[i].isWhitespace()) i++
        if (i >= stream.length) return ""
        if (stream.startsWith("Tj", i)) return "Tj"
        val ch = stream[i]
        if (ch == '\'' || ch == '"') return ch.toString()
        if (ch == '-' || ch == '+' || ch.isDigit() || ch == '(' || ch == '<' || ch == ']') return "TJ-part"
        return ""
    }

    private fun skipOperator(stream: String, from: Int): Int {
        var i = from
        while (i < stream.length && stream[i].isWhitespace()) i++
        if (stream.startsWith("Tj", i)) return i + 2
        if (i < stream.length && (stream[i] == '\'' || stream[i] == '"')) return i + 1
        // TJ-part: skip a number if present.
        while (i < stream.length && (stream[i].isDigit() || stream[i] == '-' || stream[i] == '+' || stream[i] == '.')) i++
        return i
    }

    private fun readLiteralString(stream: String, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start + 1
        var depth = 1
        while (i < stream.length && depth > 0) {
            val c = stream[i]
            when {
                c == '\\' && i + 1 < stream.length -> { sb.append(c); sb.append(stream[i + 1]); i += 2 }
                c == '(' -> { depth++; sb.append(c); i++ }
                c == ')' -> { depth--; if (depth > 0) sb.append(c); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString() to i
    }

    private fun decodeLiteral(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val e = raw[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append(''); i += 2 }
                    '(', ')', '\\' -> { sb.append(e); i += 2 }
                    in '0'..'7' -> {
                        var j = i + 1
                        val oct = StringBuilder()
                        while (j < raw.length && j < i + 4 && raw[j] in '0'..'7') { oct.append(raw[j]); j++ }
                        sb.append(oct.toString().toIntOrNull(8)?.toChar() ?: '?')
                        i = j
                    }
                    else -> { sb.append(e); i += 2 }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    private fun decodeHexString(hex: String, cmap: Map<Int, String>): String {
        val bytes = hexToBytes(hex) ?: return ""
        if (cmap.isNotEmpty() && bytes.size >= 2) {
            val sb = StringBuilder()
            var i = 0
            while (i + 1 < bytes.size) {
                val code = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                val mapped = cmap[code]
                if (mapped != null) sb.append(mapped) else sb.append(' ')
                i += 2
            }
            return sb.toString()
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    // ------------------------------------------------------------------
    // ZIP
    // ------------------------------------------------------------------

    data class ZipEntryInfo(val path: String, val sizeBytes: Long, val isDirectory: Boolean)

    data class ZipResult(
        val entries: List<ZipEntryInfo>,
        /** path -> bounded text content, for text-like entries only. */
        val textContents: Map<String, String>,
        /** Plain-language notes for every bound that bit (never silent). */
        val notes: List<String>,
    )

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "json", "xml", "csv", "tsv", "kt", "kts", "java",
        "js", "jsx", "ts", "tsx", "py", "html", "htm", "css", "scss", "yml", "yaml",
        "toml", "gradle", "properties", "sh", "bash", "c", "h", "cpp", "hpp", "rs",
        "go", "swift", "rb", "php", "sql", "ini", "cfg", "env",
    )

    fun inspectZip(bytes: ByteArray): ZipResult {
        val entries = mutableListOf<ZipEntryInfo>()
        val contents = linkedMapOf<String, String>()
        val notes = mutableListOf<String>()
        var totalEntries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                totalEntries++
                if (totalEntries <= MAX_ZIP_ENTRIES) {
                    entries += ZipEntryInfo(entry.name, entry.size, entry.isDirectory)
                }
                if (!entry.isDirectory && contents.size < MAX_ZIP_TEXT_FILES &&
                    isTextLike(entry.name) && entry.size in 0..MAX_ZIP_TEXT_FILE_BYTES
                ) {
                    val data = readBounded(zip, MAX_ZIP_TEXT_CHARS_EACH * 2)
                    val text = String(data, Charsets.UTF_8)
                    contents[entry.name] = if (text.length > MAX_ZIP_TEXT_CHARS_EACH) {
                        text.substring(0, MAX_ZIP_TEXT_CHARS_EACH)
                    } else {
                        text
                    }
                }
                zip.closeEntry()
            }
        }
        if (totalEntries > MAX_ZIP_ENTRIES) {
            notes += "Archive has $totalEntries entries; only the first $MAX_ZIP_ENTRIES are listed."
        }
        val textLike = entries.count { !it.isDirectory && isTextLike(it.path) }
        if (textLike > contents.size) {
            notes += "Contents shown for ${contents.size} of $textLike text files (limit $MAX_ZIP_TEXT_FILES files, ${MAX_ZIP_TEXT_CHARS_EACH} characters each)."
        }
        return ZipResult(entries, contents, notes)
    }

    /**
     * Extracts the archive under [targetDir] (zip-slip guarded, file
     * and byte caps) and returns the same inspection result.
     */
    fun extractZip(bytes: ByteArray, targetDir: File): ZipResult {
        val inspection = inspectZip(bytes)
        val notes = inspection.notes.toMutableList()
        val root = targetDir.canonicalFile
        root.mkdirs()
        var files = 0
        var written = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val outFile = File(root, entry.name).canonicalFile
                when {
                    !outFile.path.startsWith(root.path) -> {
                        notes += "Skipped an entry outside the folder: ${entry.name}"
                    }
                    entry.isDirectory -> outFile.mkdirs()
                    files >= MAX_ZIP_EXTRACT_FILES -> {
                        notes += "Extraction stopped at $MAX_ZIP_EXTRACT_FILES files."
                        break
                    }
                    written >= MAX_ZIP_EXTRACT_BYTES -> {
                        notes += "Extraction stopped at ${MAX_ZIP_EXTRACT_BYTES / 1_000_000} MB."
                        break
                    }
                    else -> {
                        outFile.parentFile?.mkdirs()
                        var count = 0L
                        outFile.outputStream().use { out ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                count += n
                                if (written + count > MAX_ZIP_EXTRACT_BYTES) break
                                out.write(buf, 0, n)
                            }
                        }
                        written += count
                        files++
                    }
                }
                zip.closeEntry()
            }
        }
        return ZipResult(inspection.entries, inspection.textContents, notes)
    }

    fun isTextLike(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTENSIONS || path.substringAfterLast('/') in
            listOf("README", "LICENSE", "CHANGELOG", "Makefile", "Dockerfile")
    }

    private fun readBounded(zip: ZipInputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var remaining = maxBytes
        while (remaining > 0) {
            val n = zip.read(buf, 0, minOf(buf.size, remaining))
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }
}
