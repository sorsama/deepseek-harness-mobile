package com.labteto.dshmobile.ui.addons

import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.core.uisync.UiSyncHostMessage
import com.labteto.dshmobile.core.wire.resolveHarnessUrl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** How the add-on page signs in to the harness it belongs to. */
internal sealed interface UiAddonsAuth {
    /** The cookie the web view's store must drop when the layer goes and the store is the shared one. */
    val cookieName: String

    /**
     * A direct connection: the harness browser session the app already holds for the host, put
     * into the web view's cookie store the way a browser holds it.
     */
    data class Session(
        /** The `Set-Cookie` line that puts the host's session into the web view's cookie store. */
        val cookieLine: String,
        /** The URL the cookie is stored for. */
        val cookieUrl: String,
    ) : UiAddonsAuth {
        override val cookieName: String get() = cookieLine.substringBefore('=')
    }

    /**
     * A paired relay (0.5.0 and later): the app holds only a bearer token, which a web view cannot
     * put on the page's own requests, scripts and sockets. Each new page trades it for a one-time
     * code (`POST /relay/session/open`) and loads the code's address, where the relay sets its
     * device cookie and sends the view on to the harness (docs/CLIENT_INTEGRATION.md of the relay).
     */
    data object RelayHandoff : UiAddonsAuth {
        override val cookieName: String = RELAY_DEVICE_COOKIE
    }
}

/** The cookie a relay's session handoff sets. */
internal const val RELAY_DEVICE_COOKIE = "dsh_relay_device"

/** Where a relay sends a page whose device cookie ended (expired, revoked): run the handoff again instead. */
internal const val RELAY_LOGIN_PATH = "/relay/login"

/** Where a relay's one-time code is spent. */
internal const val RELAY_ENTER_PATH = "/relay/session/enter"

/** The harness page the add-on layer loads for one connected host, and how it signs in. */
internal data class UiAddonsTarget(
    /** Identifies the host the page belongs to; a different key means a different page. */
    val key: String,
    /** The harness web client's index, `/` under the host's base path. */
    val pageUrl: String,
    /** `scheme://host[:port]`, the only origin the page may run in and talk to the app from. */
    val origin: String,
    val auth: UiAddonsAuth,
    /** SPKI pin to accept a certificate the platform does not trust; null means the platform decides. */
    val pin: String?,
) {
    /** Whether the page comes through a relay (its badge is hidden, its sign-in is the handoff). */
    val relay: Boolean get() = auth is UiAddonsAuth.RelayHandoff
}

/**
 * The page target for [host], or null when the add-on layer must not load for it.
 *
 * A direct connection rides the session [cookie] the app holds for the host, and has no page
 * without one (the host was never signed in to from this phone). A relay needs no cookie here: the
 * page's sign-in is the relay's own handoff (see [UiAddonsAuth.RelayHandoff]). Null too for an
 * address that is not an `http`/`https` URL.
 */
internal fun uiAddonsTarget(host: HostConfig, cookie: String?): UiAddonsTarget? {
    val page = runCatching { resolveHarnessUrl(host.baseUrl, "/") }.getOrNull() ?: return null
    if (page.scheme != "http" && page.scheme != "https") return null
    val key = uiAddonsHostKey(host)
    if (host.isRelay) {
        return UiAddonsTarget(key = key, pageUrl = page.toString(), origin = originOf(page), auth = UiAddonsAuth.RelayHandoff, pin = host.relayFingerprint)
    }
    val pair = cookie?.trim()?.takeIf { isCookiePair(it) } ?: return null
    val secure = page.isHttps
    return UiAddonsTarget(
        key = key,
        pageUrl = page.toString(),
        origin = originOf(page),
        auth = UiAddonsAuth.Session(
            // Path `/`: the harness binds its session to the authority, not to a path, and a reverse
            // proxy that serves it under a prefix still has it answer for every route below.
            cookieLine = "$pair; Path=/; HttpOnly; SameSite=Strict" + if (secure) "; Secure" else "",
            cookieUrl = page.toString(),
        ),
        pin = host.relayFingerprint,
    )
}

/**
 * The document-start script for [target]: the companion's phone flag, and on a relay the mark that
 * hides the relay's own badge before it paints. Some document-start hooks run before the parser
 * has made `<html>`, so the mark waits for it then (the relay's client guide).
 */
internal fun uiAddonsStartScript(target: UiAddonsTarget): String = buildString {
    append(com.labteto.dshmobile.core.uisync.UiSyncProtocol.PHONE_FLAG_SCRIPT)
    if (target.relay) append(RELAY_BADGE_OFF_SCRIPT)
}

