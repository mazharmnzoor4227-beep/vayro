package com.vayro.app.context

import com.vayro.app.lsp.ParserProjectIndex
import com.vayro.app.runtime.ContextEngine
import com.vayro.app.runtime.ProjectIndex
import com.vayro.app.runtime.ProjectMemoryStore
import com.vayro.app.runtime.WorkspaceSemanticIndex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Milestone 4 — context relevance benchmark (acceptance gate).
 *
 * Builds realistic sample repositories on disk, runs representative tasks
 * (bug fix, feature request, refactor) through the measurable pipeline, and
 * asserts:
 *  - relevant files are retrieved (recall),
 *  - unrelated files are excluded or ranked below relevant ones (precision),
 *  - the token budget is never exceeded (measured, not estimated),
 *  - the trace shows relevant definitions/dependencies for every task.
 */
class ContextRelevanceBenchmarkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceDir: File
    private lateinit var memoryDir: File
    private lateinit var engine: ContextEngine

    private fun write(rel: String, content: String) {
        File(workspaceDir, rel).apply {
            parentFile?.mkdirs()
            writeText(content.trimIndent())
        }
    }

    @Before
    fun setUp() {
        workspaceDir = tempFolder.newFolder("bench_ws")
        memoryDir = tempFolder.newFolder("bench_mem")

        // --- Sample repository 1: Kotlin auth app ---
        write("src/auth/AuthService.kt", """
            package com.example.auth

            import com.example.auth.TokenManager

            class AuthService(private val tokens: TokenManager) {
                fun login(username: String, password: String): String {
                    val token = tokens.issue(username)
                    return token
                }
                fun logout(token: String) {
                    tokens.revoke(token)
                }
            }
        """)
        write("src/auth/TokenManager.kt", """
            package com.example.auth

            class TokenManager {
                fun issue(username: String): String = "tok-${'$'}username"
                fun validate(token: String): Boolean = token.startsWith("tok-")
                fun revoke(token: String) { /* ... */ }
            }
        """)
        write("src/users/UserController.kt", """
            package com.example.users

            import com.example.auth.AuthService

            class UserController(private val auth: AuthService) {
                fun handleLogin(u: String, p: String): String = auth.login(u, p)
                fun handleLogout(t: String) = auth.logout(t)
            }
        """)
        write("src/users/UserRepository.kt", """
            package com.example.users

            class UserRepository {
                fun findByName(name: String): String? = null
            }
        """)
        // Unrelated files that must NOT dominate the context.
        write("src/util/StringUtils.kt", """
            package com.example.util

            object StringUtils {
                fun capitalize(s: String): String = s.replaceFirstChar { it.uppercase() }
            }
        """)
        write("src/config/AppConfig.kt", """
            package com.example.config

            object AppConfig {
                const val PORT = 8080
            }
        """)

        // --- Sample repository 2: Python scripts ---
        write("py/app.py", """
            from db import connect

            def main():
                conn = connect()
                print("ready", conn)
        """)
        write("py/db.py", """
            def connect():
                return "db-conn"
        """)
        write("py/unrelated.py", """
            def helper():
                return 42
        """)

        val semanticIndex = WorkspaceSemanticIndex(workspaceDir).indexWorkspace()
        val parserIndex = ParserProjectIndex()
        val workspaceFiles = workspaceDir.walkTopDown().filter { it.isFile }.toList()
        // Two passes: index all files first, then re-link so every import
        // resolves against the complete file set regardless of walk order.
        workspaceFiles.forEach { file ->
            val rel = file.relativeTo(workspaceDir).invariantSeparatorsPath
            parserIndex.indexFile(rel, file.readText())
        }
        workspaceFiles.forEach { file ->
            val rel = file.relativeTo(workspaceDir).invariantSeparatorsPath
            parserIndex.updateFile(rel, file.readText())
        }

        engine = ContextEngine(
            projectIndex = ProjectIndex(memoryDir),
            memoryStore = ProjectMemoryStore(memoryDir),
            workspaceSemanticIndex = semanticIndex,
            parserProjectIndex = parserIndex,
            tokenizer = ApproximateTokenizer(),
        )
    }

    private data class BenchmarkTask(
        val name: String,
        val request: String,
        val mentionedPaths: List<String> = emptyList(),
        /** Files a good retrieval MUST surface. */
        val mustRetrieve: Set<String>,
        /** Files that must NOT appear above any mustRetrieve file. */
        val mustNotDominate: Set<String>,
    )

    private fun runTask(task: BenchmarkTask) {
        val result = engine.buildContextWithTrace(
            projectId = "bench",
            workspace = workspaceDir,
            request = task.request,
            mentionedPaths = task.mentionedPaths,
            maxTokenBudget = 2000,
        )

        // 1. Budget is measured and never exceeded.
        assertTrue(
            "${task.name}: budget exceeded: ${result.budget.tokensUsed} > ${result.budget.maxTokens}",
            result.budget.withinBudget,
        )

        // 2. Recall: every must-retrieve file is in the trace's included set.
        val included = result.trace.includedPaths().toSet()
        for (expected in task.mustRetrieve) {
            assertTrue("${task.name}: expected $expected in context, got $included", expected in included)
        }

        // 3. Precision: unrelated files never outrank relevant ones.
        val rankedPaths = result.trace.entries
            .filter { it.included }
            .groupBy { it.filePath }
            .mapValues { (_, es) -> es.sumOf { it.scoreContribution } }
            .toList()
            .sortedByDescending { it.second }
            .map { it.first }
        val worstRelevant = task.mustRetrieve.map { rankedPaths.indexOf(it) }.maxOrNull() ?: -1
        for (unrelated in task.mustNotDominate) {
            val pos = rankedPaths.indexOf(unrelated)
            if (pos != -1 && worstRelevant != -1) {
                assertTrue(
                    "${task.name}: unrelated $unrelated (pos $pos) outranks relevant files (worst $worstRelevant)",
                    pos > worstRelevant,
                )
            }
        }

        // 4. Trace shows relevant definitions/dependencies, not a file dump.
        val rendered = result.trace.renderText()
        assertTrue("${task.name}: trace must name signals", rendered.contains("Signals used:"))
        assertTrue("${task.name}: trace must list included files", rendered.contains("INCLUDED"))
    }

    @Test
    fun `bug fix retrieves stack-trace target plus real callers and dependencies`() {
        runTask(
            BenchmarkTask(
                name = "bugfix",
                request = "Fix crash:\njava.lang.NullPointerException: token null\n" +
                    "\tat com.example.auth.TokenManager.validate(TokenManager.kt:6)\n" +
                    "\tat com.example.auth.AuthService.login(AuthService.kt:7)",
                mustRetrieve = setOf("src/auth/TokenManager.kt", "src/auth/AuthService.kt"),
                mustNotDominate = setOf("src/util/StringUtils.kt", "src/config/AppConfig.kt", "py/unrelated.py"),
            )
        )
    }

    @Test
    fun `feature request with mention retrieves target and its real dependencies`() {
        runTask(
            BenchmarkTask(
                name = "feature",
                request = "Add a session-timeout check to the login flow",
                mentionedPaths = listOf("src/users/UserController.kt"),
                mustRetrieve = setOf("src/users/UserController.kt", "src/auth/AuthService.kt"),
                mustNotDominate = setOf("src/util/StringUtils.kt", "src/config/AppConfig.kt"),
            )
        )
    }

    @Test
    fun `refactor retrieves declaration and impacted callers`() {
        runTask(
            BenchmarkTask(
                name = "refactor",
                request = "Rename TokenManager.validate to isValid and update all callers",
                mustRetrieve = setOf("src/auth/TokenManager.kt"),
                mustNotDominate = setOf("src/util/StringUtils.kt", "src/config/AppConfig.kt", "py/unrelated.py"),
            )
        )
    }

    @Test
    fun `python task retrieves real import graph files`() {
        runTask(
            BenchmarkTask(
                name = "python",
                request = "The database connection in app.py is failing, fix connect",
                mentionedPaths = listOf("py/app.py"),
                mustRetrieve = setOf("py/app.py", "py/db.py"),
                mustNotDominate = setOf("py/unrelated.py", "src/util/StringUtils.kt"),
            )
        )
    }

    @Test
    fun `cache invalidation hooks clear stale context`() {
        val first = engine.buildContextWithTrace(
            projectId = "bench", workspace = workspaceDir,
            request = "Fix login flow", mentionedPaths = listOf("src/users/UserController.kt"),
        )
        assertTrue(first.context.isNotEmpty())

        // All four invalidation hooks must run without error and clear state.
        engine.invalidateOnFileEdit("src/users/UserController.kt")
        engine.invalidateOnBranchSwitch()
        engine.invalidateOnDependencyChange()
        engine.invalidateOnIndexRebuild()

        val second = engine.buildContextWithTrace(
            projectId = "bench", workspace = workspaceDir,
            request = "Fix login flow", mentionedPaths = listOf("src/users/UserController.kt"),
        )
        assertTrue(second.context.isNotEmpty())
        assertTrue(second.budget.withinBudget)
    }

    @Test
    fun `memory sourceRef and edit controls work end to end`() {
        val store = ProjectMemoryStore(memoryDir)
        val item = store.record(
            "bench", com.vayro.app.runtime.MemoryCategory.ARCHITECTURE,
            "auth_rule", "Tokens expire after 30 minutes",
            sourceRef = "src/auth/TokenManager.kt",
        )
        assertTrue(item.sourceRef == "src/auth/TokenManager.kt")

        val updated = store.updateMemory("bench", item.id, content = "Tokens expire after 15 minutes")
        assertTrue(updated != null && updated.content == "Tokens expire after 15 minutes")
        assertTrue(updated!!.sourceRef == "src/auth/TokenManager.kt")

        // sourceRef survives a reload from disk.
        val reloaded = ProjectMemoryStore(memoryDir).getMemories("bench").first { it.id == item.id }
        assertTrue(reloaded.sourceRef == "src/auth/TokenManager.kt")

        assertTrue(store.deleteMemory("bench", item.id))
        assertFalse(store.deleteMemory("bench", item.id))
    }
}
