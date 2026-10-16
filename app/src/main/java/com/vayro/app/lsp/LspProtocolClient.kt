package com.vayro.app.lsp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Milestone 3 — a real Language Server Protocol client over stdio.
 *
 * Speaks genuine JSON-RPC with `Content-Length` framing: `initialize`
 * handshake, `initialized` notification, `textDocument/didOpen`/`didChange`,
 * and `textDocument/definition|references|completion|hover` requests, plus
 * `textDocument/publishDiagnostics` notification handling. Responses are
 * matched to requests by id; requests time out honestly instead of hanging.
 *
 * This is what makes [LspManager.startServer] more than a process launch:
 * the manager performs the initialize handshake through this client, and
 * routes queries through it while the server is running.
 */
class LspProtocolClient(
    /** The server process's stdout. */
    private val serverInput: InputStream,
    /** The server process's stdin. */
    private val serverOutput: OutputStream,
    private val requestTimeoutMs: Long = 15_000,
) {
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()
    private val diagnosticsListeners =
        CopyOnWriteArrayList<(uri: String, diagnostics: List<LspDiagnostic>) -> Unit>()

    @Volatile
    private var running = true
    @Volatile
    var isInitialized = false
        private set

    private val readerThread = Thread(::readLoop, "lsp-protocol-reader").apply {
        isDaemon = true
    }

    init {
        readerThread.start()
    }

    /** Registers a listener for `textDocument/publishDiagnostics` notifications. */
    fun onDiagnostics(listener: (uri: String, diagnostics: List<LspDiagnostic>) -> Unit) {
        diagnosticsListeners.add(listener)
    }

    /**
     * Performs the LSP `initialize` handshake. Returns true only when the
     * server responded with a result containing capabilities.
     */
    fun initialize(rootPath: String): Boolean {
        // ProcessHandle is not in Android's compile SDK; resolve the pid
        // purely by string-based reflection (no compile-time reference).
        val pid: Long? = runCatching {
            val handleClass = Class.forName("java.lang.ProcessHandle")
            val current = handleClass.getMethod("current").invoke(null)
            handleClass.getMethod("pid").invoke(current) as? Long
        }.getOrNull()
        val params = JSONObject()
            .put("processId", if (pid != null) pid else JSONObject.NULL)
            .put("rootUri", pathToUri(rootPath))
            .put(
                "capabilities", JSONObject()
                    .put("textDocument", JSONObject()
                        .put("definition", JSONObject())
                        .put("references", JSONObject())
                        .put("completion", JSONObject().put("completionItem", JSONObject()))
                        .put("hover", JSONObject())
                        .put("publishDiagnostics", JSONObject()))
            )
        return try {
            val response = sendRequest("initialize", params)
            val hasCapabilities = response.optJSONObject("result")
                ?.has("capabilities") == true
            if (hasCapabilities) {
                sendNotification("initialized", JSONObject())
                isInitialized = true
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun didOpen(filePath: String, languageId: String, content: String) {
        if (!isInitialized) return
        sendNotification(
            "textDocument/didOpen",
            JSONObject().put(
                "textDocument", JSONObject()
                    .put("uri", pathToUri(filePath))
                    .put("languageId", languageId)
                    .put("version", 1)
                    .put("text", content)
            )
        )
    }

    fun didChange(filePath: String, content: String, version: Int = 2) {
        if (!isInitialized) return
        sendNotification(
            "textDocument/didChange",
            JSONObject()
                .put(
                    "textDocument", JSONObject()
                        .put("uri", pathToUri(filePath))
                        .put("version", version)
                )
                .put(
                    "contentChanges", JSONArray().put(
                        JSONObject().put("text", content)
                    )
                )
        )
    }

    fun definition(filePath: String, line: Int, character: Int): List<LspLocation> {
        val result = requestDocument("textDocument/definition", filePath, line, character)
            ?: return emptyList()
        return parseLocations(result)
    }

    fun references(
        filePath: String,
        line: Int,
        character: Int,
        includeDeclaration: Boolean = true,
    ): List<LspLocation> {
        if (!isInitialized) return emptyList()
        val params = documentPositionParams(filePath, line, character)
            .put("context", JSONObject().put("includeDeclaration", includeDeclaration))
        val result = sendRequest("textDocument/references", params)
            .opt("result") ?: return emptyList()
        return parseLocations(result)
    }

    fun completion(
        filePath: String,
        line: Int,
        character: Int,
    ): List<LspCompletionItem> {
        if (!isInitialized) return emptyList()
        val result = sendRequest(
            "textDocument/completion",
            documentPositionParams(filePath, line, character)
        ).opt("result") ?: return emptyList()
        val items = when (result) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("items") ?: JSONArray()
            else -> JSONArray()
        }
        return (0 until items.length()).mapNotNull { i ->
            val obj = items.optJSONObject(i) ?: return@mapNotNull null
            LspCompletionItem(
                label = obj.optString("label", ""),
                detail = obj.optString("detail", null),
                documentation = obj.optString("documentation", null),
                insertText = obj.optString("insertText", null),
            )
        }
    }

    fun hover(filePath: String, line: Int, character: Int): LspHover? {
        if (!isInitialized) return null
        val result = sendRequest(
            "textDocument/hover",
            documentPositionParams(filePath, line, character)
        ).optJSONObject("result") ?: return null
        val contents = result.opt("contents") ?: return null
        val text = when (contents) {
            is String -> contents
            is JSONObject -> contents.optString("value", "")
            is JSONArray -> (0 until contents.length())
                .mapNotNull {
                    val item = contents.opt(it)
                    when (item) {
                        is String -> item
                        is JSONObject -> item.optString("value", null)
                        else -> null
                    }
                }.joinToString("\n")
            else -> ""
        }
        if (text.isBlank()) return null
        return LspHover(contents = text)
    }

    /** Sends `shutdown` and stops the reader. */
    fun shutdown() {
        if (!running) return
        running = false
        runCatching {
            if (isInitialized) {
                sendRequest("shutdown", JSONObject())
                sendNotification("exit", JSONObject())
            }
        }
        runCatching { serverInput.close() }
        runCatching { serverOutput.close() }
        readerThread.interrupt()
    }

    // ------------------------------------------------------------------
    // JSON-RPC plumbing
    // ------------------------------------------------------------------

    private fun documentPositionParams(
        filePath: String,
        line: Int,
        character: Int,
    ): JSONObject = JSONObject()
        .put(
            "textDocument", JSONObject().put("uri", pathToUri(filePath))
        )
        .put(
            "position", JSONObject()
                .put("line", (line - 1).coerceAtLeast(0))
                .put("character", character.coerceAtLeast(0))
        )

    private fun requestDocument(
        method: String,
        filePath: String,
        line: Int,
        character: Int,
    ): Any? {
        if (!isInitialized) return null
        return sendRequest(method, documentPositionParams(filePath, line, character))
            .opt("result")
    }

    private fun parseLocations(result: Any?): List<LspLocation> {
        val array = when (result) {
            is JSONArray -> result
            is JSONObject -> JSONArray().put(result) // single Location
            else -> return emptyList()
        }
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val uri = obj.optString("uri", "")
            val range = obj.optJSONObject("range") ?: return@mapNotNull null
            val start = range.optJSONObject("start") ?: return@mapNotNull null
            val end = range.optJSONObject("end") ?: return@mapNotNull null
            LspLocation(
                uri = uri,
                range = LspRange(
                    start = LspPosition(start.optInt("line", 0), start.optInt("character", 0)),
                    end = LspPosition(end.optInt("line", 0), end.optInt("character", 0)),
                ),
            )
        }
    }

    private fun sendRequest(method: String, params: JSONObject): JSONObject {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        val message = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        writeMessage(message)
        try {
            return future.get(requestTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.remove(id)
            throw e
        }
    }

    private fun sendNotification(method: String, params: JSONObject) {
        val message = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
        runCatching { writeMessage(message) }
    }

    @Synchronized
    private fun writeMessage(message: JSONObject) {
        val body = message.toString().toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${body.size}\r\n\r\n"
        serverOutput.write(header.toByteArray(Charsets.US_ASCII))
        serverOutput.write(body)
        serverOutput.flush()
    }

    private fun readLoop() {
        try {
            while (running) {
                val message = readMessage() ?: break
                dispatch(message)
            }
        } catch (e: Exception) {
            // Stream closed or broken: fail all pending requests honestly.
            if (running) {
                val error = JSONObject().put("error", JSONObject().put("message", e.message ?: "stream closed"))
                for ((_, future) in pending) {
                    future.complete(error)
                }
                pending.clear()
            }
        }
    }

    private fun dispatch(message: JSONObject) {
        // Response to one of our requests.
        if (message.has("id") && !message.isNull("id")) {
            val id = message.optInt("id", -1)
            pending.remove(id)?.complete(message)
            return
        }
        // Notification from the server.
        when (message.optString("method", "")) {
            "textDocument/publishDiagnostics" -> {
                val params = message.optJSONObject("params") ?: return
                val uri = params.optString("uri", "")
                val array = params.optJSONArray("diagnostics") ?: JSONArray()
                val diags = (0 until array.length()).mapNotNull { i ->
                    val obj = array.optJSONObject(i) ?: return@mapNotNull null
                    val range = obj.optJSONObject("range") ?: return@mapNotNull null
                    val start = range.optJSONObject("start") ?: return@mapNotNull null
                    LspDiagnostic(
                        range = LspRange(
                            start = LspPosition(start.optInt("line", 0), start.optInt("character", 0)),
                            end = LspPosition(
                                range.optJSONObject("end")?.optInt("line", 0) ?: 0,
                                range.optJSONObject("end")?.optInt("character", 0) ?: 0,
                            ),
                        ),
                        message = obj.optString("message", ""),
                        severity = when (obj.optInt("severity", 1)) {
                            1 -> LspDiagnosticSeverity.ERROR
                            2 -> LspDiagnosticSeverity.WARNING
                            3 -> LspDiagnosticSeverity.INFORMATION
                            else -> LspDiagnosticSeverity.HINT
                        },
                        code = obj.optString("code", null),
                        source = obj.optString("source", null),
                    )
                }
                for (listener in diagnosticsListeners) {
                    runCatching { listener(uri, diags) }
                }
            }
            // Other notifications are ignored (honest no-op, not an error).
        }
    }

    /**
     * Reads one `Content-Length`-framed message. Returns null on clean EOF.
     */
    private fun readMessage(): JSONObject? {
        var contentLength = -1
        // Read headers.
        while (true) {
            val line = readHeaderLine() ?: return null // EOF
            if (line.isEmpty()) break // blank line ends headers
            val parts = line.split(":", limit = 2)
            if (parts.size == 2 && parts[0].trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = parts[1].trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength < 0) {
            // Malformed framing: cannot recover this message; keep reading.
            return JSONObject()
        }
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = serverInput.read(body, read, contentLength - read)
            if (n == -1) throw EOFException("EOF reading LSP message body")
            read += n
        }
        return JSONObject(String(body, Charsets.UTF_8))
    }

    /** Reads one CRLF-terminated header line; null on EOF. */
    private fun readHeaderLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = serverInput.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    companion object {
        fun pathToUri(path: String): String {
            val normalized = path.replace('\\', '/')
            return if (normalized.startsWith("/")) "file://$normalized"
            else "file:///$normalized"
        }

        fun uriToPath(uri: String): String =
            uri.removePrefix("file://")
    }
}
