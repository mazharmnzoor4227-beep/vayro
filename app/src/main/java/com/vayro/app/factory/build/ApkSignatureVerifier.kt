package com.vayro.app.factory.build

import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Which tool performed signature verification. */
enum class SignatureTool {
    APKSIGNER,
    JARSIGNER_FALLBACK,
    NONE,
}

/** Honest result of verifying an APK's signature. */
data class SignatureVerificationResult(
    val file: File,
    val verified: Boolean,
    val toolUsed: SignatureTool,
    val toolAvailable: Boolean,
    val signerCertSha256: String?,
    val signerDn: String?,
    val warnings: List<String>,
    val rawOutput: String,
    val durationMs: Long = 0L,
)

/** Honest result of validating an AAB's bundle structure. */
data class AabValidationResult(
    val file: File,
    val valid: Boolean,
    val hasBundleConfig: Boolean,
    val baseModuleEntries: Int,
    val error: String?,
)

/** Locates the `apksigner` binary. Injectable for tests. */
fun interface ApksignerLocator {
    fun locate(): File?
}

/** Production locator: ANDROID_HOME/build-tools/<latest>/apksigner, then PATH. */
class DefaultApksignerLocator : ApksignerLocator {

    override fun locate(): File? {
        val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (!androidHome.isNullOrBlank()) {
            val buildTools = File(androidHome, "build-tools")
            if (buildTools.isDirectory) {
                val latest = buildTools.listFiles { f -> f.isDirectory }
                    ?.maxByOrNull { it.name }
                    ?.let { File(it, "apksigner") }
                if (latest != null && latest.isFile && latest.canExecute()) return latest
            }
        }
        return runCatching {
            val process = ProcessBuilder("sh", "-c", "command -v apksigner")
                .redirectErrorStream(true)
                .start()
            val path = process.inputStream.bufferedReader().readText().trim()
            process.waitFor(10, TimeUnit.SECONDS)
            if (process.exitValue() == 0 && path.isNotBlank()) File(path).takeIf { it.isFile } else null
        }.getOrNull()
    }
}

private val signerSha256Regex = Regex("(?i)certificate SHA-256 digest:\\s*([0-9a-f: ]+)")
private val signerDnRegex = Regex("(?i)certificate DN:\\s*(.+)")

/**
 * Real APK/AAB signing verification (v3, Milestone 8).
 *
 * [verifyApk] genuinely invokes `apksigner verify --print-certs` and parses
 * the signer certificate SHA-256 + DN. When no signer tooling is available it
 * reports `toolAvailable=false` — never claims "verified" without a real check.
 * [validateAab] inspects the AAB zip structure for `BundleConfig.pb` and base
 * module entries.
 */
object ApkSignatureVerifier {

    fun verifyApk(
        apkFile: File,
        locator: ApksignerLocator = DefaultApksignerLocator(),
        timeout: Duration = 5.minutes,
    ): SignatureVerificationResult {
        val startedAt = System.currentTimeMillis()
        require(apkFile.isFile) { "APK not found: ${apkFile.absolutePath}" }

        val apksigner = locator.locate()
        if (apksigner == null) {
            return SignatureVerificationResult(
                file = apkFile,
                verified = false,
                toolUsed = SignatureTool.NONE,
                toolAvailable = false,
                signerCertSha256 = null,
                signerDn = null,
                warnings = listOf("apksigner not found (checked ANDROID_HOME/build-tools and PATH) — signature could not be verified."),
                rawOutput = "",
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }

        return try {
            val process = ProcessBuilder(
                apksigner.absolutePath, "verify", "--print-certs", "--verbose", apkFile.absolutePath
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            val durationMs = System.currentTimeMillis() - startedAt
            if (!finished) {
                process.destroyForcibly()
                return SignatureVerificationResult(
                    file = apkFile, verified = false, toolUsed = SignatureTool.APKSIGNER,
                    toolAvailable = true, signerCertSha256 = null, signerDn = null,
                    warnings = listOf("apksigner timed out after ${timeout.inWholeMinutes} minutes."),
                    rawOutput = output, durationMs = durationMs,
                )
            }
            val exitOk = process.exitValue() == 0
            val warnings = output.lines()
                .filter { it.trimStart().startsWith("WARNING:", ignoreCase = true) }
                .map { it.trim() }
            val sha = signerSha256Regex.find(output)?.groupValues?.get(1)
                ?.replace(":", "")?.replace(" ", "")?.lowercase()?.takeIf { it.isNotBlank() }
            val dn = signerDnRegex.find(output)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            SignatureVerificationResult(
                file = apkFile,
                verified = exitOk,
                toolUsed = SignatureTool.APKSIGNER,
                toolAvailable = true,
                signerCertSha256 = sha,
                signerDn = dn,
                warnings = warnings,
                rawOutput = output,
                durationMs = durationMs,
            )
        } catch (e: Exception) {
            SignatureVerificationResult(
                file = apkFile, verified = false, toolUsed = SignatureTool.APKSIGNER,
                toolAvailable = true, signerCertSha256 = null, signerDn = null,
                warnings = listOf("Failed to run apksigner: ${e.message}"),
                rawOutput = "", durationMs = System.currentTimeMillis() - startedAt,
            )
        }
    }

    /**
     * Validates that [aabFile] is a structurally sound Android App Bundle:
     * a valid ZIP containing `BundleConfig.pb` and at least one `base/` module entry.
     */
    fun validateAab(aabFile: File): AabValidationResult {
        require(aabFile.isFile) { "AAB not found: ${aabFile.absolutePath}" }
        return try {
            ZipFile(aabFile).use { zip ->
                val entries = Collections.list(zip.entries())
                if (entries.isEmpty()) {
                    return AabValidationResult(aabFile, false, false, 0, "AAB is an empty zip archive.")
                }
                val hasBundleConfig = entries.any { it.name == "BundleConfig.pb" }
                val baseEntries = entries.count { it.name.startsWith("base/") }
                val valid = hasBundleConfig && baseEntries > 0
                AabValidationResult(
                    file = aabFile,
                    valid = valid,
                    hasBundleConfig = hasBundleConfig,
                    baseModuleEntries = baseEntries,
                    error = when {
                        !hasBundleConfig -> "Missing BundleConfig.pb at archive root."
                        baseEntries == 0 -> "No base/ module entries found."
                        else -> null
                    },
                )
            }
        } catch (e: Exception) {
            AabValidationResult(aabFile, false, false, 0, "Not a readable zip archive: ${e.message}")
        }
    }
}
