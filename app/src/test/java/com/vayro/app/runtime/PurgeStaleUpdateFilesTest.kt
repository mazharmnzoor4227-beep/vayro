package com.vayro.app.runtime

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * QA Medium: EnvironmentUpdateManager.cleanup() existed but was never
 * called, so failed updates accumulated package zips and staging dirs
 * forever.
 */
class PurgeStaleUpdateFilesTest {

    private lateinit var root: File
    private lateinit var downloads: File
    private lateinit var components: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "env_cleanup_test_" + System.nanoTime())
        downloads = File(root, "downloads").apply { mkdirs() }
        components = File(root, "components").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun staleZipsPartialsAndStagingDirsArePurged() {
        val staleZip = File(downloads, "core-1.0.zip").apply { writeText("stale") }
        val stalePart = File(downloads, "core-1.0.zip.part").apply { writeText("stale") }
        val staging = File(components, "core/.1.0.installing").apply { mkdirs() }
        File(staging, "junk").writeText("junk")
        val restore = File(components, "core/.1.0.restore").apply { mkdirs() }
        val liveVersion = File(components, "core/1.0").apply { mkdirs() }
        File(liveVersion, "keep").writeText("keep")
        val unrelated = File(downloads, "notes.txt").apply { writeText("keep") }

        purgeStaleUpdateFiles(downloads, components)

        assertFalse(staleZip.exists())
        assertFalse(stalePart.exists())
        assertFalse(staging.exists())
        assertFalse(restore.exists())
        assertTrue(liveVersion.isDirectory)
        assertTrue(File(liveVersion, "keep").isFile)
        assertTrue(unrelated.isFile)
    }

    @Test
    fun missingDirsAreFine() {
        purgeStaleUpdateFiles(File(root, "no-downloads"), File(root, "no-components"))
    }
}
