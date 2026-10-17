package com.vayro.app.factory.build

import com.vayro.app.factory.model.AppBlueprint
import com.vayro.app.factory.model.BuildArtifactType
import com.vayro.app.factory.model.BuildMode
import com.vayro.app.factory.model.BuildResult
import com.vayro.app.git.automation.WorkflowAutomation
import com.vayro.app.network.GitHubClient
import com.vayro.app.network.selectBuildArtifact
import com.vayro.app.runtime.RuntimeInstaller
import com.vayro.app.runtime.readTailText
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * One-tap GitHub CI build for App Factory projects.
 *
 * Pipeline: generate the project's `android-build.yml` workflow (existing
 * generator) → create the GitHub repository when missing → commit and push the
 * project from inside the Linux runtime (the same credential bind-mount the
 * GitHub sign-in uses, so the stored `gh` credentials authorize the push) →
 * the push triggers the workflow; if no run appears, the workflow is
 * dispatched explicitly → poll the run to completion → download the uploaded
 * artifact, extract the built file, and hand it back as a [BuildResult] so
 * the factory's existing Install button can install it.
 *
 * Every failure returns an honest failed [BuildResult] carrying the real
 * error (git output, run conclusion, API message) — never a fake success.
 * Blocking: call on an IO dispatcher.
 */
