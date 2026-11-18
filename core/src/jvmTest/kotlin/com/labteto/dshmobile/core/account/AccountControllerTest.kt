package com.labteto.dshmobile.core.account

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.AccountBonusBatch
import com.labteto.dshmobile.core.wire.dto.AccountLinks
import com.labteto.dshmobile.core.wire.dto.AccountProfileOutcome
import com.labteto.dshmobile.core.wire.dto.AccountView
import com.labteto.dshmobile.core.wire.dto.AccountWallet
import com.labteto.dshmobile.core.wire.dto.SignInAttempt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The controller against a fake port that records every call it is handed. The answers it replays are
 * the ones a real harness gave (see [AccountFixtures]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountControllerTest {

    private class FakePort : AccountPort {
        val calls = mutableListOf<String>()
        val starts = mutableListOf<String>()
        val cancels = mutableListOf<String>()
        val acks = mutableListOf<Pair<String, String>>()

        var onState: suspend () -> RpcResult<AccountView> = { ok(AccountFixtures.STATE_SIGNED_OUT) }
        var onProfile: suspend () -> RpcResult<AccountProfileOutcome?> = { RpcResult.Ok(profileOf(AccountFixtures.PROFILE_READY)) }
        var onBalance: suspend () -> RpcResult<AccountWallet?> = { RpcResult.Ok(balanceOf(AccountFixtures.BALANCE_READY)) }
        var onBonuses: suspend () -> RpcResult<AccountBonusBatch?> = { RpcResult.Ok(bonusesOf(AccountFixtures.BONUSES_NONE)) }
        var onAck: suspend (String, String) -> RpcResult<Boolean> = { _, _ -> RpcResult.Ok(true) }
        var onStart: suspend (String) -> RpcResult<AccountView> = { ok(AccountFixtures.STATE_STARTING) }
        var onCancel: suspend (String) -> RpcResult<AccountView> = { ok(AccountFixtures.STATE_CANCELLED) }
        var onSignOut: suspend () -> RpcResult<AccountView> = { ok(AccountFixtures.STATE_AFTER_SIGN_OUT) }
        var onRunning: suspend () -> RpcResult<Boolean> = { RpcResult.Ok(false) }

        override suspend fun state(): RpcResult<AccountView> { calls += "state"; return onState() }
        override suspend fun profile(): RpcResult<AccountProfileOutcome?> { calls += "profile"; return onProfile() }
        override suspend fun balance(): RpcResult<AccountWallet?> { calls += "balance"; return onBalance() }
        override suspend fun bonuses(): RpcResult<AccountBonusBatch?> { calls += "bonuses"; return onBonuses() }
        override suspend fun ackBonus(accountId: String, orderId: String): RpcResult<Boolean> {
            acks += accountId to orderId
            return onAck(accountId, orderId)
        }
        override suspend fun startSignIn(callbackOrigin: String): RpcResult<AccountView> { starts += callbackOrigin; return onStart(callbackOrigin) }
        override suspend fun cancelSignIn(attemptId: String): RpcResult<AccountView> { cancels += attemptId; return onCancel(attemptId) }
        override suspend fun signOut(): RpcResult<AccountView> { calls += "signOut"; return onSignOut() }
        override suspend fun hasRunningTasks(): RpcResult<Boolean> = onRunning()
    }

    private class Harness(val port: FakePort, val controller: AccountController, val changes: () -> Int)

    private fun TestScope.harness(port: FakePort = FakePort()): Harness {
        var changes = 0
        return Harness(port, AccountController(port, this) { changes++ }, { changes })
    }

    private fun TestScope.loaded(port: FakePort): Harness = harness(port).also {
        it.controller.load()
        advanceUntilIdle()
    }

    @Test
    fun `a signed out harness is ready and nothing is read for an account`() = runTest {
        val h = loaded(FakePort())
        val s = h.controller.state.value
        assertEquals(AccountPhase.Ready, s.phase)
        assertFalse(s.snapshot!!.signedIn)
        assertEquals(listOf("state"), h.port.calls)
        assertEquals(0, h.changes())
    }

    @Test
    fun `a signed in harness reads the profile the balance and the notices`() = runTest {
        val port = FakePort().apply { onState = { ok(AccountFixtures.STATE_SIGNED_IN) } }
        val s = loaded(port).controller.state.value
        assertTrue(s.snapshot!!.signedIn)
        assertEquals("Mock Account", (s.profile as Loadable.Ready).value.name)
        assertEquals("138****5678", (s.profile as Loadable.Ready).value.contact)
        val balance = (s.balance as Loadable.Ready).value
        assertEquals(listOf("CNY", "USD"), balance.value.map { it.currency })
        assertEquals(listOf("10.00"), balance.bonusWallets.map { it.balance })
        assertTrue(s.bonuses.isEmpty())
    }

    @Test
    fun `opening the page on a signed in harness is not a change for the usage card`() = runTest {
        val port = FakePort().apply { onState = { ok(AccountFixtures.STATE_SIGNED_IN) } }
        assertEquals(0, loaded(port).changes())
    }

    @Test
    fun `a notice shows the words of the server and is acknowledged only when closed`() = runTest {
        val port = FakePort().apply {
            onState = { ok(AccountFixtures.STATE_SIGNED_IN) }
            onBonuses = { RpcResult.Ok(bonusesOf(AccountFixtures.BONUSES_TWO)) }
        }
        val h = loaded(port)
        val shown = h.controller.state.value.bonuses
        assertEquals(listOf("order-1", "order-2"), shown.map { it.orderId })
        assertEquals("Welcome bonus", shown[0].title)
        assertNull(shown[1].title)
        assertEquals("", shown[1].expiresAt)
        assertTrue(port.acks.isEmpty())

        h.controller.dismissBonus("order-1")
        advanceUntilIdle()
        assertEquals(listOf("order-2"), h.controller.state.value.bonuses.map { it.orderId })
        assertEquals(listOf("mock-user-1" to "order-1"), port.acks)
    }

    @Test
    fun `a notice the harness would not acknowledge is not brought back or reported`() = runTest {
        val port = FakePort().apply {
            onState = { ok(AccountFixtures.STATE_SIGNED_IN) }
            onBonuses = { RpcResult.Ok(bonusesOf(AccountFixtures.BONUSES_TWO)) }
            onAck = { _, _ -> RpcResult.Err(RpcError("internal", "down")) }
        }
        val h = loaded(port)
        h.controller.dismissBonus("order-2")
        advanceUntilIdle()
        assertEquals(listOf("order-1"), h.controller.state.value.bonuses.map { it.orderId })
        assertNull(h.controller.state.value.actionFailure)
    }

    @Test
    fun `a profile that could not be read does not hide the balance`() = runTest {
        val port = FakePort().apply {
            onState = { ok(AccountFixtures.STATE_SIGNED_IN) }
            onProfile = { RpcResult.Ok(profileOf("""{"ok":true,"value":{"status":"failed"}}""")) }
        }
        val s = loaded(port).controller.state.value
        assertEquals(Loadable.Failed, s.profile)
        assertIs<Loadable.Ready<*>>(s.balance)
    }

    @Test
    fun `a balance Platform answered badly is failed and not zero`() = runTest {
        val port = FakePort().apply {
            onState = { ok(AccountFixtures.STATE_SIGNED_IN) }
            onBalance = { RpcResult.Ok(balanceOf(AccountFixtures.BALANCE_FAILED)) }
        }
        val s = loaded(port).controller.state.value
        assertEquals(Loadable.Failed, s.balance)
        assertIs<Loadable.Ready<*>>(s.profile)
    }

    @Test
    fun `a harness whose account plugin is off is unavailable`() = runTest {
        val port = FakePort().apply { onState = { RpcResult.Err(accountRefusal(AccountFixtures.SERVICE_UNAVAILABLE)) } }
        val s = loaded(port).controller.state.value
        assertEquals(AccountPhase.Unavailable, s.phase)
    }

    @Test
    fun `the first read failing shows the failure and a later one keeps what was read`() = runTest {
        val port = FakePort().apply { onState = { RpcResult.Err(RpcError("internal", "socket closed")) } }
        val h = loaded(port)
        assertEquals(AccountPhase.Failed(AccountFailure.Unreachable("socket closed")), h.controller.state.value.phase)

        port.onState = { ok(AccountFixtures.STATE_SIGNED_OUT) }
        h.controller.load()
        advanceUntilIdle()
        assertEquals(AccountPhase.Ready, h.controller.state.value.phase)

        port.onState = { RpcResult.Err(RpcError("internal", "socket closed")) }
        h.controller.refresh()
        advanceUntilIdle()
        val kept = h.controller.state.value
        assertEquals(AccountPhase.Ready, kept.phase)
        assertTrue(kept.refreshFailed)
        assertFalse(kept.snapshot!!.signedIn)

        port.onState = { ok(AccountFixtures.STATE_SIGNED_OUT) }
        h.controller.refresh()
        advanceUntilIdle()
        assertFalse(h.controller.state.value.refreshFailed)
    }

    @Test
    fun `a port that throws is a refusal and not a crash`() = runTest {
        val port = FakePort().apply { onState = { throw IllegalStateException("boom") } }
        val s = loaded(port).controller.state.value
        assertEquals(AccountPhase.Failed(AccountFailure.Unreachable("boom")), s.phase)
    }

    // ------------------------------------------------------------------ starting, cancelling

    @Test
    fun `starting hands the harness the origin and waits to open the browser`() = runTest {
        val h = loaded(FakePort())
        h.controller.startSignIn("http://127.0.0.1:3080")
        advanceUntilIdle()
        assertEquals(listOf("http://127.0.0.1:3080"), h.port.starts)
        val started = h.controller.state.value
        val attemptId = started.snapshot!!.progress!!.attemptId
        assertEquals(SignInStep.Starting, started.snapshot!!.progress!!.step)
        assertEquals(attemptId, started.openBrowserFor)
        assertNull(started.working)

        h.port.onState = { ok(AccountFixtures.STATE_WAITING) }
        h.controller.refresh()
        advanceUntilIdle()
        val waiting = h.controller.state.value
        assertEquals(SignInStep.WaitingForBrowser, waiting.snapshot!!.progress!!.step)
        assertTrue(waiting.snapshot!!.progress!!.authorizeUrl!!.isNotEmpty())
        // The recorded waiting state is the same attempt as the recorded start, so the link is still owed to the browser.
        assertEquals(attemptId, waiting.openBrowserFor)
        h.controller.browserOpened(attemptId)
        assertNull(h.controller.state.value.openBrowserFor)
    }

    @Test
    fun `the link is opened once for the attempt this phone started`() = runTest {
        val port = FakePort().apply { onStart = { ok(AccountFixtures.STATE_WAITING) } }
        val h = loaded(port)
        h.controller.startSignIn("http://127.0.0.1:3080")
        advanceUntilIdle()
        val id = h.controller.state.value.snapshot!!.progress!!.attemptId
        assertEquals(id, h.controller.state.value.openBrowserFor)

        h.controller.browserOpened("another-attempt")
        assertEquals(id, h.controller.state.value.openBrowserFor)
        h.controller.browserOpened(id)
        assertNull(h.controller.state.value.openBrowserFor)

        // A later read of the same attempt does not ask for it again.
        port.onState = { ok(AccountFixtures.STATE_WAITING) }
        h.controller.refresh()
        advanceUntilIdle()
        assertNull(h.controller.state.value.openBrowserFor)
    }

    @Test
    fun `a sign-in started elsewhere is never opened by this phone`() = runTest {
        val port = FakePort().apply { onState = { ok(AccountFixtures.STATE_WAITING) } }
        val s = loaded(port).controller.state.value
        assertEquals(SignInStep.WaitingForBrowser, s.snapshot!!.progress!!.step)
        assertNull(s.openBrowserFor)
    }

    @Test
    fun `a start the harness refuses says why and leaves nothing running`() = runTest {
        val port = FakePort().apply { onStart = { RpcResult.Err(accountRefusal(AccountFixtures.START_REFUSED_LAN_ORIGIN)) } }
        val h = loaded(port)
        h.controller.startSignIn("http://192.168.1.20:3080")
        advanceUntilIdle()
        val s = h.controller.state.value
        assertEquals(AccountFailure.Rejected("account: protocol"), s.actionFailure)
        assertNull(s.working)
        assertNull(s.snapshot!!.progress)
        assertNull(s.openBrowserFor)
        h.controller.dismissActionFailure()
        assertNull(h.controller.state.value.actionFailure)
    }

    @Test
    fun `a failure remembers which action it belongs to so that one can be retried`() = runTest {
        val port = FakePort().apply {
            onState = { ok(AccountFixtures.STATE_SIGNED_IN) }
            onSignOut = { RpcResult.Err(RpcError("internal", "socket closed")) }
        }
        val h = loaded(port)
        h.controller.signOut()
        advanceUntilIdle()
        assertEquals(AccountWork.SigningOut, h.controller.state.value.failedWork)
        // Trying again clears the old failure first, then lands.
        port.onSignOut = { ok(AccountFixtures.STATE_AFTER_SIGN_OUT) }
        h.controller.signOut()
        advanceUntilIdle()
        val s = h.controller.state.value
        assertNull(s.failedWork)
        assertNull(s.actionFailure)
        assertFalse(s.snapshot!!.signedIn)
    }

    @Test
    fun `dismissing a failure forgets its action too`() = runTest {
        val port = FakePort().apply { onStart = { RpcResult.Err(accountRefusal(AccountFixtures.START_REFUSED_LAN_ORIGIN)) } }
        val h = loaded(port)
        h.controller.startSignIn("http://127.0.0.1:3080")
        advanceUntilIdle()
        assertEquals(AccountWork.Starting, h.controller.state.value.failedWork)
        h.controller.dismissActionFailure()
        assertNull(h.controller.state.value.failedWork)
    }

    @Test
    fun `a link the phone could not open is remembered until the attempt ends`() = runTest {
        val port = FakePort().apply { onStart = { ok(AccountFixtures.STATE_WAITING) } }
        val h = loaded(port)
        h.controller.startSignIn("http://127.0.0.1:3080")
        advanceUntilIdle()
        val id = h.controller.state.value.snapshot!!.progress!!.attemptId
        h.controller.browserOpened(id, opened = false)
        assertEquals(id, h.controller.state.value.openFailedFor)
        assertNull(h.controller.state.value.openBrowserFor)

        // A later press that does open it clears the note.
        h.controller.browserOpened(id, opened = true)
        assertNull(h.controller.state.value.openFailedFor)

        // And when the attempt ends the note goes with it.
        h.controller.browserOpened(id, opened = false)
        port.onState = { ok(AccountFixtures.STATE_CANCELLED) }
        h.controller.refresh()
        advanceUntilIdle()
        assertNull(h.controller.state.value.openFailedFor)
    }

    @Test
    fun `a start answered with the plugin off makes the page unavailable`() = runTest {
        val port = FakePort().apply { onStart = { RpcResult.Err(accountRefusal(AccountFixtures.SERVICE_UNAVAILABLE)) } }
        val h = loaded(port)
        h.controller.startSignIn("http://127.0.0.1:3080")
        advanceUntilIdle()
        assertEquals(AccountPhase.Unavailable, h.controller.state.value.phase)
    }

    @Test
    fun `only one action runs at a time`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakePort().apply { onStart = { gate.await(); ok(AccountFixtures.STATE_STARTING) } }
        val h = loaded(port)
        h.controller.startSignIn("http://127.0.0.1:3080")
        advanceUntilIdle()
        assertEquals(AccountWork.Starting, h.controller.state.value.working)
        h.controller.startSignIn("http://127.0.0.1:3080")
        h.controller.signOut()
        advanceUntilIdle()
        assertEquals(1, port.starts.size)
        assertFalse("signOut" in port.calls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(h.controller.state.value.working)
    }

    @Test
    fun `cancelling names the attempt on screen`() = runTest {
        val port = FakePort().apply { onState = { ok(AccountFixtures.STATE_WAITING) } }
        val h = loaded(port)
        val id = h.controller.state.value.snapshot!!.progress!!.attemptId
        h.controller.cancelSignIn()
        advanceUntilIdle()
        assertEquals(listOf(id), port.cancels)
        val s = h.controller.state.value
        assertNull(s.snapshot!!.progress)
        // The cancel is said on the page now, not left as a silent return to "Not signed in".
        assertEquals(SignInOutcome.Cancelled, s.snapshot!!.outcome)
        assertNull(s.working)
    }

    @Test
    fun `cancelling with nothing running asks nothing`() = runTest {
        val h = loaded(FakePort())
        h.controller.cancelSignIn()
        advanceUntilIdle()
        assertTrue(h.port.cancels.isEmpty())
    }

    @Test
    fun `a slow read cannot put a cancelled attempt back`() = runTest {
        val port = FakePort().apply { onState = { ok(AccountFixtures.STATE_WAITING) } }
        val h = loaded(port)
        val gate = CompletableDeferred<Unit>()
        port.onState = { gate.await(); ok(AccountFixtures.STATE_WAITING) }
        h.controller.refresh()
        advanceUntilIdle()
        h.controller.cancelSignIn()
        advanceUntilIdle()
        assertNull(h.controller.state.value.snapshot!!.progress)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(h.controller.state.value.snapshot!!.progress)
    }

    @Test
    fun `a cancel that fails keeps the attempt on screen and says so`() = runTest {
        val port = FakePort().apply {
            onState = { ok(AccountFixtures.STATE_WAITING) }
            onCancel = { RpcResult.Err(RpcError("internal", "socket closed")) }
        }
        val h = loaded(port)
        h.controller.cancelSignIn()
        advanceUntilIdle()
        val s = h.controller.state.value
        assertIs<AccountFailure.Unreachable>(s.actionFailure)
        assertEquals(SignInStep.WaitingForBrowser, s.snapshot!!.progress!!.step)
    }

    // ------------------------------------------------------------------ finishing, signing out

    @Test
    fun `a sign-in finished in the browser is picked up and its details are read`() = runTest {
        val port = FakePort().apply { onState = { ok(AccountFixtures.STATE_WAITING) } }
        val h = loaded(port)
        assertEquals(listOf("state"), port.calls)

        port.onState = { ok(AccountFixtures.STATE_SIGNED_IN) }
        h.controller.refresh()
        advanceUntilIdle()
        val s = h.controller.state.value
        assertTrue(s.snapshot!!.signedIn)
        assertNull(s.snapshot!!.progress)
        assertIs<Loadable.Ready<*>>(s.profile)
        assertIs<Loadable.Ready<*>>(s.balance)
        assertEquals(1, h.changes())
    }

    @Test
    fun `signing out asks the harness and drops what was read`() = runTest {
        val port = FakePort().apply {
            onState = { ok(AccountFixtures.STATE_SIGNED_IN) }
            onBonuses = { RpcResult.Ok(bonusesOf(AccountFixtures.BONUSES_TWO)) }
        }
        val h = loaded(port)
        assertEquals(2, h.controller.state.value.bonuses.size)
        h.controller.signOut()
        advanceUntilIdle()
        val s = h.controller.state.value
        assertFalse(s.snapshot!!.signedIn)
        assertTrue(s.bonuses.isEmpty())
        assertEquals(Loadable.Loading, s.profile)
        assertNull(s.working)
        assertEquals(1, h.changes())
        // A notice of the old account cannot be acknowledged after sign-out.
        h.controller.dismissBonus("order-1")
        advanceUntilIdle()
        assertTrue(port.acks.isEmpty())
    }

    @Test
    fun `a sign-out that fails leaves the sign-in and says so`() = runTest {
        val port = FakePort().apply {
            onState = { ok(AccountFixtures.STATE_SIGNED_IN) }
            onSignOut = { RpcResult.Err(RpcError("internal", "socket closed")) }
        }
        val h = loaded(port)
        h.controller.signOut()
        advanceUntilIdle()
        val s = h.controller.state.value
        assertTrue(s.snapshot!!.signedIn)
        assertIs<AccountFailure.Unreachable>(s.actionFailure)
        assertEquals(0, h.changes())
    }

    @Test
    fun `details that arrive after a sign-out are dropped`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakePort().apply {
            onState = { ok(AccountFixtures.STATE_SIGNED_IN) }
            onProfile = { gate.await(); RpcResult.Ok(profileOf(AccountFixtures.PROFILE_READY)) }
        }
        val h = loaded(port)
        assertEquals(Loadable.Loading, h.controller.state.value.profile)
        h.controller.signOut()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(Loadable.Loading, h.controller.state.value.profile)
    }

    @Test
    fun `a second sign-in over the stored one reads the details again`() = runTest {
        val port = FakePort().apply { onState = { ok(AccountFixtures.STATE_SIGNED_IN) } }
        val h = loaded(port)
        assertEquals(1, port.calls.count { it == "profile" })
        port.onState = { RpcResult.Ok(AccountView("credential-stored", AccountLinks(), SignInAttempt("a-new-one", "succeeded"))) }
        h.controller.refresh()
        advanceUntilIdle()
        assertEquals(2, port.calls.count { it == "profile" })
        // Reading the same credential again does not read the details again.
        h.controller.refresh()
        advanceUntilIdle()
        assertEquals(2, port.calls.count { it == "profile" })
    }

    @Test
    fun `the harness ending a sign-in is said until the person signs in again`() = runTest {
        val port = FakePort().apply { onState = { ok(AccountFixtures.STATE_SIGNED_IN) } }
        val h = loaded(port)
        port.onState = { ok(AccountFixtures.STATE_SIGNED_OUT) }
        h.controller.onSessionExpired()
        advanceUntilIdle()
        val ended = h.controller.state.value
        assertTrue(ended.sessionEnded)
        assertFalse(ended.snapshot!!.signedIn)
        assertEquals(1, h.changes())

        port.onState = { ok(AccountFixtures.STATE_SIGNED_IN) }
        h.controller.refresh()
        advanceUntilIdle()
        assertFalse(h.controller.state.value.sessionEnded)
    }

    @Test
    fun `starting a new sign-in clears the note that the last one ended`() = runTest {
        val h = loaded(FakePort())
        h.controller.onSessionExpired()
        advanceUntilIdle()
        assertTrue(h.controller.state.value.sessionEnded)
        h.controller.startSignIn("http://127.0.0.1:3080")
        advanceUntilIdle()
        assertFalse(h.controller.state.value.sessionEnded)
    }

    @Test
    fun `signing out says what it would stop`() = runTest {
        val port = FakePort().apply { onRunning = { RpcResult.Ok(true) } }
        val h = harness(port)
        assertEquals(SignOutImpact.TasksRunning, h.controller.signOutImpact())
        port.onRunning = { RpcResult.Ok(false) }
        assertEquals(SignOutImpact.Idle, h.controller.signOutImpact())
        port.onRunning = { RpcResult.Err(RpcError("internal", "down")) }
        assertEquals(SignOutImpact.Unknown, h.controller.signOutImpact())
        port.onRunning = { throw IllegalStateException("boom") }
        assertEquals(SignOutImpact.Unknown, h.controller.signOutImpact())
    }


    // ----------------------------------------------- how the last try ended, and a fresh try

    @Test
    fun `after a cancel the page says so and keeps saying so until the next attempt`() = runTest {
        val port = FakePort().apply { onState = { ok(AccountFixtures.STATE_WAITING) } }
        val h = loaded(port)
        h.controller.cancelSignIn()
        advanceUntilIdle()
        assertEquals(SignInOutcome.Cancelled, h.controller.state.value.snapshot!!.outcome)

        // The harness keeps the last attempt, so every later read says the same and nothing clears it on its own.
        port.onState = { ok(AccountFixtures.STATE_CANCELLED) }
        repeat(3) {
            h.controller.refresh()
            advanceUntilIdle()
        }
        assertEquals(SignInOutcome.Cancelled, h.controller.state.value.snapshot!!.outcome)

        // The next attempt takes it away.
        h.controller.startSignIn("http://127.0.0.1:3080")
        advanceUntilIdle()
        assertNull(h.controller.state.value.snapshot!!.outcome)
        assertEquals(SignInStep.Starting, h.controller.state.value.snapshot!!.progress!!.step)
    }

    @Test
    fun `a sign-in that timed out or failed while the app was away is said when the page reads it`() = runTest {
        val port = FakePort().apply { onState = { ok(AccountFixtures.STATE_EXPIRED) } }
        val timedOut = loaded(port)
        assertEquals(SignInOutcome.Expired, timedOut.controller.state.value.snapshot!!.outcome)

        val failedPort = FakePort().apply { onState = { ok(AccountFixtures.STATE_FAILED_EXCHANGE) } }
        val failed = loaded(failedPort)
        assertEquals(SignInOutcome.Failed(SignInProblem.Generic), failed.controller.state.value.snapshot!!.outcome)
    }

    /** The recorded waiting attempt with a link the app will not open: plain http to a host that is not loopback. */
    private fun refusedLinkState() = stateOf(AccountFixtures.STATE_WAITING).let {
        it.copy(attempt = it.attempt!!.copy(authorizeUrl = "http://platform.deepseek.com/dsh/authorize"))
    }

    /** The recorded start, as a different attempt from the one waiting. */
    private fun freshStartState() = stateOf(AccountFixtures.STATE_STARTING).let { it.copy(attempt = it.attempt!!.copy(id = "fresh-attempt")) }

    @Test
    fun `trying again after a refused link cancels that attempt and then starts a fresh one`() = runTest {
        val order = mutableListOf<String>()
        val port = FakePort().apply {
            onState = { RpcResult.Ok(refusedLinkState()) }
            onCancel = { order += "cancel"; ok(AccountFixtures.STATE_CANCELLED) }
            onStart = { order += "start"; RpcResult.Ok(freshStartState()) }
        }
        val h = loaded(port)
        val refused = h.controller.state.value.snapshot!!.progress!!
        assertTrue(refused.linkRefused())

        h.controller.retrySignIn("http://127.0.0.1:3080")
        advanceUntilIdle()

        // The harness answers a start with the attempt already running, so the old one goes first.
        assertEquals(listOf("cancel", "start"), order)
        assertEquals(listOf(refused.attemptId), port.cancels)
        assertEquals(listOf("http://127.0.0.1:3080"), port.starts)
        val s = h.controller.state.value
        assertEquals("fresh-attempt", s.snapshot!!.progress!!.attemptId)
        // The fresh attempt's link is opened once it is ready, as for any start from this phone.
        assertEquals("fresh-attempt", s.openBrowserFor)
        assertNull(s.working)
        assertNull(s.actionFailure)
        assertNull(s.snapshot!!.outcome)
    }

    @Test
    fun `a cancel that fails does not stop the fresh start`() = runTest {
        val port = FakePort().apply {
            onState = { RpcResult.Ok(refusedLinkState()) }
            onCancel = { RpcResult.Err(RpcError("internal", "no such attempt")) }
            onStart = { RpcResult.Ok(freshStartState()) }
        }
        val h = loaded(port)
        h.controller.retrySignIn("http://127.0.0.1:3080")
        advanceUntilIdle()
        assertEquals(1, port.starts.size)
        assertEquals("fresh-attempt", h.controller.state.value.snapshot!!.progress!!.attemptId)
        assertNull(h.controller.state.value.actionFailure)
    }

    @Test
    fun `a fresh start that fails is reported and can be tried again`() = runTest {
        val port = FakePort().apply {
            onState = { RpcResult.Ok(refusedLinkState()) }
            onStart = { RpcResult.Err(RpcError("internal", "socket closed")) }
        }
        val h = loaded(port)
        h.controller.retrySignIn("http://127.0.0.1:3080")
        advanceUntilIdle()
        val s = h.controller.state.value
        assertIs<AccountFailure.Unreachable>(s.actionFailure)
        assertEquals(AccountWork.Starting, s.failedWork)
        assertNull(s.working)
    }

    @Test
    fun `trying again with nothing running only starts`() = runTest {
        val port = FakePort().apply { onStart = { RpcResult.Ok(freshStartState()) } }
        val h = loaded(port)
        h.controller.retrySignIn("http://127.0.0.1:3080")
        advanceUntilIdle()
        assertTrue(port.cancels.isEmpty())
        assertEquals(1, port.starts.size)
    }

    @Test
    fun `trying again is one action like the others`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = FakePort().apply {
            onState = { RpcResult.Ok(refusedLinkState()) }
            onStart = { gate.await(); RpcResult.Ok(freshStartState()) }
        }
        val h = loaded(port)
        h.controller.retrySignIn("http://127.0.0.1:3080")
        h.controller.retrySignIn("http://127.0.0.1:3080")
        h.controller.startSignIn("http://127.0.0.1:3080")
        advanceUntilIdle()
        assertEquals(AccountWork.Starting, h.controller.state.value.working)
        assertEquals(1, port.cancels.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, port.starts.size)
    }

    // ------------------------------------------------- the account plugin switched off and on

    @Test
    fun `switching the account plugin on brings the page back without a relaunch`() = runTest {
        val port = FakePort().apply { onState = { RpcResult.Err(accountRefusal(AccountFixtures.SERVICE_UNAVAILABLE)) } }
        val h = loaded(port)
        assertEquals(AccountPhase.Unavailable, h.controller.state.value.phase)

        // The Plugins page switched the bundle on; the harness announces plugin-manager/changed and the screen reads again.
        port.onState = { ok(AccountFixtures.STATE_SIGNED_OUT) }
        h.controller.refresh()
        advanceUntilIdle()
        assertEquals(AccountPhase.Ready, h.controller.state.value.phase)
        assertFalse(h.controller.state.value.snapshot!!.signedIn)
    }

    @Test
    fun `switching the account plugin off takes the page away without a relaunch`() = runTest {
        val port = FakePort()
        val h = loaded(port)
        assertEquals(AccountPhase.Ready, h.controller.state.value.phase)

        port.onState = { RpcResult.Err(accountRefusal(AccountFixtures.SERVICE_UNAVAILABLE)) }
        h.controller.refresh()
        advanceUntilIdle()
        assertEquals(AccountPhase.Unavailable, h.controller.state.value.phase)

        // And back on again.
        port.onState = { ok(AccountFixtures.STATE_SIGNED_OUT) }
        h.controller.refresh()
        advanceUntilIdle()
        assertEquals(AccountPhase.Ready, h.controller.state.value.phase)
    }

    @Test
    fun `the harness answering gateway service unavailable is a missing capability for the store`() {
        // This is the spelling the store's probe uses to retire and to restore the Account feature.
        val refused = RpcResult.Err(accountRefusal(AccountFixtures.SERVICE_UNAVAILABLE)).withAccountCapability() as RpcResult.Err
        assertEquals("capability-unavailable", refused.error.code)
    }
}

private fun ok(json: String): RpcResult<AccountView> = RpcResult.Ok(stateOf(json))
