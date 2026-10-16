package com.vayro.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectChatTest {

    @Test
    fun testProjectChatCreation() {
        val chat = ProjectChat(title = "Feature Exploration")
        assertNotNull(chat.id)
        assertEquals("Feature Exploration", chat.title)
        assertTrue(chat.createdAtMillis > 0L)
        assertTrue(chat.updatedAtMillis > 0L)
    }

    @Test
    fun testProjectSlugSanitization() {
        assertEquals("my-awesome-repo", projectSlug("My Awesome Repo!"))
        assertEquals("conzyx-v2", projectSlug("Conzyx (v2)"))
        assertEquals("project", projectSlug("   "))
    }
}
