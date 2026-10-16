package com.vayro.app.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * QA Medium: first-run runtime install deleted the live rootfs before the
 * rename — a kill in between destroyed the whole Linux runtime with no
 * recovery path.
 */
class ActivateStagingDirTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "staging_swap_test_" + System.nanoTime())
        root.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun successReplacesTargetAndDropsBackup() {
        val target = File(root, "rootfs").apply { mkdirs() }
        File(target, "old.txt").writeText("old")
        val staging = File(root, "ubuntu.installing").apply { mkdirs() }
        File(staging, "new.txt").writeText("new")
        val backup = File(root, "ubuntu.previous")

        activateStagingDir(staging, target, backup)

        assertTrue(File(target, "new.txt").isFile)
        assertFalse(File(target, "old.txt").exists())
        assertFalse("staging consumed", staging.exists())
        assertFalse("backup dropped after success", backup.exists())
    }

    @Test
    fun failedActivationRestoresTheOldTarget() {
        val target = File(root, "rootfs").apply { mkdirs() }
        File(target, "old.txt").writeText("old")
        val staging = File(root, "missing-staging") // renameTo fails
        val backup = File(root, "ubuntu.previous")

        try {
            activateStagingDir(staging, target, backup)
            fail("expected activation to fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("Could not activate"))
        }

        assertEquals("old", File(target, "old.txt").readText())
        assertFalse("restored backup leaves nothing behind", backup.exists())
    }

    @Test
    fun freshInstallWithNoTargetWorks() {
        val target = File(root, "rootfs")
        val staging = File(root, "ubuntu.installing").apply { mkdirs() }
        File(staging, "new.txt").writeText("new")

        activateStagingDir(staging, target, File(root, "ubuntu.previous"))

        assertTrue(File(target, "new.txt").isFile)
    }
}
