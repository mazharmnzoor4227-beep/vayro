package com.vayro.app.factory.build

import android.content.Context
import com.vayro.app.factory.model.AppBlueprint
import com.vayro.app.factory.model.BuildArtifactType
import com.vayro.app.factory.model.BuildMode
import com.vayro.app.factory.model.BuildResult
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Real build orchestration for App Factory scaffolded projects (v3).
 *
 * [runBuild] actually executes Gradle inside [projectDir] and reports the
 * genuine outcome — no simulated success. [installApk] uses the platform
 * [PackageInstaller] API to install a real APK file.
 */
object AppBuildOrchestrator {

    fun runBuild(
        projectDir: File,
        blueprint: AppBlueprint,
        artifactType: BuildArtifactType,
        buildMode: BuildMode
    ): BuildResult {
        val startedAt = System.currentTimeMillis()
        val logs = mutableListOf<String>()
        logs += "Building ${blueprint.name} (${artifactType.displayName}) via ${buildMode.displayName}..."

        if (buildMode == BuildMode.GITHUB_ACTIONS) {
            // The full CI pipeline (GitHub sign-in check, repository push,
            // run polling, artifact download) needs app services, so it runs
            // from AppFactoryCoordinator — this pure entry point only reports
            // that honestly instead of pretending to build.
            val msg = "GitHub Actions CI builds run from the App Factory Build step: " +
                "choose GitHub CI there and Kodex pushes the project, runs the build on GitHub, and downloads the finished file."
            logs += "ERROR: $msg"
            return BuildResult(
                success = false,
                artifactType = artifactType,
                buildMode = buildMode,
                logs = logs,
                durationMs = System.currentTimeMillis() - startedAt,
                errorMessage = msg
            )
        }

        val gradleTask = when (artifactType) {
            BuildArtifactType.APK_DEBUG -> "assembleDebug"
            BuildArtifactType.APK_RELEASE -> "assembleRelease"
            BuildArtifactType.AAB_BUNDLE -> "bundleRelease"
        }

        val gradlew = File(projectDir, "gradlew")
        if (!gradlew.isFile) {
            val msg = "No Gradle wrapper found in ${projectDir.absolutePath} — scaffold the project first."
            logs += "ERROR: $msg"
            return BuildResult(
                success = false,
                artifactType = artifactType,
                buildMode = buildMode,
                logs = logs,
                durationMs = System.currentTimeMillis() - startedAt,
                errorMessage = msg
            )
        }

        return try {
            gradlew.setExecutable(true)
            val process = ProcessBuilder("sh", gradlew.absolutePath, gradleTask, "--stacktrace")
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.MINUTES)
            logs += output.lines().takeLast(120)
            if (!finished) {
                process.destroyForcibly()
                val msg = "Gradle build timed out after 30 minutes."
                logs += "ERROR: $msg"
                return BuildResult(
                    success = false, artifactType = artifactType, buildMode = buildMode,
                    logs = logs, durationMs = System.currentTimeMillis() - startedAt,
                    errorMessage = msg
                )
            }
            val exitCode = process.exitValue()
            val artifact = findBuiltArtifact(projectDir, artifactType)
            if (exitCode == 0 && artifact != null) {
                logs += "Build succeeded: ${artifact.absolutePath}"
                BuildResult(
                    success = true, artifactType = artifactType, buildMode = buildMode,
                    outputFile = artifact, logs = logs,
                    durationMs = System.currentTimeMillis() - startedAt
                )
            } else {
                val msg = if (exitCode != 0) "Gradle exited with code $exitCode."
                else "Gradle succeeded but no ${artifactType.extension.uppercase()} artifact was produced."
                logs += "ERROR: $msg"
                BuildResult(
                    success = false, artifactType = artifactType, buildMode = buildMode,
                    logs = logs, durationMs = System.currentTimeMillis() - startedAt,
                    errorMessage = msg
                )
            }
        } catch (e: Exception) {
            val msg = "Build failed to start: ${e.message}"
            logs += "ERROR: $msg"
            BuildResult(
                success = false, artifactType = artifactType, buildMode = buildMode,
                logs = logs, durationMs = System.currentTimeMillis() - startedAt,
                errorMessage = msg
            )
        }
    }

    private fun findBuiltArtifact(projectDir: File, type: BuildArtifactType): File? {
        val ext = type.extension
        val outputs = File(projectDir, "app/build/outputs")
        if (!outputs.isDirectory) return null
        return outputs.walkTopDown()
            .filter { it.isFile && it.extension.equals(ext, ignoreCase = true) }
            .maxByOrNull { it.lastModified() }
    }

    /**
     * Discovers the built artifact of [artifactType] under [projectDir]'s
     * standard Gradle output layout. Returns null when nothing was produced.
     */
    fun discoverOutputFile(projectDir: File, artifactType: BuildArtifactType): File? =
        findBuiltArtifact(projectDir, artifactType)

    /**
     * Computes real metadata (size + SHA-256) for a built artifact.
     * Throws when [file] does not exist — never reports "unknown".
     */
    fun extractMetadata(file: File, artifactType: BuildArtifactType): ArtifactMetadata {
        require(file.isFile) { "Artifact not found: ${file.absolutePath}" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } > 0) digest.update(buf, 0, n)
        }
        val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
        return ArtifactMetadata(
            artifactType = artifactType,
            sizeBytes = file.length(),
            sha256Hex = sha256Hex
        )
    }

    /**
     * Generates a real GitHub Actions workflow (`android-build.yml`) that
     * compiles [artifactType] for the scaffolded project in [projectDir].
     * Returns the written workflow file.
     */
    fun generateGitHubActionsWorkflow(
        projectDir: File,
        blueprint: AppBlueprint,
        artifactType: BuildArtifactType
    ): File {
        val gradleTask = when (artifactType) {
            BuildArtifactType.APK_DEBUG -> "assembleDebug"
            BuildArtifactType.APK_RELEASE -> "assembleRelease"
            BuildArtifactType.AAB_BUNDLE -> "bundleRelease"
        }
        val workflowsDir = File(projectDir, ".github/workflows").apply { mkdirs() }
        val workflowFile = File(workflowsDir, "android-build.yml")
        workflowFile.writeText(
            """
            name: Android Build — ${blueprint.name}

            on:
              push:
                branches: [ main ]
              workflow_dispatch:

            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - name: Set up JDK 17
                    uses: actions/setup-java@v4
                    with:
                      distribution: 'temurin'
                      java-version: '17'
                  - name: Build ${artifactType.displayName}
                    run: ./gradlew $gradleTask --stacktrace
                  - name: Upload artifact
                    uses: actions/upload-artifact@v4
                    with:
                      name: ${blueprint.name.lowercase().replace(Regex("[^a-z0-9]"), "-")}-${artifactType.extension}
                      path: app/build/outputs/**/**/*.${artifactType.extension}
            """.trimIndent()
        )
        return workflowFile
    }

    /**
     * Installs [apkFile] via the app's real installer ([AndroidAppInstaller]).
     * Throws on any failure — the caller reports the real error.
     */
    fun installApk(context: Context, apkFile: File) {
        require(apkFile.isFile) { "APK not found: ${apkFile.absolutePath}" }
        com.vayro.app.runtime.AndroidAppInstaller.install(context, apkFile)
    }
}

/** Real, computed metadata for a built artifact — never "unknown". */
data class ArtifactMetadata(
    val artifactType: BuildArtifactType,
    val sizeBytes: Long,
    val sha256Hex: String
)
