package com.vayro.app.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 3 — validates the project index: cross-file definition,
 * references, the dependency graph and genuine incremental invalidation.
 */
class ParserProjectIndexTest {

    private fun kotlinProject(): ParserProjectIndex {
        val index = ParserProjectIndex()
        index.indexFile(
            "math/Add.kt",
            """
            package math
            fun add(a: Int, b: Int): Int = a + b
            """.trimIndent(),
        )
        index.indexFile(
            "app/Main.kt",
            """
            package app
            import math.add
            fun main() {
                val total = add(2, 3)
                println(total)
            }
            """.trimIndent(),
        )
        return index
    }

    @Test
    fun `cross-file definition resolves through imports`() {
        val index = kotlinProject()
        val main = """
            package app
            import math.add
            fun main() {
                val total = add(2, 3)
                println(total)
            }
        """.trimIndent()
        // `add` call is on line 4 (1-based).
        val defs = index.definitions("app/Main.kt", main, 4, 16)
        assertEquals(1, defs.size)
        assertEquals("math/Add.kt", defs[0].filePath)
        assertEquals("add", defs[0].symbolName)
    }

    @Test
    fun `local definition resolves before imports`() {
        val index = ParserProjectIndex()
        index.indexFile(
            "A.kt",
            """
            fun helper(): Int = 1
            fun use() {
                val x = helper()
            }
            """.trimIndent(),
        )
        val content = """
            fun helper(): Int = 1
            fun use() {
                val x = helper()
            }
        """.trimIndent()
        val defs = index.definitions("A.kt", content, 3, 13)
        assertEquals(1, defs.size)
        assertEquals("A.kt", defs[0].filePath)
        assertEquals("helper", defs[0].symbolName)
    }

    @Test
    fun `unresolvable symbol returns honest empty`() {
        val index = kotlinProject()
        val main = "fun main() {\n    nosuchthing()\n}\n"
        index.indexFile("app/Main.kt", main)
        val defs = index.definitions("app/Main.kt", main, 2, 5)
        assertTrue(defs.isEmpty())
    }

    @Test
    fun `references span definition and call sites`() {
        val index = kotlinProject()
        val refs = index.references("app/Main.kt", "add")
        // Definition in math/Add.kt + call in app/Main.kt.
        assertTrue(refs.any { it.filePath == "math/Add.kt" && it.kind != "reference" })
        assertTrue(refs.any { it.filePath == "app/Main.kt" && it.kind == "reference" })
    }

    @Test
    fun `dependency graph links importer to imported`() {
        val index = kotlinProject()
        assertTrue(index.getImports("app/Main.kt").contains("math/Add.kt"))
        assertTrue(index.getDependents("math/Add.kt").contains("app/Main.kt"))
    }

    @Test
    fun `updateFile invalidates transitive dependents only`() {
        val index = kotlinProject()
        index.indexFile(
            "lib/Deep.kt",
            "package lib\nimport math.add\nfun deep() = add(1, 1)\n",
        )
        // app/Main.kt imports math.add; lib/Deep.kt imports math.add too.
        val affected = index.updateFile(
            "math/Add.kt",
            "package math\nfun add(a: Int, b: Int): Int = a + b + 1\n",
        )
        assertTrue(affected.contains("app/Main.kt"))
        assertTrue(affected.contains("lib/Deep.kt"))
        // The changed file itself is not listed as affected.
        assertTrue(!affected.contains("math/Add.kt"))
        // And the definition still resolves after the update.
        val main = "package app\nimport math.add\nfun main() {\n    val t = add(1, 2)\n}\n"
        val defs = index.definitions("app/Main.kt", main, 4, 13)
        assertEquals(1, defs.size)
        assertEquals("math/Add.kt", defs[0].filePath)
    }

    @Test
    fun `removeFile drops the file and reports dependents`() {
        val index = kotlinProject()
        val affected = index.removeFile("math/Add.kt")
        assertTrue(affected.contains("app/Main.kt"))
        assertTrue(index.getParsedFile("math/Add.kt") == null)
    }

    @Test
    fun `completions are scope aware`() {
        val index = ParserProjectIndex()
        val content = """
            fun computeTotal(items: List<String>): Int {
                val count = items.size
                return cou
            }
        """.trimIndent()
        index.indexFile("C.kt", content)
        // `cou` prefix on line 4: `count` is in scope.
        val items = index.completions("C.kt", content, 4, 13, "cou")
        assertTrue(items.contains("count"))
    }

    @Test
    fun `diagnostics surface parser errors`() {
        val index = ParserProjectIndex()
        val content = "fun broken( {\n}\n"
        index.indexFile("B.kt", content)
        val diags = index.diagnostics("B.kt", content)
        assertTrue(diags.any { it.severity == "error" })
    }

    @Test
    fun `rename locations cover definition and call sites`() {
        val index = kotlinProject()
        val main = """
            package app
            import math.add
            fun main() {
                val total = add(2, 3)
            }
        """.trimIndent()
        val locations = index.renameLocations("app/Main.kt", main, 4, 16)
        assertTrue(locations.any { it.filePath == "math/Add.kt" })
        assertTrue(locations.any { it.filePath == "app/Main.kt" && it.kind == "reference" })
    }

    @Test
    fun `typescript relative imports resolve`() {
        val index = ParserProjectIndex()
        index.indexFile(
            "src/utils.ts",
            "export function helper(): string { return \"h\"; }\n",
        )
        index.indexFile(
            "src/main.ts",
            "import { helper } from './utils';\nconst x = helper();\n",
        )
        assertTrue(index.getImports("src/main.ts").contains("src/utils.ts"))
        val content = "import { helper } from './utils';\nconst x = helper();\n"
        val defs = index.definitions("src/main.ts", content, 2, 11)
        assertEquals(1, defs.size)
        assertEquals("src/utils.ts", defs[0].filePath)
    }

    @Test
    fun `python from-import resolves to module file`() {
        val index = ParserProjectIndex()
        index.indexFile(
            "helpers.py",
            "def format_name(n):\n    return n.strip()\n",
        )
        index.indexFile(
            "main.py",
            "from helpers import format_name\nprint(format_name(\"x\"))\n",
        )
        assertTrue(index.getImports("main.py").contains("helpers.py"))
        val content = "from helpers import format_name\nprint(format_name(\"x\"))\n"
        val defs = index.definitions("main.py", content, 2, 8)
        assertEquals(1, defs.size)
        assertEquals("helpers.py", defs[0].filePath)
        assertEquals("format_name", defs[0].symbolName)
    }
}
