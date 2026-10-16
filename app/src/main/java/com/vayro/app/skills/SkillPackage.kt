package com.vayro.app.skills

import com.vayro.app.mcp.McpRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Thrown when a skill manifest or package fails validation. */
class SkillValidationException(message: String) : Exception(message)

/**
 * Manifest for a skill package (skill.json at the package root).
 * A skill bundles an entry prompt plus metadata declaring which MCP tools it needs.
 */
data class SkillManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String = "",
    val requiredTools: List<String> = emptyList(),
    val entryPromptFile: String = "SKILL.md",
    val minAppVersion: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("description", description)
        put("author", author)
        put("requiredTools", JSONArray(requiredTools))
        put("entryPromptFile", entryPromptFile)
        minAppVersion?.let { put("minAppVersion", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): SkillManifest {
            val id = json.optString("id", "")
            if (id.isBlank()) throw SkillValidationException("skill.json: 'id' is required")
            val name = json.optString("name", "")
            if (name.isBlank()) throw SkillValidationException("skill.json: 'name' is required")
            val version = json.optString("version", "")
            if (version.isBlank()) throw SkillValidationException("skill.json: 'version' is required")
            if (!isValidVersion(version)) {
                throw SkillValidationException("skill.json: 'version' must be numeric dotted (e.g. 1.2.0), got '$version'")
            }
            val tools = mutableListOf<String>()
            val toolsArr = json.optJSONArray("requiredTools")
            if (toolsArr != null) {
                for (i in 0 until toolsArr.length()) tools.add(toolsArr.getString(i))
            }
            return SkillManifest(
                id = id,
                name = name,
                version = version,
                description = json.optString("description", ""),
                author = json.optString("author", ""),
                requiredTools = tools,
                entryPromptFile = json.optString("entryPromptFile", "SKILL.md").takeIf { it.isNotBlank() } ?: "SKILL.md",
                minAppVersion = json.optString("minAppVersion").takeIf { it.isNotBlank() }
            )
        }

        fun isValidVersion(version: String): Boolean =
            version.matches(Regex("\\d+(\\.\\d+)*(-[A-Za-z0-9.]+)?"))
    }
}

/** A validated skill package: manifest plus its on-disk directory. */
data class SkillPackage(
    val manifest: SkillManifest,
    val dir: File
) {
    /** Reads the entry prompt file; null when the file cannot be read. */
    fun entryPrompt(): String? {
        val file = File(dir, manifest.entryPromptFile)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }
}

/**
 * Loads and validates skill packages from directories.
 * A valid package is a directory containing skill.json and the entry prompt file.
 */
class SkillLoader {

    fun loadFromDir(dir: File): SkillPackage {
        if (!dir.isDirectory) {
            throw SkillValidationException("Skill package is not a directory: ${dir.path}")
        }
        val manifestFile = File(dir, "skill.json")
        if (!manifestFile.isFile) {
            throw SkillValidationException("Skill package is missing skill.json: ${dir.path}")
        }
        val manifest = try {
            SkillManifest.fromJson(JSONObject(manifestFile.readText()))
        } catch (e: SkillValidationException) {
            throw e
        } catch (e: Exception) {
            throw SkillValidationException("skill.json is not valid JSON: ${e.message}")
        }
        val entryFile = File(dir, manifest.entryPromptFile)
        if (!entryFile.isFile) {
            throw SkillValidationException(
                "Skill '${manifest.id}' is missing its entry prompt file '${manifest.entryPromptFile}'"
            )
        }
        return SkillPackage(manifest, dir)
    }
}

/**
 * Versioned install/update/remove for skill packages, stored under <baseDir>/skills/<id>/.
 * Each install snapshots the package directory; downgrades are refused unless forced.
 */
class SkillRegistry(baseDir: File) {

    private val skillsDir: File = File(baseDir.apply { mkdirs() }, "skills").apply { mkdirs() }
    private val loader = SkillLoader()
    private val lock = Any()

    fun list(): List<SkillPackage> {
        synchronized(lock) {
            val dirs = skillsDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
            return dirs.mapNotNull { dir ->
                runCatching { loader.loadFromDir(dir) }.getOrNull()
            }.sortedBy { it.manifest.id }
        }
    }

    fun get(skillId: String): SkillPackage? {
        synchronized(lock) {
            val dir = File(skillsDir, skillId)
            if (!dir.isDirectory) return null
            return runCatching { loader.loadFromDir(dir) }.getOrNull()
        }
    }

    /**
     * Installs a skill package. Refuses to downgrade an existing install unless
     * [force] is true. Returns the installed package.
     */
    fun install(pkg: SkillPackage, force: Boolean = false): SkillPackage {
        synchronized(lock) {
            val existing = get(pkg.manifest.id)
            if (existing != null && !force) {
                val cmp = McpRegistry.compareVersions(pkg.manifest.version, existing.manifest.version)
                if (cmp < 0) {
                    throw SkillValidationException(
                        "Refusing to downgrade skill '${pkg.manifest.id}' " +
                            "from ${existing.manifest.version} to ${pkg.manifest.version} (use force=true)"
                    )
                }
            }
            val target = File(skillsDir, pkg.manifest.id)
            if (target.exists()) target.deleteRecursively()
            pkg.dir.copyRecursively(target)
            return loader.loadFromDir(target)
        }
    }

    /**
     * Updates an installed skill. Requires the new version to be strictly newer
     * than the installed one (downgrades go through install(force=true)).
     */
    fun update(pkg: SkillPackage): SkillPackage {
        synchronized(lock) {
            val existing = get(pkg.manifest.id)
                ?: throw SkillValidationException("Skill '${pkg.manifest.id}' is not installed")
            val cmp = McpRegistry.compareVersions(pkg.manifest.version, existing.manifest.version)
            if (cmp <= 0) {
                throw SkillValidationException(
                    "Update requires a newer version than installed " +
                        "(${existing.manifest.version}); got ${pkg.manifest.version}"
                )
            }
            return install(pkg, force = true)
        }
    }

    fun remove(skillId: String): Boolean {
        synchronized(lock) {
            val dir = File(skillsDir, skillId)
            if (!dir.isDirectory) return false
            return dir.deleteRecursively()
        }
    }
}
