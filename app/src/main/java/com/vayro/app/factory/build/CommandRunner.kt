package com.vayro.app.factory.build

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Honest result of running one OS command. */
data class CommandResult(
    val command: List<String>,
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    val durationMs: Long = 0L,
) {
    val success: Boolean get() = !timedOut && exitCode == 0
}

/** Seam for running OS commands — production uses ProcessBuilder; tests inject fakes. */
fun interface CommandRunner {
    fun run(command: List<String>, workingDir: File, timeout: Duration): CommandResult
}

/** Production [CommandRunner] over [ProcessBuilder] (v3, Milestone 8). */
class ProcessBuilderCommandRunner : CommandRunner {

    override fun run(command: List<String>, workingDir: File, timeout: Duration): CommandResult {
        require(command.isNotEmpty()) { "Command must not be empty." }
        val startedAt = System.currentTimeMillis()
        return try {
            val process = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val readerThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        val buf = CharArray(8192)
                        var n: Int
                        while (reader.read(buf).also { n = it } >= 0) {
                            synchronized(output) { output.append(buf, 0, n) }
                        }
                    }
                }
            }.also { it.isDaemon = true; it.start() }

            val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            readerThread.join(5_000)
            val durationMs = System.currentTimeMillis() - startedAt
            if (!finished) {
                process.destroyForcibly()
                CommandResult(
                    command = command,
                    exitCode = -1,
                    output = synchronized(output) { output.toString() },
                    timedOut = true,
                    durationMs = durationMs,
                )
            } else {
                CommandResult(
                    command = command,
                    exitCode = process.exitValue(),
                    output = synchronized(output) { output.toString() },
                    timedOut = false,
                    durationMs = durationMs,
                )
            }
        } catch (e: Exception) {
            CommandResult(
                command = command,
                exitCode = -1,
                output = "Failed to start command: ${e.message}",
                timedOut = false,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }
    }
}
