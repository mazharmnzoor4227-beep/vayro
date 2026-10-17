package com.vayro.app.marketplace

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

const val MARKETPLACE_UPDATE_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

val CURATED_PRESEED_IDS: Set<String> = setOf(
    "catalog-skill-rea",
    "catalog-skill-desigma",
    "catalog-skill-flick",
    "catalog-skill-app-store-screenshots",
    "catalog-skill-ux-ui-agent-skills",
    "catalog-skill-gsap-skills",
    "catalog-skill-typography-system",
    "catalog-skill-awesome-gamedev-agent-skills",
    "catalog-skill-game-ui-mobile-friendly-design",
)

// v2 (v3.2.24): the design & game skill pack was added to CURATED_PRESEED_IDS;
// the bump makes already-seeded installs pick up the new ids (pendingSeedIds
// still skips anything already installed).
const val CURATED_SEED_VERSION = 2

fun parseGitHubRepo(installUrl: String): Pair<String, String>? {
    val trimmed = installUrl.trim().removeSuffix("/").removeSuffix(".git")
    val prefix = "https://github.com/"
    if (!trimmed.startsWith(prefix)) return null
    val rest = trimmed.removePrefix(prefix)
    val parts = rest.split("/").filter { it.isNotBlank() }
    if (parts.size != 2) return null
    return parts[0] to parts[1]
}

fun isUpdateAvailable(storedSha: String?, headSha: String?): Boolean {
    if (storedSha.isNullOrBlank() || headSha.isNullOrBlank()) return false
    return !storedSha.equals(headSha.trim(), ignoreCase = true)
}

fun updateCheckDue(lastCheckAt: Long, now: Long): Boolean {
    if (lastCheckAt <= 0L) return true
    return now - lastCheckAt >= MARKETPLACE_UPDATE_CHECK_INTERVAL_MS
}

fun pendingSeedIds(seedIds: Set<String>, installedIds: Set<String>): Set<String> {
    return seedIds - installedIds
}

private fun githubApiGet(url: String): JSONObject? {
    var connection: HttpURLConnection? = null
    return try {
        connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "Kodex-Marketplace")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connect()
        if (connection.responseCode !in 200..299) return null
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        if (body.isBlank()) return null
        JSONObject(body)
    } catch (e: Exception) {
        null
    } finally {
        runCatching { connection?.disconnect() }
    }
}

fun fetchRepoDefaultBranch(owner: String, repo: String): String? {
    return githubApiGet("https://api.github.com/repos/$owner/$repo")
        ?.optString("default_branch")
        ?.takeIf { it.isNotBlank() }
}

fun fetchBranchHeadSha(owner: String, repo: String, branch: String): String? {
    return githubApiGet("https://api.github.com/repos/$owner/$repo/commits/$branch")
        ?.optString("sha")
        ?.takeIf { it.isNotBlank() }
}

fun resolveUpstreamHeadSha(installUrl: String): String? {
    val (owner, repo) = parseGitHubRepo(installUrl) ?: return null
    val branch = fetchRepoDefaultBranch(owner, repo) ?: return null
    return fetchBranchHeadSha(owner, repo, branch)
}
