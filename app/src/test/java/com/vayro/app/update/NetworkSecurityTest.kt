package com.vayro.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class NetworkSecurityTest {

    @Test
    fun gitHubDomainAllowsGitHubHosts() {
        assertTrue(AppUpdater.isGitHubDomain("https://github.com/sunmughan/Conzyx"))
        assertTrue(AppUpdater.isGitHubDomain("https://api.github.com/repos/x"))
        assertTrue(AppUpdater.isGitHubDomain("https://GITHUB.COM/x"))
    }

    @Test
    fun gitHubDomainRejectsLookalikes() {
        assertFalse(AppUpdater.isGitHubDomain("https://attackergithub.com/x"))
        assertFalse(AppUpdater.isGitHubDomain("https://github.com.evil.com/x"))
        assertFalse(AppUpdater.isGitHubDomain("https://evil-github.com/x"))
        assertFalse(AppUpdater.isGitHubDomain("https://notgithub.com/"))
    }

    @Test
    fun gitHubDomainRequiresHttps() {
        assertFalse(AppUpdater.isGitHubDomain("http://github.com/x"))
        assertFalse(AppUpdater.isGitHubDomain("not a url"))
        assertFalse(AppUpdater.isGitHubDomain(""))
    }

    @Test
    fun redirectResolutionKeepsHttpsTargets() {
        assertEquals(
            "https://objects.githubusercontent.com/abc",
            AppUpdater.resolveRedirectUrl("https://github.com/a", "https://objects.githubusercontent.com/abc"),
        )
    }

    @Test
    fun redirectResolutionResolvesRelativeLocations() {
        assertEquals(
            "https://github.com/c/d",
            AppUpdater.resolveRedirectUrl("https://github.com/a/b", "/c/d"),
        )
        assertEquals(
            "https://github.com/a/c",
            AppUpdater.resolveRedirectUrl("https://github.com/a/b", "c"),
        )
    }

    @Test
    fun redirectResolutionRejectsUnsafeTargets() {
        assertNull(AppUpdater.resolveRedirectUrl("https://github.com/a", "http://evil.com/b"))
        assertNull(AppUpdater.resolveRedirectUrl("https://github.com/a", "ftp://evil.com/b"))
        assertNull(AppUpdater.resolveRedirectUrl("https://github.com/a", "javascript:alert(1)"))
        assertNull(AppUpdater.resolveRedirectUrl("https://github.com/a", ""))
        assertNull(AppUpdater.resolveRedirectUrl("https://github.com/a", null))
        assertNull(AppUpdater.resolveRedirectUrl("not a url", "https://github.com/b"))
    }

    @Test
    fun networkSecurityConfigDisablesCleartextByDefault() {
        val xml = File("src/main/res/xml/network_security_config.xml")
        assertTrue("network_security_config.xml must exist at ${xml.absolutePath}", xml.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val baseConfigs = doc.getElementsByTagName("base-config")
        assertTrue(baseConfigs.length > 0)
        val permitted = baseConfigs.item(0).attributes.getNamedItem("cleartextTrafficPermitted")?.nodeValue
        assertEquals("false", permitted)
    }
}
