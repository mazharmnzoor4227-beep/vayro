package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Covers the v3.2.55 startup-repair fix (upstream v3.5.3): Retry must only re-run
 * initialization for a fully launchable core, and a stripped exec bit
 * must be restorable by the repair step.
 */
class StartupRepairTest {

    @Test
    fun retryRoute_initializesOnlyWhenInstalledAndBinariesExecutable() {
        assertEquals(
            StartupRetryRoute.INITIALIZE_EXISTING,
            startupRetryRoute(installed = true, nodeExecutable = true, gitExecutable = true),
        )
        // The pre-3.5.3 trap: installed yet non-executable binaries used to
        // take the initialize path and fail forever without re-entering setup.
        assertEquals(
            StartupRetryRoute.REINSTALL,
            startupRetryRoute(installed = true, nodeExecutable = false, gitExecutable = true),
        )
        assertEquals(
            StartupRetryRoute.REINSTALL,
            startupRetryRoute(installed = true, nodeExecutable = true, gitExecutable = false),
        )
        assertEquals(
            StartupRetryRoute.REINSTALL,
            startupRetryRoute(installed = true, nodeExecutable = false, gitExecutable = false),
        )
        assertEquals(
            StartupRetryRoute.REINSTALL,
            startupRetryRoute(installed = false, nodeExecutable = true, gitExecutable = true),
        )
        assertEquals(
            StartupRetryRoute.REINSTALL,
            startupRetryRoute(installed = false, nodeExecutable = false, gitExecutable = false),
        )
    }

    @Test
    fun repairCoreExecBits_restoresStrippedExecBit() {
        val dir = Files.createTempDirectory("kodex-repair-test").toFile()
        try {
            val node = File(dir, "node").apply {
                writeText("fake binary")
                setExecutable(false)
            }
            assertFalse(node.canExecute())
            assertTrue(repairCoreExecBits(listOf(node)))
            assertTrue(node.canExecute())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun repairCoreExecBits_reportsMissingFileAsNotRepaired() {
        val dir = Files.createTempDirectory("kodex-repair-test").toFile()
        try {
            assertFalse(repairCoreExecBits(listOf(File(dir, "absent"))))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun repairCoreExecBits_leavesAlreadyExecutableFilesAlone() {
        val dir = Files.createTempDirectory("kodex-repair-test").toFile()
        try {
            val git = File(dir, "git").apply {
                writeText("fake binary")
                setExecutable(true)
            }
            assertTrue(repairCoreExecBits(listOf(git)))
            assertTrue(git.canExecute())
        } finally {
            dir.deleteRecursively()
        }
    }
}