package com.vayro.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {

    @Test
    fun testMarkdownLinkGeneratesLinkAnnotation() {
        var clickedUrl = ""
        val annotated = buildMarkdownAnnotatedString(
            text = "Check out [Release Page](https://github.com/sunmughan/Conzyx/releases/tag/v1.0.20) for details.",
            primaryColor = Color.Blue,
            codeColor = Color.Red,
            codeBg = Color.Gray,
            onOpenUri = { clickedUrl = it },
        )

        assertEquals("Check out Release Page for details.", annotated.text)
        val linkAnnotations = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, linkAnnotations.size)
        val link = linkAnnotations[0].item as LinkAnnotation.Url
        assertEquals("https://github.com/sunmughan/Conzyx/releases/tag/v1.0.20", link.url)

        // Test interaction listener callback
        link.linkInteractionListener?.onClick(link)
        assertEquals("https://github.com/sunmughan/Conzyx/releases/tag/v1.0.20", clickedUrl)
    }

    @Test
    fun testRawUrlGeneratesLinkAnnotation() {
        var clickedUrl = ""
        val annotated = buildMarkdownAnnotatedString(
            text = "Release Page:\nhttps://github.com/sunmughan/Conzyx/releases/tag/v1.0.20",
            primaryColor = Color.Blue,
            codeColor = Color.Red,
            codeBg = Color.Gray,
            onOpenUri = { clickedUrl = it },
        )

        val linkAnnotations = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, linkAnnotations.size)
        val link = linkAnnotations[0].item as LinkAnnotation.Url
        assertEquals("https://github.com/sunmughan/Conzyx/releases/tag/v1.0.20", link.url)

        link.linkInteractionListener?.onClick(link)
        assertEquals("https://github.com/sunmughan/Conzyx/releases/tag/v1.0.20", clickedUrl)
    }

    @Test
    fun testRawUrlWithTrailingPunctuation() {
        val annotated = buildMarkdownAnnotatedString(
            text = "Visit https://example.com/test. Also check https://example.com/api, thanks!",
            primaryColor = Color.Blue,
            codeColor = Color.Red,
            codeBg = Color.Gray,
        )

        val linkAnnotations = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(2, linkAnnotations.size)
        val link1 = linkAnnotations[0].item as LinkAnnotation.Url
        val link2 = linkAnnotations[1].item as LinkAnnotation.Url
        assertEquals("https://example.com/test", link1.url)
        assertEquals("https://example.com/api", link2.url)
        assertTrue(annotated.text.endsWith("thanks!"))
    }

    @Test
    fun testTableParsing() {
        val markdown = """
            | Asset | File Name | Size | Direct Download URL |
            | :--- | :--- | :--- | :--- |
            | Online Release APK | app-online-release.apk | ~91.3 MB | [Download Online APK](https://github.com/sunmughan/Conzyx/releases/download/v1.0.20/app-online-release.apk) |
            | Offline Release APK | app-offline-release.apk | ~888.4 MB | [Download Offline APK](https://github.com/sunmughan/Conzyx/releases/download/v1.0.20/app-offline-release.apk) |
        """.trimIndent()

        val blocks = parseMarkdown(markdown)
        assertEquals(1, blocks.size)
        val table = blocks[0] as MarkdownBlock.Table
        assertEquals(listOf("Asset", "File Name", "Size", "Direct Download URL"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals("Online Release APK", table.rows[0][0])
        assertEquals("app-online-release.apk", table.rows[0][1])
        assertEquals("~91.3 MB", table.rows[0][2])
        assertTrue(table.rows[0][3].contains("[Download Online APK]"))
    }

    @Test
    fun testNormalizeMarkdownUrl() {
        assertEquals("https://github.com", normalizeMarkdownUrl("https://github.com"))
        assertEquals("http://example.com", normalizeMarkdownUrl("http://example.com"))
        assertEquals("https://example.com", normalizeMarkdownUrl("example.com"))
        assertEquals("mailto:test@example.com", normalizeMarkdownUrl("mailto:test@example.com"))
    }
}
