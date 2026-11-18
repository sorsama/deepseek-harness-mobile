package com.labteto.dshmobile.ui.screens.main.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The workspace Browser tab's web view, owned by the open panel.
 *
 * It is created the first time the tab needs it, kept while the reader switches between Files,
 * Preview, Terminal and Browser (so the page, its history and its scroll position stay), paused
 * whenever the tab is not on screen, and destroyed when the panel closes ([rememberBrowserViewHolder])
 * or, if the activity finishes first, with the activity. [WebPageSlot] holds the rules (one view,
 * destroyed once, never made again after the close, replaced after its renderer died);
 * [AndroidWebPage] holds the teardown order.
 *
 * The page runs JavaScript, because the dev servers this tab exists for are JavaScript apps. What it
 * does not get:
 *  - no JavaScript interface: nothing in the app is callable from the page;
 *  - no file or content access, no file-URL universal access, no mixed content, no geolocation, no
 *    camera or microphone (every permission request is denied), no file chooser, no downloads, no
 *    second window;
 *  - nothing of the app's: the harness, relay and pairing credentials live in OkHttp and the Android
 *    Keystore, never in a web view, and this view keeps its cookies and storage in a WebView profile
 *    of its own ([BrowserProfile]);
 *  - no other scheme: a link to `intent:`, `mailto:` or an app's own scheme only reaches another app
 *    after the reader allows it ([BrowserTabState.otherApp]), and `javascript:`, `file:`,
 *    `content:`, `data:` and `blob:` navigations are dropped.
 *
 * Safe Browsing stays on, and a certificate error cancels the load with no way to proceed.
 */
internal class BrowserViewHolder(private val context: Context, private val state: BrowserTabState) {
    /**
     * Whether a navigation the page starts (a link, a redirect, a script) may load: null to let it,
     * or the problem to show instead. Set by the tab from the current connection.
     */
    var gate: (String) -> BrowserProblem? = { null }

    /** Bumped when a view is made or dropped, so the tab composes the view that is current. */
    var generation by mutableIntStateOf(0)
        private set

    private val slot = WebPageSlot { create() }

    /** Cookies and storage are in the tab's own profile; false on a WebView too old for profiles. */
    var isolated: Boolean = false
        private set

    /** The URL of the main-frame load in progress, to tell a page's certificate error from a resource's. */
    private var mainUrl: String? = null

    /**
     * The panel's web view. Null before the first page, after its renderer died and once the panel
     * closed; nothing outside this class keeps a reference to a view that is gone.
     */
    val view: WebView?
        get() {
            // Read so a composition that asks is told when the view is replaced or dropped.
            @Suppress("UNUSED_EXPRESSION")
            generation
            return slot.current?.view
        }

    val hasView: Boolean get() = view != null

    fun load(url: String) {
        // The panel is gone (a late tap or callback): nothing may be made for a panel nobody sees.
        if (slot.closed) return
        state.problem = null
        state.notice = null
        state.url = url
        mainUrl = url
        val page = slot.obtain()
        if (page == null) {
            // This phone has no usable WebView right now. Retry tries to build one again.
            state.live = false
            state.problem = BrowserProblem.Crashed
            return
        }
        state.live = true
        page.view.loadUrl(url)
    }

    fun reload() {
        val v = view
        val url = state.url ?: return
        if (v == null || !state.live || state.problem != null) load(url) else v.reload()
    }

    fun stop() {
        view?.stopLoading()
        state.loading = false
    }

    /** One step back in the page's history; false when there is none (or no page). */
    fun goBack(): Boolean {
        val v = view ?: return false
        if (!state.live || !v.canGoBack()) return false
        state.problem = null
        v.goBack()
        return true
    }

    fun goForward() {
        val v = view ?: return
        if (v.canGoForward()) {
            state.problem = null
            v.goForward()
        }
    }

    fun pause() {
        slot.pause()
    }

    fun resume() {
        slot.resume()
    }

