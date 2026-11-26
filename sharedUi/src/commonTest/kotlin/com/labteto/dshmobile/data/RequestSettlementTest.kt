package com.labteto.dshmobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a client may take its own question or approval card away.
 *
 * This is the whole exit path for the client that acted. The host settles a waterfall by dropping
 * the answering client's delivery *first* and then pushing `cancel` to the deliveries that remain,
 * so the one client that is never told the request resolved is the one that resolved it. Through
 * 0.11.3 the card waited for that frame anyway and every answer, dismissal and skip left a panel
 * frozen on "Submitting…" that only a force-stop cleared.
 *
 * The rule cuts both ways, which is why it is pinned rather than inlined: clearing on anything
 * less than a settled request would throw away the only control that can still answer a wait the
 * host is still holding.
 */
class RequestSettlementTest {

    @Test
    fun `an accepted answer ends the request`() {
        assertTrue(settlesRequest(QuestionOutcome.Accepted))
    }

    @Test
    fun `a request the host says is over ends too`() {
        // Another client answered first, or the caller gave up before the `cancel` frame landed.
        // The card cannot address that wait any more, so keeping it offers a button to nowhere.
        assertTrue(settlesRequest(QuestionOutcome.Refused(NOT_PENDING)))
    }

    @Test
    fun `a refusal that leaves the wait open keeps the card`() {
        // `bad-response` is the host reading the batch and declining it: the tool call behind it
        // is still blocked, and this card is the only thing that can still unblock it.
        assertFalse(settlesRequest(QuestionOutcome.Refused("bad-response")))
        assertFalse(settlesRequest(QuestionOutcome.Refused("stale-generation")))
        // A code this build has never heard of is treated as the open wait it probably is.
        assertFalse(settlesRequest(QuestionOutcome.Refused("gateway/internal")))
    }

    @Test
    fun `a response that never arrived keeps the card`() {
        // Nothing is known about the wait, so taking the card away would strand the session with
        // no way to retry — the failure the user can see is the one they can act on.
        assertFalse(settlesRequest(QuestionOutcome.Unsent))
    }
}
