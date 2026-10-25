package com.vayro.app.factory.build

import com.vayro.app.factory.model.VersionBumpType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * QA C3: the keystore password must never fall back to a hardcoded default,
 * and keytool's minimum length is enforced before any process is spawned.
 */
class SigningAndVersionManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `short store password is rejected before keytool runs`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            SigningAndVersionManager.createKeystore(
                projectDir = temp.newFolder(),
                keyAlias = "releaseKey",
                storePassword = "abc",
                keyPassword = "longenough",
            )
        }
        assertTrue(error.message!!.contains("at least 6 characters"))
    }

    @Test
    fun `short key password is rejected before keytool runs`() {
        assertThrows(IllegalArgumentException::class.java) {
            SigningAndVersionManager.createKeystore(
                projectDir = temp.newFolder(),
                keyAlias = "releaseKey",
                storePassword = "longenough",
                keyPassword = "abc",
            )
        }
    }

    @Test
    fun `blank alias is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SigningAndVersionManager.createKeystore(
                projectDir = temp.newFolder(),
                keyAlias = "  ",
                storePassword = "longenough",
                keyPassword = "longenough",
            )
        }
    }

    @Test
    fun `blank password is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SigningAndVersionManager.createKeystore(
                projectDir = temp.newFolder(),
                keyAlias = "releaseKey",
                storePassword = "",
                keyPassword = "",
            )
        }
    }

    @Test
    fun `valid password generates a real keystore with keytool`() {
        val projectDir: File = temp.newFolder()
        val config = SigningAndVersionManager.createKeystore(
            projectDir = projectDir,
            keyAlias = "releaseKey",
            storePassword = "correct-horse-9",
            keyPassword = "correct-horse-9",
        )
        assertTrue(config.keystoreFile.isFile)
        assertEquals("releaseKey", config.keyAlias)
        assertEquals(10000, config.validityDays)
    }

    @Test
    fun `bumpVersion creates missing project folder instead of crashing with ENOENT`() {
        val projectDir = File(temp.root, "deleted-or-never-created-project")
        assertTrue(!projectDir.exists())
        val (newCode, newName) = SigningAndVersionManager.bumpVersion(projectDir, VersionBumpType.PATCH)
        assertEquals(2, newCode)
        assertEquals("1.0.1", newName)
        assertTrue(File(projectDir, "app-version.properties").isFile)
    }
}
