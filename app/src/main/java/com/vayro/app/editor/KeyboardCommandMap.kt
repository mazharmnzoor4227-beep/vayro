package com.vayro.app.editor

/**
 * Milestone 2 — keyboard command map for the mobile IDE.
 *
 * Every core editor command is reachable by hardware keyboard (no touch
 * required) and is also listed in the command palette for discoverability.
 * Shortcuts use Ctrl on Android/Windows/Linux and Cmd on macOS/iOS; the
 * [KeyShortcut.displayName] renders the platform-appropriate label.
 *
 * UI note: the app renders these via the existing command palette and key
 * handlers; this map is the single source of truth for ids, titles and
 * shortcuts so palette, menus and tests cannot drift apart.
 */

/** A keyboard shortcut. [key] is a single character or a named key ("Enter", "F", "Delete", ...). */
data class KeyShortcut(
    val ctrlOrCmd: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val key: String,
) {
    /** Platform-appropriate label, e.g. "Ctrl+S" or "⌘S". */
    fun displayName(useMacSymbols: Boolean = false): String {
        val main = if (useMacSymbols && ctrlOrCmd) "⌘" else if (ctrlOrCmd) "Ctrl+" else ""
        val shiftPart = if (shift) (if (useMacSymbols) "⇧" else "Shift+") else ""
        val altPart = if (alt) (if (useMacSymbols) "⌥" else "Alt+") else ""
        return "$main$shiftPart$altPart$key"
    }
}

/** One invocable editor command. */
data class EditorCommand(
    /** Stable id, e.g. "editor.save". */
    val id: String,
    val title: String,
    val category: String,
    val shortcut: KeyShortcut?,
    /** True when the command is also reachable without a hardware keyboard. */
    val touchAccessible: Boolean = true,
)

object KeyboardCommandMap {

    fun defaultCommands(): List<EditorCommand> = listOf(
        EditorCommand("editor.save", "Save file", "File", KeyShortcut(ctrlOrCmd = true, key = "S")),
        EditorCommand("editor.closeTab", "Close tab", "File", KeyShortcut(ctrlOrCmd = true, key = "W")),
        EditorCommand("editor.closeOtherTabs", "Close other tabs", "File", null),
        EditorCommand("editor.closeAllTabs", "Close all tabs", "File", KeyShortcut(ctrlOrCmd = true, shift = true, key = "W")),
        EditorCommand("editor.findInFile", "Find in file", "Edit", KeyShortcut(ctrlOrCmd = true, key = "F")),
        EditorCommand("editor.replaceInFile", "Replace in file", "Edit", KeyShortcut(ctrlOrCmd = true, key = "H")),
        EditorCommand("editor.findInProject", "Find in project", "Edit", KeyShortcut(ctrlOrCmd = true, shift = true, key = "F")),
        EditorCommand("editor.undo", "Undo", "Edit", KeyShortcut(ctrlOrCmd = true, key = "Z")),
        EditorCommand("editor.redo", "Redo", "Edit", KeyShortcut(ctrlOrCmd = true, shift = true, key = "Z")),
        EditorCommand("editor.goToLine", "Go to line", "Navigate", KeyShortcut(ctrlOrCmd = true, key = "G")),
        EditorCommand("editor.goToDefinition", "Go to definition", "Navigate", KeyShortcut(ctrlOrCmd = true, key = "B")),
        EditorCommand("editor.findReferences", "Find references", "Navigate", KeyShortcut(shift = true, key = "F12")),
        EditorCommand("editor.renameSymbol", "Rename symbol", "Navigate", KeyShortcut(shift = true, key = "F6")),
        EditorCommand("editor.toggleOutline", "Toggle outline", "View", KeyShortcut(ctrlOrCmd = true, shift = true, key = "O")),
        EditorCommand("editor.toggleWordWrap", "Toggle word wrap", "View", KeyShortcut(alt = true, key = "Z")),
        EditorCommand("editor.splitEditor", "Split editor", "View", null),
        EditorCommand("editor.commandPalette", "Command palette", "View", KeyShortcut(ctrlOrCmd = true, shift = true, key = "P")),
        EditorCommand("terminal.new", "New terminal", "Terminal", KeyShortcut(ctrlOrCmd = true, key = "T")),
        EditorCommand("terminal.toggle", "Toggle terminal", "Terminal", KeyShortcut(ctrlOrCmd = true, key = "`")),
        EditorCommand("terminal.interrupt", "Interrupt process", "Terminal", KeyShortcut(ctrlOrCmd = true, key = "C")),
        EditorCommand("terminal.clear", "Clear terminal", "Terminal", KeyShortcut(ctrlOrCmd = true, key = "K")),
    )

    /**
     * Returns shortcut conflicts: groups of commands sharing the exact same
     * shortcut. Empty means the map is conflict-free.
     */
    fun findConflicts(commands: List<EditorCommand> = defaultCommands()): List<List<EditorCommand>> =
        commands
            .filter { it.shortcut != null }
            .groupBy { it.shortcut }
            .values
            .filter { it.size > 1 }

    fun byId(id: String, commands: List<EditorCommand> = defaultCommands()): EditorCommand? =
        commands.firstOrNull { it.id == id }
}
