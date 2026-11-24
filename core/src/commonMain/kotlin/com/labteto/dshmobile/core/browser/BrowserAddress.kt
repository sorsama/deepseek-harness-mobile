package com.labteto.dshmobile.core.browser

/**
 * The address rules of the workspace Browser tab: what may be loaded, where it points, and whether
 * the phone can reach it on the connection it has.
 *
 * Only `http` and `https` are ever loaded. An address names the scheme, or gets one: `http` for a
 * local address (a dev server on `localhost:5173` speaks plain HTTP) and `https` for anything else,
 * as the official client's Browser does for a bare host name. Embedded credentials are refused, as
 * the official client refuses them, and so is anything a browser would read differently from this
 * parser (a backslash or a percent sign in the host, a zone id), so the classification below is
 * always about the host the web view will really connect to.
 *
 * Numeric hosts are read the way WHATWG URL parsing (and so Chromium) reads them: `127.1`,
 * `0x7f.0.0.1` and `2130706433` are all `127.0.0.1`. `0.0.0.0` and `[::]` ("every interface", which
 * dev servers print) are written as `127.0.0.1` and `[::1]`, the same machine spelled the way a
 * browser can open it.
 */
object BrowserAddress {
    /** The longest address the tab accepts; the official client keeps the same bound. */
    const val MAX_LENGTH: Int = 16 * 1024
}

/** Where an address points, as far as the address alone can tell. */
enum class AddressScope {
    /** The machine the page is loaded on: `localhost`, `*.localhost`, 127.0.0.0/8, `::1`. */
    Loopback,

    /**
     * A private network: 10/8, 172.16/12, 192.168/16, 100.64/10 (carrier-grade NAT, which is where
     * Tailscale addresses live), IPv6 unique-local fc00::/7, mDNS `.local` and other home-network
     * suffixes, and single-label names, which only a local resolver answers.
     */
    PrivateNetwork,

    /** 169.254/16 and fe80::/10. */
    LinkLocal,

    /** Everything else. */
    Public,
}

/** A parsed, loadable address. [url] is what the web view is given. */
data class WebAddress(
    val url: String,
    val secure: Boolean,
    /** The host as it appears in [url]: lower case, IPv6 in brackets, IDN as punycode when converted. */
    val host: String,
    /** An explicit port other than the scheme's default, or null. */
    val port: Int?,
    val scope: AddressScope,
) {
    /** The port a connection really uses. */
    val effectivePort: Int get() = port ?: if (secure) 443 else 80

    /** Not the public internet: loopback, a private network or link-local. */
    val isLocal: Boolean get() = scope != AddressScope.Public
}

/** Why an address was not loaded. */
enum class AddressRefusal {
    Empty,
    TooLong,

    /** Not a well-formed address, or one a browser would read differently from this parser. */
    Invalid,

    /** A user name or password in the address (`http://a@b/`). */
    Credentials,

    /** A scheme other than http and https: `javascript:`, `intent:`, `file:`, `data:`, an app's own. */
    UnsupportedScheme,
}

/** The outcome of reading an address. */
sealed interface AddressInput {
    data class Web(val address: WebAddress) : AddressInput

    /** [scheme] is the refused scheme, lower case, for [AddressRefusal.UnsupportedScheme]. */
    data class Refused(val reason: AddressRefusal, val scheme: String? = null) : AddressInput
}

/**
 * Read one address-bar value, or one link, into a loadable [WebAddress].
 *
 * @param toAscii converts a host with non-ASCII characters to its ASCII (IDNA) form, or returns null
 * when it cannot be converted. Android passes `java.net.IDN`; without it a Unicode host is kept as it
 * is and read as public, which is what it is unless it imitates a local name.
 */
