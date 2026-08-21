package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.ObservedKey
import com.labteto.dshmobile.core.wire.OkHttpRpcTransport
import com.labteto.dshmobile.core.wire.RelayOrigin
import com.labteto.dshmobile.core.wire.RelayPairOutcome
import com.labteto.dshmobile.core.wire.RelayPairing
import com.labteto.dshmobile.core.wire.RelayTls
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.ServerRequest
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.TransportFailures
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.WsDownlink
import com.labteto.dshmobile.core.wire.WsDownlinkSink
import com.labteto.dshmobile.mockharness.MockHarness
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The client, against the real relay.
 *
 * Every other relay test in this repo runs against [MockHarness] — a stand-in written from reading
 * the plugin. That is the one thing those tests cannot check: a misreading would be baked into both
 * the client and the mock, and every assertion would still pass. This drives the actual
 * `dsh-relay` implementation, so the pairing shape, the header it wants, the status it answers
 * with, and the key it publishes are checked against the code that will really be on the other end.
 *
 * Skipped unless the plugin's sources are on this machine and Node can run them, so CI and anyone
 * without the relay checked out are unaffected. Set `DSH_RELAY_SRC` to run it against a
 * different checkout.
 *
 * The relay is deliberately configured with `compat.addressGrants: false`. That bridge accepts a
 * request purely because of the address it came from, and leaving it on would let every request
 * here through whether or not the bearer token was ever attached — which is the whole thing under
 * test.
 */
class RelayConformanceTest {

