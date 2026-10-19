package com.vayro.app.mcp

import com.vayro.app.runtime.RuntimeInstaller
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * v3.2.39 (Scope 1): REAL stdio MCP transport inside the app's Linux guest.
 *
 * Why this exists: the app process itself has no `npx` / `uvx` on its PATH —
 * those runners live inside the proot guest the agents use. The old factory
 * ([RealMcpProcessFactory]) spawned with ProcessBuilder on the host, so every
 * stdio server failed to exec. This factory spawns the server through
 * [RuntimeInstaller.process] — the exact guest launcher the agent runtimes
 * use — with one protocol-critical difference:
 *
 * The native spawn merges the child's stdout and stderr into one output
 * file, which would corrupt the NDJSON stream (stderr chatter is not JSON).
 * So the guest command is a small POSIX sh wrapper that redirects the
 * SERVER's stderr to its own log file inside the guest workspace BEFORE
 * exec'ing the server. After that redirect, the native output file carries
 * the server's stdout ONLY — pure NDJSON — and stderr is tailed separately
 * (host side, through the workspace bind) into the diagnostics ring buffer.
 *
 * stdin stays the native spawn's live pipe, so writes are real-time.
 *
 * Runner reality is checked BEFORE spawning: a guest probe answers which of
 * the server's program / python3 / node exist. A missing runner fails with a
 * plain message naming the app component that provides it (see
 * [mcpRunnerProblem]) instead of a cryptic exec error. `uvx` gets one
 * bootstrap chance: with Python present, the wrapper installs the `uv`
 * package once via pip (its output goes to the stderr log, never stdout).
 */

private const val TAIL_POLL_MS = 40L
private const val STDERR_RING_CAPACITY = 200
private const val PROBE_TIMEOUT_MS = 45_000L

/**
 * v3.2.40 (Scope C): the environment every guest MCP server spawns with.
 *
 * PYTHONUNBUFFERED is FORCED, and this is the device root cause it fixes:
 * the guest's stdout is redirected to a plain FILE (the native spawn's
 * output file), and a Python process whose stdout is a file BLOCK-buffers
 * it — the MCP initialize answer (a few hundred bytes) sat in the
 * server's stdio buffer, never reached the tail reader, and discovery
 * died by timeout even though the server was running fine. Node's stdout
 * writes are unbuffered syscalls, so Node servers never hit this; there
 * is no Node equivalent to set. The value is forced (not defaulted) on
 * purpose: a server config that set it to 0 would silently re-break the
 * transport. Pure — unit-tested.
 */
internal fun mcpSpawnEnvironment(env: Map<String, String>): Map<String, String> =
    buildMap {
        putAll(env)
        put("PYTHONUNBUFFERED", "1")
        put("PYTHONIOENCODING", "utf-8")
    }

/** Which runtime component a missing MCP runner belongs to, for messages. */
internal fun mcpRunnerComponent(program: String): String? = when (program) {
    "uvx", "uv" -> "Python"
    "npx", "node", "npm" -> "Node.js (part of the Core runtime)"
    else -> null
}

/**
 * Decides whether a server can start, given what the guest probe found.
 * Returns null when the start may proceed, or the plain-language problem
 * to show the user (never a raw exec error). Pure — unit-tested.
 */
internal fun mcpRunnerProblem(
    program: String,
    programFound: Boolean,
    hasPython: Boolean,
    hasNode: Boolean,
): String? {
    if (programFound) return null
    return when (program) {
        // uvx is a pip-installable runner: with Python present the guest
        // wrapper bootstraps it, so only a Python-less guest is a problem.
        "uvx", "uv" -> if (hasPython) {
            null
        } else {
            "This server needs the Python component — it runs with the 'uvx' " +
                "tool runner, which is not in the Linux runtime yet. Install or " +
                "update the Python component (Settings → Environment updates), " +
                "then try again."
        }
        "npx", "node", "npm" -> "This server needs Node.js, which is part of " +
            "the Core runtime, but Node.js was not found inside the Linux " +
            "runtime. Finish or repair the runtime setup (Settings → Linux " +
            "runtime), then try again."
        else -> "The program '$program' was not found inside the Linux " +
            "runtime, so this server cannot start. The server's command must " +
            "exist in the runtime."
    }
}

