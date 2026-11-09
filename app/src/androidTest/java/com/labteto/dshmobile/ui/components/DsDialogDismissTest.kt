package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The scrim around a dialog's plate.
 *
 * The dialog window covers the whole display — it has to, or a dialog's footer slides under the
 * gesture bar (see `DsWindow`) — so `Window.shouldCloseOnTouch` can never see a touch outside the
 * decor view, and the platform's `dismissOnClickOutside` never fires. [DsDialog] handles the
 * gesture itself, and these pin both halves of it: the area around the plate dismisses, the plate
 * does not. Tapping a dialog and having nothing happen was reported on the new-session picker,
 * which is one of the ~19 dialogs that share this surface.
 */
class DsDialogDismissTest {
    @get:Rule val compose = createComposeRule()

    private var dismissed = false

    private fun openDialog() {
        compose.setContent {
            DshTheme {
                DsDialog(title = "New session in", onDismiss = { dismissed = true }) {
                    Text("studio-pc", Modifier.size(200.dp, 48.dp))
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aTapBesideThePlateDismissesTheDialog() {
        openDialog()
        compose.onNode(isDialog()).performTouchInput { click(Offset(4f, 4f)) }
        compose.runOnIdle { assertTrue("a tap on the scrim must dismiss", dismissed) }
    }

    @Test
    fun aTapOnThePlateKeepsTheDialogOpen() {
        openDialog()
        compose.onNodeWithText("studio-pc").assertIsDisplayed().performClick()
        compose.runOnIdle { assertFalse("a tap on the plate must not dismiss", dismissed) }
    }
}
