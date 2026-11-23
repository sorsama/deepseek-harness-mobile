package com.labteto.dshmobile.connection

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The app side of the relay's web view handoff (relay 0.5.0, docs/CLIENT_INTEGRATION.md). */
class RelaySessionHandoffTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun start() {
        server = MockWebServer().apply { start() }
    }

    @After fun stop() {
        server.shutdown()
    }

    private fun base() = server.url("/").toString()

    private fun open() = runBlocking { openRelaySession(client, base(), "Bearer t0k", "/") }

    @Test fun `asks with the bearer token and loads the code on the address the app uses`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
                """{"code":"abc","path":"/relay/session/enter?code=abc","url":"https://192.168.1.9:3443/relay/session/enter?code=abc","expiresAt":1763891234567}""",
            ),
        )
        val outcome = open()
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/relay/session/open", request.path)
        assertEquals("Bearer t0k", request.getHeader("Authorization"))
        assertEquals("""{"next":"/"}""", request.body.readUtf8())
        // Built from the path on the app's own origin, not from the relay's idea of its address.
        assertEquals(RelayHandoffOutcome.Code(server.url("/relay/session/enter?code=abc").toString(), 1763891234567), outcome)
    }

    @Test fun `a refused token means pairing again`() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(RelayHandoffOutcome.Refused, open())
    }

    @Test fun `a relay without the handoff is told apart`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(RelayHandoffOutcome.Unsupported, open())
    }

    @Test fun `a rate limit is worth asking again later`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "12"))
        val outcome = open()
        assertTrue(outcome is RelayHandoffOutcome.Failed)
        assertEquals(12L, (outcome as RelayHandoffOutcome.Failed).retryAfterSeconds)
    }

    @Test fun `an answer that is not a code on the relay is refused`() {
        for (body in listOf(
            "not json",
            """{"code":"abc"}""",
            """{"path":"/somewhere/else?code=abc"}""",
            """{"path":"//evil.example/relay/session/enter?code=abc"}""",
        )) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(body))
            assertTrue(body, open() is RelayHandoffOutcome.Failed)
        }
    }

    @Test fun `a dropped connection is a failure not a crash`() {
        val gone = base()
        server.shutdown()
        val outcome = runBlocking { openRelaySession(client, gone, "Bearer t0k", "/") }
        assertTrue(outcome is RelayHandoffOutcome.Failed)
    }
}
