package com.labteto.dshmobile.ios

import com.labteto.dshmobile.core.wire.dto.REMOTE_STREAM_MUX_PATH

/** The port a harness listens on unless its operator chose another. */
internal const val DEFAULT_HARNESS_PORT = 3080

/**
 * Where a harness was said to be, parsed from what a person typed.
 *
 * The Android Connect screen has a host field and a port field; the iOS one has a single field, so
 * this accepts the forms people actually paste: a bare host, `host:port`, an `http://` or `https://`
 * URL with an optional port and a reverse-proxy path, and an IPv6 literal with or without brackets.
 * A port that is not typed is [DEFAULT_HARNESS_PORT] for a plain address and the scheme's own port
 * for an `https://` one. Any other scheme, credentials in the address, whitespace or a port outside
 * 1 to 65535 is refused rather than guessed at (the same refusals as the Android screen's
 * `parseHostInput`, which this follows).
 */
internal class HarnessAddress(
    val host: String,
    val port: Int,
    val tls: Boolean,
    /** A reverse-proxy mount, `""` or `/team/dsh`; never ends with a slash. */
    val basePath: String,
) {
    /** `host:port` for messages, with an IPv6 literal bracketed. */
    val authority: String = "${hostForUrl(host)}:$port"

    /** The URL the transport is built from. */
    val baseUrl: String = "${if (tls) "https" else "http"}://$authority$basePath"

    /** The URL of the mux WebSocket; `http(s)://` is fine, the mux socket maps the scheme. */
    val muxUrl: String = baseUrl + REMOTE_STREAM_MUX_PATH

    private fun hostForUrl(value: String): String = if (':' in value) "[$value]" else value
}

/** [raw] as a [HarnessAddress], or null when it is not one. */
internal fun parseHarnessAddress(raw: String): HarnessAddress? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    var tls = false
    var rest = trimmed
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd >= 0) {
        tls = when (trimmed.take(schemeEnd).lowercase()) {
            "http" -> false
            "https" -> true
            else -> return null
        }
        rest = trimmed.substring(schemeEnd + 3)
    }
    if (rest.any { it.isWhitespace() || it == '\\' }) return null

    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    if ('@' in authority) return null
    val afterAuthority = rest.substring(authorityEnd)
    val basePath = if (afterAuthority.startsWith("/")) {
        afterAuthority.substringBefore('?').substringBefore('#').trimEnd('/')
    } else {
        ""
    }

    val host: String
    var port: Int? = null
    when {
        // The URL form of an IPv6 literal: [::1] or [::1]:3080.
        authority.startsWith("[") -> {
            val close = authority.indexOf(']')
            if (close <= 1) return null
            host = authority.substring(1, close)
            val after = authority.substring(close + 1)
            when {
                after.isEmpty() -> Unit
                after.startsWith(":") -> port = after.drop(1).toValidPort() ?: return null
                else -> return null
            }
        }
        // Several colons and no brackets can only be a bare IPv6 literal.
        authority.count { it == ':' } > 1 -> host = authority
        ':' in authority -> {
            host = authority.substringBefore(':')
            port = authority.substringAfter(':').toValidPort() ?: return null
        }
        else -> host = authority
    }
    if (host.isBlank()) return null
    return HarnessAddress(
        host = host,
        port = port ?: if (tls) 443 else DEFAULT_HARNESS_PORT,
        tls = tls,
        basePath = basePath,
    )
}

private fun String.toValidPort(): Int? = toIntOrNull()?.takeIf { it in 1..65535 }
