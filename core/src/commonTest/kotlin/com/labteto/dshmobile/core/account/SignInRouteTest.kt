package com.labteto.dshmobile.core.account

import com.labteto.dshmobile.core.wire.RpcResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a sign-in can be finished from, and which links are handed to a browser.
 *
 * The rule under test is the harness's own: `loginOrigin` in
 * `packages/credentials/deepseek-account-platform/src/protocol.ts` accepts only
 * `http://localhost`, `http://127.0.0.1` or `http://[::1]` with an explicit port, and the browser that
 * approves the sign-in is redirected to `<origin>/oauth/callback`, so it has to reach the harness there.
 * The harness's answers to a LAN and an https origin were recorded ([AccountFixtures.START_REFUSED_LAN_ORIGIN]).
 */
class SignInRouteTest {

    @Test
    fun `the built-in harness and a harness on this phone loopback are finished on the phone`() {
        // The built-in harness listens on 127.0.0.1 and the app reaches it there (HarnessRuntimeController).
        assertEquals(
            SignInRoute.OnThisPhone("http://127.0.0.1:3080"),
            signInRoute("127.0.0.1", 3080, useTls = false, basePath = "", isRelay = false),
        )
    }

    @Test
    fun `the origin handed to the harness has the form the harness accepted when it was recorded`() {
        // The recorded start used exactly this shape and the harness answered with a waiting attempt.
        val route = signInRoute("127.0.0.1", 3590, useTls = false, basePath = "", isRelay = false) as SignInRoute.OnThisPhone
        assertEquals("http://127.0.0.1:3590", route.callbackOrigin)
        assertEquals("waiting-browser", stateOf(AccountFixtures.STATE_WAITING).attempt!!.phase)
    }

    @Test
    fun `localhost and the IPv6 loopback are written the way the harness accepts them`() {
        assertEquals(SignInRoute.OnThisPhone("http://127.0.0.1:3080"), signInRoute("localhost", 3080, false, "", false))
        assertEquals(SignInRoute.OnThisPhone("http://127.0.0.1:3080"), signInRoute("LocalHost", 3080, false, "", false))
        assertEquals(SignInRoute.OnThisPhone("http://[::1]:3080"), signInRoute("::1", 3080, false, "", false))
        assertEquals(SignInRoute.OnThisPhone("http://[::1]:3080"), signInRoute("[::1]", 3080, false, "", false))
    }

    @Test
    fun `a harness on the local network is finished on its own computer`() {
        // The harness refused this origin when it was recorded, so the phone never starts one for a LAN harness.
        assertEquals("account: protocol", accountRefusal(AccountFixtures.START_REFUSED_LAN_ORIGIN).message)
        assertEquals(SignInRoute.OnHarnessComputer, signInRoute("192.168.1.20", 3080, false, "", false))
        assertEquals(SignInRoute.OnHarnessComputer, signInRoute("agent.home", 3080, false, "", false))
    }

    @Test
    fun `a relay is finished on the harness computer even when its address looks local`() {
        assertEquals(SignInRoute.OnHarnessComputer, signInRoute("127.0.0.1", 3080, false, "", isRelay = true))
        assertEquals(SignInRoute.OnHarnessComputer, signInRoute("relay.example.com", 443, true, "", isRelay = true))
    }

    @Test
    fun `a harness behind a reverse proxy is finished on its own computer`() {
        // https is refused by the harness too (recorded), and a path prefix has no route at the origin root.
        assertEquals("account: protocol", accountRefusal(AccountFixtures.START_REFUSED_HTTPS_ORIGIN).message)
        assertEquals(SignInRoute.OnHarnessComputer, signInRoute("127.0.0.1", 443, useTls = true, basePath = "", isRelay = false))
        assertEquals(SignInRoute.OnHarnessComputer, signInRoute("127.0.0.1", 8080, useTls = false, basePath = "/dsh", isRelay = false))
    }

    @Test
    fun `a port that cannot be the harness is never offered`() {
        assertEquals(SignInRoute.OnHarnessComputer, signInRoute("127.0.0.1", 0, false, "", false))
        assertEquals(SignInRoute.OnHarnessComputer, signInRoute("127.0.0.1", 70000, false, "", false))
    }

    // ------------------------------------------------------------------ the link

    @Test
    fun `a sign-in link on https is opened with the app scheme added`() {
        assertEquals(
            "https://platform.deepseek.com/dsh/authorize?authorize_id=abc&theme=dark",
            browserLink("https://platform.deepseek.com/dsh/authorize?authorize_id=abc", dark = true),
        )
        assertEquals(
            "https://platform.deepseek.com/dsh/authorize?authorize_id=abc&theme=light",
            browserLink("https://platform.deepseek.com/dsh/authorize?authorize_id=abc", dark = false),
        )
    }

