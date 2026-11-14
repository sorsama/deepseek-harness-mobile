package com.labteto.dshmobile.ui.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipContentTest {

    @Test fun contentAddressesAreFiles() {
        val split = splitClip(listOf(ClipPart("content://files/a.png", null), ClipPart("content://files/b.pdf", null)))
        assertEquals(listOf("content://files/a.png", "content://files/b.pdf"), split.files)
        assertTrue(split.text.isEmpty())
    }

    @Test fun aFilesDisplayNameIsNotTypedIntoTheDraft() {
        // The Files app puts the file name beside its address; the name is not something to type.
        val split = splitClip(listOf(ClipPart("content://files/report.pdf", "report.pdf")))
        assertEquals(listOf("content://files/report.pdf"), split.files)
        assertEquals("", split.text)
    }

    @Test fun plainTextIsText() {
        val split = splitClip(listOf(ClipPart(null, "hello"), ClipPart(null, "world")))
        assertTrue(split.files.isEmpty())
        assertEquals("hello\nworld", split.text)
    }

    @Test fun aLinkWithNoTextIsTypedAsItsAddress() {
        val split = splitClip(listOf(ClipPart("https://example.com/page", null)))
        assertTrue(split.files.isEmpty())
        assertEquals("https://example.com/page", split.text)
    }

    @Test fun aLinkWithTextKeepsTheText() {
        assertEquals("a page", splitClip(listOf(ClipPart("https://example.com", "a page"))).text)
    }

    @Test fun fileAddressesAreRefused() {
        // The platform will not share them, and no grant exists for one: they cannot be read.
        val split = splitClip(listOf(ClipPart("file:///sdcard/a.png", null)))
        assertTrue(split.files.isEmpty())
    }

    @Test fun repeatedFilesAreCountedOnce() {
        val split = splitClip(listOf(ClipPart("content://x/1", null), ClipPart("content://x/1", null)))
        assertEquals(1, split.files.size)
    }

    @Test fun filesAndTextTogetherAreBothKept() {
        val split = splitClip(listOf(ClipPart("content://x/1", "1"), ClipPart(null, "note")))
        assertEquals(listOf("content://x/1"), split.files)
        assertEquals("note", split.text)
    }

    @Test fun anEmptyClipIsEmpty() {
        val split = splitClip(emptyList())
        assertTrue(split.files.isEmpty())
        assertEquals("", split.text)
    }
}
