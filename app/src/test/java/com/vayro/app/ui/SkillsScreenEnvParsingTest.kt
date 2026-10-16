package com.vayro.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA H34/H35: the Add Custom MCP dialog parses env + secrets from
 * `KEY=VAL, ...` text. The parser is the logic that decides what lands in
 * plaintext env vs the encrypted credential store, so it is tested directly.
 */
class SkillsScreenEnvParsingTest {

    @Test
    fun `parses comma-separated key-value pairs`() {
        assertEquals(
            mapOf("A" to "1", "B" to "two"),
            parseKeyValueList("A=1, B=two")
        )
    }

    @Test
    fun `value may contain equals signs`() {
        assertEquals(
            mapOf("URL" to "https://example.com/?a=b"),
            parseKeyValueList("URL=https://example.com/?a=b")
        )
    }

    @Test
    fun `entries without equals are ignored`() {
        // NOEQUALS is dropped (no `=`), B= is kept with an empty value:
        // an empty env value is a real value.
        assertEquals(
            mapOf("A" to "1", "B" to ""),
            parseKeyValueList("A=1, NOEQUALS, B=")
        )
    }

    @Test
    fun `blank keys are ignored and later entries win`() {
        assertEquals(
            mapOf("A" to "second"),
            parseKeyValueList("=x, A=first, A=second")
        )
    }

    @Test
    fun `blank and empty input parse to empty`() {
        assertTrue(parseKeyValueList("").isEmpty())
        assertTrue(parseKeyValueList("   ").isEmpty())
    }
}
