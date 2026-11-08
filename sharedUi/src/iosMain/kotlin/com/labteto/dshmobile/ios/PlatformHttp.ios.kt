package com.labteto.dshmobile.ios

import com.labteto.dshmobile.core.wire.darwinHttpClient
import io.ktor.client.HttpClient

/**
 * The HTTP client one connect attempt runs on, and nothing else: the screen closes it when the
 * attempt ends. On iOS it is `darwinHttpClient()` from `:core`, the Darwin engine (`NSURLSession`).
 *
 * No pinning and no trust-on-first-use yet: this step connects to a harness directly, and the relay
 * pairing flow that needs those arrives with the full Connect screen.
 */
internal fun newHarnessHttpClient(): HttpClient = darwinHttpClient()
