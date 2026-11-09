package com.labteto.dshmobile.core.wire

import io.ktor.http.URLProtocol
import io.ktor.http.Url

/**
 * A harness base URL, and the one rule for turning a route into a request URL under it.
 *
 * This is [resolveHarnessUrl] for carriers that do not have OkHttp's `HttpUrl`: the same
 * contract, written against Ktor's URL type and plain string work so it compiles for iOS. A
 * harness (or the relay in front of it) can live under a reverse-proxy prefix such as
 * `https://host/team/dsh`, and every route — `/api/<endpoint>`, the export download, the mux
 * upgrade — resolves inside that prefix and never outside it. `HarnessEndpointTest` runs both
 * implementations over the same table of inputs, so the two cannot drift.
 *
 * What it enforces, as `resolveHarnessUrl` does:
 * - the base is `http` or `https` and carries no credentials (they belong in headers);
 * - the route starts with exactly one `/`;
 * - dot segments are removed, and a route that climbs out of the prefix is refused;
 * - the base's own query and fragment are dropped, and the route's fragment is never sent.
 */
internal class HarnessEndpoint private constructor(
    /** `http` or `https`. */
    val scheme: String,
    /** The bare host: lower case, IPv6 literals unbracketed (see [hostHeader]). */
    val host: String,
    val port: Int,
    /** The base path with a trailing slash, e.g. `/` or `/team/dsh/`. */
    private val prefix: String,
) {
    /** The `Host` header requests to this endpoint carry; see [hostHeaderFor]. */
    val hostHeader: String = hostHeaderFor(scheme, host, port)

    private val origin: String = "$scheme://$hostHeader"

    /**
     * The absolute URL of [route] (`/api/session.list`, `/api/session.export?sessionId=…`).
     *
     * @throws IllegalArgumentException when [route] is not a harness route or escapes the prefix.
     */
    fun resolve(route: String): String {
        require(route.startsWith("/") && !route.startsWith("//")) { "expected a harness route" }
        val withoutFragment = route.substring(1).substringBefore('#')
        val pathPart = withoutFragment.substringBefore('?')
        val query = if ('?' in withoutFragment) withoutFragment.substringAfter('?') else null
        val path = UrlText.removeDotSegments(prefix + UrlText.canonicalize(pathPart, UrlText.PATH_ENCODE_SET))
        require(path.startsWith(prefix)) { "route escapes the harness root" }
        return buildString {
            append(origin)
            append(path)
            if (query != null) {
                append('?')
                append(UrlText.canonicalize(query, UrlText.QUERY_ENCODE_SET))
            }
        }
    }

    companion object {
        /**
         * Parse [baseUrl].
         *
         * @throws IllegalArgumentException when it is not an `http(s)` URL, names no host, or
         *   carries credentials — the same refusals `resolveHarnessUrl` makes.
         */
        fun of(baseUrl: String): HarnessEndpoint {
            // Ktor reads a scheme-less string as a host-only `http` URL; OkHttp refuses it.
            require("://" in baseUrl) { "Expected an http or https URL: $baseUrl" }
            val url = try {
                Url(baseUrl)
            } catch (e: Exception) {
                throw IllegalArgumentException("Expected an http or https URL: $baseUrl", e)
            }
            val scheme = when (url.protocol) {
                URLProtocol.HTTP -> "http"
                URLProtocol.HTTPS -> "https"
                else -> throw IllegalArgumentException("Expected URL scheme 'http' or 'https': $baseUrl")
            }
            require(url.user == null && url.password == null) { "credentials do not belong in a harness URL" }
            // Ktor may keep the brackets of an IPv6 literal; the bare form is what the Host rule
            // and the URL writer both start from.
            val host = url.host.removePrefix("[").removeSuffix("]").lowercase()
            require(host.isNotEmpty() && host.all(UrlText::isHostChar)) { "Expected a host: $baseUrl" }
            val basePath = UrlText.removeDotSegments(
                UrlText.canonicalize(url.encodedPath.ifEmpty { "/" }, UrlText.PATH_ENCODE_SET),
            )
            return HarnessEndpoint(scheme, host, url.port, basePath.trimEnd('/') + "/")
        }
    }
}
