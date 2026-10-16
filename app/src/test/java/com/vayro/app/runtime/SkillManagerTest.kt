package com.vayro.app.runtime

import com.vayro.app.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillManagerTest {

    @Test
    fun ensureDefaultSkillsProvisionsBuiltInSkills() {
        val tempDir = Files.createTempDirectory("skill-manager-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()

            val skills = manager.installed()
            assertEquals("Should provision 12 built-in skills", 12, skills.size)

            val browser = skills.firstOrNull { it.name == "browser-automation" }
            assertNotNull("browser-automation skill should be provisioned", browser)
            assertTrue(browser!!.description.contains("Chromium"))
            assertEquals("built-in", browser.source)
            assertTrue(File(browser.path, "SKILL.md").isFile)

            val android = skills.firstOrNull { it.name == "android-development" }
            assertNotNull("android-development skill should be provisioned", android)
            assertEquals("built-in", android!!.source)
            assertTrue(File(android.path, "SKILL.md").isFile)

            assertTrue("retired stub skills must not be seeded",
                skills.none { it.name == "academic-paper-architect" || it.name == "geo-sleuth" || it.name == "fullstack-dev-accelerator" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun ensureDefaultSkillsRemovesRetiredStubSkills() {
        val tempDir = Files.createTempDirectory("skill-retire-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            val stubDir = File(tempDir, "academic-paper-architect").apply { mkdirs() }
            File(stubDir, "SKILL.md").writeText("---\nname: academic-paper-architect\n---\n")
            val registry = File(tempDir, "registry.json")
            registry.writeText(
                """[{"name":"academic-paper-architect","description":"stub","source":"https://github.com/jarves/academic-paper-architect","installedAtMillis":1,"path":"${stubDir.absolutePath}","enabled":true}]"""
            )
            manager.ensureDefaultSkills()
            val skills = manager.installed()
            assertTrue(skills.none { it.name == "academic-paper-architect" })
            assertFalse(stubDir.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun syncToWorkspaceCopiesSkillsIntoAgentsDirectory() {
        val skillsDir = Files.createTempDirectory("skills-dir").toFile()
        val workspaceDir = Files.createTempDirectory("workspace-dir").toFile()
        try {
            val manager = SkillManager(baseDir = skillsDir)
            manager.ensureDefaultSkills()

            val synced = manager.syncToWorkspace(workspaceDir)
            assertEquals(12, synced.size)
            assertTrue(synced.any { it.name == "browser-automation" })
            assertTrue(synced.any { it.name == "android-development" })
            assertTrue(synced.any { it.name == "python-development" })

            assertTrue(File(workspaceDir, ".agents/skills/browser-automation/SKILL.md").isFile)
            assertTrue(File(workspaceDir, ".agents/skills/android-development/SKILL.md").isFile)
            assertTrue(File(workspaceDir, ".agents/skills/python-development/SKILL.md").isFile)
        } finally {
            skillsDir.deleteRecursively()
            workspaceDir.deleteRecursively()
        }
    }

    @Test
    fun buildPromptContextIncludesProvisionedSkills() {
        val tempDir = Files.createTempDirectory("skill-prompt-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()

            val promptContext = manager.buildPromptContext(AgentKind.ANTIGRAVITY)
            assertTrue(promptContext.contains("<mobile_harness_skills>"))
            assertTrue(promptContext.contains("browser-automation"))
            assertTrue(promptContext.contains("android-development"))
            assertTrue(promptContext.contains("python-development"))
            assertTrue(promptContext.contains("Selected agent: antigravity"))
            assertTrue(promptContext.contains("</mobile_harness_skills>"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun checkUpdatesFiltersGitHubSources() {
        val tempDir = Files.createTempDirectory("skill-update-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()

            val installed = manager.installed()
            val githubSkills = installed.filter { it.source.startsWith("https://github.com/") }
            assertEquals(0, githubSkills.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun setEnabledDisablesAndReenablesSkill() {
        val tempDir = Files.createTempDirectory("skill-toggle-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()

            assertTrue(manager.setEnabled("android-development", false))
            val disabled = manager.installed().first { it.name == "android-development" }
            assertFalse(disabled.enabled)
            assertTrue(manager.installed().first { it.name == "browser-automation" }.enabled)

            assertTrue(manager.setEnabled("android-development", true))
            assertTrue(manager.installed().first { it.name == "android-development" }.enabled)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun setEnabledReturnsFalseForUnknownSkill() {
        val tempDir = Files.createTempDirectory("skill-toggle-unknown-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()
            assertFalse(manager.setEnabled("no-such-skill", false))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun enabledSkillsExcludesDisabled() {
        val tempDir = Files.createTempDirectory("skill-enabled-list-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()
            val total = manager.installed().size

            manager.setEnabled("android-development", false)
            manager.setEnabled("python-development", false)

            val enabled = manager.enabledSkills()
            assertEquals(total - 2, enabled.size)
            assertTrue(enabled.none { it.name == "android-development" || it.name == "python-development" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun buildPromptContextExcludesDisabledSkills() {
        val tempDir = Files.createTempDirectory("skill-prompt-toggle-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()

            manager.setEnabled("android-development", false)

            val promptContext = manager.buildPromptContext(AgentKind.ANTIGRAVITY)
            assertTrue(promptContext.contains("browser-automation"))
            assertFalse(promptContext.contains("android-development"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun syncToWorkspaceSkipsDisabledSkills() {
        val tempDir = Files.createTempDirectory("skill-sync-toggle-test").toFile()
        val workspaceDir = Files.createTempDirectory("skill-sync-toggle-workspace").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()

            manager.setEnabled("android-development", false)
            val synced = manager.syncToWorkspace(workspaceDir)

            assertTrue(synced.none { it.name == "android-development" })
            assertFalse(File(workspaceDir, ".agents/skills/android-development").exists())
            assertTrue(File(workspaceDir, ".agents/skills/browser-automation/SKILL.md").isFile)
        } finally {
            tempDir.deleteRecursively()
            workspaceDir.deleteRecursively()
        }
    }

    @Test
    fun enabledFlagSurvivesRegistryRoundTrip() {
        val tempDir = Files.createTempDirectory("skill-toggle-persist-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            manager.ensureDefaultSkills()
            manager.setEnabled("android-development", false)

            val reloaded = SkillManager(baseDir = tempDir)
            assertFalse(reloaded.installed().first { it.name == "android-development" }.enabled)
            assertTrue(reloaded.enabledSkills().none { it.name == "android-development" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun installZipAcceptsSkillWithoutFrontmatter() {
        val tempDir = Files.createTempDirectory("skill-nofrontmatter-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            val zip = File(tempDir, "bundle.zip")
            zipArchive(zip, mapOf(
                "my-tool/SKILL.md" to "# My Tool\n\nDoes useful things for developers.\n",
                "my-tool/helper.txt" to "helper",
            ))
            val installed = manager.installZip(zip, "test")
            assertEquals(1, installed.size)
            assertEquals("my-tool", installed[0].name)
            assertTrue(installed[0].description.contains("Does useful things"))
            assertTrue(File(tempDir, "my-tool/SKILL.md").isFile)
            assertTrue(File(tempDir, "my-tool/helper.txt").isFile)
            assertTrue(manager.installed().any { it.name == "my-tool" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun installZipAcceptsBomPrefixedFrontmatter() {
        val tempDir = Files.createTempDirectory("skill-bom-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            val zip = File(tempDir, "bundle.zip")
            zipArchive(zip, mapOf(
                "bom-skill/SKILL.md" to "﻿---\nname: bom-skill\ndescription: Skill with a byte-order mark.\n---\n\nBody.\n",
            ))
            val installed = manager.installZip(zip, "test")
            assertEquals(1, installed.size)
            assertEquals("bom-skill", installed[0].name)
            assertEquals("Skill with a byte-order mark.", installed[0].description)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun installZipSkipsBadSkillWithoutFailingBatch() {
        val tempDir = Files.createTempDirectory("skill-batch-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            val zip = File(tempDir, "bundle.zip")
            zipArchive(zip, mapOf(
                "good-skill/SKILL.md" to "---\nname: good-skill\ndescription: A valid skill.\n---\n\nBody.\n",
                "empty-skill/SKILL.md" to "",
            ))
            val installed = manager.installZip(zip, "test")
            assertEquals(1, installed.size)
            assertEquals("good-skill", installed[0].name)
            assertTrue(manager.installed().any { it.name == "good-skill" })
            assertTrue(manager.installed().none { it.name == "empty-skill" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun installZipThrowsWhenNothingInstallable() {
        val tempDir = Files.createTempDirectory("skill-empty-batch-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            val zip = File(tempDir, "bundle.zip")
            zipArchive(zip, mapOf("empty-skill/SKILL.md" to ""))
            val result = runCatching { manager.installZip(zip, "test") }
            assertTrue(result.isFailure)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun zipArchive(zip: File, entries: Map<String, String>) {
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
    }

    private fun scannedDetail(
        name: String = "demo-skill",
        description: String = "A sufficiently long description of what this skill does for the agent.",
        hasFrontmatter: Boolean = true,
        structure: SkillManager.PortableSkillStructure = SkillManager.PortableSkillStructure(
            hasManifest = true,
            hasInstructions = true,
            hasTools = true,
            hasExamples = true,
            hasResources = true,
            isComplete = true,
        ),
    ) = ScannedSkillDetail(name, description, hasFrontmatter, structure)

    @Test
    fun buildScanSuggestionsFlagsNonSkillRepo() {
        val tempDir = Files.createTempDirectory("skill-scan-suggest-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            val suggestions = manager.buildScanSuggestions(emptyList(), null)
            assertEquals(1, suggestions.size)
            assertTrue(suggestions[0].contains("no SKILL.md", ignoreCase = true))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun buildScanSuggestionsFlagsMissingFrontmatterAndStructure() {
        val tempDir = Files.createTempDirectory("skill-scan-suggest-test2").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            val incomplete = SkillManager.PortableSkillStructure(
                hasManifest = true,
                hasInstructions = false,
                hasTools = false,
                hasExamples = true,
                hasResources = false,
                isComplete = false,
            )
            val suggestions = manager.buildScanSuggestions(
                listOf(scannedDetail(hasFrontmatter = false, description = "short", structure = incomplete)),
                null,
            )
            assertTrue(suggestions.any { it.contains("frontmatter", ignoreCase = true) })
            assertTrue(suggestions.any { it.contains("description", ignoreCase = true) })
            assertTrue(suggestions.any { it.contains("instructions", ignoreCase = true) })
            assertTrue(suggestions.any { it.contains("tools", ignoreCase = true) })
            assertTrue(suggestions.any { it.contains("No releases published", ignoreCase = true) })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun buildScanSuggestionsRecommendsNewerRelease() {
        val tempDir = Files.createTempDirectory("skill-scan-suggest-test3").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            val release = SkillReleaseScan(tag = "v2.0.0", name = "v2", publishedAt = "2026-10-01T00:00:00Z", newerThanBranch = true)
            val suggestions = manager.buildScanSuggestions(listOf(scannedDetail()), release)
            assertTrue(suggestions.any { it.contains("v2.0.0") && it.contains("newer", ignoreCase = true) })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun buildScanSuggestionsCleanForCompleteSkill() {
        val tempDir = Files.createTempDirectory("skill-scan-suggest-test4").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            val release = SkillReleaseScan(tag = "v1.0.0", name = "v1", publishedAt = "2026-01-01T00:00:00Z", newerThanBranch = false)
            val suggestions = manager.buildScanSuggestions(listOf(scannedDetail()), release)
            assertTrue(suggestions.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun installGitHubReleaseRejectsInvalidTag() {
        val tempDir = Files.createTempDirectory("skill-release-tag-test").toFile()
        try {
            val manager = SkillManager(baseDir = tempDir)
            val result = runCatching { manager.installGitHubRelease("https://github.com/owner/repo", "../evil") }
            assertTrue(result.isFailure)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
