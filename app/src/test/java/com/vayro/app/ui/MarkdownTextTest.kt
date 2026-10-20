package com.vayro.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

    @Test
    fun contentBodyStyleIsTheSerifReadingStandard() {
        // v3.2.43 (Parts 2+3a): the one reading style for message
        // content — Source Serif 4, 17sp, line height in the Claude
        // map's 1.55-1.6 band.
        val style = contentBodyStyle()
        assertEquals(com.vayro.app.ui.theme.SourceSerifFontFamily, style.fontFamily)
        assertEquals(FontWeight.Normal, style.fontWeight)
        assertEquals(17, style.fontSize.value.toInt())
        val ratio = style.lineHeight.value / style.fontSize.value
        assertTrue("line-height ratio $ratio must sit in 1.55..1.6", ratio in 1.55f..1.6f)
    }

    @Test
    fun headingSpecsFormASerifBoldSizeLadder() {
        // v3.2.43 (P3a): headings must read as a hierarchy — strictly
        // decreasing sizes H1 > H2 > H3 > H4, H4 bottoming out at the
        // 17sp body size, every level serif + bold.
        val sizes = (1..4).map { markdownHeadingSpec(it).fontSizeSp }
        assertTrue("heading sizes must strictly decrease: $sizes", sizes.zipWithNext().all { (a, b) -> a > b })
        assertEquals(17f, markdownHeadingSpec(4).fontSizeSp, 0.001f)
        assertEquals(markdownHeadingSpec(4), markdownHeadingSpec(6))
        for (level in 1..6) {
            val style = markdownHeadingStyle(level)
            assertEquals(FontWeight.Bold, style.fontWeight)
            assertEquals(com.vayro.app.ui.theme.SourceSerifFontFamily, style.fontFamily)
        }
    }

    @Test
    fun fixtureAnswerParsesIntoStructuredBlocks() {
        // v3.2.43 (P3a): the fixture answer — Part headings, a bold
        // lead, sub-headings, bullets, numbered steps, a quote, a
        // divider and a code block — must parse into the exact block
        // sequence the renderer turns into the visual hierarchy.
        val fixture = """
            # Part 1 — Overview
            Some **bold lead** text here.
            ## Details
            - First point
            - Second point
            ### Deep dive
            1. Step one
            2. Step two
            > A quoted line
            ---
            ```kotlin
            val x = 1
            ```
            Closing paragraph.
        """.trimIndent()
        val blocks = parseMarkdown(fixture)
        val kinds = blocks.map { block ->
            when (block) {
                is MarkdownBlock.Header -> "H${block.level}"
                is MarkdownBlock.Paragraph -> "P"
                is MarkdownBlock.BulletItem -> "B"
                is MarkdownBlock.NumberedItem -> "N"
                is MarkdownBlock.BlockQuote -> "Q"
                is MarkdownBlock.HorizontalRule -> "HR"
                is MarkdownBlock.CodeBlock -> "CODE"
                is MarkdownBlock.Table -> "T"
            }
        }
        assertEquals(
            listOf("H1", "P", "H2", "B", "B", "H3", "N", "N", "Q", "HR", "CODE", "P"),
            kinds,
        )
        val code = blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single()
        assertEquals("kotlin", code.language)
        assertTrue(code.code.contains("val x = 1"))
    }
}
