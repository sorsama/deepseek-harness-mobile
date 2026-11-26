package com.labteto.dshmobile.ui.screens.main.browser

import com.labteto.dshmobile.core.browser.AddressInput
import com.labteto.dshmobile.core.browser.parseBrowserAddress

/**
 * What the toolbar and the address field say, worked out from what the tab actually shows: a loaded
 * page, an explanation page, or the start page. Nothing here may describe a page that is not on
 * screen.
 */
internal data class BrowserShown(
    /** The address the field follows; null on the start page, where the field is empty. */
    val address: String?,
    /** The line beside the buttons: a page's title, else its host; empty when there is no page. */
    val title: String,
    /** Only a page that really loaded earns the lock or "not secure" mark. */
    val marked: Boolean,
    /** Reload (or Retry) means something: the page that is shown, or the one that failed to load. */
    val reloadable: Boolean,
)

/**
 * [live] is whether a web view holds [url]; [problem] is what the tab shows over (or instead of) the
 * page. An explanation page names the address it is about, not the page behind it.
 */
internal fun browserShown(live: Boolean, url: String?, title: String?, problem: BrowserProblem?): BrowserShown {
    fun hostOrEmpty(of: String?): String = of?.let(::hostOf).orEmpty()
    return when (problem) {
        null ->
            if (live && url != null) {
                BrowserShown(address = url, title = title ?: hostOrEmpty(url), marked = true, reloadable = true)
            } else {
                BrowserShown(address = null, title = "", marked = false, reloadable = false)
            }
        is BrowserProblem.Refused -> BrowserShown(address = problem.typed, title = "", marked = false, reloadable = false)
        is BrowserProblem.HarnessLoopback -> BrowserShown(problem.url, hostOrEmpty(problem.url), marked = false, reloadable = false)
        is BrowserProblem.HarnessItself -> BrowserShown(problem.url, hostOrEmpty(problem.url), marked = false, reloadable = false)
        is BrowserProblem.LoadFailed -> BrowserShown(problem.url, hostOrEmpty(problem.url), marked = false, reloadable = url != null)
        is BrowserProblem.Tls -> BrowserShown(problem.url, hostOrEmpty(problem.url), marked = false, reloadable = url != null)
        BrowserProblem.Crashed -> BrowserShown(url, hostOrEmpty(url), marked = false, reloadable = url != null)
    }
}

/** The host of [url] for the toolbar while a page has no title; [url] itself when it is not a web address. */
internal fun hostOf(url: String): String = (parseBrowserAddress(url, ::idnToAscii) as? AddressInput.Web)?.address?.host ?: url
