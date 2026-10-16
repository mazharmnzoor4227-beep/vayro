package com.vayro.app.lsp.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 3 — validates the real Kotlin structural parser: declarations,
 * nesting/scopes, imports, supertypes and genuine diagnostics.
 */
class KotlinParserTest {

    private val sample = """
        package com.example.calculator

        import com.example.math.AddOperation
        import com.example.math.*

        /**
         * A simple calculator.
         */
        class Calculator(val brand: String) : AddOperation, AutoCloseable {
            private var total: Double = 0.0

            fun add(a: Double, b: Double): Double {
                val result = a + b
                total += result
                return result
            }

            fun reset() {
                total = 0.0
            }

            companion object {
                const val VERSION = "1.0"
                fun create(): Calculator = Calculator("default")
            }
        }

        fun formatTotal(value: Double): String = "Total: ${'$'}value"
    """.trimIndent()

    @Test
    fun `parses package and imports`() {
        val parsed = KotlinParser("Calculator.kt", sample).parse()
        assertEquals("com.example.calculator", parsed.packageName)
        assertEquals(2, parsed.imports.size)
        assertEquals("com.example.math.AddOperation", parsed.imports[0].rawText)
        assertEquals(listOf("AddOperation"), parsed.imports[0].importedNames)
        assertTrue(parsed.imports[1].isStar)
    }

    @Test
    fun `finds class with supertypes and doc comment`() {
        val parsed = KotlinParser("Calculator.kt", sample).parse()
        val cls = parsed.symbols.first { it.name == "Calculator" && it.kind == ParsedSymbolKind.CLASS }
        assertEquals(9, cls.line)
        assertEquals(listOf("AddOperation", "AutoCloseable"), cls.superTypes)
        assertTrue(cls.docComment?.contains("simple calculator") == true)
        // Primary constructor parameter recorded.
        assertTrue(cls.parameters.contains("brand"))
    }

    @Test
    fun `finds methods with containers and parameters`() {
        val parsed = KotlinParser("Calculator.kt", sample).parse()
        val add = parsed.symbols.first { it.name == "add" }
        assertEquals(ParsedSymbolKind.METHOD, add.kind)
        assertEquals("Calculator", add.containerName)
        assertEquals(listOf("a", "b"), add.parameters)
        assertTrue(add.signature?.contains("Double") == true)
    }

    @Test
    fun `finds nested companion object members`() {
        val parsed = KotlinParser("Calculator.kt", sample).parse()
        val create = parsed.symbols.first { it.name == "create" }
        assertEquals("companion", create.containerName)
        val version = parsed.symbols.first { it.name == "VERSION" }
        assertEquals("companion", version.containerName)
    }

    @Test
    fun `finds top-level function`() {
        val parsed = KotlinParser("Calculator.kt", sample).parse()
        val fmt = parsed.symbols.first { it.name == "formatTotal" }
        assertEquals(ParsedSymbolKind.FUNCTION, fmt.kind)
        assertEquals(null, fmt.containerName)
    }

    @Test
    fun `strings do not confuse the parser`() {
        // The string contains text that looks like code.
        val src = """
            fun trick(): String {
                val s = "class Fake { fun nope() }"
                return s
            }
        """.trimIndent()
        val parsed = KotlinParser("T.kt", src).parse()
        assertTrue(parsed.symbols.none { it.name == "Fake" || it.name == "nope" })
        assertTrue(parsed.symbols.any { it.name == "trick" })
        assertTrue(parsed.symbols.any { it.name == "s" })
    }

    @Test
    fun `comments do not confuse the parser`() {
        val src = """
            // fun commentedOut() {}
            /* class AlsoCommented */
            fun real() {}
        """.trimIndent()
        val parsed = KotlinParser("T.kt", src).parse()
        assertTrue(parsed.symbols.none { it.name == "commentedOut" || it.name == "AlsoCommented" })
        assertTrue(parsed.symbols.any { it.name == "real" })
    }

    @Test
    fun `reports unclosed brace as error diagnostic`() {
        val src = "class Broken {\n    fun foo() {\n"
        val parsed = KotlinParser("B.kt", src).parse()
        assertTrue(
            parsed.diagnostics.any { it.severity == "error" && it.message.contains("Unclosed") }
        )
    }

    @Test
    fun `reports unclosed paren as error diagnostic`() {
        val src = "fun broken(a: Int, b: Int {\n}\n"
        val parsed = KotlinParser("B.kt", src).parse()
        assertTrue(
            parsed.diagnostics.any { it.severity == "error" && it.message.contains("Unclosed") }
        )
    }

    @Test
    fun `reports duplicate definition in same scope`() {
        val src = "fun dup() {}\nfun dup() {}\n"
        val parsed = KotlinParser("D.kt", src).parse()
        assertTrue(
            parsed.diagnostics.any { it.severity == "warning" && it.message.contains("already defined") }
        )
    }

    @Test
    fun `collects call sites structurally`() {
        val src = """
            fun caller() {
                val x = compute(1, 2)
                println(x)
            }
        """.trimIndent()
        val parsed = KotlinParser("C.kt", src).parse()
        val calls = parsed.callSites.map { it.name }
        assertTrue(calls.contains("compute"))
        assertTrue(calls.contains("println"))
    }
}