class GitHubCiBuildRunner(
    private val installer: RuntimeInstaller,
    private val gitHubClient: GitHubClient,
    private val workflowAutomation: WorkflowAutomation = WorkflowAutomation(gitHubClient),
    private val cacheDir: File,
) {
    fun build(
        projectDir: File,
        blueprint: AppBlueprint,
        artifactType: BuildArtifactType,
        login: String,
        token: String,
        onLog: (String) -> Unit = {},
    ): BuildResult {
        val startedAt = System.currentTimeMillis()
        val logs = mutableListOf<String>()
        fun log(line: String) {
            logs += line
            onLog(line)
        }
        fun fail(message: String, workflowFile: File? = null): BuildResult {
            log("ERROR: $message")
            return BuildResult(
                success = false,
                artifactType = artifactType,
                buildMode = BuildMode.GITHUB_ACTIONS,
                ciWorkflowFile = workflowFile,
                logs = logs.toList(),
                durationMs = System.currentTimeMillis() - startedAt,
                errorMessage = message,
            )
        }

        log("Building ${blueprint.name} (${artifactType.displayName}) via GitHub Actions CI...")

        if (!installer.isInstalled()) {
            return fail("The Linux runtime is not ready yet. Finish the Kodex setup first, then try the GitHub CI build again.")
        }

        // (a) Workflow file — the existing generator.
        val workflowFile = try {
            AppBuildOrchestrator.generateGitHubActionsWorkflow(projectDir, blueprint, artifactType)
        } catch (e: Exception) {
            return fail("Could not generate the CI workflow file: ${e.message}")
        }
        log("Workflow ready: .github/workflows/android-build.yml")

        val repoName = repoNameFor(blueprint.name)
        val repoFullName = "$login/$repoName"

        // (b) Repository — create it on the signed-in account when missing.
        try {
            if (gitHubClient.getRepository(token, repoFullName) == null) {
                gitHubClient.createRepository(token, repoName, "Built with the Kodex App Factory", private = true)
                log("Created GitHub repository $repoFullName")
            } else {
                log("Using existing GitHub repository $repoFullName")
            }
        } catch (e: Exception) {
            return fail("GitHub repository check failed for $repoFullName: ${e.message}", workflowFile)
        }

        // Baseline so polling only accepts the run this push produces.
        val baselineRunId = runCatching {
            gitHubClient.listWorkflowRunsForWorkflow(token, repoFullName, WORKFLOW_FILE).maxOfOrNull { it.id } ?: 0L
        }.getOrDefault(0L)

        // (b2) Commit + push from inside the runtime, with the gh credential binds.
        val pushFailure = pushProject(projectDir, repoName, repoFullName, login, ::log)
        if (pushFailure != null) return fail(pushFailure, workflowFile)
        log("Project pushed to $repoFullName (branch main). Waiting for the GitHub Actions run...")

        // (c) Poll for the run; dispatch explicitly if the push trigger produced none.
        val deadline = System.currentTimeMillis() + RUN_TIMEOUT_MS
        var dispatched = false
        var runId: Long? = null
        var conclusion: String? = null
        var runUrl: String? = null
        while (System.currentTimeMillis() < deadline) {
            val candidate = runCatching {
                gitHubClient.listWorkflowRunsForWorkflow(token, repoFullName, WORKFLOW_FILE)
                    .filter { it.id > baselineRunId }
                    .maxByOrNull { it.id }
            }.getOrNull()
            if (candidate != null) {
                if (runId != candidate.id) {
                    runId = candidate.id
                    log("GitHub Actions run #${candidate.runNumber} (${candidate.id}) is ${candidate.status}...")
                }
                if (candidate.status == "completed") {
                    conclusion = candidate.conclusion
                    runUrl = candidate.htmlUrl
                    break
                }
            } else if (!dispatched && System.currentTimeMillis() > deadline - RUN_TIMEOUT_MS + DISPATCH_FALLBACK_AFTER_MS) {
                dispatched = true
                log("No run appeared from the push; dispatching the workflow directly...")
                val dispatchResult = workflowAutomation.dispatch(token, repoFullName, WORKFLOW_FILE, "main")
                if (!dispatchResult.isSuccess) {
                    return fail("Workflow dispatch failed: ${dispatchResult.error}", workflowFile)
                }
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        val finishedRunId = runId
            ?: return fail("Timed out waiting for the GitHub Actions run to appear. Open $repoFullName on GitHub to check Actions.", workflowFile)
        if (conclusion != "success") {
            return fail(
                "GitHub Actions run did not succeed (conclusion: ${conclusion ?: "not completed"}). Details: ${runUrl ?: "https://github.com/$repoFullName/actions"}",
                workflowFile,
            )
        }
        log("GitHub Actions run succeeded. Downloading the built ${artifactType.displayName}...")

        // (d) Artifact download + extraction.
        return try {
            val artifacts = gitHubClient.listRunArtifacts(token, repoFullName, finishedRunId)
            val artifact = selectBuildArtifact(artifacts, artifactType.extension)
                ?: return fail("The CI run succeeded but uploaded no artifact. Check the run: $runUrl", workflowFile)
            val downloadDir = File(cacheDir, "factory-ci/$repoName").apply { mkdirs() }
            val zipFile = File(downloadDir, "artifact-${artifact.id}.zip")
            gitHubClient.downloadArtifactZip(token, repoFullName, artifact.id, zipFile)
            val extracted = extractArtifact(zipFile, File(downloadDir, "run-$finishedRunId"), artifactType.extension)
                ?: return fail("The downloaded artifact did not contain a .${artifactType.extension} file.", workflowFile)
            log("Downloaded ${extracted.name} (${extracted.length()} bytes) from GitHub Actions.")
            BuildResult(
                success = true,
                artifactType = artifactType,
                buildMode = BuildMode.GITHUB_ACTIONS,
                outputFile = extracted,
                ciWorkflowFile = workflowFile,
                logs = logs.toList(),
                durationMs = System.currentTimeMillis() - startedAt,
            )
        } catch (e: Exception) {
            fail("Artifact download failed: ${e.message}", workflowFile)
        }
    }

    /**
     * Initializes (when needed), commits, and pushes [projectDir] to
     * [repoFullName]'s main branch from inside the Linux runtime. Returns null
     * on success, or the failure message to report.
     */
    private fun pushProject(
        projectDir: File,
        guestSlug: String,
        repoFullName: String,
        login: String,
        log: (String) -> Unit,
    ): String? {
        val runtime = installer.installedRuntime()
        val remoteUrl = "https://github.com/$repoFullName.git"

        fun runGuest(command: List<String>, guestBinary: String? = null): Pair<Int, String> {
            val outputFile = File(cacheDir, "factory-ci-push-${System.nanoTime()}.log")
            return try {
                val process = installer.process(
                    runtime.proot,
                    runtime.rootfs,
                    projectDir,
                    mapOf(
                        "GIT_TERMINAL_PROMPT" to "0",
                        "GH_PROMPT_DISABLED" to "1",
                        "GH_NO_UPDATE_NOTIFIER" to "1",
                    ),
                    listOfNotNull(guestBinary) + command,
                    guestWorkspacePath = "/workspace/$guestSlug",
                    outputFile = outputFile,
                    extraBinds = installer.githubCredentialBinds(),
                )
                val finished = process.waitFor(COMMAND_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                if (!finished) {
                    process.destroyForcibly()
                    return -1 to "Command timed out: ${command.joinToString(" ")}"
                }
                process.exitValue() to outputFile.readTailText(MAX_OUTPUT_BYTES).trim()
            } finally {
                outputFile.delete()
            }
        }

        if (!File(projectDir, ".git").isDirectory) {
            val (initExit, initOut) = runGuest(listOf("init", "-b", "main"), guestBinary = "git")
            if (initExit != 0) return "git init failed: ${initOut.takeLast(300)}"
        }
        // Let the gh CLI register itself as git's credential helper so the
        // HTTPS push uses the signed-in account (best effort — a real auth
        // failure still surfaces from the push itself below).
        runGuest(listOf("auth", "setup-git"), guestBinary = RuntimeInstaller.GITHUB_CLI_GUEST_PATH)

        val (addExit, addOut) = runGuest(listOf("add", "-A"), guestBinary = "git")
        if (addExit != 0) return "git add failed: ${addOut.takeLast(300)}"

        val (commitExit, commitOut) = runGuest(
            listOf(
                "-c", "user.name=$login",
                "-c", "user.email=$login@users.noreply.github.com",
                "commit", "-m", "Kodex App Factory build",
            ),
            guestBinary = "git",
        )
        if (commitExit != 0 && !commitOut.contains("nothing to commit", ignoreCase = true)) {
            return "git commit failed: ${commitOut.takeLast(300)}"
        }

        val (remoteExit, _) = runGuest(listOf("remote", "get-url", "origin"), guestBinary = "git")
        val remoteArgs = if (remoteExit == 0) listOf("remote", "set-url", "origin", remoteUrl)
        else listOf("remote", "add", "origin", remoteUrl)
        val (remoteSetExit, remoteSetOut) = runGuest(remoteArgs, guestBinary = "git")
        if (remoteSetExit != 0) return "git remote setup failed: ${remoteSetOut.takeLast(300)}"

        val (branchExit, branchOut) = runGuest(listOf("branch", "-M", "main"), guestBinary = "git")
        if (branchExit != 0) return "git branch failed: ${branchOut.takeLast(300)}"

        log("Pushing project to GitHub...")
        val (pushExit, pushOut) = runGuest(listOf("push", "-u", "origin", "main"), guestBinary = "git")
        if (pushExit != 0) {
            return "git push failed: ${pushOut.takeLast(400).ifBlank { "exit code $pushExit" }}"
        }
        return null
    }

    /** Extracts [zipFile] into [destDir] and returns the newest file matching [extension], if any. */
    private fun extractArtifact(zipFile: File, destDir: File, extension: String): File? {
        destDir.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = File(destDir, entry.name)
                // Zip-slip guard: entries must stay inside destDir.
                if (target.canonicalPath.startsWith(destDir.canonicalPath)) {
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output -> zip.copyTo(output) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return destDir.walkTopDown()
            .filter { it.isFile && it.extension.equals(extension, ignoreCase = true) }
            .maxByOrNull { it.lastModified() }
    }

    companion object {
        private const val WORKFLOW_FILE = "android-build.yml"
        private const val POLL_INTERVAL_MS = 15_000L
        private const val RUN_TIMEOUT_MS = 20L * 60L * 1000L
        private const val DISPATCH_FALLBACK_AFTER_MS = 120_000L
        private const val COMMAND_TIMEOUT_MINUTES = 10L
        private const val MAX_OUTPUT_BYTES = 256 * 1024

        /** GitHub-safe repository name for a project title (also a valid guest workspace slug). */
        internal fun repoNameFor(projectName: String): String {
            val slug = projectName.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(60)
                .trim('-')
            return slug.ifBlank { "kodex-app" }
        }
    }
}
