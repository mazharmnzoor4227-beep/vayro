package com.vayro.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceCheckpointManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testCreateCheckpointAndList() {
        val filesDir = tempFolder.newFolder("filesDir")
        val workspaceDir = tempFolder.newFolder("workspace")

        val file1 = File(workspaceDir, "src/Main.kt").apply {
            parentFile?.mkdirs()
            writeText("fun main() { println(\"Hello\") }")
        }
        val file2 = File(workspaceDir, "README.md").apply {
            writeText("# My Project")
        }

        val manager = WorkspaceCheckpointManager(filesDir)
        val checkpoint = manager.createCheckpoint("proj-1", workspaceDir, "Initial setup")

        assertNotNull(checkpoint)
        assertEquals("Initial setup", checkpoint?.label)
        assertEquals(2, checkpoint?.fileCount)

        val list = manager.listCheckpoints("proj-1")
        assertEquals(1, list.size)
        assertEquals("Initial setup", list[0].label)
    }

    @Test
    fun testRollbackToCheckpoint() {
        val filesDir = tempFolder.newFolder("filesDir2")
        val workspaceDir = tempFolder.newFolder("workspace2")

        val targetFile = File(workspaceDir, "config.json").apply {
            writeText("""{"version": "1.0"}""")
        }

        val manager = WorkspaceCheckpointManager(filesDir)
        val ckpt = manager.createCheckpoint("proj-2", workspaceDir, "v1.0 baseline")
        assertNotNull(ckpt)

        // Modify the file
        targetFile.writeText("""{"version": "2.0-corrupted"}""")
        assertEquals("""{"version": "2.0-corrupted"}""", targetFile.readText())

        // Rollback
        val success = manager.rollbackToCheckpoint("proj-2", ckpt!!.id, workspaceDir)
        assertTrue(success)

        // Verify restoration
        assertEquals("""{"version": "1.0"}""", targetFile.readText())
    }

    @Test
    fun testWriteSafely() {
        val filesDir = tempFolder.newFolder("filesDir3")
        val target = File(filesDir, "atomic_file.txt")

        val manager = WorkspaceCheckpointManager(filesDir)
        val ok = manager.writeSafely(target, "Safe atomic content")

        assertTrue(ok)
        assertTrue(target.exists())
        assertEquals("Safe atomic content", target.readText())
    }

    @Test
    fun testBackupAndRestoreWorkspaces() {
        val filesDir = tempFolder.newFolder("filesDir4")
        val externalDir = tempFolder.newFolder("externalBackups")
        val workspacesDir = File(filesDir, "workspaces/proj-abc").apply { mkdirs() }
        File(workspacesDir, "index.js").writeText("console.log('hello world');")

        val manager = WorkspaceCheckpointManager(filesDir)
        val backupCount = manager.backupAllWorkspaces(externalDir)
        assertEquals(1, backupCount)
        assertTrue(File(externalDir, "workspaces_mirror/proj-abc/index.js").exists())

        // Wipe internal workspaces
        workspacesDir.deleteRecursively()
        assertEquals(false, File(filesDir, "workspaces/proj-abc/index.js").exists())

        // Restore
        val restoreCount = manager.restoreAllWorkspaces(externalDir)
        assertEquals(1, restoreCount)
        assertTrue(File(filesDir, "workspaces/proj-abc/index.js").exists())
        assertEquals("console.log('hello world');", File(filesDir, "workspaces/proj-abc/index.js").readText())
    }
}

