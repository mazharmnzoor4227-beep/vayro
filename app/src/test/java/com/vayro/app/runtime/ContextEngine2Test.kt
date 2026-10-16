package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContextEngine2Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceDir: File
    private lateinit var memoryDir: File
    private lateinit var memoryStore: ProjectMemoryStore
    private lateinit var projectIndex: ProjectIndex
    private lateinit var contextEngine: ContextEngine

    @Before
    fun setUp() {
        workspaceDir = tempFolder.newFolder("ctx_workspace")
        memoryDir = tempFolder.newFolder("ctx_memory")
        memoryStore = ProjectMemoryStore(memoryDir)
        projectIndex = ProjectIndex(memoryDir)
        contextEngine = ContextEngine(projectIndex, memoryStore)

        File(workspaceDir, "src/AuthService.kt").apply {
            parentFile?.mkdirs()
            writeText("class AuthService { fun login() {} }")
        }
        File(workspaceDir, "src/UserModel.kt").writeText("data class User(val id: String)")
    }

    @Test
    fun `detectIntent detects bug fix, feature, refactoring, and build intents`() {
        assertEquals(UserIntent.BUG_FIX, ContextRankingEngine.detectIntent("Fix NullPointerException in AuthService"))
        assertEquals(UserIntent.FEATURE_IMPLEMENTATION, ContextRankingEngine.detectIntent("Add Google OAuth login support"))
        assertEquals(UserIntent.REFACTORING, ContextRankingEngine.detectIntent("Refactor UserModel to separate file"))
        assertEquals(UserIntent.BUILD_OR_TEST, ContextRankingEngine.detectIntent("Run gradle test on backend"))
    }

    @Test
    fun `projectMemoryStore records and persists architectural items across reloads`() {
        memoryStore.record(
            projectId = "test-proj",
            category = MemoryCategory.ARCHITECTURE,
            key = "auth_pattern",
            content = "Use repository pattern with OAuth tokens",
        )
        memoryStore.record(
            projectId = "test-proj",
            category = MemoryCategory.ERROR_FIX,
            key = "null_token_fix",
            content = "Check for null token before refreshing",
        )

        val freshStore = ProjectMemoryStore(memoryDir)
        val memories = freshStore.getMemories("test-proj")
        assertEquals(2, memories.size)
        assertTrue(memories.any { it.key == "auth_pattern" && it.category == MemoryCategory.ARCHITECTURE })
    }

    @Test
    fun `buildPromptContext includes memories and intent within token budget`() {
        memoryStore.record(
            projectId = "test-proj",
            category = MemoryCategory.ARCHITECTURE,
            key = "db",
            content = "SQLite room database",
        )

        val promptContext = contextEngine.buildPromptContext(
            projectId = "test-proj",
            workspace = workspaceDir,
            request = "Fix the login failure in AuthService",
            mentionedPaths = listOf("src/AuthService.kt"),
            maxTokenBudget = 2000,
        )

        assertTrue(promptContext.contains("intent=\"BUG_FIX\""))
        assertTrue(promptContext.contains("SQLite room database"))
        assertTrue(promptContext.contains("src/AuthService.kt"))
    }
}
