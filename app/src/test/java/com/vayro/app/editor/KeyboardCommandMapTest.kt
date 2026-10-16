package com.vayro.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardCommandMapTest {

    @Test
    fun defaultMapHasNoShortcutConflicts() {
        val conflicts = KeyboardCommandMap.findConflicts()
        assertTrue(
            "Conflicting shortcuts: ${conflicts.map { it.map { c -> c.id } }}",
            conflicts.isEmpty(),
        )
    }

    @Test
    fun everyCommandHasIdTitleAndCategory() {
        for (command in KeyboardCommandMap.defaultCommands()) {
            assertTrue(command.id.isNotBlank())
            assertTrue(command.title.isNotBlank())
            assertTrue(command.category.isNotBlank())
        }
    }

    @Test
    fun coreCommandsExistWithShortcuts() {
        val ids = listOf(
            "editor.save",
            "editor.findInFile",
            "editor.findInProject",
            "editor.goToLine",
            "editor.commandPalette",
            "terminal.new",
            "terminal.toggle",
        )
        for (id in ids) {
            val command = KeyboardCommandMap.byId(id)
            assertNotNull("$id missing", command)
            assertNotNull("$id has no shortcut", command?.shortcut)
        }
    }

    @Test
    fun shortcutDisplayNames() {
        val shortcut = KeyShortcut(ctrlOrCmd = true, shift = true, key = "P")
        assertEquals("Ctrl+Shift+P", shortcut.displayName())
        assertEquals("⌘⇧P", shortcut.displayName(useMacSymbols = true))
    }

    @Test
    fun commandsWithoutShortcutsAreStillPaletteAccessible() {
        val noShortcut = KeyboardCommandMap.defaultCommands().filter { it.shortcut == null }
        assertTrue(noShortcut.isNotEmpty())
        // Every command without a shortcut must be touch-accessible (palette/menu).
        assertTrue(noShortcut.all { it.touchAccessible })
    }
}
