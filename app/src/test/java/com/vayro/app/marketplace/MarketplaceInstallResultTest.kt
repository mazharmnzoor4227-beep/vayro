package com.vayro.app.marketplace

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * QA agent-6 finding 19: installing a listing that fetched nothing (a local
 * `npx …` reference or a catalog-only entry) must never be reported as Installed.
 */
class MarketplaceInstallResultTest {

    @Test
    fun `non downloaded sources are reported as reference recorded, never installed`() {
        val dir = File("/tmp/pkg")
        val localRef = installResultFor(false, dir)
        assertTrue(localRef is MarketplaceInstallResult.ReferenceRecorded)
        assertTrue(localRef !is MarketplaceInstallResult.Installed)
    }

    @Test
    fun `downloaded sources are reported as installed`() {
        val result = installResultFor(true, File("/tmp/pkg"))
        assertTrue(result is MarketplaceInstallResult.Installed)
    }
}
