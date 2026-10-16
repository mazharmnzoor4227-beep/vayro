package com.vayro.app.runtime

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

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

    @Test
    fun absoluteLinkTargetIsRejected() {
        val root = root()
        assertRejected(root, File(root, "evil"), "/etc/passwd", "absolute")
    }

    @Test
    fun absoluteLinkTargetWithDoubleSlashIsRejected() {
        val root = root()
        assertRejected(root, File(root, "evil"), "//etc/passwd", "absolute")
    }

    @Test
    fun emptyLinkTargetIsRejected() {
        val root = root()
        assertRejected(root, File(root, "empty"), "", "empty")
        assertRejected(root, File(root, "blank"), "   ", "empty")
    }

    @Test
    fun relativeTraversalLinkIsRejected() {
        val root = root()
        File(root, "sub").mkdirs()
        assertRejected(root, File(root, "sub/evil"), "../../outside", "escapes")
    }

    @Test
    fun dotDotSlashTraversalIsRejected() {
        val root = root()
        assertRejected(root, File(root, "evil"), "../outside.txt", "escapes")
    }

    @Test
    fun linkChainEscapeIsRejected() {
        val root = root()
        val real = File(root, "real").apply { mkdirs() }
        val alias = File(root, "alias")
        validateArchiveSymlinkTarget(root, alias, "real")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        assertRejected(root, File(alias, "chained"), "../../escape", "escapes")
    }

    @Test
    fun forwardLinkChainToEscapingLinkIsRejected() {
        val root = root()
        val first = File(root, "first")
        validateArchiveSymlinkTarget(root, first, "second")
        Files.createSymbolicLink(first.toPath(), File(root, "second").toPath())
        assertRejected(root, File(root, "second"), "../escape", "escapes")
    }

    @Test
    fun linkThroughPreExistingEscapingDirLinkIsRejected() {
        val root = root()
        val outer = tempFolder.newFolder("outer")
        val dirLink = File(root, "dirlink")
        Files.createSymbolicLink(dirLink.toPath(), outer.toPath())
        assertRejected(root, File(dirLink, "evil"), "payload", "escapes")
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
    }
}
