package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The way out of a [DsBottomSheet] that needs no gesture.
 *
 * Reported on the Subagents sheet: with a long child transcript the sheet fills the screen, so there
 * is no scrim to tap (and none at all with the keyboard up), and a swipe down from inside the list
 * scrolls the list instead of the sheet. Every titled sheet now carries a close button in its title
 * row, and the row is not part of any scrolling region. These pin the three things that matter:
 * the button is there and dismisses, it dismisses once, and it is still there (and the row still
 * drags the sheet) after the content below has been scrolled to its end.
 */
@OptIn(ExperimentalTestApi::class)
class DsBottomSheetCloseTest {
    @get:Rule val compose = createComposeRule()

    private var dismissed = 0

    private fun openSheet(title: String? = "Subagents", rows: Int = 3) {
        dismissed = 0
        compose.setContent {
            DshTheme {
                DsBottomSheet(title = title, onDismiss = { dismissed += 1 }) {
                    Column(Modifier.fillMaxWidth().height(400.dp).testTag("scroller").verticalScroll(rememberScrollState())) {
                        repeat(rows) { Text("row $it", Modifier.fillMaxWidth().height(56.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aTitledSheetShowsACloseButtonAndItDismisses() {
        openSheet()
        compose.onNodeWithTag(DS_SHEET_CLOSE_TAG).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { dismissed > 0 }
        assertEquals("the close button asks the owner to drop the sheet", 1, dismissed)
    }

    @Test
    fun twoTapsDuringTheSlideDismissOnce() {
        openSheet()
        val close = compose.onNodeWithTag(DS_SHEET_CLOSE_TAG)
        close.performClick()
        close.performClick()
        compose.waitUntil(5_000) { dismissed > 0 }
        compose.waitForIdle()
        assertEquals("a second tap must not close the stream or the sheet twice", 1, dismissed)
    }

    @Test
    fun theCloseButtonStaysReachableWhenTheContentIsScrolledToItsEnd() {
        openSheet(rows = 60)
        compose.onNodeWithTag("scroller").performTouchInput { swipeUp() }
        compose.onNodeWithTag("scroller").performTouchInput { swipeUp() }
        compose.onNodeWithTag(DS_SHEET_CLOSE_TAG).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { dismissed > 0 }
        assertEquals(1, dismissed)
    }

    @Test
    fun aDragDownOnTheTitleRowClosesTheSheetWhateverIsScrolledBelow() {
        openSheet(rows = 60)
        compose.onNodeWithTag("scroller").performTouchInput { swipeUp() }
        // A real drag: well past the title text's own height, as a thumb would travel.
        compose.onNodeWithText("Subagents").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 900f, durationMillis = 250)
        }
        compose.waitUntil(5_000) { dismissed > 0 }
        assertEquals("a drag on the header must drag the sheet", 1, dismissed)
    }

    /**
     * Android 11 hands back to the app as a key press, and the focused follow-up field took it: the
     * first back hid the keyboard, the second did nothing, the third closed the sheet. The sheet now
     * looks at the key first.
     */
    @Test
    fun backAsAKeyClosesTheSheetEvenWhileATextFieldHasFocus() {
        dismissed = 0
        val focus = FocusRequester()
        compose.setContent {
            DshTheme {
                DsBottomSheet(title = "Subagents", onDismiss = { dismissed += 1 }) {
                    var text by remember { mutableStateOf("") }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().height(56.dp).testTag("field").focusRequester(focus),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { focus.requestFocus() }
        compose.onNodeWithTag("field").performKeyInput { pressKey(Key.Back) }
        compose.waitUntil(5_000) { dismissed > 0 }
        assertEquals("back must not stop at the focused field", 1, dismissed)
    }

    @Test
    fun aSheetWithNoTitleHasNoHeaderRowAndNoCloseButton() {
        openSheet(title = null)
        compose.onNodeWithText("row 0").assertIsDisplayed()
        compose.onAllNodesWithTagCount(DS_SHEET_CLOSE_TAG, expected = 0)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String, expected: Int) {
    val found = onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
    assertEquals("nodes tagged $tag", expected, found)
}
