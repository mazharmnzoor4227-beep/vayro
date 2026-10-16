package com.vayro.app.context

import com.vayro.app.lsp.ParserProjectIndex
import com.vayro.app.runtime.DependencyGraph
import com.vayro.app.runtime.IndexedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 4 — unified ranked pipeline tests.
 */
class RankedContextPipelineTest {

    private fun indexedFile(path: String, symbols: List<String> = emptyList(), imports: List<String> = emptyList()) =
        IndexedFile(path = path, language = "Kotlin", sizeBytes = 100, lastModified = 1L, symbols = symbols, imports = imports)

    @Test
    fun `pipeline ranks explicit mention first and traces every signal`() {
        val files = listOf(
            indexedFile("src/Main.kt", symbols = listOf("main")),
            indexedFile("src/Util.kt", symbols = listOf("helper")),
            indexedFile("src/Unrelated.kt"),
        )
        val result = RankedContextPipeline.run(
            PipelineInput(
                files = files,
                request = "Fix the crash in Main",
                mentionedPaths = listOf("src/Main.kt"),
            )
        )
        assertTrue(result.ranked.isNotEmpty())
        assertEquals("src/Main.kt", result.ranked.first().file.path)
        assertTrue(result.trace.signalsUsed.contains(TraceSignal.EXPLICIT_MENTION))
        assertTrue(result.trace.includedPaths().contains("src/Main.kt"))
        assertTrue(result.trace.excludedPaths().contains("src/Unrelated.kt"))
        assertFalse(result.parserBacked)
    }

    @Test
    fun `pipeline merges real parser dependency edges over heuristic ones`() {
        val parserIndex = ParserProjectIndex()
        // Index the dependency target FIRST: import resolution only links to
        // files already present in the index at relink time.
        parserIndex.indexFile(
            "src/B.kt",
            "package com.example\nclass B { fun run() {} }",
            "kotlin",
        )
        parserIndex.indexFile(
            "src/A.kt",
            "import com.example.B\nclass A { fun use() { B().run() } }",
            "kotlin",
        )

        val heuristic = DependencyGraph(
            imports = mapOf("src/A.kt" to listOf("src/WRONG.kt")),
            dependents = mapOf("src/WRONG.kt" to listOf("src/A.kt")),
        )

        val merged = ParserIndexAdapters.mergeDependencyGraphs(
            ParserIndexAdapters.toDependencyGraph(parserIndex),
            heuristic,
        )
        // Parser edges win: src/A.kt must NOT point at the heuristic's wrong target.
        assertFalse(merged.imports["src/A.kt"]?.contains("src/WRONG.kt") == true)
        // Heuristic-only files still fill gaps.
        assertTrue(merged.imports.containsKey("src/WRONG.kt") || merged.dependents.containsKey("src/WRONG.kt"))

        val files = listOf(
            indexedFile("src/A.kt"),
            indexedFile("src/B.kt"),
            indexedFile("src/WRONG.kt"),
        )
        val result = RankedContextPipeline.run(
            PipelineInput(
                files = files,
                request = "change class B behaviour",
                mentionedPaths = listOf("src/B.kt"),
                dependencyGraph = heuristic,
                parserProjectIndex = parserIndex,
            )
        )
        assertTrue(result.parserBacked)
        val rankedPaths = result.ranked.map { it.file.path }
        // A.kt is a real dependent of B.kt via the parser graph, so it outranks the heuristic-only file.
        assertTrue(rankedPaths.contains("src/A.kt"))
        val aPos = rankedPaths.indexOf("src/A.kt")
        val wrongPos = rankedPaths.indexOf("src/WRONG.kt")
        assertTrue(aPos < wrongPos || wrongPos == -1)
    }

    @Test
    fun `error context targets rank above unrelated files`() {
        val files = listOf(
            indexedFile("src/AuthService.kt", symbols = listOf("login")),
            indexedFile("src/Unrelated.kt"),
        )
        val request = "Fix crash:\njava.lang.NullPointerException\n\tat com.example.AuthService.login(AuthService.kt:5)"
        val result = RankedContextPipeline.run(
            PipelineInput(files = files, request = request)
        )
        assertEquals("src/AuthService.kt", result.ranked.first().file.path)
        assertTrue(result.trace.signalsUsed.contains(TraceSignal.ERROR_TARGET))
    }
}
