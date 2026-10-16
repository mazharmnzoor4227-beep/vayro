package com.vayro.app.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.vayro.app.BuildConfig
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
    val variant: String = BuildConfig.APP_VARIANT,
)

class AppUpdater(
    private val context: Context,
    /**
     * Optional manifest URL or proxy endpoint override. When non-empty,
     * used in place of [BuildConfig.APP_UPDATE_MANIFEST_URL].
     */
    private val manifestUrlOverride: String = "",
    /**
     * Optional Bearer / Personal Access Token for accessing private GitHub repository releases.
     */
    private val authToken: String = "",
    /**
     * True for Google Play builds. Play builds must never self-update from a
     * GitHub APK — Play policy reserves updates to Google Play itself.
     * Defaults to the build flag; tests pass an explicit value.
     */
    private val isPlayBuild: Boolean = BuildConfig.IS_PLAY_BUILD,
) {
    /**
     * QA Medium: an update check used to swallow every error and report
     * "no update" — offline looked exactly like "up to date", and a forced
     * check toasted "up to date" on a network failure. The check now
     * distinguishes the three outcomes so callers can say "check failed"
     * instead of "up to date".
     */
    sealed interface AppUpdateCheckResult {
        data object UpToDate : AppUpdateCheckResult
        data class Available(val info: AppUpdateInfo) : AppUpdateCheckResult
        data class Failed(val error: String) : AppUpdateCheckResult
    }

    fun check(): AppUpdateCheckResult {
        // Play builds never check a side-channel manifest: no GitHub update
        // is ever offered on a build distributed through Google Play.
        if (!isSelfUpdateAllowed(isPlayBuild)) return AppUpdateCheckResult.UpToDate
        val urlsToTry = if (manifestUrlOverride.isNotBlank()) {
            listOf(manifestUrlOverride)
        } else {
            listOf(
                BuildConfig.APP_UPDATE_MANIFEST_URL,
                // QA Medium: the upstream author's manifest used to be a
                // third fallback here — a repo this project does not
                // control presenting update offers. Removed: only this
                // project's own manifest and its own releases API answer.
                "https://api.github.com/repos/mazharmnzoor4227-beep/vayro/releases/latest",
            )
        }

        var lastError: Throwable? = null
        for (manifestUrl in urlsToTry) {
            if (!manifestUrl.startsWith("https://")) continue
            try {
                val update = if (manifestUrl.contains("api.github.com/repos/") && manifestUrl.endsWith("/releases/latest")) {
                    checkGitHubReleaseApi(manifestUrl)
                } else {
                    checkStandardManifest(manifestUrl)
                }
                if (update != null) return AppUpdateCheckResult.Available(update)
            } catch (e: Throwable) {
                // Try the next URL; if nothing answers, the failure below
                // is reported instead of masquerading as "up to date".
                lastError = e
            }
        }
        return if (lastError != null) {
            AppUpdateCheckResult.Failed(lastError.message?.take(200) ?: "Update check failed")
        } else {
            AppUpdateCheckResult.UpToDate
        }
    }

    private fun checkStandardManifest(manifestUrl: String): AppUpdateInfo? {
        val connection = openConnectionFollowingRedirects(manifestUrl) { hopUrl, conn ->
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            if (authToken.isNotBlank() && isGitHubDomain(hopUrl)) {
                conn.setRequestProperty("Authorization", "Bearer $authToken")
            }
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            // QA Medium: a failed fetch used to read as "no update".
            throw java.io.IOException("Update manifest fetch failed: HTTP $code")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val root = JSONObject(body)
        val versionCode = root.optLong("versionCode")
        if (versionCode <= BuildConfig.VERSION_CODE) return null

        val artifact = resolveArtifactForVariant(root, BuildConfig.APP_VARIANT) ?: return null
        val url = artifact.optString("url").ifBlank { artifact.optString("apkUrl") }
        if (!url.startsWith("https://")) return null

        return AppUpdateInfo(
            versionCode = versionCode,
            versionName = root.optString("versionName", versionCode.toString()),
            apkUrl = url,
            sha256 = artifact.optString("sha256").lowercase(),
            sizeBytes = artifact.optLong("sizeBytes", -1L),
            notes = root.optString("notes"),
            variant = BuildConfig.APP_VARIANT,
        )
    }

    private fun checkGitHubReleaseApi(apiUrl: String): AppUpdateInfo? {
        val connection = openConnectionFollowingRedirects(apiUrl) { hopUrl, conn ->
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (authToken.isNotBlank() && isGitHubDomain(hopUrl)) {
                conn.setRequestProperty("Authorization", "Bearer $authToken")
            }
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            // QA Medium: a failed fetch used to read as "no update".
            throw java.io.IOException("GitHub releases fetch failed: HTTP $code")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val releaseJson = JSONObject(body)
        val assets = releaseJson.optJSONArray("assets") ?: JSONArray()

        // 1. If release contains mobile-harness-update.json, fetch and parse it
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == "mobile-harness-update.json") {
                val assetApiUrl = asset.optString("url")
                val manifestContent = fetchStringWithAuth(assetApiUrl)
                    // QA Medium: an unreadable manifest used to read as
                    // "no update". The release HAS a manifest asset — if it
                    // cannot be fetched, that is a check failure.
                    ?: throw java.io.IOException("Could not download the release manifest asset")
                val root = runCatching { JSONObject(manifestContent) }.getOrNull()
                    ?: throw java.io.IOException("Release manifest asset is not valid JSON")
                run {
                    val versionCode = root.optLong("versionCode")
                    if (versionCode <= BuildConfig.VERSION_CODE) return null
                    val artifact = resolveArtifactForVariant(root, BuildConfig.APP_VARIANT) ?: continue
                    val rawUrl = artifact.optString("url").ifBlank { artifact.optString("apkUrl") }

                    // If the repo is private, download URL may require GitHub Asset API URL instead of raw HTML redirect
                    val targetApkUrl = if (isGitHubDomain(rawUrl) && authToken.isNotBlank()) {
                        findAssetUrlForVariant(assets, BuildConfig.APP_VARIANT) ?: rawUrl
                    } else {
                        rawUrl
                    }

                    return AppUpdateInfo(
                        versionCode = versionCode,
                        versionName = root.optString("versionName", versionCode.toString()),
                        apkUrl = targetApkUrl,
                        sha256 = artifact.optString("sha256").lowercase(),
                        sizeBytes = artifact.optLong("sizeBytes", -1L),
                        notes = root.optString("notes").ifBlank { releaseJson.optString("body") },
                        variant = BuildConfig.APP_VARIANT,
                    )
                }
            }
        }

        // No manifest asset, no update: the tag-derived fallback that used to
        // live here is DELETED (QA H20). It fabricated a versionCode from
        // the tag (v3.0.1 -> 30001) that can never equal the real APK
        // versionCode (34), so verifyApk always threw — and it set
        // sha256 = "" (no hash verification). CI publishes
        // mobile-harness-update.json with the true versionCode + SHA-256
        // on every release, so a release without it offers no update
        // rather than an unverifiable one.
        return null
    }

    @Volatile
    private var activeDownloadConnection: HttpURLConnection? = null

    fun cancelDownload() {
        activeDownloadConnection?.disconnect()
    }

    fun download(info: AppUpdateInfo, progress: (Long, Long) -> Unit): File {
        check(isSelfUpdateAllowed(isPlayBuild)) {
            "Self-update is disabled on Google Play builds; updates come from Google Play."
        }
        val directory = File(context.filesDir, "updates").also { it.mkdirs() }
        // QA Medium: the download used to restart from zero on any
        // interruption. The partial file is now versioned, so an
        // interrupted download resumes where it left off; partials from
        // other versions are dropped (resuming them would corrupt this
        // download).
        val partial = File(directory, "mobile-harness-${BuildConfig.APP_VARIANT}-${info.versionCode}.apk.part")
        directory.listFiles()
            ?.filter { it.name.endsWith(".apk.part") && it != partial }
            ?.forEach { it.delete() }
        val target = File(directory, "mobile-harness-${BuildConfig.APP_VARIANT}.apk")

        val resumeFrom = if (partial.exists()) partial.length() else 0L
        val (stream, totalBytes, startOffset) = openDownloadStream(info.apkUrl, info.sizeBytes, resumeFrom)
        val append = startOffset > 0
        try {
            java.io.FileOutputStream(partial, append).use { output ->
                val buffer = ByteArray(128 * 1024)
                var downloaded = startOffset
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    progress(downloaded, totalBytes)
                }
            }
        } finally {
            activeDownloadConnection = null
            stream.close()
        }

        try {
            if (info.sha256.isNotBlank()) {
                val actual = sha256(partial)
                check(actual.equals(info.sha256, ignoreCase = true)) { "Downloaded APK failed its SHA-256 verification" }
            }
            verifyApk(partial, info.versionCode)
        } catch (e: Throwable) {
            // A failed verification means these bytes will never verify:
            // drop the partial so the next attempt restarts clean instead
            // of resuming corrupt bytes and failing forever.
            partial.delete()
            throw e
        }
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "Could not prepare the downloaded update" }
        return target
    }

    private val redirectStatusCodes = setOf(
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        307,
        308,
    )

    private fun openConnectionFollowingRedirects(
        urlStr: String,
        maxRedirects: Int = 5,
        configure: (hopUrl: String, connection: HttpURLConnection) -> Unit,
    ): HttpURLConnection {
        var currentUrl = urlStr
        var redirects = 0
        while (true) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            configure(currentUrl, connection)
            if (connection.responseCode !in redirectStatusCodes) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            currentUrl = resolveRedirectUrl(currentUrl, location)
                ?: error("Blocked an unsafe update redirect")
            redirects += 1
            check(redirects <= maxRedirects) { "Too many redirects" }
        }
    }

    /**
     * Opens the download stream, following up to 5 redirects. When
     * [resumeFrom] > 0, requests `Range: bytes=<resumeFrom>-` so an
     * interrupted download continues where it left off. Returns the
     * stream, the total expected bytes, and the offset the stream starts
     * at (0 when the server ignored the Range request).
     */
    private fun openDownloadStream(urlStr: String, fallbackSize: Long, resumeFrom: Long = 0L): Triple<InputStream, Long, Long> {
        val connection = openConnectionFollowingRedirects(urlStr) { hopUrl, conn ->
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (authToken.isNotBlank() && isGitHubDomain(hopUrl)) {
                conn.setRequestProperty("Authorization", "Bearer $authToken")
            }
            conn.setRequestProperty("Accept", "application/octet-stream")
            if (resumeFrom > 0) {
                conn.setRequestProperty("Range", "bytes=$resumeFrom-")
            }
        }
        val code = connection.responseCode
        check(code in 200..299) { "Update download failed (HTTP $code)" }
        val startOffset = if (code == HttpURLConnection.HTTP_PARTIAL) resumeFrom else 0L
        val remaining = connection.contentLengthLong.takeIf { it > 0 }
        val total = if (remaining != null) startOffset + remaining else maxOf(fallbackSize, startOffset)
        activeDownloadConnection = connection
        return Triple(connection.inputStream, total, startOffset)
    }

    private fun fetchStringWithAuth(urlStr: String): String? {
        return try {
            val (stream, _, _) = openDownloadStream(urlStr, -1L)
            stream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, expectedVersionCode: Long) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Downloaded file is not a valid APK")
        check(archive.packageName == context.packageName) { "Update package name does not match Vayro" }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
        check(archiveVersion == expectedVersionCode) { "Update version does not match its manifest" }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.signingInfo?.apkContentsSigners else archive.signatures
        val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installed.signingInfo?.apkContentsSigners else installed.signatures
        check(!archiveSignatures.isNullOrEmpty() && !installedSignatures.isNullOrEmpty() &&
            archiveSignatures.map { sha256(it.toByteArray()) }.toSet() == installedSignatures.map { sha256(it.toByteArray()) }.toSet()
        ) { "Update is not signed with the installed app's signing key" }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * The single decision point for GitHub self-update: allowed everywhere
         * except Google Play builds. [AppUpdater.check] and [AppUpdater.download]
         * both consult it, so a Play build can never offer or fetch a side-channel APK.
         */
        fun isSelfUpdateAllowed(isPlayBuild: Boolean): Boolean = !isPlayBuild

        fun resolveArtifactForVariant(root: JSONObject, variant: String): JSONObject? {
            return root.optJSONObject("artifacts")?.optJSONObject(variant)
                ?: root.optJSONObject(variant)
                ?: root.takeIf { it.has("url") || it.has("apkUrl") }
        }

        fun isGitHubDomain(url: String): Boolean {
            val parsed = runCatching { URL(url) }.getOrNull() ?: return false
            if (!parsed.protocol.equals("https", ignoreCase = true)) return false
            val host = parsed.host.lowercase()
            return host == "github.com" || host.endsWith(".github.com")
        }

        fun resolveRedirectUrl(currentUrl: String, location: String?): String? {
            if (location.isNullOrBlank()) return null
            val base = runCatching { URL(currentUrl) }.getOrNull() ?: return null
            val resolved = runCatching { URL(base, location) }.getOrNull() ?: return null
            if (!resolved.protocol.equals("https", ignoreCase = true)) return null
            return resolved.toString()
        }

        private fun findAssetUrlForVariant(assets: JSONArray, variant: String): String? {
            return findAssetForVariant(assets, variant)?.optString("url")
        }

        private fun findAssetForVariant(assets: JSONArray, variant: String): JSONObject? {
            val targetName = "app-$variant-release.apk"
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (name.equals(targetName, ignoreCase = true) || (name.contains(variant, ignoreCase = true) && name.endsWith(".apk", ignoreCase = true))) {
                    return asset
                }
            }
            return null
        }
    }
}
