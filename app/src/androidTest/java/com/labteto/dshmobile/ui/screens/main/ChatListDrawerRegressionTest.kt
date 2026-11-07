package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Issue #70: the filter strip must leave height for the session list on a phone.
 *
 * Exercises the actual production DrawerQuickFilters, not a copy of its modifiers, in a fixture
 * Column with a weighted Box / fillMaxSize LazyColumn matching the drawer's layout pattern. The
 * Column, list, header, footer, rows and filtering state are all fixtures; this does not render the
 * full ChatListDrawer call site or test SessionStore, session filtering or host connectivity.
 */
class ChatListDrawerRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Row(val id: String, val title: String, val running: Boolean = false)

    private class Harness(initial: List<Row>) {
        var rows by mutableStateOf(initial)
        var open by mutableStateOf(true)
        var filter by mutableStateOf(DrawerFilter.All)
        val selected = mutableListOf<String>()
    }

    private fun render(
        initial: List<Row>,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ): Harness {
        val h = Harness(initial)
        compose.setContent {
            BoxWithConstraints {
                // A bounded phone viewport, scaled down only if the assigned device is smaller.
                val density = Density(density = minOf(LocalDensity.current.density,
                    constraints.maxWidth / 360f, constraints.maxHeight / 640f), fontScale = fontScale)
                CompositionLocalProvider(
                    LocalDensity provides density,
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    DshTheme {
                        if (h.open) {
                            Column(Modifier.size(360.dp, 640.dp).testTag("viewport")) {
                                Spacer(Modifier.height(96.dp)) // wordmark and hero
                                DrawerQuickFilters(
                                    totalCount = h.rows.size,
                                    runningCount = h.rows.count { it.running },
                                    needsYouCount = 0,
                                    filter = h.filter,
                                    onFilterChange = { h.filter = it },
                                )
                                Spacer(Modifier.height(8.dp))
                                Box(Modifier.weight(1f)) {
                                    LazyColumn(Modifier.fillMaxSize().testTag("session-list")) {
                                        val visible = h.rows.filter {
                                            h.filter != DrawerFilter.Running || it.running
                                        }
                                        if (visible.isEmpty()) {
                                            item("empty") {
                                                Text(context.getString(R.string.chatlist_empty),
                                                    Modifier.padding(vertical = 24.dp))
                                            }
                                        }
                                        items(visible, key = { it.id }) { row ->
                                            Text(row.title, Modifier.fillMaxWidth()
                                                .clickable { h.selected += row.id }
                                                .padding(16.dp))
                                        }
                                    }
                                }
                                Spacer(Modifier.height(48.dp)) // start-task bar
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
        return h
    }

    private fun assertListHasVisibleHeight() {
        val bounds = compose.onNodeWithTag("session-list").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        // The viewport is 640dp tall even when scaled down for a smaller assigned device.
        val heightDp = bounds.height * 640f / viewport.height
        assertTrue("Quick filters consumed the fixture height; list height: ${heightDp}dp, bounds: $bounds",
            heightDp > 300f)
    }

    private fun assertRowIsVisibleAndSelectable(title: String) {
        val row = compose.onNodeWithText(title).assertIsDisplayed()
        val bounds = row.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val heightDp = bounds.height * 640f / viewport.height
        assertTrue("Session row must retain a 48dp hit target; height: ${heightDp}dp", heightDp >= 48f)
        row.performClick()
    }

    @Test
    fun populatedSessionsAreVisibleAndSelectionReachesCallback() {
        val h = render(listOf(Row("session-1", "Existing session")))

        assertListHasVisibleHeight()
        assertRowIsVisibleAndSelectable("Existing session")
        compose.runOnIdle { assertEquals(listOf("session-1"), h.selected) }
    }

    @Test
    fun largeTextInRtlStillLeavesSessionsVisibleAndSelectable() {
        val h = render(listOf(Row("rtl", "RTL session")),
            fontScale = 1.5f, layoutDirection = LayoutDirection.Rtl)

        assertListHasVisibleHeight()
        compose.onNodeWithText(context.getString(R.string.ink_chat_filter_all, 1)).assertIsDisplayed()
        assertRowIsVisibleAndSelectable("RTL session")
        compose.runOnIdle { assertEquals(listOf("rtl"), h.selected) }
    }

    @Test
    fun anEmptyListShowsItsFallbackRatherThanABlankDrawer() {
        render(emptyList())

        assertListHasVisibleHeight()
        compose.onNodeWithText(context.getString(R.string.chatlist_empty)).assertIsDisplayed()
    }

    @Test
    fun filteringThenReopeningWithUpdatedRowsKeepsTheListVisible() {
        val h = render(listOf(Row("idle", "Idle session"), Row("running", "Running session", true)))
        val running = context.getString(R.string.ink_chat_filter_running, 1)
        val all = context.getString(R.string.ink_chat_filter_all, 2)

        compose.onNodeWithText(running).assertIsDisplayed().performClick()
        compose.onNodeWithText(running).assertIsSelected()
        assertListHasVisibleHeight()
        compose.onNodeWithText("Running session").assertIsDisplayed()
        compose.onNodeWithText("Idle session").assertDoesNotExist()
        compose.onNodeWithText(all).performClick()
        compose.onNodeWithText("Idle session").assertIsDisplayed()

        compose.runOnIdle { h.open = false }
        compose.onNodeWithText("Idle session").assertDoesNotExist()
        compose.runOnIdle {
            h.rows = listOf(Row("idle", "Updated session"), Row("new", "New session"))
            h.open = true
        }
        assertListHasVisibleHeight()
        compose.onNodeWithText("Idle session").assertDoesNotExist()
        assertRowIsVisibleAndSelectable("Updated session")
        compose.onNodeWithText("New session").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("idle"), h.selected) }
    }
}
