package com.vayro.app.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 3 gate — for each claimed language family (Kotlin/Java,
 * TypeScript/JavaScript, Python), cross-file definition, references, rename,
 * diagnostics and completion pass through the [LanguageIntelligenceProvider]
 * contract.
 */
class ParserBackedProviderTest {

    // ------------------------------------------------------------------
    // Kotlin
    // ------------------------------------------------------------------

    private fun kotlinIndex(): ParserProjectIndex {
        val index = ParserProjectIndex()
        index.indexFile(
            "calc/Calc.kt",
            """
            package calc
            class Calc {
                fun add(a: Int, b: Int): Int = a + b
            }
            """.trimIndent(),
        )
        return index
    }

    private val kotlinMain = """
        package app
        import calc.Calc
        fun main() {
            val c = Calc()
            val sum = c.add(1, 2)
        }
    """.trimIndent()

    @Test
    fun `kotlin definition resolves cross-file`() {
        val provider = ParserBackedIntelligenceProvider.kotlinProvider(kotlinIndex())
        assertFalse(provider.isFallback)
        // `Calc()` on line 4.
        val defs = provider.definitions("app/Main.kt", kotlinMain, 4, 13)
        assertEquals(1, defs.size)
        assertEquals("calc/Calc.kt", defs[0].filePath)
        assertEquals("Calc", defs[0].symbolName)
    }

    @Test
    fun `kotlin references find definition and call`() {
        val index = kotlinIndex()
        index.indexFile("app/Main.kt", kotlinMain)
        val provider = ParserBackedIntelligenceProvider.kotlinProvider(index)
        val refs = provider.references("app/Main.kt", kotlinMain, "add")
        assertTrue(refs.any { it.filePath == "calc/Calc.kt" })
        assertTrue(refs.any { it.kind == "reference" })
    }

    @Test
    fun `kotlin rename returns definition plus references`() {
        val index = ParserProjectIndex()
        val lib = "package calc\nfun add(a: Int, b: Int): Int = a + b\n"
        val main = "package app\nimport calc.add\nfun main() {\n    val t = add(1, 2)\n}\n"
        index.indexFile("calc/Add.kt", lib)
        index.indexFile("app/Main.kt", main)
        val provider = ParserBackedIntelligenceProvider.kotlinProvider(index)
        // Rename the `add` function: cursor on the call (line 4).
        val locations = provider.renameLocations("app/Main.kt", main, 4, 13, "plus")
        assertTrue(locations != null && locations.isNotEmpty())
        assertTrue(locations!!.any { it.filePath == "calc/Add.kt" })
        assertTrue(locations.any { it.filePath == "app/Main.kt" && it.kind == "reference" })
        // Invalid new name -> empty, not a guess.
        val invalid = provider.renameLocations("app/Main.kt", main, 4, 13, "not valid!")
        assertTrue(invalid != null && invalid.isEmpty())
    }

    @Test
    fun `kotlin diagnostics report real parser errors`() {
        val provider = ParserBackedIntelligenceProvider.kotlinProvider()
        val bad = "class Broken {\n    fun foo() {\n"
        val diags = provider.diagnostics("B.kt", bad)
        assertTrue(diags.any { it.severity == "error" && it.message.contains("Unclosed") })
        assertTrue(diags.all { it.source.isNotBlank() })
        // Clean code -> no diagnostics (honest empty, not noise).
        val good = "fun ok(): Int = 1\n"
        assertTrue(provider.diagnostics("G.kt", good).isEmpty())
    }

    @Test
    fun `kotlin completion is scope aware`() {
        val provider = ParserBackedIntelligenceProvider.kotlinProvider()
        val content = "fun main() {\n    val greeting = \"hi\"\n    print(gree\n}\n"
        val items = provider.completionItems("M.kt", content, 3, 14, "gree")
        assertTrue(items.contains("greeting"))
    }

    @Test
    fun `kotlin provider rejects unsupported language`() {
        val provider = ParserBackedIntelligenceProvider.kotlinProvider()
        assertTrue(provider.definitions("x.go", "package main\n", 1, 0).isEmpty())
    }

    // ------------------------------------------------------------------
    // TypeScript
    // ------------------------------------------------------------------

    private fun tsIndex(): ParserProjectIndex {
        val index = ParserProjectIndex()
        index.indexFile(
            "lib.ts",
            "export function double(n: number): number { return n * 2; }\n",
        )
        return index
    }

    private val tsMain = "import { double } from './lib';\nconst y = double(21);\n"

