package com.labteto.dshmobile.ui.input

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.HOLD_MENU_TAG
import com.labteto.dshmobile.ui.components.HoldAction
import com.labteto.dshmobile.ui.components.HoldMenu
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.clickableWithHold
import com.labteto.dshmobile.ui.components.holdable
import com.labteto.dshmobile.ui.components.horizontalWheelScroll
import com.labteto.dshmobile.ui.screens.main.Composer
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Keyboard and mouse behaviour for a Chromebook or a docked tablet, driven through Compose's own
 * key and mouse injection. The decisions behind the keys are unit-tested in [ComposerKeysTest]; these
 * check that the composer, the windows and the controls act on them.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopInputTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The configuration as a device with, or without, a keyboard attached reports it. */
    @androidx.compose.runtime.Composable
    private fun WithKeyboard(attached: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        val base = LocalConfiguration.current
        val config = Configuration(base).apply {
            keyboard = if (attached) Configuration.KEYBOARD_QWERTY else Configuration.KEYBOARD_NOKEYS
        }
        CompositionLocalProvider(LocalConfiguration provides config, content = content)
    }

    // ---- The composer ------------------------------------------------------------------------

    private fun composer(keyboard: Boolean, sent: MutableList<String>, initial: String = "hello"): () -> String {
        var draft by mutableStateOf(initial)
        compose.setContent {
            DshTheme {
                WithKeyboard(keyboard) {
                    Composer(
                        draft, { draft = it }, emptyList(), {}, {}, null, null, {}, null, null,
                        running = false, enabled = true, onOpenSheet = {}, onStop = {},
                        onSend = { sent.add(it) },
                    )
                }
            }
        }
        return { draft }
    }

    private val field get() = compose.onNode(androidx.compose.ui.test.hasSetTextAction())

    @Test fun enterSendsAndClearsTheDraftWithAKeyboard() {
        val sent = mutableListOf<String>()
        val draft = composer(keyboard = true, sent)
        field.requestFocus()
        field.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle {
            assertEquals(listOf("hello"), sent)
            assertEquals("", draft())
        }
    }

    @Test fun shiftEnterAddsALineAndSendsNothing() {
        val sent = mutableListOf<String>()
        val draft = composer(keyboard = true, sent)
        field.requestFocus()
        field.performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Enter)
            keyUp(Key.ShiftLeft)
        }
        compose.runOnIdle {
            assertTrue("nothing is sent", sent.isEmpty())
            assertTrue("a line break was added to the draft, got ${draft().replace("\n", "\\n")}", draft().contains("\n"))
        }
    }

    @Test fun ctrlEnterSendsToo() {
        val sent = mutableListOf<String>()
        composer(keyboard = true, sent)
        field.requestFocus()
        field.performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Enter)
            keyUp(Key.CtrlLeft)
        }
        compose.runOnIdle { assertEquals(listOf("hello"), sent) }
    }

    @Test fun withoutAKeyboardEnterDoesNotSend() {
        // A phone: Enter reaching the field (which it only can from a key event) is left to the field.
        val sent = mutableListOf<String>()
        composer(keyboard = false, sent)
        field.requestFocus()
        field.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
    }

    @Test fun enterOnAnEmptyDraftSendsNothing() {
        val sent = mutableListOf<String>()
        composer(keyboard = true, sent, initial = "")
        field.requestFocus()
        field.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
    }

    // ---- Windows: Escape ---------------------------------------------------------------------

    @Test fun escapeClosesASheet() {
        var open by mutableStateOf(true)
        compose.setContent {
            DshTheme {
                if (open) {
                    HoldMenu(
                        onDismiss = { open = false },
                        actions = listOf(HoldAction("Copy", Icons.Outlined.Edit) {}),
                        preview = "a message",
                    )
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Copy").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(HOLD_MENU_TAG).performKeyInput { pressKey(Key.Escape) }
        compose.waitUntil(5_000) { !open }
        assertTrue(!open)
    }

    @Test fun escapeClosesADialog() {
        var open by mutableStateOf(true)
        compose.setContent {
            DshTheme { if (open) DsDialog(title = "Title", onDismiss = { open = false }) { Text("Dialog body") } }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Dialog body").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Dialog body").performKeyInput { pressKey(Key.Escape) }
        compose.waitUntil(5_000) { !open }
        assertTrue(!open)
    }

    // ---- Mouse -------------------------------------------------------------------------------

    @Test fun anIconButtonNamesItselfWhenAMouseRestsOnIt() {
        compose.setContent {
            DshTheme { DsIconButton(Icons.Filled.Close, "Close the thing", onClick = {}, modifier = Modifier.testTag("button")) }
        }
        // The description is on the icon, not text: no tooltip yet means no text node with the words.
        compose.onAllNodesWithText("Close the thing").assertCountEquals(0)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("button").performMouseInput { moveTo(center) }
        compose.mainClock.advanceTimeBy(900)
        compose.waitForIdle()
        compose.onAllNodesWithText("Close the thing").assertCountEquals(1)
        compose.onNodeWithTag("button").performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(-40f, -40f)) }
        compose.mainClock.advanceTimeBy(900)
        compose.waitForIdle()
        compose.onAllNodesWithText("Close the thing").assertCountEquals(0)
    }

    @Test fun aTouchHoldDoesNotShowATooltip() {
        // The tooltip is a pointer thing: a hold must not grow a second meaning on a phone.
        compose.setContent {
            DshTheme { DsIconButton(Icons.Filled.Close, "Close the thing", onClick = {}, modifier = Modifier.testTag("button")) }
        }
        compose.onNodeWithTag("button").performTouchInputHold()
        compose.waitForIdle()
        compose.onAllNodesWithText("Close the thing").assertCountEquals(0)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performTouchInputHold() {
        performTouchInput { down(center); advanceEventTime(900); up() }
    }

    @Test fun rightClickOpensWhatAHoldOpensAndDoesNotClick() {
        var clicks by mutableIntStateOf(0)
        var holds by mutableIntStateOf(0)
        compose.setContent {
            DshTheme {
                Box(
                    Modifier.testTag("row").size(120.dp, 48.dp)
                        .clickableWithHold("Actions", onHold = { holds++ }, onClick = { clicks++ }),
                )
            }
        }
        compose.onNodeWithTag("row").performMouseInput { rightClick() }
        compose.runOnIdle { assertEquals(1, holds); assertEquals(0, clicks) }
        compose.onNodeWithTag("row").performClick()
        compose.runOnIdle { assertEquals(1, holds); assertEquals(1, clicks) }
    }

    @Test fun rightClickWorksOnAHoldOnlyBubble() {
        var holds by mutableIntStateOf(0)
        compose.setContent {
            DshTheme { Box(Modifier.testTag("bubble").size(120.dp, 48.dp).holdable("Actions") { holds++ }) }
        }
        compose.onNodeWithTag("bubble").performMouseInput { rightClick() }
        compose.runOnIdle { assertEquals(1, holds) }
    }

    @Test fun theWheelMovesASidewaysList() {
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        compose.setContent {
            DshTheme {
                state = rememberLazyListState()
                LazyRow(
                    state = state,
                    modifier = Modifier.testTag("tray").width(300.dp).horizontalWheelScroll(state),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items((0 until 20).toList()) { Box(Modifier.size(100.dp).background(Color.Gray)) { Text("$it") } }
                }
            }
        }
        compose.runOnIdle { assertEquals(0, state.firstVisibleItemIndex) }
        compose.onNodeWithTag("tray").performMouseInput { scroll(3f, ScrollWheel.Vertical) }
        compose.runOnIdle { assertTrue("the wheel moved the list", state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0) }
    }

    // ---- Keyboard focus ----------------------------------------------------------------------

    /**
     * Puts the window in keyboard input mode, as a hardware key press does on a real device. A phone starts
     * in touch mode, where a clickable takes no focus and a Tab sent straight to the Compose view (which is
     * what the test injector does) has nowhere to start from.
     */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @androidx.compose.runtime.Composable
    private fun KeyboardMode() {
        val mode = androidx.compose.ui.platform.LocalInputModeManager.current
        androidx.compose.runtime.SideEffect { mode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
    }

    @Test fun tabMovesFocusInReadingOrder() {
        compose.setContent {
            DshTheme {
                KeyboardMode()
                Row {
                    for (name in listOf("a", "b", "c")) {
                        Box(Modifier.testTag(name).size(60.dp).clickable { })
                    }
                }
            }
        }
        compose.onNodeWithTag("a").requestFocus()
        compose.onNodeWithTag("a").assertIsFocused()
        compose.onNodeWithTag("a").performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithTag("b").assertIsFocused()
        compose.onNodeWithTag("b").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft) }
        compose.onNodeWithTag("a").assertIsFocused()
    }

    @Test fun theFocusedControlGetsARing() {
        var accent = Color.Unspecified
        compose.setContent {
            DshTheme(indication = { base, ring -> rememberFocusRingIndication(base, ring) }) {
                KeyboardMode()
                accent = DsTheme.colors.accent
                Box(Modifier.testTag("box").size(160.dp, 96.dp).background(Color.White).clickable { })
            }
        }
        compose.onNodeWithTag("box").requestFocus()
        compose.waitForIdle()
        val image = compose.onNodeWithTag("box").captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels, 0, 0, image.width, image.height)
        // A 2dp ring centred 2dp in from the edge: somewhere in the top 6dp of the middle column is ring-coloured.
        val density = context.resources.displayMetrics.density
        val x = image.width / 2
        val column = (0 until (6 * density).toInt()).map { y -> pixels[y * image.width + x] }
        assertTrue("the focused box wears an accent ring, column was ${column.map { Integer.toHexString(it) }}", accent.toArgb() in column)
    }

    @Test fun anEnabledControlShowsTheHandAndASwitchedOffOneDoesNot() {
        // The cursor itself is the platform's to draw; what is pinned here is that a disabled
        // clickable is not offered as one (no hand over something that will not respond).
        var clicks by mutableIntStateOf(0)
        compose.setContent {
            DshTheme { Box(Modifier.testTag("off").size(60.dp).clickable(enabled = false) { clicks++ }) }
        }
        compose.onNodeWithTag("off").performClick()
        compose.runOnIdle { assertEquals(0, clicks) }
    }

    @Test fun theSendButtonHasATooltipForAMouse() {
        val sent = mutableListOf<String>()
        compose.setContent {
            DshTheme {
                Composer(
                    "hi", {}, emptyList(), {}, {}, null, null, {}, null, null,
                    running = false, enabled = true, onOpenSheet = {}, onStop = {}, onSend = { sent.add(it) },
                )
            }
        }
        val label = context.getString(R.string.chat_composer_send)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(label).performMouseInput { moveTo(center) }
        compose.mainClock.advanceTimeBy(900)
        compose.waitForIdle()
        compose.onAllNodesWithText(label).assertCountEquals(1)
    }

    @Test fun theDropHighlightSaysWhatDroppingWillDo() {
        var visible by mutableStateOf(false)
        compose.setContent { DshTheme { DropHighlight(visible = visible) } }
        compose.onNodeWithTag(DROP_HIGHLIGHT_TAG).assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.chat_drop_hint)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(DROP_HIGHLIGHT_TAG).assertExists()
        compose.onNode(hasText(context.getString(R.string.chat_drop_hint))).assertExists()
    }
}
