package com.labteto.dshmobile.ui.screens.main.browser

/**
 * What the Browser tab needs from one web view to own it, so the rules for creating, pausing and
 * destroying it can be tested without a device. [AndroidWebPage] is the real one.
 */
internal interface WebPage {
    fun pause()
    fun resume()

    /**
     * Take the page down for good. [WebPageSlot] calls this once per page and never touches the
     * page again afterwards. [rendererGone]: the page's renderer died, so detaching and destroying
     * are all the platform allows.
     */
    fun teardown(rendererGone: Boolean)
}

/**
 * The single web view of an open panel, and the rules that keep it from outliving the panel:
 *
 *  - the view is made on first use ([obtain]) and the same one is handed out until it is dropped;
 *  - [pause] and [resume] reach it only while it exists, and a view made while paused starts paused;
 *  - [close] takes it down exactly once and nothing can be made after that, so a late callback or
 *    recomposition never builds a view that nobody would destroy;
 *  - when its renderer dies ([rendererGone]) it is taken down at once and the next [obtain] builds a
 *    fresh one;
 *  - a view that cannot be made (WebView missing or being updated) gives null, not a crash.
 *
 * Main thread only.
 */
internal class WebPageSlot<P : WebPage>(private val create: () -> P) {
    private var page: P? = null
    private var paused = false

    /** The panel is done with the browser: nothing is made or paused any more. */
    var closed: Boolean = false
        private set

    /** The live page; null before the first use, after its renderer died, and once [closed]. */
    val current: P? get() = page

    /** The live page, made if there is none; null once [closed] or when no view can be made. */
    fun obtain(): P? {
        if (closed) return null
        page?.let { return it }
        val fresh = try {
            create()
        } catch (_: RuntimeException) {
            return null
        }
        page = fresh
        if (paused) fresh.pause()
        return fresh
    }

    fun pause() {
        if (paused) return
        paused = true
        page?.pause()
    }

    fun resume() {
        if (!paused) return
        paused = false
        page?.resume()
    }

    /**
     * [lost] reported that its renderer died. Takes it down and returns true when it was the live
     * page; false for a page that was already dropped or replaced, which needs nothing more.
     */
    fun rendererGone(lost: P): Boolean {
        if (closed || lost !== page) return false
        page = null
        lost.teardown(rendererGone = true)
        return true
    }

    /** Destroys the live page, once, and refuses to make another. Calling it again does nothing. */
    fun close() {
        if (closed) return
        closed = true
        val last = page
        page = null
        last?.teardown(rendererGone = false)
    }
}