private const val RELAY_BADGE_OFF_SCRIPT =
    "(function(){function m(){document.documentElement.setAttribute('data-dsh-relay-badge','off')}" +
        "if(document.documentElement){m();return}" +
        "new MutationObserver(function(_,o){if(document.documentElement){m();o.disconnect()}})" +
        ".observe(document,{childList:true})})();"

/** A main-frame address on [target]'s origin that is the relay's sign-in page: the device cookie ended. */
internal fun isRelaySignIn(target: UiAddonsTarget, url: String?): Boolean {
    if (!target.relay) return false
    val parsed = url?.toHttpUrlOrNull() ?: return false
    return originOf(parsed) == target.origin && parsed.encodedPath.trimEnd('/') == RELAY_LOGIN_PATH
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

    /**
     * A discovery load: the page runs live so it can record its items in the host's item store, but
     * shows nothing, takes no touch and is hidden from TalkBack, wherever the app is (Settings too).
     */
    Discover,
}

/** Everything [uiAddonsMode] decides from. */
internal data class UiAddonsInputs(
    /** The harness has the companion: true, false, or null while unknown (just connected, reconnecting). */
    val available: Boolean?,
    val target: UiAddonsTarget?,
    /** A WebView is installed and enabled. */
    val supported: Boolean,
    /**
     * The "Show plugin add-ons" switch (per device; on by default with 6 GB of memory or more, see
     * [uiAddonsDefaultOn]). Off, no web view is ever made: the layer costs a whole web client.
     */
    val enabled: Boolean,
    /**
     * Some item would show on the phone, from the host's item store: true, false, or null while
     * unknown. A companion without an item store reads true (it cannot say).
     */
    val phoneItems: Boolean?,
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
    /**
     * A discovery load wants the page (the item store looked incomplete, or someone tapped "Find
     * add-ons"). It needs neither the switch, nor items for the phone, nor the chat.
     */
    val discovering: Boolean = false,
    /**
     * The view that exists was made for a discovery load. The normal rules then decide as if there
     * were none: once the load is over, it stays only if they would make it now.
     */
    val discoveryView: Boolean = false,
)

/**
 * The layer's rules, without a device.
 *
 * - Off whenever there is nothing to load: no target, the companion missing, no WebView, the switch
 *   off, no item that would show on the phone, or a page that kept crashing.
 * - While the companion's presence or the item store is unknown (both are asked again after every
 *   reconnect), an existing view stays as it is, but none is made: the chat looks as it always did
 *   until the harness says yes.
 * - A view is made only for an open session on a chat that is on screen, with the app in front.
 * - Once made it stays across the chat going off screen or the app going to the background (Away),
 *   and across the drawer or Details covering it (Covered); it is destroyed with the host.
 * - A discovery load makes the page whatever the switch, the item store or the chat say, as long as
 *   the app is in front (Discover); when the normal rules would draw or keep it, they win, so a page
 *   that finds items for the phone is simply shown. In the background a discovery page waits (Away).
 */
internal fun uiAddonsMode(inputs: UiAddonsInputs): UiAddonsMode = with(inputs) {
    if (target == null || available == false || !supported || givenUp) return UiAddonsMode.Off
    val normal = normalMode()
    if (!discovering || normal == UiAddonsMode.Shown || normal == UiAddonsMode.Covered) return normal
    if (available == null && !hasView) return UiAddonsMode.Off
    if (!started) return if (hasView) UiAddonsMode.Away else UiAddonsMode.Off
    UiAddonsMode.Discover
}

/** [uiAddonsMode] without a discovery load, once the host has a page to load at all. */
private fun UiAddonsInputs.normalMode(): UiAddonsMode {
    val ownView = hasView && !discoveryView
    if (!enabled || phoneItems == false) return UiAddonsMode.Off
    if ((available == null || phoneItems == null) && !ownView) return UiAddonsMode.Off
    if (!ownView && !(chatShown && started && sessionOpen && !covered)) return UiAddonsMode.Off
    return when {
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

/**
 * The "Show plugin add-ons" switch where nobody has set it: on for a phone with 6 GB of memory or
 * more, off below and on a phone that calls itself low on memory. The layer adds about 250 MB.
 *
 * [totalBytes] is `ActivityManager.MemoryInfo.totalMem`, which leaves out what the kernel and
 * firmware keep for themselves: a 6 GB phone reports about 5.5 GiB and a 4 GB one about 3.6 GiB.
 * The line sits between the two classes, at [UI_ADDONS_MEMORY_LINE].
 */
internal fun uiAddonsDefaultOn(totalBytes: Long, lowRam: Boolean): Boolean = !lowRam && totalBytes >= UI_ADDONS_MEMORY_LINE

/** 5 GiB: above every 4 GB phone's reported total, below every 6 GB one's. */
internal const val UI_ADDONS_MEMORY_LINE: Long = 5L * 1024 * 1024 * 1024
