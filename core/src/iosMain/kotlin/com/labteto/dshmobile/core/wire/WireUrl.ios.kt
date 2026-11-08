package com.labteto.dshmobile.core.wire

/** iOS actuals: no `HttpUrl` here, so `PortableUrls`, which `PortableUrlsTest` holds to it. */

internal actual fun parseWireUrl(text: String): WireUrl? = PortableUrls.parse(text)

internal actual fun WireUrl.resolveReference(reference: String): WireUrl? = PortableUrls.resolve(this, reference)

internal actual fun WireUrl.withQueryParameter(name: String, value: String): WireUrl =
    PortableUrls.withQueryParameter(this, name, value)

internal actual fun resolveHarnessWireUrl(baseUrl: String, route: String): WireUrl =
    PortableUrls.harness(baseUrl, route)
