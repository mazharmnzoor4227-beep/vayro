package com.vayro.app.git.automation

import com.vayro.app.core.result.OperationResult
import com.vayro.app.network.GitHubClient
import java.io.File

/**
 * Milestone 9 — release automation.
 *
 * Tag → release → assets pipeline: optionally creates the git tag ref, generates
 * release notes via the GitHub API, creates the release, then uploads each asset.
 * Asset uploads are per-file: a failed upload fails the whole operation and names
 * the file, with the partial state (release URL, assets uploaded so far) in the
 * logs so nothing is misreported.
 */
data class ReleaseAssetFile(
    val file: File,
    val contentType: String,
    val name: String = file.name,
)

data class ReleaseRequest(
    val tagName: String,
    val name: String? = null,
    /** Commit SHA to tag. Null = tag the repo's default branch head (API resolves). */
    val headSha: String? = null,
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    /** When true, body is replaced by GitHub-generated release notes. */
    val generateNotes: Boolean = false,
    val previousTagName: String? = null,
    val assets: List<ReleaseAssetFile> = emptyList(),
    /** Maximum asset size in bytes; larger files are refused before upload. */
    val maxAssetBytes: Long = 100L * 1024 * 1024,
)

class ReleaseAutomation(
    private val client: GitHubClient = GitHubClient(),
) {
    fun publish(
        token: String,
        repoFullName: String,
        request: ReleaseRequest,
    ): OperationResult {
        val startedAt = System.currentTimeMillis()
        if (token.isBlank()) {
            return OperationResult.notConfigured(
                "GitHub token is missing. Refusing to publish release ${request.tagName} without credentials."
            )
        }
        if (request.tagName.isBlank()) {
            return OperationResult.failure(
                error = "Refusing to publish a release with a blank tag name.",
                startedAt = startedAt,
            )
        }
        val logs = mutableListOf<String>()
        return try {
            if (request.headSha != null) {
                val ref = client.createGitRef(token, repoFullName, "refs/tags/${request.tagName}", request.headSha)
                logs += "tag ref created: $ref -> ${request.headSha.take(8)}"
            }
            val notes = if (request.generateNotes) {
                client.generateReleaseNotes(token, repoFullName, request.tagName, request.previousTagName)
                    .also { logs += "release notes generated (${it.length} chars)" }
            } else {
                request.body
            }
            val release = client.createRelease(
                token = token,
                repoFullName = repoFullName,
                tagName = request.tagName,
                name = request.name ?: request.tagName,
                body = notes,
                draft = request.draft,
                prerelease = request.prerelease,
            )
            logs += "release created: ${release.htmlUrl} (id=${release.id})"

            if (release.uploadUrl.isBlank() && request.assets.isNotEmpty()) {
                return OperationResult.failure(
                    error = "Release ${request.tagName} was created but GitHub returned no upload URL; " +
                        "${request.assets.size} asset(s) could not be uploaded. Release: ${release.htmlUrl}",
                    logs = logs,
                    startedAt = startedAt,
                )
            }
            val uploaded = mutableListOf<String>()
            for (asset in request.assets) {
                if (!asset.file.isFile) {
                    return OperationResult.failure(
                        error = "Asset '${asset.name}' is not a readable file (${asset.file.path}). " +
                            "Release ${release.htmlUrl} exists with ${uploaded.size} asset(s) uploaded.",
                        logs = logs,
                        startedAt = startedAt,
                    )
                }
                if (asset.file.length() > request.maxAssetBytes) {
                    return OperationResult.failure(
                        error = "Asset '${asset.name}' (${asset.file.length()} bytes) exceeds the " +
                            "${request.maxAssetBytes}-byte limit. Release ${release.htmlUrl} exists with " +
                            "${uploaded.size} asset(s) uploaded.",
                        logs = logs,
                        startedAt = startedAt,
                    )
                }
                val uploadedAsset = client.uploadReleaseAsset(
                    uploadUrlTemplate = release.uploadUrl,
                    fileName = asset.name,
                    contentStream = { asset.file.inputStream() },
                    contentLength = asset.file.length(),
                    contentType = asset.contentType,
                    token = token,
                )
                uploaded += uploadedAsset.name
                logs += "asset uploaded: ${uploadedAsset.name} (${uploadedAsset.sizeBytes} bytes)"
            }
            val evidence =
                "Release ${request.tagName} published: ${release.htmlUrl} with ${uploaded.size} asset(s)"
            OperationResult.success(
                logs = logs,
                artifacts = listOf(release.htmlUrl) + uploaded,
                verificationEvidence = evidence,
                startedAt = startedAt,
            )
        } catch (e: Exception) {
            OperationResult.failure(
                error = "Release publish failed for ${request.tagName}: ${e.message}",
                logs = logs,
                startedAt = startedAt,
            )
        }
    }
}
