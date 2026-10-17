package com.vayro.app.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubArtifactsTest {

    private fun artifactsJson(vararg artifacts: Triple<Long, String, Boolean>): JSONObject {
        val array = JSONArray()
        for ((id, name, expired) in artifacts) {
            array.put(
                JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("size_in_bytes", 1234L)
                    put("archive_download_url", "https://api.github.com/repos/o/r/actions/artifacts/$id/zip")
                    put("expired", expired)
                }
            )
        }
        return JSONObject().apply { put("artifacts", array) }
    }

    @Test
    fun parseRunArtifactsReadsFields() {
        val parsed = parseRunArtifacts(
            artifactsJson(
                Triple(7L, "myapp-apk", false),
                Triple(8L, "old-aab", true),
            )
        )
        assertEquals(2, parsed.size)
        assertEquals(7L, parsed[0].id)
        assertEquals("myapp-apk", parsed[0].name)
        assertEquals(1234L, parsed[0].sizeBytes)
        assertTrue(parsed[0].archiveDownloadUrl.endsWith("/7/zip"))
        assertTrue(parsed[1].expired)
    }

    @Test
    fun parseRunArtifactsHandlesMissingArray() {
        assertTrue(parseRunArtifacts(JSONObject()).isEmpty())
    }

    @Test
    fun selectBuildArtifactPrefersExtensionMatch() {
        val artifacts = parseRunArtifacts(
            artifactsJson(
                Triple(1L, "logs", false),
                Triple(2L, "myapp-apk", false),
                Triple(3L, "myapp-aab", false),
            )
        )
        assertEquals(2L, selectBuildArtifact(artifacts, "apk")?.id)
        assertEquals(3L, selectBuildArtifact(artifacts, "aab")?.id)
    }

    @Test
    fun selectBuildArtifactSkipsExpiredAndFallsBack() {
        val artifacts = parseRunArtifacts(
            artifactsJson(
                Triple(1L, "myapp-apk", true),
                Triple(2L, "bundle", false),
            )
        )
        // The only live artifact wins even without an extension match.
        assertEquals(2L, selectBuildArtifact(artifacts, "apk")?.id)
        assertNull(selectBuildArtifact(parseRunArtifacts(artifactsJson(Triple(1L, "x-apk", true))), "apk"))
        assertNull(selectBuildArtifact(emptyList(), "apk"))
    }
}
