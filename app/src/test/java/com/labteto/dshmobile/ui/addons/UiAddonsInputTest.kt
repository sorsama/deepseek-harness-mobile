package com.labteto.dshmobile.ui.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back over a plugin dialog, and the keyboard for a plugin text field, as the layer decides them. */
class UiAddonsInputTest {
    private val treat = "dsh-ui-sync-example-pet|conversation.input.right|example-pet.treat"

    // ------------------------------------------------------------------ Back

    private class BackLog : UiAddonsBack.Effects {
        val enabled = mutableListOf<Boolean>()
        val sent = mutableListOf<String>()
        var fallThroughs = 0
        var reachable = true
        var timer: (() -> Unit)? = null
        var cancelled = 0
        private var ids = 0

        override fun setEnabled(enabled: Boolean) {
            this.enabled += enabled
        }

        override fun sendBack(requestId: String): Boolean {
            sent += requestId
            return reachable
        }

        override fun fallThrough() {
            fallThroughs++
        }

        override fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
            timer = block
            return { cancelled++; timer = null }
        }

        override fun newRequestId(): String = "b${++ids}"
    }

    private fun openDialog(log: BackLog): UiAddonsBack =
        UiAddonsBack(log).apply { update(supported = true, shown = true, dialogs = listOf(treat)) }

    @Test fun `Back is taken only while a dialog is open on a shown layer with the feature`() {
        val log = BackLog()
        val back = UiAddonsBack(log)
        back.update(supported = true, shown = true, dialogs = emptyList())
        assertFalse(back.wanted)
        assertTrue(log.enabled.isEmpty())
        back.update(dialogs = listOf(treat))
        assertTrue(back.wanted)
        assertEquals(listOf(true), log.enabled)
        back.update(shown = false)
        assertEquals(listOf(true, false), log.enabled)
        back.update(shown = true, supported = false)
        assertFalse(back.wanted)
        assertEquals(listOf(true, false), log.enabled)
    }

    @Test fun `a dialog the page closes ends Back there`() {
        val log = BackLog()
        val back = openDialog(log)
        back.onBack()
        assertEquals(listOf("b1"), log.sent)
        assertEquals("b1", back.waiting)
        assertTrue(back.onAck("b1", ok = true, handled = true))
        assertEquals(0, log.fallThroughs)
        assertEquals(1, log.cancelled)
        assertNull(back.waiting)
        // The page then says the dialog is gone, and Back is native again.
        back.update(dialogs = emptyList())
        assertEquals(listOf(true, false), log.enabled)
    }

    @Test fun `a dialog that ignores Escape hands Back on`() {
        val log = BackLog()
        val back = openDialog(log)
        back.onBack()
        back.onAck("b1", ok = true, handled = false)
        assertEquals(1, log.fallThroughs)
        // Still listed as open: the next Back asks the page again.
        assertTrue(back.wanted)
    }

    @Test fun `no answer in time hands Back on`() {
        val log = BackLog()
        val back = openDialog(log)
        back.onBack()
        log.timer!!.invoke()
        assertEquals(1, log.fallThroughs)
        assertNull(back.waiting)
        // A late answer changes nothing.
        assertFalse(back.onAck("b1", ok = true, handled = true))
        assertEquals(1, log.fallThroughs)
    }

    @Test fun `a refused or unsent request hands Back on`() {
        val refused = BackLog()
        val back = openDialog(refused)
        back.onBack()
        back.onAck("b1", ok = false, handled = null)
        assertEquals(1, refused.fallThroughs)

        val unreachable = BackLog().apply { reachable = false }
        openDialog(unreachable).onBack()
        assertEquals(1, unreachable.fallThroughs)
        assertNull(unreachable.timer)
    }

    @Test fun `a second Back while one waits is swallowed`() {
        val log = BackLog()
        val back = openDialog(log)
        back.onBack()
        back.onBack()
        assertEquals(listOf("b1"), log.sent)
        assertEquals(0, log.fallThroughs)
    }

    @Test fun `other acks are not Back answers`() {
        val back = openDialog(BackLog())
        back.onBack()
        assertFalse(back.onAck("f1", ok = true, handled = null))
        assertEquals("b1", back.waiting)
    }

    @Test fun `a page that goes away settles Back`() {
        val log = BackLog()
        val back = openDialog(log)
        back.onBack()
        back.reset()
        assertNull(back.waiting)
        assertFalse(back.wanted)
        assertEquals(1, log.cancelled)
        assertEquals(false, log.enabled.last())
    }

    // ------------------------------------------------------------------ focus

    private class FocusLog : UiAddonsFocus.Effects {
        val calls = mutableListOf<String>()
        var reachable = true

        override fun allowFocus(allowed: Boolean) {
            calls += "allow=$allowed"
        }

        override fun focusAndShowKeyboard() {
            calls += "focus+keyboard"
        }

        override fun hideKeyboardIfFocused() {
            calls += "hide"
        }

        override fun releaseFocus() {
            calls += "release"
        }

        override fun sendBlur(): Boolean {
            calls += "blur"
            return reachable
        }
    }

    @Test fun `a plugin text field gets focus and the keyboard`() {
        val log = FocusLog()
        val focus = UiAddonsFocus(log).apply { supported = true }
        log.calls.clear()
        focus.onPageFocus(editable = true, itemId = treat)
        assertEquals(listOf("allow=true", "focus+keyboard"), log.calls)
        assertTrue(focus.editable)
    }

    @Test fun `focus on a plugin button keeps the view but drops the keyboard`() {
        val log = FocusLog()
        val focus = UiAddonsFocus(log).apply { supported = true }
        focus.onPageFocus(editable = true, itemId = treat)
        log.calls.clear()
        focus.onPageFocus(editable = false, itemId = treat)
        assertEquals(listOf("allow=true", "hide"), log.calls)
    }

    @Test fun `focus leaving the items releases the view`() {
        val log = FocusLog()
        val focus = UiAddonsFocus(log).apply { supported = true }
        focus.onPageFocus(editable = true, itemId = treat)
        log.calls.clear()
        focus.onPageFocus(editable = false, itemId = null)
        assertEquals(listOf("release"), log.calls)
        assertNull(focus.itemId)
    }

    @Test fun `a native field taking focus tells the page to let go`() {
        val log = FocusLog()
        val focus = UiAddonsFocus(log).apply { supported = true }
        focus.onPageFocus(editable = true, itemId = treat)
        log.calls.clear()
        focus.onNativeFocus()
        assertEquals(listOf("blur"), log.calls)
        // Nothing in the page has focus: native focus moving around says nothing to it.
        focus.onPageFocus(editable = false, itemId = null)
        log.calls.clear()
        focus.onNativeFocus()
        assertTrue(log.calls.isEmpty())
    }

    @Test fun `a blur that cannot be sent releases the view here`() {
        val log = FocusLog().apply { reachable = false }
        val focus = UiAddonsFocus(log).apply { supported = true }
        focus.onPageFocus(editable = true, itemId = treat)
        log.calls.clear()
        focus.onNativeFocus()
        assertEquals(listOf("blur", "release"), log.calls)
        assertNull(focus.itemId)
    }

    @Test fun `a page without the focus feature never gets focus`() {
        val log = FocusLog()
        val focus = UiAddonsFocus(log)
        focus.onPageFocus(editable = true, itemId = treat)
        focus.onNativeFocus()
        assertTrue(log.calls.isEmpty())
    }

    @Test fun `losing the feature releases the view`() {
        val log = FocusLog()
        val focus = UiAddonsFocus(log).apply { supported = true }
        focus.onPageFocus(editable = true, itemId = treat)
        log.calls.clear()
        focus.supported = false
        assertEquals(listOf("release"), log.calls)
        assertFalse(focus.editable)
    }
}
