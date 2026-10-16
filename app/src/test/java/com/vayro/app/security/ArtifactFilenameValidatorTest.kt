package com.vayro.app.security

import com.vayro.app.security.ArtifactFilenameValidator.FilenameValidation
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ArtifactFilenameValidatorTest {

    private lateinit var destDir: File

    @Before
    fun setUp() {
        destDir = File(System.getProperty("java.io.tmpdir"), "validator_test_" + System.nanoTime())
        destDir.mkdirs()
    }

    @After
    fun tearDown() {
        destDir.deleteRecursively()
    }

    @Test
    fun acceptsPlainSingleSegmentFilenames() {
        for (name in listOf("app-release.apk", "report 1.html", "archive.tar.gz", "a..b.apk", ".hidden", "x")) {
            assertTrue("expected valid: $name", ArtifactFilenameValidator.validate(name) is FilenameValidation.Valid)
        }
    }

    @Test
    fun rejectsTraversalAndAbsoluteFilenames() {
        for (name in listOf(
            "../evil.apk",
            "../../evil",
            "..\\evil.apk",
            "/abs/path.apk",
            "a/b.apk",
            "a\\b.apk",
            "..",
            ".",
            "",
            "   ",
            "evil.apk\u0000",
            "ev\u0001il.apk",
            "C:\\evil.apk"
        )) {
            val result = ArtifactFilenameValidator.validate(name)
            assertTrue("expected invalid: '$name'", result is FilenameValidation.Invalid)
        }
    }

    @Test
    fun resolveTargetKeepsValidNamesInsideDestination() {
        val target = ArtifactFilenameValidator.resolveTarget(destDir, "app.apk")
        assertNotNull(target)
        assertTrue(target!!.absolutePath.startsWith(destDir.absolutePath + File.separator))
        assertTrue(target.name == "app.apk")
    }

    @Test
    fun resolveTargetRejectsEveryUnsafeName() {
        for (name in listOf("../evil.apk", "../../evil", "/abs/path.apk", "a/b.apk", "a\\b.apk", "..", "")) {
            assertNull("expected null target for: '$name'", ArtifactFilenameValidator.resolveTarget(destDir, name))
        }
    }

    @Test
    fun tempFileNameIsRandomAndNotDerivedFromRemoteName() {
        val first = ArtifactFilenameValidator.newTempFile(destDir)
        val second = ArtifactFilenameValidator.newTempFile(destDir)
        assertNotEquals(first.name, second.name)
        assertFalse(first.name.contains("evil"))
        assertTrue(first.parentFile.absolutePath == destDir.absoluteFile.absolutePath)
        assertTrue(first.name.startsWith("artifact-"))
        assertTrue(first.name.endsWith(".tmp"))
    }

    @Test
    fun moveVerifiedFileWritesContentAndOverwrites() {
        val src = File(destDir, "src.tmp")
        src.writeText("new-content")
        val dest = File(destDir, "final.apk")
        dest.writeText("old-content")
        assertTrue(ArtifactFilenameValidator.moveVerifiedFile(src, dest))
        assertFalse(src.exists())
        assertTrue(dest.readText() == "new-content")
    }
}
