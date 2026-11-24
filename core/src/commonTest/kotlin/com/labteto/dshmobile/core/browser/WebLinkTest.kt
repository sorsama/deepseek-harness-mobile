package com.labteto.dshmobile.core.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebLinkTest {
    private fun urls(text: String) = findWebLinks(text).map { it.url }

    @Test
    fun devServerBanner() {
        val text = "  ➜  Local:   http://localhost:5173/\n  ➜  Network: http://192.168.1.5:5173/"
        assertEquals(listOf("http://localhost:5173/", "http://192.168.1.5:5173/"), urls(text))
    }

    @Test
    fun rangesCoverTheLink() {
        val text = "Open http://localhost:3000 now"
        val link = findWebLinks(text).single()
        assertEquals("http://localhost:3000", text.substring(link.start, link.end))
    }

    @Test
    fun trailingPunctuationIsNotPartOfTheLink() {
        assertEquals(listOf("http://localhost:3000"), urls("The server runs at http://localhost:3000."))
        assertEquals(listOf("http://localhost:3000/a"), urls("(see http://localhost:3000/a)"))
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), urls("https://en.wikipedia.org/wiki/Foo_(bar), done"))
        assertEquals(listOf("http://localhost:8080"), urls("'http://localhost:8080'"))
        assertEquals(listOf("http://localhost:8080/x?y=1"), urls("Try http://localhost:8080/x?y=1!"))
    }

    @Test
    fun uppercaseSchemeIsFound() {
        assertEquals(listOf("HTTP://LOCALHOST:5000"), urls("go to HTTP://LOCALHOST:5000"))
    }

    @Test
    fun linksEndAtQuotesAndAngleBrackets() {
        assertEquals(listOf("http://localhost:1/"), urls("<http://localhost:1/>"))
        assertEquals(listOf("http://localhost:2"), urls("href=\"http://localhost:2\""))
        assertEquals(listOf("http://localhost:3"), urls("`http://localhost:3`"))
    }

    @Test
    fun notALink() {
        assertTrue(urls("http:// and https://").isEmpty())
        assertTrue(urls("javascript:alert(1) file:///etc/passwd").isEmpty())
        assertTrue(urls("xhttp://localhost:1").isEmpty())
        assertTrue(urls("http://user@localhost:1").isEmpty())
        assertTrue(urls("no links here").isEmpty())
    }

    @Test
    fun onlyLocalLinksAreKeptByTheLocalFinder() {
        val text = "Docs at https://vitejs.dev, app at http://localhost:5173/ and http://devbox.local:8000"
        assertEquals(listOf("http://localhost:5173/", "http://devbox.local:8000"), findLocalWebLinks(text).map { it.url })
    }

    @Test
    fun severalLinksOnOneLine() {
        assertEquals(
            listOf("http://127.0.0.1:8000", "http://[::1]:8000"),
            urls("http://127.0.0.1:8000 or http://[::1]:8000"),
        )
    }
}
