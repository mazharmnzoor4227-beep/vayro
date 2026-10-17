package com.vayro.app.security

import android.content.Context
import com.vayro.app.workspace.SafeFileOps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class PermissionCategory {
    FILE_READ,
    FILE_CREATE,
    FILE_WRITE,
    FILE_MODIFY,
    FILE_DELETE,
    SHELL_EXEC,
    INSTALL_PACKAGE,
    NETWORK,
    EXTERNAL_SERVICES,
    GIT_COMMIT,
    GIT_PUSH,
    GIT_BRANCH,
    INSTALL_APK,
    SECRETS,
    ACCESS_ENV
}

enum class PermissionLevel {
    ONCE,
    SESSION,
    PROJECT,
    ALWAYS,
    DENY
}

data class PermissionRequest(
    val category: PermissionCategory,
    val target: String,
    val command: String? = null,
    val sessionId: String? = null,
    val projectId: String? = null
)

data class PermissionDecision(
    val allowed: Boolean,
    val level: PermissionLevel? = null,
    val requiresPrompt: Boolean = false,
    val reason: String = ""
)

data class SensitivePathResult(
    val isSensitive: Boolean,
    val reason: String = ""
)

/**
 * Kodex Granular Permission & Safety Engine.
 * Evaluates requests against active safety policies, sensitive-file boundaries,
 * dangerous command detectors, and emergency kill switches.
 */
