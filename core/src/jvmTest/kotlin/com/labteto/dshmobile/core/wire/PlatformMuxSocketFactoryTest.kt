package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.mockharness.MockHarness
import com.labteto.dshmobile.mockharness.RelayMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The JVM actual of [platformMuxSocketFactory] is the socket the Android app has always run
 * (`WsChannel`), reached by the one name common code uses. It has to build a socket that carries the
 * credential and reaches `ready`, and it has to refuse the trust settings the iOS actual refuses, so
 * a call that is wrong on one platform is wrong on both.
 */
class PlatformMuxSocketFactoryTest {

    @Test
    fun `the factory's socket carries the credential and reaches ready`() = runBlocking {
        val harness = MockHarness(port = 0, relay = RelayMode())
        val port = harness.start()
        try {
            val factory = platformMuxSocketFactory(
                "http://127.0.0.1:$port/api/remote.mux",
                authorization = "Bearer relay-test-token",
                connectTimeoutMs = 5_000,
            )
            val mux = RemoteStreamMux(factory)
            try {
                mux.start()
                withTimeout(15_000) { mux.awaitOpen() }
                val stream = mux.open("\$events")
                val ready = withTimeout(15_000) { stream.receive() }!!.jsonObject
                assertEquals("ready", ready["type"]!!.jsonPrimitive.content)
                stream.cancel()
            } finally {
                mux.close()
            }
        } finally {
            harness.stop()
        }
    }

    @Test
    fun `a blank pin, a pin with an observed key and a pin on a plaintext address are refused where they are written`() {
        val tls = "https://relay.example/api/remote.mux"
        assertThrows(IllegalArgumentException::class.java) { platformMuxSocketFactory(tls, pinnedSpkiSha256 = " ") }
        assertThrows(IllegalArgumentException::class.java) {
            platformMuxSocketFactory(tls, pinnedSpkiSha256 = "AAAA", observedKey = ObservedKey())
        }
        assertThrows(IllegalArgumentException::class.java) {
            platformMuxSocketFactory("http://relay.example/api/remote.mux", pinnedSpkiSha256 = "AAAA")
        }
    }
}
