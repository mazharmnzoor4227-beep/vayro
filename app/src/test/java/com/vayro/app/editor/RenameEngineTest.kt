package com.vayro.app.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Milestone 2 — tests for the text-based rename workflow.
 */
class RenameEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceDir: File

    @Before
    fun setUp() {
        workspaceDir = tempFolder.newFolder("rename_workspace")
        File(workspaceDir, "src").mkdirs()
        File(workspaceDir, "src/Api.kt").writeText(
            """
            package com.example

            class ApiClient {
                fun fetchData(): String {
                    val client = ApiClient()
                    return client.toString()
                }
            }
            """.trimIndent(),
        )
        File(workspaceDir, "src/Use.kt").writeText(
            """
            package com.example

            fun use() {
                val api = ApiClient()
                println(api)
            }
            """.trimIndent(),
        )
    }

    @Test
    fun planRenameFindsWholeWordMatchesOnly() {
        val preview = RenameEngine.planRename(workspaceDir, "ApiClient", "HttpClient")
        assertNull(preview.note)
        assertEquals(2, preview.totalFiles)
        // "ApiClient" appears 3 times: declaration + 2 usages. "api" (lowercase) untouched.
        assertEquals(3, preview.totalMatches)
        assertTrue(preview.undoAvailable)
    }

    @Test
    fun applyAndUndoRename() {
        val before = File(workspaceDir, "src/Api.kt").readText()
        val preview = RenameEngine.planRename(workspaceDir, "ApiClient", "HttpClient")
        val tx = RenameEngine.applyRename(workspaceDir, preview)
        assertEquals(2, tx.filesChanged)
        val after = File(workspaceDir, "src/Api.kt").readText()
        assertTrue(after.contains("class HttpClient"))
        assertTrue(after.contains("val client = HttpClient()"))
        // Lowercase "api" variable untouched (case-sensitive).
        assertTrue(File(workspaceDir, "src/Use.kt").readText().contains("val api = HttpClient()"))

        val restored = RenameEngine.undoRename(workspaceDir, tx)
        assertEquals(2, restored)
        assertEquals(before, File(workspaceDir, "src/Api.kt").readText())
    }

    @Test
    fun blankNewNameProducesNoteAndNoPlans() {
        val preview = RenameEngine.planRename(workspaceDir, "ApiClient", "")
        assertNotNull(preview.note)
        assertEquals(0, preview.totalFiles)
    }

    @Test
    fun identicalNamesProduceNoteAndNoPlans() {
        val preview = RenameEngine.planRename(workspaceDir, "ApiClient", "ApiClient")
        assertNotNull(preview.note)
        assertEquals(0, preview.totalFiles)
    }

    @Test
    fun invalidIdentifierProducesNoteAndNoPlans() {
        val preview = RenameEngine.planRename(workspaceDir, "ApiClient", "my-client!")
        assertNotNull(preview.note)
        assertEquals(0, preview.totalFiles)
    }

    @Test
    fun renameDoesNotMatchSubstrings() {
        File(workspaceDir, "src/Sub.kt").writeText("val x = ApiClientFactory()\n")
        val preview = RenameEngine.planRename(workspaceDir, "ApiClient", "HttpClient")
        assertTrue(preview.filePlans.none { it.filePath == "src/Sub.kt" })
    }
}
