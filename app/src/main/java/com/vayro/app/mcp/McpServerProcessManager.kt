package com.vayro.app.mcp

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

/** Lifecycle states of an MCP server session. */
enum class McpSessionState {
    IDLE, STARTING, INITIALIZING, RUNNING, STOPPING, STOPPED, FAILED
}

/**
 * A spawned MCP server process behind a test seam. Implementations speak
 * newline-delimited JSON-RPC on stdin/stdout (the MCP stdio transport).
 */
interface McpManagedProcess {
    /** Writes one JSON-RPC message line to the server's stdin. */
    fun writeLine(line: String)

    /** Reads one stdout line, or null on timeout / EOF. */
    fun readLine(timeoutMs: Long): String?

    /** Drains stderr lines captured so far (diagnostics ring buffer). */
    fun drainStderr(): List<String>

    fun isAlive(): Boolean

    /** SIGTERM-equivalent; waits up to [graceMs], returns true if the process exited. */
    fun destroyGracefully(graceMs: Long): Boolean

    fun destroyForcibly()
    fun exitCode(): Int?
}

/** Creates [McpManagedProcess] instances. The real factory uses ProcessBuilder. */
interface McpProcessFactory {
    fun start(command: String, args: List<String>, env: Map<String, String>, workingDir: File?): McpManagedProcess
}

private const val STDERR_RING_CAPACITY = 200

/** Production [McpManagedProcess] backed by java.lang.Process. */
class RealMcpManagedProcess(private val process: Process) : McpManagedProcess {
    private val stdin: BufferedWriter = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
    private val stdoutQueue: BlockingQueue<String> = ArrayBlockingQueue(1024)
    private val stderrRing: BlockingQueue<String> = ArrayBlockingQueue(STDERR_RING_CAPACITY)

