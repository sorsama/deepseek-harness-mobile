package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The drawer's filter chips must not take the session list's height — issue #70.
 *
 * 0.19.0 drew the fade at the end of the chip row as a second child of a `Box`, sized with
 * `fillMaxHeight()`. Inside a `Box` that child counts toward the box's own size, and the drawer's
 * `Column` offers an unweighted child all the height that is left, so the chip row grew to the whole
 * drawer. The weighted session list under it was measured at zero height, and so was the start bar:
 * the filter said "All 3" over a blank panel, and search showed nothing either because its results
 * live in the same list.
 *
 * The same shape as the drawer is used here: a column holding the chip row, then a weighted list
 * area. Nothing about the chips themselves matters, only how much height the row asks for.
 */
class FadingChipRowLayoutTest {

    @get:Rule val compose = createComposeRule()

    private fun setDrawerShape(chipHeight: Int = 48) {
        compose.setContent {
            Column(Modifier.fillMaxSize()) {
                FadingChipRow(fadeColor = Color.Black, modifier = Modifier.testTag("chips")) {
                    repeat(4) { Box(Modifier.width(110.dp).height(chipHeight.dp)) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().testTag("list"))
                Box(Modifier.fillMaxWidth().height(56.dp).testTag("bar"))
            }
        }
    }

    @Test
    fun chipRowKeepsItsOwnHeight() {
        setDrawerShape()
        val chips = compose.onNodeWithTag("chips").getUnclippedBoundsInRoot()
        assertEquals("the chip row is as tall as its chips, no more", 48f, chips.height.value, 0.5f)
    }

    @Test
    fun listAreaKeepsTheRestOfTheColumn() {
        setDrawerShape()
        val chips = compose.onNodeWithTag("chips").getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("list").getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        assertTrue("the list got ${list.height} of height; it must not be starved", list.height.value > 200f)
        assertEquals("the start bar keeps its own height", 56f, bar.height.value, 0.5f)
        assertTrue("the list sits below the chips", list.top >= chips.bottom)
        assertTrue("the start bar sits below the list", bar.top >= list.bottom)
    }

    @Test
    fun aTallerChipRowStillLeavesTheList() {
        setDrawerShape(chipHeight = 72)
        val chips = compose.onNodeWithTag("chips").getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("list").getUnclippedBoundsInRoot()
        assertEquals(72f, chips.height.value, 0.5f)
        assertTrue(list.height.value > 200f)
    }

    @Test
    fun theFadeAddsNoSizeOfItsOwn() {
        compose.setContent {
            Box(Modifier.size(width = 300.dp, height = 600.dp)) {
                FadingChipRow(fadeColor = Color.Black, modifier = Modifier.testTag("chips")) {
                    Box(Modifier.size(width = 80.dp, height = 40.dp))
                }
            }
        }
        val chips = compose.onNodeWithTag("chips").getUnclippedBoundsInRoot()
        assertEquals("even in a tall parent the row is only as tall as its chips", 40f, chips.height.value, 0.5f)
    }
}
