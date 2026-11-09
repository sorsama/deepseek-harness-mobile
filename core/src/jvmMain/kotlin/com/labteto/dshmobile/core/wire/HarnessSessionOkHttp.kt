package com.labteto.dshmobile.core.wire

import okhttp3.OkHttpClient

/**
 * [HarnessSession.exchange] over an `OkHttpClient`: the form the Android app calls, with the client
 * it already shares. The exchange itself is common code (`HarnessSession`); this only wraps the
 * client in [OkHttpPlainHttp], which gives it the redirect and deadline settings the exchange needs.
 */
suspend fun HarnessSession.exchange(
    baseUrl: String,
    token: String,
    client: OkHttpClient,
    timeoutMs: Long = 10_000,
): SessionExchange = exchange(baseUrl, token, OkHttpPlainHttp(client), timeoutMs)
