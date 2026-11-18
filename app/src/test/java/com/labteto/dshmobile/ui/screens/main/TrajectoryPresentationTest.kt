package com.labteto.dshmobile.ui.screens.main

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.labteto.dshmobile.core.session.TimeKind
import com.labteto.dshmobile.ui.theme.DsThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timing bar's rules that a screenshot cannot hold: neighbouring slices differ in lightness and
 * not just hue, and the legend's percentages are whole numbers that never show a real slice as 0%.
 */
class TrajectoryPresentationTest {

    /** WCAG relative-luminance contrast ratio of two colours, 1.0 (identical) up to 21. */
    private fun contrast(a: Color, b: Color): Double {
        val high = maxOf(a.luminance(), b.luminance()).toDouble()
        val low = minOf(a.luminance(), b.luminance()).toDouble()
        return (high + 0.05) / (low + 0.05)
    }

    @Test
    fun neighbouringSlicesDifferInLightnessInTheLightAndTheDarkScheme() {
        val schemes = listOf("light" to DsThemeTokens.light, "dark" to DsThemeTokens.dark)
        for ((name, colors) in schemes) {
            TimeKind.entries.zipWithNext().forEach { (a, b) ->
                val ratio = contrast(timingFill(a, colors), timingFill(b, colors))
                assertTrue("$name: $a next to $b only differs by $ratio:1", ratio >= MIN_NEIGHBOUR_CONTRAST)
            }
        }
    }

    @Test
    fun everyKindHasItsOwnFillInEachScheme() {
        for (colors in listOf(DsThemeTokens.light, DsThemeTokens.dark)) {
            val fills = TimeKind.entries.map { timingFill(it, colors) }
            assertEquals(fills.size, fills.toSet().size)
        }
    }

    @Test
    fun aShareIsAWholePercentRoundedHalfUp() {
        assertEquals(50, timingPercent(1, 2))
        assertEquals(33, timingPercent(1, 3))
        assertEquals(67, timingPercent(2, 3))
        assertEquals(100, timingPercent(7, 7))
    }

    @Test
    fun aSliceThatHappenedNeverReadsAsZeroPercent() {
        assertEquals(1, timingPercent(3, 10_000))
    }

    @Test
    fun noTimeAtAllIsZeroPercentWithoutDividingByZero() {
        assertEquals(0, timingPercent(0, 100))
        assertEquals(0, timingPercent(5, 0))
    }

    @Test
    fun theShellToolsAreNamedByTheirProductNamesNotTheWireNames() {
        assertEquals("PowerShell", shellToolTitle("pwsh"))
        assertEquals("PowerShell", shellToolTitle("PWSH"))
        assertEquals("Bash", shellToolTitle("bash"))
    }

    @Test
    fun otherToolsKeepTheirOwnTitle() {
        assertEquals(null, shellToolTitle("read"))
        assertEquals(null, shellToolTitle("web_fetch"))
        assertEquals(null, shellToolTitle(""))
    }

    private companion object {
        /**
         * Not WCAG's 3:1 for graphics, which a bar whose legend carries every number does not need; just
         * not hue alone. The closest pair today is 1.6:1 (dark scheme, approval next to tools).
         */
        const val MIN_NEIGHBOUR_CONTRAST = 1.5
    }
}
