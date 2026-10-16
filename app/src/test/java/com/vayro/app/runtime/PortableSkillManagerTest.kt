package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PortableSkillManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var skillsDir: File
    private lateinit var skillManager: SkillManager

    @Before
    fun setUp() {
        skillsDir = tempFolder.newFolder("skills_test_dir")
        skillManager = SkillManager(skillsDir)
    }

    @Test
    fun `ensureDefaultSkills installs all 11 curated development skill packs`() {
        val installed = skillManager.installed()
        val installedNames = installed.map { it.name }.toSet()

        val expected11Curated = listOf(
            "android-development",
            "flutter-development",
            "laravel-development",
            "react-development",
            "nextjs-development",
            "nodejs-development",
            "python-development",
            "devops-engineering",
            "database-engineering",
            "testing-qa",
            "ui-ux-design"
        )

        for (curatedSkill in expected11Curated) {
            assertTrue("Expected skill '$curatedSkill' to be installed", installedNames.contains(curatedSkill))
            val skill = installed.find { it.name == curatedSkill }
            assertNotNull(skill)
            assertTrue("Description must not be blank for '$curatedSkill'", skill!!.description.isNotBlank())
        }
    }

    @Test
    fun `curated skills follow 5-folder portable skill structure`() {
        skillManager.ensureDefaultSkills()

        val expectedSkills = listOf(
            "android-development",
            "flutter-development",
            "laravel-development",
            "react-development",
            "nextjs-development",
            "nodejs-development",
            "python-development",
            "devops-engineering",
            "database-engineering",
            "testing-qa",
            "ui-ux-design"
        )

        for (skillName in expectedSkills) {
            val skillDir = File(skillsDir, skillName)
            assertTrue("Directory must exist for $skillName", skillDir.isDirectory)

            val structure = skillManager.validatePortableStructure(skillDir)
            assertTrue("Manifest must exist for $skillName", structure.hasManifest)
            assertTrue("instructions/ folder must exist for $skillName", structure.hasInstructions)
            assertTrue("tools/ folder must exist for $skillName", structure.hasTools)
            assertTrue("examples/ folder must exist for $skillName", structure.hasExamples)
            assertTrue("resources/ folder must exist for $skillName", structure.hasResources)
            assertTrue("Structure must be complete for $skillName", structure.isComplete)
        }
    }

    @Test
    fun `lowercase skill_md is recognized as valid manifest`() {
        val customSkillDir = File(skillsDir, "custom-portable-skill")
        customSkillDir.mkdirs()
        val lowerManifest = File(customSkillDir, "skill.md")
        lowerManifest.writeText("""---
name: custom-portable-skill
description: Custom portable skill using lowercase skill.md.
---

# Custom Portable Skill
""")
        skillManager.ensurePortableSkillLayout(customSkillDir)

        val manifestFile = skillManager.getSkillManifest(customSkillDir)
        assertNotNull(manifestFile)
        assertEquals("skill.md", manifestFile?.name)

        val structure = skillManager.validatePortableStructure(customSkillDir)
        assertTrue(structure.hasManifest)
        assertTrue(structure.isComplete)
    }
}
