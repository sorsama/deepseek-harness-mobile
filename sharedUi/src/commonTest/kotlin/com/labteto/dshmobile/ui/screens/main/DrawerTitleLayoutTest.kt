package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertFalse
import com.labteto.dshmobile.testing.assertTrue
import kotlin.test.Test

/**
 * How the drawer's "Sessions" title gives way in a narrow pane. It must never be cut with an ellipsis.
 * Shrinking the title and then folding Sort into the More menu keep the header one line tall, which a
 * landscape phone needs, so both come before a second line for the actions.
 */
class DrawerTitleLayoutTest {

    /**
     * The title fits beside the actions at the (compact, folded) pairs in [besideAt], and alone at the
     * sizes in [aloneAt] (`true` is the compact size).
     */
    private fun choose(besideAt: Set<Pair<Boolean, Boolean>>, aloneAt: Set<Boolean>) =
        chooseTitleLayout(fitsBeside = { compact, folded -> (compact to folded) in besideAt }, fitsAlone = { it in aloneAt })

    private val everything = setOf(false to false, true to false, true to true)

    @Test
    fun `a wide drawer keeps the display title beside every action`() {
        assertEquals(TitleLayout.Beside, choose(besideAt = everything, aloneAt = setOf(false, true)))
    }

    @Test
    fun `a narrower pane shrinks the title before it touches the actions`() {
        assertEquals(TitleLayout.BesideCompact, choose(besideAt = setOf(true to false, true to true), aloneAt = setOf(false, true)))
    }

    @Test
    fun `a narrow pane folds Sort into the More menu rather than take a second line`() {
        val layout = choose(besideAt = setOf(true to true), aloneAt = setOf(false, true))
        assertEquals(TitleLayout.BesideFolded, layout)
        assertTrue(layout.sortFolded)
        assertFalse(layout.stacked)
    }

    @Test
    fun `when even that does not fit the actions move under the title`() {
        assertEquals(TitleLayout.Stacked, choose(besideAt = emptySet(), aloneAt = setOf(false, true)))
        assertEquals(TitleLayout.StackedCompact, choose(besideAt = emptySet(), aloneAt = setOf(true)))
    }

    @Test
    fun `a title too long for any line wraps instead of being cut`() {
        val layout = choose(besideAt = emptySet(), aloneAt = emptySet())
        assertEquals(TitleLayout.StackedWrapped, layout)
        assertTrue(layout.wraps)
    }

    @Test
    fun `only the last resort wraps and only the stacked ones move the actions and only one folds Sort`() {
        for (layout in TitleLayout.values()) {
            assertEquals(layout == TitleLayout.StackedWrapped, layout.wraps)
            assertEquals(layout in setOf(TitleLayout.Stacked, TitleLayout.StackedCompact, TitleLayout.StackedWrapped), layout.stacked)
            assertEquals(layout == TitleLayout.BesideFolded, layout.sortFolded)
        }
        assertFalse(TitleLayout.Beside.compact)
    }
}
