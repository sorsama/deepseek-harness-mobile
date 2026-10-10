package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.HarnessLaunchLine
import com.labteto.dshmobile.core.wire.HarnessSession
import com.labteto.dshmobile.core.wire.OkHttpRpcTransport
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.SessionExchange
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.TransportFailures
import com.labteto.dshmobile.mockharness.MockHarness
import com.labteto.dshmobile.mockharness.SessionMode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sign-in a harness on this phone needs, end to end over a real socket: the probe answers
 * 401 without a session, the startup line's token buys the cookie, and the same probe answers
 * with it. This is the path "start the harness from the app" automates, so the pieces it chains
 * — [HarnessLaunchLine], [HarnessSession] and the transport's cookie — are checked together.
 */
class HarnessSessionExchangeTest {

    private lateinit var harness: MockHarness
    private var port: Int = -1
    private val http = OkHttpClient()

    private val baseUrl get() = "http://127.0.0.1:$port"

    @Before
    fun setUp() = runBlocking {
        harness = MockHarness(port = 0, session = SessionMode())
        port = harness.start()
    }

    @After
    fun tearDown() = runBlocking { harness.stop() }

    private fun client(cookie: String? = null) =
        DshApiClient(OkHttpRpcTransport(baseUrl, http, 5_000, 5_000, cookie = cookie))

    @Test
    fun `without a session the probe call is refused with 401`() = runBlocking {
        val result = client().sessionCanOpenWorkspacePath()
        assertTrue(result is RpcResult.Err)
        assertEquals(TransportFailure.UNAUTHENTICATED, TransportFailures.of((result as RpcResult.Err).error))
    }

    @Test
    fun `the launch token is exchanged for the session cookie`() = runBlocking {
        assertEquals(
            SessionExchange.Granted("dsh-auth-test=session-test"),
            HarnessSession.exchange(baseUrl, "launch-token-test", http),
        )
    }

    /** The token rotates on every harness start; yesterday's buys nothing. */
    @Test
    fun `a stale token is refused and issues nothing`() = runBlocking {
        assertTrue(HarnessSession.exchange(baseUrl, "yesterday", http) is SessionExchange.Refused)
    }

    @Test
    fun `with the cookie the probe call answers`() = runBlocking {
        val granted = HarnessSession.exchange(baseUrl, "launch-token-test", http) as SessionExchange.Granted
        assertEquals(RpcResult.Ok(true), client(granted.cookie).sessionCanOpenWorkspacePath())
    }

    /** What the start script hands back is the whole line, markers and all — and that is enough. */
    @Test
    fun `the readiness line the start script captures signs the app in`() = runBlocking {
        val stdout = "DSHM:started\ndsh web: $baseUrl/?token=launch-token-test\n"
        val line = HarnessLaunchLine.parse(stdout)!!
        assertEquals(baseUrl, line.baseUrl)
        val token = HarnessSession.tokenFrom(line.raw)!!
        assertTrue(HarnessSession.exchange(line.baseUrl, token, http) is SessionExchange.Granted)
    }
}
