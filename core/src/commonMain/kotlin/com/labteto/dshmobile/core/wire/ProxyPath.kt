package com.labteto.dshmobile.core.wire

/**
 * The canonical form of the reverse-proxy path someone typed after a host (`agent/` in
 * `https://home.example/agent/`), without its trailing slash, or null when it is not a path a URL
 * can carry.
 *
 * Canonical means the URL parser's own: dot segments resolved, characters percent-encoded the way
 * they are sent. It is done by the platform's URL parser (OkHttp's `HttpUrl` on the JVM, which is
 * what the Android app's connect form has always asked, `PortableUrls` on iOS), so the Android
 * answer did not change when the connect form's parsing moved to common code.
 */
fun canonicalProxyPath(path: String): String? =
    parseWireUrl("https://placeholder/$path")?.encodedPath?.trimEnd('/')
