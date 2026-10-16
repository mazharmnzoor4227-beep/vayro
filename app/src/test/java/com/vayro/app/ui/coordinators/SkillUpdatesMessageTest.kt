package com.vayro.app.ui.coordinators

import com.vayro.app.runtime.SkillUpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA Medium: a skill update check used to swallow per-skill errors, so
 * offline looked exactly like "no updates". A failed check must never
 * report "up to date".
 */
class SkillUpdatesMessageTest {

    @Test
    fun `no updates and no failures reports up to date`() {
        assertEquals(
            "All remotely sourced skills are up to date.",
            skillUpdatesMessage(emptyList(), emptyList())
        )
    }

    @Test
    fun `no updates with a failed check reports could not check`() {
        val message = skillUpdatesMessage(emptyList(), listOf("my-skill"))
        assertTrue(message.startsWith("Could not check for skill updates"))
        assertTrue(message.contains("my-skill"))
    }

    @Test
    fun `updates with a partial failure reports both`() {
        val message = skillUpdatesMessage(
            listOf(SkillUpdateInfo("skill-a", "https://github.com/x/y")),
            listOf("skill-b")
        )
        assertTrue(message.contains("1 skill update available"))
        assertTrue(message.contains("Could not check skill-b"))
    }
}
