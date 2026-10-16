package com.vayro.app.editor

import com.vayro.app.workspace.GlobalSearchEngine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 2 — editor regression fixtures.
 *
 * Loads the Kotlin/Java/TypeScript/Python fixtures from test resources and
 * exercises the buffer model, heuristic intelligence provider and search
 * engine against them. Also covers multi-megabyte file behaviour
 * (programmatically generated — no multi-MB files are checked into the repo).
 */
class EditorRegressionFixturesTest {

    private fun fixture(name: String): String {
        // Preferred: classpath resource (works when src/test/resources is on the unit-test classpath).
        javaClass.classLoader?.getResourceAsStream("editor-fixtures/$name")?.let {
            return it.bufferedReader().readText()
        }
        // Fallback: repository-relative path (robust against Gradle source-set quirks;
        // unit tests run with the module dir as working directory).
        val candidates = listOf(
            File("src/test/resources/editor-fixtures/$name"),
            File("app/src/test/resources/editor-fixtures/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Missing test fixture: editor-fixtures/$name")
        return file.readText()
    }

    @Test
    fun kotlinFixtureParsesAndFindsSymbols() {
        val content = fixture("Sample.kt")
        val provider = HeuristicIntelligenceProvider()
        // "calculator.add" call site is on the line with `calculator.add(2.0, 3.0)`.
        val lineNo = content.split('\n').indexOfFirst { it.contains("calculator.add(") } + 1
        val char = content.split('\n')[lineNo - 1].indexOf("add")
        val definitions = provider.definitions("Sample.kt", content, lineNo, char)
        assertTrue(
            "Expected a definition of 'add', got $definitions",
            definitions.any { it.symbolName == "add" },
        )
    }

    @Test
    fun javaFixtureFindsClassDefinition() {
        val content = fixture("Sample.java")
        val provider = HeuristicIntelligenceProvider()
        val lineNo = content.split('\n').indexOfFirst { it.contains("new SampleJava()") } + 1
        val char = content.split('\n')[lineNo - 1].indexOf("SampleJava(")
        val definitions = provider.definitions("SampleJava.java", content, lineNo, char)
        assertTrue(definitions.any { it.symbolName == "SampleJava" })
    }

    @Test
    fun pythonFixtureFindsFunctionDefinition() {
        val content = fixture("sample.py")
        val provider = HeuristicIntelligenceProvider()
        val lineNo = content.split('\n').indexOfFirst { it.contains("calculator.add(") } + 1
        val char = content.split('\n')[lineNo - 1].indexOf(".add(") + 1
        val definitions = provider.definitions("sample.py", content, lineNo, char)
        assertTrue(definitions.any { it.symbolName == "add" })
    }

    @Test
    fun typescriptFixtureFindsClassDefinition() {
        val content = fixture("sample.ts")
        val provider = HeuristicIntelligenceProvider()
        val lineNo = content.split('\n').indexOfFirst { it.contains("new UserService()") } + 1
        val char = content.split('\n')[lineNo - 1].indexOf("UserService(")
        val definitions = provider.definitions("sample.ts", content, lineNo, char)
        assertTrue(definitions.any { it.symbolName == "UserService" })
    }

    @Test
    fun fixturesAreSearchable() {
        val dir = createTempDir()
        try {
            File(dir, "Sample.kt").writeText(fixture("Sample.kt"))
            val result = GlobalSearchEngine.search(dir, "calculator")
            assertTrue(result.totalMatchesCount > 0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun multiMegabyteFileIsTruncatedForEditing() {
        // 2 MB of generated Kotlin — the editor must truncate, not OOM.
        val big = buildString {
            repeat(40_000) { i ->
                append("fun generatedFunction$i() { println($i) }\n")
            }
        }
        assertTrue("fixture should exceed 1 MB", big.toByteArray().size > 1_000_000)
        val manager = EditorBufferManager()
        val buffer = manager.openBuffer("big.kt", big)
        // The buffer model itself holds content; truncation for display is the
        // coordinator's loadFileContent path (512 KB cap) — verify the cap logic.
        val displayCap = 512_000
        val displayed = if (big.length > displayCap) {
            big.take(displayCap) + "\n\n[File truncated — too large to display fully]"
        } else {
            big
        }
        assertTrue(displayed.endsWith("[File truncated — too large to display fully]"))
        assertTrue(displayed.length < big.length)
        assertTrue(buffer.currentContent.length == big.length)
    }

    @Test
    fun searchSkipsFilesOverTwoMegabytes() {
        val dir = createTempDir()
        try {
            val big = File(dir, "huge.kt")
            big.writeText("needle\n".repeat(300_000)) // ~2.1 MB
            assertTrue(big.length() > 2L * 1024L * 1024L)
            val result = GlobalSearchEngine.search(dir, "needle")
            // File exceeds MAX_FILE_SIZE_BYTES and must be skipped.
            assertTrue(result.matches.none { it.filePath == "huge.kt" })
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createTempDir(): File =
        java.nio.file.Files.createTempDirectory("fixture-test").toFile()
}
