package com.vayro.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevStackTest {

    @Test
    fun devStackEntriesIncludeBrowserAutomation() {
        val browser = DevStack.BROWSER
        assertNotNull(browser)
        assertEquals("Browser Automation (Chromium & Puppeteer)", browser.label)
        assertTrue(browser.label.contains("Chromium"))
        assertTrue(browser.description.contains("Automated browser"))
        assertTrue(browser.installsSummary.contains("pocket-browser"))
    }

    @Test
    fun devStackContainsAllExpectedToolchains() {
        val stacks = DevStack.entries.map { it.name }.toSet()
        assertTrue(stacks.contains("WEB"))
        assertTrue(stacks.contains("PYTHON"))
        assertTrue(stacks.contains("ANDROID"))
        assertTrue(stacks.contains("CPP"))
        assertTrue(stacks.contains("PHP"))
        assertTrue(stacks.contains("BROWSER"))
        assertEquals(6, DevStack.entries.size)
    }

    @Test
    fun projectModelSupportsRootPathAndSlug() {
        val project = Project(
            name = "Test Browser App",
            description = "Browser test project",
            language = "TypeScript",
            rootPath = "frontend",
        )
        assertEquals("Test Browser App", project.name)
        assertEquals("frontend", project.rootPath)
        assertEquals("test-browser-app", project.slug)

        val updated = project.copy(rootPath = "packages/web")
        assertEquals("packages/web", updated.rootPath)
    }

    @Test
    fun projectSlugHandlesSpecialCharactersAndSpaces() {
        val project = Project(
            name = "My Awesome App (v2.0)!",
            description = "Description",
            language = "Kotlin",
        )
        assertEquals("my-awesome-app-v2-0", project.slug)
    }
}
