package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.testing.assertFalse
import com.labteto.dshmobile.testing.assertTrue
import kotlin.test.Test

/**
 * Pins when the chat's view tabs fold away and come back, and the rule for the bar's status row.
 *
 * The scroll rule is checked with plain numbers: positive deltas are the reader going back toward older
 * messages, negative ones coming back toward the newest, and the distance is how far the list is from
 * the newest message.
 */
class ChatTabsCollapseTest {

    private fun rule() = TabsCollapseRule(foldAfter = 40f, returnAfter = 20f, minDistance = 120f)

    private val far = 10_000f

    // ----- folding -----

    @Test
    fun `tabs fold once the reader has gone far enough back`() {
        val rule = rule()
        rule.onScroll(30f, far)
        assertFalse("30 is under the fold travel", rule.collapsed)
        rule.onScroll(15f, far)
        assertTrue("45 in one direction folds them", rule.collapsed)
    }

    @Test
    fun `tabs stay while the list is near the newest message however far the finger travels`() {
        val rule = rule()
        repeat(10) { rule.onScroll(50f, 80f) }
        assertFalse("under the minimum distance from the newest message", rule.collapsed)
        rule.onScroll(1f, 120f)
        assertTrue("at the minimum distance the accumulated travel folds them", rule.collapsed)
    }

    @Test
    fun `a flick that stops short and turns around folds nothing`() {
        val rule = rule()
        rule.onScroll(30f, far)
        rule.onScroll(-5f, far)
        rule.onScroll(30f, far)
        assertFalse("each leg is under the fold travel and a turn restarts the count", rule.collapsed)
    }

    // ----- returning -----

    @Test
    fun `tabs come back after a short drag toward the newest`() {
        val rule = rule()
        rule.onScroll(60f, far)
        assertTrue(rule.collapsed)
        rule.onScroll(-12f, far)
        assertTrue("12 is under the return travel", rule.collapsed)
        rule.onScroll(-10f, far)
        assertFalse("22 toward the newest brings them back", rule.collapsed)
    }

    @Test
    fun `a wobble while reading back does not bring the tabs back`() {
        val rule = rule()
        rule.onScroll(60f, far)
        rule.onScroll(-8f, far)
        rule.onScroll(40f, far)
        rule.onScroll(-8f, far)
        assertTrue(rule.collapsed)
    }

    @Test
    fun `reveal puts the tabs back and forgets the travel`() {
        val rule = rule()
        rule.onScroll(60f, far)
        rule.reveal()
        assertFalse(rule.collapsed)
        rule.onScroll(30f, far)
        assertFalse("the earlier 60 is not counted again", rule.collapsed)
    }

    @Test
    fun `a zero delta changes nothing`() {
        val rule = rule()
        rule.onScroll(60f, far)
        rule.onScroll(0f, far)
        assertTrue(rule.collapsed)
    }

    // ----- where the tabs may fold at all -----

    @Test
    fun `tabs are always drawn on the Trajectory and Files views`() {
        assertTrue(chatTabsVisible(collapsed = true, tab = ChatTab.Trajectory, locked = false))
        assertTrue(chatTabsVisible(collapsed = true, tab = ChatTab.Files, locked = false))
    }

    @Test
    fun `tabs are drawn while a sheet is open`() {
        assertTrue(chatTabsVisible(collapsed = true, tab = ChatTab.Chat, locked = true))
    }

    @Test
    fun `tabs are hidden only on the transcript with nothing open and the rule folded`() {
        assertFalse(chatTabsVisible(collapsed = true, tab = ChatTab.Chat, locked = false))
        assertTrue(chatTabsVisible(collapsed = false, tab = ChatTab.Chat, locked = false))
    }

    // ----- the status row -----

    @Test
    fun `the status row is left out when nothing applies`() {
        assertFalse(
            hasStatusRow(running = false, needsYou = false, agentPresetLabel = null, teamAvailable = false, subagentCount = 0),
        )
    }

    @Test
    fun `each item that has something to say brings the status row back`() {
        assertTrue(hasStatusRow(running = true, needsYou = false, agentPresetLabel = null, teamAvailable = false, subagentCount = 0))
        assertTrue(hasStatusRow(running = false, needsYou = true, agentPresetLabel = null, teamAvailable = false, subagentCount = 0))
        assertTrue(hasStatusRow(running = false, needsYou = false, agentPresetLabel = "Standard", teamAvailable = false, subagentCount = 0))
        assertTrue(hasStatusRow(running = false, needsYou = false, agentPresetLabel = null, teamAvailable = true, subagentCount = 0))
        assertTrue(hasStatusRow(running = false, needsYou = false, agentPresetLabel = null, teamAvailable = false, subagentCount = 2))
    }
}
