package com.vayro.app.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class CodingToolbarTest {

    @Test
    fun `applyEditorSymbol wraps selection with bracket pairs`() {
        val initial = TextFieldValue("hello world", TextRange(0, 5))
        val result = applyEditorSymbol(initial, "(")
        assertEquals("(hello) world", result.text)
        assertEquals(TextRange(1, 6), result.selection)

        val curly = applyEditorSymbol(initial, "{")
        assertEquals("{hello} world", curly.text)
    }

    @Test
    fun `applyEditorSymbol inserts pair with cursor inside when no selection`() {
        val initial = TextFieldValue("func", TextRange(4))
        val result = applyEditorSymbol(initial, "(")
        assertEquals("func()", result.text)
        assertEquals(TextRange(5), result.selection)
    }

    @Test
    fun `applyEditorSymbol TAB indents with 4 spaces`() {
        val initial = TextFieldValue("line", TextRange(0))
        val result = applyEditorSymbol(initial, "TAB")
        assertEquals("    line", result.text)
    }

    @Test
    fun `applyEditorSymbol UNTAB removes up to 4 spaces indentation`() {
        val initial = TextFieldValue("    indented", TextRange(4))
        val result = applyEditorSymbol(initial, "UNTAB")
        assertEquals("indented", result.text)
    }

    @Test
    fun `applyEditorSymbol comment toggle prepends and removes double slash`() {
        val initial = TextFieldValue("val x = 10", TextRange(5))
        val commented = applyEditorSymbol(initial, "//")
        assertEquals("// val x = 10", commented.text)

        val uncommented = applyEditorSymbol(commented, "//")
        assertEquals("val x = 10", uncommented.text)
    }

    @Test
    fun `applyEditorSymbol arrow keys move cursor correctly`() {
        val initial = TextFieldValue("abc", TextRange(1))
        val left = applyEditorSymbol(initial, "LEFT")
        assertEquals(TextRange(0), left.selection)

        val right = applyEditorSymbol(initial, "RIGHT")
        assertEquals(TextRange(2), right.selection)
    }
}