    @Test
    fun `the recorded link to a local stand-in is accepted and keeps its own query`() {
        val link = stateOf(AccountFixtures.STATE_WAITING).attempt!!.authorizeUrl!!
        assertEquals("http://127.0.0.1:4590/dsh/authorize?authorize_id=MOCKID&theme=dark", browserLink(link, dark = true))
    }

    @Test
    fun `an existing theme is replaced and a fragment is dropped`() {
        assertEquals(
            "https://platform.deepseek.com/dsh/authorize?a=1&theme=dark",
            browserLink("https://platform.deepseek.com/dsh/authorize?theme=light&a=1#frag", dark = true),
        )
    }

    @Test
    fun `a link without a query gets only the scheme`() {
        assertEquals("https://platform.deepseek.com/dsh/authorize?theme=light", browserLink("https://platform.deepseek.com/dsh/authorize", dark = false))
    }

    @Test
    fun `only https and loopback http are ever handed to a browser`() {
        assertNull(browserLink("http://platform.deepseek.com/dsh/authorize?a=1", true))
        assertNull(browserLink("javascript:alert(1)", true))
        assertNull(browserLink("intent://scan/#Intent;scheme=zxing;end", true))
        assertNull(browserLink("file:///sdcard/x", true))
        assertNull(browserLink("market://details?id=x", true))
        assertNull(browserLink("platform.deepseek.com/dsh/authorize", true))
        assertEquals("http://[::1]:3000/x?theme=dark", browserLink("http://[::1]:3000/x", true))
        assertEquals("http://localhost:3000/x?theme=dark", browserLink("http://localhost:3000/x", true))
    }

    @Test
    fun `a link with credentials or odd characters is refused`() {
        assertNull(browserLink("https://platform.deepseek.com@evil.example/x", true))
        assertNull(browserLink("https://user:pw@platform.deepseek.com/x", true))
        assertNull(browserLink("https://platform.deepseek.com/x y", true))
        assertNull(browserLink("https://platform.deepseek.com/\u0007x", true))
        assertNull(browserLink("", true))
        assertNull(browserLink("   ", true))
        assertNull(browserLink("https:///path", true))
    }

    @Test
    fun `a trailing newline is trimmed rather than refused`() {
        assertTrue(browserLink("https://platform.deepseek.com/x\n", true)!!.startsWith("https://platform.deepseek.com/x"))
    }

    @Test
    fun `signing out names what it would stop`() {
        assertEquals(SignOutImpact.Idle, signOutImpact(RpcResult.Ok(ackOf(AccountFixtures.RUNNING_TASKS_NONE))))
        assertEquals(SignOutImpact.TasksRunning, signOutImpact(RpcResult.Ok(true)))
        // A question that failed is not an answer of "nothing".
        assertEquals(SignOutImpact.Unknown, signOutImpact(RpcResult.Err(accountRefusal(AccountFixtures.SERVICE_UNAVAILABLE))))
    }

    // --------------------------------------------------------------- a link the app refuses

    private fun waiting(url: String?) = SignInProgress("a1", SignInStep.WaitingForBrowser, url, 1794979700569L)

    @Test
    fun `the recorded waiting attempt has a link the browser can be given`() {
        val progress = stateOf(AccountFixtures.STATE_WAITING).toSnapshot().progress!!
        assertFalse(progress.linkRefused())
        assertTrue(progress.browserTarget(dark = true)!!.contains("theme=dark"))
        assertTrue(progress.browserTarget(dark = false)!!.contains("theme=light"))
    }

    @Test
    fun `an attempt waiting with a link the app will not open is refused`() {
        val refused = listOf(
            null, "", "javascript:alert(1)", "ftp://platform.deepseek.com/x", "http://platform.deepseek.com/x",
            "https://user@platform.deepseek.com/x", "https://platform.deepseek.com/x y",
        )
        for (url in refused) {
            val progress = waiting(url)
            assertTrue(progress.linkRefused(), "$url")
            assertNull(progress.browserTarget(dark = false), "$url")
            assertNull(progress.browserTarget(dark = true), "$url")
        }
    }

    @Test
    fun `an attempt that is not waiting for the browser has no refused link`() {
        // Starting has no link yet and Finishing has used it: neither is a link problem.
        assertFalse(SignInProgress("a1", SignInStep.Starting, null, null).linkRefused())
        assertFalse(SignInProgress("a1", SignInStep.Finishing, null, null).linkRefused())
        assertNull(SignInProgress("a1", SignInStep.Finishing, "https://platform.deepseek.com/x", null).browserTarget(dark = true))
    }
}
