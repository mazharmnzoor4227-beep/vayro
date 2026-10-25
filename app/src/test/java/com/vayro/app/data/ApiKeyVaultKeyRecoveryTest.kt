package com.vayro.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.UnrecoverableKeyException

/**
 * Covers the keystore-key recovery decision used by [ApiKeyVault] when a
 * saved API key cannot be written: an unusable key must be replaced so the
 * next save succeeds instead of force-stopping setup on every Continue tap
 * (GitHub issues #6, #7). Ordinary failures must NOT destroy the key.
 */
class ApiKeyVaultKeyRecoveryTest {

    @Test
    fun unrecoverableKeyIsRecreated() {
        assertTrue(shouldRecreateKeystoreKey(UnrecoverableKeyException()))
        assertTrue(shouldRecreateKeystoreKey(UnrecoverableKeyException("key invalidated")))
    }

    @Test
    fun ordinaryFailuresAreNotRecreated() {
        assertFalse(shouldRecreateKeystoreKey(IllegalStateException("boom")))
        assertFalse(shouldRecreateKeystoreKey(java.security.KeyStoreException("keystore unavailable")))
        assertFalse(shouldRecreateKeystoreKey(RuntimeException("cipher init failed")))
    }
}
