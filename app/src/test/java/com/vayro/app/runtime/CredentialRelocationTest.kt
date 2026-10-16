package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CredentialRelocationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `moves token files out of the legacy guest dir and removes the legacy tree`() {
        val legacy = tempFolder.newFolder("legacy")
        File(legacy, "hosts.yml").writeText("github.com:\n  oauth_token: secret")
        val nested = File(legacy, "nested").apply { mkdirs() }
        File(nested, "token").writeText("nested-secret")
        val secure = File(tempFolder.root, "secure")

        relocateCredentialFiles(legacy, secure)

        assertFalse(legacy.exists())
        assertEquals("github.com:\n  oauth_token: secret", File(secure, "hosts.yml").readText())
        assertEquals("nested-secret", File(secure, "nested/token").readText())
    }

    @Test
    fun `missing legacy dir leaves the secure dir ready and moves nothing`() {
        val secure = File(tempFolder.root, "secure")

        relocateCredentialFiles(File(tempFolder.root, "does-not-exist"), secure)

        assertTrue(secure.isDirectory)
        assertEquals(0, secure.listFiles()?.size ?: 0)
    }

    @Test
    fun `keeps the newer secure copy when both locations hold the file`() {
        val legacy = tempFolder.newFolder("legacy")
        val legacyFile = File(legacy, "hosts.yml").apply { writeText("old") }
        val secure = tempFolder.newFolder("secure")
        val secureFile = File(secure, "hosts.yml").apply { writeText("new") }
        legacyFile.setLastModified(System.currentTimeMillis() - 60_000)
        secureFile.setLastModified(System.currentTimeMillis() + 60_000)

        relocateCredentialFiles(legacy, secure)

        assertEquals("new", secureFile.readText())
        assertFalse(legacy.exists())
    }

    @Test
    fun `newer legacy copy replaces a stale secure copy`() {
        val legacy = tempFolder.newFolder("legacy")
        val legacyFile = File(legacy, "hosts.yml").apply { writeText("fresh") }
        val secure = tempFolder.newFolder("secure")
        val secureFile = File(secure, "hosts.yml").apply { writeText("stale") }
        legacyFile.setLastModified(System.currentTimeMillis() + 60_000)
        secureFile.setLastModified(System.currentTimeMillis() - 60_000)

        relocateCredentialFiles(legacy, secure)

        assertEquals("fresh", secureFile.readText())
        assertFalse(legacy.exists())
    }
}
