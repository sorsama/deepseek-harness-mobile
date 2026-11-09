package com.labteto.dshmobile.core.wire

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private fun HttpUrl.toWireUrl(): WireUrl = WireUrl(
    scheme = scheme,
    host = host,
    port = port,
    encodedPath = encodedPath,
    hasCredentials = username.isNotEmpty() || password.isNotEmpty(),
    text = toString(),
    platform = this,
)

/** The `HttpUrl` behind a [WireUrl] this file made; one that did not come from here is parsed. */
private val WireUrl.http: HttpUrl get() = platform as? HttpUrl ?: text.toHttpUrl()

internal actual fun parseWireUrl(text: String): WireUrl? = text.toHttpUrlOrNull()?.toWireUrl()

internal actual fun WireUrl.resolveReference(reference: String): WireUrl? = http.resolve(reference)?.toWireUrl()

internal actual fun WireUrl.withQueryParameter(name: String, value: String): WireUrl =
    http.newBuilder().addQueryParameter(name, value).build().toWireUrl()

internal actual fun resolveHarnessWireUrl(baseUrl: String, route: String): WireUrl =
    resolveHarnessUrl(baseUrl, route).toWireUrl()
