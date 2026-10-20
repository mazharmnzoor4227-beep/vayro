package com.vayro.app.workspace

import com.vayro.app.AppCrashLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class WorkspaceCheckpoint(
    val id: String,
    val projectId: String,
    val label: String,
    val timestamp: Long,
    val fileCount: Int,
    val modifiedFiles: List<String> = emptyList(),
) {
    val formattedDate: String
        get() = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

class WorkspaceCheckpointManager(private val filesDir: File) {

    private fun checkpointsBaseDir(projectId: String): File =
        File(filesDir, "checkpoints/$projectId/snapshots").apply { mkdirs() }

    private fun indexFile(projectId: String): File =
        File(checkpointsBaseDir(projectId), "checkpoints_index.json")

    /**
     * Creates a full workspace snapshot checkpoint with metadata.
     */
    fun createCheckpoint(projectId: String, workspaceDir: File, label: String): WorkspaceCheckpoint? {
        return runCatching {
            val id = "ckpt_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val checkpointDir = File(checkpointsBaseDir(projectId), id).apply { mkdirs() }
            val backupDir = File(checkpointDir, "files").apply { mkdirs() }

            var fileCount = 0
            val relativePaths = mutableListOf<String>()

            val canonicalWorkspace = workspaceDir.canonicalFile
            canonicalWorkspace.walkTopDown()
                .filter { it.isFile && !isIgnored(it, canonicalWorkspace) }
                .forEach { sourceFile ->
                    val relPath = sourceFile.relativeTo(canonicalWorkspace).invariantSeparatorsPath
                    val destFile = File(backupDir, relPath)
                    destFile.parentFile?.mkdirs()
                    sourceFile.copyTo(destFile, overwrite = true)
                    fileCount++
                    relativePaths.add(relPath)
                }

            val checkpoint = WorkspaceCheckpoint(
                id = id,
                projectId = projectId,
                label = label.ifBlank { "Checkpoint: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}" },
                timestamp = System.currentTimeMillis(),
                fileCount = fileCount,
                modifiedFiles = relativePaths.take(50),
            )

            // Write metadata
            val metaFile = File(checkpointDir, "meta.json")
            val json = JSONObject().apply {
                put("id", checkpoint.id)
                put("projectId", checkpoint.projectId)
                put("label", checkpoint.label)
                put("timestamp", checkpoint.timestamp)
                put("fileCount", checkpoint.fileCount)
                put("modifiedFiles", JSONArray(checkpoint.modifiedFiles))
            }
            metaFile.writeText(json.toString(2))

            // Update index
            val existing = listCheckpoints(projectId).toMutableList()
            existing.add(0, checkpoint)
            saveIndex(projectId, existing)

            AppCrashLogger.checkpoint("WorkspaceCheckpointCreated", "Project $projectId: ${checkpoint.label} ($fileCount files)")
            checkpoint
        }.getOrElse {
            AppCrashLogger.log("Failed to create checkpoint: ${it.message}")
            null
        }
    }

    /**
     * Returns all saved checkpoints for a project, newest first.
     */
    fun listCheckpoints(projectId: String): List<WorkspaceCheckpoint> {
        val file = indexFile(projectId)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<WorkspaceCheckpoint>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val modifiedArr = obj.optJSONArray("modifiedFiles") ?: JSONArray()
                val modifiedList = (0 until modifiedArr.length()).map { modifiedArr.getString(it) }
                list.add(
                    WorkspaceCheckpoint(
                        id = obj.getString("id"),
                        projectId = obj.getString("projectId"),
                        label = obj.getString("label"),
                        timestamp = obj.getLong("timestamp"),
                        fileCount = obj.getInt("fileCount"),
                        modifiedFiles = modifiedList,
                    )
                )
            }
            list.sortedByDescending { it.timestamp }
        }.getOrElse { emptyList() }
    }

    /**
     * Reverts the workspace directory to the exact state saved in this checkpoint.
     */
    fun rollbackToCheckpoint(projectId: String, checkpointId: String, workspaceDir: File): Boolean {
        return runCatching {
            val checkpointDir = File(checkpointsBaseDir(projectId), checkpointId)
            val backupDir = File(checkpointDir, "files")
            if (!backupDir.exists()) return false

            val canonicalWorkspace = workspaceDir.canonicalFile

            // Delete existing non-ignored files in workspace
            canonicalWorkspace.walkTopDown()
                .filter { it.isFile && !isIgnored(it, canonicalWorkspace) }
                .forEach { it.delete() }

            // Restore from backup
            backupDir.walkTopDown()
                .filter { it.isFile }
                .forEach { sourceFile ->
                    val relPath = sourceFile.relativeTo(backupDir).invariantSeparatorsPath
                    val destFile = File(canonicalWorkspace, relPath)
                    destFile.parentFile?.mkdirs()
                    sourceFile.copyTo(destFile, overwrite = true)
                }

            AppCrashLogger.checkpoint("WorkspaceCheckpointRollback", "Project $projectId rolled back to $checkpointId")
            true
        }.getOrElse {
            AppCrashLogger.log("Rollback failed: ${it.message}")
            false
        }
    }


    /**
     * Atomically writes file content using SafeFileOps to prevent partial writes.
     */
    fun atomicWriteFile(targetFile: File, content: String): Boolean = SafeFileOps.atomicWriteText(targetFile, content)

    fun writeSafely(targetFile: File, content: String): Boolean = atomicWriteFile(targetFile, content)

    /**
     * Verifies that a checkpoint's metadata and backup file trees are intact.
     */
    fun verifyCheckpointIntegrity(projectId: String, checkpointId: String): Boolean {
        val checkpointDir = File(checkpointsBaseDir(projectId), checkpointId)
        val metaFile = File(checkpointDir, "meta.json")
        val backupDir = File(checkpointDir, "files")
        if (!metaFile.isFile || !backupDir.isDirectory) return false
        return runCatching {
            val json = JSONObject(metaFile.readText())
            json.getString("id") == checkpointId && json.getString("projectId") == projectId
        }.getOrDefault(false)
    }

    /**
     * Inspects workspace for corrupted or truncated files (e.g. 0-byte source/build files or temp leftovers).
     */
    fun detectWorkspaceCorruption(workspaceDir: File): List<String> {
        if (!workspaceDir.isDirectory) return listOf("Workspace directory does not exist: ${workspaceDir.path}")
        val issues = mutableListOf<String>()
        val canonicalWorkspace = workspaceDir.canonicalFile
        canonicalWorkspace.walkTopDown()
            .filter { it.isFile && !isIgnored(it, canonicalWorkspace) }
            .forEach { file ->
                val rel = file.relativeTo(canonicalWorkspace).invariantSeparatorsPath
                if (file.name.contains(".tmp_")) {
                    issues.add("Stale temporary file left by crashed process: $rel")
                } else if (file.length() == 0L && (file.extension in setOf("kt", "java", "json", "gradle", "kts", "xml", "py", "js", "ts"))) {
                    issues.add("Potentially truncated empty source file: $rel")
                }
            }
        return issues
    }


    private fun saveIndex(projectId: String, checkpoints: List<WorkspaceCheckpoint>) {
        val arr = JSONArray()
        checkpoints.forEach { ck ->
            arr.put(JSONObject().apply {
                put("id", ck.id)
                put("projectId", ck.projectId)
                put("label", ck.label)
                put("timestamp", ck.timestamp)
                put("fileCount", ck.fileCount)
                put("modifiedFiles", JSONArray(ck.modifiedFiles))
            })
        }
        SafeFileOps.atomicWriteText(indexFile(projectId), arr.toString(2))
    }

    private fun isIgnored(file: File, base: File): Boolean {
        val rel = file.relativeTo(base).invariantSeparatorsPath
        return rel.startsWith(".git/") ||
            rel.startsWith(".idea/") ||
            rel.startsWith(".gradle/") ||
            rel.startsWith("build/") ||
            rel.startsWith("node_modules/") ||
            rel.startsWith(".agents/") ||
            rel.startsWith("target/")
    }

    /**
     * Mirrors all workspaces in internal storage to an external backup directory to prevent data loss.
     */
    fun backupAllWorkspaces(externalBackupDir: File): Int {
        return runCatching {
            val workspacesDir = File(filesDir, "workspaces")
            if (!workspacesDir.isDirectory) return 0
            val targetDir = File(externalBackupDir, "workspaces_mirror").apply { mkdirs() }
            var count = 0
            workspacesDir.listFiles()?.filter { it.isDirectory }?.forEach { projDir ->
                val destProj = File(targetDir, projDir.name).apply { mkdirs() }
                projDir.walkTopDown()
                    .filter { it.isFile && !isIgnored(it, projDir) }
                    .forEach { file ->
                        val rel = file.relativeTo(projDir).invariantSeparatorsPath
                        val destFile = File(destProj, rel)
                        destFile.parentFile?.mkdirs()
                        file.copyTo(destFile, overwrite = true)
                    }
                count++
            }
            AppCrashLogger.checkpoint("WorkspacesBackupCompleted", "Mirrored $count workspaces to ${targetDir.path}")
            count
        }.getOrElse {
            AppCrashLogger.log("Failed to mirror workspaces: ${it.message}")
            0
        }
    }

    /**
     * Restores workspaces from external backup directory into internal storage.
     */
    fun restoreAllWorkspaces(externalBackupDir: File): Int {
        return runCatching {
            val sourceDir = File(externalBackupDir, "workspaces_mirror")
            if (!sourceDir.isDirectory) return 0
            val workspacesDir = File(filesDir, "workspaces").apply { mkdirs() }
            var count = 0
            sourceDir.listFiles()?.filter { it.isDirectory }?.forEach { projDir ->
                val destProj = File(workspacesDir, projDir.name).apply { mkdirs() }
                projDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val rel = file.relativeTo(projDir).invariantSeparatorsPath
                        val destFile = File(destProj, rel)
                        destFile.parentFile?.mkdirs()
                        if (!destFile.exists()) {
                            file.copyTo(destFile, overwrite = false)
                        }
                    }
                count++
            }
            AppCrashLogger.checkpoint("WorkspacesRestoreCompleted", "Restored $count workspaces from ${sourceDir.path}")
            count
        }.getOrElse {
            AppCrashLogger.log("Failed to restore workspaces: ${it.message}")
            0
        }
    }
}

