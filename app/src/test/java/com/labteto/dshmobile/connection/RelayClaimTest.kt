package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.RelayOrigin
import com.labteto.dshmobile.core.wire.RelayPairOutcome
import com.labteto.dshmobile.core.wire.RelayPairing
import com.labteto.dshmobile.mockharness.MockHarness
import com.labteto.dshmobile.mockharness.RelayMode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The claim exchange, against a relay stand-in.
 *
 * `POST /relay/pair` is the only call in the app that is not the harness wire protocol, and it is
 * the one whose failure modes are least forgiving: a code works once, a wrong content type gets a
 * web page instead of a token, and everything unauthorized is 403 rather than 401. None of that is
 * visible from a unit test of the parser.
 */
class RelayClaimTest {

    private lateinit var harness: MockHarness
    private var port: Int = -1
    private val client = OkHttpClient()

    private val baseUrl get() = "http://127.0.0.1:$port"

    @Before
    fun setUp() = runBlocking {
        harness = MockHarness(port = 0, relay = RelayMode(fingerprint = "3q2+7w=="))
        port = harness.start()
    }

    @After
    fun tearDown() = runBlocking { harness.stop() }

    @Test
    fun `a valid code mints a token`() = runBlocking {
        val outcome = RelayPairing.claim(baseUrl, "48213977", "Pixel 8", client)
        val paired = outcome as RelayPairOutcome.Paired
        assertEquals("relay-test-token", paired.response.token)
        assertEquals("9f2c41ab30d7e155", paired.response.deviceId)
        // The relay repeats its pin in the answer; that is what makes a typed pairing pinnable.
        assertEquals("3q2+7w==", paired.response.fingerprint)
    }

    /** Single use is the property that makes an intercepted code worthless once it has been spent. */
    @Test
    fun `a code cannot be claimed twice`() = runBlocking {
        assertTrue(RelayPairing.claim(baseUrl, "48213977", "first", client) is RelayPairOutcome.Paired)
        assertEquals(
            RelayPairOutcome.Rejected,
            RelayPairing.claim(baseUrl, "48213977", "second", client),
        )
    }

    @Test
    fun `a wrong code is refused`() = runBlocking {
        assertEquals(
            RelayPairOutcome.Rejected,
            RelayPairing.claim(baseUrl, "00000000", "Pixel 8", client),
        )
    }

    /** The relay's lockout answers 429; the header it sets is the back-off the client owes it. */
    @Test
    fun `a rate limited claim reports how long to wait`() = runBlocking {
        harness.pairingRateLimited = true
        val outcome = RelayPairing.claim(baseUrl, "48213977", "Pixel 8", client)
        assertEquals(RelayPairOutcome.RateLimited(30), outcome)
    }

    /** Anything else at that address is not a relay, whatever HTTP status it chose to say so with. */
    @Test
    fun `a plain harness is not a pairing endpoint`() = runBlocking {
        val plain = MockHarness(port = 0)
        val plainPort = plain.start()
        try {
            assertEquals(
                RelayPairOutcome.NotARelay,
                RelayPairing.claim("http://127.0.0.1:$plainPort", "48213977", "Pixel 8", client),
            )
        } finally {
            plain.stop()
        }
    }

    @Test
    fun `health tells a relay from a bare harness`() = runBlocking {
        assertTrue(RelayPairing.health(baseUrl, client))
        val plain = MockHarness(port = 0)
        val plainPort = plain.start()
        try {
            assertFalse(RelayPairing.health("http://127.0.0.1:$plainPort", client))
        } finally {
            plain.stop()
        }
    }

    @Test
    fun `an unreachable address is reported as such, not as a refusal`() = runBlocking {
        val outcome = RelayPairing.claim("http://127.0.0.1:1", "48213977", "Pixel 8", client)
        assertTrue(outcome is RelayPairOutcome.Unreachable)
    }

    /**
     * Since relay 0.1.1 the harness's own port answers `/relay` with a redirect to the relay's
     * listener, which turns the address people already know into a way in. It only works if the
     * answer is read: chasing it would record a relay living at the harness's port, and a 302
     * rewrites the claim's POST into a GET, so the request would arrive as a page view.
     */
    @Test
    fun `the harness port names the relay rather than answering for it`() = runBlocking {
        val harness = MockHarness(port = 0, relayRedirectTo = baseUrl)
        val harnessPort = harness.start()
        try {
            val located = RelayPairing.locate("http://127.0.0.1:$harnessPort", client)
                as RelayOrigin.Redirected
            assertEquals(RelayOrigin.Redirected(baseUrl, false), located)
            // Not chased: health is about this address, not wherever it points.
            assertFalse(RelayPairing.health("http://127.0.0.1:$harnessPort", client))
            // A claim sent there is refused rather than silently turned into a page view.
            assertEquals(
                RelayPairOutcome.NotARelay,
                RelayPairing.claim("http://127.0.0.1:$harnessPort", "48213977", "Pixel 8", client),
            )
            // Resolving first is what makes the typed harness address pair.
            assertTrue(
                RelayPairing.claim(located.origin, "48213977", "Pixel 8", client)
                    is RelayPairOutcome.Paired,
            )
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `the relay's own address needs no resolving`() = runBlocking {
        assertEquals(RelayOrigin.Here(baseUrl, false), RelayPairing.locate(baseUrl, client))
    }

    /** A redirect somewhere other than the health path is somebody else's routing, not the relay. */
    @Test
    fun `a bare harness names nothing`() = runBlocking {
        val plain = MockHarness(port = 0)
        val plainPort = plain.start()
        try {
            assertEquals(RelayOrigin.None, RelayPairing.locate("http://127.0.0.1:$plainPort", client))
        } finally {
            plain.stop()
        }
    }

    /**
     * The failure that made a working relay look like a missing one.
     *
     * The relay's fence runs before every route, so an address it does not know itself by — an
     * emulator's host alias, a name it was never told — answers 403 to the unauthenticated
     * liveness probe as readily as to anything else. Reading that as "no relay here" is why a scan
     * came back empty while the harness log said `refused GET /relay/health: untrusted-host`.
     */
    @Test
    fun `a relay that refuses the address is still found`() = runBlocking {
        val fenced = MockHarness(port = 0, relay = RelayMode(refuseHost = true))
        val fencedPort = fenced.start()
        val origin = "http://127.0.0.1:$fencedPort"
        try {
            assertEquals(RelayOrigin.Untrusted(origin, false), RelayPairing.locate(origin, client))
            // And a claim there is a configuration problem, not a bad code — the code is never read.
            assertEquals(
                RelayPairOutcome.HostRefused,
                RelayPairing.claim(origin, "48213977", "Pixel 8", client),
            )
        } finally {
            fenced.stop()
        }
    }

    /** The other 403: the relay read the code and would not have it. Told apart by the body. */
    @Test
    fun `a refused code is not reported as a refused address`() = runBlocking {
        assertEquals(
            RelayPairOutcome.Rejected,
            RelayPairing.claim(baseUrl, "00000000", "Pixel 8", client),
        )
    }
}
