package com.labteto.dshmobile.ui.screens.pair

import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.RelayIdentity
import com.labteto.dshmobile.core.wire.ObservedKey
import com.labteto.dshmobile.core.wire.PlainHttp
import com.labteto.dshmobile.core.wire.PlainHttpRequest
import com.labteto.dshmobile.core.wire.PlainHttpResponse
import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertFalse
import com.labteto.dshmobile.testing.assertNull
import com.labteto.dshmobile.testing.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * The Pair screen's decisions, driven through fake ports and a scripted relay: what `PairController` does
 * with a scanned code, a typed one, a refusal, a lockout, an origin that does not answer, and a relay that
 * redirects. It is the logic the Android `PairViewModel` used to hold, run on the JVM and on an iOS
 * simulator by the same file, so the two apps cannot decide differently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairControllerTest {

    /** A relay that answers `/relay/health` and `/relay/pair` as [claim] and [health] script it. */
    private class ScriptedRelay : PlainHttp {
        val requests = mutableListOf<PlainHttpRequest>()
        var health: (String) -> PlainHttpResponse = { PlainHttpResponse(200, """{"service":"dsh-relay","ok":true}""") }
        var claim: (String) -> PlainHttpResponse = { PlainHttpResponse(200, PAIRED_BODY) }

        override suspend fun execute(request: PlainHttpRequest): PlainHttpResponse {
            requests += request
            return if (request.url.endsWith("/relay/health")) health(request.url) else claim(request.url)
        }
    }

    private class FakePorts(val relay: ScriptedRelay) : PairPorts {
        override val deviceName = "Test phone"
        var now = 1_000L
        override fun nowMillis() = now

        /** Each client the controller asked for: the pin it was to enforce, and whether it was to learn the key. */
        val clients = mutableListOf<Pair<String?, Boolean>>()
        val events = mutableListOf<String>()
        var remembered: RelayIdentity? = null
        var rememberedAt: String? = null

        override suspend fun <T> withHttp(fingerprint: String?, observed: ObservedKey?, block: suspend (PlainHttp) -> T): T {
            clients += fingerprint to (observed != null)
            return block(relay)
        }

        override suspend fun rememberRelay(host: String, port: Int, basePath: String, relay: RelayIdentity): HostConfig {
            events += "remember"
            remembered = relay
            rememberedAt = "$host:$port$basePath"
            return HostConfig(
                id = "id-$host-$port",
                name = host,
                host = host,
                port = port,
                basePath = basePath,
                useTls = relay.useTls,
                relayFingerprint = relay.fingerprint,
                relayDeviceId = relay.deviceId,
                relayTokenExpiresAt = relay.tokenExpiresAt,
            )
        }

        override suspend fun storeToken(hostId: String, token: String) {
            events += "token:$hostId:$token"
        }

        override suspend fun connect(config: HostConfig) {
            events += "connect:${config.id}"
        }
    }

    private class Rig(scope: TestScope) {
        val relay = ScriptedRelay()
        val ports = FakePorts(relay)
        val controller = PairController(scope.backgroundScope, ports)
        val state get() = controller.state.value
        val paths get() = relay.requests.map { it.method + " " + it.url }
    }

    private fun TestScope.rig() = Rig(this)

    private fun payload(
        url: String = "https://192.168.1.5:3443",
        code: String = "48213977",
        fingerprint: String? = "scanned-pin==",
        expiresAt: Long = 2_000,
        urls: List<String>? = null,
        version: Int = 1,
        kind: String = "dsh-relay-pair",
    ): String = buildString {
        append("""{"v":$version,"kind":"$kind","url":"$url","code":"$code","expiresAt":$expiresAt""")
        if (fingerprint != null) append(""","fingerprint":"$fingerprint"""")
        if (urls != null) append(""","urls":[${urls.joinToString(",") { "\"$it\"" }}]""")
        append("}")
    }

    @Test
    fun theFormStartsWithThePlatformsDeviceName() = runTest {
        val rig = rig()
        assertEquals("Test phone", rig.state.deviceName)
        assertEquals(PairStage.Idle, rig.state.stage)
        assertNull(rig.state.failure)
    }

    @Test
    fun aCodeIsDigitsOnlyAndTypingClearsAFailure() = runTest {
        val rig = rig()
        rig.controller.submit()
        assertEquals(PairFailure.InvalidUrl, rig.state.failure)
        rig.controller.setCode("48a2-13 9")
        assertEquals("482139", rig.state.code)
        assertNull(rig.state.failure)
    }

    @Test
    fun prefillDoesNotOverwriteWhatWasTyped() = runTest {
        val rig = rig()
        rig.controller.prefill("https://relay.example:3443")
        assertEquals("https://relay.example:3443", rig.state.url)
        rig.controller.setUrl("https://mine.example")
        rig.controller.prefill("https://other.example")
        assertEquals("https://mine.example", rig.state.url)
    }

    @Test
    fun anAddressThatIsNotAUrlAndAMissingCodeSendNothing() = runTest {
        val rig = rig()
        rig.controller.setUrl("not a url")
        rig.controller.setCode("12345678")
        rig.controller.submit()
        assertEquals(PairFailure.InvalidUrl, rig.state.failure)

        rig.controller.setUrl("https://192.168.1.5:3443")
        rig.controller.setCode("")
        rig.controller.submit()
        assertEquals(PairFailure.InvalidCode, rig.state.failure)
        runCurrent()
        assertTrue(rig.relay.requests.isEmpty())
    }

    @Test
    fun aTypedHttpsAddressLearnsTheKeyAndPinsTheOneTheRelayRepeats() = runTest {
        val rig = rig()
        rig.controller.setUrl("https://192.168.1.5:3443")
        rig.controller.setCode("48213977")
        assertEquals(KeyProvenance.TrustedOnFirstUse, rig.state.provenance())
        rig.controller.submit()
        runCurrent()

        // The probe is spoken to by the ordinary client; the claim by one that learns the key.
        assertEquals(listOf<Pair<String?, Boolean>>(null to false, null to true), rig.ports.clients)
        assertEquals(
            listOf("GET https://192.168.1.5:3443/relay/health", "POST https://192.168.1.5:3443/relay/pair"),
            rig.paths,
        )
        val identity = assertNotNull(rig.ports.remembered)
        assertEquals("pinned-by-relay==", identity.fingerprint)
        assertTrue(identity.useTls)
        assertEquals("d1", identity.deviceId)
        assertEquals("192.168.1.5:3443", rig.ports.rememberedAt)
    }

    @Test
    fun enrolmentRemembersFirstStoresTheTokenThenConnectsAndSignalsTheScreen() = runTest {
        val rig = rig()
        rig.controller.setUrl("https://192.168.1.5:3443")
        rig.controller.setCode("48213977")
        rig.controller.submit()
        assertEquals(PairStage.Claiming, rig.state.stage)
        assertTrue(rig.state.busy)
        runCurrent()

        assertEquals(listOf("remember", "token:id-192.168.1.5-3443:device-token", "connect:id-192.168.1.5-3443"), rig.ports.events)
        assertEquals(PairStage.Paired, rig.state.stage)
        assertEquals("id-192.168.1.5-3443", rig.state.paired?.id)

        // The screen consumes the signal, so pairing opened later does not close itself.
        rig.controller.acknowledgePaired()
        assertEquals(PairStage.Idle, rig.state.stage)
        assertNull(rig.state.paired)
    }

    @Test
    fun aScannedCodePinsTheKeyItCarriesBeforeAnythingIsSent() = runTest {
        val rig = rig()
        rig.relay.claim = { PlainHttpResponse(200, """{"deviceId":"d2","token":"tok","expiresAt":9}""") }
        rig.controller.onScanned(payload())
        assertEquals(KeyProvenance.Verified, rig.state.provenance())
        assertEquals("48213977", rig.state.code)
        assertEquals("https://192.168.1.5:3443", rig.state.url)
        runCurrent()

        // The claim's client enforces the scanned key and does not learn one.
        assertEquals(listOf<Pair<String?, Boolean>>(null to false, "scanned-pin==" to false), rig.ports.clients)
        // The answer carried no key of its own, so the one the QR named is what is stored.
        assertEquals("scanned-pin==", rig.ports.remembered?.fingerprint)
        assertEquals("d2", rig.ports.remembered?.deviceId)
    }

    @Test
    fun aPlaintextRelayIsReachedWithNeitherAPinNorALearningClient() = runTest {
        val rig = rig()
        rig.controller.setUrl("http://192.168.1.5:3444")
        rig.controller.setCode("48213977")
        assertEquals(KeyProvenance.Plaintext, rig.state.provenance())
        rig.controller.submit()
        runCurrent()

        assertEquals(listOf<Pair<String?, Boolean>>(null to false, null to false), rig.ports.clients)
        assertFalse(rig.ports.remembered?.useTls == true)
    }

    @Test
    fun aCodeThatHasAlreadyExpiredIsNotSent() = runTest {
        val rig = rig()
        rig.ports.now = 5_000
        rig.controller.onScanned(payload(expiresAt = 2_000))
        runCurrent()
        assertEquals(PairFailure.Expired, rig.state.failure)
        assertTrue(rig.relay.requests.isEmpty())
        assertEquals(PairStage.Idle, rig.state.stage)
    }

    @Test
    fun somethingThatIsNotAPairingCodeOrIsFromAnewerRelayIsToldApart() = runTest {
        val rig = rig()
        rig.controller.onScanned("https://example.com/not-a-code")
        assertEquals(PairFailure.NotAPairingCode, rig.state.failure)
        rig.controller.onScanned(payload(kind = "something-else"))
        assertEquals(PairFailure.NotAPairingCode, rig.state.failure)
        rig.controller.onScanned(payload(version = 7))
        assertEquals(PairFailure.TooNew(7), rig.state.failure)
        runCurrent()
        assertTrue(rig.relay.requests.isEmpty())
    }

    @Test
    fun aRefusedCodeIsReportedAndNothingIsStored() = runTest {
        val rig = rig()
        rig.relay.claim = { PlainHttpResponse(403, """{"error":"pairing-failed","message":"no"}""") }
        rig.controller.setUrl("http://192.168.1.5:3444")
        rig.controller.setCode("00000000")
        rig.controller.submit()
        runCurrent()

        assertEquals(PairFailure.Rejected, rig.state.failure)
        assertEquals(PairStage.Idle, rig.state.stage)
        assertTrue(rig.ports.events.isEmpty())
    }

    @Test
    fun aRelayThatRefusesTheAddressIsNotTheSameAsOneThatRefusesTheCode() = runTest {
        val rig = rig()
        rig.relay.claim = { PlainHttpResponse(403, "forbidden") }
        rig.controller.setUrl("http://10.0.2.2:3444")
        rig.controller.setCode("48213977")
        rig.controller.submit()
        runCurrent()
        assertEquals(PairFailure.HostRefused("10.0.2.2:3444"), rig.state.failure)
    }

    @Test
    fun aLockoutReportsTheWaitAndFallsBackWhenTheRelayDoesNotSayHowLong() = runTest {
        val rig = rig()
        rig.relay.claim = { PlainHttpResponse(429, "{}", mapOf("Retry-After" to listOf("7"))) }
        rig.controller.setUrl("http://192.168.1.5:3444")
        rig.controller.setCode("42900000")
        rig.controller.submit()
        runCurrent()
        assertEquals(PairFailure.RateLimited(7), rig.state.failure)

        rig.relay.claim = { PlainHttpResponse(429, "{}") }
        rig.controller.submit()
        runCurrent()
        assertEquals(PairFailure.RateLimited(60), rig.state.failure)
    }

    @Test
    fun somethingThatIsNotARelayIsNamedByItsAuthority() = runTest {
        val rig = rig()
        rig.relay.claim = { PlainHttpResponse(404, "not found") }
        rig.controller.setUrl("http://192.168.1.5:3080")
        rig.controller.setCode("48213977")
        rig.controller.submit()
        runCurrent()
        assertEquals(PairFailure.NotARelay("192.168.1.5:3080"), rig.state.failure)
    }

    @Test
    fun nothingAnsweringIsUnreachable() = runTest {
        val rig = rig()
        rig.relay.health = { throw IOException("refused") }
        rig.relay.claim = { throw IOException("refused") }
        rig.controller.setUrl("http://192.168.1.5:3444")
        rig.controller.setCode("48213977")
        rig.controller.submit()
        runCurrent()
        assertEquals(PairFailure.Unreachable("192.168.1.5:3444"), rig.state.failure)
        assertTrue(rig.ports.events.isEmpty())
    }

    @Test
    fun anOriginThatDoesNotAnswerFailsOverToTheNextOneTheInvitationNames() = runTest {
        val rig = rig()
        rig.relay.health = { url -> if ("192.168.1.5" in url) throw IOException("unreachable") else PlainHttpResponse(200, """{"service":"dsh-relay","ok":true}""") }
        rig.relay.claim = { url -> if ("192.168.1.5" in url) throw IOException("unreachable") else PlainHttpResponse(200, PAIRED_BODY) }
        rig.controller.onScanned(payload(fingerprint = null, url = "http://192.168.1.5:3444", urls = listOf("http://10.0.0.9:3444")))
        runCurrent()

        assertEquals("10.0.0.9:3444", rig.ports.rememberedAt)
        assertEquals(PairStage.Paired, rig.state.stage)
    }

    @Test
    fun aCodeTheRelayRefusedIsNotTriedAtAnotherOrigin() = runTest {
        val rig = rig()
        rig.relay.claim = { PlainHttpResponse(403, """{"error":"pairing-failed"}""") }
        rig.controller.onScanned(payload(fingerprint = null, url = "http://192.168.1.5:3444", urls = listOf("http://10.0.0.9:3444")))
        runCurrent()

        assertEquals(PairFailure.Rejected, rig.state.failure)
        // One claim only: asking a second address would burn a code that works once.
        assertEquals(1, rig.relay.requests.count { it.method == "POST" })
    }

    @Test
    fun aHarnessThatPointsAtItsRelayIsPairedAtTheRelaysOwnListener() = runTest {
        val rig = rig()
        rig.relay.health = { PlainHttpResponse(302, "", mapOf("Location" to listOf("http://192.168.1.5:3444/relay/health"))) }
        rig.controller.setUrl("http://192.168.1.5:3080")
        rig.controller.setCode("48213977")
        rig.controller.submit()
        runCurrent()

        assertTrue("POST http://192.168.1.5:3444/relay/pair" in rig.paths)
        assertEquals("192.168.1.5:3444", rig.ports.rememberedAt)
    }

    @Test
    fun theDeviceNameTheFormShowsIsTheOneSentAndABlankOneFallsBack() = runTest {
        val rig = rig()
        rig.controller.setDeviceName("Sam's iPhone")
        rig.controller.setUrl("http://192.168.1.5:3444")
        rig.controller.setCode("48213977")
        rig.controller.submit()
        runCurrent()
        assertTrue("Sam's iPhone" in assertNotNull(rig.relay.requests.last().body))

        rig.controller.setDeviceName("  ")
        rig.controller.submit()
        runCurrent()
        assertTrue("Test phone" in assertNotNull(rig.relay.requests.last().body))
    }

    private companion object {
        const val PAIRED_BODY = """{"deviceId":"d1","token":"device-token","expiresAt":1900000000000,"fingerprint":"pinned-by-relay=="}"""
    }
}
