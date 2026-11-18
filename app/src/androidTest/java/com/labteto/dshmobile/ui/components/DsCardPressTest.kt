package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemeCustomization
import com.labteto.dshmobile.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A card that can be pressed shows it, as a change of its own fill.
 *
 * Reported twice from a device: holding a bundle card on the Plugins page changed no pixel, while a
 * button, a chip and an icon button on the same page did. The app's click indication then drew no
 * press at all (it has since been fixed, see `FocusRing.kt`), and a card made of several targets
 * cannot lean on a per-target indication anyway, so [DsCard] reads the press itself. These hold a finger down on a
 * target and sample a pixel of the card that is under no target, in both themes: it has to change
 * while the finger is down and come back when it lifts, for every target that shares the card's
 * source, and it must not change for a card nothing in it can press.
 *
 * Not run in the change that added it (no emulator was available then); it compiles with the
 * instrumented tests and needs a device or emulator to run.
 */
@OptIn(ExperimentalTestApi::class)
class DsCardPressTest {
    @get:Rule val compose = createComposeRule()

    /** A pixel in the card's right-hand margin: inside the card, under none of the targets. */
    private fun marginPixel(): Int {
        val image = compose.onNodeWithTag("card").captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels, 0, 0, image.width, image.height)
        return pixels[(image.height / 2) * image.width + (image.width - 24)]
    }

    private fun hold(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput { down(center) }
        compose.waitForIdle()
    }

    private fun release(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput { up() }
        compose.waitForIdle()
    }

    private fun showTwoTargets(dark: Boolean) {
        val source = MutableInteractionSource()
        compose.setContent {
            DshTheme(customization = ThemeCustomization.Default.copy(preference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT)) {
                DsCard(Modifier.width(320.dp).testTag("card"), contentPadding = PaddingValues(0.dp), pressSource = source) {
                    Row {
                        Box(Modifier.testTag("first").size(120.dp, 64.dp).clickable(interactionSource = source, indication = null) { })
                        Box(Modifier.testTag("second").size(48.dp, 64.dp).clickable(interactionSource = source, indication = null) { })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertEveryTargetLightsTheCard(dark: Boolean) {
        showTwoTargets(dark)
        val rest = marginPixel()
        listOf("first", "second").forEach { target ->
            hold(target)
            val pressed = marginPixel()
            assertNotEquals("holding $target changes the card's fill", rest, pressed)
            val restLight = Color(rest).luminance()
            val pressedLight = Color(pressed).luminance()
            assertTrue(
                "the pressed tone moves the fill away from the page, not the other way (rest $restLight, pressed $pressedLight)",
                if (dark) pressedLight > restLight else pressedLight < restLight,
            )
            release(target)
            assertEquals("lifting from $target gives the fill back", rest, marginPixel())
        }
    }

    @Test fun holdingEitherTargetFillsTheWholeCardInTheDarkTheme() = assertEveryTargetLightsTheCard(dark = true)

    @Test fun holdingEitherTargetFillsTheWholeCardInTheLightTheme() = assertEveryTargetLightsTheCard(dark = false)

    @Test fun aCardWithItsOwnTapFillsWhileItIsHeld() {
        compose.setContent {
            DshTheme(customization = ThemeCustomization.Default.copy(preference = ThemePreference.DARK)) {
                DsCard(Modifier.width(320.dp).testTag("card"), onClick = { }) {
                    Box(Modifier.size(1.dp))
                }
            }
        }
        compose.waitForIdle()
        val rest = marginPixel()
        hold("card")
        assertNotEquals(rest, marginPixel())
        release("card")
        assertEquals(rest, marginPixel())
    }

    @Test fun aCardNothingInItCanPressNeverChanges() {
        compose.setContent {
            DshTheme(customization = ThemeCustomization.Default.copy(preference = ThemePreference.DARK)) {
                DsCard(Modifier.width(320.dp).testTag("card")) {
                    Box(Modifier.size(1.dp))
                }
            }
        }
        compose.waitForIdle()
        val rest = marginPixel()
        hold("card")
        assertEquals(rest, marginPixel())
        release("card")
    }
}