/**
 * Tails a growing file and emits complete lines (UTF-8, CRLF-tolerant).
 *
 * The native spawn writes the guest's stdout to a plain file; there is no
 * pipe to block on. This reader polls the file, buffers raw bytes, and
 * splits on '\n' at the BYTE level so a multi-byte UTF-8 character split
 * across two writes is never mangled. While [isAlive] reports the producer
 * is running, EOF just means "no new data yet". Once the producer is dead,
 * the remaining bytes are drained (a trailing partial line is emitted too)
 * and the reader ends.
 */
internal class FileTailLineReader(
    private val file: File,
    private val isAlive: () -> Boolean,
    private val onLine: (String) -> Unit,
) : Runnable {
    @Volatile
    private var stopped = false

    fun stop() {
        stopped = true
    }

    override fun run() {
        var pending = ByteArray(0)
        var stream: FileInputStream? = null
        try {
            // The native spawn creates the output file as the child starts;
            // wait briefly for it to appear instead of failing the session.
            var waits = 0
            while (stream == null && !stopped) {
                stream = runCatching { FileInputStream(file) }.getOrNull()
                if (stream == null) {
                    if (!isAlive() && waits > 25) return
                    Thread.sleep(TAIL_POLL_MS)
                    waits++
                }
            }
            val input = stream ?: return
            val buffer = ByteArray(8192)
            while (!stopped) {
                val read = runCatching { input.read(buffer) }.getOrDefault(-1)
                if (read > 0) {
                    pending = appendAndEmit(pending, buffer, read)
                } else {
                    if (!isAlive()) {
                        // Producer finished: one last drain, then flush any
                        // trailing partial line and end.
                        val extra = runCatching { input.read(buffer) }.getOrDefault(-1)
                        if (extra > 0) pending = appendAndEmit(pending, buffer, extra)
                        if (pending.isNotEmpty()) {
                            onLine(String(pending, Charsets.UTF_8).trimEnd('\r'))
                        }
                        return
                    }
                    Thread.sleep(TAIL_POLL_MS)
                }
            }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { stream?.close() }
        }
    }

    /** Appends bytes, emits every complete line, returns the remainder. */
    private fun appendAndEmit(pending: ByteArray, buffer: ByteArray, read: Int): ByteArray {
        var data = pending + buffer.copyOf(read)
        while (true) {
            val nl = data.indexOf('\n'.code.toByte())
            if (nl < 0) return data
            val lineBytes = data.copyOfRange(0, nl)
            onLine(String(lineBytes, Charsets.UTF_8).trimEnd('\r'))
            data = data.copyOfRange(nl + 1, data.size)
        }
    }
}

/**
 * An MCP server process running inside the Linux guest. stdout is tailed
 * from the native spawn's output file (protocol only — see the factory's
 * class doc), stderr from the guest-side stderr log in the workspace.
 */