fun parseBrowserAddress(input: String, toAscii: ((String) -> String?)? = null): AddressInput {
    val text = input.trim()
    if (text.isEmpty()) return AddressInput.Refused(AddressRefusal.Empty)
    if (text.length > BrowserAddress.MAX_LENGTH) return AddressInput.Refused(AddressRefusal.TooLong)
    if (text.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return AddressInput.Refused(AddressRefusal.Invalid)

    val scheme = explicitScheme(text)
    val rest: String
    val chosenScheme: String?
    if (scheme != null) {
        if (scheme != "http" && scheme != "https") return AddressInput.Refused(AddressRefusal.UnsupportedScheme, scheme)
        val afterColon = text.substring(scheme.length + 1)
        if (!afterColon.startsWith("//")) return AddressInput.Refused(AddressRefusal.Invalid)
        rest = afterColon.substring(2)
        chosenScheme = scheme
    } else {
        // `//host/path` is a scheme-relative reference, not something a person types; refuse it
        // rather than guess.
        if (text.startsWith("/")) return AddressInput.Refused(AddressRefusal.Invalid)
        rest = text
        chosenScheme = null
    }

    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
        .let { if (it < 0) rest.length else it }
    // A backslash ends the authority for a browser (it is read as `/`) but not for most parsers:
    // `http://evil.test\@good.test` is the classic way to make two readers disagree about the host.
    if (authorityEnd < rest.length && rest[authorityEnd] == '\\') return AddressInput.Refused(AddressRefusal.Invalid)
    val authority = rest.substring(0, authorityEnd)
    val tail = rest.substring(authorityEnd)
    if (authority.contains('@')) return AddressInput.Refused(AddressRefusal.Credentials)

    val hostText: String
    val portText: String?
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close < 0) return AddressInput.Refused(AddressRefusal.Invalid)
        hostText = authority.substring(0, close + 1)
        val after = authority.substring(close + 1)
        portText = when {
            after.isEmpty() -> null
            after.startsWith(":") -> after.substring(1)
            else -> return AddressInput.Refused(AddressRefusal.Invalid)
        }
    } else {
        val colon = authority.lastIndexOf(':')
        if (colon >= 0) {
            hostText = authority.substring(0, colon)
            portText = authority.substring(colon + 1)
        } else {
            hostText = authority
            portText = null
        }
    }
    val host = readHost(hostText, toAscii) ?: return AddressInput.Refused(AddressRefusal.Invalid)

    val explicitPort: Int? = when {
        portText == null || portText.isEmpty() -> null
        portText.length > 5 || portText.any { it !in '0'..'9' } -> return AddressInput.Refused(AddressRefusal.Invalid)
        else -> portText.toInt().also { if (it !in 1..65535) return AddressInput.Refused(AddressRefusal.Invalid) }
    }

    val finalScheme = chosenScheme ?: if (host.scope == AddressScope.Public) "https" else "http"
    val secure = finalScheme == "https"
    val port = explicitPort?.takeUnless { (secure && it == 443) || (!secure && it == 80) }
    val path = if (tail.isEmpty() || tail[0] != '/') "/$tail" else tail
    val url = buildString {
        append(finalScheme).append("://").append(host.text)
        if (port != null) append(':').append(port)
        append(path)
    }
    return AddressInput.Web(WebAddress(url = url, secure = secure, host = host.text, port = port, scope = host.scope))
}

