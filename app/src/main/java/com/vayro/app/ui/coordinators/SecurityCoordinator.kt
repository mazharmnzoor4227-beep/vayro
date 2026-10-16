package com.vayro.app.ui.coordinators

import com.vayro.app.security.AuditDecision
import com.vayro.app.security.PermissionCategory
import com.vayro.app.security.PermissionLevel
import com.vayro.app.security.PermissionPolicyEngine

/**
 * Security domain coordinator (Milestone 1).
 *
 * Explicit owner of the permission-policy state machine: the emergency kill
 * switch, per-category permission policies and the security audit log. All
 * enforcement lives in the real [PermissionPolicyEngine]; this coordinator
 * only mirrors policy state into the UI and routes user actions to the engine.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class SecurityCoordinator(
    private val host: CoordinatorHost,
    private val permissionPolicyEngine: PermissionPolicyEngine,
    private val stopTask: () -> Unit,
) {
    fun engageEmergencyKillSwitch(reason: String = "User requested emergency stop") {
        permissionPolicyEngine.engageEmergencyKillSwitch(reason)
        host.updateState { it.copy(emergencyKillSwitchActive = true) }
        stopTask()
    }

    fun resetEmergencyKillSwitch() {
        permissionPolicyEngine.resetEmergencyKillSwitch()
        host.updateState { it.copy(emergencyKillSwitchActive = false) }
    }

    fun isEmergencyKillSwitchEngaged(): Boolean = permissionPolicyEngine.isEmergencyKillSwitchEngaged()

    fun refreshPermissionPolicies() {
        val globals = permissionPolicyEngine.getAllGlobalPolicies()
        val shell = when (globals[PermissionCategory.SHELL_EXEC]) {
            PermissionLevel.ALWAYS -> true
            PermissionLevel.DENY -> false
            else -> true
        }
        val net = when (globals[PermissionCategory.NETWORK]) {
            PermissionLevel.ALWAYS -> true
            PermissionLevel.DENY -> false
            else -> true
        }
        val fileDel = when (globals[PermissionCategory.FILE_DELETE]) {
            PermissionLevel.ALWAYS -> true
            PermissionLevel.DENY -> false
            else -> false
        }
        val gitPush = when (globals[PermissionCategory.GIT_PUSH]) {
            PermissionLevel.ALWAYS -> true
            PermissionLevel.DENY -> false
            else -> false
        }
        val apkInstall = when (globals[PermissionCategory.INSTALL_APK]) {
            PermissionLevel.ALWAYS -> true
            PermissionLevel.DENY -> false
            else -> false
        }
        val extServices = when (globals[PermissionCategory.EXTERNAL_SERVICES]) {
            PermissionLevel.ALWAYS -> true
            PermissionLevel.DENY -> false
            else -> false
        }
        val accessEnv = when (globals[PermissionCategory.ACCESS_ENV]) {
            PermissionLevel.ALWAYS -> true
            PermissionLevel.DENY -> false
            else -> false
        }
        val logs = permissionPolicyEngine.auditLogger.getEntries(200)
        host.updateState {
            it.copy(
                allowShellExec = shell,
                allowNetworkAccess = net,
                allowFileDeletion = fileDel,
                allowGitPush = gitPush,
                allowApkInstall = apkInstall,
                allowExternalServices = extServices,
                allowAccessEnv = accessEnv,
                securityAuditLogs = logs,
                emergencyKillSwitchActive = permissionPolicyEngine.isEmergencyKillSwitchEngaged(),
            )
        }
    }

    fun updatePermissionPolicy(category: PermissionCategory, allowed: Boolean) {
        if (allowed) {
            permissionPolicyEngine.grantPermission(category, PermissionLevel.ALWAYS)
        } else {
            permissionPolicyEngine.grantPermission(category, PermissionLevel.DENY)
        }
        refreshPermissionPolicies()
    }

    fun loadSecurityAuditLogs(limit: Int = 200) {
        val logs = permissionPolicyEngine.auditLogger.getEntries(limit)
        host.updateState { it.copy(securityAuditLogs = logs) }
    }

    fun searchSecurityAuditLogs(
        query: String = "",
        category: PermissionCategory? = null,
        decision: AuditDecision? = null,
        limit: Int = 200
    ) {
        val logs = permissionPolicyEngine.auditLogger.filter(
            query = query,
            category = category,
            decision = decision,
            limit = limit
        )
        host.updateState { it.copy(securityAuditLogs = logs) }
    }

    fun clearSecurityAuditLogs() {
        permissionPolicyEngine.auditLogger.clear()
        host.updateState { it.copy(securityAuditLogs = emptyList()) }
    }
}
