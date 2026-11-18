package com.labteto.dshmobile.core.account

import com.labteto.dshmobile.core.wire.dto.AccountLinks
import com.labteto.dshmobile.core.wire.dto.AccountView
import com.labteto.dshmobile.core.wire.dto.SignInAttempt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `account/getState` as the page reads it, against the answers a real harness gave for every state of a
 * sign-in (see [AccountFixtures]). The phases and error codes are the ones in
 * `packages/credentials/deepseek-account/src/types.ts`.
 */
class AccountStateTest {

    @Test
    fun `a harness with no sign-in and no attempt is just signed out`() {
        val snapshot = stateOf(AccountFixtures.STATE_SIGNED_OUT).toSnapshot()
        assertFalse(snapshot.signedIn)
        assertNull(snapshot.progress)
        assertNull(snapshot.outcome)
        assertNull(snapshot.credentialKey)
    }

    @Test
    fun `an attempt that has only started has no link yet`() {
        val progress = stateOf(AccountFixtures.STATE_STARTING).toSnapshot().progress!!
        assertEquals(SignInStep.Starting, progress.step)
        assertNull(progress.authorizeUrl)
        assertTrue(progress.cancellable)
    }

    @Test
    fun `an attempt waiting for the browser carries its link and its deadline`() {
        val snapshot = stateOf(AccountFixtures.STATE_WAITING).toSnapshot()
        assertFalse(snapshot.signedIn)
        val progress = snapshot.progress!!
        assertEquals(SignInStep.WaitingForBrowser, progress.step)
        assertTrue(progress.authorizeUrl!!.contains("/dsh/authorize"))
        assertEquals(1794979700569L, progress.expiresAtMs)
        assertTrue(progress.cancellable)
        assertNull(snapshot.outcome)
    }

    @Test
    fun `joining a running attempt reports the same attempt`() {
        assertEquals(
            stateOf(AccountFixtures.STATE_WAITING).toSnapshot().progress,
            stateOf(AccountFixtures.STATE_JOINED).toSnapshot().progress,
        )
        // A cancel that names another attempt leaves the running one on screen.
        assertEquals(
            stateOf(AccountFixtures.STATE_WAITING).toSnapshot().progress,
            stateOf(AccountFixtures.STATE_STALE_CANCEL).toSnapshot().progress,
        )
    }

    @Test
    fun `a cancelled attempt is not progress but it is said how the last try ended`() {
        // The harness keeps the cancelled attempt: signed out, phase cancelled, no link. The page used
        // to read that as nothing to say and went back to "Not signed in" without a word.
        val view = stateOf(AccountFixtures.STATE_CANCELLED)
        assertEquals("cancelled", view.attempt!!.phase)
        val snapshot = view.toSnapshot()
        assertFalse(snapshot.signedIn)
        assertNull(snapshot.progress)
        assertEquals(SignInOutcome.Cancelled, snapshot.outcome)
    }

    @Test
    fun `every way the last try can have ended is said and none of them while one is running`() {
        assertEquals(SignInOutcome.Cancelled, stateOf(AccountFixtures.STATE_CANCELLED).toSnapshot().outcome)
        assertEquals(SignInOutcome.Expired, stateOf(AccountFixtures.STATE_EXPIRED).toSnapshot().outcome)
        assertEquals(SignInOutcome.Failed(SignInProblem.Generic), stateOf(AccountFixtures.STATE_FAILED_EXCHANGE).toSnapshot().outcome)
        // A running attempt, and a stored sign-in whose last attempt succeeded, have no outcome to report.
        assertNull(stateOf(AccountFixtures.STATE_WAITING).toSnapshot().outcome)
        assertNull(stateOf(AccountFixtures.STATE_STARTING).toSnapshot().outcome)
        assertNull(stateOf(AccountFixtures.STATE_SIGNED_IN).toSnapshot().outcome)
        assertNull(stateOf(AccountFixtures.STATE_SIGNED_OUT).toSnapshot().outcome)
    }

    @Test
    fun `a cancelled attempt over a stored sign-in is not reported`() {
        // Signed in with an older attempt that was cancelled (an account switch someone gave up on): nothing to say.
        val view = stateOf(AccountFixtures.STATE_SIGNED_IN)
        val cancelled = view.copy(attempt = view.attempt!!.copy(phase = "cancelled"))
        assertNull(cancelled.toSnapshot().outcome)
        assertTrue(cancelled.toSnapshot().signedIn)
    }

    @Test
    fun `a stored sign-in keeps its succeeded attempt and that is not progress`() {
        val view = stateOf(AccountFixtures.STATE_SIGNED_IN)
        assertEquals("succeeded", view.attempt!!.phase)
        val snapshot = view.toSnapshot()
        assertTrue(snapshot.signedIn)
        assertNull(snapshot.progress)
        assertNull(snapshot.outcome)
        assertEquals(view.attempt!!.id, snapshot.credentialKey)
    }

