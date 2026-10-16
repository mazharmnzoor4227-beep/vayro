package com.vayro.app.ui

import java.io.File

/**
 * QA H1: the Command Palette's Run Build / Run Tests actions used to dismiss
 * the sheet and do nothing. They now resolve the project's real build/test
 * command from its manifest files, prefill it in the Terminal tab, and let
 * the user review + run it. Returns null when the project has no detectable
 * command for the requested kind — the caller must say so, never invent one.
 */
internal fun detectPaletteBuildCommand(projectDir: File, forTests: Boolean): String? {
    val hasWrapper = File(projectDir, "gradlew").exists()
    val gradleCmd = if (hasWrapper) "./gradlew" else "gradle"
    if (File(projectDir, "build.gradle.kts").exists() || File(projectDir, "build.gradle").exists()) {
        return if (forTests) "$gradleCmd test" else "$gradleCmd build"
    }
    val packageJson = File(projectDir, "package.json")
    if (packageJson.exists()) {
        val text = runCatching { packageJson.readText() }.getOrDefault("")
        val script = if (forTests) "test" else "build"
        // `"test":` / `"build":` as a scripts key, not a substring of another word.
        return if (Regex("\"$script\"\\s*:").containsMatchIn(text)) "npm run $script" else null
    }
    if (File(projectDir, "Cargo.toml").exists()) {
        return if (forTests) "cargo test" else "cargo build"
    }
    if (File(projectDir, "go.mod").exists()) {
        return if (forTests) "go test ./..." else "go build ./..."
    }
    if (File(projectDir, "requirements.txt").exists() || File(projectDir, "pyproject.toml").exists()) {
        if (forTests) {
            val hasTests = File(projectDir, "tests").isDirectory ||
                File(projectDir, "test").isDirectory ||
                runCatching { File(projectDir, "pyproject.toml").readText() }
                    .getOrDefault("").contains("[tool.pytest")
            return if (hasTests) "python -m pytest" else null
        }
        return null
    }
    return null
}
