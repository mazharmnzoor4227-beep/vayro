package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContextEngine2FullPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceDir: File
    private lateinit var memoryDir: File
    private lateinit var memoryStore: ProjectMemoryStore
    private lateinit var projectIndex: ProjectIndex
    private lateinit var semanticIndex: WorkspaceSemanticIndex
    private lateinit var contextEngine: ContextEngine

    @Before
    fun setUp() {
        workspaceDir = tempFolder.newFolder("workspace_pipeline")
        memoryDir = tempFolder.newFolder("memory_pipeline")
        memoryStore = ProjectMemoryStore(memoryDir)
        projectIndex = ProjectIndex(memoryDir)

        File(workspaceDir, "src/AuthService.kt").apply {
            parentFile?.mkdirs()
            writeText(
                """
                package com.example

                class AuthService {
                    fun login(token: String?) {
                        val length = token!!.length
                        println("Length: " + length)
                    }
                }
                """.trimIndent()
            )
        }

        File(workspaceDir, "src/UserController.kt").apply {
            parentFile?.mkdirs()
            writeText(
                """
                package com.example

                class UserController(private val auth: AuthService) {
                    fun handleLogin(t: String?) {
                        auth.login(t)
                    }
                }
                """.trimIndent()
            )
        }

        semanticIndex = WorkspaceSemanticIndex(workspaceDir).indexWorkspace()
        contextEngine = ContextEngine(
            projectIndex = projectIndex,
            memoryStore = memoryStore,
            workspaceSemanticIndex = semanticIndex,
        )
    }

    @Test
    fun `ErrorContextExtractor extracts exception, target files, and line numbers from stack trace`() {
        val stackTrace = """
            java.lang.NullPointerException: Token must not be null
                at com.example.AuthService.login(AuthService.kt:5)
                at com.example.UserController.handleLogin(UserController.kt:6)
        """.trimIndent()

        val err = ErrorContextExtractor.extractErrorContext(stackTrace)
        assertNotNull(err)
        assertEquals("NullPointerException", err!!.exceptionType)
        assertEquals("Token must not be null", err.exceptionMessage)
        assertTrue(err.targetFiles.contains("AuthService.kt"))
        assertEquals(5, err.lineNumbers["AuthService.kt"])
    }

    @Test
    fun `rankFiles prioritizes stack trace targets, git modified files, and symbol graph relations`() {
        val snapshot = projectIndex.ensureFresh("proj-1", workspaceDir)
        val errContext = ErrorContext(
            exceptionType = "NullPointerException",
            targetFiles = listOf("AuthService.kt"),
            lineNumbers = mapOf("AuthService.kt" to 5),
        )

        val ranked = ContextRankingEngine.rankFiles(
            files = snapshot.files,
            request = "Fix NullPointerException in AuthService",
            gitModifiedPaths = listOf("src/UserController.kt"),
            errorContext = errContext,
            symbolGraph = semanticIndex.getSymbolGraph(),
        )

        assertTrue(ranked.isNotEmpty())
        val first = ranked.first()
        assertEquals("src/AuthService.kt", first.file.path)
        assertTrue(first.reason.contains("Stack trace error target"))

        val second = ranked.find { it.file.path == "src/UserController.kt" }
        assertNotNull(second)
        assertTrue(second!!.reason.contains("Recently modified in Git"))
    }

    @Test
    fun `extractRelevantCodeBlocks extracts code snippet around target line`() {
        val snapshot = projectIndex.ensureFresh("proj-1", workspaceDir)
        val ranked = listOf(
            RankedContextFile(
                file = snapshot.files.first { it.path == "src/AuthService.kt" },
                score = 800,
                matchedSymbols = listOf("login"),
                reason = "Stack trace error target",
                targetLine = 5,
            )
        )

        val blocks = ContextRankingEngine.extractRelevantCodeBlocks(workspaceDir, ranked)
        assertEquals(1, blocks.size)
        val block = blocks.first()
        assertEquals("src/AuthService.kt", block.filePath)
        assertTrue(block.code.contains("> 5:         val length = token!!.length"))
    }

    @Test
    fun `buildPromptContext runs 10-step pipeline end to end with budget enforcement and caching`() {
        memoryStore.recordArchitectureRule("proj-1", "auth_arch", "Auth tokens must be validated with HMAC")
        memoryStore.recordErrorFix("proj-1", "null_check", "Always check token != null before reading length")

        val stackPrompt = """
            Fix crash:
            java.lang.NullPointerException: Token is null
                at com.example.AuthService.login(AuthService.kt:5)
        """.trimIndent()

        val promptContext = contextEngine.buildPromptContext(
            projectId = "proj-1",
            workspace = workspaceDir,
            request = stackPrompt,
            maxTokenBudget = 2500,
        )

        assertTrue(promptContext.contains("intent=\"BUG_FIX\""))
        assertTrue(promptContext.contains("<project_overview>"))
        assertTrue(promptContext.contains("<error_context>"))
        assertTrue(promptContext.contains("NullPointerException"))
        assertTrue(promptContext.contains("<project_memory>"))
        assertTrue(promptContext.contains("auth_arch"))
        assertTrue(promptContext.contains("<relevant_code_blocks count="))
        assertTrue(promptContext.contains("<relevant_files count="))

        // Verify caching: subsequent invocation with same prompt returns cached response
        val cachedContext = contextEngine.buildPromptContext(
            projectId = "proj-1",
            workspace = workspaceDir,
            request = stackPrompt,
            maxTokenBudget = 2500,
        )
        assertEquals(promptContext, cachedContext)

        // Clear cache and verify it regenerates cleanly
        contextEngine.clearCache()
        val regenerated = contextEngine.buildPromptContext(
            projectId = "proj-1",
            workspace = workspaceDir,
            request = stackPrompt,
            maxTokenBudget = 2500,
        )
        assertEquals(promptContext, regenerated)
    }
}
