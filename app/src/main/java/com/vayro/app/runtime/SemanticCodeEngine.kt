package com.vayro.app.runtime

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class SymbolKind {
    CLASS,
    INTERFACE,
    ENUM,
    OBJECT,
    STRUCT,
    FUNCTION,
    METHOD,
    CONSTRUCTOR,
    PROPERTY,
    VARIABLE,
    CONSTANT,
    TYPE_ALIAS,
    PACKAGE,
    IMPORT,
}

enum class ReferenceType {
    CALL,
    TYPE_USAGE,
    IMPORT_USAGE,
}

data class CodeSymbol(
    val name: String,
    val kind: SymbolKind,
    val line: Int,
    val characterOffset: Int = 0,
    val containerName: String? = null,
    val signature: String? = null,
    val visibility: String = "public",
    val isAsync: Boolean = false,
    val superTypes: List<String> = emptyList(),
    val docComment: String? = null,
)

data class SymbolReference(
    val symbolName: String,
    val line: Int,
    val sourceFilePath: String,
    val referenceType: ReferenceType,
)

data class FileSemanticSummary(
    val filePath: String,
    val language: String,
    val symbols: List<CodeSymbol>,
    val rawImports: List<String>,
    val calls: List<String> = emptyList(),
    val contentHash: String = "",
)

data class DependencyGraph(
    val imports: Map<String, List<String>>, // filePath -> list of imported filePaths
    val dependents: Map<String, List<String>>, // filePath -> list of filePaths depending on it
)

/**
 * Multi-language Semantic Code Intelligence Engine.
 * Extracts AST-level declarations, methods, properties, cross-file imports,
 * call references, and dependency graphs across Kotlin, Java, TS/JS, Python, Go, Rust, C/C++, and Shell.
 */
object SemanticCodeEngine {

    private val cache = ConcurrentHashMap<String, FileSemanticSummary>()

