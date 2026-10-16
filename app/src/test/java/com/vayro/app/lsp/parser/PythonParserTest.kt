package com.vayro.app.lsp.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 3 — validates the real Python structural parser
 * (indentation-based scoping).
 */
class PythonParserTest {

    private val sample = """
        # Module docstring.

        import os
        import sys as system
        from typing import List, Optional
        from .helpers import format_name as fmt

        CONSTANT = 42

        class Calculator(BaseCalculator):
            "A simple calculator."

            def __init__(self, brand: str):
                self.brand = brand
                self.total = 0.0

            def add(self, a: float, b: float) -> float:
                result = a + b
                self.total += result
                return result

            @property
            def total_rounded(self) -> int:
                return round(self.total)


        def format_total(value: float) -> str:
            return f"Total: {value}"


        async def fetch_rate() -> float:
            return 1.0
    """.trimIndent()

    @Test
    fun `parses imports with as aliases and from forms`() {
        val parsed = PythonParser("calc.py", sample).parse()
        assertEquals(4, parsed.imports.size)
        // `import sys as system` binds `system`.
        assertTrue(parsed.imports.any { it.importedNames.contains("system") })
        assertTrue(parsed.imports.any { it.importedNames.contains("List") })
        // `from .helpers import format_name as fmt` binds `fmt`.
        assertTrue(parsed.imports.any { it.importedNames.contains("fmt") })
    }

    @Test
    fun `finds class with bases and docstring`() {
        val parsed = PythonParser("calc.py", sample).parse()
        val cls = parsed.symbols.first { it.name == "Calculator" }
        assertEquals(ParsedSymbolKind.CLASS, cls.kind)
        assertEquals(listOf("BaseCalculator"), cls.superTypes)
        assertTrue(cls.docComment?.contains("simple calculator") == true)
    }

    @Test
    fun `finds methods with self excluded from parameters`() {
        val parsed = PythonParser("calc.py", sample).parse()
        val add = parsed.symbols.first { it.name == "add" }
        assertEquals(ParsedSymbolKind.METHOD, add.kind)
        assertEquals("Calculator", add.containerName)
        assertEquals(listOf("a", "b"), add.parameters)
    }

    @Test
    fun `finds module-level functions including async`() {
        val parsed = PythonParser("calc.py", sample).parse()
        val fmt = parsed.symbols.first { it.name == "format_total" }
        assertEquals(ParsedSymbolKind.FUNCTION, fmt.kind)
        assertEquals(null, fmt.containerName)
        assertTrue(parsed.symbols.any { it.name == "fetch_rate" })
    }

    @Test
    fun `finds module-level assignment`() {
        val parsed = PythonParser("calc.py", sample).parse()
        assertTrue(parsed.symbols.any { it.name == "CONSTANT" && it.kind == ParsedSymbolKind.VARIABLE })
    }

    @Test
    fun `indentation defines scope, not braces`() {
        val src = """
            def outer():
                x = 1
                def inner():
                    y = 2
                z = 3
        """.trimIndent()
        val parsed = PythonParser("n.py", src).parse()
        val inner = parsed.symbols.first { it.name == "inner" }
        assertEquals("outer", inner.containerName)
        val y = parsed.symbols.first { it.name == "y" }
        assertEquals("inner", y.containerName)
        val z = parsed.symbols.first { it.name == "z" }
        assertEquals("outer", z.containerName)
    }

    @Test
    fun `strings and comments do not confuse the parser`() {
        val src = """
            # def fake(): pass
            def real():
                s = "class Fake: pass"
                return s
        """.trimIndent()
        val parsed = PythonParser("r.py", src).parse()
        assertTrue(parsed.symbols.none { it.name == "Fake" || it.name == "fake" })
        assertTrue(parsed.symbols.any { it.name == "real" })
    }

    @Test
    fun `reports unbalanced paren`() {
        val src = "def broken(a, b:\n    pass\n"
        val parsed = PythonParser("b.py", src).parse()
        assertTrue(parsed.diagnostics.any { it.severity == "error" })
    }

    @Test
    fun `decorated function is found`() {
        val parsed = PythonParser("calc.py", sample).parse()
        assertTrue(parsed.symbols.any { it.name == "total_rounded" && it.kind == ParsedSymbolKind.METHOD })
    }
}
