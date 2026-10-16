package com.vayro.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileOperationsControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceDir: File
    private lateinit var controller: FileOperationsController

    @Before
    fun setUp() {
        workspaceDir = tempFolder.newFolder("test_workspace")
        controller = FileOperationsController()
    }

    @Test
    fun `createFile creates file with content and parent directories`() {
        val result = controller.createFile(workspaceDir, "src/main/Test.kt", "fun main() {}")
        assertTrue(result.isSuccess)
        val file = result.getOrThrow()
        assertTrue(file.exists())
        assertEquals("fun main() {}", file.readText())
    }

    @Test
    fun `createFile fails if file already exists`() {
        controller.createFile(workspaceDir, "hello.txt", "first")
        val second = controller.createFile(workspaceDir, "hello.txt", "second")
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull() is FileOperationsController.FileOpError.TargetAlreadyExists)
    }

    @Test
    fun `createFile rejects directory traversal outside workspace`() {
        val result = controller.createFile(workspaceDir, "../outside.txt", "malicious")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FileOperationsController.FileOpError.SecurityViolation)
    }

    @Test
    fun `createDirectory creates nested folders successfully`() {
        val result = controller.createDirectory(workspaceDir, "deep/nested/folder")
        assertTrue(result.isSuccess)
        val dir = result.getOrThrow()
        assertTrue(dir.isDirectory)
        assertTrue(dir.exists())
    }

    @Test
    fun `renameEntry renames file cleanly`() {
        controller.createFile(workspaceDir, "old.txt", "content")
        val renameResult = controller.renameEntry(workspaceDir, "old.txt", "new.txt")
        assertTrue(renameResult.isSuccess)
        assertFalse(File(workspaceDir, "old.txt").exists())
        val newFile = File(workspaceDir, "new.txt")
        assertTrue(newFile.exists())
        assertEquals("content", newFile.readText())
    }

    @Test
    fun `deleteEntry removes file and recursively removes folder`() {
        controller.createFile(workspaceDir, "to_delete.txt", "content")
        val deleteFile = controller.deleteEntry(workspaceDir, "to_delete.txt")
        assertTrue(deleteFile.isSuccess)
        assertFalse(File(workspaceDir, "to_delete.txt").exists())

        controller.createFile(workspaceDir, "folder/sub.txt", "inner")
        val deleteFolder = controller.deleteEntry(workspaceDir, "folder")
        assertTrue(deleteFolder.isSuccess)
        assertFalse(File(workspaceDir, "folder").exists())
    }

    @Test
    fun `deleteEntry refuses to delete workspace root`() {
        val result = controller.deleteEntry(workspaceDir, ".")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FileOperationsController.FileOpError.SecurityViolation ||
            result.exceptionOrNull() is FileOperationsController.FileOpError.InvalidPath)
    }

    @Test
    fun `duplicateEntry creates duplicate with copy suffix without corrupting original`() {
        controller.createFile(workspaceDir, "main.kt", "println(1)")
        val copyResult = controller.duplicateEntry(workspaceDir, "main.kt")
        assertTrue(copyResult.isSuccess)
        val copy = copyResult.getOrThrow()
        assertEquals("main_copy.kt", copy.name)
        assertEquals("println(1)", copy.readText())
        assertEquals("println(1)", File(workspaceDir, "main.kt").readText())

        // Duplicate again to verify counter increment
        val copy2Result = controller.duplicateEntry(workspaceDir, "main.kt")
        assertTrue(copy2Result.isSuccess)
        assertEquals("main_copy_2.kt", copy2Result.getOrThrow().name)
    }

    @Test
    fun `moveEntry relocates file into destination directory`() {
        controller.createFile(workspaceDir, "doc.txt", "notes")
        controller.createDirectory(workspaceDir, "archive")
        val moveResult = controller.moveEntry(workspaceDir, "doc.txt", "archive")
        assertTrue(moveResult.isSuccess)
        assertFalse(File(workspaceDir, "doc.txt").exists())
        val moved = File(workspaceDir, "archive/doc.txt")
        assertTrue(moved.exists())
        assertEquals("notes", moved.readText())
    }

    @Test
    fun `moveEntry relocates directory into another folder`() {
        controller.createDirectory(workspaceDir, "subpkg")
        controller.createFile(workspaceDir, "subpkg/File.kt", "code")
        controller.createDirectory(workspaceDir, "target_parent")
        val moveResult = controller.moveEntry(workspaceDir, "subpkg", "target_parent")
        assertTrue(moveResult.isSuccess)
        assertFalse(File(workspaceDir, "subpkg").exists())
        val moved = File(workspaceDir, "target_parent/subpkg/File.kt")
        assertTrue(moved.exists())
        assertEquals("code", moved.readText())
    }

    @Test
    fun `moveEntry fails when source does not exist`() {
        controller.createDirectory(workspaceDir, "dest")
        val moveResult = controller.moveEntry(workspaceDir, "ghost.txt", "dest")
        assertTrue(moveResult.isFailure)
        assertTrue(moveResult.exceptionOrNull() is FileOperationsController.FileOpError.SourceNotFound)
    }

    @Test
    fun `moveEntry rejects destination outside workspace`() {
        controller.createFile(workspaceDir, "valid.txt", "valid")
        val moveResult = controller.moveEntry(workspaceDir, "valid.txt", "../outside")
        assertTrue(moveResult.isFailure)
        assertTrue(moveResult.exceptionOrNull() is FileOperationsController.FileOpError.SecurityViolation)
    }
}