    fun detectLanguage(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "Kotlin"
            "java" -> "Java"
            "ts", "tsx" -> "TypeScript"
            "js", "jsx", "mjs", "cjs" -> "JavaScript"
            "py", "pyw" -> "Python"
            "go" -> "Go"
            "rs" -> "Rust"
            "c", "h" -> "C"
            "cpp", "cc", "cxx", "hpp" -> "C++"
            "php", "phtml" -> "PHP"
            "dart" -> "Dart"
            "sh", "bash", "zsh" -> "Shell"
            "json" -> "JSON"
            "yaml", "yml" -> "YAML"
            "xml", "html" -> "XML"
            "md", "markdown" -> "Markdown"
            else -> "Text"
        }
    }

    /**
     * Parses source text of a file and returns its semantic symbols, imports, and calls.
     */
    fun parseFile(filePath: String, content: String): FileSemanticSummary {
        val hash = hashContent(content)
        val cached = cache[filePath]
        if (cached != null && cached.contentHash == hash) {
            return cached
        }

        val language = detectLanguage(filePath)
        val symbols = mutableListOf<CodeSymbol>()
        val imports = mutableListOf<String>()
        val calls = mutableListOf<String>()

        when (language) {
            "Kotlin" -> parseKotlin(content, symbols, imports, calls)
            "Java" -> parseJava(content, symbols, imports, calls)
            "TypeScript", "JavaScript" -> parseTypeScriptJs(content, symbols, imports, calls)
            "Python" -> parsePython(content, symbols, imports, calls)
            "Go" -> parseGo(content, symbols, imports, calls)
            "Rust" -> parseRust(content, symbols, imports, calls)
            "C", "C++" -> parseCpp(content, symbols, imports, calls)
            "PHP" -> parsePhp(content, symbols, imports, calls)
            "Dart" -> parseDart(content, symbols, imports, calls)
            "Shell" -> parseShell(content, symbols, imports, calls)
            else -> parseGeneric(content, symbols, imports, calls)
        }

        val summary = FileSemanticSummary(
            filePath = filePath,
            language = language,
            symbols = symbols.distinctBy { "${it.kind}:${it.name}:${it.line}" },
            rawImports = imports.distinct(),
            calls = calls.distinct(),
            contentHash = hash,
        )
        cache[filePath] = summary
        return summary
    }

    /**
     * Builds a cross-file dependency graph for the entire project workspace.
     */
    fun buildDependencyGraph(files: Map<String, String>): DependencyGraph {
        val summaries = files.map { (path, content) ->
            path to parseFile(path, content)
        }.toMap()

        val allPaths = files.keys.toList()
        val importsMap = mutableMapOf<String, List<String>>()
        val dependentsMap = mutableMapOf<String, MutableList<String>>()

        allPaths.forEach { dependentsMap[it] = mutableListOf() }

        for ((path, summary) in summaries) {
            val resolved = resolveImportsToFilePaths(path, summary.rawImports, allPaths)
            importsMap[path] = resolved
            for (imported in resolved) {
                dependentsMap.getOrPut(imported) { mutableListOf() }.add(path)
            }
        }

        return DependencyGraph(
            imports = importsMap,
            dependents = dependentsMap.mapValues { it.value.distinct() },
        )
    }

    /**
     * Resolves raw import strings (e.g. `import com.foo.bar.MyClass` or `import "./utils"`)
     * to matching workspace relative paths.
     */
    fun resolveImportsToFilePaths(
        currentFile: String,
        rawImports: List<String>,
        allWorkspaceFiles: List<String>,
    ): List<String> {
        val resolved = mutableListOf<String>()
        val currentDir = currentFile.substringBeforeLast('/', "")

        for (raw in rawImports) {
            val clean = raw.trim().trim('\'', '"', ';')

            // Relative path imports (e.g. ./utils, ../components/button)
            if (clean.startsWith("./") || clean.startsWith("../")) {
                val candidateDir = if (currentDir.isEmpty()) "" else "$currentDir/"
                val combined = normalizePath("$candidateDir$clean")
                val matched = allWorkspaceFiles.firstOrNull { file ->
                    file == combined ||
                        file.substringBeforeLast('.') == combined ||
                        file == "$combined/index.ts" ||
                        file == "$combined/index.js"
                }
                if (matched != null) resolved.add(matched)
                continue
            }

            // Package / module based resolution (Kotlin, Java, Python, Go)
            val parts = clean.split('.', '/')
            val targetName = parts.lastOrNull().orEmpty()
            if (targetName.isNotEmpty() && targetName != "*") {
                val matched = allWorkspaceFiles.filter { file ->
                    val fileNameWithoutExt = file.substringAfterLast('/').substringBeforeLast('.')
                    fileNameWithoutExt.equals(targetName, ignoreCase = true)
                }
                resolved.addAll(matched)
            }
        }
        return resolved.distinct().filter { it != currentFile }
    }

    /**
     * Finds declaration definitions across indexed files matching [query].
     */
    fun findDefinitions(
        query: String,
        summaries: Collection<FileSemanticSummary>,
        exactMatch: Boolean = false,
    ): List<Pair<String, CodeSymbol>> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()

        return summaries.flatMap { file ->
            file.symbols
                .filter { sym ->
                    if (exactMatch) sym.name.equals(normalized, ignoreCase = true)
                    else sym.name.lowercase().contains(normalized)
                }
                .map { file.filePath to it }
        }
    }

    /**
     * Finds references (usages, calls, imports) of [symbolName] across indexed files.
     */
    fun findReferences(
        symbolName: String,
        summaries: Collection<FileSemanticSummary>,
    ): List<SymbolReference> {
        val normalized = symbolName.trim()
        if (normalized.isBlank()) return emptyList()

        val results = mutableListOf<SymbolReference>()
        for (file in summaries) {
            // Check calls
            if (file.calls.contains(normalized)) {
                results.add(
                    SymbolReference(
                        symbolName = normalized,
                        line = 1,
                        sourceFilePath = file.filePath,
                        referenceType = ReferenceType.CALL,
                    )
                )
            }
            // Check imports
            if (file.rawImports.any { it.contains(normalized) }) {
                results.add(
                    SymbolReference(
                        symbolName = normalized,
                        line = 1,
                        sourceFilePath = file.filePath,
                        referenceType = ReferenceType.IMPORT_USAGE,
                    )
                )
            }
        }
        return results
    }


    // --- Language Parsers ---

    private fun parseKotlin(
        content: String,
        symbols: MutableList<CodeSymbol>,
        imports: MutableList<String>,
        calls: MutableList<String>,
    ) {
        val classRegex = Regex("""^\s*(?:@\w+\s+)*(?:(?:data|sealed|enum|annotation|open|abstract|internal|public|private)\s+)*(class|interface|object|enum\s+class)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val funRegex = Regex("""^\s*(?:@\w+\s+)*(?:(?:override|suspend|open|abstract|internal|public|private|inline|infix)\s+)*fun\s+(?:<[^>]+>\s+)?([A-Za-z_][A-Za-z0-9_]*)""")
        val propRegex = Regex("""^\s*(?:(?:override|const|internal|public|private)\s+)*(val|var)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val importRegex = Regex("""^\s*import\s+([A-Za-z0-9_.*]+)""")
        val packageRegex = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""")
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

        var currentClass: String? = null
        var lastDocComment: String? = null
        val docBuffer = StringBuilder()
        var offset = 0

        content.lines().forEachIndexed { idx, line ->
            val lineNum = idx + 1
            val trimmed = line.trim()

            if (trimmed.startsWith("/**") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                docBuffer.append(trimmed.removePrefix("/**").removePrefix("*").removePrefix("/*").removeSuffix("*/").trim()).append(" ")
            } else if (trimmed.isNotBlank()) {
                lastDocComment = if (docBuffer.isNotEmpty()) docBuffer.toString().trim() else null
                docBuffer.setLength(0)
            } else {
                docBuffer.setLength(0)
                lastDocComment = null
            }

            packageRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                symbols.add(CodeSymbol(it, SymbolKind.PACKAGE, lineNum, offset, docComment = lastDocComment))
            }
            importRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                imports.add(it)
                symbols.add(CodeSymbol(it, SymbolKind.IMPORT, lineNum, offset, docComment = lastDocComment))
            }
            classRegex.find(line)?.let { m ->
                val kindStr = m.groupValues[1]
                val name = m.groupValues[2]
                val kind = when {
                    kindStr.contains("interface") -> SymbolKind.INTERFACE
                    kindStr.contains("enum") -> SymbolKind.ENUM
                    kindStr.contains("object") -> SymbolKind.OBJECT
                    else -> SymbolKind.CLASS
                }
                currentClass = name
                val supers = run {
                    val nameIdx = line.indexOf(name)
                    if (nameIdx >= 0) {
                        val afterName = line.substring(nameIdx + name.length)
                        var depth = 0
                        var colonIdx = -1
                        for (i in afterName.indices) {
                            when (afterName[i]) {
                                '(', '<', '[' -> depth++
                                ')', '>', ']' -> if (depth > 0) depth--
                                ':' -> if (depth == 0) { colonIdx = i; break }
                                '{' -> if (depth == 0) break
                            }
                        }
                        if (colonIdx >= 0) {
                            afterName.substring(colonIdx + 1).substringBefore("{").split(",")
                                .map { it.trim().substringBefore("(").substringBefore("<").trim() }
                                .filter { it.isNotEmpty() && !it.startsWith("val ") && !it.startsWith("var ") }
                        } else emptyList()
                    } else emptyList()
                }
                symbols.add(CodeSymbol(name, kind, lineNum, offset, signature = trimmed, superTypes = supers, docComment = lastDocComment))
            }
            funRegex.find(line)?.let { m ->
                val name = m.groupValues[1]
                val kind = if (currentClass != null) SymbolKind.METHOD else SymbolKind.FUNCTION
                val isAsync = line.contains("suspend ")
                symbols.add(CodeSymbol(name, kind, lineNum, offset, containerName = currentClass, signature = trimmed, isAsync = isAsync, docComment = lastDocComment))
            }
            propRegex.find(line)?.let { m ->
                val name = m.groupValues[2]
                val kind = if (m.groupValues[1] == "val") SymbolKind.CONSTANT else SymbolKind.PROPERTY
                symbols.add(CodeSymbol(name, kind, lineNum, offset, containerName = currentClass, signature = trimmed, docComment = lastDocComment))
            }

            // Extract calls
            callRegex.findAll(line).forEach { m ->
                val callName = m.groupValues[1]
                if (callName !in KOTLIN_KEYWORDS) calls.add(callName)
            }

            offset += line.length + 1
        }
    }

    private fun parseJava(
        content: String,
        symbols: MutableList<CodeSymbol>,
        imports: MutableList<String>,
        calls: MutableList<String>,
    ) {
        val classRegex = Regex("""^\s*(?:(?:public|protected|private|abstract|static|final)\s+)*(class|interface|enum|@interface)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val methodRegex = Regex("""^\s*(?:(?:public|protected|private|static|final|abstract|synchronized)\s+)+[A-Za-z0-9_<>,\[\]]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val importRegex = Regex("""^\s*import\s+(?:static\s+)?([A-Za-z0-9_.*]+);""")
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

        var currentClass: String? = null
        var lastDocComment: String? = null
        val docBuffer = StringBuilder()
        var offset = 0

        content.lines().forEachIndexed { idx, line ->
            val lineNum = idx + 1
            val trimmed = line.trim()

            if (trimmed.startsWith("/**") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                docBuffer.append(trimmed.removePrefix("/**").removePrefix("*").removePrefix("/*").removeSuffix("*/").trim()).append(" ")
            } else if (trimmed.isNotBlank()) {
                lastDocComment = if (docBuffer.isNotEmpty()) docBuffer.toString().trim() else null
                docBuffer.setLength(0)
            } else {
                docBuffer.setLength(0)
                lastDocComment = null
            }

            importRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                imports.add(it)
                symbols.add(CodeSymbol(it, SymbolKind.IMPORT, lineNum, offset, docComment = lastDocComment))
            }
            classRegex.find(line)?.let { m ->
                val kindStr = m.groupValues[1]
                val name = m.groupValues[2]
                val kind = when (kindStr) {
                    "interface", "@interface" -> SymbolKind.INTERFACE
                    "enum" -> SymbolKind.ENUM
                    else -> SymbolKind.CLASS
                }
                currentClass = name
                val extendsMatch = Regex("""\bextends\s+([A-Za-z0-9_]+)""").find(line)?.groupValues?.getOrNull(1)
                val implementsMatch = Regex("""\bimplements\s+([A-Za-z0-9_,\s]+)""").find(line)?.groupValues?.getOrNull(1)
                val supers = listOfNotNull(extendsMatch) + (implementsMatch?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList())
                symbols.add(CodeSymbol(name, kind, lineNum, offset, signature = line.trim(), superTypes = supers, docComment = lastDocComment))
            }
            methodRegex.find(line)?.let { m ->
                val name = m.groupValues[1]
                symbols.add(CodeSymbol(name, SymbolKind.METHOD, lineNum, offset, containerName = currentClass, signature = line.trim(), docComment = lastDocComment))
            }
            callRegex.findAll(line).forEach { m ->
                val callName = m.groupValues[1]
                if (callName !in JAVA_KEYWORDS) calls.add(callName)
            }
            offset += line.length + 1
        }
    }

    private fun parseTypeScriptJs(
        content: String,
        symbols: MutableList<CodeSymbol>,
        imports: MutableList<String>,
        calls: MutableList<String>,
    ) {
        val classRegex = Regex("""^\s*(?:export\s+)?(?:default\s+)?(?:abstract\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val ifaceRegex = Regex("""^\s*(?:export\s+)?interface\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val typeRegex = Regex("""^\s*(?:export\s+)?type\s+([A-Za-z_][A-Za-z0-9_]*)\s*=""")
        val funcRegex = Regex("""^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val constFuncRegex = Regex("""^\s*(?:export\s+)?const\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?:async\s*)?\(""")
        val importRegex = Regex("""import\s+(?:(?:.+?)\s+from\s+)?['"]([^'"]+)['"]""")
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

        var offset = 0
        content.lines().forEachIndexed { idx, line ->
            val lineNum = idx + 1
            importRegex.findAll(line).forEach { m ->
                m.groupValues.getOrNull(1)?.let {
                    imports.add(it)
                    symbols.add(CodeSymbol(it, SymbolKind.IMPORT, lineNum, offset))
                }
            }
            classRegex.find(line)?.groupValues?.getOrNull(1)?.let { name ->
                val extendsMatch = Regex("""\bextends\s+([A-Za-z0-9_]+)""").find(line)?.groupValues?.getOrNull(1)
                val implementsMatch = Regex("""\bimplements\s+([A-Za-z0-9_,\s]+)""").find(line)?.groupValues?.getOrNull(1)
                val supers = listOfNotNull(extendsMatch) + (implementsMatch?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList())
                symbols.add(CodeSymbol(name, SymbolKind.CLASS, lineNum, offset, signature = line.trim(), superTypes = supers))
            }
            ifaceRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                val extendsMatch = Regex("""\bextends\s+([A-Za-z0-9_,\s]+)""").find(line)?.groupValues?.getOrNull(1)
                val supers = extendsMatch?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                symbols.add(CodeSymbol(it, SymbolKind.INTERFACE, lineNum, offset, signature = line.trim(), superTypes = supers))
            }
            typeRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                symbols.add(CodeSymbol(it, SymbolKind.TYPE_ALIAS, lineNum, offset, signature = line.trim()))
            }
            funcRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                symbols.add(CodeSymbol(it, SymbolKind.FUNCTION, lineNum, offset, signature = line.trim(), isAsync = line.contains("async ")))
            }
            constFuncRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                symbols.add(CodeSymbol(it, SymbolKind.FUNCTION, lineNum, offset, signature = line.trim(), isAsync = line.contains("async")))
            }
            callRegex.findAll(line).forEach { m ->
                val callName = m.groupValues[1]
                if (callName !in JS_KEYWORDS) calls.add(callName)
            }
            offset += line.length + 1
        }
    }

    private fun parsePython(
        content: String,
        symbols: MutableList<CodeSymbol>,
        imports: MutableList<String>,
        calls: MutableList<String>,
    ) {
        val classRegex = Regex("""^\s*class\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val defRegex = Regex("""^(\s*)(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val importRegex = Regex("""^\s*(?:from\s+([A-Za-z0-9_.]+)\s+import|import\s+([A-Za-z0-9_.]+))""")
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

        var currentClass: String? = null
        var classIndent = -1
        var offset = 0

        content.lines().forEachIndexed { idx, line ->
            val lineNum = idx + 1
            importRegex.find(line)?.let { m ->
                val imp = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: m.groupValues.getOrNull(2)
                if (imp != null) {
                    imports.add(imp)
                    symbols.add(CodeSymbol(imp, SymbolKind.IMPORT, lineNum, offset))
                }
            }
            classRegex.find(line)?.groupValues?.getOrNull(1)?.let { name ->
                currentClass = name
                classIndent = line.takeWhile { it.isWhitespace() }.length
                val supers = Regex("""class\s+[A-Za-z0-9_]+\s*\(([^)]+)\)""").find(line)?.groupValues?.getOrNull(1)
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() && it != "object" } ?: emptyList()
                symbols.add(CodeSymbol(name, SymbolKind.CLASS, lineNum, offset, signature = line.trim(), superTypes = supers))
            }
            defRegex.find(line)?.let { m ->
                val indent = m.groupValues[1].length
                val name = m.groupValues[2]
                val isMethod = currentClass != null && indent > classIndent
                val kind = if (isMethod) SymbolKind.METHOD else SymbolKind.FUNCTION
                val isAsync = line.contains("async def")
                symbols.add(CodeSymbol(name, kind, lineNum, offset, containerName = if (isMethod) currentClass else null, signature = line.trim(), isAsync = isAsync))
            }
            callRegex.findAll(line).forEach { m ->
                val callName = m.groupValues[1]
                if (callName !in PY_KEYWORDS) calls.add(callName)
            }
            offset += line.length + 1
        }
    }

    private fun parseGo(
        content: String,
        symbols: MutableList<CodeSymbol>,
        imports: MutableList<String>,
        calls: MutableList<String>,
    ) {
        val packageRegex = Regex("""^\s*package\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val importRegex = Regex("""^\s*(?:import\s+)?["']([^"']+)["']""")
        val typeRegex = Regex("""^\s*type\s+([A-Za-z_][A-Za-z0-9_]*)\s+(struct|interface)""")
        val funcRegex = Regex("""^\s*func\s+(?:\([^)]+\)\s+)?([A-Za-z_][A-Za-z0-9_]*)""")
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

        var offset = 0
        content.lines().forEachIndexed { idx, line ->
            val lineNum = idx + 1
            packageRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                symbols.add(CodeSymbol(it, SymbolKind.PACKAGE, lineNum, offset))
            }
            importRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                imports.add(it)
                symbols.add(CodeSymbol(it, SymbolKind.IMPORT, lineNum, offset))
            }
            typeRegex.find(line)?.let { m ->
                val name = m.groupValues[1]
                val kind = if (m.groupValues[2] == "interface") SymbolKind.INTERFACE else SymbolKind.STRUCT
                symbols.add(CodeSymbol(name, kind, lineNum, offset, signature = line.trim()))
            }
            funcRegex.find(line)?.groupValues?.getOrNull(1)?.let { name ->
                symbols.add(CodeSymbol(name, SymbolKind.FUNCTION, lineNum, offset, signature = line.trim()))
            }
            callRegex.findAll(line).forEach { m ->
                val callName = m.groupValues[1]
                if (callName !in GO_KEYWORDS) calls.add(callName)
            }
            offset += line.length + 1
        }
    }

    private fun parseRust(
        content: String,
        symbols: MutableList<CodeSymbol>,
        imports: MutableList<String>,
        calls: MutableList<String>,
    ) {
        val useRegex = Regex("""^\s*(?:pub\s+)?use\s+([A-Za-z0-9_:]+)""")
        val structRegex = Regex("""^\s*(?:pub\s+)?(?:struct|enum|trait)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val fnRegex = Regex("""^\s*(?:pub\s+)?(?:async\s+)?fn\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

        var offset = 0
        content.lines().forEachIndexed { idx, line ->
            val lineNum = idx + 1
            useRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                imports.add(it)
                symbols.add(CodeSymbol(it, SymbolKind.IMPORT, lineNum, offset))
            }
            structRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                symbols.add(CodeSymbol(it, SymbolKind.STRUCT, lineNum, offset, signature = line.trim()))
            }
            fnRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                symbols.add(CodeSymbol(it, SymbolKind.FUNCTION, lineNum, offset, signature = line.trim()))
            }
            callRegex.findAll(line).forEach { m ->
                val callName = m.groupValues[1]
                if (callName !in RUST_KEYWORDS) calls.add(callName)
            }
            offset += line.length + 1
        }
    }

    private fun parseCpp(
        content: String,
        symbols: MutableList<CodeSymbol>,
        imports: MutableList<String>,
        calls: MutableList<String>,
    ) {
        val includeRegex = Regex("""^\s*#include\s*[<"]([^>"]+)[>"]""")
        val classRegex = Regex("""^\s*(?:class|struct)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val fnRegex = Regex("""^\s*(?:[A-Za-z0-9_*&:]+\s+)+([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)\s*[{;]""")

        var offset = 0
        content.lines().forEachIndexed { idx, line ->
            val lineNum = idx + 1
            includeRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                imports.add(it)
                symbols.add(CodeSymbol(it, SymbolKind.IMPORT, lineNum, offset))
            }
            classRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                symbols.add(CodeSymbol(it, SymbolKind.CLASS, lineNum, offset, signature = line.trim()))
            }
            fnRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                symbols.add(CodeSymbol(it, SymbolKind.FUNCTION, lineNum, offset, signature = line.trim()))
            }
            offset += line.length + 1
        }
    }

    private fun parseShell(
        content: String,
        symbols: MutableList<CodeSymbol>,
        imports: MutableList<String>,
        calls: MutableList<String>,
    ) {
        val fnRegex = Regex("""^\s*(?:function\s+)?([A-Za-z0-9_-]+)\s*\(\)\s*\{""")
        val sourceRegex = Regex("""^\s*(?:source|\.)\s+([^\s]+)""")

        var offset = 0
        content.lines().forEachIndexed { idx, line ->
            val lineNum = idx + 1
            sourceRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                imports.add(it)
                symbols.add(CodeSymbol(it, SymbolKind.IMPORT, lineNum, offset))
            }
            fnRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                symbols.add(CodeSymbol(it, SymbolKind.FUNCTION, lineNum, offset, signature = line.trim()))
            }
            offset += line.length + 1
        }
    }

    private fun parsePhp(
        content: String,
        symbols: MutableList<CodeSymbol>,
        imports: MutableList<String>,
        calls: MutableList<String>,
    ) {
        val nsRegex = Regex("""^\s*namespace\s+([A-Za-z0-9_\\]+)\s*;""")
        val useRegex = Regex("""^\s*use\s+([A-Za-z0-9_\\]+)(?:\s+as\s+([A-Za-z0-9_]+))?\s*;""")
        val requireRegex = Regex("""^\s*(?:require|require_once|include|include_once)\s*\(?['"]([^'"]+)['"]\)?\s*;""")
        val classRegex = Regex("""^\s*(?:(?:final|abstract|readonly)\s+)*(class|interface|trait|enum)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s+extends\s+([A-Za-z0-9_\\]+))?(?:\s+implements\s+([^{]+))?""")
        val fnRegex = Regex("""^\s*(?:(?:public|protected|private|static|final|abstract)\s+)*function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val constRegex = Regex("""^\s*(?:(?:public|protected|private)\s+)?const\s+([A-Za-z_][A-Za-z0-9_]*)\s*=""")
        val propRegex = Regex("""^\s*(?:(?:public|protected|private|static|readonly)\s+)+(?:[A-Za-z0-9_\\?]+\s+)?\$([A-Za-z_][A-Za-z0-9_]*)""")
        val callRegex = Regex("""(?:->|::|\b)([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

        var currentClass: String? = null
        var lastDocComment: String? = null
        val docBuffer = StringBuilder()
        var offset = 0

        content.lines().forEachIndexed { idx, line ->
            val lineNum = idx + 1
            val trimmed = line.trim()

            if (trimmed.startsWith("/**") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                docBuffer.append(trimmed.removePrefix("/**").removePrefix("*").removePrefix("/*").removeSuffix("*/").trim()).append(" ")
            } else if (trimmed.isNotBlank()) {
                lastDocComment = if (docBuffer.isNotEmpty()) docBuffer.toString().trim() else null
                docBuffer.setLength(0)
            } else {
                docBuffer.setLength(0)
                lastDocComment = null
            }

            nsRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                symbols.add(CodeSymbol(it, SymbolKind.PACKAGE, lineNum, offset, docComment = lastDocComment))
            }
            useRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                imports.add(it)
                symbols.add(CodeSymbol(it, SymbolKind.IMPORT, lineNum, offset, docComment = lastDocComment))
            }
            requireRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                imports.add(it)
                symbols.add(CodeSymbol(it, SymbolKind.IMPORT, lineNum, offset, docComment = lastDocComment))
            }
            classRegex.find(line)?.let { m ->
                val kindStr = m.groupValues[1]
                val name = m.groupValues[2]
                val extendsName = m.groupValues.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
                val implementsNames = m.groupValues.getOrNull(4)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                val supers = listOfNotNull(extendsName) + implementsNames

                val kind = when (kindStr) {
                    "interface", "trait" -> SymbolKind.INTERFACE
                    "enum" -> SymbolKind.ENUM
                    else -> SymbolKind.CLASS
                }
                currentClass = name
                symbols.add(
                    CodeSymbol(
                        name = name,
                        kind = kind,
                        line = lineNum,
                        characterOffset = offset,
                        signature = trimmed,
                        superTypes = supers,
                        docComment = lastDocComment,
                    )
                )
            }
            fnRegex.find(line)?.let { m ->
                val name = m.groupValues[1]
                val kind = if (currentClass != null) {
                    if (name == "__construct") SymbolKind.CONSTRUCTOR else SymbolKind.METHOD
                } else SymbolKind.FUNCTION
                symbols.add(
                    CodeSymbol(
                        name = name,
                        kind = kind,
                        line = lineNum,
                        characterOffset = offset,
                        containerName = currentClass,
                        signature = trimmed,
                        docComment = lastDocComment,
                    )
                )
            }
            constRegex.find(line)?.let { m ->
                val name = m.groupValues[1]
                symbols.add(CodeSymbol(name, SymbolKind.CONSTANT, lineNum, offset, containerName = currentClass, signature = trimmed, docComment = lastDocComment))
            }
            propRegex.find(line)?.let { m ->
                val name = m.groupValues[1]
                symbols.add(CodeSymbol(name, SymbolKind.PROPERTY, lineNum, offset, containerName = currentClass, signature = trimmed, docComment = lastDocComment))
            }

            callRegex.findAll(line).forEach { m ->
                val c = m.groupValues[1]
                if (c !in PHP_KEYWORDS && c != "function") calls.add(c)
            }

            offset += line.length + 1
        }
    }

    private fun parseDart(
        content: String,
        symbols: MutableList<CodeSymbol>,
        imports: MutableList<String>,
        calls: MutableList<String>,
    ) {
        val importRegex = Regex("""^\s*import\s+['"]([^'"]+)['"]""")
        val partRegex = Regex("""^\s*part\s+(?:of\s+)?['"]([^'"]+)['"]""")
        val classRegex = Regex("""^\s*(?:abstract\s+)?(class|mixin|enum|extension)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val fnRegex = Regex("""^\s*(?:(?:static|final|const|late)\s+)*(?:(?:void|Future<[^>]+>|Stream<[^>]+>|[A-Za-z0-9_<>?]+)\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)\s*(?:async\s*)?[{=>]""")
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

        var currentClass: String? = null
        var lastDocComment: String? = null
        val docBuffer = StringBuilder()
        var offset = 0

        content.lines().forEachIndexed { idx, line ->
            val lineNum = idx + 1
            val trimmed = line.trim()

            if (trimmed.startsWith("///") || trimmed.startsWith("//")) {
                docBuffer.append(trimmed.removePrefix("///").removePrefix("//").trim()).append(" ")
            } else if (trimmed.startsWith("/**") || trimmed.startsWith("*")) {
                docBuffer.append(trimmed.removePrefix("/**").removePrefix("*").removeSuffix("*/").trim()).append(" ")
            } else if (trimmed.isNotBlank()) {
                lastDocComment = if (docBuffer.isNotEmpty()) docBuffer.toString().trim() else null
                docBuffer.setLength(0)
            } else {
                docBuffer.setLength(0)
                lastDocComment = null
            }

            importRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                imports.add(it)
                symbols.add(CodeSymbol(it, SymbolKind.IMPORT, lineNum, offset, docComment = lastDocComment))
            }
            partRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                imports.add(it)
                symbols.add(CodeSymbol(it, SymbolKind.IMPORT, lineNum, offset, docComment = lastDocComment))
            }
            classRegex.find(line)?.let { m ->
                val kindStr = m.groupValues[1]
                val name = m.groupValues[2]
                val afterName = line.substringAfter(name).substringBefore("{")
                val extendsName = Regex("""\bextends\s+([A-Za-z0-9_]+)""").find(afterName)?.groupValues?.getOrNull(1)
                val withMixins = Regex("""\bwith\s+([A-Za-z0-9_,\s]+?)(?=\s+implements|\s*\{|$)""").find(afterName)
                    ?.groupValues?.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                val implementsNames = Regex("""\bimplements\s+([A-Za-z0-9_,\s]+?)(?=\s*\{|$)""").find(afterName)
                    ?.groupValues?.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                val supers = listOfNotNull(extendsName) + withMixins + implementsNames

                val kind = when (kindStr) {
                    "enum" -> SymbolKind.ENUM
                    "mixin" -> SymbolKind.INTERFACE
                    "extension" -> SymbolKind.OBJECT
                    else -> SymbolKind.CLASS
                }
                currentClass = name
                symbols.add(
                    CodeSymbol(
                        name = name,
                        kind = kind,
                        line = lineNum,
                        characterOffset = offset,
                        signature = trimmed,
                        superTypes = supers,
                        docComment = lastDocComment,
                    )
                )
            }
            fnRegex.find(line)?.let { m ->
                val name = m.groupValues[1]
                if (name !in DART_KEYWORDS && name != "class" && name != "mixin") {
                    val kind = if (currentClass != null) {
                        if (name == currentClass) SymbolKind.CONSTRUCTOR else SymbolKind.METHOD
                    } else SymbolKind.FUNCTION
                    val isAsync = line.contains("async")
                    symbols.add(
                        CodeSymbol(
                            name = name,
                            kind = kind,
                            line = lineNum,
                            characterOffset = offset,
                            containerName = currentClass,
                            signature = trimmed,
                            isAsync = isAsync,
                            docComment = lastDocComment,
                        )
                    )
                }
            }

            callRegex.findAll(line).forEach { m ->
                val c = m.groupValues[1]
                if (c !in DART_KEYWORDS) calls.add(c)
            }

            offset += line.length + 1
        }
    }

    private fun parseGeneric(
        content: String,
        symbols: MutableList<CodeSymbol>,
        imports: MutableList<String>,
        calls: MutableList<String>,
    ) {
        // Fallback generic scanner
        val classRegex = Regex("""\b(?:class|struct|interface)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val funcRegex = Regex("""\b(?:def|fun|function|fn|func)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        var offset = 0
        content.lines().forEachIndexed { idx, line ->
            val lineNum = idx + 1
            classRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                symbols.add(CodeSymbol(it, SymbolKind.CLASS, lineNum, offset))
            }
            funcRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                symbols.add(CodeSymbol(it, SymbolKind.FUNCTION, lineNum, offset))
            }
            offset += line.length + 1
        }
    }

    private fun hashContent(content: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun normalizePath(path: String): String {
        val segments = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { seg ->
            when {
                seg.isEmpty() || seg == "." -> {}
                seg == ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(seg)
            }
        }
        return segments.joinToString("/")
    }

    private val KOTLIN_KEYWORDS = setOf("if", "else", "when", "for", "while", "return", "throw", "is", "as")
    private val JAVA_KEYWORDS = setOf("if", "else", "switch", "for", "while", "return", "throw", "new", "super", "this")
    private val JS_KEYWORDS = setOf("if", "else", "switch", "for", "while", "return", "throw", "catch", "import", "require", "typeof")
    private val PY_KEYWORDS = setOf("if", "elif", "else", "for", "while", "return", "yield", "raise", "with", "print")
    private val GO_KEYWORDS = setOf("if", "else", "switch", "for", "return", "panic", "make", "new", "len", "cap")
    private val RUST_KEYWORDS = setOf("if", "else", "match", "for", "while", "return", "loop", "panic")
    private val PHP_KEYWORDS = setOf("if", "else", "elseif", "for", "foreach", "while", "return", "throw", "new", "echo", "die", "exit")
    private val DART_KEYWORDS = setOf("if", "else", "switch", "for", "while", "return", "throw", "new", "super", "this", "await", "yield")
}
