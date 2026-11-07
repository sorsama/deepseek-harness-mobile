package com.labteto.dshmobile.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineHtmlTest {
    private fun convert(text: String) = inlineHtmlToMarkdown(text)

    @Test
    fun textWithoutTagsIsReturnedAsIs() {
        val text = "Plain **text** with a < b and `List<String>`"
        assertEquals(text, convert(text))
    }

    @Test
    fun lineBreaksAndEmphasisBecomeMarkdown() {
        assertEquals("one\ntwo", convert("one<br>two"))
        assertEquals("one\ntwo", convert("one<br/>two"))
        assertEquals("one\ntwo", convert("one<BR />two"))
        assertEquals("a **bold** and *slanted* and ~~gone~~", convert("a <b>bold</b> and <i>slanted</i> and <del>gone</del>"))
        assertEquals("**strong** *emph*", convert("<strong>strong</strong> <em>emph</em>"))
    }

    @Test
    fun codeAndKeysBecomeCodeSpans() {
        assertEquals("press `Ctrl` + `C` or `ls -la`", convert("press <kbd>Ctrl</kbd> + <kbd>C</kbd> or <code>ls -la</code>"))
    }

    @Test
    fun linksKeepTheirTextAndTarget() {
        assertEquals("see [the docs](https://example.com/a) now", convert("see <a href=\"https://example.com/a\">the docs</a> now"))
        assertEquals("[guide](docs/guide.md)", convert("<a href='docs/guide.md'>guide</a>"))
        assertEquals("[x](https://en.wikipedia.org/wiki/Foo_(bar%29)", convert("<a href=\"https://en.wikipedia.org/wiki/Foo_(bar)\">x</a>"))
    }

    @Test
    fun aLinkWithAnUnsafeSchemeKeepsOnlyItsText() {
        assertEquals("click", convert("<a href=\"javascript:alert(1)\">click</a>"))
    }

    @Test
    fun imagesBecomeTheirAltText() {
        assertEquals("[badge](https://img.example.com/b.svg)", convert("<img alt=\"badge\" src=\"https://img.example.com/b.svg\">"))
        assertEquals("local picture", convert("<img src=\"a.png\" alt=\"local picture\">"))
        assertEquals("", convert("<img src=\"a.png\">"))
    }

    @Test
    fun subSupSpanAndAlignedParagraphsKeepTheirText() {
        assertEquals("H2O and x2", convert("H<sub>2</sub>O and x<sup>2</sup>"))
        assertEquals("a b", convert("<span style=\"color:red\">a</span> b"))
        val paragraph = convert("<p align=\"center\">Centred text</p>")
        assertTrue(paragraph, paragraph.trim() == "Centred text")
        val div = convert("<div align=\"center\">Boxed</div>")
        assertTrue(div, div.trim() == "Boxed")
    }

    @Test
    fun detailsBecomeABoldTitleAndTheBody() {
        val text = convert("<details>\n<summary>More info</summary>\nThe body.\n</details>")
        assertTrue(text, text.contains("**More info**"))
        assertTrue(text, text.contains("The body."))
        assertTrue(text, !text.contains("<"))
    }

    @Test
    fun commentsDisappearEvenAcrossLines() {
        assertEquals("a  b", convert("a <!-- hidden --> b"))
        val spanning = convert("before\n<!-- start\nstill hidden\nend -->\nafter")
        assertTrue(spanning, spanning.startsWith("before") && spanning.endsWith("after"))
        assertTrue(spanning, !spanning.contains("hidden") && !spanning.contains("<!--") && !spanning.contains("-->"))
    }

    @Test
    fun headingsAndListsAreReadable() {
        val heading = convert("<h2>Title</h2>")
        assertEquals("**Title**", heading.trim())
        val list = convert("<ul><li>one</li><li>two</li></ul>")
        assertTrue(list, list.contains("\n- one") && list.contains("\n- two"))
    }

    @Test
    fun fencedCodeAndCodeSpansAreNeverTouched() {
        val text = "```html\n<b>bold</b><br>\n```\nafter <b>x</b>"
        assertEquals("```html\n<b>bold</b><br>\n```\nafter **x**", convert(text))
        assertEquals("keep `<b>this</b>` and **that**", convert("keep `<b>this</b>` and <b>that</b>"))
    }

    @Test
    fun anUnfinishedTagIsLeftAsTyped() {
        assertEquals("a <b", convert("a <b"))
        assertEquals("a <a href=\"https://exa", convert("a <a href=\"https://exa"))
    }

    @Test
    fun unknownTagsAndGenericsStayVisible() {
        assertEquals("a <custom-tag>b</custom-tag>", convert("a <custom-tag>b</custom-tag>"))
        assertEquals("List<String> and Map<K, V>", convert("List<String> and Map<K, V>"))
        assertEquals("<https://example.com>", convert("<https://example.com>"))
    }
}
