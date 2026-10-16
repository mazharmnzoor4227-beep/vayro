package com.vayro.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutonomousLoopEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testDetectBuildToolGradle() {
        val root = tempFolder.newFolder("gradle-project")
        File(root, "build.gradle.kts").writeText("// gradle config")
        File(root, "gradlew").writeText("#!/bin/bash")

        val tool = AutonomousLoopEngine.detectBuildTool(root)
        assertNotNull(tool)
        assertEquals("Gradle", tool?.name)
        assertTrue(tool?.verificationCommand?.contains("./gradlew") == true)
    }

    @Test
    fun testDetectBuildToolNpm() {
        val root = tempFolder.newFolder("node-project")
        File(root, "package.json").writeText("""{"scripts": {"build": "vite build"}}""")

        val tool = AutonomousLoopEngine.detectBuildTool(root)
        assertNotNull(tool)
        assertEquals("npm", tool?.name)
        assertEquals("npm run build", tool?.verificationCommand)
    }

    @Test
    fun testDetectBuildToolCargo() {
        val root = tempFolder.newFolder("rust-project")
        File(root, "Cargo.toml").writeText("[package]\nname = \"demo\"")

        val tool = AutonomousLoopEngine.detectBuildTool(root)
        assertNotNull(tool)
        assertEquals("Cargo", tool?.name)
        assertEquals("cargo check", tool?.verificationCommand)
    }

    @Test
    fun testDetectBuildToolPython() {
        val root = tempFolder.newFolder("python-project")
        File(root, "requirements.txt").writeText("requests==2.31.0")

        val tool = AutonomousLoopEngine.detectBuildTool(root)
        assertNotNull(tool)
        assertEquals("Python", tool?.name)
        assertEquals("python -m py_compile", tool?.verificationCommand)
    }

    @Test
    fun testDetectBuildToolNone() {
        val root = tempFolder.newFolder("empty-project")
        val tool = AutonomousLoopEngine.detectBuildTool(root)
        assertNull(tool)
    }

    @Test
    fun testParseCompilerErrors() {
        val compilerOutput = """
            Starting compilation...
            e: /workspace/app/src/main/App.kt:15:5 Unresolved reference 'foo'
            Some info line
            error: incompatible types in assignment
            Build finished with 2 errors.
        """.trimIndent()

        val errors = AutonomousLoopEngine.parseCompilerErrors(compilerOutput)
        assertEquals(2, errors.size)
        assertTrue(errors[0].contains("Unresolved reference 'foo'"))
        assertTrue(errors[1].contains("incompatible types"))
    }

    @Test
    fun testBuildAutoFixPrompt() {
        val result = BuildVerificationResult(
            success = false,
            toolName = "Gradle",
            command = "./gradlew check",
            output = "Compilation failure in App.kt",
            errors = listOf("e: App.kt:12:3 Unresolved reference 'Bar'"),
        )
        val prompt = AutonomousLoopEngine.buildAutoFixPrompt(result, 1, 3)

        assertTrue(prompt.contains("Gradle"))
        assertTrue(prompt.contains("./gradlew check"))
        assertTrue(prompt.contains("Unresolved reference 'Bar'"))
    }
}
