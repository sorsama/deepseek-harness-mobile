package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertNull
import kotlin.test.Test

/** Which link targets in a reply open as a workspace file, on both platforms. */
class PreviewPathTest {

    @Test
    fun `a relative path opens as itself without its anchor`() {
        assertEquals("app/src/Main.kt", previewPath("app/src/Main.kt"))
        assertEquals("app/src/Main.kt", previewPath(" <app/src/Main.kt#L12> "))
    }

    @Test
    fun `a Windows path with either slash opens as itself`() {
        assertEquals("C:\\Users\\demo\\a.txt", previewPath("C:\\Users\\demo\\a.txt"))
        assertEquals("C:/Users/demo/a.txt", previewPath("C:/Users/demo/a.txt"))
    }

    @Test
    fun `a file URI opens as its decoded path`() {
        assertEquals("/C:/Users/demo/my notes.md", previewPath("file:///C:/Users/demo/my%20notes.md"))
    }

    @Test
    fun `an anchor alone or another scheme opens nothing`() {
        assertNull(previewPath("#section"))
        assertNull(previewPath(""))
        assertNull(previewPath("intent://scan"))
        assertNull(previewPath("mailto:someone@example.com"))
        assertNull(previewPath("https://example.com/a.md"))
    }
}
