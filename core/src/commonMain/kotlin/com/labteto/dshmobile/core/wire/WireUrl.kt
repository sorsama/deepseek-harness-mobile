package com.labteto.dshmobile.core.wire

/**
 * An absolute `http(s)` URL as the connect flow (`HarnessSession`, `RelayPairing`) reads it: just
 * the parts it makes decisions on, plus the canonical text to send.
 *
 * What the parts are is OkHttp's `HttpUrl` vocabulary, because that is what this code was written
 * against: [host] is bare (IPv6 literals unbracketed, lower case, punycode), [port] is always filled
 * in (the scheme's default when the text gave none), and [encodedPath] is the canonical path
 * (`/` for none, dot segments resolved).
 */
internal class WireUrl(
    val scheme: String,
    val host: String,
    val port: Int,
    val encodedPath: String,
    /** Whether the URL carried a user name or password. */
    val hasCredentials: Boolean,
    /** The canonical URL, as it is requested. */
    val text: String,
    /** The platform's own URL object when there is one, so a platform actual never re-parses [text]. */
    val platform: Any? = null,
) {
    override fun toString(): String = text
}

/*
 * The URL operations the connect flow needs, each done by the platform's own URL parser:
 *
 * - On the JVM that is OkHttp's `HttpUrl`, which is what this code has always used. The Android
 *   app's URL handling is therefore byte-for-byte what it was before it moved to common code.
 * - On iOS, which has no `HttpUrl`, it is `PortableUrls`, written to give the same answers and held
 *   to them by `PortableUrlsTest` over the inputs the app and its tests use. The differences are
 *   listed there and in `docs/IOS.md`.
 *
 * `expect`/`actual` rather than one portable implementation because the Android app is the product:
 * swapping its URL parser for a new one is a risk to take on iOS, not on a shipping app.
 */

/** `text.toHttpUrlOrNull()`: the parsed URL, or null when it is not an absolute `http(s)` URL. */
internal expect fun parseWireUrl(text: String): WireUrl?

/** `HttpUrl.resolve`: [reference] (absolute, or relative to this URL) as a URL, or null when it is not one. */
internal expect fun WireUrl.resolveReference(reference: String): WireUrl?

/** This URL with `?name=value` (or `&name=value`) added, the value percent-encoded as a query component. */
internal expect fun WireUrl.withQueryParameter(name: String, value: String): WireUrl

/**
 * `resolveHarnessUrl(baseUrl, route)` as a [WireUrl]: [route] inside the reverse-proxy prefix of
 * [baseUrl], with the same refusals.
 *
 * @throws IllegalArgumentException when [baseUrl] is not an `http(s)` URL or carries credentials, or
 *   [route] is not a harness route or climbs out of the prefix.
 */
internal expect fun resolveHarnessWireUrl(baseUrl: String, route: String): WireUrl
