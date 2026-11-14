package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.wire.dto.ContextPressureView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's action row shows the context meter only when the bar has something to draw.
 *
 * The bar is 44dp wide and 6dp tall. A session that has used a fraction of one percent of its window
 * still reports a pressure, so the bar used to be drawn as an empty grey pill between the permission
 * chip and send, and it took 52dp off the chip's label as it did.
 */
class ComposerContextMeterTest {
    private fun pressure(used: Long, window: Long = 1_000_000) =
        ContextPressureView(projectedTokens = used, contextWindow = window)

    @Test fun noReadingDrawsNoMeter() {
        assertFalse(showsContextMeter(null))
        // The host sends `{}` before the first request, and a window without a count is no reading.
        assertFalse(showsContextMeter(ContextPressureView()))
        assertFalse(showsContextMeter(ContextPressureView(contextWindow = 1_000_000)))
    }

    @Test fun anAlmostEmptyWindowDrawsNoMeter() {
        // 3.5k of 1M is the demo session's "10 turns" reading: a fill 0.15dp wide.
        assertFalse(showsContextMeter(pressure(3_500)))
        assertFalse(showsContextMeter(pressure(0)))
    }

    @Test fun theMeterAppearsOnceItsFillIsWiderThanTheBarIsTall() {
        // Just under and just over 6/44, the point where the fill is as wide as the bar is tall.
        assertFalse(showsContextMeter(pressure(136_000)))
        assertTrue(showsContextMeter(pressure(137_000)))
        assertEquals(6f / 44f, ContextMeterMinRatio, 0f)
    }

    @Test fun aFillingWindowAlwaysDrawsTheMeter() {
        assertTrue(showsContextMeter(pressure(500_000)))
        assertTrue(showsContextMeter(pressure(1_000_000)))
        // A projection past the window is clamped by the DTO, so it is still a full bar.
        assertTrue(showsContextMeter(pressure(2_000_000)))
    }
}
