package com.labteto.dshmobile.core.wire

import io.ktor.client.HttpClient

/**
 * The fixtures as a separate process, which the `ios-app` CI job starts before it runs the tests
 * (`mock-harness --ci-fixtures`, see `CiFixtures` in `:mock-harness`): a simulator shares the Mac's
 * network, so `127.0.0.1` is that process. The ports are compiled in because a Kotlin/Native test
 * has no other way to be told. If nothing is listening, every test fails with a refused connection,
 * which is the right answer to "you forgot to start the harness".
 *
 * `muxFenceVerdict` is the one platform-specific expectation: a mux upgrade rejected with 403 arrives
 * on Darwin as `NSURLErrorBadServerResponse`, so it classifies as "not a harness" (see "Transport
 * parity" in `docs/IOS.md`). If the simulator shows otherwise, that note and this value change.
 */
internal actual suspend fun openContractRig(): ContractRig = object : ContractRig {
    override val plainUrl = "http://127.0.0.1:3181"
    override val sessionUrl = "http://127.0.0.1:3182"
    override val relayUrl = "http://127.0.0.1:3183"
    override val deadUrl = "http://127.0.0.1:3189"
    override val muxFenceVerdict = TransportFailure.NOT_A_HARNESS
    override fun newClient(): HttpClient = darwinHttpClient()
}
