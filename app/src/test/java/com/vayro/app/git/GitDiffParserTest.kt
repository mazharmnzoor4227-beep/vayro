package com.vayro.app.git

import com.vayro.app.git.diff.GitDiffParser
import com.vayro.app.git.model.GitDiffLineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitDiffParserTest {

    @Test
    fun parseSingleFileModificationDiff() {
        val rawDiff = """
            diff --git a/app/src/Main.kt b/app/src/Main.kt
            index 1234567..89abcde 100644
            --- a/app/src/Main.kt
            +++ b/app/src/Main.kt
            @@ -10,4 +10,5 @@ class Main {
             val x = 1
            -val y = 2
            +val y = 20
            +val z = 30
             fun run() {}
        """.trimIndent()

        val result = GitDiffParser.parse(rawDiff)
        assertEquals(1, result.size)

        val fileDiff = result[0]
        assertEquals("app/src/Main.kt", fileDiff.oldPath)
        assertEquals("app/src/Main.kt", fileDiff.newPath)
        assertFalse(fileDiff.isNewFile)
        assertFalse(fileDiff.isDeletedFile)
        assertFalse(fileDiff.isBinary)
        assertEquals(2, fileDiff.additions)
        assertEquals(1, fileDiff.deletions)

        assertEquals(1, fileDiff.hunks.size)
        val hunk = fileDiff.hunks[0]
        assertEquals(10, hunk.oldStart)
        assertEquals(4, hunk.oldCount)
        assertEquals(10, hunk.newStart)
        assertEquals(5, hunk.newCount)

        val lines = hunk.lines
        assertEquals(5, lines.size)
        assertEquals(GitDiffLineType.CONTEXT, lines[0].type)
        assertEquals("val x = 1", lines[0].text)

        assertEquals(GitDiffLineType.DELETION, lines[1].type)
        assertEquals("val y = 2", lines[1].text)
        assertEquals(11, lines[1].oldLineNumber)
        assertNull(lines[1].newLineNumber)

        assertEquals(GitDiffLineType.ADDITION, lines[2].type)
        assertEquals("val y = 20", lines[2].text)
        assertNull(lines[2].oldLineNumber)
        assertEquals(11, lines[2].newLineNumber)

        assertEquals(GitDiffLineType.ADDITION, lines[3].type)
        assertEquals("val z = 30", lines[3].text)
        assertEquals(12, lines[3].newLineNumber)

        assertEquals(GitDiffLineType.CONTEXT, lines[4].type)
        assertEquals("fun run() {}", lines[4].text)
    }

    @Test
    fun parseNewFileDiff() {
        val rawDiff = """
            diff --git a/README.md b/README.md
            new file mode 100644
            index 0000000..abcdef1
            --- /dev/null
            +++ b/README.md
            @@ -0,0 +1,2 @@
            +# Conzyx
            +Autonomous development suite.
        """.trimIndent()

        val result = GitDiffParser.parse(rawDiff)
        assertEquals(1, result.size)

        val fileDiff = result[0]
        assertTrue(fileDiff.isNewFile)
        assertFalse(fileDiff.isDeletedFile)
        assertEquals("README.md", fileDiff.newPath)
        assertEquals(2, fileDiff.additions)
        assertEquals(0, fileDiff.deletions)
        assertEquals(2, fileDiff.hunks[0].lines.size)
    }

    @Test
    fun parseDeletedFileDiff() {
        val rawDiff = """
            diff --git a/old_file.txt b/old_file.txt
            deleted file mode 100644
            index abcdef1..0000000
            --- a/old_file.txt
            +++ /dev/null
            @@ -1,1 +0,0 @@
            -obsolete content
        """.trimIndent()

        val result = GitDiffParser.parse(rawDiff)
        assertEquals(1, result.size)

        val fileDiff = result[0]
        assertTrue(fileDiff.isDeletedFile)
        assertFalse(fileDiff.isNewFile)
        assertEquals("old_file.txt", fileDiff.oldPath)
        assertEquals(0, fileDiff.additions)
        assertEquals(1, fileDiff.deletions)
    }

    @Test
    fun parseBinaryFileDiff() {
        val rawDiff = """
            diff --git a/logo.png b/logo.png
            index 1234567..89abcde 100644
            Binary files a/logo.png and b/logo.png differ
        """.trimIndent()

        val result = GitDiffParser.parse(rawDiff)
        assertEquals(1, result.size)

        val fileDiff = result[0]
        assertTrue(fileDiff.isBinary)
        assertEquals("logo.png", fileDiff.oldPath)
        assertEquals("logo.png", fileDiff.newPath)
        assertEquals(0, fileDiff.hunks.size)
    }

    @Test
    fun parseMultipleFilesAndEmptyContent() {
        val emptyResult = GitDiffParser.parse("")
        assertTrue(emptyResult.isEmpty())

        val multiDiff = """
            diff --git a/file1.txt b/file1.txt
            --- a/file1.txt
            +++ b/file1.txt
            @@ -1,1 +1,1 @@
            -a
            +b
            diff --git a/file2.txt b/file2.txt
            --- a/file2.txt
            +++ b/file2.txt
            @@ -1,1 +1,1 @@
            -c
            +d
        """.trimIndent()

        val multiResult = GitDiffParser.parse(multiDiff)
        assertEquals(2, multiResult.size)
        assertEquals("file1.txt", multiResult[0].newPath)
        assertEquals("file2.txt", multiResult[1].newPath)
    }
}
