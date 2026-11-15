package com.labteto.dshmobile.ui.screens.connect

import com.labteto.dshmobile.connection.ConnectMode
import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.testing.assertEquals
import kotlin.test.Test

/**
 * The card offers one action per state, so the mapping decides which button someone sees: a
 * 401 must become "sign in", never "start", and a refused loopback connect must become "start",
 * never a firewall diagnosis.
 */
class LoopbackStatusTest {

    @Test
    fun `a harness that answered is ready with whatever it said about itself`() {
        val home = HostDescription(home = "/data/data/com.termux/files/home")
        assertEquals(LoopbackStatus.Ready(home), ProbeOutcome.Reachable(home).toLoopbackStatus())
        assertEquals(LoopbackStatus.Ready(null), ProbeOutcome.Reachable(null).toLoopbackStatus())
    }

    @Test
    fun `a 401 means up but not signed in`() {
        assertEquals(LoopbackStatus.NeedsSignIn, ProbeOutcome.Unauthenticated.toLoopbackStatus())
    }

    /** On loopback nothing drops packets, so a timeout and a refusal are the same "down". */
    @Test
    fun `nothing answering is down whichever way it did not answer`() {
        for (outcome in listOf(ProbeOutcome.Refused, ProbeOutcome.Timeout, ProbeOutcome.Unreachable, ProbeOutcome.DnsFailure)) {
            assertEquals(LoopbackStatus.Down(outcome), outcome.toLoopbackStatus())
        }
    }

    @Test
    fun `something else on the port is foreign`() {
        for (outcome in listOf(
            ProbeOutcome.NotAHarness,
            ProbeOutcome.TlsFailure,
            ProbeOutcome.TrustFence,
            ProbeOutcome.PairingRequired,
            ProbeOutcome.CertificateChanged,
            ProbeOutcome.Other("socket closed"),
        )) {
            assertEquals(LoopbackStatus.Foreign(outcome), outcome.toLoopbackStatus())
        }
    }
}

class FirstRunConnectModeTest {
    @Test
    fun aFreshPhoneThatCanRunTheBuiltInHarnessOpensOnThisPhone() {
        kotlin.test.assertEquals(ConnectMode.LOOPBACK, firstRunConnectMode(builtInSupported = true, termuxInstalled = false, rememberedHosts = 0))
    }

    @Test
    fun termuxAloneStillOpensOnThisPhoneAsBefore() {
        kotlin.test.assertEquals(ConnectMode.LOOPBACK, firstRunConnectMode(builtInSupported = false, termuxInstalled = true, rememberedHosts = 0))
    }

    @Test
    fun aRememberedHarnessOrNoWayToRunOneHereOpensOnLocalNetwork() {
        kotlin.test.assertEquals(ConnectMode.LAN, firstRunConnectMode(builtInSupported = true, termuxInstalled = true, rememberedHosts = 1))
        kotlin.test.assertEquals(ConnectMode.LAN, firstRunConnectMode(builtInSupported = false, termuxInstalled = false, rememberedHosts = 0))
    }
}