/** The scheme an address names, lower case, or null when it names none (`localhost:5173` is a host and port). */
private fun explicitScheme(text: String): String? {
    val colon = text.indexOf(':')
    if (colon <= 0) return null
    val candidate = text.substring(0, colon)
    if (!candidate[0].isAsciiLetter()) return null
    if (!candidate.all { it.isAsciiLetter() || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) return null
    // `host:port`, optionally followed by a path: the official client reads it as a host, and so do we.
    val after = text.substring(colon + 1)
    val digits = after.takeWhile { it in '0'..'9' }
    if (digits.isNotEmpty() && (digits.length == after.length || after[digits.length] in "/?#")) return null
    return candidate.lowercase()
}

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

private class Host(val text: String, val scope: AddressScope)

/** The host's canonical text and scope, or null when it is not a host a browser would read the same way. */
private fun readHost(raw: String, toAscii: ((String) -> String?)?): Host? {
    if (raw.isEmpty()) return null
    if (raw.startsWith("[")) {
        val inner = raw.substring(1, raw.length - 1)
        // A zone id (`fe80::1%25wlan0`) is not loadable in a browser address.
        if (inner.contains('%')) return null
        val groups = parseIpv6(inner) ?: return null
        val unspecified = groups.all { it == 0 }
        val canonical = if (unspecified) LOOPBACK_V6 else groups
        return Host("[" + formatIpv6(canonical) + "]", ipv6Scope(canonical))
    }
    var name = foldWidth(raw)
    // Forbidden host code points (WHATWG), plus `%`: Chromium decodes percent escapes in a host, so
    // `%6c%6fcalhost` would be read as one name here and as `localhost` there.
    if (name.any { it in FORBIDDEN_HOST_CHARS }) return null
    if (name.any { it.code > 0x7f }) {
        if (toAscii == null) {
            val unicode = name.lowercase()
            if (unicode.removeSuffix(".").split('.').any { it.isEmpty() }) return null
            return Host(unicode, unicodeScope(unicode))
        }
        name = toAscii(name) ?: return null
        if (name.any { it.code > 0x7f || it in FORBIDDEN_HOST_CHARS }) return null
    }
    name = name.lowercase()
    val bare = name.removeSuffix(".")
    if (bare.isEmpty()) return null
    val labels = bare.split('.')
    if (labels.any { it.isEmpty() }) return null
    if (endsInNumber(labels)) {
        val v4 = parseIpv4(labels) ?: return null
        if (v4 == 0L) return Host("127.0.0.1", AddressScope.Loopback)
        val scope = ipv4Scope(v4) ?: return null
        return Host(formatIpv4(v4), scope)
    }
    if (labels.any { it.length > 63 }) return null
    return Host(name, nameScope(bare))
}

/**
 * Fullwidth ASCII (U+FF01..U+FF5E) and the ideographic full stops fold to ASCII, as IDNA mapping
 * does in a browser, so `ｌｏｃａｌｈｏｓｔ` is read as the `localhost` the web view would open.
 */
private fun foldWidth(text: String): String = buildString(text.length) {
    for (c in text) {
        when (c) {
            in '！'..'～' -> append((c.code - 0xFEE0).toChar())
            '。', '．', '｡' -> append('.')
            else -> append(c)
        }
    }
}

private val FORBIDDEN_HOST_CHARS = setOf(' ', '#', '%', '/', ':', '<', '>', '?', '@', '[', '\\', ']', '^', '|', '"', '{', '}', '`', '\'')

/** WHATWG "ends in a number": the last label is all digits, or a 0x hex number. */
private fun endsInNumber(labels: List<String>): Boolean {
    val last = labels.last()
    if (last.isNotEmpty() && last.all { it in '0'..'9' }) return true
    return parseIpv4Part(last) != null && (last.startsWith("0x") || last.startsWith("0X"))
}

/** One IPv4 part: decimal, `0x` hex or leading-zero octal, as WHATWG reads it. */
private fun parseIpv4Part(part: String): Long? {
    if (part.isEmpty()) return null
    var digits = part
    var radix = 10
    if (digits.length >= 2 && (digits.startsWith("0x") || digits.startsWith("0X"))) {
        digits = digits.substring(2); radix = 16
        if (digits.isEmpty()) return 0
    } else if (digits.length >= 2 && digits.startsWith("0")) {
        digits = digits.substring(1); radix = 8
    }
    if (digits.length > 12) return null
    var value = 0L
    for (c in digits) {
        val d = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> return null
        }
        if (d >= radix) return null
        value = value * radix + d
    }
    return value
}

/** The address as a 32-bit value, or null when WHATWG would refuse it. */
private fun parseIpv4(labels: List<String>): Long? {
    if (labels.size > 4) return null
    val parts = labels.map { parseIpv4Part(it) ?: return null }
    for (i in 0 until parts.size - 1) if (parts[i] > 255) return null
    val lastLimit = 1L shl (8 * (5 - parts.size))
    if (parts.last() >= lastLimit) return null
    var value = parts.last()
    for (i in 0 until parts.size - 1) value += parts[i] shl (8 * (3 - i))
    return value
}

private fun formatIpv4(v: Long): String =
    "${(v shr 24) and 0xff}.${(v shr 16) and 0xff}.${(v shr 8) and 0xff}.${v and 0xff}"

/** Null for an address no page can be at (the rest of 0/8, multicast, broadcast). */
private fun ipv4Scope(v: Long): AddressScope? {
    val a = (v shr 24) and 0xff
    val b = (v shr 16) and 0xff
    return when {
        a == 0L -> null
        a >= 224L -> null
        a == 127L -> AddressScope.Loopback
        a == 10L -> AddressScope.PrivateNetwork
        a == 172L && b in 16L..31L -> AddressScope.PrivateNetwork
        a == 192L && b == 168L -> AddressScope.PrivateNetwork
        a == 100L && b in 64L..127L -> AddressScope.PrivateNetwork
        a == 169L && b == 254L -> AddressScope.LinkLocal
        else -> AddressScope.Public
    }
}

private val LOOPBACK_V6 = intArrayOf(0, 0, 0, 0, 0, 0, 0, 1)

