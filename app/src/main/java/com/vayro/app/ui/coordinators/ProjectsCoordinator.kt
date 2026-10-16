package com.vayro.app.ui.coordinators

import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.vayro.app.data.AppPreferences
import com.vayro.app.model.Project
import com.vayro.app.model.ProjectChat
import com.vayro.app.model.ProjectKind
import com.vayro.app.model.generateQuickChatIdentity
import com.vayro.app.model.projectSlug
import com.vayro.app.runtime.WorkspaceCheckpoints
import com.vayro.app.workspace.FileOperationsController
import com.vayro.app.workspace.ProjectWorkspaceController
import com.vayro.app.workspace.WorkspaceCheckpoint
import com.vayro.app.workspace.WorkspaceCheckpointManager
import java.io.File
import java.util.UUID

/**
 * Projects domain coordinator (Milestone 1).
 *
 * Explicit owner of project lifecycle operations: quick project creation,
 * device-folder tree copies, nested project-root detection, project rename,
 * project reload (including orphaned-workspace recovery), workspace
 * checkpoints (create/rollback/list), and full workspace backup/restore.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class ProjectsCoordinator(
    private val host: CoordinatorHost,
    private val preferences: AppPreferences,
    private val projectWorkspaceController: ProjectWorkspaceController,
    private val fileOperationsController: FileOperationsController,
    private val editorCheckpoints: WorkspaceCheckpoints,
    private val checkpointManager: WorkspaceCheckpointManager,
    private val workspaceRootFor: (Project) -> File,
    private val openProject: (Project) -> Unit,
    private val refreshProjectFiles: () -> Unit,
) {
    fun createQuickProject() {
        val busy = host.state().isRunning || host.state().projectTerminalRunning
        val identity = generateQuickChatIdentity(host.state().projects.mapTo(mutableSetOf()) { it.slug })
        val project = Project(
            name = identity.displayName,
            description = "Quick project workspace",
            language = "General",
            slug = identity.slug,
            kind = ProjectKind.QUICK_PROJECT,
        )
        val firstChat = ProjectChat(title = "New chat")
        File(host.appContext.filesDir, "workspaces/${project.id}").mkdirs()
        preferences.saveProjectChats(project.id, listOf(firstChat))
        host.updateState { it.copy(projects = listOf(project) + it.projects) }
        preferences.saveProjects(host.state().projects)
        if (busy) {
            host.updateState {
                it.copy(toastMessage = "${project.name} created. The current task continues in the background; open it when ready.")
            }
            return
        }
        openProject(project)
    }

    private fun copyTreeToDirectory(treeUri: Uri, destinationDir: File): Int {
        val app = host.appContext
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val documentFile = DocumentFile.fromTreeUri(app, treeUri)
            ?: error("Cannot access the selected device folder")
        var copiedCount = 0
        val maxFiles = 3000

        fun copyTree(doc: DocumentFile, targetDir: File) {
            targetDir.mkdirs()
            val files = doc.listFiles()
            for (f in files) {
                val name = f.name ?: continue
                if (name in setOf(".git", ".gradle", "build", "node_modules", ".idea", ".DS_Store", "__pycache__", ".next")) {
                    continue
                }
                val destFile = File(targetDir, name)
                if (f.isDirectory) {
                    copyTree(f, destFile)
                } else if (f.isFile) {
                    if (copiedCount++ > maxFiles) break
                    app.contentResolver.openInputStream(f.uri)?.use { inStream ->
                        destFile.outputStream().use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                }
            }
        }

        copyTree(documentFile, destinationDir)
        return copiedCount
    }

    fun renameProject(projectId: String, newName: String) {
        val clean = newName.replace(Regex("\\s+"), " ").trim().take(60)
        if (clean.isBlank()) return
        host.updateState { current ->
            val projects = current.projects.map { project ->
                if (project.id == projectId) project.copy(name = clean) else project
            }
            val active = current.activeProject?.let { project ->
                if (project.id == projectId) project.copy(name = clean) else project
            }
            current.copy(projects = projects, activeProject = active)
        }
        preferences.saveProjects(host.state().projects)
    }

    private fun detectNestedProjectRoot(project: Project): String? {
        val base = File(host.appContext.filesDir, "workspaces/${project.id}")
        if (!base.isDirectory) return null
        val visible = base.listFiles().orEmpty().filterNot { file ->
            file.name == ".claude" || file.name == ".claude.json"
        }
        val onlyDirectory = visible.singleOrNull()?.takeIf(File::isDirectory) ?: return null
        val containsProjectFiles = onlyDirectory.walkTopDown()
            .maxDepth(2)
            .any { it.isFile && it.name !in setOf(".DS_Store", ".claude.json") }
        return onlyDirectory.name.takeIf { containsProjectFiles && !it.contains("..") }
    }

    fun createWorkspaceCheckpoint(label: String): WorkspaceCheckpoint? {
        val project = host.state().activeProject ?: return null
        val workspace = workspaceRootFor(project)
        val ckpt = checkpointManager.createCheckpoint(project.id, workspace, label)
        if (ckpt != null) {
            host.updateState { it.copy(toastMessage = "Checkpoint created: ${ckpt.label}") }
        }
        return ckpt
    }

    fun rollbackWorkspaceCheckpoint(checkpointId: String): Boolean {
        val project = host.state().activeProject ?: return false
        val workspace = workspaceRootFor(project)
        val ok = checkpointManager.rollbackToCheckpoint(project.id, checkpointId, workspace)
        if (ok) {
            refreshProjectFiles()
            host.updateState { it.copy(toastMessage = "Rolled back to checkpoint") }
        } else {
            host.updateState { it.copy(toastMessage = "Rollback failed") }
        }
        return ok
    }

    fun listWorkspaceCheckpoints(): List<WorkspaceCheckpoint> {
        val project = host.state().activeProject ?: return emptyList()
        return checkpointManager.listCheckpoints(project.id)
    }

    fun backupAllWorkspaces(): Int {
        val app = host.appContext
        val externalDir = app.getExternalFilesDir("backups") ?: File(app.filesDir, "backups")
        val count = checkpointManager.backupAllWorkspaces(externalDir)
        if (count > 0) {
            host.updateState { it.copy(toastMessage = "Backed up $count projects to external storage") }
        } else {
            host.updateState { it.copy(toastMessage = "No projects to backup") }
        }
        return count
    }

    fun restoreAllWorkspaces(): Int {
        val app = host.appContext
        val externalDir = app.getExternalFilesDir("backups") ?: File(app.filesDir, "backups")
        var count = checkpointManager.restoreAllWorkspaces(externalDir)
        if (count == 0) {
            // Migration fallback: check legacy package backups directory if current path has no backups
            val legacyExternalDir = File("/storage/emulated/0/Android/data/com.vayro.app/files/backups")
            if (legacyExternalDir.isDirectory) {
                count = checkpointManager.restoreAllWorkspaces(legacyExternalDir)
            }
        }
        if (count > 0) {
            reloadProjects()
            host.updateState { it.copy(toastMessage = "Restored $count projects from backup") }
        } else {
            host.updateState { it.copy(toastMessage = "No backup projects found") }
        }
        return count
    }

    fun reloadProjects() {
        val loaded = preferences.loadProjects().toMutableList()
        val loadedIds = loaded.map { it.id }.toSet()
        val workspacesDir = File(host.appContext.filesDir, "workspaces")
        workspacesDir.listFiles()?.filter { it.isDirectory && it.name !in loadedIds }?.forEach { dir ->
            val p = Project(
                id = dir.name,
                name = dir.name.replace('-', ' ').replaceFirstChar { it.uppercase() },
                description = "Restored workspace",
                language = "General",
                slug = projectSlug(dir.name),
            )
            loaded.add(p)
        }
        preferences.saveProjects(loaded)
        host.updateState { it.copy(projects = loaded) }
    }
}
