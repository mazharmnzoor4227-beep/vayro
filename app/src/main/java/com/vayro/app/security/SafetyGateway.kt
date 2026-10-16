package com.vayro.app.security

import com.vayro.app.mcp.McpPermissionDecision
import com.vayro.app.mcp.McpPermissionManager
import com.vayro.app.mcp.McpPermissionPolicy
import com.vayro.app.mcp.McpServerInfo
import java.io.File

/**
 * M5: the single safety gateway. Every side-effecting operation — file, shell,
 * Git, MCP, network, APK install, remote work — is authorized here before any
 * provider executes it.
 *
 * Decision order for every operation:
 * 1. Emergency kill switch (deny everything).
 * 2. Path audit for file-bearing operations (traversal / symlink / absolute
 *    escape is a hard deny — never promptable).
 * 3. MCP tool calls additionally consult [McpPermissionManager]; DENY wins.
 * 4. [PermissionPolicyEngine] evaluation (dangerous commands, sensitive
 *    files, explicit deny, grants, baselines).
 *
 * Providers integrate via [ProviderSafetyAdapter]: they call
 * [authorize] / [requireAllowed] and only run their effect lambda when the
 * verdict is [SafetyVerdict.ALLOW]. A [SafetyVerdict.DENY] always throws
 * [SafetyDeniedException] from [requireAllowed] — there is no bypass path.
 */
