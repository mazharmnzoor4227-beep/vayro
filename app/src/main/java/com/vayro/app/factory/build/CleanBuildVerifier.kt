package com.vayro.app.factory.build

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Outcome of one Gradle task inside the clean-state verification. */
data class CleanBuildTaskResult(
    val task: String,
    val success: Boolean,
    val exitCode: Int,
    val timedOut: Boolean,
    val outputTail: String,
    val durationMs: Long,
)

/** Honest result of building + testing a generated project from a clean state. */
data class CleanBuildResult(
    val projectName: String,
    val sourceProjectDir: File,
    val cleanDir: File?,
    val taskResults: List<CleanBuildTaskResult>,
    val success: Boolean,
    val error: String?,
    val durationMs: Long,
) {
    /** Tail of the last failed task's output, for quick diagnosis. */
    val failureDiagnosis: String?
        get() = taskResults.firstOrNull { !it.success }?.outputTail
            ?: error
}

private val DEFAULT_TASKS = listOf("clean", "testDebugUnitTest", "assembleDebug")
private val EXCLUDED_DIR_NAMES = setOf("build", ".gradle", ".cxx", ".kotlin")

/**
 * Verifies that a generated project compiles and passes tests from a clean
 * state (v3, Milestone 8): the project is copied to a temp dir with all build
 * outputs stripped, then the real Gradle tasks run there. Every outcome —
 * including missing wrapper, task failure, and timeout — is reported honestly.
 */
object CleanBuildVerifier {

    fun verify(
        projectDir: File,
        projectName: String = projectDir.name,
        tasks: List<String> = DEFAULT_TASKS,
        commandRunner: CommandRunner = ProcessBuilderCommandRunner(),
        timeoutPerTask: Duration = 30.minutes,
    ): CleanBuildResult {
        val startedAt = System.currentTimeMillis()
        require(projectDir.isDirectory) { "Project dir not found: ${projectDir.absolutePath}" }
        require(tasks.isNotEmpty()) { "Task list must not be empty." }

        val gradlew = File(projectDir, "gradlew")
        if (!gradlew.isFile) {
            return CleanBuildResult(
                projectName = projectName,
                sourceProjectDir = projectDir,
                cleanDir = null,
                taskResults = emptyList(),
                success = false,
                error = "No Gradle wrapper found in ${projectDir.absolutePath} — scaffold the project first.",
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }

        val cleanDir = runCatching {
            Files.createTempDirectory("mh-cleanbuild-").toFile().also { copyClean(projectDir, it) }
        }.getOrElse { e ->
            return CleanBuildResult(
                projectName = projectName,
                sourceProjectDir = projectDir,
                cleanDir = null,
                taskResults = emptyList(),
                success = false,
                error = "Failed to stage clean copy: ${e.message}",
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }

        val taskResults = mutableListOf<CleanBuildTaskResult>()
        for (task in tasks) {
            val taskStarted = System.currentTimeMillis()
            val result = commandRunner.run(
                command = listOf("sh", File(cleanDir, "gradlew").absolutePath, task, "--stacktrace"),
                workingDir = cleanDir,
                timeout = timeoutPerTask,
            )
            val tail = result.output.lines().takeLast(40).joinToString("\n")
            taskResults.add(
                CleanBuildTaskResult(
                    task = task,
                    success = result.success,
                    exitCode = result.exitCode,
                    timedOut = result.timedOut,
                    outputTail = tail,
                    durationMs = System.currentTimeMillis() - taskStarted,
                )
            )
            if (!result.success) break // stop at first failure — no point running further tasks
        }

        return CleanBuildResult(
            projectName = projectName,
            sourceProjectDir = projectDir,
            cleanDir = cleanDir,
            taskResults = taskResults,
            success = taskResults.isNotEmpty() && taskResults.all { it.success },
            error = if (taskResults.isNotEmpty() && taskResults.all { it.success }) null
            else "Clean build failed at '${taskResults.firstOrNull { !it.success }?.task}'.",
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    /** Copies [source] to [target], stripping build outputs and Gradle caches. */
    fun copyClean(source: File, target: File) {
        require(source.isDirectory) { "Source is not a directory: ${source.absolutePath}" }
        target.mkdirs()
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source)
            if (relative.path.isEmpty()) return@forEach
            if (relative.path.split(File.separator).any { it in EXCLUDED_DIR_NAMES }) return@forEach
            val dest = File(target, relative.path)
            if (file.isDirectory) {
                dest.mkdirs()
            } else {
                dest.parentFile?.mkdirs()
                Files.copy(file.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        File(target, "gradlew").takeIf { it.isFile }?.setExecutable(true)
    }
}
