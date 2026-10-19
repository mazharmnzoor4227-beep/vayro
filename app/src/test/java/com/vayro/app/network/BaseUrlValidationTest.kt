package com.vayro.app.network

import com.vayro.app.model.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.41 (Scope 2): a Base URL is one clean web address. Anything
 * else — above all a pasted documentation curl example — is rejected
 * at the field with one plain message, never saved, never sent to
 * the network layer (where it used to surface as a "no protocol"
 * exception dump).
 */
class BaseUrlValidationTest {

    @Test
    fun `every provider default base url passes`() {
        ProviderKind.entries.forEach { kind ->
            if (kind.defaultBaseUrl.isBlank()) return@forEach
            assertNull(
                "default for ${kind.name} must be a clean address",
                BaseUrlValidation.errorOrNull(kind.defaultBaseUrl),
            )
        }
    }

    @Test
    fun `blank means use the default, not garbage`() {
        assertNull(BaseUrlValidation.errorOrNull(""))
        assertNull(BaseUrlValidation.errorOrNull("   "))
        assertTrue(BaseUrlValidation.isValid(""))
    }

    @Test
    fun `a pasted curl example is rejected`() {
        val pasted = "curl https://generativelanguage.googleapis.com/v1beta/openai/chat/completions " +
            "-H 'Content-Type: application/json' -d '{\"contents\": [{\"parts\": [{\"text\": \"hi\"}]}]}'"
        assertEquals(BaseUrlValidation.INVALID_MESSAGE, BaseUrlValidation.errorOrNull(pasted))
    }

    @Test
    fun `spaces quotes and braces are rejected`() {
        assertNotNull(BaseUrlValidation.errorOrNull("https://api.example.com/v1 beta"))
        assertNotNull(BaseUrlValidation.errorOrNull("https://api.example.com/v1\""))
        assertNotNull(BaseUrlValidation.errorOrNull("https://api.example.com/{model}"))
        assertNotNull(BaseUrlValidation.errorOrNull("'https://api.example.com/v1'"))
    }

    @Test
    fun `missing or non-http scheme is rejected`() {
        assertNotNull(BaseUrlValidation.errorOrNull("api.example.com/v1"))
        assertNotNull(BaseUrlValidation.errorOrNull("ftp://api.example.com/v1"))
        assertNotNull(BaseUrlValidation.errorOrNull("https://"))
    }

    @Test
    fun `ordinary custom addresses pass`() {
        assertNull(BaseUrlValidation.errorOrNull("https://generativelanguage.googleapis.com/v1beta/openai"))
        assertNull(BaseUrlValidation.errorOrNull("http://localhost:11434/v1"))
        assertNull(BaseUrlValidation.errorOrNull(" https://openrouter.ai/api "))
    }
}
