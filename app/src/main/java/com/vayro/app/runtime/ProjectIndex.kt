package com.vayro.app.runtime

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class IndexedFile(
    val path: String,
    val language: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val symbols: List<String>,
    val imports: List<String>,
)

data class ProjectIndexSnapshot(
    val projectId: String,
    val generatedAtMillis: Long,
    val rootFingerprint: String,
    val files: List<IndexedFile>,
)

class ProjectIndex(private val filesDir: File) {
    fun load(projectId: String): ProjectIndexSnapshot? {
        val file = indexFile(projectId)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val files = json.optJSONArray("files") ?: JSONArray()
            ProjectIndexSnapshot(
                projectId = projectId,
                generatedAtMillis = json.optLong("generatedAtMillis"),
                rootFingerprint = json.optString("rootFingerprint"),
                files = (0 until files.length()).mapNotNull { i ->
                    files.optJSONObject(i)?.let(::parseIndexedFile)
                },
            )
        }.getOrNull()
    }

    fun build(projectId: String, workspace: File): ProjectIndexSnapshot {
        val root = workspace.canonicalFile
        require(root.isDirectory) { "Project workspace does not exist" }
        val rootPath = root.toPath()
        val indexed = mutableListOf<IndexedFile>()
        root.walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .onEnter { directory ->
                if (directory == root) true else {
                    val relative = directory.relativeTo(root).invariantSeparatorsPath
                    !isExcluded(relative) &&
                        !Files.isSymbolicLink(directory.toPath()) &&
                        runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                }
            }
            .filter { file ->
                file.isFile &&
                    file.length() <= MAX_INDEXED_FILE_BYTES &&
                    !isExcluded(file.relativeTo(root).invariantSeparatorsPath) &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            }
            .take(MAX_INDEXED_FILES)
            .forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val text = runCatching { file.readText() }.getOrNull()
                if (text != null && !looksBinary(text)) {
                    val semantic = SemanticCodeEngine.parseFile(relative, text)
                    val combinedSymbols = (semantic.symbols.map { it.name } + extractSymbols(text)).distinct().take(MAX_SYMBOLS_PER_FILE)
                    val combinedImports = (semantic.rawImports + extractImports(text)).distinct().take(MAX_IMPORTS_PER_FILE)
                    indexed += IndexedFile(
                        path = relative,
                        language = semantic.language,
                        sizeBytes = file.length(),
                        lastModified = file.lastModified(),
                        symbols = combinedSymbols,
                        imports = combinedImports,
                    )
                }
            }

        val snapshot = ProjectIndexSnapshot(
            projectId = projectId,
            generatedAtMillis = System.currentTimeMillis(),
            rootFingerprint = quickFingerprint(root),
            files = indexed.sortedBy { it.path.lowercase() },
        )
        persist(snapshot)
        return snapshot
    }

    fun ensureFresh(projectId: String, workspace: File): ProjectIndexSnapshot {
        val current = load(projectId)
        val actualFingerprint = quickFingerprint(workspace)
        if (current != null && current.rootFingerprint == actualFingerprint) return current
        return build(projectId, workspace)
    }

    fun search(snapshot: ProjectIndexSnapshot, query: String, limit: Int = 12): List<IndexedFile> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return snapshot.files.take(limit)
        return snapshot.files
            .map { file -> file to relevance(file, normalized) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<IndexedFile, Int>> { it.second }.thenBy { it.first.path })
            .take(limit)
            .map { it.first }
    }

    private fun relevance(file: IndexedFile, query: String): Int {
        val path = file.path.lowercase()
        var score = 0
        if (path == query) score += 100
        if (path.substringAfterLast('/') == query) score += 75
        if (path.contains(query)) score += 35
        score += file.symbols.count { it.lowercase().contains(query) } * 20
        score += file.imports.count { it.lowercase().contains(query) } * 8
        if (file.language.lowercase() == query) score += 12
        return score
    }

    private fun persist(snapshot: ProjectIndexSnapshot) {
        val destination = indexFile(snapshot.projectId)
        destination.parentFile?.mkdirs()
        val files = JSONArray()
        snapshot.files.forEach { file ->
            files.put(
                JSONObject()
                    .put("path", file.path)
                    .put("language", file.language)
                    .put("sizeBytes", file.sizeBytes)
                    .put("lastModified", file.lastModified)
                    .put("symbols", JSONArray(file.symbols))
                    .put("imports", JSONArray(file.imports)),
            )
        }
        destination.writeText(
            JSONObject()
                .put("projectId", snapshot.projectId)
                .put("generatedAtMillis", snapshot.generatedAtMillis)
                .put("rootFingerprint", snapshot.rootFingerprint)
                .put("files", files)
                .toString(),
        )
    }

    private fun indexFile(projectId: String): File = File(filesDir, "indexes/$projectId.json")

    private fun parseIndexedFile(json: JSONObject): IndexedFile = IndexedFile(
        path = json.optString("path"),
        language = json.optString("language"),
        sizeBytes = json.optLong("sizeBytes"),
        lastModified = json.optLong("lastModified"),
        symbols = json.optJSONArray("symbols")?.let { array ->
            (0 until array.length()).map(array::getString)
        }.orEmpty(),
        imports = json.optJSONArray("imports")?.let { array ->
            (0 until array.length()).map(array::getString)
        }.orEmpty(),
    )

    private fun quickFingerprint(root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        root.walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .onEnter { directory ->
                directory == root || !isExcluded(directory.relativeTo(root).invariantSeparatorsPath)
            }
            .filter { it.isFile && !isExcluded(it.relativeTo(root).invariantSeparatorsPath) }
            .take(MAX_INDEXED_FILES)
            .forEach {
                digest.update(it.relativeTo(root).invariantSeparatorsPath.toByteArray())
                digest.update(':'.code.toByte())
                digest.update(it.length().toString().toByteArray())
                digest.update(':'.code.toByte())
                digest.update(it.lastModified().toString().toByteArray())
                count++
            }
        digest.update(count.toString().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fingerprint(files: List<IndexedFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.path }.forEach {
            digest.update((it.path + ":" + it.sizeBytes + ":" + it.lastModified).toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isExcluded(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        val name = normalized.substringAfterLast('/')
        return normalized == ".git" ||
            normalized.startsWith(".git/") ||
            normalized == ".claude" ||
            normalized.startsWith(".claude/") ||
            name in EXCLUDED_DIRECTORIES ||
            normalized.split('/').any { it in EXCLUDED_DIRECTORIES }
    }

    private fun looksBinary(text: String): Boolean =
        text.any { it.code == 0 || (it.code in 0..8 && it != '\n' && it != '\r' && it != '\t') }

    private fun languageFor(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "Kotlin"
            "java" -> "Java"
            "js", "mjs", "cjs" -> "JavaScript"
            "ts", "tsx" -> "TypeScript"
            "jsx" -> "JSX"
            "py" -> "Python"
            "rs" -> "Rust"
            "go" -> "Go"
            "c", "h" -> "C"
            "cc", "cpp", "cxx", "hpp" -> "C++"
            "swift" -> "Swift"
            "dart" -> "Dart"
            "json" -> "JSON"
            "xml" -> "XML"
            "yaml", "yml" -> "YAML"
            "md", "markdown" -> "Markdown"
            "html", "htm" -> "HTML"
            "css", "scss" -> "CSS"
            "sh", "bash", "zsh" -> "Shell"
            else -> "Text"
        }
    }

    private fun extractSymbols(text: String): List<String> {
        val patterns = listOf(
            Regex("""^\s*(?:data\s+|sealed\s+|enum\s+)?(?:class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""^\s*(?:suspend\s+)?fun\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""^\s*(?:async\s+)?(?:def|function)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
            Regex("""^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*="""),
            Regex("""^\s*(?:func)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
        )
        return text.lineSequence()
            .flatMap { line ->
                patterns.asSequence().mapNotNull { it.find(line)?.groupValues?.getOrNull(1) }
            }
            .distinct()
            .toList()
    }

    private fun extractImports(text: String): List<String> {
        val patterns = listOf(
            Regex("""^\s*import\s+(.+)$"""),
            Regex("""^\s*from\s+([A-Za-z0-9_./@-]+)\s+import\s+.+$"""),
            Regex("""^\s*#include\s*[<"]([^>"]+)[>"]"""),
            Regex("""^\s*use\s+([A-Za-z0-9_:]+)"""),
            Regex("""^\s*require\(\s*['"]([^'"]+)['"]\s*\)"""),
        )
        return text.lineSequence()
            .flatMap { line ->
                patterns.asSequence().mapNotNull { it.find(line)?.groupValues?.getOrNull(1)?.trim() }
            }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    companion object {
        private const val MAX_SCAN_DEPTH = 12
        private const val MAX_INDEXED_FILES = 5_000
        private const val MAX_INDEXED_FILE_BYTES = 1_000_000L
        private const val MAX_SYMBOLS_PER_FILE = 80
        private const val MAX_IMPORTS_PER_FILE = 120
        private val EXCLUDED_DIRECTORIES = setOf(
            "node_modules", "build", ".gradle", ".idea", ".next", ".cache",
            ".venv", "venv", "__pycache__", "dist", "target",
        )
    }
}
