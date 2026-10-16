package com.vayro.app.ui

import com.vayro.app.model.ProjectKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA H29: the startup cleanup policy. Only a quick project with zero
 * user messages AND zero user files is empty enough to delete.
 */
class StartupCleanupPolicyTest {

    @Test
    fun `empty quick project is deletable`() {
        assertTrue(isEmptyQuickProject(ProjectKind.QUICK_PROJECT, 0, 0))
    }

    @Test
    fun `quick project with a user message is kept`() {
        assertFalse(isEmptyQuickProject(ProjectKind.QUICK_PROJECT, 1, 0))
    }

    @Test
    fun `quick project with a user file is kept`() {
        assertFalse(isEmptyQuickProject(ProjectKind.QUICK_PROJECT, 0, 1))
    }

    @Test
    fun `non-quick project is never cleaned even when empty`() {
        assertFalse(isEmptyQuickProject(ProjectKind.PROJECT, 0, 0))
    }
}
