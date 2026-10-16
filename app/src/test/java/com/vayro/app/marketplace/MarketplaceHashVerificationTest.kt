package com.vayro.app.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA H17: marketplace installs must verify the download hash when the
 * listing provides one. The hash primitives are pure, so they are tested
 * directly (the install path itself needs Android Context + network).
 */
class MarketplaceHashVerificationTest {

    @Test
    fun `sha256Hex matches the known vector`() {
        // SHA-256("abc"), a standard test vector.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun `verifyPackageHash accepts a matching hash`() {
        val bytes = "package-bytes".toByteArray(Charsets.UTF_8)
        assertTrue(verifyPackageHash(sha256Hex(bytes), bytes))
    }

    @Test
    fun `verifyPackageHash is case-insensitive`() {
        val bytes = "package-bytes".toByteArray(Charsets.UTF_8)
        assertTrue(verifyPackageHash(sha256Hex(bytes).uppercase(), bytes))
    }

    @Test
    fun `verifyPackageHash rejects a mismatch`() {
        val bytes = "package-bytes".toByteArray(Charsets.UTF_8)
        assertFalse(verifyPackageHash("0".repeat(64), bytes))
        assertFalse(verifyPackageHash(sha256Hex("other-bytes".toByteArray(Charsets.UTF_8)), bytes))
    }

    @Test
    fun `verifyPackageHash passes when the listing provides no hash`() {
        val bytes = "package-bytes".toByteArray(Charsets.UTF_8)
        assertTrue(verifyPackageHash("", bytes))
        assertTrue(verifyPackageHash("   ", bytes))
    }

    @Test
    fun `classifyInstallSource requires https for downloads`() {
        assertEquals(InstallSourceKind.HTTPS_DOWNLOAD, classifyInstallSource("https://example.com/pkg.zip"))
        assertEquals(InstallSourceKind.INSECURE_HTTP, classifyInstallSource("http://example.com/pkg.zip"))
        assertEquals(InstallSourceKind.LOCAL_REFERENCE, classifyInstallSource("content://media/1"))
        assertEquals(InstallSourceKind.LOCAL_REFERENCE, classifyInstallSource("/sdcard/pkg.zip"))
        assertEquals(InstallSourceKind.CATALOG_ONLY, classifyInstallSource(""))
        assertEquals(InstallSourceKind.CATALOG_ONLY, classifyInstallSource("   "))
    }
}
