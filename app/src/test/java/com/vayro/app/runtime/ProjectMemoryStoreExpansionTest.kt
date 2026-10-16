package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectMemoryStoreExpansionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var memoryDir: File
    private lateinit var memoryStore: ProjectMemoryStore

    @Before
    fun setUp() {
        memoryDir = tempFolder.newFolder("memory_test")
        memoryStore = ProjectMemoryStore(memoryDir)
    }

    @Test
    fun `records and retrieves items across all 7 roadmap memory categories`() {
        val projId = "demo-project"

        memoryStore.recordProjectMetadata(projId, "build_tool", "Gradle Kotlin DSL 8.5")
        memoryStore.recordArchitectureRule(projId, "clean_arch", "Domain layer must have zero Android dependencies")
        memoryStore.recordUserPreference(projId, "naming", "Use camelCase for all helper functions")
        memoryStore.recordSessionGoal(projId, "sprint_goal", "Implement OAuth2 refresh flow")
        memoryStore.recordConversationDecision(projId, "auth_decision", "Agreed on PKCE authorization code grant")
        memoryStore.recordErrorFix(projId, "npe_fix", "Guard against null credentials in AuthService:42")
        memoryStore.recordPreviousImplementation(projId, "token_cache", "Added EncryptedSharedPreferences token cache")

        val all = memoryStore.getMemories(projId)
        assertEquals(7, all.size)

        assertEquals("Gradle Kotlin DSL 8.5", memoryStore.getMemories(projId, MemoryCategory.PROJECT).first().content)
        assertEquals("Domain layer must have zero Android dependencies", memoryStore.getMemories(projId, MemoryCategory.ARCHITECTURE).first().content)
        assertEquals("Use camelCase for all helper functions", memoryStore.getMemories(projId, MemoryCategory.USER_PREFERENCE).first().content)
        assertEquals("Implement OAuth2 refresh flow", memoryStore.getMemories(projId, MemoryCategory.SESSION).first().content)
        assertEquals("Agreed on PKCE authorization code grant", memoryStore.getMemories(projId, MemoryCategory.CONVERSATION).first().content)
        assertEquals("Guard against null credentials in AuthService:42", memoryStore.getMemories(projId, MemoryCategory.ERROR).first().content)
        assertEquals("Added EncryptedSharedPreferences token cache", memoryStore.getMemories(projId, MemoryCategory.PREVIOUS_IMPLEMENTATION).first().content)
    }

    @Test
    fun `search finds relevant memories by keyword in key or content`() {
        val projId = "search-project"
        memoryStore.recordArchitectureRule(projId, "mvvm_rule", "ViewModels must expose StateFlow not LiveData")
        memoryStore.recordErrorFix(projId, "flow_collection_leak", "Use repeatOnLifecycle when collecting flows")
        memoryStore.recordUserPreference(projId, "formatting", "Indent with 4 spaces")

        val results = memoryStore.search(projId, "Flow")
        assertEquals(2, results.size)
        assertTrue(results.any { it.key == "mvvm_rule" })
        assertTrue(results.any { it.key == "flow_collection_leak" })

        val formatResults = memoryStore.search(projId, "spaces")
        assertEquals(1, formatResults.size)
        assertEquals("formatting", formatResults.first().key)
    }

    @Test
    fun `persistence reloads memory correctly after store re-instantiation`() {
        val projId = "persist-proj"
        val item = memoryStore.recordArchitectureRule(projId, "rule_1", "Always use immutable models")

        val freshStore = ProjectMemoryStore(memoryDir)
        val loaded = freshStore.getMemories(projId)
        assertEquals(1, loaded.size)
        assertEquals("rule_1", loaded.first().key)
        assertEquals("Always use immutable models", loaded.first().content)

        // Test delete
        val deleted = freshStore.deleteMemory(projId, item.id)
        assertTrue(deleted)
        assertEquals(0, freshStore.getMemories(projId).size)
    }
}
