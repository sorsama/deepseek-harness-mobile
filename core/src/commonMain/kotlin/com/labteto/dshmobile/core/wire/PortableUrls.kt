package com.labteto.dshmobile.core.wire

/**
 * The iOS side of [WireUrl]'s `expect`/`actual` functions: OkHttp's `HttpUrl` answers, rebuilt from
 * plain string work so they compile for iOS.
 *
 * It answers the questions the connect flow asks — is this an `http(s)` URL, what is its host, port
 * and path, where does this redirect point, what is this route under this base — the way `HttpUrl`
 * does, for the inputs the app and its tests use: scheme and host in any case, default ports,
 * bracketed IPv6 literals, user info, a missing or trailing-slash path, `\` for `/`, dot segments
 * (also percent-encoded), percent-encoding of what a request line cannot carry, query and fragment,
 * and relative references of every kind (`/x`, `x`, `./x`, `../x`, `?q`, `#f`, `//host/x`).
 * `PortableUrlsTest` runs it and OkHttp over one table.
 *
 * What it does not do, and refuses rather than guesses at: **non-ASCII and percent-encoded host
 * names** (OkHttp converts those to punycode, so on iOS an internationalised name must be typed as
 * punycode, as for [HarnessEndpoint]) and **IPv6 and IPv4 literals in unusual spellings**
 * (`[0:0:0:0:0:0:0:1]`, `http://0x7f.1`), which OkHttp rewrites to a canonical form and this keeps as
 * typed, lower-cased. A URL's fragment is dropped, since nothing here ever sends one.
 */
internal object PortableUrls {

    fun parse(input: String): WireUrl? {
        val text = input.trim { it <= ' ' }
        val scheme = leadingScheme(text) ?: return null
        if (scheme != "http" && scheme != "https") return null
        // Like `toHttpUrlOrNull`, any number of slashes (even none) may follow `scheme:`.
        return parseAuthority(scheme, text.substring(scheme.length + 1).dropWhile { it == '/' || it == '\\' })
    }

