package com.labteto.dshmobile.core.account

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The refusals a real harness sent for the account calls, and how each is worded to a person
 * (see [AccountFixtures]). A failed sign-in step reaches the wire as `gateway/internal` with the message
 * `account: <code>` (`PlatformAuthError` in `deepseek-account-platform/src/protocol.ts`).
 */
class AccountFailureTest {

    @Test
    fun `a harness whose account plugin is off is unavailable`() {
        // Recorded with the deepseek-account entry disabled: the controller is exported but its service is not running.
        val error = accountRefusal(AccountFixtures.SERVICE_UNAVAILABLE)
        assertEquals("gateway/service-unavailable", error.code)
        assertEquals(AccountFailure.Unavailable, error.toAccountFailure())
    }

    @Test
    fun `a harness without the call is unavailable whichever way it says so`() {
        for (code in listOf(
            "capability-unavailable", "gateway/invocation-unavailable", "gateway/definition-unavailable",
            "method-not-found", "-32601", "gateway/service-unavailable",
        )) {
            assertEquals(AccountFailure.Unavailable, RpcError(code, "no").toAccountFailure(), code)
        }
    }

    @Test
    fun `an origin the harness refuses is a rejection that keeps its words as detail`() {
        val rejected = assertIs<AccountFailure.Rejected>(accountRefusal(AccountFixtures.START_REFUSED_LAN_ORIGIN).toAccountFailure())
        assertEquals("account: protocol", rejected.detail)
        assertIs<AccountFailure.Rejected>(accountRefusal(AccountFixtures.START_REFUSED_HTTPS_ORIGIN).toAccountFailure())
    }

    @Test
    fun `a harness that could not reach DeepSeek is worded as a network problem`() {
        // `no-response`: fetch returned no Response, including a timeout (docs/upgrade-guide/v0.2.0-rc.2/account-sign-in-errors).
        val failure = RpcError("gateway/internal", "account: no-response").toAccountFailure()
        assertEquals(AccountFailure.PlatformUnreachable("account: no-response"), failure)
    }

    @Test
    fun `a storage problem is its own reason`() {
        assertIs<AccountFailure.Storage>(RpcError("gateway/internal", "account: storage").toAccountFailure())
    }

    @Test
    fun `an HTTP error a bad response and an expired attempt are generic rejections`() {
        for (code in listOf("network", "protocol", "expired")) {
            assertIs<AccountFailure.Rejected>(RpcError("gateway/internal", "account: $code").toAccountFailure(), code)
        }
        assertIs<AccountFailure.Rejected>(RpcError("gateway/arguments-invalid", "bad").toAccountFailure())
    }

    @Test
    fun `a broken carrier and a refused session are unreachable`() {
        assertEquals(AccountFailure.Unreachable("socket closed"), RpcError("internal", "socket closed").toAccountFailure())
        assertEquals(AccountFailure.Unreachable("no session"), RpcError("unauthenticated", "no session").toAccountFailure())
        assertEquals(AccountFailure.Unreachable("fenced"), RpcError("forbidden", "fenced").toAccountFailure())
    }

    @Test
    fun `an account message with extra words is not mistaken for a code`() {
        assertIs<AccountFailure.Rejected>(RpcError("gateway/internal", "account: no-response but worse").toAccountFailure())
    }

    @Test
    fun `the capability record hears service-unavailable as a missing capability`() {
        val mapped = RpcResult.Err(accountRefusal(AccountFixtures.SERVICE_UNAVAILABLE)).withAccountCapability()
        assertEquals("capability-unavailable", (mapped as RpcResult.Err).error.code)
        // Nothing else is touched.
        val other = RpcResult.Err(RpcError("gateway/internal", "account: protocol"))
        assertEquals(other, other.withAccountCapability())
        val ok = RpcResult.Ok(7)
        assertEquals(ok, ok.withAccountCapability())
    }

    // ------------------------------------------------------------------ a link is a secret

    @Test
    fun `a sign-in link in a harness message is cut down to its origin`() {
        val link = stateOf(AccountFixtures.STATE_WAITING).attempt!!.authorizeUrl!!
        val redacted = redactUrls("could not open $link, giving up")
        assertFalse(redacted.contains("authorize_id"))
        assertFalse(redacted.contains("MOCKID"))
        assertEquals("could not open http://127.0.0.1:4590/…, giving up", redacted)
    }

    @Test
    fun `a link with a port a path and a query keeps only scheme host and port`() {
        assertEquals(
            "see https://platform.deepseek.com/…",
            redactUrls("see https://platform.deepseek.com/dsh/authorize?authorize_id=a1&state=s2"),
        )
        assertEquals("(http://localhost:3080/…)", redactUrls("(http://localhost:3080/oauth/callback?code=c&state=s)"))
    }

    @Test
    fun `several links are each cut and text without a link is left alone`() {
        assertEquals("a https://x.test/… b http://y.test:1/…", redactUrls("a https://x.test/p?q=1 b http://y.test:1/z"))
        assertEquals("account: protocol", redactUrls("account: protocol"))
        assertEquals("https://x.test", redactUrls("https://x.test"))
    }

    @Test
    fun `a failure keeps no link in its detail`() {
        val link = stateOf(AccountFixtures.STATE_WAITING).attempt!!.authorizeUrl!!
        val failure = RpcError("gateway/internal", "bad redirect to $link").toAccountFailure()
        val detail = assertIs<AccountFailure.Rejected>(failure).detail
        assertTrue(detail.startsWith("bad redirect to http://127.0.0.1:4590"))
        assertFalse(detail.contains("MOCKID"))
    }
}
