package com.vayro.app.editor

import com.vayro.app.workspace.FileReplacePlan
import com.vayro.app.workspace.GlobalSearchEngine
import com.vayro.app.workspace.ReplacePreview
import com.vayro.app.workspace.ReplaceTransaction
import java.io.File

/**
 * Milestone 2 — correct cross-project rename workflow.
 *
 * Text-based rename with whole-word, case-sensitive matching, a preview of
 * every affected file, per-file selection and reliable undo. This is
 * explicitly a TEXT rename, not a compiler-accurate semantic rename: it
 * cannot distinguish a local variable from a same-named symbol in another
 * scope, and it does not understand language grammar. The preview exists
 * precisely so the user can review every change before applying it.
 * Compiler-accurate rename arrives with Milestone 3's real language
 * providers ([com.vayro.app.lsp.LanguageIntelligenceProvider]).
 */
object RenameEngine {

    /** Label shown in the UI so the heuristic nature is never hidden. */
    const val PROVIDER_LABEL = "Text-based rename (not compiler-accurate)"

    private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /**
     * Plans a rename without touching any file. Returns a preview with a
     * [ReplacePreview.note] explaining why nothing was planned when the input
     * is invalid (blank names, identical names, invalid identifier).
     */
    fun planRename(
        workspaceDir: File,
        oldName: String,
        newName: String,
        fileFilter: String? = null,
    ): ReplacePreview {
        val note = validateNames(oldName, newName)
        if (note != null) {
            return ReplacePreview(
                query = oldName,
                replacement = newName,
                filePlans = emptyList(),
                totalMatches = 0,
                undoAvailable = true,
                note = note,
            )
        }
        return GlobalSearchEngine.previewReplace(
            workspaceDir = workspaceDir,
            query = oldName,
            replacement = newName,
            isRegex = false,
            matchCase = true,
            wholeWord = true,
            fileFilter = fileFilter,
            note = null,
        )
    }

    /**
     * Applies a rename plan (optionally limited to [includeFiles]).
     * See [GlobalSearchEngine.applyReplacePlan].
     */
    fun applyRename(
        workspaceDir: File,
        preview: ReplacePreview,
        includeFiles: Set<String>? = null,
    ): ReplaceTransaction =
        GlobalSearchEngine.applyReplacePlan(workspaceDir, preview, includeFiles)

    /**
     * Undoes a rename transaction. See [GlobalSearchEngine.revertReplace].
     */
    fun undoRename(workspaceDir: File, transaction: ReplaceTransaction): Int =
        GlobalSearchEngine.revertReplace(workspaceDir, transaction)

    private fun validateNames(oldName: String, newName: String): String? {
        if (oldName.isBlank()) return "Nothing to rename: the current name is blank."
        if (newName.isBlank()) return "Nothing to rename: the new name is blank."
        if (oldName == newName) return "Nothing to rename: the names are identical."
        if (!IDENTIFIER.matches(newName)) {
            return "“$newName” is not a valid identifier (letters, digits and underscore; must not start with a digit)."
        }
        return null
    }
}
