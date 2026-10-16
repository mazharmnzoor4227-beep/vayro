package com.vayro.app.git.automation

import com.vayro.app.core.result.OperationStatus
import com.vayro.app.network.GitHubClient
import com.vayro.app.network.GitHubRelease
import com.vayro.app.network.GitHubReleaseAsset
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeReleaseClient(
    var uploadUrl: String = "https://uploads.github.com/repos/o/r/releases/1/assets{?name,label}",
    var notes: String = "Generated notes",
) : GitHubClient() {
    var refCreated: String? = null
    var createdRelease: GitHubRelease? = null
    val uploadedNames = mutableListOf<String>()

    override fun createGitRef(token: String, repoFullName: String, ref: String, sha: String): String {
        refCreated = ref
        return ref
    }

    override fun generateReleaseNotes(token: String, repoFullName: String, tagName: String, previousTagName: String?, targetCommitish: String?): String {
        return notes
    }

    override fun createRelease(
        token: String,
        repoFullName: String,
        tagName: String,
        name: String,
        body: String,
        draft: Boolean,
        prerelease: Boolean,
    ): GitHubRelease {
        val release = GitHubRelease(
            id = 1L, tagName = tagName, name = name, body = body,
            draft = draft, prerelease = prerelease,
            createdAt = "", publishedAt = "",
            htmlUrl = "https://github.com/o/r/releases/tag/$tagName",
            uploadUrl = uploadUrl,
        )
        createdRelease = release
        return release
    }

    override fun uploadReleaseAsset(
        uploadUrlTemplate: String,
        fileName: String,
        contentStream: () -> java.io.InputStream,
        contentLength: Long,
        contentType: String,
        token: String,
    ): GitHubReleaseAsset {
        uploadedNames += fileName
        return GitHubReleaseAsset(
            id = uploadedNames.size.toLong(), name = fileName,
            sizeBytes = contentLength, downloadUrl = "https://example.com/$fileName",
            contentType = contentType,
        )
    }
}

class ReleaseAutomationTest {

    private fun tempFile(name: String, bytes: ByteArray): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "release_test_${System.nanoTime()}").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(bytes) }
    }

    @Test
    fun publishTagReleaseAssets() {
        val client = FakeReleaseClient()
        val apk = tempFile("app.apk", ByteArray(16) { it.toByte() })
        val result = ReleaseAutomation(client).publish(
            "tok", "o/r",
            ReleaseRequest(
                tagName = "v2.0.0",
                headSha = "deadbeef",
                body = "Big release",
                assets = listOf(ReleaseAssetFile(apk, "application/vnd.android.package-archive")),
            )
        )
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertEquals("refs/tags/v2.0.0", client.refCreated)
        assertEquals(listOf("app.apk"), client.uploadedNames)
        assertTrue(result.verificationEvidence!!.contains("v2.0.0"))
        assertTrue(result.artifacts.any { it.contains("releases/tag/v2.0.0") })
    }

    @Test
    fun publishWithGeneratedNotes() {
        val client = FakeReleaseClient(notes = "## What's Changed\n* stuff")
        val result = ReleaseAutomation(client).publish(
            "tok", "o/r",
            ReleaseRequest(tagName = "v2.1.0", generateNotes = true, previousTagName = "v2.0.0")
        )
        assertEquals(OperationStatus.SUCCESS, result.status)
        assertEquals("## What's Changed\n* stuff", client.createdRelease!!.body)
    }

    @Test
    fun missingUploadUrlWithAssetsFails() {
        val client = FakeReleaseClient(uploadUrl = "")
        val apk = tempFile("app.apk", ByteArray(4))
        val result = ReleaseAutomation(client).publish(
            "tok", "o/r",
            ReleaseRequest(tagName = "v3.0.0", assets = listOf(ReleaseAssetFile(apk, "application/octet-stream")))
        )
        assertEquals(OperationStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("no upload URL"))
        // release was still created — partial state is reported, not hidden
        assertTrue(result.logs.any { it.contains("release created") })
    }

    @Test
    fun missingAssetFileFailsAndNamesFile() {
        val client = FakeReleaseClient()
        val missing = File(System.getProperty("java.io.tmpdir"), "does-not-exist-${System.nanoTime()}.apk")
        val result = ReleaseAutomation(client).publish(
            "tok", "o/r",
            ReleaseRequest(tagName = "v3.1.0", assets = listOf(ReleaseAssetFile(missing, "application/octet-stream")))
        )
        assertEquals(OperationStatus.FAILED, result.status)
        assertTrue(result.error!!.contains(missing.name))
    }

    @Test
    fun oversizedAssetIsRefused() {
        val client = FakeReleaseClient()
        val big = tempFile("big.bin", ByteArray(10))
        val result = ReleaseAutomation(client).publish(
            "tok", "o/r",
            ReleaseRequest(tagName = "v3.2.0", maxAssetBytes = 5L, assets = listOf(ReleaseAssetFile(big, "application/octet-stream")))
        )
        assertEquals(OperationStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("exceeds"))
        assertTrue(client.uploadedNames.isEmpty())
    }

    @Test
    fun blankTagNameRefused() {
        val client = FakeReleaseClient()
        val result = ReleaseAutomation(client).publish("tok", "o/r", ReleaseRequest(tagName = "  "))
        assertEquals(OperationStatus.FAILED, result.status)
    }

    @Test
    fun blankTokenIsNotConfigured() {
        val client = FakeReleaseClient()
        val result = ReleaseAutomation(client).publish("", "o/r", ReleaseRequest(tagName = "v9.9.9"))
        assertEquals(OperationStatus.NOT_CONFIGURED, result.status)
        assertEquals(null, client.createdRelease)
    }
}
