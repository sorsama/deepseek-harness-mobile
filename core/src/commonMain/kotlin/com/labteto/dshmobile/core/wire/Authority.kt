package com.labteto.dshmobile.core.wire

/**
 * `host:port` spelled so that a URL parser reads it back as the same address.
 *
 * An IPv6 literal has to be bracketed. Unbracketed, its own colons are indistinguishable from the
 * port separator, and `new URL("http://" + authority)` — which is how both dsh-relay's fence and
 * the harness's `isTrustedApiRequest` parse an incoming `Host` — throws rather than guessing. That
 * is a whole class of failure that only shows up on an IPv6-only network: everything pairs, then
 * every request is refused.
 *
 * Hosts are stored bare throughout this client, which is the right storage form — it is the
 * identity key, and `[::1]` and `::1` must not be two different hosts. Bracketing belongs here, at
 * the point a bare host is spelled into a URL or a header, and nowhere else.
 */
fun authorityOf(host: String, port: Int): String =
    if (':' in host) "[$host]:$port" else "$host:$port"