class GuestMcpManagedProcess(
    private val process: Process,
    private val stdoutFile: File,
    private val stderrFile: File,
) : McpManagedProcess {
    private val stdoutQueue: BlockingQueue<String> = ArrayBlockingQueue(1024)
    private val stderrRing: BlockingQueue<String> = ArrayBlockingQueue(STDERR_RING_CAPACITY)

    private val stdoutReader = FileTailLineReader(stdoutFile, { process.isAlive }) { line ->
        stdoutQueue.offer(line)
    }
    private val stderrReader = FileTailLineReader(stderrFile, { process.isAlive }) { line ->
        if (!stderrRing.offer(line)) {
            stderrRing.poll()
            stderrRing.offer(line)
        }
    }

    init {
        Thread(stdoutReader, "mcp-guest-stdout").apply { isDaemon = true }.start()
        Thread(stderrReader, "mcp-guest-stderr").apply { isDaemon = true }.start()
    }

    @Synchronized
    override fun writeLine(line: String) {
        val out = process.outputStream
        out.write((line + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
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
        val exited = runCatching {
            process.waitFor(graceMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (exited) cleanupFiles()
        return exited
    }

    override fun destroyForcibly() {
        process.destroyForcibly()
        cleanupFiles()
    }

    override fun exitCode(): Int? =
        if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull()

    private fun cleanupFiles() {
        // Diagnostics live in the in-memory rings by now; the files are
        // scratch. Best-effort cleanup.
        runCatching { stdoutFile.delete() }
        runCatching { stderrFile.delete() }
    }
}

/**
 * Production [McpProcessFactory] that runs stdio MCP servers inside the
 * app's Linux guest (proot) — the only place their runners exist.
 */
class GuestMcpProcessFactory(
    private val installer: RuntimeInstaller,
    private val workspaceDir: File,
    private val cacheDir: File,
) : McpProcessFactory {

    /** Runners already confirmed present in the guest (positives only). */
    private val confirmedPresent = ConcurrentHashMap.newKeySet<String>()

    override fun start(
        command: String,
        args: List<String>,
        env: Map<String, String>,
        workingDir: File?,
    ): McpManagedProcess {
        if (!installer.isInstalled()) {
            throw McpConfigurationException(
                "spawn: The Linux runtime is not set up yet, so MCP servers cannot " +
                    "start. Finish the runtime setup (reopen Kodex and complete " +
                    "setup, or Settings → Linux runtime), then try again."
            )
        }
        val argv = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() } + args
        require(argv.isNotEmpty()) { "MCP server has an empty command" }
        val program = argv.first().substringAfterLast('/')

        checkRunner(program, env)

        val runtime = installer.installedRuntime()
        val stamp = System.nanoTime().toString()
        val stderrName = "stderr-$stamp.log"
        val guestStderrPath = "/workspace/.mcp/$stderrName"
        val hostStderrFile = File(workspaceDir, ".mcp/$stderrName")
        val stdoutFile = File(cacheDir, "mcp-stdout-$stamp.log")

        val guestCommand = buildList {
            add("/bin/sh")
            add("-c")
            add(MCP_GUEST_WRAPPER_SCRIPT)
            add("kodex-mcp")
            add(guestStderrPath)
            addAll(argv)
        }
        val process = try {
            installer.process(
                proot = runtime.proot,
                rootfs = runtime.rootfs,
                workspace = workspaceDir,
                environment = mcpSpawnEnvironment(env),
                guestCommand = guestCommand,
                guestWorkspacePath = "/workspace",
                outputFile = stdoutFile,
            )
        } catch (t: Throwable) {
            throw McpConfigurationException(
                "spawn: The server process could not be launched inside the Linux " +
                    "runtime: ${t.message ?: "unknown launch error"}"
            )
        }
        return GuestMcpManagedProcess(process, stdoutFile, hostStderrFile)
    }

    /**
     * Fails fast with a plain, component-mapped message when the server's
     * runner is missing from the guest. A probe boot costs a second or two;
     * confirmed runners are cached for the life of the app process.
     */
    private fun checkRunner(program: String, env: Map<String, String>) {
        if (program in confirmedPresent) return
        val probe = probeGuest(program, env) ?: return // probe failed: let the real spawn speak
        val problem = mcpRunnerProblem(
            program = program,
            programFound = probe[program] == true,
            hasPython = probe["python3"] == true,
            hasNode = probe["node"] == true,
        )
        if (problem != null) throw McpConfigurationException("probe: $problem")
        if (probe[program] == true) confirmedPresent.add(program)
    }

    /**
     * Runs `command -v` for the program + python3 + node inside the guest.
     * v3.2.40 (Scope C): the probe now runs with the SAME environment map
     * the real spawn will get (probe/spawn parity — a PATH or HOME set in
     * the server's own config can no longer make the probe answer about
     * a different guest than the one that spawns), and it also counts a
     * previously bootstrapped `$HOME/.local/bin/<program>` as found,
     * exactly where the wrapper looks for it.
     */
    private fun probeGuest(program: String, env: Map<String, String>): Map<String, Boolean>? {
        val runtime = runCatching { installer.installedRuntime() }.getOrNull() ?: return null
        val outFile = File(cacheDir, "mcp-probe-${System.nanoTime()}.log")
        val process = runCatching {
            installer.process(
                proot = runtime.proot,
                rootfs = runtime.rootfs,
                workspace = workspaceDir,
                environment = mcpSpawnEnvironment(env),
                guestCommand = listOf("/bin/sh", "-c", MCP_PROBE_SCRIPT, "kodex-mcp-probe", program),
                guestWorkspacePath = "/workspace",
                outputFile = outFile,
            )
        }.getOrNull() ?: return null
        val finished = runCatching {
            process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            return null
        }
        val text = runCatching { outFile.readText() }.getOrDefault("")
        runCatching { outFile.delete() }
        if (text.isBlank()) return null
        val found = mutableMapOf<String, Boolean>()
        text.lineSequence().forEach { line ->
            val parts = line.trim().split(" ", limit = 2)
            if (parts.size == 2) {
                when (parts[0]) {
                    "HAVE" -> found[parts[1]] = true
                    "MISS" -> found.putIfAbsent(parts[1], false)
                }
            }
        }
        return found
    }

}

/**
 * Guest wrapper (POSIX sh — v3.2.40 Scope C: it runs under the guest's
 * /bin/sh, so it must never grow a bashism). Arguments after the script
 * name are <stderr-path> <program> [args...]. stderr is redirected to
 * its own file BEFORE anything else runs, so the spawn's output file
 * carries stdout only. All wrapper chatter goes to stderr, never
 * stdout. ($ signs are escaped for Kotlin string templates.)
 */
internal val MCP_GUEST_WRAPPER_SCRIPT: String =
"err=\"\$1\"; shift\n" +
        "mkdir -p \"\$(dirname \"\$err\")\"\n" +
        ": > \"\$err\"\n" +
        "exec 2>\"\$err\"\n" +
        "prog=\"\$1\"; shift\n" +
        "if [ \"\$prog\" = \"uvx\" ] && ! command -v uvx >/dev/null 2>&1; then\n" +
        "  if command -v uv >/dev/null 2>&1; then\n" +
        "    exec uv tool run \"\$@\"\n" +
        "  fi\n" +
        "  if command -v python3 >/dev/null 2>&1; then\n" +
        "    if [ ! -x \"\$HOME/.local/bin/uvx\" ]; then\n" +
        "      echo \"Kodex: installing the uv tool runner (one-time setup)...\"\n" +
        "      python3 -m pip install --user --disable-pip-version-check uv >>\"\$err\" 2>&1 || { echo \"Kodex: could not install the uv tool runner. Check the connection and try again.\"; exit 127; }\n" +
        "    fi\n" +
        "    exec \"\$HOME/.local/bin/uvx\" \"\$@\"\n" +
        "  fi\n" +
        "  echo \"Kodex: 'uvx' is not available inside the Linux runtime.\"\n" +
        "  exit 127\n" +
        "fi\n" +
        "exec \"\$prog\" \"\$@\""

/**
 * The runner probe (POSIX sh): prints HAVE/MISS for the requested
 * program plus python3 and node. A program also counts as found when a
 * previous bootstrap left it executable at $HOME/.local/bin/<program> —
 * the exact fallback the wrapper itself uses for uvx.
 */
internal val MCP_PROBE_SCRIPT: String =
    "for p in \"\$1\" python3 node; do " +
    "if command -v \"\$p\" >/dev/null 2>&1 || [ -x \"\$HOME/.local/bin/\$p\" ]; then echo \"HAVE \$p\"; else echo \"MISS \$p\"; fi; done"
