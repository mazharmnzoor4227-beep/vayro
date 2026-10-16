package com.vayro.app.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectIndexTest {
    @Test
    fun buildIndexesSymbolsAndImportsAndRanksMatchingFiles() {
        val temp = Files.createTempDirectory("mobile-harness-index").toFile()
        try {
            File(temp, "src").mkdirs()
            File(temp, "src/Main.kt").writeText(
                """
                package demo
                import demo.Helper
                class Main
                fun runTask() = Helper()
                """.trimIndent(),
            )
            File(temp, "src/Helper.kt").writeText(
                """
                package demo
                class Helper
                """.trimIndent(),
            )
            File(temp, ".git").mkdirs()
            File(temp, ".git/config").writeText("internal")

            val index = ProjectIndex(temp)
            val snapshot = index.build("project", temp)
            val main = snapshot.files.first { it.path == "src/Main.kt" }

            assertEquals("Kotlin", main.language)
            assertTrue(main.symbols.contains("Main"))
            assertTrue(main.symbols.contains("runTask"))
            assertTrue(main.imports.contains("demo.Helper"))
            assertEquals("src/Main.kt", index.search(snapshot, "runTask", 1).single().path)
            assertTrue(snapshot.files.none { it.path.startsWith(".git/") })
        } finally {
            temp.deleteRecursively()
        }
    }
}
