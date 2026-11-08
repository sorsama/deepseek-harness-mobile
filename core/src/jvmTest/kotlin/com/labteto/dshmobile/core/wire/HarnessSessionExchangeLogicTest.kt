package com.labteto.dshmobile.core.wire

import java.net.ConnectException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The launch-token exchange's own logic, with the HTTP taken away: what it asks a [PlainHttp] for
 * and how it reads the answer. (`HarnessSessionUrlTest`, the app's `HarnessSessionExchangeTest` and
 * the conformance suite run it over real sockets.)
 */
class HarnessSessionExchangeLogicTest {

    private class FakeHttp(private val answer: (PlainHttpRequest) -> PlainHttpResponse) : PlainHttp {
        val requests = mutableListOf<PlainHttpRequest>()
        override suspend fun execute(request: PlainHttpRequest): PlainHttpResponse {
            requests += request
            return answer(request)
        }
    }

    private fun answering(status: Int, vararg setCookie: String) = FakeHttp {
        PlainHttpResponse(status, "", mapOf("Set-Cookie" to setCookie.toList()))
    }

    @Test
    fun `the exchange is a get of the root with the token, and does not read the body`() = runBlocking {
        val http = answering(303, "dsh-auth-x=1; Path=/")
        HarnessSession.exchange("http://127.0.0.1:3080", "abc-DEF_123", http, timeoutMs = 7_000)

        val request = http.requests.single()
        assertEquals("GET", request.method)
        assertEquals("http://127.0.0.1:3080/?token=abc-DEF_123", request.url)
        assertEquals(7_000, request.connectTimeoutMs)
        assertEquals(7_000, request.readTimeoutMs)
        assertEquals(false, request.readBody)
        assertEquals(emptyList<Pair<String, String>>(), request.headers)
        assertNull(request.body)
    }

    @Test
    fun `a reverse-proxy prefix is kept and the token is percent-encoded`() = runBlocking {
        val http = answering(303, "dsh-auth-x=1")
        HarnessSession.exchange("https://relay.example:8443/team/dsh/?old=1#f", "a b&c=d+e/f", http)
        assertEquals("https://relay.example:8443/team/dsh/?token=a%20b%26c%3Dd%2Be%2Ff", http.requests.single().url)
    }

    @Test
    fun `only the name and value of the session cookie travel back`() = runBlocking {
        val result = HarnessSession.exchange(
            "http://h:1",
            "t",
            answering(303, "other=1; Path=/", "dsh-auth-127.0.0.1:3080=abc.def; Path=/; HttpOnly; SameSite=Lax"),
        )
        assertEquals(SessionExchange.Granted("dsh-auth-127.0.0.1:3080=abc.def"), result)
    }

    @Test
    fun `a carrier that folds repeated cookies into one header still yields the session cookie`() = runBlocking {
        val folded = "other=1; Path=/, dsh-auth-x=2; Path=/; HttpOnly, third=3"
        assertEquals(SessionExchange.Granted("dsh-auth-x=2"), HarnessSession.exchange("http://h:1", "t", answering(303, folded)))
    }

    @Test
    fun `an answer without the session cookie is a refusal carrying its status`() = runBlocking {
        assertEquals(SessionExchange.Refused(200), HarnessSession.exchange("http://h:1", "t", answering(200)))
        assertEquals(SessionExchange.Refused(401), HarnessSession.exchange("http://h:1", "t", answering(401, "other=1")))
        // A cookie that merely mentions the prefix is not the session cookie.
        assertEquals(SessionExchange.Refused(200), HarnessSession.exchange("http://h:1", "t", answering(200, "x=dsh-auth-y=1")))
    }

    @Test
    fun `a failure to get an answer is unreachable and says why`() = runBlocking {
        val http = FakeHttp { throw ConnectException("Connection refused") }
        val result = HarnessSession.exchange("http://h:1", "t", http)
        assertTrue(result.toString(), result is SessionExchange.Unreachable)
        result as SessionExchange.Unreachable
        assertEquals(TransportFailure.REFUSED, result.kind)
        assertEquals("Connection refused", result.message)
    }

    @Test
    fun `a bad base address is the callers mistake and reaches no http`() = runBlocking {
        val http = answering(200)
        for (base in listOf("", "not a url", "ftp://host", "http://user:pw@host")) {
            try {
                HarnessSession.exchange(base, "t", http)
                fail("<$base> must be refused")
            } catch (expected: IllegalArgumentException) {
                // refused
            }
        }
        assertEquals(emptyList<PlainHttpRequest>(), http.requests)
    }

    @Test
    fun `tokens are read out of whatever was pasted`() {
        assertEquals("abc", HarnessSession.tokenFrom("abc"))
        assertEquals("abc", HarnessSession.tokenFrom("  abc  "))
        assertEquals("abc", HarnessSession.tokenFrom("http://127.0.0.1:3080/?token=abc"))
        assertEquals("abc", HarnessSession.tokenFrom("dsh web: http://127.0.0.1:3080/?token=abc&x=1 (LAN)"))
        assertNull(HarnessSession.tokenFrom("   "))
        assertNull(HarnessSession.tokenFrom("?token="))
    }
}
