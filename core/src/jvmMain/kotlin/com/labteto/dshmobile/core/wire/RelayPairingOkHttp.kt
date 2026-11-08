package com.labteto.dshmobile.core.wire

import okhttp3.OkHttpClient

/*
 * `RelayPairing`'s calls over an `OkHttpClient`: the form the Android app uses, with the client it
 * already has, including a pinned or trust-on-first-use one from `RelayTls`. The calls themselves
 * are common code in `RelayPairing`; these only wrap the client in `OkHttpPlainHttp`, which gives
 * each request the connect and read deadlines it needs and switches redirects off.
 */

/** [RelayPairing.claim] over [client]. */
suspend fun RelayPairing.claim(baseUrl: String, code: String, name: String, client: OkHttpClient): RelayPairOutcome =
    claim(baseUrl, code, name, OkHttpPlainHttp(client))

/** [RelayPairing.health] over [client]. */
suspend fun RelayPairing.health(baseUrl: String, client: OkHttpClient): Boolean =
    health(baseUrl, OkHttpPlainHttp(client))

/** [RelayPairing.locate] over [client]. */
suspend fun RelayPairing.locate(baseUrl: String, client: OkHttpClient): RelayOrigin =
    locate(baseUrl, OkHttpPlainHttp(client))