    init {
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        stdoutQueue.offer(line)
                    }
                }
            }
        }, "mcp-stdout-reader").apply { isDaemon = true }.start()

        Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!stderrRing.offer(line)) {
                            stderrRing.poll()
                            stderrRing.offer(line)
                        }
                    }
                }
            }
        }, "mcp-stderr-reader").apply { isDaemon = true }.start()
    }

    @Synchronized
    override fun writeLine(line: String) {
        stdin.write(line)
        stdin.newLine()
        stdin.flush()
    }

    override fun readLine(timeoutMs: Long): String? =
        stdoutQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun drainStderr(): List<String> {
        val out = mutableListOf<String>()
        stderrRing.drainTo(out)
        return out
    }

    override fun isAlive(): Boolean = process.isAlive

    override fun destroyGracefully(graceMs: Long): Boolean {
        process.destroy()
        return runCatching {
            process.waitFor(graceMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
    }

    override fun destroyForcibly() {
        process.destroyForcibly()
    }

    override fun exitCode(): Int? = if (process.isAlive) null else process.exitValue()
}

/** Production [McpProcessFactory] using ProcessBuilder. */
class RealMcpProcessFactory : McpProcessFactory {
    override fun start(
        command: String,
        args: List<String>,
        env: Map<String, String>,
        workingDir: File?
    ): McpManagedProcess {
        val pb = ProcessBuilder(listOf(command) + args)
        workingDir?.let { pb.directory(it) }
        pb.environment().putAll(env)
        pb.redirectErrorStream(false)
        return RealMcpManagedProcess(pb.start())
    }
}

/**
 * Hardened lifecycle for one stdio MCP server: spawn, initialize handshake,
 * health pings, bounded restarts with backoff, and graceful shutdown.
 *
 * All blocking I/O honors [ioTimeoutMs]; a server that never answers the
 * handshake fails honestly instead of hanging the caller.
 */
class McpServerSession(
    val server: McpServerInfo,
    private val processFactory: McpProcessFactory = RealMcpProcessFactory(),
    private val ioTimeoutMs: Long = 15_000,
    private val maxRestarts: Int = 3,
    private val restartBackoffMs: Long = 2_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Volatile
    var state: McpSessionState = McpSessionState.IDLE
        private set

    private var process: McpManagedProcess? = null
    private var restartsUsed = 0
    private val lock = Any()

    /** Number of restarts consumed so far (0 on a fresh session). */
    fun restartsUsed(): Int = restartsUsed

    /**
     * Starts the server process and performs the MCP initialize handshake.
     * Returns true when the session reaches RUNNING.
     */
    fun start(resolvedEnv: Map<String, String>): Boolean {
        synchronized(lock) {
            if (state == McpSessionState.RUNNING || state == McpSessionState.INITIALIZING) return true
            state = McpSessionState.STARTING
            return try {
                process = processFactory.start(
                    server.command,
                    server.args,
                    resolvedEnv,
                    server.workingDir?.let { File(it) }
                )
                state = McpSessionState.INITIALIZING
                if (performHandshake()) {
                    state = McpSessionState.RUNNING
                    true
                } else {
                    fail("initialize handshake failed")
                    false
                }
            } catch (t: Throwable) {
                fail("process start failed: ${t.message}")
                false
            }
        }
    }

    /**
     * Ensures the session is RUNNING, restarting a dead process within the
     * bounded restart budget. Returns true when RUNNING afterwards.
     */
    fun ensureRunning(resolvedEnv: Map<String, String>): Boolean {
        synchronized(lock) {
            val proc = process
            if (state == McpSessionState.RUNNING && proc != null && proc.isAlive()) return true
            if (restartsUsed >= maxRestarts) {
                fail("restart budget exhausted ($restartsUsed/$maxRestarts)")
                return false
            }
            restartsUsed++
            if (restartBackoffMs > 0) {
                try {
                    Thread.sleep(restartBackoffMs)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    fail("restart interrupted")
                    return false
                }
            }
            killProcess()
            state = McpSessionState.IDLE
            return start(resolvedEnv)
        }
    }

    /** Sends tools/list and returns the advertised tools. */
    fun listTools(): List<McpToolDefinition> {
        val result = rpc(McpProtocol.toolsListRequest()) { McpProtocol.parseToolsList(it) }
        return result
    }

    /** Invokes one tool and returns its text outcome. */
    fun callTool(toolName: String, arguments: JSONObject): McpToolCallOutcome {
        return rpc(McpProtocol.toolsCallRequest(toolName, arguments)) {
            McpProtocol.parseToolsCallResult(it)
        }
    }

    /** Health check: a ping round-trip within the I/O timeout. */
    fun ping(): Boolean {
        return try {
            rpc(McpProtocol.pingRequest()) { true }
        } catch (t: Exception) {
            false
        }
    }

    /** Graceful shutdown: SIGTERM-equivalent, then forcible kill after the grace period. */
    fun stop(graceMs: Long = 3_000) {
        synchronized(lock) {
            if (state == McpSessionState.STOPPED || state == McpSessionState.IDLE) return
            state = McpSessionState.STOPPING
            val proc = process
            process = null
            if (proc != null) {
                val exited = runCatching { proc.destroyGracefully(graceMs) }.getOrDefault(false)
                if (!exited) proc.destroyForcibly()
            }
            state = McpSessionState.STOPPED
        }
    }

    /** Last stderr lines captured from the server process (diagnostics). */
    fun diagnostics(): List<String> = process?.drainStderr() ?: emptyList()

    private fun <T> rpc(req: JSONObject, parse: (JSONObject) -> T): T {
        val proc = synchronized(lock) {
            val p = process
            if (state != McpSessionState.RUNNING || p == null || !p.isAlive()) {
                throw McpTransportException("MCP server '${server.id}' is not running (state=$state)")
            }
            p
        }
        val requestId = req.opt("id")
        val deadline = clock() + ioTimeoutMs
        proc.writeLine(req.toString())
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0) throw McpTransportException("MCP server '${server.id}' timed out waiting for a response")
            val line = proc.readLine(remaining) ?: throw McpTransportException(
                "MCP server '${server.id}' closed the connection or timed out"
            )
            if (line.isBlank()) continue
            val json = runCatching { JSONObject(line) }.getOrElse {
                throw McpProtocolException("MCP server '${server.id}' sent non-JSON: ${line.take(200)}")
            }
            // Skip notifications / responses for other ids (e.g. server-initiated pings).
            if (!json.isNull("id") && requestId != null && json.opt("id").toString() != requestId.toString()) {
                continue
            }
            if (json.isNull("id")) continue
            return when (val parsed = McpProtocol.parseResponse(json)) {
                is McpRpcResult.Success -> parse(parsed.result)
                is McpRpcResult.Failure -> throw McpRpcException(parsed.code, parsed.message)
            }
        }
    }

    private fun performHandshake(): Boolean {
        val proc = process ?: return false
        val req = McpProtocol.initializeRequest("vayro", "3.0")
        val deadline = clock() + ioTimeoutMs
        proc.writeLine(req.toString())
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0) return false
            val line = proc.readLine(remaining) ?: return false
            if (line.isBlank()) continue
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (json.isNull("id")) continue // notification; keep waiting
            if (json.opt("id").toString() != req.opt("id").toString()) continue
            val parsed = runCatching { McpProtocol.parseResponse(json) }.getOrNull() ?: return false
            if (parsed is McpRpcResult.Failure) return false
            // Handshake accepted; acknowledge per the MCP lifecycle.
            proc.writeLine(McpProtocol.initializedNotification().toString())
            return true
        }
    }

    private fun fail(reason: String) {
        killProcess()
        state = McpSessionState.FAILED
    }

    private fun killProcess() {
        val proc = process
        process = null
        if (proc != null) {
            runCatching { proc.destroyForcibly() }
        }
    }
}
