package com.vayro.app.workspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Milestone 2 — tests for replace preview, apply and undo.
 */
class GlobalSearchReplaceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceDir: File

    @Before
    fun setUp() {
        workspaceDir = tempFolder.newFolder("replace_workspace")
        File(workspaceDir, "src").mkdirs()
        File(workspaceDir, "src/Main.kt").writeText(
            """
            package com.example

            class Engine {
                fun startEngine() {
                    println("engine on")
                }
            }
            """.trimIndent(),
        )
        File(workspaceDir, "src/Other.kt").writeText(
            """
            package com.example

            // engine comment
            val engine = Engine()
            """.trimIndent(),
        )
        // Ignored directory must never be touched.
        File(workspaceDir, "build").mkdirs()
        File(workspaceDir, "build/Gen.kt").writeText("class engine { }")
    }

    @Test
    fun previewDoesNotModifyFiles() {
        val preview = GlobalSearchEngine.previewReplace(
            workspaceDir, query = "engine", replacement = "motor",
        )
        assertTrue(preview.totalMatches > 0)
        assertEquals(2, preview.totalFiles)
        // Files untouched.
        assertTrue(File(workspaceDir, "src/Main.kt").readText().contains("class Engine"))
        assertTrue(File(workspaceDir, "src/Other.kt").readText().contains("val engine = Engine()"))
    }

    @Test
    fun previewRespectsIgnoredDirectories() {
        val preview = GlobalSearchEngine.previewReplace(
            workspaceDir, query = "engine", replacement = "motor",
        )
        assertFalse(preview.filePlans.any { it.filePath.startsWith("build/") })
    }

    @Test
    fun previewLinesShowBeforeAndAfter() {
        val preview = GlobalSearchEngine.previewReplace(
            workspaceDir, query = "engine", replacement = "motor",
        )
        val plan = preview.filePlans.first { it.filePath == "src/Other.kt" }
        assertTrue(plan.previewLines.isNotEmpty())
        val line = plan.previewLines.first { it.before.contains("engine") }
        assertTrue(line.after.contains("motor"))
        assertTrue(line.lineNumber >= 1) // line numbers are 1-based
    }

    @Test
    fun applyThenUndoRestoresOriginals() {
        val beforeMain = File(workspaceDir, "src/Main.kt").readText()
        val beforeOther = File(workspaceDir, "src/Other.kt").readText()

        val preview = GlobalSearchEngine.previewReplace(
            workspaceDir, query = "engine", replacement = "motor",
        )
        assertTrue(preview.undoAvailable)
        val tx = GlobalSearchEngine.applyReplacePlan(workspaceDir, preview)
        assertEquals(2, tx.filesChanged)
        assertTrue(tx.matchesReplaced > 0)
        assertTrue(File(workspaceDir, "src/Main.kt").readText().contains("motor"))

        val restored = GlobalSearchEngine.revertReplace(workspaceDir, tx)
        assertEquals(2, restored)
        assertEquals(beforeMain, File(workspaceDir, "src/Main.kt").readText())
        assertEquals(beforeOther, File(workspaceDir, "src/Other.kt").readText())
    }

    @Test
    fun applyWithIncludeFilesOnlyTouchesSelected() {
        val preview = GlobalSearchEngine.previewReplace(
            workspaceDir, query = "engine", replacement = "motor",
        )
        val tx = GlobalSearchEngine.applyReplacePlan(
            workspaceDir, preview, includeFiles = setOf("src/Main.kt"),
        )
        assertEquals(1, tx.filesChanged)
        // Case-insensitive match: "class Engine" became "class motor".
        assertTrue(File(workspaceDir, "src/Main.kt").readText().contains("class motor"))
        assertTrue(File(workspaceDir, "src/Other.kt").readText().contains("val engine = Engine()"))

        // Undo restores only the applied file.
        val restored = GlobalSearchEngine.revertReplace(workspaceDir, tx)
        assertEquals(1, restored)
        assertTrue(File(workspaceDir, "src/Other.kt").readText().contains("val engine = Engine()"))
    }

    @Test
    fun previewWithNoMatchesIsEmpty() {
        val preview = GlobalSearchEngine.previewReplace(
            workspaceDir, query = "zzz_no_such_token", replacement = "x",
        )
        assertEquals(0, preview.totalMatches)
        assertEquals(0, preview.totalFiles)
        assertTrue(preview.undoAvailable)
    }

    @Test
    fun wholeWordReplaceDoesNotTouchSubstrings() {
        File(workspaceDir, "src/Words.kt").writeText("engine engines reengineered\n")
        val preview = GlobalSearchEngine.previewReplace(
            workspaceDir, query = "engine", replacement = "motor", wholeWord = true,
        )
        val plan = preview.filePlans.firstOrNull { it.filePath == "src/Words.kt" }
        assertEquals(1, plan?.matchCount)
        val tx = GlobalSearchEngine.applyReplacePlan(workspaceDir, preview)
        assertTrue(tx.matchesReplaced >= 1)
        val content = File(workspaceDir, "src/Words.kt").readText()
        assertTrue(content.contains("motor engines reengineered"))
    }

    @Test
    fun revertWithUndoUnavailableChangesNothing() {
        val preview = GlobalSearchEngine.previewReplace(
            workspaceDir, query = "engine", replacement = "motor",
        ).copy(undoAvailable = false)
        val tx = GlobalSearchEngine.applyReplacePlan(workspaceDir, preview)
        val restored = GlobalSearchEngine.revertReplace(workspaceDir, tx)
        assertEquals(0, restored)
        // Applied content stays.
        assertTrue(File(workspaceDir, "src/Main.kt").readText().contains("motor"))
    }
}
