package com.vayro.app.workspace

import android.content.Context
import com.vayro.app.model.Project
import java.io.File

/**
 * Domain controller for project workspace detection, metadata inference,
 * and workspace filesystem organization.
 */
class ProjectWorkspaceController(private val context: Context) {

    private val workspacesDir: File
        get() = File(context.filesDir, "workspaces").apply { mkdirs() }

    fun projectWorkspaceRoot(project: Project): File {
        val base = File(workspacesDir, project.id)
        return if (project.rootPath.isNotBlank()) File(base, project.rootPath) else base
    }

    fun detectNestedProjectRoot(projectId: String): String? {
        val base = File(workspacesDir, projectId)
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

    fun detectImportedProjectMetadata(root: File): Pair<String, String> {
        val names = root.walkTopDown().maxDepth(3).filter(File::isFile).map { it.name.lowercase() }.toSet()
        return when {
            names.any { it == "settings.gradle.kts" || it == "build.gradle.kts" } -> "Imported Gradle project" to "Kotlin"
            names.any { it == "settings.gradle" || it == "build.gradle" } -> "Imported Gradle project" to "Java"
            "package.json" in names && names.any { it == "tsconfig.json" || it.endsWith(".ts") || it.endsWith(".tsx") } -> "Imported web project" to "TypeScript"
            "package.json" in names -> "Imported Node.js project" to "JavaScript"
            "pubspec.yaml" in names -> "Imported Flutter project" to "Dart"
            names.any { it == "cargo.toml" } -> "Imported Rust project" to "Rust"
            names.any { it == "go.mod" } -> "Imported Go project" to "Go"
            names.any { it == "pyproject.toml" || it == "requirements.txt" || it.endsWith(".py") } -> "Imported Python project" to "Python"
            names.any { it == "cmakelists.txt" || it.endsWith(".cpp") || it.endsWith(".c") } -> "Imported C/C++ project" to "C++"
            else -> "Imported device project" to "General"
        }
    }

}
