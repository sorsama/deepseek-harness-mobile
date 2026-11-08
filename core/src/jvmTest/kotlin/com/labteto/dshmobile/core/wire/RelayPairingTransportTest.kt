package com.labteto.dshmobile.core.wire

import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * `RelayPairing` is common code over a [PlainHttp]; this runs it against a loopback relay stand-in
 * on both implementations, so the pairing and discovery logic the Android app relies on is shown to
 * behave the same on the carrier iOS uses: what it sends, how it reads each answer, that it never
 * chases a redirect, and how it reports a relay it cannot reach.
 */
@RunWith(Parameterized::class)
class RelayPairingTransportTest(private val kind: TransportKind) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun kinds(): List<TransportKind> = TransportKind.parameters()
    }

    private val json = "application/json"
    private val paired = """{"deviceId":"dev-1","token":"tok-1","expiresAt":9999999,"fingerprint":"abc="}"""
    private val healthy = """{"service":"dsh-relay","ok":true}"""

    private suspend fun claim(baseUrl: String, code: String = "48213977", name: String = "Pixel 8") =
        RelayPairing.claim(baseUrl, code, name, kind.plain())

    // ---------------------------------------------------------------- claim

    @Test
    fun `a claim posts the code and device name as json and reads the credential`() = runBlocking {
        RecordingServer().route("/") { ex, _ -> ex.reply(200, paired, json) }.start().use { server ->
            val outcome = claim(server.baseUrl, "48213977", "Pixel 8")

            assertEquals(RelayPairOutcome.Paired(RelayPairResponse("dev-1", "tok-1", 9999999, "abc=")), outcome)
            val seen = server.seen.single()
            assertEquals("POST", seen.method)
            assertEquals("/relay/pair", seen.rawPath)
            assertEquals(json, seen.header("accept"))
            assertEquals("application/json; charset=utf-8", seen.header("content-type")?.lowercase())
            assertEquals("""{"code":"48213977","name":"Pixel 8"}""", String(seen.body, Charsets.UTF_8))
        }
    }

    @Test
    fun `each way a relay can refuse a claim is told apart`() = runBlocking {
        data class Case(val status: Int, val body: String, val headers: Map<String, String>, val expected: RelayPairOutcome)
        val cases = listOf(
            Case(403, """{"error":"pairing-failed","message":"no"}""", emptyMap(), RelayPairOutcome.Rejected),
            Case(403, "Forbidden: unknown host", emptyMap(), RelayPairOutcome.HostRefused),
            Case(403, """{"error":"something-else"}""", emptyMap(), RelayPairOutcome.HostRefused),
            Case(429, "{}", mapOf("Retry-After" to "17"), RelayPairOutcome.RateLimited(17)),
            Case(429, "{}", emptyMap(), RelayPairOutcome.RateLimited(RelayPairing.DEFAULT_RETRY_AFTER_SECONDS)),
            Case(429, "{}", mapOf("Retry-After" to "0"), RelayPairOutcome.RateLimited(1)),
            Case(404, "not here", emptyMap(), RelayPairOutcome.NotARelay),
            Case(500, "boom", emptyMap(), RelayPairOutcome.NotARelay),
            Case(200, "<html>a claim form</html>", emptyMap(), RelayPairOutcome.NotARelay),
        )
        for (case in cases) {
            RecordingServer().route("/") { ex, _ -> ex.reply(case.status, case.body, json, case.headers) }.start().use { server ->
                assertEquals("status ${case.status} body <${case.body}>", case.expected, claim(server.baseUrl))
            }
        }
    }

    @Test
    fun `a claim never follows a redirect`() = runBlocking {
        RecordingServer().route("/") { ex, _ -> ex.reply(302, ByteArray(0), headers = mapOf("Location" to "/elsewhere")) }.start().use { server ->
            assertEquals(RelayPairOutcome.NotARelay, claim(server.baseUrl))
            assertEquals("a followed 302 would have turned the POST into a GET", 1, server.seen.size)
            assertEquals("POST", server.seen.single().method)
        }
    }

    @Test
    fun `a claim keeps the deployment prefix the relay lives under`() = runBlocking {
        RecordingServer().route("/") { ex, _ -> ex.reply(200, paired, json) }.start().use { server ->
            assertTrue(claim(server.baseUrl + "/team%20one/dsh/") is RelayPairOutcome.Paired)
            assertEquals("/team%20one/dsh/relay/pair", server.seen.single().rawPath)
        }
    }

    @Test
    fun `a claim that cannot reach the relay says why`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val outcome = claim("http://127.0.0.1:$closedPort") as RelayPairOutcome.Unreachable
        assertEquals(TransportFailure.REFUSED, outcome.kind)

        val unresolved = claim("http://dsh-no-such-host.invalid:3443") as RelayPairOutcome.Unreachable
        assertEquals(TransportFailure.DNS, unresolved.kind)
    }

    @Test
    fun `an address that is not a url is reported without a request`() = runBlocking {
        for (bad in listOf("", "   ", "not a url", "ftp://host", "http://user:pw@host")) {
            val outcome = claim(bad)
            assertEquals("<$bad>", RelayPairOutcome.Unreachable(TransportFailure.DNS, "not a usable relay address"), outcome)
        }
    }

    // ---------------------------------------------------------------- locate and health

    @Test
    fun `a relay at the address is found, and health is true only for that`() = runBlocking {
        RecordingServer().route("/") { ex, _ -> ex.reply(200, healthy, json) }.start().use { server ->
            assertEquals(RelayOrigin.Here(server.baseUrl, false), RelayPairing.locate(server.baseUrl, kind.plain()))
            assertEquals(RelayOrigin.Here(server.baseUrl, false), RelayPairing.locate(server.baseUrl + "/", kind.plain()))
            assertTrue(RelayPairing.health(server.baseUrl, kind.plain()))
            val seen = server.seen.first()
            assertEquals("GET", seen.method)
            assertEquals("/relay/health", seen.rawPath)
            assertEquals(json, seen.header("accept"))
        }
    }

    @Test
    fun `the deployment prefix is part of the origin`() = runBlocking {
        RecordingServer().route("/") { ex, _ -> ex.reply(200, healthy, json) }.start().use { server ->
            val located = RelayPairing.locate(server.baseUrl + "/team%20one/dsh/", kind.plain())
            assertEquals(RelayOrigin.Here(server.baseUrl + "/team%20one/dsh", false), located)
            assertEquals("/team%20one/dsh/relay/health", server.seen.single().rawPath)
        }
    }

    @Test
    fun `something that is not a relay is nothing`() = runBlocking {
        val answers = listOf(
            200 to """{"service":"something-else"}""",
            200 to "<html>a harness</html>",
            200 to "",
            404 to "not here",
            500 to "boom",
        )
        for ((status, body) in answers) {
            RecordingServer().route("/") { ex, _ -> ex.reply(status, body, json) }.start().use { server ->
                assertEquals("$status <$body>", RelayOrigin.None, RelayPairing.locate(server.baseUrl, kind.plain()))
                assertEquals(false, RelayPairing.health(server.baseUrl, kind.plain()))
            }
        }
    }

    @Test
    fun `a relay that refuses the address it was reached by is still reported`() = runBlocking {
        RecordingServer().route("/") { ex, _ -> ex.reply(403, "Forbidden", "text/plain") }.start().use { server ->
            assertEquals(RelayOrigin.Untrusted(server.baseUrl + "/dsh", false), RelayPairing.locate(server.baseUrl + "/dsh/", kind.plain()))
        }
    }

    @Test
    fun `a redirect to the relay's own health path is read and not followed`() = runBlocking {
        for (location in listOf("/other/dsh/relay/health", "other/relay/health", "../x/relay/health")) {
            RecordingServer().route("/") { ex, _ -> ex.reply(302, ByteArray(0), headers = mapOf("Location" to location)) }.start().use { server ->
                val located = RelayPairing.locate(server.baseUrl + "/dsh", kind.plain()) as RelayOrigin.Redirected
                val resolved = parseWireUrl(server.baseUrl + "/dsh/relay/health")!!.resolveReference(location)!!
                assertEquals("Location <$location>", server.baseUrl + resolved.encodedPath.removeSuffix("/relay/health").trimEnd('/'), located.origin)
                assertEquals(false, located.secure)
                assertEquals("the redirect must not be chased", 1, server.seen.size)
            }
        }
    }

    @Test
    fun `an absolute redirect is read, and any other redirect is nothing`() = runBlocking {
        RecordingServer().route("/") { ex, _ ->
            ex.reply(301, ByteArray(0), headers = mapOf("Location" to "https://relay.example:8443/dsh/relay/health"))
        }.start().use { server ->
            assertEquals(RelayOrigin.Redirected("https://relay.example:8443/dsh", true), RelayPairing.locate(server.baseUrl, kind.plain()))
        }
        for (location in listOf("/login", "https://relay.example/", "ftp://relay.example/relay/health", "http://user:pw@relay.example/relay/health")) {
            RecordingServer().route("/") { ex, _ -> ex.reply(302, ByteArray(0), headers = mapOf("Location" to location)) }.start().use { server ->
                assertEquals("Location <$location>", RelayOrigin.None, RelayPairing.locate(server.baseUrl, kind.plain()))
                assertEquals(1, server.seen.size)
            }
        }
        RecordingServer().route("/") { ex, _ -> ex.reply(302, ByteArray(0)) }.start().use { server ->
            assertEquals("a redirect with no Location", RelayOrigin.None, RelayPairing.locate(server.baseUrl, kind.plain()))
        }
    }

    @Test
    fun `an address with nothing listening is nothing, and so is a bad one`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        assertEquals(RelayOrigin.None, RelayPairing.locate("http://127.0.0.1:$closedPort", kind.plain()))
        assertEquals(false, RelayPairing.health("http://127.0.0.1:$closedPort", kind.plain()))
        assertEquals(RelayOrigin.None, RelayPairing.locate("not a url", kind.plain()))
    }

    // ---------------------------------------------------------------- the payload

    @Test
    fun `candidates and payload checks use the platform URL parser`() {
        assertEquals(
            // The entries come back as written; the parser only decides which are usable and which repeat.
            listOf("https://192.168.1.122:3443", "http://10.0.0.5:3080/dsh/"),
            RelayPairing.candidates(
                "https://192.168.1.122:3443",
                listOf("https://192.168.1.122:3443/", "192.168.1.140:3443", "ftp://x", "http://10.0.0.5:3080/dsh/"),
            ),
        )
        val valid = """{"v":1,"kind":"dsh-relay-pair","url":"https://192.168.1.5:3443","code":"1","expiresAt":1}"""
        assertTrue(RelayPairing.parsePayload(valid) is PairingPayloadResult.Valid)
        assertEquals(
            PairingPayloadResult.NotAPairingCode,
            RelayPairing.parsePayload(valid.replace("https://192.168.1.5:3443", "192.168.1.5:3443")),
        )
    }
}
