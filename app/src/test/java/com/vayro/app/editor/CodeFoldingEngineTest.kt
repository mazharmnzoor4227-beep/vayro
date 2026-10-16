package com.vayro.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeFoldingEngineTest {

    @Test
    fun `detects brace blocks in Kotlin code`() {
        val code = """
            class MyClass {
                fun first() {
                    println("hello")
                }
                fun second() {
                    val a = 1
                    val b = 2
                }
            }
        """.trimIndent()

        val folds = CodeFoldingEngine.findFoldableRanges(code, "Kotlin")
        assertTrue(folds.isNotEmpty())
        assertTrue(folds.any { it.type == FoldType.BRACES })

        // Outer class starts line 1 and ends line 9
        val classFold = folds.firstOrNull { it.startLine == 1 && it.endLine == 9 }
        assertTrue("Outer class fold should be detected", classFold != null)

        // Function first starts line 2 and ends line 4
        val firstFuncFold = folds.firstOrNull { it.startLine == 2 && it.endLine == 4 }
        assertTrue("first() function fold should be detected", firstFuncFold != null)
    }

    @Test
    fun `detects indentation blocks in Python code`() {
        val pythonCode = """
            def greet(name):
                if name:
                    return f"Hello {name}"
                else:
                    return "Hello stranger"

            def farewell():
                return "Goodbye"
        """.trimIndent()

        val folds = CodeFoldingEngine.findFoldableRanges(pythonCode, "Python")
        assertTrue(folds.isNotEmpty())
        assertTrue(folds.all { it.type == FoldType.INDENTATION })

        // Function greet starts line 1
        val greetFold = folds.firstOrNull { it.startLine == 1 }
        assertTrue("greet() block should be foldable", greetFold != null)
        assertTrue(greetFold!!.endLine >= 5)
    }

    @Test
    fun `detects import blocks with three or more imports`() {
        val code = """
            package com.example

            import java.io.File
            import java.net.URL
            import java.util.UUID
            import kotlinx.coroutines.flow.Flow

            fun main() {
                println("app")
            }
        """.trimIndent()

        val folds = CodeFoldingEngine.findFoldableRanges(code, "Kotlin")
        val importFold = folds.firstOrNull { it.type == FoldType.IMPORT_BLOCK }
        assertTrue("Import block fold should be detected", importFold != null)
        assertEquals(3, importFold!!.startLine)
        assertEquals(6, importFold.endLine)
    }

    @Test
    fun `detects multiline comment blocks`() {
        val code = """
            /*
             * Multi-line license header
             * Copyright 2026
             */
            class Foo {
            }
        """.trimIndent()

        val folds = CodeFoldingEngine.findFoldableRanges(code, "Kotlin")
        val commentFold = folds.firstOrNull { it.type == FoldType.COMMENT_BLOCK }
        assertTrue("Comment block fold should be detected", commentFold != null)
        assertEquals(1, commentFold!!.startLine)
        assertEquals(4, commentFold.endLine)
    }

    @Test
    fun `isLineHiddenByFold correctly reports collapsed line visibility`() {
        val range = FoldableRange(startLine = 10, endLine = 20, type = FoldType.BRACES)
        val activeFolds = setOf(range)

        assertFalse("Start line (header) should not be hidden", CodeFoldingEngine.isLineHiddenByFold(10, activeFolds))
        assertTrue("Inner line 11 should be hidden", CodeFoldingEngine.isLineHiddenByFold(11, activeFolds))
        assertTrue("End line 20 should be hidden", CodeFoldingEngine.isLineHiddenByFold(20, activeFolds))
        assertFalse("Line outside fold 21 should not be hidden", CodeFoldingEngine.isLineHiddenByFold(21, activeFolds))
        assertFalse("Line before fold 9 should not be hidden", CodeFoldingEngine.isLineHiddenByFold(9, activeFolds))
    }
}
