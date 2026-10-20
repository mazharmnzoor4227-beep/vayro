package com.vayro.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.51: home-chat skill selection and prompt assembly (the same
 * SKILL.md material the workspace agents use).
 */
class HomeSkillContextTest {

    private val seo = HomeSkillContext.SkillCandidate(
        "seo-audit",
        "Audit a website for SEO issues and ranking problems",
        "Run the audit checklist step by step.",
    )
    private val chai = HomeSkillContext.SkillCandidate(
        "chai-recipe",
        "Make tea and snacks the desi way",
        "Boil water, add patti.",
    )

    @Test
    fun `frontmatter is stripped`() {
        val raw = "---\nname: seo-audit\ndescription: x\n---\nBody here"
        assertEquals("Body here", HomeSkillContext.stripFrontmatter(raw))
        assertEquals("Plain body", HomeSkillContext.stripFrontmatter("Plain body"))
    }

    @Test
    fun `naming the skill selects it`() {
        val selected = HomeSkillContext.select("please run the seo audit on my site", listOf(seo, chai))
        assertEquals(listOf(seo), selected)
    }

    @Test
    fun `keyword overlap selects without naming`() {
        val selected = HomeSkillContext.select("help me audit my website ranking", listOf(seo, chai))
        assertEquals(listOf(seo), selected)
    }

    @Test
    fun `unrelated messages select nothing`() {
        assertTrue(HomeSkillContext.select("what is the weather like", listOf(seo, chai)).isEmpty())
        assertTrue(HomeSkillContext.select("", listOf(seo)).isEmpty())
    }

    @Test
    fun `block carries the index the guard and capped bodies`() {
        val block = HomeSkillContext.buildBlock(listOf(seo, chai), listOf(seo))
        assertTrue(block.contains("<home_skills>"))
        assertTrue(block.contains("- seo-audit:"))
        assertTrue(block.contains("- chai-recipe:"))
        assertTrue(block.contains("<skill name=\"seo-audit\">"))
        assertTrue(block.contains("Run the audit checklist step by step."))
        assertTrue(block.contains("never override the rules above"))
        assertFalse(block.contains("Boil water"))
    }

    @Test
    fun `no skills means no block`() {
        assertEquals("", HomeSkillContext.buildBlock(emptyList(), emptyList()))
    }

    @Test
    fun `skill bodies are capped per skill and in total`() {
        val big1 = HomeSkillContext.SkillCandidate("big-one", "first", "y".repeat(10_000))
        val big2 = HomeSkillContext.SkillCandidate("big-two", "second", "z".repeat(10_000))
        val block = HomeSkillContext.buildBlock(listOf(big1, big2), listOf(big1, big2))
        assertTrue(block.contains("y".repeat(6_000)))
        assertFalse(block.contains("y".repeat(6_001)))
        assertFalse(block.contains("z".repeat(6_001)))
    }
}
