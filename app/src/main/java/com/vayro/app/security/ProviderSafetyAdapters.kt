package com.vayro.app.security

/**
 * M5: provider safety adapters. Each adapter is the single choke point through
 * which one provider family (file, shell, Git, MCP, network, APK, remote work)
 * routes its side effects. The effect lambda is invoked ONLY when the gateway
 * verdict is [SafetyVerdict.ALLOW]:
 * - DENY  -> [SafetyDeniedException] is thrown, the lambda never runs.
 * - PROMPT -> [SafetyPromptRequiredException] is thrown carrying the precise
 *   [AuthorizationPrompt]; the caller must obtain a grant and retry.
 *
 * Integration tests run every adapter against the shared [SafetyTestMatrix]
 * deny/approve cases: a denied operation must never execute its lambda.
 */
class SafetyPromptRequiredException(
    val operation: SafetyOperation,
    val prompt: AuthorizationPrompt
) : SecurityException("Authorization required: ${operation.describe()}")

abstract class ProviderSafetyAdapter(
    protected val gateway: SafetyGateway
) {
    protected fun <T> guarded(
        operation: SafetyOperation,
        sessionId: String? = null,
        projectId: String? = null,
        effect: () -> T
    ): T {
        val decision = gateway.requireAllowed(operation, sessionId, projectId)
        if (decision.verdict == SafetyVerdict.PROMPT) {
            throw SafetyPromptRequiredException(
                operation,
                decision.prompt ?: AuthorizationPrompt.forOperation(operation)
            )
        }
        return effect()
    }
}

/** File provider: reads, writes, creates and deletes go through the gateway. */
class FileSafetyAdapter(gateway: SafetyGateway) : ProviderSafetyAdapter(gateway) {
    fun <T> read(path: String, sessionId: String? = null, projectId: String? = null, effect: () -> T): T =
        guarded(SafetyOperation.FileOp(FileOpKind.READ, path), sessionId, projectId, effect)

    fun <T> write(path: String, sessionId: String? = null, projectId: String? = null, effect: () -> T): T =
        guarded(SafetyOperation.FileOp(FileOpKind.WRITE, path), sessionId, projectId, effect)

    fun <T> create(path: String, sessionId: String? = null, projectId: String? = null, effect: () -> T): T =
        guarded(SafetyOperation.FileOp(FileOpKind.CREATE, path), sessionId, projectId, effect)

    fun <T> delete(path: String, sessionId: String? = null, projectId: String? = null, effect: () -> T): T =
        guarded(SafetyOperation.FileOp(FileOpKind.DELETE, path), sessionId, projectId, effect)
}

/** Shell provider: command execution goes through the gateway. */
class ShellSafetyAdapter(gateway: SafetyGateway) : ProviderSafetyAdapter(gateway) {
    fun <T> exec(
        command: String,
        workingDir: String? = null,
        sessionId: String? = null,
        projectId: String? = null,
        effect: () -> T
    ): T = guarded(SafetyOperation.ShellExec(command, workingDir), sessionId, projectId, effect)
}

/** Git provider: commit/push/branch/clone/fetch go through the gateway. */
class GitSafetyAdapter(gateway: SafetyGateway) : ProviderSafetyAdapter(gateway) {
    fun <T> git(
        kind: GitOpKind,
        repoPath: String,
        remoteUrl: String? = null,
        sessionId: String? = null,
        projectId: String? = null,
        effect: () -> T
    ): T = guarded(SafetyOperation.GitOp(kind, repoPath, remoteUrl), sessionId, projectId, effect)
}

/** MCP provider: tool calls go through the gateway (and MCP permission manager). */
class McpSafetyAdapter(gateway: SafetyGateway) : ProviderSafetyAdapter(gateway) {
    fun <T> callTool(
        serverId: String,
        toolName: String,
        argsSummary: String = "",
        sessionId: String? = null,
        projectId: String? = null,
        effect: () -> T
    ): T = guarded(
        SafetyOperation.McpToolCall(serverId, toolName, argsSummary),
        sessionId, projectId, effect
    )
}

/** Network provider: outbound requests go through the gateway. */
class NetworkSafetyAdapter(gateway: SafetyGateway) : ProviderSafetyAdapter(gateway) {
    fun <T> request(
        url: String,
        method: String = "GET",
        sessionId: String? = null,
        projectId: String? = null,
        effect: () -> T
    ): T = guarded(SafetyOperation.NetworkRequest(url, method), sessionId, projectId, effect)
}

/** APK provider: device installs go through the gateway. */
class ApkSafetyAdapter(gateway: SafetyGateway) : ProviderSafetyAdapter(gateway) {
    fun <T> install(
        apkPath: String,
        packageName: String? = null,
        sessionId: String? = null,
        projectId: String? = null,
        effect: () -> T
    ): T = guarded(SafetyOperation.ApkInstall(apkPath, packageName), sessionId, projectId, effect)
}

/** Remote-work provider: cloud/remote command execution goes through the gateway. */
class RemoteWorkSafetyAdapter(gateway: SafetyGateway) : ProviderSafetyAdapter(gateway) {
    fun <T> execRemote(
        target: String,
        command: String,
        sessionId: String? = null,
        projectId: String? = null,
        effect: () -> T
    ): T = guarded(SafetyOperation.RemoteExec(target, command), sessionId, projectId, effect)
}
