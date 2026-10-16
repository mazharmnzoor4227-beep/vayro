package com.vayro.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GlobalSearchEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceDir: File

    @Before
    fun setUp() {
        workspaceDir = tempFolder.newFolder("search_workspace")
        File(workspaceDir, "src").mkdirs()
        File(workspaceDir, "src/Main.kt").writeText(
            """
            package com.example
            
            class Engine {
                fun startEngine() {
                    println("Starting engine...")
                }
            }
            """.trimIndent()
        )
        File(workspaceDir, "src/Utils.kt").writeText(
            """
            package com.example
            
            val ENGINE_VERSION = "2.0.0"
            """.trimIndent()
        )
        File(workspaceDir, "readme.md").writeText(
            """
            # Project
            This project uses the high-performance Engine.
            """.trimIndent()
        )
    }

    @Test
    fun `search finds matches across all project files case-insensitively`() {
        val result = GlobalSearchEngine.search(workspaceDir, "engine")
        assertTrue(result.matches.isNotEmpty())
        assertEquals(3, result.matches.map { it.filePath }.distinct().size)
    }

    @Test
    fun `search with matchCase respects casing`() {
        val caseSensitive = GlobalSearchEngine.search(workspaceDir, "ENGINE_VERSION", matchCase = true)
        assertEquals(1, caseSensitive.matches.size)
        assertEquals("src/Utils.kt", caseSensitive.matches.first().filePath)
    }

    @Test
    fun `search with wholeWord matches exact token only`() {
        val wholeWord = GlobalSearchEngine.search(workspaceDir, "Engine", matchCase = true, wholeWord = true)
        assertTrue(wholeWord.matches.any { it.filePath == "src/Main.kt" && it.lineNumber == 3 })
        // Shouldn't match startEngine because it's part of a compound word
        assertFalse(wholeWord.matches.any { it.lineContent.contains("fun startEngine") })
    }

    @Test
    fun `search with fileFilter only searches matching patterns`() {
        val ktOnly = GlobalSearchEngine.search(workspaceDir, "Engine", fileFilter = "*.kt")
        assertTrue(ktOnly.matches.all { it.filePath.endsWith(".kt") })
        assertFalse(ktOnly.matches.any { it.filePath.endsWith(".md") })
    }

    @Test
    fun `replaceAll performs atomic replacements across files`() {
        val replacedCount = GlobalSearchEngine.replaceAll(
            workspaceDir = workspaceDir,
            query = "ENGINE_VERSION",
            replacement = "SYSTEM_VERSION",
            matchCase = true,
        )
        assertEquals(1, replacedCount)
        val utilsContent = File(workspaceDir, "src/Utils.kt").readText()
        assertTrue(utilsContent.contains("SYSTEM_VERSION = \"2.0.0\""))
        assertFalse(utilsContent.contains("ENGINE_VERSION"))
    }
}
