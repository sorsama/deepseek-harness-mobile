package com.labteto.dshmobile.mockharness

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

/**
 * CLI entry point for the mock harness.
 *
 * Usage: `mock-harness [--port N] [--trusted-host HOST ...] [--viewer-fixtures]`, or
 * `mock-harness --ci-fixtures` for the fixed-port fixture servers (three harnesses, two pairing
 * relays one of which speaks TLS, and WebSocket servers for keep-alive and dropped links) the iOS
 * simulator job runs its tests and scenarios against (see [CiFixtures]); that mode serves until the
 * process is killed.
 *
 * `--viewer-fixtures` serves sample files (CSV, JSON, a notebook, a patch, an SVG, a PNG, a zip,
 * binary data) through the workspace file contract, to try the app's file viewer by hand.
 *
 * Starts a [MockHarness] bound to 127.0.0.1 (port 0 picks an OS-assigned port), prints the
 * listening URL, and blocks until Enter is pressed.
 */
fun main(args: Array<String>) {
    var port = 0
    var viewerFixtures = false
    var ciFixtures = false
    val trustedHosts = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--port" -> {
                port = args.getOrNull(index + 1)?.toIntOrNull()
                    ?: error("--port requires a numeric value")
                index += 2
            }
            "--trusted-host" -> {
                trustedHosts += args.getOrNull(index + 1) ?: error("--trusted-host requires a value")
                index += 2
            }
            "--ci-fixtures" -> {
                ciFixtures = true
                index += 1
            }
            "--viewer-fixtures" -> {
                viewerFixtures = true
                index += 1
            }
            else -> {
                System.err.println("Unknown argument: ${args[index]}")
                index += 1
            }
        }
    }
    if (ciFixtures) {
        // The standalone fixture run the CI simulator job talks to. It has no terminal to wait on,
        // so it serves until the process is killed.
        runBlocking {
            val servers = CiFixtures.start(
                plainPort = CiFixtures.PLAIN_PORT,
                sessionPort = CiFixtures.SESSION_PORT,
                relayPort = CiFixtures.RELAY_PORT,
                keepAlivePort = CiFixtures.KEEPALIVE_PORT,
                dropPort = CiFixtures.DROP_PORT,
                pairPort = CiFixtures.PAIR_PORT,
                pairTlsPort = CiFixtures.PAIR_TLS_PORT,
                withPairTls = true,
            )
            println(
                "ci fixtures listening: plain=${servers.plainUrl} session=${servers.sessionUrl} " +
                    "relay=${servers.relayUrl} keepalive=${servers.keepAliveUrl} drop=${servers.dropUrl} " +
                    "pair=${servers.pairUrl} pairTls=${servers.pairTlsUrl}",
            )
            // The pairing relays: what a script needs to build a pairing QR for them. The code is the one
            // the operator would read off the relay's pairing page; the fingerprint is the TLS relay's key.
            println(
                "ci pairing: code=${CiFixtures.pairRelayMode.pairingCode} rateLimitedCode=${CiFixtures.RATE_LIMITED_CODE} " +
                    "plain=${servers.pairUrl} tls=${servers.pairTlsUrl} fingerprint=${servers.pairTlsFingerprint}",
            )
            awaitCancellation()
        }
        return
    }
    runBlocking {
        val harness = MockHarness(trustedHosts = trustedHosts, port = port)
        // The panel contracts (files, terminals) are only served when a scenario is attached.
        if (viewerFixtures) PanelScenario(harness).addViewerFixtures()
        val boundPort = harness.start()
        println("mock harness listening on http://127.0.0.1:$boundPort")
        readlnOrNull()
        harness.stop()
    }
}
