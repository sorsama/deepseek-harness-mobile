package com.labteto.dshmobile.ui.screens.main.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.labteto.dshmobile.core.browser.AddressRefusal

/**
 * What the workspace Browser tab knows about its page, kept in the panel's state so it outlives a
 * switch to Files, Preview or Terminal and the panel being closed and opened again.
 *
 * The web view itself is not kept here: it belongs to the open panel ([BrowserViewHolder]) and is
 * destroyed when the panel closes. After that, [url] is only remembered; the tab offers to load it
 * again rather than loading it by itself.
 */
internal class BrowserTabState {
    /** The page the tab shows, or showed last. */
    var url by mutableStateOf<String?>(null)
    var title by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    /** Something the tab shows in place of (or over) the page. */
    var problem by mutableStateOf<BrowserProblem?>(null)

    /** A link the page tried to hand to another app, waiting for the reader to allow it. */
    var otherApp by mutableStateOf<String?>(null)

    /** A short note under the toolbar: a download or a file picker the tab does not handle. */
    var notice by mutableStateOf<BrowserNotice?>(null)

    /** An address to open as soon as the tab is on screen: a link tapped in the chat. */
    var pending by mutableStateOf<String?>(null)

    /** A web view of the open panel holds [url] (false after the panel was closed and opened again). */
    var live by mutableStateOf(false)

    /**
     * The reader chose "Open on this phone instead" for a loopback address while the harness is on
     * another computer: links within the phone's own loopback load from then on, until the panel
     * closes, instead of asking again on every tap.
     */
    var phoneLoopback by mutableStateOf(false)
}

/** Why the tab shows a message instead of a page. */
internal sealed interface BrowserProblem {
    /** A typed address the tab does not open. */
    data class Refused(val reason: AddressRefusal, val scheme: String?) : BrowserProblem

    /** The harness computer's loopback, which this phone cannot reach on the current connection. */
    data class HarnessLoopback(val url: String, val alternative: String?, val relay: Boolean) : BrowserProblem

    /** The harness (or relay) the app is connected to. */
    data class HarnessItself(val url: String) : BrowserProblem

    /** The page did not load: no server, no route, a timeout. [detail] is the web view's own words. */
    data class LoadFailed(val url: String, val detail: String?) : BrowserProblem

    /** The certificate could not be verified. There is no way past this one. */
    data class Tls(val url: String) : BrowserProblem

    /** The page's renderer stopped. */
    data object Crashed : BrowserProblem
}

internal enum class BrowserNotice { Download, FileChooser }