    @Test
    fun `typescript definition resolves cross-file`() {
        val provider = ParserBackedIntelligenceProvider.typeScriptProvider(tsIndex())
        assertFalse(provider.isFallback)
        val defs = provider.definitions("main.ts", tsMain, 2, 11)
        assertEquals(1, defs.size)
        assertEquals("lib.ts", defs[0].filePath)
        assertEquals("double", defs[0].symbolName)
    }

    @Test
    fun `typescript references diagnostics completion rename`() {
        val index = tsIndex()
        index.indexFile("main.ts", tsMain)
        val provider = ParserBackedIntelligenceProvider.typeScriptProvider(index)
        val refs = provider.references("main.ts", tsMain, "double")
        assertTrue(refs.any { it.filePath == "lib.ts" })
        assertTrue(refs.any { it.kind == "reference" })

        val diags = provider.diagnostics("b.ts", "function broken( {\n}\n")
        assertTrue(diags.any { it.severity == "error" })

        val content = "function compute() {\n  const value = 1;\n  return val\n}\n"
        val items = provider.completionItems("c.ts", content, 3, 12, "val")
        assertTrue(items.contains("value"))

        val rename = provider.renameLocations("main.ts", tsMain, 2, 11, "twice")
        assertTrue(rename != null && rename.any { it.filePath == "lib.ts" })
    }

    // ------------------------------------------------------------------
    // Python
    // ------------------------------------------------------------------

    private fun pyIndex(): ParserProjectIndex {
        val index = ParserProjectIndex()
        index.indexFile(
            "util.py",
            "def shout(text):\n    return text.upper()\n",
        )
        return index
    }

    private val pyMain = "from util import shout\nprint(shout(\"hi\"))\n"

    @Test
    fun `python definition resolves cross-file`() {
        val provider = ParserBackedIntelligenceProvider.pythonProvider(pyIndex())
        assertFalse(provider.isFallback)
        val defs = provider.definitions("main.py", pyMain, 2, 8)
        assertEquals(1, defs.size)
        assertEquals("util.py", defs[0].filePath)
        assertEquals("shout", defs[0].symbolName)
    }

    @Test
    fun `python references diagnostics completion rename`() {
        val index = pyIndex()
        index.indexFile("main.py", pyMain)
        val provider = ParserBackedIntelligenceProvider.pythonProvider(index)
        val refs = provider.references("main.py", pyMain, "shout")
        assertTrue(refs.any { it.filePath == "util.py" })
        assertTrue(refs.any { it.kind == "reference" })

        val diags = provider.diagnostics("b.py", "def broken(:\n    pass\n")
        assertTrue(diags.any { it.severity == "error" })

        val content = "def f():\n    count = 5\n    return cou\n"
        val items = provider.completionItems("c.py", content, 3, 12, "cou")
        assertTrue(items.contains("count"))

        val rename = provider.renameLocations("main.py", pyMain, 2, 8, "yell")
        assertTrue(rename != null && rename.any { it.filePath == "util.py" })
    }

    // ------------------------------------------------------------------
    // Java (shares the Kotlin provider)
    // ------------------------------------------------------------------

    @Test
    fun `java definition resolves in file`() {
        val index = ParserProjectIndex()
        val content = """
            public class Greeter {
                public String greet(String name) {
                    return "hi " + name;
                }
                public static void main(String[] args) {
                    Greeter g = new Greeter();
                    g.greet("bob");
                }
            }
        """.trimIndent()
        index.indexFile("Greeter.java", content)
        val provider = ParserBackedIntelligenceProvider.kotlinProvider(index)
        // `greet` call on line 7 (`        g.greet("bob");` — greet at chars 10-14).
        val defs = provider.definitions("Greeter.java", content, 7, 12)
        assertEquals(1, defs.size)
        assertEquals("greet", defs[0].symbolName)
        assertEquals(2, defs[0].line)
    }

    // ------------------------------------------------------------------
    // Labelling honesty
    // ------------------------------------------------------------------

    @Test
    fun `provider names never claim compiler accuracy`() {
        val providers = listOf(
            ParserBackedIntelligenceProvider.kotlinProvider(),
            ParserBackedIntelligenceProvider.typeScriptProvider(),
            ParserBackedIntelligenceProvider.pythonProvider(),
        )
        for (p in providers) {
            assertFalse(p.isFallback)
            val name = p.providerName.lowercase()
            assertFalse("must not claim compiler accuracy", name.contains("compiler"))
            assertFalse("must not claim LSP accuracy", name.contains("language server"))
        }
    }
}
