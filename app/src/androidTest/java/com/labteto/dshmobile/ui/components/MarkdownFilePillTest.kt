package com.labteto.dshmobile.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private const val PHONE_WINDOW_WIDTH_DP = 411
private const val PHONE_WINDOW_HEIGHT_DP = 891

/** A workspace link drawn as a pill opens from anywhere on the pill, not only its left half. */
class MarkdownFilePillTest {
    @get:Rule val compose = createComposeRule()

    private val files = mutableListOf<String>()
    private val folders = mutableListOf<String>()

    /**
     * A pill's label is capped at 70% of the *window's* width (`LocalConfiguration.screenWidthDp`),
     * not of the text it sits in, so `longLabelStaysInsideTheText` only means something when the
     * window is the phone this text box is sized for. On a tablet that cap is 900dp, the pill is
     * allowed to run far past the 320dp box, and the test failed with "pill right 1308 past text
     * right 640" without anything having changed in how a pill opens.
     *
     * The window is pinned to a typical phone, so the test checks the same thing on every AVD.
     */
    @Composable
    private fun phoneWindow() = Configuration(LocalConfiguration.current).apply {
        screenWidthDp = PHONE_WINDOW_WIDTH_DP
        screenHeightDp = PHONE_WINDOW_HEIGHT_DP
    }

    private fun render(markdown: String) {
        compose.setContent {
            DshTheme {
                CompositionLocalProvider(
                    LocalConfiguration provides phoneWindow(),
                    LocalFileOpener provides { path: String -> files += path },
                    LocalFolderOpener provides { path: String -> folders += path },
                ) {
                    MarkdownText(markdown, Modifier.width(320.dp))
                }
            }
        }
    }

    @Test fun pillOpensFromItsLeftEdge() {
        render("See [Notes.md](<docs/Notes.md>) for details.")
        compose.onNodeWithText("Notes.md", useUnmergedTree = true).performTouchInput { click(centerLeft + Offset(2f, 0f)) }
        compose.runOnIdle { assertEquals(listOf("docs/Notes.md"), files) }
    }

    @Test fun pillOpensFromItsRightEdge() {
        render("See [Notes.md](<docs/Notes.md>) for details.")
        compose.onNodeWithText("Notes.md", useUnmergedTree = true).performTouchInput { click(centerRight - Offset(2f, 0f)) }
        compose.runOnIdle { assertEquals(listOf("docs/Notes.md"), files) }
    }

    @Test fun shortPillBesideALongOneOpensFromItsRightEdge() {
        render("Edited [a.kt](<src/a.kt>) and [VeryLongFileNameForTesting.kt](<src/VeryLongFileNameForTesting.kt>).")
        compose.onNodeWithText("a.kt", useUnmergedTree = true).performTouchInput { click(centerRight - Offset(2f, 0f)) }
        compose.runOnIdle { assertEquals(listOf("src/a.kt"), files) }
    }

    @Test fun longLabelStaysInsideTheText() {
        val label = "a/very/long/label/that/no/phone/line/could/ever/hold/in/one/piece/Notes.md"
        render("See [$label](<docs/Notes.md>).")
        val text = compose.onNodeWithText("See $label.").fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("pill right ${pill.right} past text right ${text.right}", pill.right <= text.right + 1f)
    }

    @Test fun folderPillGoesToTheFolderOpener() {
        render("Everything lives in [src](<app/src/>).")
        compose.onNodeWithText("src", useUnmergedTree = true).performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(listOf("app/src/"), folders)
            assertEquals(emptyList<String>(), files)
        }
    }
}
