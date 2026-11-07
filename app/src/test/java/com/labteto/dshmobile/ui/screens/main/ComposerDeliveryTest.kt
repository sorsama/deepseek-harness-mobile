package com.labteto.dshmobile.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Test

/** The rule that keeps a Steer chosen with nothing running from being refused by the host. */
class ComposerDeliveryTest {

    @Test
    fun `steer with a running turn is asked for as steer`() {
        assertEquals("steer", promptDelivery("steer", running = true))
    }

    @Test
    fun `steer with no turn running becomes a queued prompt`() {
        assertEquals("queue", promptDelivery("steer", running = false))
    }

    @Test
    fun `queue stays queue whether or not a turn runs`() {
        assertEquals("queue", promptDelivery("queue", running = true))
        assertEquals("queue", promptDelivery("queue", running = false))
    }

    @Test
    fun `an unknown delivery is left for the store to normalise`() {
        assertEquals("context", promptDelivery("context", running = false))
    }
}
