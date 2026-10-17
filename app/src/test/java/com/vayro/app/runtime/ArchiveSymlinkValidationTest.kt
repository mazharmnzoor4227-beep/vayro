package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Spec for rootfs symlink validation (v3.2.17 semantics): a bundle is a
 * guest filesystem image, so targets are resolved lexically inside the
 * image and any ".." climb is clamped at the image root — the same way
 * the guest kernel will resolve them once the rootfs is mounted. Only a
 * blank target is invalid. Validation never consults the host disk, so
 * pre-existing files or links in the extraction directory cannot change
 * the verdict (the failure mode that broke setup on real devices).
 */
class ArchiveSymlinkValidationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun root(): File = tempFolder.newFolder("extract_root")

    private fun assertRejected(root: File, linkFile: File, linkName: String, messagePart: String) {
        try {
            validateArchiveSymlinkTarget(root, linkFile, linkName)
            fail("symlink target '$linkName' must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "rejection reason '${e.message}' should mention '$messagePart'",
                e.message?.contains(messagePart) == true
            )
        }
    }

    private fun resolved(root: File, linkFile: File, linkName: String): String =
        resolveSymlinkTargetLexically(root, linkFile, linkName)

    @Test
    fun absoluteLinkTargetsInsideRootAreAccepted() {
        // A standard Linux rootfs is full of absolute links (for example
        // /bin -> usr/bin). Inside the guest root those targets are
        // root-relative, so they must validate, not be rejected.
        val root = root()
        File(root, "usr/bin").mkdirs()
        validateArchiveSymlinkTarget(root, File(root, "bin"), "/usr/bin")
        validateArchiveSymlinkTarget(root, File(root, "etc_link"), "/etc/passwd")
        validateArchiveSymlinkTarget(root, File(root, "sh"), "//usr/bin/sh")
        assertEquals(
            File(root, "usr/bin").absolutePath,
            resolved(root, File(root, "bin"), "/usr/bin")
        )
    }

    @Test
    fun absoluteTraversalIsClampedAtTheRoot() {
        val root = root()
        validateArchiveSymlinkTarget(root, File(root, "odd"), "/../../etc/passwd")
        assertEquals(
            File(root, "etc/passwd").absolutePath,
            resolved(root, File(root, "odd"), "/../../etc/passwd")
        )
        assertEquals(
            File(root, "etc/passwd").absolutePath,
            resolved(root, File(root, "odd"), "//../../etc/passwd")
        )
    }

    @Test
    fun relativeTraversalIsClampedAtTheRoot() {
        val root = root()
        File(root, "sub").mkdirs()
        validateArchiveSymlinkTarget(root, File(root, "sub/odd"), "../../outside")
        assertEquals(
            File(root, "outside").absolutePath,
            resolved(root, File(root, "sub/odd"), "../../outside")
        )
        validateArchiveSymlinkTarget(root, File(root, "odd"), "../outside.txt")
        assertEquals(
            File(root, "outside.txt").absolutePath,
            resolved(root, File(root, "odd"), "../outside.txt")
        )
    }

    @Test
    fun emptyLinkTargetIsRejected() {
        val root = root()
        assertRejected(root, File(root, "empty"), "", "empty")
        assertRejected(root, File(root, "blank"), "   ", "empty")
    }

    @Test
    fun validRelativeLinksAreAccepted() {
        val root = root()
        File(root, "bin").mkdirs()
        File(root, "sub").mkdirs()
        validateArchiveSymlinkTarget(root, File(root, "link1"), "bin/tool")
        validateArchiveSymlinkTarget(root, File(root, "sub/link2"), "../bin/tool")
        validateArchiveSymlinkTarget(root, File(root, "self"), "self")
        validateArchiveSymlinkTarget(root, File(root, "dot"), "./bin/tool")
        assertEquals(
            File(root, "bin/tool").absolutePath,
            resolved(root, File(root, "sub/link2"), "../bin/tool")
        )
    }

    @Test
    fun verdictDoesNotDependOnDiskState() {
        // Regression test for the on-device failure: links already present
        // in the extraction directory (leftovers of a previous attempt, or
        // entries extracted earlier from the same bundle) must not change
        // validation outcomes — the old canonical-file check followed them
        // and falsely reported escapes.
        val root = root()
        val outer = tempFolder.newFolder("outer")
        val dirLink = File(root, "dirlink")
        Files.createSymbolicLink(dirLink.toPath(), outer.toPath())
        validateArchiveSymlinkTarget(root, File(dirLink, "some"), "payload")
        validateArchiveSymlinkTarget(root, File(root, "bin"), "/usr/bin")

        val real = File(root, "real").apply { mkdirs() }
        val alias = File(root, "alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        validateArchiveSymlinkTarget(root, File(alias, "chained"), "../../target")
        assertEquals(
            File(root, "target").absolutePath,
            resolved(root, File(alias, "chained"), "../../target")
        )
    }
}
