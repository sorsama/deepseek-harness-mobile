package com.labteto.dshmobile.mockharness

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The pairing relays the simulator and emulator scripts pair through: a code that can be used again, a
 * code that is always locked out, and a relay that speaks TLS with a certificate whose pin is known.
 */
class PairingFixturesTest {

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun claim(base: String, code: String, client: HttpClient = http): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("$base/relay/pair"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"code":"$code","name":"test phone"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun aReusableCodeStaysValidAfterItHasBeenClaimed() = runBlocking {
        val servers = CiFixtures.start()
        try {
            val code = CiFixtures.pairRelayMode.pairingCode
            assertEquals(200, claim(servers.pairUrl, code).statusCode())
            // A real relay's code works once; this one is for a script that pairs once per screenshot.
            assertEquals(200, claim(servers.pairUrl, code).statusCode())
            assertEquals(403, claim(servers.pairUrl, "00000000").statusCode())
        } finally {
            servers.stop()
        }
    }

    @Test
    fun theLockoutCodeAlwaysAnswers429WithARetryAfter() = runBlocking {
        val servers = CiFixtures.start()
        try {
            val response = claim(servers.pairUrl, CiFixtures.RATE_LIMITED_CODE)
            assertEquals(429, response.statusCode())
            assertEquals("30", response.headers().firstValue("Retry-After").orElse(""))
        } finally {
            servers.stop()
        }
    }

    @Test
    fun theSingleUseRelayStillRefusesAReplayedCode() = runBlocking {
        val servers = CiFixtures.start()
        try {
            val code = CiFixtures.relayMode.pairingCode
            assertEquals(200, claim(servers.relayUrl, code).statusCode())
            assertEquals(403, claim(servers.relayUrl, code).statusCode())
        } finally {
            servers.stop()
        }
    }

    @Test
    fun theTlsRelayPresentsTheKeyItNamesInTheClaimAnswer() = runBlocking {
        val servers = CiFixtures.start(withPairTls = true)
        try {
            val pin = servers.pairTlsFingerprint ?: error("the TLS fixture was asked for and has no fingerprint")
            val seen = mutableListOf<X509Certificate>()
            // Trust whatever is presented, and record it: the same position a trust-on-first-use client is in.
            val recording = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    seen += chain[0]
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(recording), SecureRandom()) }
            val client = HttpClient.newBuilder().sslContext(context).build()

            val response = claim(servers.pairTlsUrl!!, CiFixtures.pairRelayMode.pairingCode, client)

            assertEquals(200, response.statusCode())
            assertTrue("the claim answer repeats the key so a typed pairing can be pinned", response.body().contains(pin))
            val presented = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(seen.single().publicKey.encoded))
            assertEquals("the key on the wire is the key the fixture advertises", pin, presented)
        } finally {
            servers.stop()
        }
    }

    @Test
    fun theTlsRelayIsNotStartedUnlessAskedFor() = runBlocking {
        val servers = CiFixtures.start()
        try {
            assertEquals(null, servers.pairTlsPort)
            assertEquals(null, servers.pairTlsFingerprint)
        } finally {
            servers.stop()
        }
    }
}
