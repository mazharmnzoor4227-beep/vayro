package com.vayro.app.lsp.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 3 — validates the real Java structural parser.
 */
class JavaParserTest {

    private val sample = """
        package com.example.store;

        import java.util.List;
        import java.util.ArrayList;
        import static java.lang.Math.max;

        /**
         * A simple store.
         */
        public class Store extends BaseStore implements Storable {
            private List<String> items = new ArrayList<>();

            public Store(String name) {
                super(name);
            }

            public void addItem(String item) {
                items.add(item);
            }

            public int itemCount() {
                return items.size();
            }

            private static String format(String s) {
                return s.trim();
            }
        }
    """.trimIndent()

    @Test
    fun `parses package and imports including static`() {
        val parsed = JavaParser("Store.java", sample).parse()
        assertEquals("com.example.store", parsed.packageName)
        assertEquals(3, parsed.imports.size)
        assertTrue(parsed.imports.any { it.rawText == "java.util.List" })
        assertTrue(parsed.imports.any { it.rawText.startsWith("static") })
    }

    @Test
    fun `finds class with extends and implements`() {
        val parsed = JavaParser("Store.java", sample).parse()
        val cls = parsed.symbols.first { it.name == "Store" && it.kind == ParsedSymbolKind.CLASS }
        assertEquals(listOf("BaseStore", "Storable"), cls.superTypes)
        assertTrue(cls.docComment?.contains("simple store") == true)
    }

    @Test
    fun `distinguishes constructor from methods`() {
        val parsed = JavaParser("Store.java", sample).parse()
        val ctor = parsed.symbols.first { it.kind == ParsedSymbolKind.CONSTRUCTOR }
        assertEquals("Store", ctor.name)
        assertEquals(listOf("name"), ctor.parameters)
        val addItem = parsed.symbols.first { it.name == "addItem" }
        assertEquals(ParsedSymbolKind.METHOD, addItem.kind)
        assertEquals("Store", addItem.containerName)
        assertEquals(listOf("item"), addItem.parameters)
    }

    @Test
    fun `finds fields`() {
        val parsed = JavaParser("Store.java", sample).parse()
        val items = parsed.symbols.first { it.name == "items" }
        assertEquals(ParsedSymbolKind.VARIABLE, items.kind)
        assertEquals("Store", items.containerName)
    }

    @Test
    fun `finds interface and enum declarations`() {
        val src = """
            public interface Storable {
                void persist();
            }
            enum Status { ACTIVE, INACTIVE }
        """.trimIndent()
        val parsed = JavaParser("T.java", src).parse()
        assertTrue(parsed.symbols.any { it.name == "Storable" && it.kind == ParsedSymbolKind.INTERFACE })
        assertTrue(parsed.symbols.any { it.name == "Status" && it.kind == ParsedSymbolKind.ENUM })
        val persist = parsed.symbols.first { it.name == "persist" }
        assertEquals(ParsedSymbolKind.METHOD, persist.kind)
    }

    @Test
    fun `strings and comments do not confuse the parser`() {
        val src = """
            public class Real {
                // public void fake() {}
                String s = "class Fake {}";
                public void actual() {}
            }
        """.trimIndent()
        val parsed = JavaParser("R.java", src).parse()
        assertTrue(parsed.symbols.none { it.name == "Fake" || it.name == "fake" })
        assertTrue(parsed.symbols.any { it.name == "Real" })
        assertTrue(parsed.symbols.any { it.name == "actual" })
    }

    @Test
    fun `reports unclosed brace`() {
        val src = "public class Broken {\n    public void foo() {\n"
        val parsed = JavaParser("B.java", src).parse()
        assertTrue(parsed.diagnostics.any { it.severity == "error" })
    }

    @Test
    fun `collects call sites`() {
        val parsed = JavaParser("Store.java", sample).parse()
        val calls = parsed.callSites.map { it.name }
        assertTrue(calls.contains("add"))
        assertTrue(calls.contains("size"))
        assertTrue(calls.contains("trim"))
    }
}
