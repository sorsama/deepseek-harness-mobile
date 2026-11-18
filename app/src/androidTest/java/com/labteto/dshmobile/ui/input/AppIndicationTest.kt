package com.labteto.dshmobile.ui.input

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemeCustomization
import com.labteto.dshmobile.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Every plain `clickable` in the app shows that it is pressed.
 *
 * For a long time none did: the app's indication wrapped Material's ripple in a node that drew the
 * keyboard focus ring, and a node that draws is drawn alone, so the ripple inside it never was. Only
 * Material's `Surface(onClick)` controls, which bring their own ripple, answered a press. These hold a
 * finger on a target dressed with the app's indication, as `AppRoot` installs it, and sample a pixel.
 *
 * Runs on API 35 or older (the Compose test rule needs Espresso, which cannot run on Android 16+).
 */
class AppIndicationTest {
    @get:Rule val compose = createComposeRule()

    private fun pixel(tag: String): Int {
        val image = compose.onNodeWithTag(tag).captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels, 0, 0, image.width, image.height)
        return pixels[(image.height / 2) * image.width + image.width - 12]
    }

    private fun hold(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput { down(center) }
        compose.waitForIdle()
    }

    private fun release(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput { up() }
        compose.waitForIdle()
    }

    private fun show(dark: Boolean) {
        compose.setContent {
            DshTheme(
                customization = ThemeCustomization.Default.copy(preference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT),
                indication = { _, ring -> rememberAppIndication(ring) },
            ) {
                val colors = DsTheme.colors
                Column(Modifier.background(colors.bgBase)) {
                    Box(Modifier.testTag("row").size(240.dp, 56.dp).background(colors.bgLayer1).clickable { })
                    Box(Modifier.testTag("switch").size(240.dp, 56.dp).background(colors.bgLayer1).toggleable(false) { })
                    Box(Modifier.testTag("silent").size(240.dp, 56.dp).background(colors.bgLayer1).clickable(interactionSource = null, indication = null) { })
                    DsCard(Modifier.width(240.dp).testTag("card"), onClick = { }) { Box(Modifier.size(24.dp)) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertPressShows(tag: String, dark: Boolean): Int {
        val rest = pixel(tag)
        hold(tag)
        val pressed = pixel(tag)
        assertNotEquals("holding $tag changes it", rest, pressed)
        val restLight = Color(rest).luminance()
        val pressedLight = Color(pressed).luminance()
        assertTrue(
            "the pressed tone moves $tag away from the page (rest $restLight, pressed $pressedLight)",
            if (dark) pressedLight > restLight else pressedLight < restLight,
        )
        release(tag)
        assertEquals("lifting from $tag gives it back", rest, pixel(tag))
        return pressed
    }

    private fun assertEveryKindShowsAPress(dark: Boolean) {
        show(dark)
        val row = assertPressShows("row", dark)
        assertPressShows("switch", dark)
        // A card draws its own fill and no indication: pressed, it is the same tone as a pressed row
        // on the same surface, not that tone twice.
        val card = assertPressShows("card", dark)
        // Within rounding: the card blends the tone in floats, the row on the canvas.
        val apart = listOf(16, 8, 0).maxOf { shift -> kotlin.math.abs((row shr shift and 0xFF) - (card shr shift and 0xFF)) }
        assertTrue("a pressed card and a pressed row are the same tone (row ${Integer.toHexString(row)}, card ${Integer.toHexString(card)})", apart <= 3)
    }

    @Test fun aClickableAndAToggleShowAPressInTheDarkTheme() = assertEveryKindShowsAPress(dark = true)

    @Test fun aClickableAndAToggleShowAPressInTheLightTheme() = assertEveryKindShowsAPress(dark = false)

    @Test fun aTargetWithoutAnIndicationStillShowsNothing() {
        show(dark = true)
        val rest = pixel("silent")
        hold("silent")
        assertEquals(rest, pixel("silent"))
        release("silent")
    }
}