    fun resolve(base: WireUrl, reference: String): WireUrl? {
        val ref = reference.trim { it <= ' ' }
        var relative = ref
        val scheme = leadingScheme(ref)
        if (scheme != null) {
            if (scheme != "http" && scheme != "https") return null
            val afterScheme = ref.substring(scheme.length + 1)
            // `http://host/x` is absolute; `http:x` with the base's own scheme is relative to it.
            if (slashCount(afterScheme) >= 2 || scheme != base.scheme) return parse(ref)
            relative = afterScheme
        }
        if (slashCount(relative) >= 2) return parse("${base.scheme}:$relative")

        val pathEnd = relative.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) relative.length else it }
        val pathPart = relative.substring(0, pathEnd).replace('\\', '/')
        val afterPath = relative.substring(pathEnd)
        val query = when {
            afterPath.startsWith("?") -> afterPath.substring(1).substringBefore('#')
            relative.isEmpty() || afterPath.startsWith("#") && pathPart.isEmpty() -> queryOf(base.text)
            else -> null
        }
        val rawPath = when {
            pathPart.isEmpty() -> base.encodedPath
            pathPart.startsWith("/") -> pathPart
            else -> base.encodedPath.substringBeforeLast('/') + "/" + pathPart
        }
        val path = UrlText.removeDotSegments(UrlText.canonicalize(rawPath, UrlText.PATH_ENCODE_SET))
        return assemble(base.scheme, base.host, base.port, path, query?.let { UrlText.canonicalize(it, UrlText.QUERY_ENCODE_SET) }, base.hasCredentials)
    }

    fun withQueryParameter(url: WireUrl, name: String, value: String): WireUrl {
        val separator = if ('?' in url.text) '&' else '?'
        val text = url.text + separator + encodeQueryComponent(name) + "=" + encodeQueryComponent(value)
        return WireUrl(url.scheme, url.host, url.port, url.encodedPath, url.hasCredentials, text)
    }

    /** `resolveHarnessUrl`, through [HarnessEndpoint], which `HarnessEndpointTest` holds to it. */
    fun harness(baseUrl: String, route: String): WireUrl {
        val text = HarnessEndpoint.of(baseUrl).resolve(route)
        return checkNotNull(parse(text)) { "a resolved harness URL did not parse: $text" }
    }

    // ---------------------------------------------------------------- parsing

    private fun parseAuthority(scheme: String, afterSlashes: String): WireUrl? {
        val authorityEnd = afterSlashes.indexOfFirst { it == '/' || it == '\\' || it == '?' || it == '#' }
            .let { if (it < 0) afterSlashes.length else it }
        val authority = afterSlashes.substring(0, authorityEnd)
        val tail = afterSlashes.substring(authorityEnd)

        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at) else ""
        val hostAndPort = authority.substring(at + 1)

        val host: String
        val portText: String
        if (hostAndPort.startsWith("[")) {
            val close = hostAndPort.indexOf(']')
            if (close < 0) return null
            host = hostAndPort.substring(1, close).lowercase()
            if (host.isEmpty() || ':' !in host || !host.all { it in 'a'..'f' || it in '0'..'9' || it == ':' || it == '.' }) return null
            val after = hostAndPort.substring(close + 1)
            if (after.isNotEmpty() && !after.startsWith(":")) return null
            portText = after.removePrefix(":")
        } else {
            val colon = hostAndPort.indexOf(':')
            host = (if (colon < 0) hostAndPort else hostAndPort.substring(0, colon)).lowercase()
            portText = if (colon < 0) "" else hostAndPort.substring(colon + 1)
            // OkHttp allows any ASCII a host name may not be forbidden to carry: controls, space and
            // `# % / : ? @ [ \ ]`. Anything non-ASCII it would convert to punycode, which is not done here.
            if (host.isEmpty() || host.any { it <= ' ' || it >= '\u007f' || it in "#%/:?@[\\]" }) return null
        }
        val port = when {
            portText.isEmpty() -> defaultPort(scheme)
            portText.all { it in '0'..'9' } -> portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            else -> return null
        }

        val pathEnd = tail.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) tail.length else it }
        val rawPath = tail.substring(0, pathEnd).replace('\\', '/').ifEmpty { "/" }
        val afterPath = tail.substring(pathEnd)
        val query = if (afterPath.startsWith("?")) afterPath.substring(1).substringBefore('#') else null

        return assemble(
            scheme,
            host,
            port,
            UrlText.removeDotSegments(UrlText.canonicalize(rawPath, UrlText.PATH_ENCODE_SET)),
            query?.let { UrlText.canonicalize(it, UrlText.QUERY_ENCODE_SET) },
            // `user:password` is a credential unless both halves are empty (`@`, `:@`).
            hasCredentials = userInfo.isNotEmpty() && userInfo != ":",
        )
    }

    private fun assemble(
        scheme: String,
        host: String,
        port: Int,
        path: String,
        query: String?,
        hasCredentials: Boolean,
    ): WireUrl {
        val text = buildString {
            append(scheme).append("://").append(hostHeaderFor(scheme, host, port)).append(path)
            if (query != null) append('?').append(query)
        }
        return WireUrl(scheme, host, port, path, hasCredentials, text)
    }

    /** The query of an already-canonical URL text, or null when it has none. */
    private fun queryOf(text: String): String? = if ('?' in text) text.substringAfter('?') else null

    /** `http` or `https` (lower case) or another scheme name, when [text] starts `scheme:`; else null. */
    private fun leadingScheme(text: String): String? {
        val colon = text.indexOf(':')
        if (colon < 1) return null
        if (!text[0].isAsciiLetter()) return null
        for (i in 1 until colon) {
            val c = text[i]
            if (!(c.isAsciiLetter() || c in '0'..'9' || c == '+' || c == '-' || c == '.')) return null
        }
        return text.substring(0, colon).lowercase()
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun slashCount(text: String): Int = text.takeWhile { it == '/' || it == '\\' }.length

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80
}
