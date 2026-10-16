package com.vayro.app.runtime

import com.vayro.app.model.ChatMessage
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBoundsTest {
    @Test
    fun tailReaderNeverLoadsEarlierOutput() {
        val directory = Files.createTempDirectory("mh-tail-test").toFile()
        try {
            val file = directory.resolve("output.log")
            file.writeText("earlier-output\nlatest-output")

            assertEquals("latest-output", file.readTailText("latest-output".length))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun conversationHistoryKeepsNewestTextWithinBudget() {
        val history = listOf(
            ChatMessage(fromUser = true, text = "old-message"),
            ChatMessage(fromUser = false, text = "middle-message"),
            ChatMessage(fromUser = true, text = "new-message"),
        )

        val bounded = history.recentWithinCharacterBudget(15)

        assertEquals(2, bounded.size)
        assertEquals("sage", bounded.first().text)
        assertEquals("new-message", bounded.last().text)
        assertEquals(15, bounded.sumOf { it.text.length })
    }

    @Test
    fun generatedDirectoriesAreExcludedFromSnapshots() {
        val directory = Files.createTempDirectory("mh-snapshot-test").toFile()
        try {
            val workspace = directory.resolve("workspaces/project").apply { mkdirs() }
            workspace.resolve("src/Main.kt").apply { parentFile?.mkdirs(); writeText("fun main() = Unit") }
            workspace.resolve("build/outputs/app.apk").apply { parentFile?.mkdirs(); writeText("generated") }
            workspace.resolve("node_modules/pkg/index.js").apply { parentFile?.mkdirs(); writeText("generated") }
            workspace.resolve(".git/index").apply { parentFile?.mkdirs(); writeText("metadata") }

            val snapshot = WorkspaceCheckpoints(directory).snapshot(workspace)

            assertEquals(setOf("src/Main.kt"), snapshot.keys)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun largeChangedFileGetsSafePreviewWithoutReadingItsContents() {
        val directory = Files.createTempDirectory("mh-diff-test").toFile()
        try {
            val checkpoints = WorkspaceCheckpoints(directory)
            val workspace = checkpoints.ensureWorkspace("project")
            val large = workspace.resolve("large.txt")
            large.outputStream().use { it.write(ByteArray(1_000_001) { 'a'.code.toByte() }) }
            checkpoints.createCheckpoint("project", workspace)
            large.appendText("changed")

            val change = checkpoints.buildChangeDetails("project", workspace, listOf("large.txt")).single()

            assertFalse(change.binary)
            assertTrue(change.diffLines.single().text.contains("too large to preview safely"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun truncateHeadAndTailCompressesLongOutput() {
        val longOutput = (1..100).joinToString("\n") { "line-$it" }
        val truncated = truncateHeadAndTail(longOutput, maxHeadLines = 5, maxTailLines = 5, maxTotalLines = 20)
        assertTrue(truncated.contains("line-1"))
        assertTrue(truncated.contains("line-5"))
        assertTrue(truncated.contains("... [output truncated: 90 lines omitted] ..."))
        assertTrue(truncated.contains("line-96"))
        assertTrue(truncated.contains("line-100"))
        assertFalse(truncated.contains("line-50"))
    }

    @Test
    fun compactHistoryCompactsOlderCodeBlocks() {
        val oldCode = "```kotlin\n" + (1..30).joinToString("\n") { "val x$it = $it" } + "\n```"
        val history = listOf(
            ChatMessage(fromUser = true, text = "Create class"),
            ChatMessage(fromUser = false, text = oldCode),
            ChatMessage(fromUser = true, text = "Add function"),
            ChatMessage(fromUser = false, text = "Immediate previous response"),
        )
        val compacted = compactHistoryForPrompt(history, maxCharacters = 10_000)
        assertEquals(4, compacted.size)
        assertTrue(compacted[1].text.contains("lines of code collapsed"))
        assertEquals("Immediate previous response", compacted[3].text)
    }
}
