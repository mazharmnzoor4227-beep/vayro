package com.vayro.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * QA H1: Run Build / Run Tests must resolve the project's real command from
 * its manifests, and return null (never an invented command) when none exists.
 */
class PaletteBuildCommandsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(vararg files: Pair<String, String>): File {
        val root = tmp.newFolder()
        files.forEach { (name, content) ->
            val f = File(root, name)
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
        return root
    }

    @Test
    fun `gradle project uses wrapper when present`() {
        val root = dir("build.gradle.kts" to "", "gradlew" to "")
        assertEquals("./gradlew build", detectPaletteBuildCommand(root, forTests = false))
        assertEquals("./gradlew test", detectPaletteBuildCommand(root, forTests = true))
    }

    @Test
    fun `gradle project without wrapper falls back to gradle`() {
        val root = dir("build.gradle" to "")
        assertEquals("gradle build", detectPaletteBuildCommand(root, forTests = false))
        assertEquals("gradle test", detectPaletteBuildCommand(root, forTests = true))
    }

    @Test
    fun `npm project uses declared scripts only`() {
        val root = dir("package.json" to """{"scripts": {"build": "vite build"}}""")
        assertEquals("npm run build", detectPaletteBuildCommand(root, forTests = false))
        assertNull(detectPaletteBuildCommand(root, forTests = true))
    }

    @Test
    fun `npm test script detected, build substring does not match`() {
        // "rebuild" contains "build" but is not the build script.
        val root = dir("package.json" to """{"scripts": {"rebuild": "x", "test": "jest"}}""")
        assertNull(detectPaletteBuildCommand(root, forTests = false))
        assertEquals("npm run test", detectPaletteBuildCommand(root, forTests = true))
    }

    @Test
    fun `cargo and go projects resolve build and test`() {
        val cargo = dir("Cargo.toml" to "")
        assertEquals("cargo build", detectPaletteBuildCommand(cargo, forTests = false))
        assertEquals("cargo test", detectPaletteBuildCommand(cargo, forTests = true))
        val go = dir("go.mod" to "")
        assertEquals("go build ./...", detectPaletteBuildCommand(go, forTests = false))
        assertEquals("go test ./...", detectPaletteBuildCommand(go, forTests = true))
    }

    @Test
    fun `python test resolves only with tests or pytest config, build never`() {
        val plain = dir("requirements.txt" to "")
        assertNull(detectPaletteBuildCommand(plain, forTests = false))
        assertNull(detectPaletteBuildCommand(plain, forTests = true))
        val withTests = dir("requirements.txt" to "", "tests/test_x.py" to "")
        assertEquals("python -m pytest", detectPaletteBuildCommand(withTests, forTests = true))
        val withConfig = dir("pyproject.toml" to "[tool.pytest.ini_options]")
        assertEquals("python -m pytest", detectPaletteBuildCommand(withConfig, forTests = true))
    }

    @Test
    fun `unknown project returns null for both`() {
        val root = dir("README.md" to "# hi")
        assertNull(detectPaletteBuildCommand(root, forTests = false))
        assertNull(detectPaletteBuildCommand(root, forTests = true))
    }

    @Test
    fun `new entry names reject separators and dot segments`() {
        assertEquals(true, isValidNewEntryName("notes.txt"))
        assertEquals(true, isValidNewEntryName("my file.kt"))
        assertEquals(true, isValidNewEntryName(".gitignore"))
        assertEquals(false, isValidNewEntryName(""))
        assertEquals(false, isValidNewEntryName("   "))
        assertEquals(false, isValidNewEntryName("."))
        assertEquals(false, isValidNewEntryName(".."))
        assertEquals(false, isValidNewEntryName("sub/file.txt"))
        assertEquals(false, isValidNewEntryName("..\\evil.txt"))
    }
}
