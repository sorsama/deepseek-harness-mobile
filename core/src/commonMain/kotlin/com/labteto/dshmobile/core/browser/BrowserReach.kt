package com.labteto.dshmobile.core.browser

/**
 * How the app reaches the harness, which decides what a loopback address in the Browser tab means.
 *
 * The harness exposes no way to fetch a page from its own computer's loopback: the official client's
 * Browser loads pages in the viewer's own browser and "does not proxy or probe remote pages"
 * (`packages/client/ui-sidebar-browser` README), and the relay forwards only to the harness's own
 * port. So `localhost` in a page address is the harness computer's only when the harness runs on
 * this phone.
 */
enum class BrowserConnection {
    /** The harness runs on this phone (the built-in harness, Termux) or behind `adb reverse`: the app reaches it on loopback. */
    ThisDevice,

    /** A harness on another computer, reached directly (LAN address, VPN, reverse proxy). */
    LocalNetwork,

    /** A harness on another computer, reached through its relay. */
    Relay,

    /** No connection to the panel's harness right now. */
    Offline,
}

/** What the tab does with an address on the current connection. */
sealed interface BrowserReach {
    /** Load it. */
    data object Open : BrowserReach

    /**
     * The address is the harness computer's loopback, which this phone cannot reach on this
     * connection. Loading it here would reach the phone's own loopback instead, so the tab does not.
     * [onHarnessAddress] is the same page on the harness computer's network address, offered as an
     * explicit choice when that address is on a network the phone can be on; dev servers often
     * listen on loopback only, so it may not answer.
     */
    data class HarnessLoopback(val onHarnessAddress: String?) : BrowserReach

    /** The harness (or its relay) itself, which the app already is. */
    data object HarnessItself : BrowserReach
}

/**
 * Decide what the tab does with [address] while connected over [connection] to the harness at
 * [harnessHost]:[harnessPort] (the endpoint the app uses, or null when it is unknown).
 */
fun browserReach(
    address: WebAddress,
    connection: BrowserConnection,
    harnessHost: String?,
    harnessPort: Int?,
    harnessSecure: Boolean = false,
): BrowserReach {
    val harness = harnessHost?.let { endpointAddress(it, harnessPort, harnessSecure) }
    if (harness != null && sameServer(address, harness)) return BrowserReach.HarnessItself
    if (address.scope != AddressScope.Loopback) return BrowserReach.Open
    return when (connection) {
        BrowserConnection.ThisDevice, BrowserConnection.Offline -> BrowserReach.Open
        BrowserConnection.LocalNetwork, BrowserConnection.Relay -> {
            val alternative = harness
                ?.takeIf { it.scope == AddressScope.PrivateNetwork || it.scope == AddressScope.LinkLocal }
                ?.let { onHost(address, it.host) }
                ?.takeUnless { candidate ->
                    (parseBrowserAddress(candidate) as? AddressInput.Web)?.address?.let { sameServer(it, harness) } == true
                }
            BrowserReach.HarnessLoopback(alternative)
        }
    }
}

/** The harness endpoint read as an address, so it is compared in the same canonical form. */
private fun endpointAddress(host: String, port: Int?, secure: Boolean): WebAddress? {
    val bracketed = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    val scheme = if (secure) "https" else "http"
    val text = if (port != null) "$scheme://$bracketed:$port/" else "$scheme://$bracketed/"
    return (parseBrowserAddress(text) as? AddressInput.Web)?.address
}

private fun sameServer(a: WebAddress, b: WebAddress): Boolean {
    if (a.effectivePort != b.effectivePort) return false
    if (a.host == b.host) return true
    return a.scope == AddressScope.Loopback && b.scope == AddressScope.Loopback
}

/** [address] with its host replaced by [host], everything else kept. */
private fun onHost(address: WebAddress, host: String): String {
    val scheme = if (address.secure) "https" else "http"
    val authority = "$scheme://${address.host}" + (address.port?.let { ":$it" } ?: "")
    val rest = address.url.removePrefix(authority)
    return "$scheme://$host" + (address.port?.let { ":$it" } ?: "") + rest
}

/** Which connection a harness endpoint is, from what the app knows about it. */
fun browserConnectionOf(connected: Boolean, isRelay: Boolean, isLoopback: Boolean): BrowserConnection = when {
    !connected -> BrowserConnection.Offline
    isRelay -> BrowserConnection.Relay
    isLoopback -> BrowserConnection.ThisDevice
    else -> BrowserConnection.LocalNetwork
}

/** One web address found in text: [start] inclusive, [end] exclusive, [url] the text it covers. */
data class WebLink(val start: Int, val end: Int, val url: String)

/**
 * Bare `http://` and `https://` addresses in [text], as a dev server or an agent prints them
 * ("Local: http://localhost:5173/"). Trailing sentence punctuation and an unmatched closing bracket
 * are left out of the link; anything [parseBrowserAddress] refuses is not a link.
 */
fun findWebLinks(text: String): List<WebLink> {
    val links = mutableListOf<WebLink>()
    var i = 0
    while (i < text.length) {
        val start = schemeAt(text, i)
        if (start < 0) { i++; continue }
        var end = start
        while (end < text.length && !text[end].isWhitespace() && text[end] !in LINK_STOPS) end++
        end = trimLinkEnd(text, start, end)
        val candidate = text.substring(start, end)
        val hostStart = candidate.indexOf("://") + 3
        if (hostStart < candidate.length && parseBrowserAddress(candidate) is AddressInput.Web) {
            links += WebLink(start, end, candidate)
            i = end
        } else {
            i = start + 1
        }
    }
    return links
}

/** [findWebLinks], keeping only local addresses (loopback, private network, link-local). */
fun findLocalWebLinks(text: String): List<WebLink> = findWebLinks(text).filter { isLocalWebAddress(it.url) }

/** Whether [raw] is a loadable address that is not on the public internet. */
fun isLocalWebAddress(raw: String): Boolean =
    (parseBrowserAddress(raw) as? AddressInput.Web)?.address?.isLocal == true

private val LINK_STOPS = setOf('<', '>', '"', '`', '\u0000')

/** The index of an `http://` or `https://` that starts a word at [i], or -1. */
private fun schemeAt(text: String, i: Int): Int {
    if (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '/' || text[i - 1] == '@')) return -1
    val matches = text.regionMatches(i, "http://", 0, 7, ignoreCase = true) ||
        text.regionMatches(i, "https://", 0, 8, ignoreCase = true)
    return if (matches) i else -1
}

private fun trimLinkEnd(text: String, start: Int, initialEnd: Int): Int {
    var end = initialEnd
    while (end > start) {
        val c = text[end - 1]
        val link = text.substring(start, end)
        val drop = when (c) {
            '.', ',', ';', ':', '!', '?', '\'', '*', '_', '~' -> true
            ')' -> link.count { it == '(' } < link.count { it == ')' }
            ']' -> link.count { it == '[' } < link.count { it == ']' }
            '}' -> link.count { it == '{' } < link.count { it == '}' }
            else -> false
        }
        if (!drop) break
        end--
    }
    return end
}
