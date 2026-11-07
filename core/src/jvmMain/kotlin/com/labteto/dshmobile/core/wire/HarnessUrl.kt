package com.labteto.dshmobile.core.wire

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Resolve a harness route inside its advertised reverse-proxy root. */
fun resolveHarnessUrl(baseUrl: String, route: String): HttpUrl {
    require(route.startsWith("/") && !route.startsWith("//")) { "expected a harness route" }
    val base = baseUrl.toHttpUrl()
    require(base.username.isEmpty() && base.password.isEmpty()) { "credentials do not belong in a harness URL" }
    val prefix = base.encodedPath.trimEnd('/') + "/"
    val root = base.newBuilder().encodedPath(prefix).query(null).fragment(null).build()
    val resolved = requireNotNull(root.resolve(route.drop(1)))
    require(resolved.encodedPath.startsWith(prefix)) { "route escapes the harness root" }
    return resolved
}
