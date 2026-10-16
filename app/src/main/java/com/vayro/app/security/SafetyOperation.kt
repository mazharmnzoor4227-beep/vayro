package com.vayro.app.security

/**
 * M5: Safety gateway — every side-effecting operation in the app is modelled as a
 * [SafetyOperation] and must pass through [SafetyGateway] before execution.
 *
 * Operation kinds cover the full M5 surface: file, shell, Git, MCP, network,
 * APK install and remote-work execution. Providers never execute directly;
 * they call a [ProviderSafetyAdapter] which authorizes first.
 */
sealed class SafetyOperation {

    /** Human-readable one-line description used in prompts and audit logs. */
    abstract fun describe(): String

    /** File-system side effects. [path] may be absolute or workspace-relative. */
    data class FileOp(
        val kind: FileOpKind,
        val path: String
    ) : SafetyOperation() {
        override fun describe(): String = "File ${kind.name.lowercase()}: $path"
    }

    /** Shell command execution. */
    data class ShellExec(
        val command: String,
        val workingDir: String? = null
    ) : SafetyOperation() {
        override fun describe(): String =
            "Shell exec: $command" + (workingDir?.let { " (in $it)" } ?: "")
    }

    /** Git side effects. */
    data class GitOp(
        val kind: GitOpKind,
        val repoPath: String,
        val remoteUrl: String? = null
    ) : SafetyOperation() {
        override fun describe(): String =
            "Git ${kind.name.lowercase()}: $repoPath" + (remoteUrl?.let { " -> $it" } ?: "")
    }

    /** MCP tool invocation. */
    data class McpToolCall(
        val serverId: String,
        val toolName: String,
        val argsSummary: String = ""
    ) : SafetyOperation() {
        override fun describe(): String =
            "MCP tool call: $serverId::$toolName" + (argsSummary.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
    }

    /** Outbound network request. */
    data class NetworkRequest(
        val url: String,
        val method: String = "GET"
    ) : SafetyOperation() {
        override fun describe(): String = "Network $method: $url"
    }

    /** APK installation on the device. */
    data class ApkInstall(
        val apkPath: String,
        val packageName: String? = null
    ) : SafetyOperation() {
        override fun describe(): String =
            "APK install: $apkPath" + (packageName?.let { " ($it)" } ?: "")
    }

    /** Remote/cloud worker execution. */
    data class RemoteExec(
        val target: String,
        val command: String
    ) : SafetyOperation() {
        override fun describe(): String = "Remote exec on $target: $command"
    }
}

enum class FileOpKind { READ, CREATE, WRITE, DELETE }

enum class GitOpKind { STAGE, COMMIT, PUSH, BRANCH, CLONE, FETCH }
