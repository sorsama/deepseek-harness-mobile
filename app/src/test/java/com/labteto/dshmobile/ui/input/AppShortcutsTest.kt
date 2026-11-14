package com.labteto.dshmobile.ui.input

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShortcutsTest {

    private fun match(key: Key, ctrl: Boolean = true, shift: Boolean = false, alt: Boolean = false, meta: Boolean = false) =
        matchAppShortcut(key, ctrl, shift, alt, meta)

    @Test fun theChordsMapToTheirCommands() {
        assertEquals(AppShortcut.NewSession, match(Key.N))
        assertEquals(AppShortcut.SearchSessions, match(Key.F))
        assertEquals(AppShortcut.FocusComposer, match(Key.K))
        assertEquals(AppShortcut.FocusComposer, match(Key.Slash))
        assertEquals(AppShortcut.ToggleSessionList, match(Key.B))
        assertEquals(AppShortcut.OpenSettings, match(Key.Comma))
    }

    @Test fun aBareKeyIsNeverAShortcut() {
        assertNull(match(Key.N, ctrl = false))
        assertNull(match(Key.F, ctrl = false))
    }

    @Test fun extraModifiersMeanItIsSomeoneElsesChord() {
        assertNull(match(Key.N, shift = true))
        assertNull(match(Key.N, alt = true))
        // Meta is the Search key on a Chromebook, which the system keeps for itself.
        assertNull(match(Key.N, ctrl = false, meta = true))
        assertNull(match(Key.N, meta = true))
    }

    @Test fun unrelatedKeysAreIgnored() {
        assertNull(match(Key.C))
        assertNull(match(Key.V))
        assertNull(match(Key.A))
        assertNull(match(Key.Enter))
    }

    @Test fun everyShortcutIsListedInThePanelTheSystemShows() {
        // The panel (Search+/ on a Chromebook) is built from the catalog, and the chords are handled by
        // matchAppShortcut: this keeps the two from drifting apart in either direction.
        val handled = ShortcutCatalog.flatMap { it.entries }
            .filter { it.modifiers == KeyEvent.META_CTRL_ON }
            .mapNotNull { matchAppShortcut(Key(it.keyCode), ctrl = true, shift = false, alt = false, meta = false) }
            .toSet()
        assertEquals("every shortcut has a catalog entry, and every catalog chord is handled", AppShortcut.entries.toSet(), handled)
    }

    @Test fun theCatalogListsEnterShiftEnterAndEscape() {
        val all = ShortcutCatalog.flatMap { it.entries }
        assertTrue(all.any { it.keyCode == KeyEvent.KEYCODE_ENTER && it.modifiers == 0 })
        assertTrue(all.any { it.keyCode == KeyEvent.KEYCODE_ENTER && it.modifiers == KeyEvent.META_SHIFT_ON })
        assertTrue(all.any { it.keyCode == KeyEvent.KEYCODE_ESCAPE })
    }

    @Test fun theRouterAcceptsAPostWithNobodyListening() {
        // A shared flow with no collector must not throw or block when something is posted.
        ShortcutRouter().post(AppShortcut.NewSession)
    }
}
