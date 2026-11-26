package com.labteto.dshmobile.ui.screens.main.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.labteto.dshmobile.core.browser.AddressRefusal

/**
 * What the workspace Browser tab knows about its page, kept in the panel's state so it outlives a
 * switch to Files, Preview or Terminal.
 *
 * The web view itself is not kept here: it belongs to the open panel ([BrowserViewHolder]) and is
 * destroyed when the panel closes. [closePage] then forgets everything that described the page, so
 * the tab shows its start page with nothing of the old page on the toolbar, and keeps only [lastUrl]
 * so the start page can offer to open it again rather than loading it by itself.
 */
class BrowserTabState {
    /** The page the tab shows; null on the start page. */
    var url by mutableStateOf<String?>(null)
    var title by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    /** The page the tab showed before its panel was closed: what the start page offers to open again. */
    var lastUrl by mutableStateOf<String?>(null)

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

    /**
     * The panel closed and its web view is gone. The page is forgotten, so nothing on the toolbar
     * can describe a page that is no longer shown; its address is kept as [lastUrl]. A link waiting
     * in [pending] is left alone: it opens when the panel next shows the tab.
     */
    fun closePage() {
        url?.let { lastUrl = it }
        url = null
        title = null
        loading = false
        progress = 0
        canGoBack = false
        canGoForward = false
        live = false
        problem = null
        otherApp = null
        notice = null
        phoneLoopback = false
    }
}

/** Why the tab shows a message instead of a page. */
sealed interface BrowserProblem {
    /** A typed address the tab does not open. [typed] is what the reader entered, kept in the field. */
    data class Refused(val reason: AddressRefusal, val scheme: String?, val typed: String? = null) : BrowserProblem

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

enum class BrowserNotice { Download, FileChooser }
