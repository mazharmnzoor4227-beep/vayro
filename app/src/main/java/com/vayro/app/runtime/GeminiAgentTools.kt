package com.vayro.app.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal fun geminiFunctionDeclarations(): JSONArray {
    fun stringProp(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)
    fun intProp(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)
    fun declaration(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(required)),
        )
    return JSONArray().apply {
        put(
            declaration(
                "run_shell",
                "Run a shell command inside the project's Linux workspace and return its output. " +
                    "Use it to build, run and test code, install packages, and inspect the project.",
                JSONObject()
                    .put("command", stringProp("The shell command to run."))
                    .put("workdir", stringProp("Optional subdirectory inside the project to run the command in.")),
                listOf("command"),
            ),
        )
        put(
            declaration(
                "read_file",
                "Read a file from the project workspace. Returns the requested line range.",
                JSONObject()
                    .put("path", stringProp("Workspace-relative file path."))
                    .put("offset", intProp("First line to read, 0-based. Defaults to 0."))
                    .put("limit", intProp("Maximum lines to read. Defaults to 200, max 500.")),
                listOf("path"),
            ),
        )
        put(
            declaration(
                "write_file",
                "Write content to a file in the project workspace, creating parent directories as needed. " +
                    "Overwrites the file if it already exists.",
                JSONObject()
                    .put("path", stringProp("Workspace-relative file path."))
                    .put("content", stringProp("The full file content to write.")),
                listOf("path", "content"),
            ),
        )
        put(
            declaration(
                "edit_file",
                "Replace the first occurrence of old_text with new_text in a workspace file. " +
                    "Both strings must match exactly, including whitespace.",
                JSONObject()
                    .put("path", stringProp("Workspace-relative file path."))
                    .put("old_text", stringProp("Exact text to find."))
                    .put("new_text", stringProp("Replacement text.")),
                listOf("path", "old_text", "new_text"),
            ),
        )
        put(
            declaration(
                "list_dir",
                "List files and directories inside a project workspace directory.",
                JSONObject()
                    .put("path", stringProp("Workspace-relative directory path. Defaults to the project root.")),
                emptyList(),
            ),
        )
    }
}

internal fun redactGeminiToolText(value: String): String = value
    .replace(Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\"]+)"), "$1$2....")
    .replace(Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\"]+"), "$1....")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(300)

interface GeminiToolExecutor {
    suspend fun execute(name: String, args: JSONObject): String
}

class WorkspaceToolExecutor(
    private val installer: RuntimeInstaller,
    private val workspace: File,
    private val checkpoints: WorkspaceCheckpoints,
    private val projectSlug: String,
    private val cacheDir: File,
) : GeminiToolExecutor {

    @Volatile private var activeProcess: Process? = null

    fun cancel() {
        runCatching { activeProcess?.destroyForcibly() }
        activeProcess = null
    }

    override suspend fun execute(name: String, args: JSONObject): String =
        withContext(Dispatchers.IO) {
            runCatching {
                when (name) {
                    "run_shell" -> runShell(
                        args.getString("command"),
                        args.optString("workdir", ""),
                    )
                    "read_file" -> readFile(
                        args.getString("path"),
                        args.optInt("offset", 0),
                        args.optInt("limit", 200),
                    )
                    "write_file" -> writeFile(args.getString("path"), args.getString("content"))
                    "edit_file" -> editFile(
                        args.getString("path"),
                        args.getString("old_text"),
                        args.getString("new_text"),
                    )
                    "list_dir" -> listDir(args.optString("path", ""))
                    else -> "Error: unknown tool '$name'."
                }
            }.getOrElse { e -> "Error: ${e.message.orEmpty().ifBlank { "tool failed" }.take(500)}" }
        }

    private fun runShell(command: String, workdir: String): String {
        if (command.isBlank()) return "Error: command is empty."
        if (!installer.isInstalled()) return "Error: the Linux runtime is not installed."
        val cleanWorkdir = workdir.trim().trim('/')
        if (cleanWorkdir.contains("..")) return "Error: workdir must stay inside the project."
        val fullCommand = if (cleanWorkdir.isEmpty()) {
            command
        } else {
            "cd '${cleanWorkdir.replace("'", "'\\''")}' && $command"
        }
        val installed = installer.installedRuntime()
        val outputFile = File(cacheDir, "gemini-shell-${System.nanoTime()}.log")
        return try {
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                emptyMap(),
                listOf("sh", "-c", fullCommand),
                guestWorkspacePath = "/workspace/$projectSlug",
                emulateHardLinks = false,
                outputFile = outputFile,
            )
            activeProcess = process
            val finished = process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            activeProcess = null
            if (!finished) {
                runCatching { process.destroyForcibly() }
                return "Error: command timed out after $SHELL_TIMEOUT_SECONDS seconds."
            }
            val exit = process.exitValue()
            val out = outputFile.readTailText(MAX_TOOL_OUTPUT_BYTES).trim()
            redactGeminiToolText("exit=$exit\n$out".take(MAX_TOOL_OUTPUT_BYTES))
        } finally {
            runCatching { outputFile.delete() }
        }
    }

    private fun readFile(path: String, offset: Int, limit: Int): String {
        val file = runCatching { checkpoints.safeWorkspaceFile(workspace, path) }.getOrNull()
            ?: return "Error: unsafe path '$path'."
        if (!file.isFile) return "Error: file not found '$path'."
        val lines = file.readLines()
        val start = offset.coerceAtLeast(0).coerceAtMost(lines.size)
        val count = limit.coerceIn(1, 500)
        val end = (start + count).coerceAtMost(lines.size)
        if (start >= end) return ""
        return lines.subList(start, end).joinToString("\n").take(MAX_TOOL_OUTPUT_BYTES)
    }

    private fun writeFile(path: String, content: String): String {
        val file = runCatching { checkpoints.safeWorkspaceFile(workspace, path) }.getOrNull()
            ?: return "Error: unsafe path '$path'."
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Wrote ${content.length} characters to '$path'."
    }

    private fun editFile(path: String, oldText: String, newText: String): String {
        if (oldText.isEmpty()) return "Error: old_text must not be empty."
        val file = runCatching { checkpoints.safeWorkspaceFile(workspace, path) }.getOrNull()
            ?: return "Error: unsafe path '$path'."
        if (!file.isFile) return "Error: file not found '$path'."
        val text = file.readText()
        if (!text.contains(oldText)) return "Error: old_text not found in '$path'."
        file.writeText(text.replaceFirst(oldText, newText))
        return "Edited '$path'."
    }

    private fun listDir(path: String): String {
        val dir = if (path.isBlank()) {
            workspace
        } else {
            runCatching { checkpoints.safeWorkspaceFile(workspace, path) }.getOrNull()
                ?: return "Error: unsafe path '$path'."
        }
        if (!dir.isDirectory) return "Error: not a directory '$path'."
        val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
        if (entries.isEmpty()) return "(empty)"
        return entries.joinToString("\n") { (if (it.isDirectory) "dir  " else "file ") + it.name }
            .take(MAX_TOOL_OUTPUT_BYTES)
    }

    private companion object {
        const val SHELL_TIMEOUT_SECONDS = 120L
        const val MAX_TOOL_OUTPUT_BYTES = 32 * 1024
    }
}
