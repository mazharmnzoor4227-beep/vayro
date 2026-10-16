package com.vayro.app.skills

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillLoaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var loader: SkillLoader
    private lateinit var registry: SkillRegistry

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("skills_test")
        loader = SkillLoader()
        registry = SkillRegistry(baseDir)
    }

    private fun writeSkill(
        parent: File,
        dirName: String = "my-skill",
        manifest: JSONObject = JSONObject()
            .put("id", "my-skill")
            .put("name", "My Skill")
            .put("version", "1.0.0")
            .put("description", "Does things"),
        prompt: String? = "# My Skill\nDo things well."
    ): File {
        val dir = File(parent, dirName).apply { mkdirs() }
        File(dir, "skill.json").writeText(manifest.toString())
        if (prompt != null) File(dir, "SKILL.md").writeText(prompt)
        return dir
    }

    @Test
    fun `valid package loads with manifest and prompt`() {
        val dir = writeSkill(tempFolder.newFolder("src"))
        val pkg = loader.loadFromDir(dir)
        assertEquals("my-skill", pkg.manifest.id)
        assertEquals("1.0.0", pkg.manifest.version)
        assertTrue(pkg.entryPrompt()!!.contains("My Skill"))
    }

    @Test
    fun `missing skill json is rejected`() {
        val dir = File(tempFolder.newFolder("src2"), "empty").apply { mkdirs() }
        try {
            loader.loadFromDir(dir)
            fail("expected SkillValidationException")
        } catch (e: SkillValidationException) {
            assertTrue(e.message!!.contains("skill.json"))
        }
    }

    @Test
    fun `blank id is rejected`() {
        val dir = writeSkill(
            tempFolder.newFolder("src3"),
            manifest = JSONObject().put("id", "").put("name", "N").put("version", "1.0.0")
        )
        try {
            loader.loadFromDir(dir)
            fail("expected SkillValidationException")
        } catch (e: SkillValidationException) {
            assertTrue(e.message!!.contains("'id'"))
        }
    }

    @Test
    fun `bad version is rejected`() {
        val dir = writeSkill(
            tempFolder.newFolder("src4"),
            manifest = JSONObject().put("id", "s").put("name", "N").put("version", "one-point-oh")
        )
        try {
            loader.loadFromDir(dir)
            fail("expected SkillValidationException")
        } catch (e: SkillValidationException) {
            assertTrue(e.message!!.contains("version"))
        }
    }

    @Test
    fun `missing entry prompt file is rejected`() {
        val dir = writeSkill(tempFolder.newFolder("src5"), prompt = null)
        try {
            loader.loadFromDir(dir)
            fail("expected SkillValidationException")
        } catch (e: SkillValidationException) {
            assertTrue(e.message!!.contains("SKILL.md"))
        }
    }

    @Test
    fun `malformed skill json is rejected`() {
        val dir = File(tempFolder.newFolder("src6"), "bad").apply { mkdirs() }
        File(dir, "skill.json").writeText("{ not json")
        File(dir, "SKILL.md").writeText("x")
        try {
            loader.loadFromDir(dir)
            fail("expected SkillValidationException")
        } catch (e: SkillValidationException) { /* expected */ }
    }

    @Test
    fun `required tools parse from manifest`() {
        val dir = writeSkill(
            tempFolder.newFolder("src7"),
            manifest = JSONObject()
                .put("id", "s")
                .put("name", "N")
                .put("version", "2.1.0")
                .put("requiredTools", org.json.JSONArray().put("read_file").put("search"))
        )
        val pkg = loader.loadFromDir(dir)
        assertEquals(listOf("read_file", "search"), pkg.manifest.requiredTools)
    }

    @Test
    fun `install list get remove round trip`() {
        val src = writeSkill(tempFolder.newFolder("src8"))
        val installed = registry.install(loader.loadFromDir(src))
        assertEquals("1.0.0", installed.manifest.version)

        assertEquals(listOf("my-skill"), registry.list().map { it.manifest.id })
        assertEquals("My Skill", registry.get("my-skill")!!.manifest.name)
        assertTrue(registry.remove("my-skill"))
        assertTrue(registry.list().isEmpty())
        assertNull(registry.get("my-skill"))
        assertFalse(registry.remove("my-skill"))
    }

    @Test
    fun `install refuses downgrade without force`() {
        val v2 = writeSkill(
            tempFolder.newFolder("v2"), "my-skill",
            JSONObject().put("id", "my-skill").put("name", "N").put("version", "2.0.0")
        )
        registry.install(loader.loadFromDir(v2))
        val v1 = writeSkill(
            tempFolder.newFolder("v1"), "my-skill",
            JSONObject().put("id", "my-skill").put("name", "N").put("version", "1.0.0")
        )
        try {
            registry.install(loader.loadFromDir(v1))
            fail("expected SkillValidationException")
        } catch (e: SkillValidationException) {
            assertTrue(e.message!!.contains("downgrade"))
        }
        assertEquals("2.0.0", registry.get("my-skill")!!.manifest.version)
    }

    @Test
    fun `install with force allows downgrade`() {
        val v2 = writeSkill(
            tempFolder.newFolder("v2b"), "my-skill",
            JSONObject().put("id", "my-skill").put("name", "N").put("version", "2.0.0")
        )
        registry.install(loader.loadFromDir(v2))
        val v1 = writeSkill(
            tempFolder.newFolder("v1b"), "my-skill",
            JSONObject().put("id", "my-skill").put("name", "N").put("version", "1.0.0")
        )
        val installed = registry.install(loader.loadFromDir(v1), force = true)
        assertEquals("1.0.0", installed.manifest.version)
    }

    @Test
    fun `update requires strictly newer version`() {
        val v1 = writeSkill(
            tempFolder.newFolder("u1"), "my-skill",
            JSONObject().put("id", "my-skill").put("name", "N").put("version", "1.0.0")
        )
        registry.install(loader.loadFromDir(v1))
        val same = writeSkill(
            tempFolder.newFolder("u1b"), "my-skill",
            JSONObject().put("id", "my-skill").put("name", "N").put("version", "1.0.0")
        )
        try {
            registry.update(loader.loadFromDir(same))
            fail("expected SkillValidationException")
        } catch (e: SkillValidationException) {
            assertTrue(e.message!!.contains("newer"))
        }
        val v2 = writeSkill(
            tempFolder.newFolder("u2"), "my-skill",
            JSONObject().put("id", "my-skill").put("name", "N").put("version", "1.1.0")
        )
        val updated = registry.update(loader.loadFromDir(v2))
        assertEquals("1.1.0", updated.manifest.version)
    }

    @Test
    fun `update on missing skill fails honestly`() {
        val v1 = writeSkill(tempFolder.newFolder("u3"))
        try {
            registry.update(loader.loadFromDir(v1))
            fail("expected SkillValidationException")
        } catch (e: SkillValidationException) {
            assertTrue(e.message!!.contains("not installed"))
        }
    }

    @Test
    fun `version comparison handles multi digit segments`() {
        assertTrue(com.vayro.app.mcp.McpRegistry.compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(com.vayro.app.mcp.McpRegistry.compareVersions("2.0.0", "2.0.0") == 0)
        assertTrue(com.vayro.app.mcp.McpRegistry.compareVersions("1.0.0", "1.0.1") < 0)
    }
}