    @Test
    fun `a new attempt over a stored sign-in is progress and the sign-in stays`() {
        val snapshot = stateOf(AccountFixtures.STATE_REAUTH_WAITING).toSnapshot()
        assertTrue(snapshot.signedIn)
        assertEquals(SignInStep.WaitingForBrowser, snapshot.progress!!.step)
        // It is not a finished sign-in yet, so it does not name a new credential.
        assertEquals("", snapshot.credentialKey)
    }

    @Test
    fun `signing out clears the attempt and the credential`() {
        val snapshot = stateOf(AccountFixtures.STATE_AFTER_SIGN_OUT).toSnapshot()
        assertFalse(snapshot.signedIn)
        assertNull(snapshot.progress)
        assertNull(snapshot.credentialKey)
    }

    @Test
    fun `a failed exchange stays on a signed out harness as a failure to report`() {
        val view = stateOf(AccountFixtures.STATE_FAILED_EXCHANGE)
        assertEquals("network", view.attempt!!.errorCode)
        assertEquals(SignInOutcome.Failed(SignInProblem.Generic), view.toSnapshot().outcome)
    }

    @Test
    fun `an attempt past its deadline is expired`() {
        val snapshot = stateOf(AccountFixtures.STATE_EXPIRED).toSnapshot()
        assertEquals(SignInOutcome.Expired, snapshot.outcome)
        assertNull(snapshot.progress)
    }

    @Test
    fun `no failure is reported over a stored sign-in`() {
        // A failed re-authorization changes nothing about the sign-in that is still there.
        val view = AccountView("credential-stored", AccountLinks(), SignInAttempt("a1", "failed", errorCode = "network"))
        assertNull(view.toSnapshot().outcome)
    }

    @Test
    fun `the harness error codes become the reasons a person is told`() {
        // The five codes of SignInErrorCode, from deepseek-account/src/types.ts.
        assertEquals(SignInProblem.PlatformUnreachable, signInProblem("no-response"))
        assertEquals(SignInProblem.Storage, signInProblem("storage"))
        assertEquals(SignInProblem.Generic, signInProblem("network"))
        assertEquals(SignInProblem.Generic, signInProblem("protocol"))
        assertEquals(SignInProblem.Generic, signInProblem(null))
        assertEquals(SignInProblem.Generic, signInProblem("a-code-from-a-newer-harness"))
        val failed = AccountView("signed-out", AccountLinks(), SignInAttempt("a2", "failed", errorCode = "no-response"))
        assertEquals(SignInOutcome.Failed(SignInProblem.PlatformUnreachable), failed.toSnapshot().outcome)
    }

    @Test
    fun `exchanging and committing are the finishing step that can no longer be cancelled`() {
        // `exchanging` and `committing` are set by PlatformAccount.run in deepseek-account-platform/src/index.ts;
        // no answer was recorded for them (they last milliseconds), so these two are written from the source.
        for (phase in listOf("exchanging", "committing")) {
            val view = AccountView("signed-out", AccountLinks(), SignInAttempt("a3", phase))
            val progress = view.toSnapshot().progress!!
            assertEquals(SignInStep.Finishing, progress.step)
            assertFalse(progress.cancellable)
        }
    }

    @Test
    fun `a phase from a newer harness neither blocks the page nor reports a failure`() {
        val view = AccountView("signed-out", AccountLinks(), SignInAttempt("a4", "reviewing"))
        val snapshot = view.toSnapshot()
        assertNull(snapshot.progress)
        assertNull(snapshot.outcome)
    }

    @Test
    fun `an unknown status is read as signed out`() {
        assertFalse(AccountView("something-new").toSnapshot().signedIn)
    }

    @Test
    fun `a second succeeded attempt over a stored sign-in names a different credential`() {
        val first = AccountView("credential-stored", AccountLinks(), SignInAttempt("one", "succeeded")).toSnapshot()
        val second = AccountView("credential-stored", AccountLinks(), SignInAttempt("two", "succeeded")).toSnapshot()
        assertNotEquals(first.credentialKey, second.credentialKey)
    }

    @Test
    fun `the one time link stays out of what a log line or a crash report would print`() {
        val view = stateOf(AccountFixtures.STATE_WAITING)
        val link = view.attempt!!.authorizeUrl!!
        assertFalse(view.attempt.toString().contains(link))
        assertFalse(view.toString().contains("authorize_id"))
        val progress = view.toSnapshot().progress!!
        assertFalse(progress.toString().contains(link))
        assertFalse(view.toSnapshot().toString().contains("authorize_id"))
        assertIs<SignInProgress>(progress)
    }

    @Test
    fun `the page asks again quickly at the ends of a sign-in and slowly while the browser is open`() {
        assertTrue(accountPollDelayMs(SignInStep.Starting) < accountPollDelayMs(SignInStep.WaitingForBrowser))
        assertTrue(accountPollDelayMs(SignInStep.Finishing) < accountPollDelayMs(SignInStep.WaitingForBrowser))
        assertTrue(accountPollDelayMs(SignInStep.WaitingForBrowser) <= 3000L)
    }
}
