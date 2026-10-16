package com.vayro.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * M5: path traversal / symlink / absolute-escape audit tests.
 * Deterministic on JVM; symlink cases use real symlinks in a temp dir and
 * are skipped only if the platform cannot create them.
 */
class PathSecurityAuditorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var auditor: PathSecurityAuditor
    private lateinit var root: File

    @Before
    fun setUp() {
        auditor = PathSecurityAuditor()
        root = tempFolder.newFolder("workspace")
        File(root, "src").mkdirs()
        File(root, "src/Main.kt").writeText("fun main() {}")
    }

    @Test
    fun `safe nested path inside root is allowed`() {
        val result = auditor.audit("src/Main.kt", root)
        assertTrue(result is PathSecurityAuditor.PathAuditResult.Safe)
    }

    @Test
    fun `dot segments inside root are allowed`() {
        val result = auditor.audit("src/./Main.kt", root)
        assertTrue(result is PathSecurityAuditor.PathAuditResult.Safe)
    }

    @Test
    fun `dotdot escaping the root is rejected as traversal`() {
        val result = auditor.audit("../../etc/passwd", root)
        assertTrue(result is PathSecurityAuditor.PathAuditResult.Rejected)
        val rejected = result as PathSecurityAuditor.PathAuditResult.Rejected
        assertEquals(
            PathSecurityAuditor.RejectionKind.TRAVERSAL,
            rejected.kind
        )
    }

    @Test
    fun `absolute path outside root is rejected`() {
        val result = auditor.audit("/etc/passwd", root)
        assertTrue(result is PathSecurityAuditor.PathAuditResult.Rejected)
        val rejected = result as PathSecurityAuditor.PathAuditResult.Rejected
        assertEquals(PathSecurityAuditor.RejectionKind.ABSOLUTE_ESCAPE, rejected.kind)
    }

    @Test
    fun `null byte in path is rejected`() {
        val result = auditor.audit("src/Main.kt\u0000.png", root)
        assertTrue(result is PathSecurityAuditor.PathAuditResult.Rejected)
        val rejected = result as PathSecurityAuditor.PathAuditResult.Rejected
        assertEquals(PathSecurityAuditor.RejectionKind.NULL_BYTE, rejected.kind)
    }

    @Test
    fun `empty path is rejected`() {
        val result = auditor.audit("   ", root)
        assertTrue(result is PathSecurityAuditor.PathAuditResult.Rejected)
    }

    @Test
    fun `symlink inside root pointing outside is rejected as symlink escape`() {
        val outside = tempFolder.newFolder("outside")
        File(outside, "secret.txt").writeText("secret")
        val link: File
        try {
            link = File(root, "evil-link")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Throwable) {
            // Platform cannot create symlinks; nothing to assert.
            return
        }
        assertTrue("test setup: link should be a symlink", Files.isSymbolicLink(link.toPath()))

        val result = auditor.audit("evil-link/secret.txt", root)
        assertTrue("expected SYMLINK_ESCAPE but was $result",
            result is PathSecurityAuditor.PathAuditResult.Rejected)
        val rejected = result as PathSecurityAuditor.PathAuditResult.Rejected
        assertEquals(PathSecurityAuditor.RejectionKind.SYMLINK_ESCAPE, rejected.kind)
    }

    @Test
    fun `symlink escape through non-existent tail is rejected`() {
        val outside = tempFolder.newFolder("outside2")
        val link: File
        try {
            link = File(root, "evil-link2")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Throwable) {
            return
        }
        // Target file does not exist yet; the symlinked ancestor must still be caught.
        val result = auditor.audit("evil-link2/new-file.txt", root)
        assertTrue("expected SYMLINK_ESCAPE but was $result",
            result is PathSecurityAuditor.PathAuditResult.Rejected)
        val rejected = result as PathSecurityAuditor.PathAuditResult.Rejected
        assertEquals(PathSecurityAuditor.RejectionKind.SYMLINK_ESCAPE, rejected.kind)
    }

    @Test
    fun `symlink inside root pointing inside root is allowed`() {
        val target = File(root, "src")
        val link: File
        try {
            link = File(root, "src-link")
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: Throwable) {
            return
        }
        val result = auditor.audit("src-link/Main.kt", root)
        assertTrue("expected Safe but was $result",
            result is PathSecurityAuditor.PathAuditResult.Safe)
    }

    @Test
    fun `isSafe convenience matches audit`() {
        assertTrue(auditor.isSafe("src/Main.kt", root))
        assertFalse(auditor.isSafe("../../etc/passwd", root))
    }

    @Test
    fun `null root rejects absolute path as absolute escape`() {
        val result = auditor.audit("/etc/passwd", null)
        assertTrue("expected Rejected but was $result",
            result is PathSecurityAuditor.PathAuditResult.Rejected)
        val rejected = result as PathSecurityAuditor.PathAuditResult.Rejected
        assertEquals(PathSecurityAuditor.RejectionKind.ABSOLUTE_ESCAPE, rejected.kind)
    }

    @Test
    fun `null root rejects traversal in relative path`() {
        val result = auditor.audit("../../etc/passwd", null)
        assertTrue("expected Rejected but was $result",
            result is PathSecurityAuditor.PathAuditResult.Rejected)
        val rejected = result as PathSecurityAuditor.PathAuditResult.Rejected
        assertEquals(PathSecurityAuditor.RejectionKind.TRAVERSAL, rejected.kind)
    }

    @Test
    fun `null root rejects null byte`() {
        val result = auditor.audit("src/Ma\u0000in.kt", null)
        assertTrue("expected Rejected but was $result",
            result is PathSecurityAuditor.PathAuditResult.Rejected)
    }

    @Test
    fun `null root allows structurally safe relative path`() {
        val result = auditor.audit("src/Main.kt", null)
        assertTrue("expected Safe but was $result",
            result is PathSecurityAuditor.PathAuditResult.Safe)
    }

    @Test
    fun `guest project root contains guest absolute paths`() {
        val guestRoot = File("/workspace/demo")
        val result = auditor.audit("/workspace/demo/src/Foo.kt", guestRoot)
        assertTrue("expected Safe but was $result",
            result is PathSecurityAuditor.PathAuditResult.Safe)
    }

    @Test
    fun `guest project root rejects host absolute path`() {
        val guestRoot = File("/workspace/demo")
        val result = auditor.audit("/etc/passwd", guestRoot)
        assertTrue("expected Rejected but was $result",
            result is PathSecurityAuditor.PathAuditResult.Rejected)
    }

    @Test
    fun `guest project root rejects traversal to sibling workspace`() {
        val guestRoot = File("/workspace/demo")
        val result = auditor.audit("/workspace/demo/../../other/x.kt", guestRoot)
        assertTrue("expected Rejected but was $result",
            result is PathSecurityAuditor.PathAuditResult.Rejected)
    }

    @Test
    fun `guest project root rejects other project workspace`() {
        val guestRoot = File("/workspace/demo")
        val result = auditor.audit("/workspace/other/x.kt", guestRoot)
        assertTrue("expected Rejected but was $result",
            result is PathSecurityAuditor.PathAuditResult.Rejected)
    }
}
