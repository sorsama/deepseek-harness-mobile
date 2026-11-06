package com.labteto.dshmobile.ui.screens.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickTest {

    @Test
    fun `tapping the model already in use sends nothing, so its effort is not reset`() {
        assertFalse(modelPickAllowed(isCurrent = true, busy = false))
    }

    @Test
    fun `a different model can be picked when nothing is in flight`() {
        assertTrue(modelPickAllowed(isCurrent = false, busy = false))
    }

    @Test
    fun `nothing can be picked while a switch is in flight`() {
        assertFalse(modelPickAllowed(isCurrent = false, busy = true))
        assertFalse(modelPickAllowed(isCurrent = true, busy = true))
    }

    @Test
    fun `a different effort tier is sent, the tier already in force is not`() {
        assertTrue(effortPickAllowed(picked = "max", inForce = "high", busy = false))
        assertFalse(effortPickAllowed(picked = "high", inForce = "high", busy = false))
    }

    @Test
    fun `an effort can be chosen when none is recorded yet`() {
        assertTrue(effortPickAllowed(picked = "high", inForce = null, busy = false))
    }

    @Test
    fun `no effort is sent mid-switch`() {
        assertFalse(effortPickAllowed(picked = "max", inForce = "high", busy = true))
    }
}