class PermissionPolicyEngine(
    private val policyDir: File? = null,
    val auditLogger: SecurityAuditLogger = SecurityAuditLogger(policyDir),
    val commandDetector: DangerousCommandDetector = DangerousCommandDetector()
) {
    constructor(context: Context) : this(File(context.filesDir, "security"))

    private val killSwitchEngaged = AtomicBoolean(false)
    @Volatile private var killSwitchReason: String = ""

    // Session grants: sessionId -> Category -> List of target patterns
    private val sessionGrants = ConcurrentHashMap<String, ConcurrentHashMap<PermissionCategory, MutableSet<String>>>()

    // Single-use grants: sessionId -> Category -> List of target patterns
    private val onceGrants = ConcurrentHashMap<String, ConcurrentHashMap<PermissionCategory, MutableSet<String>>>()

    // Project grants: projectId -> Category -> PermissionLevel
    private val projectPolicies = ConcurrentHashMap<String, ConcurrentHashMap<PermissionCategory, PermissionLevel>>()

    // Global policies: Category -> PermissionLevel
    private val globalPolicies = ConcurrentHashMap<PermissionCategory, PermissionLevel>()

    private val policyFile: File? = policyDir?.let { File(it, "permission_policies.json") }
    private val lock = Any()

    init {
        loadPersistedPolicies()
    }

    fun engageEmergencyKillSwitch(reason: String = "User engaged emergency stop") {
        killSwitchEngaged.set(true)
        killSwitchReason = reason
        auditLogger.log(
            SecurityAuditEntry(
                category = PermissionCategory.SHELL_EXEC,
                action = "KILL_SWITCH_ENGAGED",
                target = "system",
                decision = AuditDecision.BLOCKED_DANGEROUS,
                reason = "Emergency kill switch engaged: $reason"
            )
        )
    }

    fun resetEmergencyKillSwitch() {
        killSwitchEngaged.set(false)
        killSwitchReason = ""
        auditLogger.log(
            SecurityAuditEntry(
                category = PermissionCategory.SHELL_EXEC,
                action = "KILL_SWITCH_RESET",
                target = "system",
                decision = AuditDecision.ALLOWED,
                reason = "Emergency kill switch reset"
            )
        )
    }

    fun isEmergencyKillSwitchEngaged(): Boolean = killSwitchEngaged.get()

    /**
     * Checks if a target path points to secrets, keys, or sensitive credentials.
     */
    fun isSensitivePath(path: String): SensitivePathResult {
        if (path.isBlank()) return SensitivePathResult(false)
        val normalized = path.replace('\\', '/').lowercase(Locale.ROOT)
        val fileName = normalized.substringAfterLast('/')

        return when {
            fileName.startsWith(".env") -> SensitivePathResult(true, "Environment secrets file (.env)")
            fileName.contains("id_rsa") || fileName.contains("id_ed25519") || fileName.contains("id_ecdsa") || fileName.contains("id_dsa") ->
                SensitivePathResult(true, "SSH Private Key")
            fileName.endsWith(".pem") || fileName.endsWith(".key") || fileName.endsWith(".p12") || fileName.endsWith(".pfx") ->
                SensitivePathResult(true, "Cryptographic private key or certificate")
            fileName.endsWith(".keystore") || fileName.endsWith(".jks") ->
                SensitivePathResult(true, "Android / Java Keystore file")
            fileName == "google-services.json" || fileName.contains("service-account") || fileName == "credentials.json" ->
                SensitivePathResult(true, "Cloud credentials and service account secrets")
            fileName == ".git-credentials" || (fileName == ".npmrc" && !normalized.contains("node_modules")) ->
                SensitivePathResult(true, "Package or Git auth credentials")
            else -> SensitivePathResult(false)
        }
    }

    /**
     * Evaluates whether a permission request is allowed, requires user prompt, or is denied.
     */
    fun evaluatePermission(request: PermissionRequest): PermissionDecision {
        // 1. Emergency Kill Switch Check
        if (killSwitchEngaged.get()) {
            val decision = PermissionDecision(
                allowed = false,
                requiresPrompt = false,
                reason = "Emergency kill switch is ACTIVE: $killSwitchReason"
            )
            auditLogger.log(
                SecurityAuditEntry(
                    category = request.category,
                    action = "EVALUATE",
                    target = request.target,
                    decision = AuditDecision.DENIED,
                    reason = decision.reason,
                    sessionId = request.sessionId,
                    projectId = request.projectId
                )
            )
            return decision
        }

        // 2. Dangerous Command Inspection for Shell Execution
        if (request.category == PermissionCategory.SHELL_EXEC && !request.command.isNullOrBlank()) {
            val cmdEval = commandDetector.evaluate(request.command)
            if (cmdEval.severity == DangerSeverity.CRITICAL) {
                val decision = PermissionDecision(
                    allowed = false,
                    requiresPrompt = false,
                    reason = "Blocked critical dangerous command: ${cmdEval.reason} (${cmdEval.matchedPattern})"
                )
                auditLogger.log(
                    SecurityAuditEntry(
                        category = PermissionCategory.SHELL_EXEC,
                        action = "COMMAND_BLOCKED",
                        target = request.command,
                        decision = AuditDecision.BLOCKED_DANGEROUS,
                        reason = decision.reason,
                        sessionId = request.sessionId,
                        projectId = request.projectId
                    )
                )
                return decision
            } else if (cmdEval.severity == DangerSeverity.HIGH) {
                // High severity requires explicit user confirmation
                val decision = PermissionDecision(
                    allowed = false,
                    requiresPrompt = true,
                    reason = "High-risk command detected: ${cmdEval.reason}"
                )
                auditLogger.log(
                    SecurityAuditEntry(
                        category = PermissionCategory.SHELL_EXEC,
                        action = "COMMAND_PROMPT_REQUIRED",
                        target = request.command,
                        decision = AuditDecision.REQUIRES_CONFIRMATION,
                        reason = decision.reason,
                        sessionId = request.sessionId,
                        projectId = request.projectId
                    )
                )
                return decision
            }
        }

        // 3. Sensitive File Protection
        val isFileOp = request.category == PermissionCategory.FILE_READ ||
                request.category == PermissionCategory.FILE_WRITE ||
                request.category == PermissionCategory.FILE_CREATE ||
                request.category == PermissionCategory.FILE_MODIFY ||
                request.category == PermissionCategory.FILE_DELETE

        if (isFileOp && isSensitivePath(request.target).isSensitive) {
            val hasSecretsGrant = checkScopeGrant(PermissionCategory.SECRETS, request.target, request.projectId, request.sessionId)
            if (!hasSecretsGrant) {
                val decision = PermissionDecision(
                    allowed = false,
                    requiresPrompt = true,
                    reason = "Target matches sensitive file pattern (${isSensitivePath(request.target).reason})"
                )
                auditLogger.log(
                    SecurityAuditEntry(
                        category = PermissionCategory.SECRETS,
                        action = "SENSITIVE_FILE_PROMPT",
                        target = request.target,
                        decision = AuditDecision.REQUIRES_CONFIRMATION,
                        reason = decision.reason,
                        sessionId = request.sessionId,
                        projectId = request.projectId
                    )
                )
                return decision
            }
        }

        // 4. Check explicit DENY rules
        if (isExplicitlyDenied(request.category, request.projectId)) {
            val decision = PermissionDecision(
                allowed = false,
                level = PermissionLevel.DENY,
                reason = "Explicit DENY policy configured for ${request.category.name}"
            )
            auditLogger.log(
                SecurityAuditEntry(
                    category = request.category,
                    action = "EXPLICIT_DENY",
                    target = request.target,
                    decision = AuditDecision.DENIED,
                    reason = decision.reason,
                    sessionId = request.sessionId,
                    projectId = request.projectId,
                    level = PermissionLevel.DENY
                )
            )
            return decision
        }

        // 5. Check ONCE grants (single use)
        if (request.sessionId != null) {
            val consumed = consumeOnceGrant(request.sessionId, request.category, request.target)
            if (consumed) {
                val decision = PermissionDecision(allowed = true, level = PermissionLevel.ONCE, reason = "Allowed via single-use grant")
                auditLogger.log(
                    SecurityAuditEntry(
                        category = request.category,
                        action = "EVALUATE_ONCE",
                        target = request.target,
                        decision = AuditDecision.ALLOWED,
                        reason = decision.reason,
                        sessionId = request.sessionId,
                        projectId = request.projectId,
                        level = PermissionLevel.ONCE
                    )
                )
                return decision
            }
        }

        // 6. Check SESSION grants
        if (request.sessionId != null && isSessionGranted(request.sessionId, request.category, request.target)) {
            val decision = PermissionDecision(allowed = true, level = PermissionLevel.SESSION, reason = "Allowed via active session grant")
            auditLogger.log(
                SecurityAuditEntry(
                    category = request.category,
                    action = "EVALUATE_SESSION",
                    target = request.target,
                    decision = AuditDecision.ALLOWED,
                    reason = decision.reason,
                    sessionId = request.sessionId,
                    projectId = request.projectId,
                    level = PermissionLevel.SESSION
                )
            )
            return decision
        }

        // 7. Check PROJECT policies
        if (request.projectId != null) {
            val projectLevel = projectPolicies[request.projectId]?.get(request.category)
            if (projectLevel == PermissionLevel.ALWAYS || projectLevel == PermissionLevel.PROJECT) {
                val decision = PermissionDecision(allowed = true, level = projectLevel, reason = "Allowed via project policy")
                auditLogger.log(
                    SecurityAuditEntry(
                        category = request.category,
                        action = "EVALUATE_PROJECT",
                        target = request.target,
                        decision = AuditDecision.ALLOWED,
                        reason = decision.reason,
                        sessionId = request.sessionId,
                        projectId = request.projectId,
                        level = projectLevel
                    )
                )
                return decision
            }
        }

        // 8. Check GLOBAL policies
        val globalLevel = globalPolicies[request.category]
        if (globalLevel == PermissionLevel.ALWAYS) {
            val decision = PermissionDecision(allowed = true, level = PermissionLevel.ALWAYS, reason = "Allowed via global policy")
            auditLogger.log(
                SecurityAuditEntry(
                    category = request.category,
                    action = "EVALUATE_GLOBAL",
                    target = request.target,
                    decision = AuditDecision.ALLOWED,
                    reason = decision.reason,
                    sessionId = request.sessionId,
                    projectId = request.projectId,
                    level = PermissionLevel.ALWAYS
                )
            )
            return decision
        }

        // 9. Default Baseline Policies
        val baseline = evaluateBaselineDefault(request)
        auditLogger.log(
            SecurityAuditEntry(
                category = request.category,
                action = "EVALUATE_DEFAULT",
                target = request.target,
                decision = if (baseline.allowed) AuditDecision.ALLOWED else if (baseline.requiresPrompt) AuditDecision.REQUIRES_CONFIRMATION else AuditDecision.DENIED,
                reason = baseline.reason,
                sessionId = request.sessionId,
                projectId = request.projectId
            )
        )
        return baseline
    }

    private fun evaluateBaselineDefault(request: PermissionRequest): PermissionDecision {
        return when (request.category) {
            PermissionCategory.FILE_READ -> PermissionDecision(allowed = true, reason = "Safe project file read allowed")
            PermissionCategory.FILE_CREATE -> PermissionDecision(allowed = true, reason = "Standard project file creation allowed")
            PermissionCategory.FILE_WRITE -> PermissionDecision(allowed = true, reason = "Standard project file write allowed")
            PermissionCategory.FILE_MODIFY -> PermissionDecision(allowed = true, reason = "Standard project file modification allowed")
            PermissionCategory.FILE_DELETE -> PermissionDecision(allowed = false, requiresPrompt = true, reason = "File deletion requires confirmation")
            PermissionCategory.SHELL_EXEC -> PermissionDecision(allowed = true, reason = "Standard safe development command allowed")
            PermissionCategory.NETWORK -> PermissionDecision(allowed = true, reason = "Standard network access allowed")
            PermissionCategory.EXTERNAL_SERVICES -> PermissionDecision(allowed = false, requiresPrompt = true, reason = "Connecting to external third-party services requires confirmation")
            PermissionCategory.GIT_COMMIT -> PermissionDecision(allowed = true, reason = "Local Git commit allowed")
            PermissionCategory.GIT_BRANCH -> PermissionDecision(allowed = true, reason = "Branch creation allowed")
            PermissionCategory.GIT_PUSH -> PermissionDecision(allowed = false, requiresPrompt = true, reason = "Remote Git push requires explicit confirmation")
            PermissionCategory.INSTALL_PACKAGE -> PermissionDecision(allowed = true, reason = "Package installation allowed")
            PermissionCategory.INSTALL_APK -> PermissionDecision(allowed = false, requiresPrompt = true, reason = "Device APK installation requires user consent")
            PermissionCategory.SECRETS -> PermissionDecision(allowed = false, requiresPrompt = true, reason = "Access to secrets requires authorization")
            PermissionCategory.ACCESS_ENV -> PermissionDecision(allowed = false, requiresPrompt = true, reason = "Reading environment variables requires user authorization")
        }
    }

    fun getAllGlobalPolicies(): Map<PermissionCategory, PermissionLevel> = globalPolicies.toMap()
    fun getGlobalPolicy(category: PermissionCategory): PermissionLevel? = globalPolicies[category]
    fun getProjectPolicy(projectId: String, category: PermissionCategory): PermissionLevel? = projectPolicies[projectId]?.get(category)

    private fun checkScopeGrant(category: PermissionCategory, target: String, projectId: String?, sessionId: String?): Boolean {
        if (globalPolicies[category] == PermissionLevel.ALWAYS) return true
        if (projectId != null && (projectPolicies[projectId]?.get(category) == PermissionLevel.ALWAYS || projectPolicies[projectId]?.get(category) == PermissionLevel.PROJECT)) return true
        if (sessionId != null && isSessionGranted(sessionId, category, target)) return true
        return false
    }

    private fun isExplicitlyDenied(category: PermissionCategory, projectId: String?): Boolean {
        if (globalPolicies[category] == PermissionLevel.DENY) return true
        if (projectId != null && projectPolicies[projectId]?.get(category) == PermissionLevel.DENY) return true
        return false
    }

    fun grantPermission(
        category: PermissionCategory,
        level: PermissionLevel,
        targetPattern: String = "*",
        projectId: String? = null,
        sessionId: String? = null
    ) {
        when (level) {
            PermissionLevel.ONCE -> {
                if (sessionId != null) {
                    val map = onceGrants.computeIfAbsent(sessionId) { ConcurrentHashMap() }
                    val set = map.computeIfAbsent(category) { ConcurrentHashMap.newKeySet() }
                    set.add(targetPattern)
                }
            }
            PermissionLevel.SESSION -> {
                if (sessionId != null) {
                    val map = sessionGrants.computeIfAbsent(sessionId) { ConcurrentHashMap() }
                    val set = map.computeIfAbsent(category) { ConcurrentHashMap.newKeySet() }
                    set.add(targetPattern)
                }
            }
            PermissionLevel.PROJECT -> {
                if (projectId != null) {
                    val map = projectPolicies.computeIfAbsent(projectId) { ConcurrentHashMap() }
                    map[category] = PermissionLevel.PROJECT
                    persistPolicies()
                }
            }
            PermissionLevel.ALWAYS -> {
                globalPolicies[category] = PermissionLevel.ALWAYS
                persistPolicies()
            }
            PermissionLevel.DENY -> {
                if (projectId != null) {
                    val map = projectPolicies.computeIfAbsent(projectId) { ConcurrentHashMap() }
                    map[category] = PermissionLevel.DENY
                } else {
                    globalPolicies[category] = PermissionLevel.DENY
                }
                persistPolicies()
            }
        }
    }

    fun revokePermission(category: PermissionCategory, projectId: String? = null, sessionId: String? = null) {
        if (sessionId != null) {
            sessionGrants[sessionId]?.remove(category)
            onceGrants[sessionId]?.remove(category)
        }
        if (projectId != null) {
            projectPolicies[projectId]?.remove(category)
        }
        globalPolicies.remove(category)
        persistPolicies()
    }

    fun clearSessionPermissions(sessionId: String) {
        sessionGrants.remove(sessionId)
        onceGrants.remove(sessionId)
    }

    fun resetAllPolicies() {
        sessionGrants.clear()
        onceGrants.clear()
        projectPolicies.clear()
        globalPolicies.clear()
        killSwitchEngaged.set(false)
        policyFile?.let { if (it.exists()) it.delete() }
    }

    private fun consumeOnceGrant(sessionId: String, category: PermissionCategory, target: String): Boolean {
        val patterns = onceGrants[sessionId]?.get(category) ?: return false
        val matched = patterns.find { patternMatches(it, target) }
        if (matched != null) {
            patterns.remove(matched)
            return true
        }
        return false
    }

    private fun isSessionGranted(sessionId: String, category: PermissionCategory, target: String): Boolean {
        val patterns = sessionGrants[sessionId]?.get(category) ?: return false
        return patterns.any { patternMatches(it, target) }
    }

    private fun patternMatches(pattern: String, target: String): Boolean {
        if (pattern == "*" || pattern == target) return true
        if (pattern.endsWith("/*")) {
            val prefix = pattern.removeSuffix("/*")
            return target.startsWith(prefix)
        }
        return false
    }

    private fun loadPersistedPolicies() {
        synchronized(lock) {
            val file = policyFile ?: return
            if (!file.exists()) return
            runCatching {
                val raw = file.readText()
                if (raw.isNotBlank()) {
                    val root = JSONObject(raw)
                    val globals = root.optJSONObject("globals")
                    globals?.keys()?.forEach { key ->
                        val cat = runCatching { PermissionCategory.valueOf(key) }.getOrNull()
                        val lvl = runCatching { PermissionLevel.valueOf(globals.getString(key)) }.getOrNull()
                        if (cat != null && lvl != null) {
                            globalPolicies[cat] = lvl
                        }
                    }
                    val projects = root.optJSONObject("projects")
                    projects?.keys()?.forEach { projId ->
                        val projObj = projects.getJSONObject(projId)
                        val map = projectPolicies.computeIfAbsent(projId) { ConcurrentHashMap() }
                        projObj.keys().forEach { catKey ->
                            val cat = runCatching { PermissionCategory.valueOf(catKey) }.getOrNull()
                            val lvl = runCatching { PermissionLevel.valueOf(projObj.getString(catKey)) }.getOrNull()
                            if (cat != null && lvl != null) {
                                map[cat] = lvl
                            }
                        }
                    }
                }
            }
        }
    }

    private fun persistPolicies() {
        synchronized(lock) {
            val file = policyFile ?: return
            runCatching {
                val root = JSONObject()
                val globals = JSONObject()
                globalPolicies.forEach { (cat, lvl) -> globals.put(cat.name, lvl.name) }
                root.put("globals", globals)

                val projects = JSONObject()
                projectPolicies.forEach { (projId, map) ->
                    val projObj = JSONObject()
                    map.forEach { (cat, lvl) -> projObj.put(cat.name, lvl.name) }
                    projects.put(projId, projObj)
                }
                root.put("projects", projects)

                SafeFileOps.atomicWriteText(file, root.toString())
            }
        }
    }
}