    /** Cookies, site storage, cache and form data of the tab, then the page again. */
    fun clearSiteData() {
        BrowserProfile.clear(isolated)
        view?.clearCache(true)
        view?.clearFormData()
        if (state.live && state.url != null) reload()
    }

    /**
     * The panel is done with the browser (it closed, or the activity is finishing): destroys the web
     * view, once, and forgets the page ([BrowserTabState.closePage]). Safe to call again.
     */
    fun destroy() {
        slot.close()
        gate = { null }
        mainUrl = null
        state.closePage()
        generation++
    }

    // JavaScript is the point of the tab (dev servers are JavaScript apps). The page gets no bridge
    // into the app and no access beyond its own origin; see the class comment.
    private fun create(): AndroidWebPage {
        val v = WebView(context)
        try {
            configure(v)
        } catch (e: RuntimeException) {
            v.destroy()
            throw e
        }
        generation++
        return AndroidWebPage(v)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(v: WebView) {
        // Must come before anything else touches the view: the profile is fixed at first use.
        isolated = BrowserProfile.attach(v)
        // Legacy WebViews can lose the containing Compose dialog's surface with GPU drawing (the
        // panel is a dialog window); the terminal and the preview do the same.
        if (Build.VERSION.SDK_INT <= 30) v.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
        with(v.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            safeBrowsingEnabled = true
        }
        if (!isolated) CookieManager.getInstance().setAcceptThirdPartyCookies(v, false)
        v.setDownloadListener { _, _, _, _, _ -> state.notice = BrowserNotice.Download }
        v.webViewClient = Client()
        v.webChromeClient = Chrome()
    }

    private fun syncHistory(v: WebView?) {
        state.canGoBack = v?.canGoBack() == true
        state.canGoForward = v?.canGoForward() == true
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return true
            when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    if (!request.isForMainFrame) return false
                    val problem = gate(uri.toString()) ?: return false.also { mainUrl = uri.toString() }
                    state.problem = problem
                    return true
                }
                in DROPPED_SCHEMES, null -> return true
                else -> {
                    // Another app's link. Only a tap may ask, and only the reader can say yes; a
                    // redirect or a script never reaches another app.
                    if (request.isForMainFrame && request.hasGesture()) state.otherApp = uri.toString()
                    return true
                }
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            state.loading = true
            state.problem = null
            state.title = null
            url?.let { state.url = it; mainUrl = it }
            syncHistory(view)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            state.loading = false
            view?.url?.let { state.url = it }
            syncHistory(view)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            url?.let { state.url = it }
            syncHistory(view)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame != true) return
            state.loading = false
            state.problem = BrowserProblem.LoadFailed(request.url.toString(), error?.description?.toString())
        }

        // No "proceed anyway": the load is cancelled, and a page's own certificate failure is shown.
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            handler?.cancel()
            val url = error?.url ?: return
            if (url == mainUrl || url == view?.url) {
                state.loading = false
                state.problem = BrowserProblem.Tls(url)
            }
        }

        // Returning false (the default) would kill the whole app with the renderer. The view cannot
        // be used again: the slot takes it down here, and Retry builds a new one.
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            val lost = slot.current?.takeIf { it.view === view }
            if (lost != null && slot.rendererGone(lost)) {
                state.live = false
                state.loading = false
                state.title = null
                state.canGoBack = false
                state.canGoForward = false
                state.problem = BrowserProblem.Crashed
                generation++
            }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            state.progress = newProgress
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            state.title = title?.takeIf { it.isNotBlank() }
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            state.notice = BrowserNotice.FileChooser
            return true
        }
    }

    private companion object {
        /** Navigations that are never followed or handed on: they would run or read something here. */
        val DROPPED_SCHEMES = setOf("javascript", "file", "content", "data", "blob", "about", "filesystem", "ws", "wss")
    }
}

/**
 * One real [WebView] and the order its teardown must follow. A view torn down with a page still
 * loading, its clients still attached or itself still in the view tree is the classic way for a
 * WebView to outlive its owner or to crash in native code when the window goes away.
 */
internal class AndroidWebPage(val view: WebView) : WebPage {
    private var down = false

