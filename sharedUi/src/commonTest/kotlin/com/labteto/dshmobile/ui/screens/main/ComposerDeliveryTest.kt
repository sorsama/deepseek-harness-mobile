package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.connection.DELIVERY_QUEUE
import com.labteto.dshmobile.connection.DELIVERY_STEER
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

    @Test
    fun `default mode updates a composer that has no manual choice`() {
        val composer = ComposerDraft(ComposerKey("host", "session"))

        composer.applyDefaultMode(DELIVERY_STEER)

        assertEquals(DELIVERY_STEER, composer.mode)
    }

    @Test
    fun `manual mode choice is not replaced by a later default`() {
        val composer = ComposerDraft(ComposerKey("host", "session"))
        composer.selectMode(DELIVERY_QUEUE)

        composer.applyDefaultMode(DELIVERY_STEER)

        assertEquals(DELIVERY_QUEUE, composer.mode)
    }

    @Test
    fun `unknown default mode falls back to queue`() {
        val composer = ComposerDraft(ComposerKey("host", "session"))

        composer.applyDefaultMode("unknown")

        assertEquals(DELIVERY_QUEUE, composer.mode)
    }
}
