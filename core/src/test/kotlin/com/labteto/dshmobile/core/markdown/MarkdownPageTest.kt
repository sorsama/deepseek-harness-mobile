package com.labteto.dshmobile.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPageTest {
    private val theme = MarkdownPageTheme(
        dark = true, background = "#0b0d12", text = "#f1f2f4", textSecondary = "#9a9ea8", link = "#6d9cff",
        border = "#23252c", codeBackground = "#15171c", quoteBar = "#2a2c33", pillBackground = "#1a1c22", pillText = "#9a9ea8",
    )

    private fun page(markdown: String, web: Boolean = false, options: MarkdownPageOptions = MarkdownPageOptions(showWebImages = web)) =
        MarkdownPages.render(markdown, theme, options)

    private val readme = """
        <p align="center">
          <img src="docs/images/banner.jpg" alt="DSH Mobile banner" width="100%">
        </p>

        <h1 align="center">DSH Mobile</h1>

        <p align="center">
          An Android app for a <a href="https://example.com/harness">harness</a>.
        </p>

        <p align="center">
          <a href="https://dshm.example.com"><img alt="Website" src="https://img.shields.io/badge/website-dshm-4176E6?style=flat-square"></a>
          <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square">
        </p>

        ## Screenshots

        | Connect | Chat |
        |:--:|--:|
        | one | two |

        - [x] done
        - [ ] todo

        ~~gone~~ and https://example.com/auto and `code`

        ```kotlin
        val a = "<b>not html</b>"
        ```
    """.trimIndent()

    @Test
    fun readmeKeepsItsHtmlLayoutInsteadOfShowingTags() {
        val html = page(readme).html
        assertTrue(html, html.contains("<h1 align=\"center\">DSH Mobile</h1>"))
        assertTrue(html, html.contains("<p align=\"center\">"))
        assertTrue(html, html.contains("<img src=\"docs/images/banner.jpg\" alt=\"DSH Mobile banner\" width=\"100%\">"))
        assertTrue(html, html.contains("<a href=\"https://example.com/harness\">harness</a>"))
        // The markdown after the html blocks is still markdown.
        assertTrue(html, html.contains("<h2 id=\"screenshots\">Screenshots</h2>"))
        assertTrue(html, html.contains("<table>"))
        assertTrue(html, html.contains("<del>gone</del>"))
        assertTrue(html, html.contains("<a href=\"https://example.com/auto\">https://example.com/auto</a>"))
        assertTrue(html, html.contains("type=\"checkbox\""))
        // Code stays code.
        assertTrue(html, html.contains("&lt;b&gt;not html&lt;/b&gt;"))
        assertFalse(html.contains("&lt;p align"))
    }

    @Test
    fun remoteImagesBecomeAltTextPillsAndAreReported() {
        val result = page(readme)
        assertTrue(result.html, result.html.contains("<span class=\"web-image\">Website</span>"))
        assertTrue(result.html, result.html.contains("<span class=\"web-image\">Android 8.0+</span>"))
        assertFalse(result.html.contains("img.shields.io"))
        assertEquals(2, result.remoteImages.size)
        assertEquals("https://img.shields.io/badge/website-dshm-4176E6?style=flat-square", result.remoteImages[0])
    }

    @Test
    fun aRemoteImageWithoutAltUsesTheFallbackLabel() {
        val result = page("<img src=\"https://example.com/a.png\">", options = MarkdownPageOptions(imageFallbackLabel = "Picture"))
        assertTrue(result.html, result.html.contains("<span class=\"web-image\">Picture</span>"))
    }

    @Test
    fun whenWebImagesAreAllowedTheyGoThroughThePreviewOriginOnly() {
        val result = page("![logo](https://example.com/logo.png)", web = true)
        val src = Regex("""<img src="([^"]+)"""").find(result.html)!!.groupValues[1]
        assertTrue(src, src.startsWith("https://preview.invalid/__web/"))
        val encoded = src.removePrefix("https://preview.invalid/__web/")
        assertEquals("https://example.com/logo.png", String(java.util.Base64.getUrlDecoder().decode(encoded)))
        assertFalse(result.html.contains("https://example.com/logo.png"))
    }

    @Test
    fun plainHttpImagesAreNeverLoaded() {
        val result = page("![x](http://example.com/a.png)", web = true)
        assertTrue(result.html, result.html.contains("<span class=\"web-image\">x</span>"))
        assertFalse(result.html.contains("<img"))
    }

    @Test
    fun relativeImagesAndSmallDataImagesStay() {
        val html = page("![a](docs/a.png)\n\n![b](data:image/png;base64,iVBORw0KGgo=)").html
        assertTrue(html, html.contains("<img src=\"docs/a.png\" alt=\"a\">"))
        assertTrue(html, html.contains("src=\"data:image/png;base64,iVBORw0KGgo=\""))
    }

    @Test
    fun scriptsHandlersFramesAndStylesAreRemoved() {
        val html = page(
            "<script>alert(1)</script>before\n\n<iframe src=\"https://evil.example\">inner</iframe>\n\n" +
                "<p onclick=\"x()\" style=\"position:fixed\" class=\"a\">text</p>\n\n<img src=\"a.png\" onerror=\"alert(1)\" alt=\"i\">\n\n" +
                "<style>body{display:none}</style>\n\n<object data=\"x\"></object>",
        ).html
        assertFalse(html, html.contains("<script"))
        assertFalse(html, html.contains("alert(1)</"))
        assertFalse(html, html.contains("iframe"))
        assertFalse(html, html.contains("onclick"))
        assertFalse(html, html.contains("onerror"))
        assertFalse(html, html.contains("position:fixed"))
        assertFalse(html, html.contains("display:none"))
        assertFalse(html, html.contains("<object"))
        assertTrue(html, html.contains("before"))
        assertTrue(html, html.contains("<p>text</p>"))
    }

    @Test
    fun dangerousLinkSchemesAreDroppedEvenWhenDisguised() {
        val html = page(
            "<a href=\"javascript:alert(1)\">a</a> <a href=\" JaVa\tScRiPt:alert(1)\">b</a> " +
                "<a href=\"java&#x73;cript:alert(1)\">c</a> <a href=\"data:text/html,x\">d</a> <a href=\"file:///etc/passwd\">e</a>",
        ).html
        assertFalse(html, html.lowercase().contains("javascript"))
        assertFalse(html, html.contains("data:text"))
        assertFalse(html, html.contains("file:"))
        assertTrue(html, html.contains(">a</a>") && html.contains(">e</a>"))
    }

    @Test
    fun safeLinksSurvive() {
        val html = page("[a](https://example.com/x) [b](docs/guide.md) [c](#install) [d](mailto:a@b.co)").html
        assertTrue(html, html.contains("href=\"https://example.com/x\""))
        assertTrue(html, html.contains("href=\"docs/guide.md\""))
        assertTrue(html, html.contains("href=\"#install\""))
        assertTrue(html, html.contains("href=\"mailto:a@b.co\""))
    }

    @Test
    fun commentsAreRemovedAndStrayAnglesEscaped() {
        val html = HtmlSanitizer.clean("a <!-- hidden --> b < c <br> d", allowWebImages = false).html
        assertEquals("a  b &lt; c <br> d", html)
    }

    @Test
    fun unknownTagsAreDroppedButTheirTextStays() {
        val html = HtmlSanitizer.clean("<blink>hi</blink><custom-thing a=\"1\">there</custom-thing>", false).html
        assertEquals("hithere", html)
    }

    @Test
    fun detailsAndSummaryArePreserved() {
        val html = page("<details open>\n<summary>More</summary>\n\nbody *text*\n\n</details>").html
        assertTrue(html, html.contains("<details open=\"\">") || html.contains("<details open>"))
        assertTrue(html, html.contains("<summary>More</summary>"))
        assertTrue(html, html.contains("<em>text</em>"))
    }

    @Test
    fun anUnclosedScriptDropsTheRest() {
        val html = HtmlSanitizer.clean("ok <script>alert(1) and more text", false).html
        assertEquals("ok ", html)
    }

    @Test
    fun frontmatterIsDrawnAsAClosedDetailsBlock() {
        val html = page(
            "# Title",
            options = MarkdownPageOptions(frontmatter = listOf("name" to "x <y>", "tags" to "a, b"), frontmatterLabel = "Front matter"),
        ).html
        assertTrue(html, html.contains("<details class=\"frontmatter\"><summary>Front matter · 2</summary>"))
        assertTrue(html, html.contains("<th>name</th><td>x &lt;y&gt;</td>"))
    }

    @Test
    fun theThemeColoursAndDarkSchemeReachThePage() {
        val html = page("hi").html
        assertTrue(html, html.contains("background:#0b0d12"))
        assertTrue(html, html.contains("color:#f1f2f4"))
        assertTrue(html, html.contains("<meta name=\"color-scheme\" content=\"dark\">"))
        assertTrue(html, html.contains("Content-Security-Policy"))
    }

    @Test
    fun tableAlignmentSurvivesTheStyleStrip() {
        val html = page("| a | b |\n|:--|--:|\n| 1 | 2 |").html
        assertTrue(html, html.contains("align=\"right\""))
    }
}
