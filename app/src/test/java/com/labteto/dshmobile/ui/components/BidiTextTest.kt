package com.labteto.dshmobile.ui.components

import com.labteto.dshmobile.ui.screens.main.technicalDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When an inline code span in a message is isolated: only inside prose written right to left. */
class BidiTextTest {

    @Test
    fun `Arabic Urdu and Hebrew are right to left`() {
        assertTrue(hasRightToLeftText("مرحبا بالعالم"))
        assertTrue(hasRightToLeftText("یہ ایک فائل ہے"))
        assertTrue(hasRightToLeftText("שלום"))
        // One letter among Latin text is enough: the paragraph is mixed.
        assertTrue(hasRightToLeftText("Open C:\\demo\\mkdocs.yml ثم احفظ"))
    }

    @Test
    fun `left to right text is not`() {
        assertFalse(hasRightToLeftText(""))
        assertFalse(hasRightToLeftText("Edit `C:\\demo\\user\\docs-site\\mkdocs.yml`, then save."))
        assertFalse(hasRightToLeftText("修改配置文件 `mkdocs.yml`"))
        assertFalse(hasRightToLeftText("Привет, 12345 !?"))
        assertFalse(hasRightToLeftText("1234567890 .,;:"))
    }

    @Test
    fun `a code span in right to left prose is isolated and left alone elsewhere`() {
        val path = "C:\\demo\\user\\docs-site\\mkdocs.yml"
        val arabic = "حرّر `$path` ثم احفظ"
        assertEquals(technicalDisplay(path), inlineCodeDisplay(path, paragraph = arabic))
        // Every other language the app ships: the characters stay exactly what was written.
        assertEquals(path, inlineCodeDisplay(path, paragraph = "Edit `$path` then save"))
        assertEquals(path, inlineCodeDisplay(path, paragraph = "编辑 `$path` 然后保存"))
        assertEquals(path, inlineCodeDisplay(path, paragraph = "`$path`"))
    }
}
