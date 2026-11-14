package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.SessionExchange
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.termux.StartSignIn
import com.labteto.dshmobile.ui.screens.connect.HarnessSignIn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sign-in both local start paths share (Termux and the built-in runtime). */
class LoopbackSignInTest {

    private class Exchange(private val answer: SessionExchange) : HarnessSignIn {
        val calls = mutableListOf<Triple<String, String, String>>()
        override suspend fun pair(hostId: String, baseUrl: String, tokenInput: String): SessionExchange {
            calls += Triple(hostId, baseUrl, tokenInput)
            return answer
        }
    }

    private val host = HostConfig(id = "loop-1", name = "127.0.0.1", host = "127.0.0.1", port = 3080, isLoopback = true, lastConnectedAt = 0)
    private var hostsAsked = 0
    private val loopback: suspend () -> HostConfig = { hostsAsked++; host }

    @Test
    fun `the readiness line from the runtime signs in against the loopback record`() = runBlocking {
        val exchange = Exchange(SessionExchange.Granted("dsh-auth-x=1"))
        val line = "dsh web: http://127.0.0.1:3080/?token=tok_123"
        assertEquals(StartSignIn.SIGNED_IN, LoopbackSignIn.signIn(line, exchange, loopback))
        assertEquals(listOf(Triple("loop-1", host.baseUrl, line)), exchange.calls)
    }

    @Test
    fun `a Termux script's whole stdout works the same way`() = runBlocking {
        val exchange = Exchange(SessionExchange.Granted("dsh-auth-x=1"))
        val stdout = "DSH_MARKER started\n\u001B[1mdsh web: http://127.0.0.1:3080/?token=tok_456\u001B[0m\nmore\n"
        assertEquals(StartSignIn.SIGNED_IN, LoopbackSignIn.signIn(stdout, exchange, loopback))
        assertTrue(exchange.calls.single().third.endsWith("token=tok_456"))
    }

    @Test
    fun `no token means no exchange and no host record`() = runBlocking {
        val exchange = Exchange(SessionExchange.Granted("unused"))
        assertEquals(StartSignIn.NO_TOKEN, LoopbackSignIn.signIn("dsh web: http://127.0.0.1:3080/", exchange, loopback))
        assertEquals(StartSignIn.NO_TOKEN, LoopbackSignIn.signIn("nothing printed", exchange, loopback))
        assertTrue(exchange.calls.isEmpty())
        assertEquals(0, hostsAsked)
    }

    @Test
    fun `a refusal and an unreachable harness are told apart`() = runBlocking {
        val line = "dsh web: http://127.0.0.1:3080/?token=t"
        assertEquals(StartSignIn.REFUSED, LoopbackSignIn.signIn(line, Exchange(SessionExchange.Refused(401)), loopback))
        assertEquals(
            StartSignIn.UNREACHABLE,
            LoopbackSignIn.signIn(line, Exchange(SessionExchange.Unreachable(TransportFailure.REFUSED, "refused")), loopback),
        )
    }
}
