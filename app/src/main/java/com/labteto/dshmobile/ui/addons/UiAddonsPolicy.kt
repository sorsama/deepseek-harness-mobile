package com.labteto.dshmobile.ui.addons

import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.core.uisync.UiSyncHostMessage
import com.labteto.dshmobile.core.wire.resolveHarnessUrl
import okhttp3.HttpUrl

/**
 * The harness page the add-on layer loads for one connected host, and the credential it rides.
 *
 * Only for a direct connection (this phone's own harness, `adb reverse`, or one on the local
 * network): the page needs the harness browser session the app already holds for that host, and
 * gets it the way a browser does, as the host's own cookie in the web view's cookie store.
 */
internal data class UiAddonsTarget(
    /** Identifies the host the page belongs to; a different key means a different page. */
    val key: String,
    /** The harness web client's index, `/` under the host's base path. */
    val pageUrl: String,
    /** `scheme://host[:port]`, the only origin the page may run in and talk to the app from. */
    val origin: String,
    /** The `Set-Cookie` line that puts the host's session into the web view's cookie store. */
    val cookieLine: String,
    /** The URL the cookie is stored for. */
    val cookieUrl: String,
    /** SPKI pin to accept a certificate the platform does not trust; null means the platform decides. */
    val pin: String?,
)

/**
 * The page target for [host], or null when the add-on layer must not load for it.
 *
 * Null for a relay: a paired phone holds only a bearer token, and a web view cannot add a header
 * to a page's own requests, scripts and socket. TODO(plugin-ui-sync M2): the relay gains a
 * device-to-browser session handoff (`/relay/session/open` then `/relay/session/enter`); load the
 * page through that, and allow the relay's `ui-sync` settings for paired devices.
 *
 * Null too without a session [cookie] (the host was never signed in to from this phone) and for an
 * address that is not an `http`/`https` URL.
 */
internal fun uiAddonsTarget(host: HostConfig, cookie: String?): UiAddonsTarget? {
    if (host.isRelay) return null
    val pair = cookie?.trim()?.takeIf { isCookiePair(it) } ?: return null
    val page = runCatching { resolveHarnessUrl(host.baseUrl, "/") }.getOrNull() ?: return null
    if (page.scheme != "http" && page.scheme != "https") return null
    val secure = page.isHttps
    return UiAddonsTarget(
        key = "${host.id}|${host.baseUrl}",
        pageUrl = page.toString(),
        origin = originOf(page),
        // Path `/`: the harness binds its session to the authority, not to a path, and a reverse
        // proxy that serves it under a prefix still has it answer for every route below.
        cookieLine = "$pair; Path=/; HttpOnly; SameSite=Strict" + if (secure) "; Secure" else "",
        cookieUrl = page.toString(),
        pin = host.relayFingerprint,
    )
}

/** `scheme://host[:port]`, with an IPv6 host bracketed and a default port left out. */
internal fun originOf(url: HttpUrl): String {
    val host = if (url.host.contains(':')) "[${url.host}]" else url.host
    val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
    return "${url.scheme}://$host$port"
}

/** A `name=value` pair the harness issued: something before `=`, and nothing a browser would read as another attribute. */
private fun isCookiePair(text: String): Boolean {
    val eq = text.indexOf('=')
    return eq > 0 && text.none { it == ';' || it == '\r' || it == '\n' || it == ',' } && !text.substring(0, eq).contains(' ')
}

/** What the add-on layer does with its web view right now. */
internal enum class UiAddonsMode {
    /** No web view: none is made, and one that exists is destroyed. */
    Off,

    /** The web view stays, paused and invisible (the chat is off screen, the app is in the background). */
    Away,

    /** The web view stays live but shows nothing and takes no touch (the drawer or Details covers the chat). */
    Covered,

    /** Drawn over the chat, with touches inside its hit rectangles. */
    Shown,
}

/** Everything [uiAddonsMode] decides from. */
internal data class UiAddonsInputs(
    /** The harness has the companion: true, false, or null while unknown (just connected, reconnecting). */
    val available: Boolean?,
    val target: UiAddonsTarget?,
    /** A WebView is installed and enabled. */
    val supported: Boolean,
    /** A low-RAM phone: the layer costs a whole web client and stays off there until it can be switched on. */
    val lowRam: Boolean,
    /** The chat pane is on screen. */
    val chatShown: Boolean,
    /** A session is open in the chat. */
    val sessionOpen: Boolean,
    /** Something native slides over the chat (the drawer, the Details overlay). */
    val covered: Boolean,
    /** The activity is started (in front). */
    val started: Boolean,
    /** A web view exists for [target] already. */
    val hasView: Boolean,
    /** The page for [target] failed for good (its renderer kept dying); no new view is made for it. */
    val givenUp: Boolean = false,
)

/**
 * The layer's rules, without a device.
 *
 * - Off whenever there is nothing to load: no direct target, the companion missing, no WebView, a
 *   low-RAM phone, or a page that kept crashing.
 * - While the companion's presence is unknown (it is asked again after every reconnect), an existing
 *   view stays as it is, but none is made: the chat looks as it always did until the harness says yes.
 * - A view is made only for an open session on a chat that is on screen, with the app in front.
 * - Once made it stays across the chat going off screen or the app going to the background (Away),
 *   and across the drawer or Details covering it (Covered); it is destroyed with the host.
 */
internal fun uiAddonsMode(inputs: UiAddonsInputs): UiAddonsMode = with(inputs) {
    if (target == null || available == false || !supported || lowRam || givenUp) return UiAddonsMode.Off
    if (available == null && !hasView) return UiAddonsMode.Off
    if (!hasView && !(chatShown && started && sessionOpen && !covered)) return UiAddonsMode.Off
    when {
        !chatShown || !started -> UiAddonsMode.Away
        covered -> UiAddonsMode.Covered
        else -> UiAddonsMode.Shown
    }
}

/** Where the chat's own chrome is, in root pixels: the pane, the top of the area below its top bar, the composer. */
internal data class UiAddonsChromeBounds(
    val paneLeft: Float,
    val paneTop: Float,
    val paneRight: Float,
    val paneBottom: Float,
    /** Top of the conversation area, just below the top bar; null until measured. */
    val contentTop: Float?,
    /** The composer block (the field and what sits directly above it); null until measured. */
    val composerLeft: Float?,
    val composerTop: Float?,
    val composerRight: Float?,
)

/**
 * `host/insets` for the chat's chrome: the page keeps its trays clear of the top bar and the
 * composer, and lines the composer trays up with the composer's own edges on a wide pane. CSS pixels
 * are view pixels over [scale] (the page's device-pixel ratio). Null until the chrome is measured.
 */
internal fun uiAddonsInsets(bounds: UiAddonsChromeBounds, scale: Float): UiSyncHostMessage.Insets? {
    if (!(scale > 0f)) return null
    val contentTop = bounds.contentTop ?: return null
    val composerTop = bounds.composerTop ?: return null
    fun css(px: Float): Double = (px.coerceAtLeast(0f) / scale).toDouble().roundTenth()
    return UiSyncHostMessage.Insets(
        top = css(contentTop - bounds.paneTop),
        right = css(bounds.paneRight - (bounds.composerRight ?: bounds.paneRight)),
        bottom = css(bounds.paneBottom - composerTop),
        left = css((bounds.composerLeft ?: bounds.paneLeft) - bounds.paneLeft),
    )
}

private fun Double.roundTenth(): Double = kotlin.math.round(this * 10.0) / 10.0
