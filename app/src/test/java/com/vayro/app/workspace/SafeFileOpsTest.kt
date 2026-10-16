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

class SafeFileOpsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `atomicWriteText writes content cleanly and creates parent directories`() {
        val target = File(tempFolder.root, "nested/dir/config.json")
        val success = SafeFileOps.atomicWriteText(target, """{"name": "test-project"}""")

        assertTrue(success)
        assertTrue(target.exists())
        assertEquals("""{"name": "test-project"}""", target.readText())
    }

    @Test
    fun `atomicWriteText overwrites existing file without corruption`() {
        val target = File(tempFolder.root, "data.txt")
        SafeFileOps.atomicWriteText(target, "initial content")
        assertEquals("initial content", target.readText())

        val success = SafeFileOps.atomicWriteText(target, "updated content")
        assertTrue(success)
        assertEquals("updated content", target.readText())
    }

    @Test
    fun `atomicWriteBytes writes binary byte array accurately`() {
        val target = File(tempFolder.root, "binary.bin")
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x7F, 0x80.toByte(), 0xFF.toByte())

        val success = SafeFileOps.atomicWriteBytes(target, bytes)
        assertTrue(success)
        assertTrue(target.readBytes().contentEquals(bytes))
    }

    @Test
    fun `safeReadText returns null for non-existent or unreadable file`() {
        val target = File(tempFolder.root, "non_existent.txt")
        assertNull(SafeFileOps.safeReadText(target))

        val dir = File(tempFolder.root, "subfolder").apply { mkdirs() }
        assertNull(SafeFileOps.safeReadText(dir))
    }

    @Test
    fun `cleanStaleTempFiles removes old tmp files and preserves active files`() {
        val baseDir = tempFolder.newFolder("staging")
        val oldTmp = File(baseDir, "data.json.tmp_old123").apply {
            writeText("stale temp")
            setLastModified(System.currentTimeMillis() - 600_000L) // 10 minutes ago
        }
        val freshTmp = File(baseDir, "data.json.tmp_fresh456").apply {
            writeText("fresh temp")
            setLastModified(System.currentTimeMillis())
        }
        val regularFile = File(baseDir, "data.json").apply {
            writeText("regular")
        }

        val cleaned = SafeFileOps.cleanStaleTempFiles(baseDir, maxAgeMs = 300_000L)
        assertEquals(1, cleaned)
        assertFalse(oldTmp.exists())
        assertTrue(freshTmp.exists())
        assertTrue(regularFile.exists())
    }
}