/** Eight 16-bit groups, or null. Accepts `::` compression and a dotted-quad tail. */
private fun parseIpv6(text: String): IntArray? {
    if (text.isEmpty()) return null
    var body = text
    var tail: IntArray? = null
    val lastColon = body.lastIndexOf(':')
    if (lastColon >= 0 && body.substring(lastColon + 1).contains('.')) {
        val quad = body.substring(lastColon + 1).split('.')
        if (quad.size != 4) return null
        val bytes = quad.map { p -> if (p.isEmpty() || p.length > 3 || p.any { it !in '0'..'9' } || (p.length > 1 && p[0] == '0')) return null else p.toInt() }
        if (bytes.any { it > 255 }) return null
        tail = intArrayOf((bytes[0] shl 8) or bytes[1], (bytes[2] shl 8) or bytes[3])
        body = body.substring(0, lastColon + 1)
        // `::1.2.3.4` leaves `::`; `a::1.2.3.4` leaves `a::`; `a:b:...:1.2.3.4` leaves a trailing colon.
        if (!body.endsWith("::")) body = body.removeSuffix(":")
    }
    val doubleColon = body.indexOf("::")
    if (doubleColon >= 0 && body.indexOf("::", doubleColon + 1) >= 0) return null
    fun groups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        return part.split(':').map { g ->
            if (g.isEmpty() || g.length > 4) return null
            var v = 0
            for (c in g) {
                val d = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> return null
                }
                v = v * 16 + d
            }
            v
        }
    }
    val tailCount = tail?.size ?: 0
    val result = IntArray(8)
    if (doubleColon >= 0) {
        val head = groups(body.substring(0, doubleColon)) ?: return null
        val rest = groups(body.substring(doubleColon + 2)) ?: return null
        if (head.size + rest.size + tailCount > 7) return null
        head.forEachIndexed { i, v -> result[i] = v }
        val start = 8 - tailCount - rest.size
        rest.forEachIndexed { i, v -> result[start + i] = v }
    } else {
        val all = groups(body) ?: return null
        if (all.size + tailCount != 8) return null
        all.forEachIndexed { i, v -> result[i] = v }
    }
    tail?.let { result[6] = it[0]; result[7] = it[1] }
    return result
}

/** RFC 5952 text: lower case, the longest run of two or more zero groups compressed. */
private fun formatIpv6(groups: IntArray): String {
    var bestStart = -1
    var bestLen = 0
    var i = 0
    while (i < 8) {
        if (groups[i] == 0) {
            var j = i
            while (j < 8 && groups[j] == 0) j++
            if (j - i > bestLen && j - i >= 2) { bestStart = i; bestLen = j - i }
            i = j
        } else i++
    }
    val hex = groups.map { it.toString(16) }
    if (bestStart < 0) return hex.joinToString(":")
    val left = hex.subList(0, bestStart).joinToString(":")
    val right = hex.subList(bestStart + bestLen, 8).joinToString(":")
    return "$left::$right"
}

private fun ipv6Scope(g: IntArray): AddressScope {
    if (g.contentEquals(LOOPBACK_V6)) return AddressScope.Loopback
    // IPv4-mapped `::ffff:a.b.c.d` is the IPv4 address it carries.
    if ((0..4).all { g[it] == 0 } && g[5] == 0xffff) {
        val v4 = (g[6].toLong() shl 16) or g[7].toLong()
        return if (v4 == 0L) AddressScope.Loopback else ipv4Scope(v4) ?: AddressScope.Public
    }
    return when {
        g[0] and 0xffc0 == 0xfe80 -> AddressScope.LinkLocal
        g[0] and 0xfe00 == 0xfc00 -> AddressScope.PrivateNetwork
        else -> AddressScope.Public
    }
}

private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal")

private fun nameScope(name: String): AddressScope = when {
    name == "localhost" || name.endsWith(".localhost") -> AddressScope.Loopback
    PRIVATE_SUFFIXES.any { name.endsWith(it) } -> AddressScope.PrivateNetwork
    !name.contains('.') -> AddressScope.PrivateNetwork
    else -> AddressScope.Public
}

/** A Unicode host nobody converted: local only for the suffixes that stay ASCII. */
private fun unicodeScope(name: String): AddressScope {
    val bare = name.removeSuffix(".")
    return if (PRIVATE_SUFFIXES.any { bare.endsWith(it) }) AddressScope.PrivateNetwork else AddressScope.Public
}
