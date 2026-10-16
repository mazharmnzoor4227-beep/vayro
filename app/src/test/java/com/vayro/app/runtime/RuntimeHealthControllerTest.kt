package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeHealthControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `detects healthy storage when free space exceeds warning threshold`() {
        val controller = RuntimeHealthController(
            baseFilesDir = tempFolder.root,
            storageProvider = { 2L * 1024L * 1024L * 1024L to 10L * 1024L * 1024L * 1024L }, // 2 GB free of 10 GB
        )

        val result = controller.checkStorageHealth()
        assertTrue(result is StorageHealthResult.Healthy)
    }

    @Test
    fun `detects warning storage when free space is below warning but above safe minimum`() {
        val controller = RuntimeHealthController(
            baseFilesDir = tempFolder.root,
            storageProvider = { 800L * 1024L * 1024L to 10L * 1024L * 1024L * 1024L }, // 800 MB free
        )

        val result = controller.checkStorageHealth()
        assertTrue(result is StorageHealthResult.Warning)
        val warning = result as StorageHealthResult.Warning
        assertTrue(warning.message.contains("800MB"))
    }

    @Test
    fun `detects critical storage when free space falls below minimum safe threshold`() {
        val controller = RuntimeHealthController(
            baseFilesDir = tempFolder.root,
            storageProvider = { 250L * 1024L * 1024L to 10L * 1024L * 1024L * 1024L }, // 250 MB free
        )

        val result = controller.checkStorageHealth()
        assertTrue(result is StorageHealthResult.Critical)
        val critical = result as StorageHealthResult.Critical
        assertTrue(critical.message.contains("Critically low storage"))
    }

    @Test
    fun `enforces custom build threshold when checking build storage health`() {
        val controller = RuntimeHealthController(
            baseFilesDir = tempFolder.root,
            storageProvider = { 800L * 1024L * 1024L to 10L * 1024L * 1024L * 1024L }, // 800 MB free
        )

        // 1 GB required for build
        val result = controller.checkStorageHealth(RuntimeHealthController.MIN_BUILD_STORAGE_BYTES)
        assertTrue(result is StorageHealthResult.Critical)
    }

    @Test
    fun `reports not installed when rootfs directory is absent`() {
        val controller = RuntimeHealthController(baseFilesDir = tempFolder.root)
        val result = controller.verifyRootfsIntegrity()
        assertEquals(RootfsIntegrityResult.NotInstalled, result)
    }

    @Test
    fun `detects intact rootfs when critical binaries are present and non-empty`() {
        val root = tempFolder.newFolder("files_intact")
        val ubuntu = File(root, "runtime/ubuntu")
        File(ubuntu, "bin").mkdirs()
        File(ubuntu, "etc").mkdirs()
        File(ubuntu, "usr/bin").mkdirs()

        File(ubuntu, "bin/sh").writeText("#!/bin/sh")
        File(ubuntu, "bin/bash").writeText("#!/bin/bash")
        File(ubuntu, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash")
        File(ubuntu, "usr/bin/env").writeText("#!/bin/sh")

        val controller = RuntimeHealthController(baseFilesDir = root)
        val result = controller.verifyRootfsIntegrity()
        assertEquals(RootfsIntegrityResult.Intact, result)
    }

    @Test
    fun `detects corrupted rootfs when critical binaries are missing or empty`() {
        val root = tempFolder.newFolder("files_corrupt")
        val ubuntu = File(root, "runtime/ubuntu")
        File(ubuntu, "bin").mkdirs()
        File(ubuntu, "etc").mkdirs()

        File(ubuntu, "bin/sh").writeText("#!/bin/sh")
        File(ubuntu, "bin/bash").writeText("") // 0 bytes (corrupted)
        // etc/passwd missing
        // usr/bin/env missing

        val controller = RuntimeHealthController(baseFilesDir = root)
        val result = controller.verifyRootfsIntegrity()
        assertTrue(result is RootfsIntegrityResult.Corrupted)
        val corrupted = result as RootfsIntegrityResult.Corrupted
        assertTrue(corrupted.missingCriticalFiles.contains("bin/bash"))
        assertTrue(corrupted.missingCriticalFiles.contains("etc/passwd"))
    }

    @Test
    fun `resetCorruptedRootfs removes the damaged runtime folder cleanly`() {
        val root = tempFolder.newFolder("files_reset")
        val ubuntu = File(root, "runtime/ubuntu").apply { mkdirs() }
        File(ubuntu, "broken.txt").writeText("damaged")

        val controller = RuntimeHealthController(baseFilesDir = root)
        val success = controller.resetCorruptedRootfs()

        assertTrue(success)
        assertFalse(ubuntu.exists())
    }
}
