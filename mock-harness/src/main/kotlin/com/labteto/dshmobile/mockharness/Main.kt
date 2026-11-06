package com.labteto.dshmobile.mockharness

import kotlinx.coroutines.runBlocking

/**
 * CLI entry point for the mock harness.
 *
 * Usage: `mock-harness [--port N] [--trusted-host HOST ...] [--viewer-fixtures]`
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