    private lateinit var upstream: MockHarness
    private var upstreamPort: Int = -1
    private var relay: Process? = null
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        assumeTrue("relay sources not present", File(pluginRoot(), "src/server.ts").isFile)
        assumeTrue("node not on PATH", node() != null)
        runBlocking {
            upstream = MockHarness(port = 0)
            upstreamPort = upstream.start()
        }
    }

    @After
    fun tearDown() {
        relay?.destroyForcibly()
        if (::upstream.isInitialized) runBlocking { upstream.stop() }
    }

    /**
     * The whole flow, plaintext: locate, claim, then talk to the harness behind it.
     *
     * One test rather than five because the steps are not independent — the token only exists
     * because the claim succeeded, and a claim only reaches the relay because it was located.
     * Splitting them would mean standing a relay up four more times to re-derive the same state.
     */
    @Test
    fun `a real relay pairs this client and then carries its traffic`() = runBlocking {
        val started = startRelay(tls = "off")
        val origin = "http://${started.host}:${started.port}"

        // 1. It identifies itself where it actually is.
        assertEquals(RelayOrigin.Here(origin, false), RelayPairing.locate(origin, http))

        // 2. The claim mints a credential.
        val paired = RelayPairing.claim(origin, started.code, "Conformance", http)
        val token = (paired as RelayPairOutcome.Paired).response.token
        assertTrue(token.isNotBlank())

        // 3. Single use, as the relay documents. A replayed code is worthless once spent.
        assertEquals(
            RelayPairOutcome.Rejected,
            RelayPairing.claim(origin, started.code, "Conformance", http),
        )

        // 4. Unauthenticated, the relay refuses before the harness is ever reached — and with 403,
        //    never 401, which is what this client's whole diagnosis rests on.
        val anonymous = clientFor(origin, authorization = null)
        val refused = (anonymous.hostDescribe() as RpcResult.Err).error
        assertEquals(TransportFailure.TRUST_FENCE, TransportFailures.of(refused))
        assertEquals(403, TransportFailures.statusOf(refused))

        // 5. With the bearer, the unary call reaches the harness behind the proxy.
        val authorized = clientFor(origin, "Bearer $token")
        assertTrue(authorized.hostDescribe() is RpcResult.Ok)

        // 6. And both downlinks upgrade — the half that costs a whole connection generation when
        //    the header is missing, because the loop opens them together on a 3000ms budget.
        for (path in listOf("/api/events.mux", "/api/events.host")) {
            val sink = LatchSink()
            val socket = WsDownlink("$origin$path", http, sink, "Bearer $token")
            socket.start()
            assertTrue("$path did not open", sink.opened.await(10, TimeUnit.SECONDS))
            assertNull(sink.failure)
            socket.close()
        }

        // 7. Without it, the upgrade is refused at the handshake rather than opening and closing.
        val bare = LatchSink()
        val unauthorized = WsDownlink("$origin/api/events.mux", http, bare, authorization = null)
        unauthorized.start()
        assertTrue(bare.closed.await(10, TimeUnit.SECONDS))
        assertEquals(TransportFailure.TRUST_FENCE, TransportFailures.classify(bare.failure))
        unauthorized.close()
    }

    /**
     * The relay's fence, which is what "untrusted-host" in the harness log actually is.
     *
     * It runs before every route, so even the unauthenticated liveness probe gets it. An address
     * the relay does not know itself by — an emulator's host alias, a name it was never told —
     * answers 403 to everything, and reading that as "nothing there" is how a running relay looks
     * like a missing one.
     */
    @Test
    fun `an address the relay does not answer to is reported, not hidden`() = runBlocking {
        val started = startRelay(tls = "off")
        val origin = "http://${started.host}:${started.port}"
        // Reached by an authority the relay was never told about. Sending it explicitly is the only
        // way to reproduce from this machine what a phone produces by simply being on the LAN.
        val request = Request.Builder()
            .url("$origin${RelayPairing.HEALTH_PATH}")
            .header("Host", "10.0.2.2:${started.port}")
            .header("Accept", "application/json")
            .get()
            .build()
        val status = http.newCall(request).execute().use { it.code }
        assertEquals(403, status)
        // Which is exactly the outcome the discovery card is built on.
        assertEquals(RelayOrigin.Untrusted(origin, false), RelayPairing.locate(origin, fencedClient()))
    }

    /**
     * TLS, pinned to the key the relay publishes.
     *
     * The pin is computed here and the fingerprint comes from the relay: if this client's SPKI
     * encoding were off by so much as a base64 variant, the two would never match and nothing else
     * would say so — a pinned client simply fails to connect.
     */
    @Test
    fun `a self-signed relay is verified by the key it publishes`() = runBlocking {
        val started = startRelay(tls = "self-signed")
        val fingerprint = assertNotNull("relay published no pin", started.fingerprint).let { started.fingerprint!! }
        val origin = "https://${started.host}:${started.port}"

        // What the relay says its key is, and what this client computes from the served
        // certificate, have to be the same string.
        val observed = ObservedKey()
        assertTrue(RelayPairing.health(origin, RelayTls.trustOnFirstUseClient(http, observed)))
        assertEquals(fingerprint, observed.pin)

        // Pinned to it: the claim succeeds over TLS the platform store would have rejected.
        val pinned = RelayTls.pinnedClient(http, fingerprint)
        val paired = RelayPairing.claim(origin, started.code, "Conformance TLS", pinned)
        assertTrue("claim over pinned TLS failed: $paired", paired is RelayPairOutcome.Paired)
        // The relay repeats its pin in the answer, which is what makes a typed pairing pinnable.
        assertEquals(fingerprint, (paired as RelayPairOutcome.Paired).response.fingerprint)

        // Pinned to anything else: refused, and reported as a changed certificate rather than as a
        // dead host, because those need opposite instructions.
        val wrong = RelayTls.pinnedClient(http, "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        val outcome = RelayPairing.claim(origin, started.code, "Impostor", wrong)
        val unreachable = outcome as RelayPairOutcome.Unreachable
        assertEquals(TransportFailure.CERTIFICATE_PIN, unreachable.kind)
    }

    // ---- harness ---------------------------------------------------------------------------

    /** What the driver reports once the relay is listening. */
    @Serializable
    private data class Started(
        /** A LAN address of this machine; loopback would be waved through as the operator. */
        val host: String? = null,
        val port: Int,
        val code: String,
        val expiresAt: Long,
        val fingerprint: String? = null,
    )

    /**
     * Boot the real relay in front of [upstream].
     *
     * Node's strip-only TypeScript mode cannot load these sources — they use parameter properties —
     * so the full transform is requested explicitly.
     */
    private fun startRelay(tls: String): Started {
        // Written to a temp file rather than into the plugin checkout, which is somebody else's
        // repository; the sources are reached by absolute URL instead.
        val driver = File.createTempFile("dsh-relay-conformance", ".mjs").also {
            it.deleteOnExit()
            it.writeText(DRIVER)
        }
        val process = ProcessBuilder(
            node()!!.absolutePath,
            "--experimental-transform-types",
            driver.absolutePath,
            pluginRoot().toURI().toString().trimEnd('/'),
            upstreamPort.toString(),
            tls,
        ).redirectErrorStream(false).start()
        relay = process
        val line = process.inputStream.bufferedReader().let(BufferedReader::readLine)
            ?: throw AssertionError(
                "relay driver produced no output; stderr: ${process.errorStream.bufferedReader().readText()}",
            )
        val started = WireJson.decodeFromString(Started.serializer(), line)
        // Nothing here can be checked from a machine with no LAN address: every request would be
        // loopback, which the relay answers as the operator without reading a credential.
        assumeTrue("no non-loopback address on this machine", started.host != null)
        return started
    }

    private fun clientFor(baseUrl: String, authorization: String?) = DshApiClient(
        transport = OkHttpRpcTransport(
            baseUrl = baseUrl,
            client = http,
            connectTimeoutMs = 10_000,
            readTimeoutMs = 10_000,
            authorization = authorization,
        ),
        wsFactory = { path, sink -> WsDownlink("$baseUrl$path", http, sink, authorization) },
    )

    /** A client that reaches the relay by an authority it was never told about. */
    private fun fencedClient(): OkHttpClient = http.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Host", "10.0.2.2").build())
        }
        .build()

    // An environment variable rather than a system property: Gradle does not forward `-D` to the
    // test JVM without extra wiring, so a property would have silently always been the default.
    private fun pluginRoot(): File = File(System.getenv(SRC_ENV) ?: DEFAULT_SRC)

    private fun node(): File? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .flatMap { listOf(File(it, "node.exe"), File(it, "node")) }
        .firstOrNull { it.isFile }

    private class LatchSink : WsDownlinkSink {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)

        @Volatile
        var failure: Throwable? = null

        override fun onFrame(frame: ServerRequest) = Unit

        override fun onOpen() {
            opened.countDown()
        }

        override fun onClosed(cause: Throwable?) {
            failure = cause
            closed.countDown()
        }
    }

    private companion object {
        const val SRC_ENV = "DSH_RELAY_SRC"
        const val DEFAULT_SRC = "D:/LabTeto/deepseek-harness-mobile-plugin"

        /**
         * Written beside the relay's own sources so its relative imports resolve, and so the
         * checkout stays the single source of truth for what is being tested.
         */
        val DRIVER = """
            import { mkdtemp, rm } from 'node:fs/promises'
            import { tmpdir } from 'node:os'
            import { join } from 'node:path'

            const [root, upstreamArg, tlsMode] = process.argv.slice(2)
            const load = (name) => import(root + '/src/' + name)

            const { Config } = await load('config.ts')
            const { RelayStore } = await load('state.ts')
            const { Authenticator } = await load('auth/index.ts')
            const { startListener } = await load('server.ts')
            const { loadCertificate, certificateSans } = await load('tls.ts')
            const { localAddresses } = await load('fence.ts')

            const upstreamPort = Number(upstreamArg)
            const dir = await mkdtemp(join(tmpdir(), 'dsh-relay-conformance-'))
            const store = await RelayStore.open(dir)
            const config = Config({
              stateDir: dir,
              port: 0,
              tls: tlsMode,
              mdns: false,
              compat: { addressGrants: false, addressGrantTtlMs: 86400000, plainPort: 0 },
            })
            const auth = new Authenticator(store, config)
            const material = await loadCertificate({
              mode: tlsMode,
              dir,
              certPath: config.tlsCertPath,
              keyPath: config.tlsKeyPath,
              sans: certificateSans(config.publicHostnames),
            })
            // Bound to every interface, and reported by a LAN address on purpose: the relay
            // treats a loopback caller as the operator and waves it straight through, so a
            // client tested over 127.0.0.1 would never present its token at all.
            const addresses = localAddresses()
            const relay = await startListener({
              runtime: {
                auth,
                config,
                target: { host: '127.0.0.1', port: upstreamPort, timeoutMs: 5000 },
                fingerprint: material?.record.fingerprint,
                log: (message) => console.error('[relay] ' + message),
              },
              bind: '0.0.0.0',
              port: 0,
              tls: material,
              authorities: ['127.0.0.1', 'localhost', ...addresses],
            })
            const code = auth.pairing.issue(config.pairingCodeLength, config.pairingWindowMs, Date.now())
            console.log(JSON.stringify({
              host: addresses[0] ?? null,
              port: relay.port,
              code: code.code,
              expiresAt: code.expiresAt,
              fingerprint: material?.record.fingerprint ?? null,
            }))
            const shutdown = async () => {
              await relay.close()
              auth.dispose()
              await rm(dir, { recursive: true, force: true })
              process.exit(0)
            }
            process.on('SIGTERM', shutdown)
            process.stdin.on('end', shutdown)
            process.stdin.resume()
        """.trimIndent()
    }
}
