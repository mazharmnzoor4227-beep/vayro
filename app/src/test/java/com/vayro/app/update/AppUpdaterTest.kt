package com.vayro.app.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun testResolveArtifactOnlineVariant() {
        val json = JSONObject("""
            {
              "versionCode": 28,
              "versionName": "2.2.0",
              "notes": "Test release",
              "artifacts": {
                "online": {
                  "url": "https://github.com/example/releases/download/v2.2.0/app-online-release.apk",
                  "sha256": "4b68019e1fa3b07ae7e189873d6ebefae3e0ec05f013d2f928e469e38d77bfbb",
                  "sizeBytes": 87421923
                },
                "offline": {
                  "url": "https://github.com/example/releases/download/v2.2.0/app-offline-release.apk",
                  "sha256": "8064a7c06ebfa507d8cf1f486d3029272898c69ee33ea8536f6d50ff0630b42b",
                  "sizeBytes": 887721644
                }
              }
            }
        """.trimIndent())

        val onlineArtifact = AppUpdater.resolveArtifactForVariant(json, "online")
        assertNotNull("Online artifact must be resolved", onlineArtifact)
        assertEquals(
            "https://github.com/example/releases/download/v2.2.0/app-online-release.apk",
            onlineArtifact!!.getString("url")
        )
        assertEquals(87421923L, onlineArtifact.getLong("sizeBytes"))

        val offlineArtifact = AppUpdater.resolveArtifactForVariant(json, "offline")
        assertNotNull("Offline artifact must be resolved", offlineArtifact)
        assertEquals(
            "https://github.com/example/releases/download/v2.2.0/app-offline-release.apk",
            offlineArtifact!!.getString("url")
        )
        assertEquals(887721644L, offlineArtifact.getLong("sizeBytes"))
    }

    @Test
    fun testResolveArtifactFallbackToRoot() {
        val rootJson = JSONObject("""
            {
              "versionCode": 25,
              "versionName": "2.0.0",
              "url": "https://example.com/app-release.apk",
              "sha256": "abcdef"
            }
        """.trimIndent())

        val resolved = AppUpdater.resolveArtifactForVariant(rootJson, "online")
        assertNotNull(resolved)
        assertEquals("https://example.com/app-release.apk", resolved!!.getString("url"))
    }

    // QA H20: there is intentionally NO test for parseVersionFromTag anymore.
    // The tag -> versionCode mapping (v3.0.1 -> 30001) is deleted: real APK
    // versionCodes are not semver-derived (34), so the mapping could never
    // match and verifyApk always threw, while the fallback skipped SHA-256
    // (sha256 = ""). A release without mobile-harness-update.json now
    // offers no update instead of an unverifiable one.

    @Test
    fun testIsGitHubDomain() {
        assertTrue(AppUpdater.isGitHubDomain("https://api.github.com/repos/sunmughan/Conzyx/releases/latest"))
        assertTrue(AppUpdater.isGitHubDomain("https://github.com/sunmughan/Conzyx/releases/latest/download/manifest.json"))
        assertTrue(AppUpdater.isGitHubDomain("https://codeload.github.com/sunmughan/Conzyx/tar.gz/v3.2.10"))

        // External mirrors or S3 redirect URLs should return false so auth headers are NOT forwarded
        assertFalse(AppUpdater.isGitHubDomain("https://updates.example.workers.dev/check"))
        assertFalse(AppUpdater.isGitHubDomain("https://s3.amazonaws.com/github-production-assets/apk.bin"))
        assertFalse(AppUpdater.isGitHubDomain("https://cloudflare-r2.storage.example/app.apk"))
        assertFalse(AppUpdater.isGitHubDomain("https://raw.githubusercontent.com/techjarves/Mobile-Harness/main/manifest.json"))
        assertFalse(AppUpdater.isGitHubDomain("https://objects.githubusercontent.com/github-production-release-asset-2e65be/"))
    }

    @Test
    fun testIsGitHubDomainRejectsLookalikes() {
        assertFalse(AppUpdater.isGitHubDomain("https://attackergithub.com/releases/latest"))
        assertFalse(AppUpdater.isGitHubDomain("https://evil-github.com/manifest.json"))
        assertFalse(AppUpdater.isGitHubDomain("https://github.com.evil.com/manifest.json"))
        assertFalse(AppUpdater.isGitHubDomain("https://api.github.com.evil.com/repos/x"))
        assertFalse(AppUpdater.isGitHubDomain("https://notgithub.com"))
    }

    @Test
    fun testIsGitHubDomainHandlesCaseAndScheme() {
        assertTrue(AppUpdater.isGitHubDomain("https://GitHub.COM/sunmughan/Conzyx"))
        assertTrue(AppUpdater.isGitHubDomain("https://API.GitHub.COM/repos/sunmughan/Conzyx/releases/latest"))
        assertFalse(AppUpdater.isGitHubDomain("http://github.com/sunmughan/Conzyx"))
        assertFalse(AppUpdater.isGitHubDomain("http://api.github.com/repos/sunmughan/Conzyx/releases/latest"))
        assertFalse(AppUpdater.isGitHubDomain("ftp://github.com/manifest.json"))
    }

    @Test
    fun testIsGitHubDomainRejectsMalformedUrls() {
        assertFalse(AppUpdater.isGitHubDomain(""))
        assertFalse(AppUpdater.isGitHubDomain("not a url"))
        assertFalse(AppUpdater.isGitHubDomain("https://"))
        assertFalse(AppUpdater.isGitHubDomain("github.com/no-scheme"))
        assertFalse(AppUpdater.isGitHubDomain("https://github.com:badport/x"))
    }

    @Test
    fun testArtifactFlavorMismatchReturnsNull() {
        val singleFlavorJson = JSONObject("""
            {
              "versionCode": 30,
              "artifacts": {
                "online": {
                  "url": "https://example.com/online.apk"
                }
              }
            }
        """.trimIndent())

        assertNotNull(AppUpdater.resolveArtifactForVariant(singleFlavorJson, "online"))
        assertNull(AppUpdater.resolveArtifactForVariant(singleFlavorJson, "offline"))
    }

    @Test
    fun testSelfUpdateDisallowedOnPlayBuilds() {
        // QA C5: a Google Play build must never offer or fetch a GitHub APK.
        // check() and download() both consult this single decision point.
        assertFalse(AppUpdater.isSelfUpdateAllowed(isPlayBuild = true))
    }

    @Test
    fun testSelfUpdateAllowedOnNonPlayBuilds() {
        assertTrue(AppUpdater.isSelfUpdateAllowed(isPlayBuild = false))
    }
}
