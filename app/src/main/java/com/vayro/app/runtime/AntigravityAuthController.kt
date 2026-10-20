package com.vayro.app.runtime

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal fun sanitizeAntigravityEmail(email: String): String =
    email.lowercase().replace(Regex("[^a-z0-9._-]"), "_")

internal fun antigravityAccountFolderName(email: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(email.lowercase().toByteArray(Charsets.UTF_8))
    val suffix = digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.take(16)
    return "${sanitizeAntigravityEmail(email)}_$suffix"
}

/** Drives only agy's official interactive OAuth flow; it never reads the resulting credentials. */
class AntigravityAuthController(
    private val context: Context,
    initiallySignedIn: Boolean,
    initialAccountEmail: String,
    private val onSignedInChanged: (Boolean, String?) -> Unit,
) {
    private val installer = RuntimeInstaller(context)
    private val mutableState = MutableStateFlow(
        AntigravityAuthState(
            status = if (initiallySignedIn) AntigravityAuthStatus.SIGNED_IN else AntigravityAuthStatus.SIGNED_OUT,
            message = initialAccountEmail.takeIf(String::isNotBlank)?.let { "Connected as $it" },
            accountEmail = initialAccountEmail.takeIf(String::isNotBlank),
        ),
    )
    val state: StateFlow<AntigravityAuthState> = mutableState.asStateFlow()
    private val authOutput = File(context.cacheDir, "antigravity-auth-output.log")
    private val logoutOutput = File(context.cacheDir, "antigravity-logout-output.log")
    @Volatile private var process: Process? = null
    @Volatile private var codeSubmitted = false

    private val prefs = context.getSharedPreferences("antigravity_auth_store", Context.MODE_PRIVATE)

    private fun accountsDir() = File(
        installer.antigravityCredentialHostDir(),
        "accounts",
    ).apply { mkdirs() }

    private fun accountCredentialFile(email: String) = File(
        accountFolder(email),
        "antigravity-oauth-token",
    )

    private fun accountFolder(email: String): File {
        val dir = accountsDir()
        val migrated = File(dir, antigravityAccountFolderName(email))
        val legacy = File(dir, sanitizeAntigravityEmail(email))
        if (legacy.isDirectory && !migrated.exists()) {
            runCatching { legacy.renameTo(migrated) }
        }
        return if (migrated.exists() || !legacy.exists()) migrated else legacy
    }

    fun getSavedAccounts(): List<AntigravityAccount> {
        val raw = prefs.getString("accounts_json", "[]") ?: "[]"
        return runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                AntigravityAccount(
                    email = obj.getString("email"),
                    isActive = obj.optBoolean("isActive", false),
                    isQuotaExhausted = obj.optBoolean("isQuotaExhausted", false),
                    quotaExhaustedAt = obj.optLong("quotaExhaustedAt", 0L),
                    lastUsedAt = obj.optLong("lastUsedAt", 0L),
                    isAuthExpired = obj.optBoolean("isAuthExpired", false),
                    authExpiredAt = obj.optLong("authExpiredAt", 0L),
                    isIneligible = obj.optBoolean("isIneligible", false),
                    ineligibleAt = obj.optLong("ineligibleAt", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAccounts(accounts: List<AntigravityAccount>) {
        val array = org.json.JSONArray()
        accounts.forEach { acc ->
            val obj = org.json.JSONObject()
                .put("email", acc.email)
                .put("isActive", acc.isActive)
                .put("isQuotaExhausted", acc.isQuotaExhausted)
                .put("quotaExhaustedAt", acc.quotaExhaustedAt)
                .put("lastUsedAt", acc.lastUsedAt)
                .put("isAuthExpired", acc.isAuthExpired)
                .put("authExpiredAt", acc.authExpiredAt)
                .put("isIneligible", acc.isIneligible)
                .put("ineligibleAt", acc.ineligibleAt)
            array.put(obj)
        }
        prefs.edit().putString("accounts_json", array.toString()).apply()
        mutableState.value = mutableState.value.copy(accounts = accounts)
    }

    fun isAutoRoundRobin(): Boolean = prefs.getBoolean("auto_round_robin", true)

    fun setAutoRoundRobin(enabled: Boolean) {
        prefs.edit().putBoolean("auto_round_robin", enabled).apply()
        mutableState.value = mutableState.value.copy(autoRoundRobin = enabled)
    }

    fun recordSignedInAccount(email: String) {
        val cred = officialCredentialFile()
        if (cred.isFile) {
            val target = accountCredentialFile(email).apply { parentFile?.mkdirs() }
            runCatching { cred.copyTo(target, overwrite = true) }
        }
        val currentAccounts = getSavedAccounts().toMutableList()
        val index = currentAccounts.indexOfFirst { it.email.equals(email, ignoreCase = true) }
        val updated = AntigravityAccount(
            email = email,
            isActive = true,
            isQuotaExhausted = false,
            lastUsedAt = System.currentTimeMillis(),
        )
        val newAccounts = currentAccounts.map { it.copy(isActive = false) }.toMutableList()
        if (index >= 0) {
            newAccounts[index] = updated
        } else {
            newAccounts.add(updated)
        }
        saveAccounts(newAccounts)
    }

    companion object {
        const val HOURLY_LIMIT_COOLDOWN_MS = 60 * 60 * 1000L // 60-minute limit cooldown window
    }

    fun isCredentialValid(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return runCatching {
            val text = file.readText().trim()
            text.isNotBlank() && (text.startsWith("{") || text.length >= 10)
        }.getOrDefault(false)
    }

    fun activateAccount(email: String): Boolean {
        val cred = accountCredentialFile(email)
        if (!isCredentialValid(cred)) return false
        val active = officialCredentialFile().apply { parentFile?.mkdirs() }
        try {
            cred.copyTo(active, overwrite = true)
            if (!active.isFile || active.length() <= 0L) return false
        } catch (_: Throwable) {
            return false
        }
        val currentAccounts = getSavedAccounts()
        val updated = currentAccounts.map {
            it.copy(
                isActive = it.email.equals(email, ignoreCase = true),
                lastUsedAt = if (it.email.equals(email, ignoreCase = true)) System.currentTimeMillis() else it.lastUsedAt,
            )
        }
        saveAccounts(updated)
        onSignedInChanged(true, email)
        mutableState.value = mutableState.value.copy(
            status = AntigravityAuthStatus.SIGNED_IN,
            message = "Connected as $email",
            accountEmail = email,
            accounts = updated,
        )
        return true
    }

    fun switchToNextAccount(): String? {
        val accounts = getSavedAccounts()
        if (accounts.size <= 1) return null
        val activeIndex = accounts.indexOfFirst { it.isActive }
        val now = System.currentTimeMillis()

        val candidatesInOrder = (1 until accounts.size).map { offset ->
            val index = (if (activeIndex >= 0) activeIndex + offset else offset) % accounts.size
            accounts[index]
        }

        // 1. Prioritize account not marked exhausted, auth-expired or
        // ineligible with valid tested credentials
        for (candidate in candidatesInOrder) {
            val credFile = accountCredentialFile(candidate.email)
            if (!candidate.isQuotaExhausted && !candidate.isAuthExpired && !candidate.isIneligible && isCredentialValid(credFile)) {
                if (activateAccount(candidate.email)) return candidate.email
            }
        }

        // 2. Prioritize account whose limit was hit >= 60 minutes ago (and not auth expired or ineligible)
        for (candidate in candidatesInOrder) {
            val credFile = accountCredentialFile(candidate.email)
            if (!candidate.isAuthExpired && !candidate.isIneligible && candidate.isQuotaExhausted && candidate.quotaExhaustedAt > 0 &&
                (now - candidate.quotaExhaustedAt >= HOURLY_LIMIT_COOLDOWN_MS) &&
                isCredentialValid(credFile)
            ) {
                if (activateAccount(candidate.email)) return candidate.email
            }
        }

        // 3. If all non-expired accounts hit quota, try candidate with oldest exhaustion time (not auth expired or ineligible)
        val candidatesByCooldown = candidatesInOrder
            .filter { !it.isAuthExpired && !it.isIneligible && isCredentialValid(accountCredentialFile(it.email)) }
            .sortedBy { it.quotaExhaustedAt }

        for (candidate in candidatesByCooldown) {
            if (activateAccount(candidate.email)) return candidate.email
        }

        return null
    }

    fun markCurrentAccountQuotaExhausted() {
        val current = mutableState.value.accountEmail ?: return
        val accounts = getSavedAccounts()
        val updated = accounts.map {
            if (it.email.equals(current, ignoreCase = true)) {
                it.copy(isQuotaExhausted = true, quotaExhaustedAt = System.currentTimeMillis())
            } else it
        }
        saveAccounts(updated)
    }

    fun markCurrentAccountAuthExpired() {
        val current = mutableState.value.accountEmail ?: return
        val accounts = getSavedAccounts()
        val updated = accounts.map {
            if (it.email.equals(current, ignoreCase = true)) {
                it.copy(isAuthExpired = true, authExpiredAt = System.currentTimeMillis())
            } else it
        }
        saveAccounts(updated)
    }

    /** v3.2.50: Google refused the active account itself (eligibility). */
    fun markCurrentAccountIneligible() {
        val current = mutableState.value.accountEmail ?: return
        val accounts = getSavedAccounts()
        val updated = accounts.map {
            if (it.email.equals(current, ignoreCase = true)) {
                it.copy(isIneligible = true, ineligibleAt = System.currentTimeMillis())
            } else it
        }
        saveAccounts(updated)
    }

    suspend fun beginAddAccount() = withContext(Dispatchers.IO) {
        val currentEmail = mutableState.value.accountEmail
        if (!currentEmail.isNullOrBlank() && officialCredentialFile().isFile) {
            val target = accountCredentialFile(currentEmail).apply { parentFile?.mkdirs() }
            runCatching { officialCredentialFile().copyTo(target, overwrite = true) }
        }
        officialCredentialFile().delete()
        beginLogin()
    }

    suspend fun removeAccount(email: String) = withContext(Dispatchers.IO) {
        val folder = accountFolder(email)
        if (folder.isDirectory) folder.deleteRecursively()
        val remaining = getSavedAccounts().filter { !it.email.equals(email, ignoreCase = true) }
        saveAccounts(remaining)
        if (mutableState.value.accountEmail.equals(email, ignoreCase = true)) {
            val next = remaining.firstOrNull()
            if (next != null) {
                activateAccount(next.email)
            } else {
                logout()
            }
        }
    }

    init {
        installer.ensureGuestCredentialsMigrated()
        // Remove output left by an app/process crash before starting a new OAuth flow.
        authOutput.delete()
        logoutOutput.delete()
        val existing = getSavedAccounts()
        val accounts = if (existing.isEmpty() && initialAccountEmail.isNotBlank() && officialCredentialFile().isFile) {
            val seeded = AntigravityAccount(
                email = initialAccountEmail,
                isActive = true,
                lastUsedAt = System.currentTimeMillis(),
            )
            runCatching {
                val target = accountCredentialFile(initialAccountEmail).apply { parentFile?.mkdirs() }
                officialCredentialFile().copyTo(target, overwrite = true)
            }
            listOf(seeded).also { saveAccounts(it) }
        } else existing
        mutableState.value = mutableState.value.copy(
            accounts = accounts,
            autoRoundRobin = isAutoRoundRobin(),
        )
    }

    private fun officialCredentialFile() = File(
        installer.antigravityCredentialHostDir(),
        "antigravity-oauth-token",
    )

    fun hasOfficialCredential(): Boolean = officialCredentialFile().isFile

    suspend fun beginLogin() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) return@withContext
        if (!installer.isAgentInstalled(com.vayro.app.model.AgentKind.ANTIGRAVITY)) {
            mutableState.value = AntigravityAuthState(
                AntigravityAuthStatus.ERROR,
                message = "Install Antigravity CLI before signing in.",
            )
            return@withContext
        }
        mutableState.value = AntigravityAuthState(AntigravityAuthStatus.STARTING, message = "Starting Google sign-in…")
        codeSubmitted = false
        authOutput.delete()
        val runtime = installer.installedRuntime()
        val workspace = java.io.File(context.filesDir, "workspaces/antigravity-auth").apply { mkdirs() }
        val running = installer.process(
            runtime.proot,
            runtime.rootfs,
            workspace,
            // SSH selects agy's official manual browser URL + one-time code flow.
            // NativeSpawn supplies a real PTY; PocketDev remains only the terminal.
            mapOf(
                "SSH_CONNECTION" to "127.0.0.1 1 127.0.0.1 1",
                "TERM" to "xterm-256color",
                "NO_COLOR" to "1",
            ),
            listOf(RuntimeInstaller.AGY_GUEST_PATH),
            guestWorkspacePath = "/workspace/antigravity-auth",
            emulateHardLinks = false,
            outputFile = authOutput,
            pseudoTerminal = true,
            ptyRows = 40,
            ptyColumns = 120,
            extraBinds = installer.antigravityCredentialBinds(),
        )
        process = running
        val native = running as? NativeSpawnProcess ?: error("Unsupported Antigravity authentication process")
        var offset = 0L
        val output = StringBuilder()
        var handshakeReplies = 0
        var lastHandshakeReplyLength = 0
        var loginMenuAdvanced = false
        var colorScreenCompleted = false
        var renderingScreenCompleted = false
        var privacyScreenCompleted = false
        var workspaceTrustCompleted = false
        try {
            while (running.isAlive || native.outputFile.length() > offset) {
                val available = native.outputFile.length() - offset
                if (available <= 0) {
                    delay(80)
                    continue
                }
                val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                val count = RandomAccessFile(native.outputFile, "r").use { file ->
                    file.seek(offset)
                    file.read(bytes)
                }
                if (count <= 0) continue
                offset += count
                output.append(bytes.decodeToString(0, count))
                val clean = sanitizeTerminalOutput(output.toString()).takeLast(40_000)
                // Antigravity's renderer asks a real terminal for DEC mode and
                // Kitty keyboard-protocol status before it paints its UI, and it
                // may ask again on every new screen. NativeSpawn is pipe-backed,
                // so answer the standard queries ourselves; `script` forwards
                // this to agy's PTY unchanged.
                if (handshakeReplies < 5 &&
                    output.length - lastHandshakeReplyLength > 200 &&
                    output.substring(lastHandshakeReplyLength).contains("\u001B[?u")
                ) {
                    running.outputStream.write(TERMINAL_HANDSHAKE_REPLY.toByteArray())
                    running.outputStream.flush()
                    handshakeReplies++
                    lastHandshakeReplyLength = output.length
                }
                if (handshakeReplies > 0 &&
                    !loginMenuAdvanced &&
                    clean.contains("Select login method", true) &&
                    clean.contains("> 1. Google OAuth", true) &&
                    extractGoogleOAuthUrl(clean) == null
                ) {
                    // agy's TUI (Ink) runs the PTY in raw mode. In raw mode
                    // the kernel does not translate CR to LF, so we must send
                    // the byte Ink treats as the Enter key (0x0A) explicitly.
                    // A lone CR is mapped to the arrow-right key and only
                    // re-paints the menu, while a real terminal transmits
                    // CR LF on Enter, which Ink accepts even with raw input.
                    running.outputStream.write("\r\n".toByteArray())
                    running.outputStream.flush()
                    loginMenuAdvanced = true
                    if (mutableState.value.status == AntigravityAuthStatus.STARTING) {
                        mutableState.value = mutableState.value.copy(
                            message = "Google OAuth selected — waiting for the browser sign-in URL…",
                        )
                    }
                }
                val url = extractGoogleOAuthUrl(clean)
                if (url != null && mutableState.value.authorizationUrl == null) {
                    mutableState.value = AntigravityAuthState(
                        AntigravityAuthStatus.AWAITING_CODE,
                        authorizationUrl = url,
                        message = "Finish signing in with Google, then paste the one-time code.",
                    )
                }
                if (isSignedInScreen(clean)) {
                    val email = extractSignedInEmail(clean)
                    onSignedInChanged(true, email)
                    if (email != null) {
                        recordSignedInAccount(email)
                    }
                    mutableState.value = mutableState.value.copy(
                        status = AntigravityAuthStatus.SIGNED_IN,
                        message = email?.let { "Connected as $it" } ?: "Google account connected",
                        accountEmail = email,
                    )
                    // Leave the official CLI cleanly so it has a chance to flush
                    // its own credential/session state before PocketDev closes
                    // the temporary terminal. PocketDev never reads that state.
                    runCatching {
                        running.outputStream.write("/quit\r\n".toByteArray())
                        running.outputStream.flush()
                    }
                    repeat(20) {
                        if (!running.isAlive) return@repeat
                        delay(50)
                    }
                    if (running.isAlive) running.destroy()
                    break
                }
                if (!colorScreenCompleted && clean.contains("Choose your color scheme", true)) {
                    // Keep the official terminal color scheme selected by default.
                    running.outputStream.write("\r\n".toByteArray())
                    running.outputStream.flush()
                    colorScreenCompleted = true
                }
                if (!renderingScreenCompleted &&
                    clean.contains("no flickering (altscreen)", true) &&
                    clean.contains("Native Terminal experience (inline)", true)
                ) {
                    // Select inline rendering, which is the appropriate mode for
                    // PocketDev's captured PTY output.
                    running.outputStream.write("\u001B[B\r\n".toByteArray())
                    running.outputStream.flush()
                    renderingScreenCompleted = true
                }
                if (!privacyScreenCompleted &&
                    clean.contains("Terms of Service & Data Use", true) &&
                    clean.contains("help improve Antigravity CLI", true)
                ) {
                    // Optional interaction-data collection is selected by default.
                    // PocketDev uses the privacy-preserving choice: Space clears
                    // the checkbox, then two Tabs focus Done and Enter confirms.
                    val optOutAndFinish = if (clean.contains("[x] Yes", true)) {
                        " \t\t\r\n"
                    } else {
                        "\t\t\r\n"
                    }
                    running.outputStream.write(optOutAndFinish.toByteArray())
                    running.outputStream.flush()
                    privacyScreenCompleted = true
                    mutableState.value = mutableState.value.copy(
                        message = "Google connected — finishing private Antigravity setup…",
                    )
                }
                if (!workspaceTrustCompleted &&
                    clean.contains("Do you trust the contents of this project", true) &&
                    clean.contains("Yes, I trust this folder", true)
                ) {
                    // The authentication workspace is created and owned privately
                    // by PocketDev and contains no user project files.
                    running.outputStream.write("\r\n".toByteArray())
                    running.outputStream.flush()
                    workspaceTrustCompleted = true
                }
                if (clean.contains("authentication failed", true) || clean.contains("failed to exchange", true)) {
                    error("Google authentication failed. Start a new sign-in attempt.")
                }
            }
            if (!codeSubmitted && mutableState.value.status == AntigravityAuthStatus.STARTING) {
                val exit = running.waitFor()
                error("Antigravity login closed before producing an authorization URL (exit $exit).")
            }
        } catch (error: Throwable) {
            if (mutableState.value.status != AntigravityAuthStatus.SIGNED_IN) {
                mutableState.value = AntigravityAuthState(
                    AntigravityAuthStatus.ERROR,
                    message = error.message?.take(240) ?: "Antigravity sign-in failed",
                )
            }
        } finally {
            if (running.isAlive) running.destroy()
            runCatching { running.outputStream.close() }
            native.outputFile.delete() // OAuth terminal output is intentionally ephemeral.
            process = null
            codeSubmitted = false
        }
    }

    fun submitCode(code: String) {
        val value = code.trim()
        require(value.isNotBlank()) { "Paste the authorization code from Google" }
        val running = process ?: error("Start Google sign-in again")
        check(running.isAlive) { "The sign-in session expired. Start again." }
        // agy's interactive editor runs the PTY in raw mode and treats CR+LF as
        // the Enter key. LF alone inserts/repaints a line without submitting it.
        running.outputStream.write((value + "\r\n").toByteArray())
        running.outputStream.flush()
        codeSubmitted = true
        mutableState.value = mutableState.value.copy(
            status = AntigravityAuthStatus.COMPLETING,
            message = "Completing Google sign-in…",
        )
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        process?.destroy()
        val previousEmail = mutableState.value.accountEmail
        mutableState.value = AntigravityAuthState(
            status = AntigravityAuthStatus.STARTING,
            message = "Signing out of Antigravity…",
            accountEmail = previousEmail,
        )
        try {
            // Under Android PRoot agy deliberately uses this file instead of a
            // Linux Secret Service keyring. Deleting this exact app-private file
            // is the deterministic equivalent of agy's /logout; its contents are
            // never read, copied, or logged by PocketDev.
            //
            // "Sign out all" (the button's label) must delete EVERY stored
            // token, not just the active one (QA H25): the per-account token
            // copies + accounts_json used to survive, so activateAccount()
            // could sign any of them back in with zero auth after logout.
            // Any deletion failure aborts logout: a surviving token would
            // leave the user believing they are signed out when they are not.
            for (target in antigravitySignOutAllTargets(officialCredentialFile(), accountsDir())) {
                if (target.exists()) {
                    val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
                    check(deleted) {
                        "Could not remove a stored Antigravity credential. Your account remains connected."
                    }
                }
            }
            check(!hasOfficialCredential()) { "Antigravity logout did not complete." }
            saveAccounts(emptyList())
            onSignedInChanged(false, null)
            mutableState.value = mutableState.value.copy(
                status = AntigravityAuthStatus.SIGNED_OUT,
                message = "Signed out",
                accountEmail = null,
            )
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(
                status = AntigravityAuthStatus.SIGNED_IN,
                message = error.message?.take(240) ?: "Could not log out of Antigravity",
                accountEmail = previousEmail,
            )
            throw error
        } finally {
            process = null
            logoutOutput.delete()
        }
    }

    fun cancel() {
        process?.destroy()
        mutableState.value = mutableState.value.copy(status = AntigravityAuthStatus.SIGNED_OUT)
    }

    fun invalidateSession(message: String) {
        onSignedInChanged(false, null)
        mutableState.value = mutableState.value.copy(status = AntigravityAuthStatus.ERROR, message = message)
    }
}

internal fun extractGoogleOAuthUrl(output: String): String? {
    val compact = output.replace(Regex("[\\r\\n\\t ]+"), "")
    // agy's Ink renderer wraps the URL across terminal rows. Removing that
    // whitespace reconstructs it, but the next rendered labels may then become
    // adjacent to the URL. The PKCE `state` value is the final parameter emitted
    // by agy, so terminate at its base64url-safe value instead of consuming TUI
    // copy such as "Copy and paste the URL".
    GOOGLE_OAUTH_URL.find(compact)?.value
        ?.takeIf { "client_id=" in it && "code_challenge=" in it }
        ?.let { return it }
    // Fallback for post-menu screens that print the browser URL in a different
    // shape (wrapped lines, shortened query display). Only apply once the login
    // menu has left the screen so help text cannot produce a false positive.
    if (!output.contains("Select login method", true) &&
        (output.contains("browser", true) || output.contains("visit", true) ||
            output.contains("open", true) || output.contains("code", true) ||
            output.contains("paste", true))
    ) {
        val candidates = Regex("https://[^\\s\"']{20,}")
            .findAll(compact)
            .map { it.value.trimEnd { char -> char !in URL_CHARACTERS } }
            .filter { it.length >= 30 && "." in it }
            .toList()
        return candidates.firstOrNull { "google" in it } ?: candidates.firstOrNull()
    }
    return null
}

private fun isSignedInScreen(output: String): Boolean =
    output.contains("for shortcuts", true) ||
        (output.contains("Antigravity CLI", true) && output.contains("Google AI", true))

private fun extractSignedInEmail(output: String): String? =
    Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        .findAll(output)
        .map { it.value }
        .firstOrNull { !it.endsWith(".apps.googleusercontent.com", ignoreCase = true) }

private val URL_CHARACTERS = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" +
    "-._~:/?#[]@!$&'()*+,;=%").toSet()

private val GOOGLE_OAUTH_URL = Regex(
    "https://accounts\\.google\\.com/[^\\s\\\"'<>]*?[?&]state=[A-Za-z0-9._~-]+",
)

private const val TERMINAL_HANDSHAKE_REPLY = "\u001B[?2026;1\$y\u001B[?2027;1\$y\u001B[?1u\n"

private val AUTH_ANSI = Regex("\\u001B(?:\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")
private fun sanitizeTerminalOutput(text: String): String = text
    .replace(AUTH_ANSI, "")
    .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }

/**
 * QA H25: the full deletion set for "Sign out all" — the active official
 * credential file plus every per-account token folder under [accountsDir].
 * Only directories count as per-account entries (each holds one account's
 * `antigravity-oauth-token`); stray files in the dir are never deleted.
 * Extracted so the set is unit-tested once; [AntigravityAuthController]
 * needs Android Context and cannot run in a JVM unit test.
 */
internal fun antigravitySignOutAllTargets(officialCredential: File, accountsDir: File): List<File> {
    val targets = mutableListOf(officialCredential)
    accountsDir.listFiles()
        ?.filter { it.isDirectory }
        ?.sortedBy { it.name }
        ?.forEach { targets.add(it) }
    return targets
}
