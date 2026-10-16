package com.vayro.app.security

/**
 * M5: precise authorization prompt model.
 *
 * When [SafetyGateway] cannot auto-allow or auto-deny an operation, it builds
 * an [AuthorizationPrompt] that states exactly what is being authorized: the
 * concrete operation, its target, the assessed risk, what will happen if
 * allowed, and the scopes the user may grant it for. Rendered via [renderText]
 * for UI display and persisted with the resulting policy decision.
 */
data class AuthorizationPrompt(
    val operation: SafetyOperation,
    val risk: PromptRisk,
    val whatHappens: String,
    val scopes: List<PermissionLevel>,
    val title: String = defaultTitle(operation)
) {
    /**
     * Renders the prompt as precise, human-readable text. Always names the
     * exact operation and target — never a vague category.
     */
    fun renderText(): String {
        val sb = StringBuilder()
        sb.appendLine(title)
        sb.appendLine()
        sb.appendLine("Operation: ${operation.describe()}")
        sb.appendLine("Risk: ${risk.label} — ${risk.explanation}")
        sb.appendLine("If allowed: $whatHappens")
        sb.appendLine()
        sb.appendLine("Grant for: ${scopes.joinToString(", ") { it.name.lowercase().replace('_', ' ') }}")
        return sb.toString().trimEnd()
    }

    companion object {
        private fun defaultTitle(operation: SafetyOperation): String = when (operation) {
            is SafetyOperation.FileOp -> "Allow file ${operation.kind.name.lowercase()}?"
            is SafetyOperation.ShellExec -> "Allow shell command?"
            is SafetyOperation.GitOp -> "Allow git ${operation.kind.name.lowercase()}?"
            is SafetyOperation.McpToolCall -> "Allow MCP tool call?"
            is SafetyOperation.NetworkRequest -> "Allow network request?"
            is SafetyOperation.ApkInstall -> "Allow APK install?"
            is SafetyOperation.RemoteExec -> "Allow remote execution?"
        }

        /** Builds a prompt with risk derived from the operation. */
        fun forOperation(
            operation: SafetyOperation,
            detector: DangerousCommandDetector = DangerousCommandDetector()
        ): AuthorizationPrompt {
            val (risk, whatHappens) = when (operation) {
                is SafetyOperation.FileOp -> when (operation.kind) {
                    FileOpKind.READ -> PromptRisk.LOW to
                        "The app will read '${operation.path}'. Nothing is modified."
                    FileOpKind.CREATE, FileOpKind.WRITE -> PromptRisk.MEDIUM to
                        "The app will write to '${operation.path}'. Existing content may be replaced."
                    FileOpKind.DELETE -> PromptRisk.HIGH to
                        "The app will permanently delete '${operation.path}'. This cannot be undone."
                }
                is SafetyOperation.ShellExec -> {
                    val eval = detector.evaluate(operation.command)
                    val r = when (eval.severity) {
                        DangerSeverity.CRITICAL -> PromptRisk.CRITICAL
                        DangerSeverity.HIGH -> PromptRisk.HIGH
                        DangerSeverity.MEDIUM -> PromptRisk.MEDIUM
                        DangerSeverity.SAFE -> PromptRisk.MEDIUM
                    }
                    r to "The app will execute '${operation.command}' with the app's permissions" +
                        (operation.workingDir?.let { " in '$it'" } ?: "") + "."
                }
                is SafetyOperation.GitOp -> when (operation.kind) {
                    GitOpKind.PUSH -> PromptRisk.HIGH to
                        "The app will push commits from '${operation.repoPath}' to '${operation.remoteUrl ?: "the configured remote"}'. Remote history will change."
                    GitOpKind.COMMIT, GitOpKind.BRANCH, GitOpKind.STAGE -> PromptRisk.LOW to
                        "The app will modify only the local repository at '${operation.repoPath}'."
                    GitOpKind.CLONE, GitOpKind.FETCH -> PromptRisk.MEDIUM to
                        "The app will download data from '${operation.remoteUrl ?: "a remote"}' into '${operation.repoPath}'."
                }
                is SafetyOperation.McpToolCall -> PromptRisk.MEDIUM to
                    "The app will invoke tool '${operation.toolName}' on MCP server '${operation.serverId}'."
                is SafetyOperation.NetworkRequest -> PromptRisk.MEDIUM to
                    "The app will send a ${operation.method} request to '${operation.url}'."
                is SafetyOperation.ApkInstall -> PromptRisk.HIGH to
                    "The app will install '${operation.apkPath}' on this device. Installed apps can access device capabilities."
                is SafetyOperation.RemoteExec -> PromptRisk.HIGH to
                    "The app will run '${operation.command}' on remote worker '${operation.target}'. You will be billed for remote compute."
            }
            val scopes = when (risk) {
                PromptRisk.LOW -> listOf(
                    PermissionLevel.ONCE, PermissionLevel.SESSION,
                    PermissionLevel.PROJECT, PermissionLevel.ALWAYS
                )
                PromptRisk.MEDIUM -> listOf(
                    PermissionLevel.ONCE, PermissionLevel.SESSION, PermissionLevel.PROJECT
                )
                PromptRisk.HIGH, PromptRisk.CRITICAL -> listOf(
                    PermissionLevel.ONCE, PermissionLevel.SESSION
                )
            }
            return AuthorizationPrompt(
                operation = operation,
                risk = risk,
                whatHappens = whatHappens,
                scopes = scopes
            )
        }
    }
}

/** Assessed risk of an operation, shown verbatim in prompts. */
enum class PromptRisk(val label: String, val explanation: String) {
    LOW("Low", "routine, reversible or read-only"),
    MEDIUM("Medium", "modifies data or contacts external systems"),
    HIGH("High", "destructive, privileged, or hard to reverse"),
    CRITICAL("Critical", "matches a known-dangerous pattern; allow only with full understanding")
}
