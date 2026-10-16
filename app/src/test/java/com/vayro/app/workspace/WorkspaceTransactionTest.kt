package com.vayro.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Milestone 1 - workspace transaction / atomicity tests against the real
 * [WorkspaceCheckpointManager] and [SafeFileOps]. Both are plain-JVM safe
 * (constructor takes only [File]s; no Android framework calls on these paths
 * beyond stubbed statics covered by `unitTests.isReturnDefaultValues`).
 */
class WorkspaceTransactionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ------------------------------------------------------------------
    // (a) atomicWriteFile either fully writes or leaves the original intact.
    // ------------------------------------------------------------------

    @Test
    fun `atomic write fully writes new content`() {
        val filesDir = tempFolder.newFolder("txA")
        val manager = WorkspaceCheckpointManager(filesDir)
        val target = File(filesDir, "config.json")

        assertTrue(manager.atomicWriteFile(target, """{"v":1}"""))
        assertEquals("""{"v":1}""", target.readText())

        // Overwrite path: the file must contain exactly the new content,
        // never a torn mix of old and new bytes.
        assertTrue(manager.atomicWriteFile(target, """{"v":2,"name":"updated"}"""))
        assertEquals("""{"v":2,"name":"updated"}""", target.readText())
    }

    @Test
    fun `failed atomic write leaves original byte-for-byte intact`() {
        val filesDir = tempFolder.newFolder("txA2")
        val manager = WorkspaceCheckpointManager(filesDir)

        // A target nested under a regular file cannot be created: the staging
        // file creation fails, so the call must report false honestly.
        val blocker = File(filesDir, "blocker").apply { writeText("original") }
        val impossible = File(blocker, "child.txt")

        assertFalse(manager.atomicWriteFile(impossible, "new content"))
        assertEquals("original content must survive the failed write", "original", blocker.readText())
        assertFalse("failed write must not materialize the target", impossible.exists())
    }

    @Test
    fun `atomic write leaves no staging debris`() {
        val filesDir = tempFolder.newFolder("txA3")
        val manager = WorkspaceCheckpointManager(filesDir)
        val target = File(filesDir, "sub/data.txt")

        assertTrue(manager.atomicWriteFile(target, "hello"))
        val leftovers = File(filesDir, "sub").listFiles()!!.filter { ".tmp_" in it.name }
        assertTrue("staging files must never be left behind: $leftovers", leftovers.isEmpty())
    }

    // ------------------------------------------------------------------
    // (b) rollbackToCheckpoint restores exact prior content.
    // ------------------------------------------------------------------

    @Test
    fun `rollback restores exact prior content`() {
        val filesDir = tempFolder.newFolder("txB")
        val workspaceDir = tempFolder.newFolder("wsB")
        val manager = WorkspaceCheckpointManager(filesDir)

        val kept = File(workspaceDir, "src/Kept.kt").apply {
            parentFile!!.mkdirs()
            writeText("v1")
        }
        File(workspaceDir, "doomed.txt").writeText("delete me later")

        val checkpoint = manager.createCheckpoint("proj-b", workspaceDir, "v1")
        assertNotNull(checkpoint)

        // Mutate after the snapshot: edit a file, delete a file, add a file.
        kept.writeText("v2-corrupted")
        assertTrue(File(workspaceDir, "doomed.txt").delete())
        File(workspaceDir, "added-after.txt").writeText("should vanish")

        assertTrue(manager.rollbackToCheckpoint("proj-b", checkpoint!!.id, workspaceDir))

        assertEquals("edited file must be restored exactly", "v1", kept.readText())
        assertEquals(
            "deleted file must be restored exactly",
            "delete me later",
            File(workspaceDir, "doomed.txt").readText(),
        )
        assertFalse(
            "files added after the checkpoint must be removed",
            File(workspaceDir, "added-after.txt").exists(),
        )
    }

    @Test
    fun `rollback to a missing checkpoint fails honestly`() {
        val filesDir = tempFolder.newFolder("txB2")
        val workspaceDir = tempFolder.newFolder("wsB2")
        File(workspaceDir, "a.txt").writeText("untouched")
        val manager = WorkspaceCheckpointManager(filesDir)

        assertFalse(manager.rollbackToCheckpoint("proj-b2", "ckpt_nope", workspaceDir))
        assertEquals("workspace must be untouched by the failed rollback", "untouched", File(workspaceDir, "a.txt").readText())
    }

    // ------------------------------------------------------------------
    // (c) verifyCheckpointIntegrity catches a tampered checkpoint.
    // ------------------------------------------------------------------

    @Test
    fun `verifyCheckpointIntegrity catches tampering`() {
        val filesDir = tempFolder.newFolder("txC")
        val workspaceDir = tempFolder.newFolder("wsC")
        File(workspaceDir, "a.txt").writeText("data")
        val manager = WorkspaceCheckpointManager(filesDir)

        fun snapshotDir(id: String) = File(filesDir, "checkpoints/proj-c/snapshots/$id")

        // Control: an untouched checkpoint verifies.
        val clean = manager.createCheckpoint("proj-c", workspaceDir, "clean")!!
        assertTrue(manager.verifyCheckpointIntegrity("proj-c", clean.id))

        // Tamper 1: meta.json rewritten with a forged id.
        val forged = manager.createCheckpoint("proj-c", workspaceDir, "forged")!!
        File(snapshotDir(forged.id), "meta.json")
            .writeText("""{"id":"ckpt_forged","projectId":"proj-c"}""")
        assertFalse("forged checkpoint id must fail verification", manager.verifyCheckpointIntegrity("proj-c", forged.id))

        // Tamper 2: meta.json is not valid JSON anymore.
        val garbled = manager.createCheckpoint("proj-c", workspaceDir, "garbled")!!
        File(snapshotDir(garbled.id), "meta.json").writeText("{not json")
        assertFalse("garbled metadata must fail verification", manager.verifyCheckpointIntegrity("proj-c", garbled.id))

        // Tamper 3: meta.json deleted outright.
        val noMeta = manager.createCheckpoint("proj-c", workspaceDir, "no-meta")!!
        assertTrue(File(snapshotDir(noMeta.id), "meta.json").delete())
        assertFalse("missing metadata must fail verification", manager.verifyCheckpointIntegrity("proj-c", noMeta.id))

        // Tamper 4: the backup file tree deleted outright.
        val noBackup = manager.createCheckpoint("proj-c", workspaceDir, "no-backup")!!
        assertTrue(File(snapshotDir(noBackup.id), "files").deleteRecursively())
        assertFalse("missing backup tree must fail verification", manager.verifyCheckpointIntegrity("proj-c", noBackup.id))
    }

    // ------------------------------------------------------------------
    // (d) Two sequential checkpoints + rollback to the older one.
    // ------------------------------------------------------------------

    @Test
    fun `two sequential checkpoints and rollback to the older one`() {
        val filesDir = tempFolder.newFolder("txD")
        val workspaceDir = tempFolder.newFolder("wsD")
        val manager = WorkspaceCheckpointManager(filesDir)
        val app = File(workspaceDir, "App.kt").apply { writeText("v1") }

        val older = manager.createCheckpoint("proj-d", workspaceDir, "v1")!!
        app.writeText("v2")
        val newer = manager.createCheckpoint("proj-d", workspaceDir, "v2")!!
        app.writeText("v3-uncommitted")

        assertEquals(2, manager.listCheckpoints("proj-d").size)

        assertTrue(manager.rollbackToCheckpoint("proj-d", older.id, workspaceDir))
        assertEquals("v1", app.readText())

        // The newer checkpoint is still independently restorable afterwards.
        assertTrue(manager.rollbackToCheckpoint("proj-d", newer.id, workspaceDir))
        assertEquals("v2", app.readText())
    }

    // ------------------------------------------------------------------
    // (e) Checkpoint of an empty / missing workspace fails honestly.
    // ------------------------------------------------------------------

    @Test
    fun `checkpoint of an empty workspace is an honest empty snapshot`() {
        val filesDir = tempFolder.newFolder("txE")
        val workspaceDir = tempFolder.newFolder("wsEmpty") // exists, contains no files
        val manager = WorkspaceCheckpointManager(filesDir)

        // Current contract: an empty workspace is a valid empty snapshot -
        // it must say fileCount=0 and back up zero files, never invent any.
        val checkpoint = manager.createCheckpoint("proj-e", workspaceDir, "empty")
        assertNotNull("an empty workspace is a valid empty snapshot", checkpoint)
        assertEquals(0, checkpoint!!.fileCount)
        assertTrue(manager.verifyCheckpointIntegrity("proj-e", checkpoint.id))

        val backedUpFiles = File(filesDir, "checkpoints/proj-e/snapshots/${checkpoint.id}/files")
            .walkTopDown().filter { it.isFile }.toList()
        assertTrue("empty snapshot must not claim any files, found: $backedUpFiles", backedUpFiles.isEmpty())
    }

    @Test
    fun `checkpoint of a missing workspace never claims phantom files`() {
        val filesDir = tempFolder.newFolder("txE2")
        val missing = File(tempFolder.root, "does-not-exist")
        assertFalse(missing.exists())
        val manager = WorkspaceCheckpointManager(filesDir)

        // Current contract: a missing workspace either fails cleanly (null) or
        // is recorded as an empty snapshot. Either way it must never claim
        // files that were never backed up.
        val checkpoint = manager.createCheckpoint("proj-e2", missing, "missing")
        if (checkpoint != null) {
            assertEquals(0, checkpoint.fileCount)
            assertTrue(manager.verifyCheckpointIntegrity("proj-e2", checkpoint.id))
        } else {
            assertNull(checkpoint)
        }
        assertTrue(
            "no checkpoint may claim files for a missing workspace",
            manager.listCheckpoints("proj-e2").all { it.fileCount == 0 },
        )
        val snapshotsDir = File(filesDir, "checkpoints/proj-e2/snapshots")
        val phantomFiles = if (snapshotsDir.isDirectory) {
            snapshotsDir.walkTopDown()
                .filter { it.isFile && it.parentFile?.name == "files" }
                .toList()
        } else {
            emptyList()
        }
        assertTrue("no phantom backup files may be recorded, found: $phantomFiles", phantomFiles.isEmpty())
    }
}
