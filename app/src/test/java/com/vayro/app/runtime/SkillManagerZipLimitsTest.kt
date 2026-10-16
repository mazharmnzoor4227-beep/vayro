package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillManagerZipLimitsTest {

    private fun tempDir(): File = Files.createTempDirectory("skill-zip-limits-test").toFile()

    private fun writeZip(zip: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                var offset = 0
                while (offset < bytes.size) {
                    val chunk = minOf(1024 * 1024, bytes.size - offset)
                    out.write(bytes, offset, chunk)
                    offset += chunk
                }
                out.closeEntry()
            }
        }
    }

    private fun zeros(mb: Int): ByteArray = ByteArray(mb * 1024 * 1024)

    private fun skillManifest(name: String): ByteArray =
        "---\nname: $name\ndescription: A test skill.\n---\n\n# $name\n".toByteArray()

    private fun extractionLeftovers(root: File): List<File> =
        File(root, ".cache").listFiles()?.filter { it.name.startsWith("skill-extract-") }.orEmpty()

    @Test
    fun validSkillZipInstallsThroughHardenedExtractor() {
        val root = tempDir()
        try {
            val manager = SkillManager(baseDir = root)
            val zip = File(root, "skill.zip")
            writeZip(zip, listOf("test-skill/SKILL.md" to skillManifest("test-skill")))
            val installed = manager.installZip(zip, "test-source")
            assertEquals(1, installed.size)
            assertEquals("test-skill", installed.first().name)
            assertTrue(File(root, "test-skill/SKILL.md").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun traversalEntryIsRejectedAndCleanedUp() {
        val root = tempDir()
        try {
            val manager = SkillManager(baseDir = root)
            val zip = File(root, "evil.zip")
            writeZip(zip, listOf("../evil.txt" to "evil".toByteArray()))
            try {
                manager.installZip(zip, "test-source")
                fail("Expected traversal rejection")
            } catch (_: IllegalArgumentException) {
            }
            assertTrue(extractionLeftovers(root).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun absolutePathEntryIsRejected() {
        val root = tempDir()
        try {
            val manager = SkillManager(baseDir = root)
            val zip = File(root, "evil.zip")
            writeZip(zip, listOf("/abs.txt" to "evil".toByteArray()))
            try {
                manager.installZip(zip, "test-source")
                fail("Expected absolute-path rejection")
            } catch (_: IllegalArgumentException) {
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tooManyEntriesAreRejected() {
        val root = tempDir()
        try {
            val manager = SkillManager(baseDir = root)
            val zip = File(root, "many.zip")
            val entries = (0 until SkillManager.MAX_SKILL_ENTRIES + 1).map { "f$it.txt" to "x".toByteArray() }
            writeZip(zip, entries)
            try {
                manager.installZip(zip, "test-source")
                fail("Expected entry-count rejection")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().contains("too many"))
            }
            assertTrue(extractionLeftovers(root).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oversizedSingleFileIsRejected() {
        val root = tempDir()
        try {
            val manager = SkillManager(baseDir = root)
            val zip = File(root, "big.zip")
            writeZip(zip, listOf("big/blob.bin" to zeros(60)))
            try {
                manager.installZip(zip, "test-source")
                fail("Expected per-file size rejection")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().contains("50 MB"))
            }
            assertTrue(extractionLeftovers(root).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

}
