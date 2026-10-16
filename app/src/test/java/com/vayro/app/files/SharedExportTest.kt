package com.vayro.app.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

class SharedExportTest {

    private fun tempFilesDir(): File = Files.createTempDirectory("shared-export-test").toFile()

    @Test
    fun stageApkForInstallCopiesApkIntoDedicatedDir() {
        val filesDir = tempFilesDir()
        try {
            val apk = File(filesDir, "app-release.apk").apply { writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) }
            val staged = SharedExport.stageApkForInstall(filesDir, apk)
            assertTrue(staged.isFile)
            assertEquals("shared/install", staged.parentFile.relativeTo(filesDir).invariantSeparatorsPath)
            assertTrue(staged.name.endsWith(".apk"))
            assertEquals(apk.readBytes().toList(), staged.readBytes().toList())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun stageApkForInstallSanitizesApkName() {
        val filesDir = tempFilesDir()
        try {
            val apk = File(filesDir, "..evil name!.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val staged = SharedExport.stageApkForInstall(filesDir, apk)
            assertFalse(staged.name.contains('/'))
            assertFalse(staged.name.contains(".."))
            assertTrue(staged.name.endsWith(".apk"))
            assertTrue(staged.parentFile == SharedExport.installDir(filesDir))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun stageApkForInstallRejectsNonApk() {
        val filesDir = tempFilesDir()
        try {
            val notApk = File(filesDir, "notes.txt").apply { writeText("hello") }
            try {
                SharedExport.stageApkForInstall(filesDir, notApk)
                fail("Expected rejection of non-APK file")
            } catch (_: IllegalArgumentException) {
            }
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun stageFileForOpenNeutralizesTraversalDisplayName() {
        val filesDir = tempFilesDir()
        try {
            val source = File(filesDir, "real.txt").apply { writeText("content") }
            val staged = SharedExport.stageFileForOpen(filesDir, source, "../../evil.sh")
            assertTrue(staged.isFile)
            assertTrue(staged.parentFile == SharedExport.openDir(filesDir))
            assertFalse(staged.name.contains('/'))
            assertFalse(".." in staged.name)
            assertEquals("content", staged.readText())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun stageFileForOpenDeduplicatesNames() {
        val filesDir = tempFilesDir()
        try {
            val first = File(filesDir, "a.txt").apply { writeText("one") }
            val second = File(filesDir, "b.txt").apply { writeText("two") }
            val stagedFirst = SharedExport.stageFileForOpen(filesDir, first, "doc.txt")
            val stagedSecond = SharedExport.stageFileForOpen(filesDir, second, "doc.txt")
            assertTrue(stagedFirst.isFile)
            assertTrue(stagedSecond.isFile)
            assertTrue(stagedFirst.name != stagedSecond.name)
            assertEquals("two", stagedSecond.readText())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun filePathsXmlExposesOnlyDedicatedExportDirs() {
        val xml = File("src/main/res/xml/file_paths.xml")
        assertTrue("file_paths.xml must exist at ${xml.absolutePath}", xml.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val roots = doc.documentElement.childNodes
        var count = 0
        for (i in 0 until roots.length) {
            val node = roots.item(i)
            if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
            count += 1
            val path = node.attributes.getNamedItem("path")?.nodeValue.orEmpty()
            assertFalse("FileProvider root <${node.nodeName}> must not expose a broad root (path='$path')",
                path.isBlank() || path == "." || path == "./" || path == "/")
            assertTrue("FileProvider root <${node.nodeName}> must stay inside a dedicated export dir (path='$path')",
                path.startsWith("shared/"))
        }
        assertTrue("file_paths.xml must define at least one export root", count > 0)
    }
}
