package com.vayro.app.editor

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorBufferManagerTest {

    @Test
    fun openBufferSeedsCleanBuffer() {
        val manager = EditorBufferManager()
        val buffer = manager.openBuffer("src/Main.kt", "fun main() {}")
        assertFalse(buffer.isDirty)
        assertEquals("fun main() {}", buffer.currentContent)
        assertEquals("fun main() {}", buffer.savedContent)
    }

    @Test
    fun updateContentMarksDirty() {
        val manager = EditorBufferManager()
        manager.openBuffer("src/Main.kt", "fun main() {}")
        manager.updateContent("src/Main.kt", "fun main() { println() }")
        assertTrue(manager.isDirty("src/Main.kt"))
        assertEquals("fun main() { println() }", manager.getBuffer("src/Main.kt")?.currentContent)
        // Saved content unchanged until markSaved.
        assertEquals("fun main() {}", manager.getBuffer("src/Main.kt")?.savedContent)
    }

    @Test
    fun markSavedClearsDirty() {
        val manager = EditorBufferManager()
        manager.openBuffer("a.txt", "one")
        manager.updateContent("a.txt", "two")
        assertTrue(manager.isDirty("a.txt"))
        manager.markSaved("a.txt", "two")
        assertFalse(manager.isDirty("a.txt"))
    }

    @Test
    fun updateContentWithSameTextIsNoOp() {
        val manager = EditorBufferManager()
        manager.openBuffer("a.txt", "one")
        manager.updateContent("a.txt", "one")
        assertFalse(manager.isDirty("a.txt"))
    }

    @Test
    fun syncFromDiskNeverClobbersDirtyBuffer() {
        val manager = EditorBufferManager()
        manager.openBuffer("a.txt", "one")
        manager.updateContent("a.txt", "two (unsaved)")
        manager.syncFromDisk("a.txt", "one-changed-on-disk")
        assertEquals("two (unsaved)", manager.getBuffer("a.txt")?.currentContent)
        assertTrue(manager.isDirty("a.txt"))
    }

    @Test
    fun syncFromDiskUpdatesCleanBuffer() {
        val manager = EditorBufferManager()
        manager.openBuffer("a.txt", "one")
        manager.syncFromDisk("a.txt", "one-changed-on-disk")
        assertEquals("one-changed-on-disk", manager.getBuffer("a.txt")?.currentContent)
        assertFalse(manager.isDirty("a.txt"))
    }

    @Test
    fun viewStateIsClampedToContentLength() {
        val manager = EditorBufferManager()
        manager.openBuffer("a.txt", "12345")
        manager.updateViewState("a.txt", EditorViewState(cursorOffset = 999, selectionEnd = 888, scrollLine = 3))
        val viewState = manager.getBuffer("a.txt")?.viewState
        assertEquals(5, viewState?.cursorOffset)
        assertEquals(5, viewState?.selectionEnd)
        assertEquals(3, viewState?.scrollLine)
    }

    @Test
    fun dirtyBuffersReturnsOnlyDirty() {
        val manager = EditorBufferManager()
        manager.openBuffer("a.txt", "one")
        manager.openBuffer("b.txt", "two")
        manager.updateContent("b.txt", "two edited")
        val dirty = manager.dirtyBuffers()
        assertEquals(1, dirty.size)
        assertEquals("b.txt", dirty.first().path)
    }

    @Test
    fun closeBufferRemovesIt() {
        val manager = EditorBufferManager()
        manager.openBuffer("a.txt", "one")
        manager.closeBuffer("a.txt")
        assertNull(manager.getBuffer("a.txt"))
    }

    @Test
    fun restoreViewStateDoesNotClampPlaceholder() {
        val manager = EditorBufferManager()
        // Session restore for a clean tab: placeholder buffer, real cursor.
        manager.restoreViewState("a.txt", EditorViewState(cursorOffset = 42, scrollLine = 10))
        assertEquals(42, manager.getBuffer("a.txt")?.viewState?.cursorOffset)
        // When the real file loads, the view state clamps to content length.
        manager.syncFromDisk("a.txt", "short")
        assertEquals(5, manager.getBuffer("a.txt")?.viewState?.cursorOffset)
        assertEquals(10, manager.getBuffer("a.txt")?.viewState?.scrollLine)
    }

    @Test
    fun sessionStoreRoundTripPreservesDirtyBuffersAndViewState() {
        val dir = Files.createTempDirectory("editor-session-test").toFile()
        try {
            val store = EditorSessionStore(dir)
            val session = EditorSession(
                projectId = "proj-1",
                tabs = listOf("a.txt", "b.txt"),
                activePath = "b.txt",
                buffers = listOf(
                    EditorBuffer(
                        path = "b.txt",
                        currentContent = "unsaved edits here",
                        savedContent = "original",
                        viewState = EditorViewState(cursorOffset = 7, selectionEnd = 10, scrollLine = 4),
                    ),
                ),
                viewStates = mapOf(
                    "a.txt" to EditorViewState(cursorOffset = 3, scrollLine = 12),
                    "b.txt" to EditorViewState(cursorOffset = 7, selectionEnd = 10, scrollLine = 4),
                ),
                splitState = EditorSplitState(secondaryPath = "a.txt", vertical = false),
            )
            store.saveSession(session)

            val loaded = store.loadSession("proj-1")
            assertEquals(listOf("a.txt", "b.txt"), loaded?.tabs)
            assertEquals("b.txt", loaded?.activePath)
            assertEquals(1, loaded?.buffers?.size)
            val buffer = loaded?.buffers?.first()
            assertEquals("b.txt", buffer?.path)
            assertEquals("unsaved edits here", buffer?.currentContent)
            assertEquals("original", buffer?.savedContent)
            assertTrue(buffer?.isDirty == true)
            assertEquals(7, buffer?.viewState?.cursorOffset)
            assertEquals(10, buffer?.viewState?.selectionEnd)
            assertEquals(4, buffer?.viewState?.scrollLine)
            assertEquals("a.txt", loaded?.splitState?.secondaryPath)
            assertFalse(loaded?.splitState?.vertical == true)
            // View states for all tabs (dirty or clean) round-trip.
            assertEquals(3, loaded?.viewStates?.get("a.txt")?.cursorOffset)
            assertEquals(12, loaded?.viewStates?.get("a.txt")?.scrollLine)
            assertEquals(7, loaded?.viewStates?.get("b.txt")?.cursorOffset)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sessionStoreReturnsNullWhenMissing() {
        val dir = Files.createTempDirectory("editor-session-test").toFile()
        try {
            val store = EditorSessionStore(dir)
            assertNull(store.loadSession("no-such-project"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sessionStoreReturnsNullOnCorruptFile() {
        val dir = Files.createTempDirectory("editor-session-test").toFile()
        try {
            File(dir, "projx.json").writeText("{ this is not json")
            val store = EditorSessionStore(dir)
            assertNull(store.loadSession("projx"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sessionStoreClearRemovesSession() {
        val dir = Files.createTempDirectory("editor-session-test").toFile()
        try {
            val store = EditorSessionStore(dir)
            store.saveSession(EditorSession(projectId = "p1", tabs = listOf("a.txt")))
            store.clearSession("p1")
            assertNull(store.loadSession("p1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sessionStoreSanitizesProjectIdForFilename() {
        val dir = Files.createTempDirectory("editor-session-test").toFile()
        try {
            val store = EditorSessionStore(dir)
            store.saveSession(EditorSession(projectId = "../../evil", tabs = listOf("a.txt")))
            // Must not escape the sessions dir.
            assertTrue(dir.listFiles()?.all { it.parentFile == dir } == true)
            assertEquals(listOf("a.txt"), store.loadSession("../../evil")?.tabs)
        } finally {
            dir.deleteRecursively()
        }
    }
}
