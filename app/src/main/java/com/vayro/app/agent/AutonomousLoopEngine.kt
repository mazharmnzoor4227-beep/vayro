package com.vayro.app.agent

import java.io.File

data class ProjectBuildTool(
    val name: String,
    val verificationCommand: String,
)

data class BuildVerificationResult(
    val success: Boolean,
    val toolName: String,
    val command: String,
    val output: String,
    val errors: List<String> = emptyList(),
)

object AutonomousLoopEngine {

    /**
     * Detects project build toolchain based on manifest files present in the project directory.
     */
    fun detectBuildTool(projectDir: File): ProjectBuildTool? {
        return when {
            File(projectDir, "build.gradle.kts").exists() || File(projectDir, "build.gradle").exists() -> {
                val hasWrapper = File(projectDir, "gradlew").exists()
                val cmd = if (hasWrapper) "./gradlew check --dry-run" else "gradle check"
                ProjectBuildTool("Gradle", cmd)
            }
            File(projectDir, "package.json").exists() -> {
                val packageJson = runCatching { File(projectDir, "package.json").readText() }.getOrDefault("")
                when {
                    packageJson.contains("\"build\"") -> ProjectBuildTool("npm", "npm run build")
                    packageJson.contains("\"test\"") -> ProjectBuildTool("npm", "npm test")
                    else -> ProjectBuildTool("Node.js", "node --check")
                }
            }
            File(projectDir, "Cargo.toml").exists() -> ProjectBuildTool("Cargo", "cargo check")
            File(projectDir, "go.mod").exists() -> ProjectBuildTool("Go", "go vet ./...")
            File(projectDir, "requirements.txt").exists() || File(projectDir, "pyproject.toml").exists() -> {
                ProjectBuildTool("Python", "python -m py_compile")
            }
            else -> null
        }
    }

    /**
     * Parses compiler and build output to extract actionable error messages.
     */
    fun parseCompilerErrors(output: String): List<String> {
        val lines = output.lines()
        val errorLines = mutableListOf<String>()
        for (line in lines) {
            val lower = line.lowercase()
            if (lower.contains("error:") ||
                lower.contains("e: ") ||
                lower.contains("syntaxerror") ||
                lower.contains("typeerror") ||
                lower.contains("compilation error") ||
                lower.contains("build failed")
            ) {
                val trimmed = line.trim()
                if (trimmed.length > 5 && !errorLines.contains(trimmed)) {
                    errorLines.add(trimmed)
                }
            }
        }
        return errorLines.take(10)
    }

    /**
     * Generates the automated repair prompt fed back to the agent when a build check fails.
     */
    fun buildAutoFixPrompt(result: BuildVerificationResult, iteration: Int, maxIterations: Int): String {
        return """
The automated build verification for ${result.toolName} (`${result.command}`) failed during Phase 5 verification (iteration $iteration of $maxIterations).

Build Output:
```
${result.output.takeLast(2500)}
```

Specific Compiler/Tool Errors:
${result.errors.joinToString("\n") { "- $it" }}

Instruction:
Please inspect the files responsible for these errors, fix the syntax/type/compilation issues, and ensure the project builds without errors.
""".trimIndent()
    }
}