class SafetyGateway(
    private val policyEngine: PermissionPolicyEngine,
    private val pathAuditor: PathSecurityAuditor = PathSecurityAuditor(),
    private val workspaceRoot: File? = null,
    private val workspaceRootResolver: ((projectId: String?) -> File?)? = null,
    private val mcpPermissionManager: McpPermissionManager? = null,
    private val mcpServerResolver: ((serverId: String) -> McpServerInfo?)? = null,
    private val auditLogger: SecurityAuditLogger = policyEngine.auditLogger
) {

    data class SafetyDecision(
        val verdict: SafetyVerdict,
        val reason: String,
        val prompt: AuthorizationPrompt? = null,
        val category: PermissionCategory,
        val level: PermissionLevel? = null
    )

    fun authorize(
        operation: SafetyOperation,
        sessionId: String? = null,
        projectId: String? = null
    ): SafetyDecision {
        // 1. Kill switch denies everything, including prompts.
        if (policyEngine.isEmergencyKillSwitchEngaged()) {
            val reason = "Emergency kill switch is ACTIVE"
            audit(operation, categoryOf(operation), AuditDecision.DENIED, reason, sessionId, projectId)
            return SafetyDecision(SafetyVerdict.DENY, reason, null, categoryOf(operation))
        }

        // 2. Path audit for file-bearing operations: hard deny on escape.
        val effectiveRoot = workspaceRootResolver?.invoke(projectId) ?: workspaceRoot
        val pathToAudit: String? = when (operation) {
            is SafetyOperation.FileOp -> operation.path
            is SafetyOperation.ApkInstall -> operation.apkPath
            is SafetyOperation.GitOp -> operation.repoPath
            is SafetyOperation.ShellExec -> operation.workingDir
            else -> null
        }
        if (pathToAudit != null) {
            when (val pathResult = pathAuditor.audit(pathToAudit, effectiveRoot)) {
                is PathSecurityAuditor.PathAuditResult.Rejected -> {
                    val reason = "Path rejected (${pathResult.kind}): ${pathResult.reason}"
                    audit(operation, categoryOf(operation), AuditDecision.BLOCKED_DANGEROUS, reason, sessionId, projectId)
                    return SafetyDecision(SafetyVerdict.DENY, reason, null, categoryOf(operation))
                }
                is PathSecurityAuditor.PathAuditResult.Safe -> Unit
            }
        }

        // 2b. Dangerous command audit for command-carrying operations that are
        // not SHELL_EXEC (the policy engine covers SHELL_EXEC itself). A
        // critical payload must not bypass detection by switching provider,
        // e.g. rm -rf / via remote execution.
        val commandToAudit: String? = when (operation) {
            is SafetyOperation.RemoteExec -> operation.command
            else -> null
        }
        if (commandToAudit != null) {
            val cmdEval = policyEngine.commandDetector.evaluate(commandToAudit)
            if (cmdEval.severity == DangerSeverity.CRITICAL) {
                val reason =
                    "Blocked critical dangerous command: ${cmdEval.reason} (${cmdEval.matchedPattern})"
                audit(
                    operation, categoryOf(operation), AuditDecision.BLOCKED_DANGEROUS,
                    reason, sessionId, projectId
                )
                return SafetyDecision(SafetyVerdict.DENY, reason, null, categoryOf(operation))
            }
        }

        // 3. MCP tool calls: MCP permission manager decides; DENY wins outright.
        if (operation is SafetyOperation.McpToolCall && mcpPermissionManager != null) {
            val server = mcpServerResolver?.invoke(operation.serverId)
            if (server != null) {
                val mcpDecision: McpPermissionDecision =
                    mcpPermissionManager.evaluate(server, operation.toolName, projectId)
                if (!mcpDecision.allowed && !mcpDecision.requiresPrompt) {
                    val reason = "MCP denied: ${mcpDecision.reason}"
                    audit(operation, PermissionCategory.EXTERNAL_SERVICES, AuditDecision.DENIED, reason, sessionId, projectId)
                    return SafetyDecision(SafetyVerdict.DENY, reason, null, PermissionCategory.EXTERNAL_SERVICES)
                }
                if (mcpDecision.requiresPrompt) {
                    val prompt = AuthorizationPrompt.forOperation(operation)
                    audit(operation, PermissionCategory.EXTERNAL_SERVICES, AuditDecision.REQUIRES_CONFIRMATION, mcpDecision.reason, sessionId, projectId)
                    return SafetyDecision(SafetyVerdict.PROMPT, mcpDecision.reason, prompt, PermissionCategory.EXTERNAL_SERVICES)
                }
            }
        }

        // 4. Policy engine evaluation.
        val category = categoryOf(operation)
        val request = toPermissionRequest(operation, sessionId, projectId)
        val policyDecision = policyEngine.evaluatePermission(request)
        val auditDecision = when {
            policyDecision.allowed -> AuditDecision.ALLOWED
            policyDecision.requiresPrompt -> AuditDecision.REQUIRES_CONFIRMATION
            else -> AuditDecision.DENIED
        }
        audit(operation, category, auditDecision, policyDecision.reason, sessionId, projectId, policyDecision.level)

        return when {
            policyDecision.allowed ->
                SafetyDecision(SafetyVerdict.ALLOW, policyDecision.reason, null, category, policyDecision.level)
            policyDecision.requiresPrompt -> {
                val prompt = AuthorizationPrompt.forOperation(operation, policyEngine.commandDetector)
                SafetyDecision(SafetyVerdict.PROMPT, policyDecision.reason, prompt, category, policyDecision.level)
            }
            else ->
                SafetyDecision(SafetyVerdict.DENY, policyDecision.reason, null, category, policyDecision.level)
        }
    }

    /**
     * Authorizes and returns the decision, throwing [SafetyDeniedException] on
     * DENY. Callers must treat PROMPT as "do not execute yet".
     */
    fun requireAllowed(
        operation: SafetyOperation,
        sessionId: String? = null,
        projectId: String? = null
    ): SafetyDecision {
        val decision = authorize(operation, sessionId, projectId)
        if (decision.verdict == SafetyVerdict.DENY) {
            throw SafetyDeniedException(operation, decision.reason)
        }
        return decision
    }

    /**
     * Records a user grant that resulted from an [AuthorizationPrompt]. The
     * grant persists according to its intended scope: PROJECT and ALWAYS are
     * persisted to disk by [PermissionPolicyEngine]; SESSION and ONCE live for
     * the session only — by design, not by accident.
     */
    fun recordGrant(
        operation: SafetyOperation,
        level: PermissionLevel,
        sessionId: String? = null,
        projectId: String? = null,
        targetPattern: String = "*"
    ) {
        policyEngine.grantPermission(
            category = categoryOf(operation),
            level = level,
            targetPattern = targetPattern,
            projectId = projectId,
            sessionId = sessionId
        )
        // MCP tool grants persist through the MCP permission manager as well.
        if (operation is SafetyOperation.McpToolCall && mcpPermissionManager != null) {
            val mcpPolicy = when (level) {
                PermissionLevel.ALWAYS -> McpPermissionPolicy.ALLOW_ALWAYS
                PermissionLevel.DENY -> McpPermissionPolicy.DENY
                else -> McpPermissionPolicy.REQUIRE_PROMPT
            }
            if (projectId != null) {
                mcpPermissionManager.setProjectToolPolicy(projectId, operation.serverId, operation.toolName, mcpPolicy)
            } else {
                mcpPermissionManager.setGlobalToolPolicy(operation.serverId, operation.toolName, mcpPolicy)
            }
        }
        auditLogger.log(
            SecurityAuditEntry(
                category = categoryOf(operation),
                action = "GRANT_RECORDED",
                target = operation.describe(),
                decision = AuditDecision.ALLOWED,
                reason = "User granted ${level.name} scope",
                sessionId = sessionId,
                projectId = projectId,
                level = level
            )
        )
    }

    private fun categoryOf(operation: SafetyOperation): PermissionCategory = when (operation) {
        is SafetyOperation.FileOp -> when (operation.kind) {
            FileOpKind.READ -> PermissionCategory.FILE_READ
            FileOpKind.CREATE -> PermissionCategory.FILE_CREATE
            FileOpKind.WRITE -> PermissionCategory.FILE_WRITE
            FileOpKind.DELETE -> PermissionCategory.FILE_DELETE
        }
        is SafetyOperation.ShellExec -> PermissionCategory.SHELL_EXEC
        is SafetyOperation.GitOp -> when (operation.kind) {
            GitOpKind.STAGE -> PermissionCategory.GIT_COMMIT
            GitOpKind.COMMIT -> PermissionCategory.GIT_COMMIT
            GitOpKind.PUSH -> PermissionCategory.GIT_PUSH
            GitOpKind.BRANCH -> PermissionCategory.GIT_BRANCH
            GitOpKind.CLONE, GitOpKind.FETCH -> PermissionCategory.NETWORK
        }
        is SafetyOperation.McpToolCall -> PermissionCategory.EXTERNAL_SERVICES
        is SafetyOperation.NetworkRequest -> PermissionCategory.NETWORK
        is SafetyOperation.ApkInstall -> PermissionCategory.INSTALL_APK
        is SafetyOperation.RemoteExec -> PermissionCategory.EXTERNAL_SERVICES
    }

    private fun toPermissionRequest(
        operation: SafetyOperation,
        sessionId: String?,
        projectId: String?
    ): PermissionRequest {
        val category = categoryOf(operation)
        val target = when (operation) {
            is SafetyOperation.FileOp -> operation.path
            is SafetyOperation.ShellExec -> operation.workingDir ?: operation.command
            is SafetyOperation.GitOp -> operation.repoPath
            is SafetyOperation.McpToolCall -> "${operation.serverId}:${operation.toolName}"
            is SafetyOperation.NetworkRequest -> operation.url
            is SafetyOperation.ApkInstall -> operation.apkPath
            is SafetyOperation.RemoteExec -> "${operation.target}:${operation.command}"
        }
        val command = when (operation) {
            is SafetyOperation.ShellExec -> operation.command
            is SafetyOperation.RemoteExec -> operation.command
            else -> null
        }
        return PermissionRequest(
            category = category,
            target = target,
            command = command,
            sessionId = sessionId,
            projectId = projectId
        )
    }

    private fun audit(
        operation: SafetyOperation,
        category: PermissionCategory,
        decision: AuditDecision,
        reason: String,
        sessionId: String?,
        projectId: String?,
        level: PermissionLevel? = null
    ) {
        auditLogger.log(
            SecurityAuditEntry(
                category = category,
                action = "GATEWAY_${decision.name}",
                target = operation.describe(),
                decision = decision,
                reason = reason,
                sessionId = sessionId,
                projectId = projectId,
                level = level
            )
        )
    }
}

enum class SafetyVerdict { ALLOW, DENY, PROMPT }
