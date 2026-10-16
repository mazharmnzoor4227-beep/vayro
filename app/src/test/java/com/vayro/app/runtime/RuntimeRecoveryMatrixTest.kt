package com.vayro.app.runtime

import com.vayro.app.workspace.SafeFileOps
import com.vayro.app.workspace.WorkspaceCheckpointManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Milestone 1 - fault-injection / recovery matrix tests.
 *
 * JVM TESTABILITY NOTE: [RuntimeInstaller] takes an android.content.Context in its
 * constructor and performs network I/O plus process execution, so it cannot be
 * instantiated on a plain JVM unit test (no Robolectric in this module). The
 * install-pipeline tests below therefore exercise:
 *  1. the REAL pure-Kotlin install stage vocabulary ([RuntimeInstallEvent] /
 *     [RuntimeInstallProgress]), and
 *  2. the honest-failure contract of the install pipeline
 *     (download -> extract -> verify -> complete) through an injectable fake
 *     defined in this file. Constructor-injected stage lambdas let each test
 *     fault exactly one stage; the fake enforces the same contract the real
 *     installer must uphold: a failed stage aborts the run, no COMPLETED
 *     progress is emitted, and the completion marker is never written.
 * [RootfsIntegrityVerifier] is a pure object and is tested against its real API.
 * Checkpoint/atomic-write paths use the real [WorkspaceCheckpointManager] and
 * [SafeFileOps] (both are plain-JVM safe; existing tests prove it).
 */

// ---------------------------------------------------------------------------
// Injectable fake install pipeline (mirrors the real stage sequence).
// ---------------------------------------------------------------------------

/** Mirrors the real install stage sequence: download -> extract -> verify -> complete. */
private enum class InstallStage { DOWNLOAD, EXTRACT, VERIFY, COMPLETE }

/**
 * Stand-in for the install pipeline. Every stage is a constructor-injected lambda
 * so a test can fault any single stage. The fake upholds the honest-failure
 * contract: progress for a stage is emitted only after that stage's action
 * succeeded, the terminal COMPLETED signal and the completion marker are
 * produced only after the final stage truly finished, and any stage failure
 * aborts with `false` instead of claiming partial success.
 */
private class FakeInstallPipeline(
    private val markerFile: File,
    private val download: () -> Unit = {},
    private val extract: () -> Unit = {},
    private val verify: () -> Unit = {},
    private val complete: () -> Unit = {},
) {
    val events = mutableListOf<RuntimeInstallProgress>()

    private fun stageFraction(stage: InstallStage): Float = when (stage) {
        InstallStage.DOWNLOAD -> 0.25f
        InstallStage.EXTRACT -> 0.60f
        InstallStage.VERIFY -> 0.90f
        InstallStage.COMPLETE -> 1.0f
    }

    /** Runs the pipeline. Returns true only if every stage succeeded. Never throws. */
    fun run(): Boolean {
        val stages = listOf(
            InstallStage.DOWNLOAD to download,
            InstallStage.EXTRACT to extract,
            InstallStage.VERIFY to verify,
            InstallStage.COMPLETE to complete,
        )
        for ((stage, action) in stages) {
            if (!runCatching { action() }.isSuccess) return false
            if (stage == InstallStage.COMPLETE) {
                // Marker first, COMPLETED announcement second: a crash between the
                // two can never leave "completed" announced without proof on disk.
                val finalized = runCatching {
                    markerFile.writeText("ready")
                    events.add(RuntimeInstallProgress("Setup complete", 1f, event = RuntimeInstallEvent.COMPLETED))
                }.isSuccess
                if (!finalized) return false
            } else {
                events.add(
                    RuntimeInstallProgress(
                        message = "Working: ${stage.name.lowercase()}",
                        fraction = stageFraction(stage),
                        event = RuntimeInstallEvent.STAGE,
                    )
                )
            }
        }
        return true
    }
}

/** Minimal staging-commit writer mirroring the pattern SafeFileOps callers use. */
private class StagedWriter(private val writeFn: (File, String) -> Boolean) {
    fun write(target: File, content: String): Boolean {
        val staging = File(target.parentFile, "${target.name}.staging_${System.nanoTime()}")
        if (!runCatching { writeFn(staging, content) }.getOrDefault(false)) {
            runCatching { staging.delete() }
            return false
        }
        return staging.renameTo(target)
    }
}

class RuntimeRecoveryMatrixTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun failingStage(): () -> Unit = { throw IOException("injected fault: simulated failure") }

    private fun newPipeline(
        markerName: String = "marker",
        download: () -> Unit = {},
        extract: () -> Unit = {},
        verify: () -> Unit = {},
        complete: () -> Unit = {},
    ): Pair<FakeInstallPipeline, File> {
        val marker = File(tempFolder.root, markerName)
        return FakeInstallPipeline(marker, download, extract, verify, complete) to marker
    }

    /** Shared honest-failure assertions: no success claim of any kind may survive a fault. */
    private fun assertHonestFailure(pipeline: FakeInstallPipeline, marker: File, ok: Boolean) {
        assertFalse("interrupted install must report failure, not success", ok)
        assertFalse("completion marker must never be written on failure", marker.exists())
        assertTrue(
            "no COMPLETED event may be emitted for an unfinished install, got: ${pipeline.events}",
            pipeline.events.none { it.event == RuntimeInstallEvent.COMPLETED },
        )
        assertTrue(
            "progress must never reach the terminal fraction on failure, got: ${pipeline.events}",
            pipeline.events.none { it.fraction >= 1f },
        )
    }

    // ------------------------------------------------------------------
    // (a) Install interrupted at each stage reports honest failure.
    // ------------------------------------------------------------------

    @Test
    fun `install event model covers every documented stage`() {
        // Pins the vocabulary the recovery matrix reasons about.
        assertEquals(
            setOf("STAGE", "COMMAND", "OUTPUT", "DOWNLOAD", "COMMAND_COMPLETED", "COMPLETED"),
            enumValues<RuntimeInstallEvent>().map { it.name }.toSet(),
        )
        // A fresh progress report defaults to a non-terminal STAGE event.
        assertEquals(RuntimeInstallEvent.STAGE, RuntimeInstallProgress("working", 0.1f).event)
    }

    @Test
    fun `interrupted download reports honest failure`() {
        val (pipeline, marker) = newPipeline("m1", download = failingStage())
        assertHonestFailure(pipeline, marker, pipeline.run())
        assertTrue("no stage may be announced before it finished, got: ${pipeline.events}", pipeline.events.isEmpty())
    }

    @Test
    fun `interrupted extract reports honest failure`() {
        val (pipeline, marker) = newPipeline("m2", extract = failingStage())
        assertHonestFailure(pipeline, marker, pipeline.run())
        assertEquals(1, pipeline.events.size) // download only; extract never announced
    }

    @Test
    fun `interrupted verify reports honest failure`() {
        val (pipeline, marker) = newPipeline("m3", verify = failingStage())
        assertHonestFailure(pipeline, marker, pipeline.run())
        assertEquals(2, pipeline.events.size) // download + extract only
    }

    @Test
    fun `interrupted complete never claims partial success`() {
        val (pipeline, marker) = newPipeline("m4", complete = failingStage())
        assertHonestFailure(pipeline, marker, pipeline.run())
        // All three real stages finished and were announced, but the terminal
        // COMPLETED signal and the marker must still be withheld.
        assertEquals(3, pipeline.events.size)
        assertTrue(pipeline.events.all { it.event == RuntimeInstallEvent.STAGE })
    }

    @Test
    fun `successful install emits completed exactly once`() {
        val (pipeline, marker) = newPipeline("m5")
        assertTrue(pipeline.run())
        assertTrue("completion marker must be written on success", marker.isFile)
        assertEquals("ready", marker.readText())
        assertEquals(1, pipeline.events.count { it.event == RuntimeInstallEvent.COMPLETED })
        val fractions = pipeline.events.map { it.fraction }
        assertEquals(fractions.sorted(), fractions) // progress never goes backwards
    }

    // ------------------------------------------------------------------
    // (b) Rootfs corruption is detected by the real integrity verifier.
    // ------------------------------------------------------------------

    private fun newHealthyRootfs(): File {
        val root = tempFolder.newFolder("rootfs")
        listOf("tmp", "dev", "proc", "etc", "bin", "usr/bin", "var/tmp").forEach {
            File(root, it).mkdirs()
        }
        File(root, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
        File(root, "etc/resolv.conf").writeText("nameserver 8.8.8.8\n")
        File(root, "usr/bin/bash").writeText("#!/bin/sh\n")
        return root
    }

    @Test
    fun `healthy rootfs verifies clean`() {
        val report = RootfsIntegrityVerifier.verify(newHealthyRootfs())
        assertTrue(report.isValid)
        assertTrue(report.missingPaths.isEmpty())
    }

    @Test
    fun `corrupted rootfs is detected`() {
        val root = newHealthyRootfs()
        // Simulate corruption: passwd wiped and every shell binary gone.
        assertTrue(File(root, "etc/passwd").delete())
        assertTrue(File(root, "usr/bin/bash").delete())

        val report = RootfsIntegrityVerifier.verify(root)
        assertFalse("corruption must be reported, not hidden", report.isValid)
        assertTrue(report.missingPaths.contains("etc/passwd"))
        assertTrue(report.missingPaths.contains("bin/sh"))
    }

    @Test
    fun `missing rootfs directory is reported honestly`() {
        val report = RootfsIntegrityVerifier.verify(File(tempFolder.root, "no-such-rootfs"))
        assertFalse(report.isValid)
        assertEquals(listOf("rootfs_root"), report.missingPaths)
    }

    @Test
    fun `verifyAndHeal repairs recoverable corruption`() {
        val root = newHealthyRootfs()
        assertTrue(File(root, "etc/resolv.conf").delete())
        assertTrue(File(root, "var/tmp").deleteRecursively())
        assertFalse(RootfsIntegrityVerifier.verify(root).isValid)

        val healed = RootfsIntegrityVerifier.verifyAndHeal(root)
        assertTrue("recoverable items must be healed, details: ${healed.details}", healed.isValid)
        assertTrue(healed.isHealed)
        assertTrue(healed.healedPaths.contains("etc/resolv.conf"))
        assertTrue(healed.healedPaths.contains("var/tmp"))
    }

    @Test
    fun `verifyAndHeal cannot fabricate a missing shell`() {
        val root = newHealthyRootfs()
        assertTrue(File(root, "usr/bin/bash").delete())

        // The healer recreates dirs and config files but NOT shell binaries:
        // it must report the remaining damage honestly instead of healing it.
        val report = RootfsIntegrityVerifier.verifyAndHeal(root)
        assertFalse("unhealable damage must stay reported", report.isValid)
        assertTrue(report.missingPaths.contains("bin/sh"))
    }

    // ------------------------------------------------------------------
    // (c) Failed checkpoint write leaves no corrupt partial state.
    // ------------------------------------------------------------------

    @Test
    fun `failed checkpoint write leaves no corrupt partial state`() {
        val filesDir = tempFolder.newFolder("filesCkpt")
        val manager = WorkspaceCheckpointManager(filesDir)

        // Block the snapshot storage path with a regular file: every mkdirs()
        // in the snapshot path silently fails and the file-copy step throws,
        // which createCheckpoint must convert into a clean null.
        val snapshotsPath = File(filesDir, "checkpoints/proj-broken/snapshots")
        snapshotsPath.parentFile?.mkdirs()
        snapshotsPath.writeText("blocking file, not a directory")

        val workspaceDir = tempFolder.newFolder("wsBroken")
        File(workspaceDir, "App.kt").writeText("fun main() {}")

        // Must not throw: the contract is null (or a clean throw), never an
        // escaped exception and never a half-written checkpoint.
        val result = runCatching { manager.createCheckpoint("proj-broken", workspaceDir, "broken") }.getOrNull()
        assertNull("createCheckpoint must return null on failure, not a partial checkpoint", result)

        // Index stays consistent: no phantom entry for the failed checkpoint.
        assertTrue(
            "index must stay consistent after a failed write",
            manager.listCheckpoints("proj-broken").isEmpty(),
        )
        // No partial metadata may be left behind on disk.
        val partialMeta = snapshotsPath.walkTopDown().filter { it.isFile && it.name == "meta.json" }.toList()
        assertTrue("no partial checkpoint metadata may be left behind", partialMeta.isEmpty())

        assertFalse(manager.verifyCheckpointIntegrity("proj-broken", "ckpt_does_not_exist"))
    }

    // ------------------------------------------------------------------
    // (d) Out-of-storage during a write fails honestly.
    // ------------------------------------------------------------------

    @Test
    fun `out of storage during write fails honestly and keeps original`() {
        val dir = tempFolder.newFolder("storage")
        val target = File(dir, "data.json").apply { writeText("""{"v":1}""") }

        // Injected ENOSPC simulation: the write function reports failure.
        val writer = StagedWriter(writeFn = { _, _ -> false })

        assertFalse("write must report failure when storage gives out", writer.write(target, """{"v":2}"""))
        assertEquals("original content must survive the failed write", """{"v":1}""", target.readText())
        assertTrue(
            "no staging debris may be left behind",
            dir.listFiles()!!.none { it.name.contains(".staging_") },
        )
    }

    @Test
    fun `throwing write function also fails honestly`() {
        val dir = tempFolder.newFolder("storage2")
        val target = File(dir, "data.json").apply { writeText("""{"v":1}""") }

        val writer = StagedWriter(writeFn = { _, _ -> throw IOException("No space left on device (ENOSPC)") })

        assertFalse(writer.write(target, """{"v":2}"""))
        assertEquals("""{"v":1}""", target.readText())
    }

    @Test
    fun `real atomic write reports failure when the parent is not a directory`() {
        // Real SafeFileOps path: the temp staging file cannot be created, so the
        // call must return false instead of throwing or half-writing.
        val blocker = File(tempFolder.root, "blocker").apply { writeText("x") }
        val target = File(blocker, "data.txt")
        assertFalse(SafeFileOps.atomicWriteText(target, "new content"))
        assertFalse("failed write must not materialize the target", target.exists())
    }

    @Test
    fun `staged writer promotes content on success control`() {
        val dir = tempFolder.newFolder("storage3")
        val target = File(dir, "data.json")
        // Control: StagedWriter must promote content when the write succeeds.
        // Uses a real file write (not SafeFileOps, which depends on android.os.Build
        // and has its own dedicated passing tests in SafeFileOpsTest); this test
        // verifies the staging/promotion logic, not SafeFileOps.
        val writer = StagedWriter(writeFn = { file, content ->
            runCatching { file.writeText(content) }.isSuccess
        })
        assertTrue(writer.write(target, """{"v":1}"""))
        assertEquals("""{"v":1}""", target.readText())
        assertTrue(
            "no staging debris may be left behind",
            dir.listFiles()!!.none { it.name.contains(".staging_") },
        )
    }

// ------------------------------------------------------------------
    // (e) Concurrent external edit between snapshot and check is detected.
    // ------------------------------------------------------------------

    @Test
    fun `externally truncated file is detected after snapshot`() {
        val filesDir = tempFolder.newFolder("filesE")
        val workspaceDir = tempFolder.newFolder("wsE")
        val app = File(workspaceDir, "App.kt").apply { writeText("fun main() {}") }
        val manager = WorkspaceCheckpointManager(filesDir)

        val checkpoint = manager.createCheckpoint("proj-e", workspaceDir, "baseline")
        assertNotNull(checkpoint)

        // Another process truncates the file after the snapshot was taken.
        app.writeText("")

        val issues = manager.detectWorkspaceCorruption(workspaceDir)
        assertTrue(
            "truncated source file must be flagged, got: $issues",
            issues.any { it.contains("App.kt") },
        )
        // The checkpoint itself is untouched by the external edit.
        assertTrue(manager.verifyCheckpointIntegrity("proj-e", checkpoint!!.id))
    }

    @Test
    fun `stale temp file from a crashed process is detected`() {
        val workspaceDir = tempFolder.newFolder("wsE2")
        File(workspaceDir, "notes.tmp_a1b2c3d4").writeText("partial write")
        val manager = WorkspaceCheckpointManager(tempFolder.newFolder("filesE2"))

        val issues = manager.detectWorkspaceCorruption(workspaceDir)
        assertTrue(
            "stale temp file must be flagged, got: $issues",
            issues.any { it.contains("notes.tmp_a1b2c3d4") },
        )
    }

    @Test
    fun `healthy workspace reports no corruption`() {
        val workspaceDir = tempFolder.newFolder("wsE3")
        File(workspaceDir, "App.kt").writeText("fun main() {}")
        val manager = WorkspaceCheckpointManager(tempFolder.newFolder("filesE3"))
        assertTrue(manager.detectWorkspaceCorruption(workspaceDir).isEmpty())
    }
}