    override fun pause() {
        if (!down) view.onPause()
    }

    override fun resume() {
        if (!down) view.onResume()
    }

    override fun teardown(rendererGone: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // `destroy()` belongs to the thread that made the view.
            Handler(Looper.getMainLooper()).post { teardown(rendererGone) }
            return
        }
        if (down) return
        down = true
        // Out of the view tree first: `destroy()` is only allowed on a detached view.
        step { (view.parent as? ViewGroup)?.removeView(view) }
        if (!rendererGone) {
            // Stop what is running, drop every callback into the app, unload the page, then stop the
            // page's own timers. A view whose renderer died takes none of these calls.
            step { view.stopLoading() }
            step { view.webViewClient = WebViewClient() }
            step { view.webChromeClient = null }
            step { view.setDownloadListener(null) }
            step { view.clearFocus() }
            step { view.loadUrl("about:blank") }
            step { view.onPause() }
        }
        step { view.removeAllViews() }
        step { view.destroy() }
    }

    /** One step failing must never skip the `destroy()` after it. */
    private inline fun step(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
        }
    }
}

/**
 * The tab's own WebView profile: cookies, local storage, IndexedDB, service workers and the HTTP
 * cache apart from the app's other web views (the file preview and the terminal).
 *
 * Profiles need a WebView that supports them (`MULTI_PROFILE`, WebView 124 and later). On an older
 * one the tab uses the default profile, which the preview and the terminal also use; they keep no
 * cookies or storage of their own (their pages are app-made, at `.invalid` origins), and the app
 * never puts a credential in any web view, so nothing of the app's is exposed even then.
 */
internal object BrowserProfile {
    private const val NAME = "dsh-workspace-browser"

    fun attach(view: WebView): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return false
        return runCatching { WebViewCompat.setProfile(view, NAME); true }.getOrDefault(false)
    }

    fun clear(isolated: Boolean) {
        if (isolated && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val profile = ProfileStore.getInstance().getOrCreateProfile(NAME)
            profile.cookieManager.removeAllCookies(null)
            profile.webStorage.deleteAllData()
        } else {
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
    }
}

/**
 * The panel's browser view: lives while the panel is open and is destroyed with it. It is also
 * destroyed when the activity starts finishing, ahead of the window going away, so a web view never
 * meets the end of its activity.
 */
@Composable
internal fun rememberBrowserViewHolder(state: BrowserTabState): BrowserViewHolder {
    val context = LocalContext.current
    val holder = remember(state, context) { BrowserViewHolder(context, state) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(holder, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            val finishing = event == Lifecycle.Event.ON_PAUSE && context.findActivity()?.isFinishing == true
            if (finishing || event == Lifecycle.Event.ON_DESTROY) holder.destroy()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            holder.destroy()
        }
    }
    return holder
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Hand a link a page asked to open to the app that handles it, after the reader allowed it.
 *
 * An `intent:` link is stripped to what a browser would let a web page do: no explicit component,
 * no selector, no granted URI permissions, and it must be browsable. Returns false when no app can
 * take it.
 */
internal fun openOutside(context: Context, uri: String): Boolean {
    val intent = try {
        if (uri.startsWith("intent:", ignoreCase = true)) {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply {
                component = null
                selector = null
                flags = flags and (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    ).inv()
                if (`package` == context.packageName) return false
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        }
    } catch (_: Exception) {
        return false
    }
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Never back into this app: a page must not reach its `dshmobile:` links (pairing, sessions).
    // The app's own activities are always visible to it, whatever the package-visibility rules.
    if (handledHere(context, intent)) return false
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** Whether this app itself would answer [intent]. */
internal fun handledHere(context: Context, intent: Intent): Boolean {
    if (intent.`package` == context.packageName) return true
    val probe = Intent(intent).setPackage(context.packageName)
    @Suppress("DEPRECATION")
    return context.packageManager.queryIntentActivities(probe, 0).isNotEmpty()
}

/** Open [url] in the phone's own browser. */
internal fun openInBrowser(context: Context, url: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}
