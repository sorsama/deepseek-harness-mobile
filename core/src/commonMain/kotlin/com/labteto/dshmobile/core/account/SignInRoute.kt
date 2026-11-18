package com.labteto.dshmobile.core.account

/**
 * Whether a sign-in can be finished from this phone, which depends on where the browser is sent back to.
 *
 * DeepSeek's sign-in ends with the browser being redirected to `<callback origin>/oauth/callback`, a
 * route the harness registers on its own web server for the length of the attempt. The harness takes
 * that origin from the client and accepts only `http://localhost`, `http://127.0.0.1` or
 * `http://[::1]` with an explicit port (`loginOrigin` in
 * `packages/credentials/deepseek-account-platform/src/protocol.ts`; its README: "non-loopback reverse
 * proxies are unsupported"). So the browser that approves the sign-in has to reach the harness through
 * its own loopback address.
 */
sealed interface SignInRoute {
    /**
     * The harness is reached on this phone's own loopback (the built-in harness, Termux, or a port
     * forwarded with `adb reverse`), so the phone's browser can be redirected straight to it.
     */
    data class OnThisPhone(val callbackOrigin: String) : SignInRoute

    /**
     * The harness is on another device. From the phone's browser `127.0.0.1` is the phone itself, so the
     * redirect would never reach the harness; sign-in has to be finished in a browser on the harness's
     * own computer. The harness takes no other origin, so there is nothing the phone can start.
     */
    data object OnHarnessComputer : SignInRoute
}

/**
 * The route for the harness this phone is connected to.
 *
 * @param host the address the app connects to, as stored (`127.0.0.1`, `localhost`, `::1`, `[::1]`, a LAN address, a name).
 * @param isRelay the connection goes through a relay, whatever its address.
 */
fun signInRoute(host: String, port: Int, useTls: Boolean, basePath: String, isRelay: Boolean): SignInRoute {
    if (isRelay || useTls || basePath.isNotEmpty() || port !in 1..65535) return SignInRoute.OnHarnessComputer
    val origin = when (host.trim().lowercase()) {
        "127.0.0.1", "localhost" -> "http://127.0.0.1:$port"
        "::1", "[::1]" -> "http://[::1]:$port"
        else -> return SignInRoute.OnHarnessComputer
    }
    return SignInRoute.OnThisPhone(origin)
}

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

/**
 * The sign-in link to hand to a browser, with the app's light or dark scheme added as the official
 * client does, or null when it is not a link this app should open.
 *
 * The link comes from the harness, which only accepts DeepSeek's own sign-in page, but a harness is
 * not trusted to that extent here: only `https` is opened (and `http` to a loopback address, which is
 * how a development Platform is reached), never a link with user information, whitespace or control
 * characters, and any fragment is dropped.
 */
fun browserLink(authorizeUrl: String, dark: Boolean): String? {
    val url = authorizeUrl.trim()
    if (url.isEmpty() || url.any { it.isWhitespace() || it.isISOControl() }) return null
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = url.substring(0, schemeEnd).lowercase()
    val rest = url.substring(schemeEnd + 3).substringBefore('#')
    val authority = rest.substringBefore('/').substringBefore('?')
    if (authority.isEmpty() || '@' in authority) return null
    val host = (if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')).lowercase()
    val allowed = scheme == "https" || (scheme == "http" && host in LOOPBACK_HOSTS)
    if (!allowed) return null
    val beforeQuery = rest.substringBefore('?')
    val query = if ('?' in rest) rest.substringAfter('?') else ""
    val kept = query.split('&').filter { it.isNotEmpty() && !it.startsWith("theme=") }
    val theme = "theme=" + if (dark) "dark" else "light"
    return "$scheme://$beforeQuery?" + (kept + theme).joinToString("&")
}

/**
 * The sign-in link of a running attempt as the browser should get it, or null when there is none to
 * open: the attempt is not waiting for the browser, or the link is one [browserLink] refuses.
 */
fun SignInProgress.browserTarget(dark: Boolean): String? =
    if (step == SignInStep.WaitingForBrowser) authorizeUrl?.let { browserLink(it, dark) } else null

/**
 * The attempt is waiting for the browser, but the link it carries is one this app will not open (none,
 * or one with another scheme, user information or control characters). Nothing on the page can finish
 * that attempt, so the page offers a fresh one instead of a Copy button for a link it would not stand
 * behind. The theme never decides whether a link is acceptable, so it is read as light here.
 */
fun SignInProgress.linkRefused(): Boolean =
    step == SignInStep.WaitingForBrowser && browserTarget(dark = false) == null
