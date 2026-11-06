package com.labteto.dshmobile.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The permission chip's pending state, and the promise that it always ends.
 *
 * The first implementation cleared it only when the `permissions` StateFlow emitted a value equal
 * to the pick. A StateFlow does not re-emit an unchanged value, so picking the preset already in
 * force — or any outcome where the harness reported something else, or nothing — left the chip
 * disabled under a skeleton for good. Each test below is one way that used to happen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionSwitchTest {

    private val ok = CommandOutcome.Ok(null)

    private fun TestScope.tracker() = PermissionSwitch(backgroundScope, settleMs = 1_000, inFlightMs = 5_000)

    @Test
    fun `picking the preset already in force sends nothing and never goes pending`() = runTest {
        val switch = tracker()
        var sent = 0

        val result = switch.switchTo("workspace-write", current = "workspace-write") { sent++; ok }

        assertEquals(PermissionSwitchResult.AlreadyActive, result)
        assertEquals(0, sent)
        assertNull(switch.pending.value)
    }

    @Test
    fun `the pick shows while the command is in flight and the projection confirming it ends it`() = runTest {
        val switch = tracker()
        val gate = CompletableDeferred<CommandOutcome>()
        val result = async { switch.switchTo("read-only", current = "workspace-write") { gate.await() } }
        runCurrent()

        assertEquals("read-only", switch.pending.value)

        // The frame can beat the command's own answer; it is the truth either way.
        switch.observe("read-only")
        assertEquals(PermissionSwitchResult.Applied("read-only"), result.await())
        assertNull(switch.pending.value)
    }

    @Test
    fun `a projection that agrees after the command is acknowledged ends it`() = runTest {
        val switch = tracker()
        val result = async { switch.switchTo("read-only", current = "workspace-write") { ok } }
        runCurrent()
        assertEquals("read-only", switch.pending.value)

        switch.observe("read-only")

        assertEquals(PermissionSwitchResult.Applied("read-only"), result.await())
        assertNull(switch.pending.value)
    }

    @Test
    fun `a projection with a different value after the acknowledgement ends it and reports the real one`() = runTest {
        val switch = tracker()
        val result = async { switch.switchTo("auto", current = "workspace-write") { ok } }
        runCurrent()

        // The harness normalised the id, or the combination now matches no preset.
        switch.observe("custom")

        assertEquals(PermissionSwitchResult.Differs(requested = "auto", actual = "custom"), result.await())
        assertNull(switch.pending.value)
    }

    @Test
    fun `a projection that moved before the acknowledgement settles on it once acknowledged`() = runTest {
        val switch = tracker()
        val gate = CompletableDeferred<CommandOutcome>()
        val result = async { switch.switchTo("auto", current = "workspace-write") { gate.await() } }
        runCurrent()

        switch.observe("custom")
        assertEquals("a verdict waits for the command to be answered", "auto", switch.pending.value)

        gate.complete(ok)
        assertEquals(PermissionSwitchResult.Differs("auto", "custom"), result.await())
        assertNull(switch.pending.value)
    }

    @Test
    fun `an unchanged projection is not a verdict`() = runTest {
        val switch = tracker()
        val result = async { switch.switchTo("read-only", current = "workspace-write") { ok } }
        runCurrent()

        // The catalog refreshing re-emits the projection with the value it already had.
        switch.observe("workspace-write")
        switch.observe(null)

        assertEquals("read-only", switch.pending.value)
        advanceTimeBy(1_001)
        assertEquals(PermissionSwitchResult.Unconfirmed("read-only"), result.await())
    }

    @Test
    fun `a projection that never arrives ends at the settle timeout`() = runTest {
        val switch = tracker()
        val result = async { switch.switchTo("read-only", current = "workspace-write") { ok } }
        runCurrent()
        assertEquals("read-only", switch.pending.value)

        advanceTimeBy(999)
        assertEquals("still waiting inside the window", "read-only", switch.pending.value)

        advanceTimeBy(2)
        assertEquals(PermissionSwitchResult.Unconfirmed("read-only"), result.await())
        assertNull(switch.pending.value)
    }

    @Test
    fun `a command that never answers ends at the in-flight limit`() = runTest {
        val switch = tracker()
        val result = async { switch.switchTo("read-only", current = "workspace-write") { awaitCancellation() } }
        runCurrent()
        assertEquals("read-only", switch.pending.value)

        advanceTimeBy(5_001)

        assertEquals(PermissionSwitchResult.Unconfirmed("read-only"), result.await())
        assertNull(switch.pending.value)
    }

    @Test
    fun `an answer that arrives after the in-flight limit changes nothing`() = runTest {
        val switch = tracker()
        val gate = CompletableDeferred<CommandOutcome>()
        val result = async { switch.switchTo("read-only", current = "workspace-write") { gate.await() } }
        runCurrent()
        advanceTimeBy(5_001)
        assertEquals(PermissionSwitchResult.Unconfirmed("read-only"), result.await())

        gate.complete(CommandOutcome.Failed("late"))
        runCurrent()

        assertNull(switch.pending.value)
    }

    @Test
    fun `a refused command ends it as a failure`() = runTest {
        val switch = tracker()
        val refusal = CommandOutcome.Failed("not allowed")

        val result = async { switch.switchTo("read-only", current = "workspace-write") { refusal } }
        runCurrent()

        assertEquals(PermissionSwitchResult.Failed(refusal), result.await())
        assertNull(switch.pending.value)
    }

    @Test
    fun `a host with no such command ends it as a failure`() = runTest {
        val switch = tracker()
        val unknown = CommandOutcome.Unknown("/permission read-only")

        val result = async { switch.switchTo("read-only", current = "workspace-write") { unknown } }
        runCurrent()

        assertEquals(PermissionSwitchResult.Failed(unknown), result.await())
        assertNull(switch.pending.value)
    }

    @Test
    fun `a command that throws ends it as a failure`() = runTest {
        val switch = tracker()

        val result = async {
            switch.switchTo("read-only", current = "workspace-write") { error("socket closed") }
        }
        runCurrent()

        assertEquals(PermissionSwitchResult.Failed(CommandOutcome.Failed("socket closed")), result.await())
        assertNull(switch.pending.value)
    }

    @Test
    fun `a caller that stops waiting does not strand the pending state`() = runTest {
        val switch = tracker()
        val caller = launch { switch.switchTo("read-only", current = "workspace-write") { ok } }
        runCurrent()
        assertEquals("read-only", switch.pending.value)

        // The screen left composition mid-switch. The command was not the caller's to cancel.
        caller.cancel()
        runCurrent()
        assertEquals("the switch is still in flight", "read-only", switch.pending.value)

        advanceTimeBy(1_001)
        runCurrent()
        assertNull(switch.pending.value)
    }

    @Test
    fun `a session change or disconnect abandons it`() = runTest {
        val switch = tracker()
        val gate = CompletableDeferred<CommandOutcome>()
        val result = async { switch.switchTo("read-only", current = "workspace-write") { gate.await() } }
        runCurrent()

        switch.reset()

        assertEquals(PermissionSwitchResult.Abandoned, result.await())
        assertNull(switch.pending.value)

        // The old session's answer, and a later frame, must not touch the next session's chip.
        gate.complete(ok)
        runCurrent()
        switch.observe("read-only")
        assertNull(switch.pending.value)
    }

    @Test
    fun `a newer pick replaces the one in flight`() = runTest {
        val switch = tracker()
        val first = async { switch.switchTo("read-only", current = "workspace-write") { ok } }
        runCurrent()

        val second = async { switch.switchTo("auto", current = "workspace-write") { ok } }
        runCurrent()

        assertEquals(PermissionSwitchResult.Abandoned, first.await())
        assertEquals("auto", switch.pending.value)

        switch.observe("auto")
        assertEquals(PermissionSwitchResult.Applied("auto"), second.await())
        assertNull(switch.pending.value)
    }

    @Test
    fun `going back to the value the projection still shows is a real request while another is in flight`() = runTest {
        val switch = tracker()
        val first = async { switch.switchTo("read-only", current = "workspace-write") { ok } }
        runCurrent()

        var sent = 0
        val back = async { switch.switchTo("workspace-write", current = "workspace-write") { sent++; ok } }
        runCurrent()

        assertEquals(1, sent)
        assertEquals(PermissionSwitchResult.Abandoned, first.await())
        assertEquals("workspace-write", switch.pending.value)

        advanceTimeBy(1_001)
        assertEquals(PermissionSwitchResult.Unconfirmed("workspace-write"), back.await())
    }

    @Test
    fun `after one switch has ended the next one starts clean`() = runTest {
        val switch = tracker()
        val first = async { switch.switchTo("read-only", current = "workspace-write") { ok } }
        runCurrent()
        switch.observe("read-only")
        first.await()

        val second = async { switch.switchTo("workspace-write", current = "read-only") { ok } }
        runCurrent()
        assertEquals("workspace-write", switch.pending.value)
        switch.observe("workspace-write")

        assertEquals(PermissionSwitchResult.Applied("workspace-write"), second.await())
        assertNull(switch.pending.value)
    }
}
