package com.labteto.dshmobile.ui.addons

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewRenderProcess
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.connection.RelayHandoffOutcome
import com.labteto.dshmobile.core.uisync.UiSyncErrorCode
import com.labteto.dshmobile.core.uisync.UiSyncFeature
import com.labteto.dshmobile.core.uisync.UiSyncHitRegion
import com.labteto.dshmobile.core.uisync.UiSyncHostMessage
import com.labteto.dshmobile.core.uisync.UiSyncPageMessage
import com.labteto.dshmobile.core.uisync.UiSyncProtocol
import com.labteto.dshmobile.core.uisync.UiSyncState
import com.labteto.dshmobile.core.wire.RelayTls
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger

/** What the chat tells the layer about itself while it is on screen. */
internal data class UiAddonsPresence(
    /** The chat pane in root (window content) pixels: where the web view goes. */
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val chrome: UiAddonsChromeBounds?,
    val covered: Boolean,
    val dark: Boolean,
    val sessionId: String?,
)

/** What the activity root tells the layer about the connected host and this device. */
internal data class UiAddonsBinding(
    /** The connected host's page, null when there is none to load. */
    val target: UiAddonsTarget?,
    /** The harness has the `dsh-ui-sync` companion: true, false, or null while unknown. */
    val available: Boolean?,
    /** The "Show plugin add-ons" switch, as it reads on this device. */
    val enabled: Boolean,
    /** Some item would show on the phone (the host's item store): true, false, or null while unknown. */
    val phoneItems: Boolean?,
)

/**
 * The one web view that draws desktop web plugin items (a pet, composer and header buttons, their
 * dialogs) over the chat, and everything that keeps it honest.
 *
 * One per activity. The chat reports itself through [present]; the activity root reports the
 * connected host, whether it has the `dsh-ui-sync` companion, the device switch and the item store
 * through [bind]. [uiAddonsMode] turns that into what the view does. The view goes into the
 * activity's content frame above the Compose view (see [UiAddonsWebView] for why a plain view
 * sibling and not a Compose `AndroidView`), placed over the chat pane.
 *
 * The page is the harness's own web client at the host's `/`, signed in the way [UiAddonsAuth]
 * says (the host's browser session in this view's cookie store, or a relay's one-time handoff) and
 * switched into phone mode by a document-start script, so the companion shows only lifted plugin
 * items and keeps the rest invisible. Nothing shows until the page has said it is in phone mode
 * (`ui-sync/ready`): a sign-in page, an error page or the desktop client before the companion
 * starts never covers the chat.
 *
 * Companion 0.2.0 features, each only when the page announces it: the page reports plugin text
 * field focus and the app gives the view the keyboard ([UiAddonsFocus]); Back closes plugin dialogs
 * ([UiAddonsBack]); the system's animation setting goes to the page as `host/motion`; TalkBack may
 * read the lifted items (`a11y`); and `host/follow-session` waits on the page (`follow-wait`), so
 * the app's own retry is only for 0.1.0 pages.
 *
 * Locked down like the workspace browser, with one difference that is the point of it: this page is
 * the harness, signed in. It runs only at the harness origin (other navigations are dropped), the
 * bridge and the start script are only for that origin (see [modernBridge] for older WebViews), it
 * has no file access, no permissions, no new windows, and its cookies and storage live in a WebView
 * profile of its own where the platform has profiles (elsewhere the sign-in cookie is removed again
 * when the view goes).
 *
 * A failure is quiet: logged, nothing on screen. Main thread only.
 */
internal class UiAddonsHost(
    private val activity: ComponentActivity,
    private val state: UiSyncState,
    /** Trade the relay's device token for a one-time web view code (relay targets only). */
    private val handoff: suspend (UiAddonsTarget) -> RelayHandoffOutcome,
) : DefaultLifecycleObserver, ComponentCallbacks2 {
    private val main = Handler(Looper.getMainLooper())
    private var page: Page? = null
    private var binding = UiAddonsBinding(target = null, available = null, enabled = false, phoneItems = null)
    private var presence: UiAddonsPresence? = null
    private var started = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private var destroyed = false
    private val crashes = mutableMapOf<String, Int>()
    /** A WebView is installed and enabled; without one there is nothing to draw with. */
    private val supported: Boolean by lazy {
        runCatching { WebViewCompat.getCurrentWebViewPackage(activity) != null }.getOrDefault(false)
    }

    /**
     * The phone flag goes in before any page script (`addDocumentStartJavaScript`) and the page talks
     * through a message listener locked to the harness origin, where the WebView has both. An older
     * WebView (Android 11's own is version 83) gets the fallback: the flag is set by
     * `evaluateJavascript` as the document commits, long before the companion's plugin bundle loads,
     * and the page posts through a JavaScript interface that only the harness document is heard on.
     * Either way nothing is drawn until the page itself says it is in phone mode, so a flag that came
     * too late leaves the layer empty, never the desktop client over the chat.
     */
    private val modernBridge: Boolean by lazy {
        runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ Back, focus, motion

    private val backCallback: OnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = back.onBack()
    }

    private val back: UiAddonsBack = UiAddonsBack(object : UiAddonsBack.Effects {
        override fun setEnabled(enabled: Boolean) {
            if (enabled) {
                // In front of every callback added since this one was: the dialog over the chat is
                // the topmost thing Back can close. A screen opened later adds its own after this.
                backCallback.remove()
                activity.onBackPressedDispatcher.addCallback(activity, backCallback)
            }
            backCallback.isEnabled = enabled
        }

        override fun sendBack(requestId: String): Boolean = page?.post(UiSyncHostMessage.Back(requestId)) == true

        override fun fallThrough() {
            val was = backCallback.isEnabled
            backCallback.isEnabled = false
            try {
                activity.onBackPressedDispatcher.onBackPressed()
            } finally {
                if (!destroyed) backCallback.isEnabled = was && (back.wanted || back.waiting != null)
            }
        }

        override fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
            val runnable = Runnable { block() }
            main.postDelayed(runnable, delayMs)
            return { main.removeCallbacks(runnable) }
        }

        override fun newRequestId(): String = UiSyncState.requestId("b")
    })

    private val focus: UiAddonsFocus = UiAddonsFocus(object : UiAddonsFocus.Effects {
        override fun allowFocus(allowed: Boolean) {
            page?.view?.focusAllowed = allowed
        }

        override fun focusAndShowKeyboard() {
            val view = page?.view ?: return
            if (!view.hasFocus()) view.requestFocus()
            imm()?.showSoftInput(view, 0)
        }

        override fun hideKeyboardIfFocused() {
            val view = page?.view ?: return
            if (view.hasFocus()) imm()?.hideSoftInputFromWindow(view.windowToken, 0)
        }

        override fun releaseFocus() {
            val view = page?.view ?: return
            if (view.hasFocus()) {
                imm()?.hideSoftInputFromWindow(view.windowToken, 0)
                view.clearFocus()
            }
            view.focusAllowed = false
        }

        override fun sendBlur(): Boolean = page?.post(UiSyncHostMessage.Blur()) == true
    })

    private fun imm(): InputMethodManager? = activity.getSystemService(InputMethodManager::class.java)

    /** A native view took focus away from the page: tell the page, so its field lets go too. */
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        val live = page ?: return@OnGlobalFocusChangeListener
        if (newFocus != null && newFocus !== live.view && !isInside(newFocus, live.view)) focus.onNativeFocus()
    }

    private fun isInside(view: View, ancestor: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }

    /**
     * The system's "Remove animations" (animator duration scale 0), sent to the page as `host/motion`.
     * Read from the setting itself: the observer below fires before this process's own animator
     * scale (`ValueAnimator.areAnimatorsEnabled`) has caught up, which would send the old value.
     */
    private fun reduceMotion(): Boolean = runCatching {
        Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrElse { !ValueAnimator.areAnimatorsEnabled() }

    private val motionObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            page?.sendMotion()
        }
    }

    init {
        activity.lifecycle.addObserver(this)
        activity.registerComponentCallbacks(this)
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        runCatching { activity.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener) }
        runCatching {
            activity.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver,
            )
        }
    }

    /** The connected host and whether the layer may load for it (see [UiAddonsBinding]). */
    fun bind(binding: UiAddonsBinding) {
        if (this.binding == binding) return
        this.binding = binding
        sync()
    }

    /** The chat is on screen as [presence] describes, or off screen (null). */
    fun present(presence: UiAddonsPresence?) {
        if (this.presence == presence) return
        this.presence = presence
        sync()
    }

    /** The activity is going away: the view goes with it, and nothing is made again. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        teardown(rendererGone = false)
        backCallback.remove()
        activity.lifecycle.removeObserver(this)
        runCatching { activity.unregisterComponentCallbacks(this) }
        runCatching {
            val observer = activity.window.decorView.viewTreeObserver
            if (observer.isAlive) observer.removeOnGlobalFocusChangeListener(focusListener)
        }
        runCatching { activity.contentResolver.unregisterContentObserver(motionObserver) }
    }

    private fun sync() {
        if (destroyed) return
        val target = binding.target
        page?.let { if (it.target != target) teardown(rendererGone = false, release = target == null) }
        val presence = presence
        val mode = uiAddonsMode(
            UiAddonsInputs(
                available = binding.available,
                target = target,
                supported = supported,
                enabled = binding.enabled,
                phoneItems = binding.phoneItems,
                chatShown = presence != null,
                sessionOpen = presence?.sessionId != null,
                covered = presence?.covered == true,
                started = started,
                hasView = page != null,
                givenUp = target != null && (crashes[target.key] ?: 0) >= MAX_RENDERER_CRASHES,
            ),
        )
        if (mode == UiAddonsMode.Off || target == null) {
            teardown(rendererGone = false)
            return
        }
        val live = page ?: create(target) ?: return
        live.apply(mode, presence)
    }

    // ------------------------------------------------------------------ the page

    private inner class Page(
        val target: UiAddonsTarget,
        val view: UiAddonsWebView,
        val isolated: Boolean,
        val script: ScriptHandler?,
        /** Messages to the page go through `window.dshUiSync.receive` instead of a reply proxy. */
        val legacy: Boolean,
    ) {
        /** The page said it is in phone mode since its last navigation started. */
        var ready = false
        /** `host/hello` and the current insets, theme and session went to this document. */
        var greeted = false
        var proxy: JavaScriptReplyProxy? = null
        /** The page's CSS-to-view pixel ratio, from its own `ready` or `hit-rects`. */
        var dpr: Float? = null
        /** What the page adds to protocol 1, from its last `ready`. */
        var features: Set<String> = emptySet()
        var failed = false
        var paused = false
        var mode = UiAddonsMode.Off
        var sentInsets: UiSyncHostMessage.Insets? = null
        var sentDark: Boolean? = null
        var sentSession: String? = null
        var sentMotion: Boolean? = null
        var placed: IntArray? = null
        /** The `host/follow-session` in flight, and how often the page refused this session so far. */
        var followRequest: String? = null
        var followRefusals = 0
        /** The relay handoff on its way, and how many this page asked for since it was last ready. */
        var handoffJob: Job? = null
        var handoffs = 0

        fun apply(mode: UiAddonsMode, presence: UiAddonsPresence?) {
            this.mode = mode
            if (presence != null) place(presence)
            val away = mode == UiAddonsMode.Away
            view.visibility = if (away) View.INVISIBLE else View.VISIBLE
            if (away && !paused) {
                paused = true
                view.onPause()
            } else if (!away && paused) {
                paused = false
                view.onResume()
                // A page that could not load is tried again when the chat comes back, not in a loop.
                if (failed) reload()
            }
            val shown = mode == UiAddonsMode.Shown && ready
            view.router.enabled = shown
            view.accessible = shown && UiSyncFeature.A11Y in features
            fade(if (shown) 1f else 0f)
            back.update(supported = ready && UiSyncFeature.BACK in features, shown = shown)
            // A field the page holds focus in lets go when the layer steps aside.
            if (!shown && focus.itemId != null && !post(UiSyncHostMessage.Blur())) focus.reset()
            if (greeted && presence != null) sendState(presence)
        }

        /** Load the page again: through a new relay code, or the host's index with the cookie already in place. */
        fun reload() {
            failed = false
            if (target.relay) handoff() else view.loadUrl(target.pageUrl)
        }

        /**
         * Ask the relay for a fresh one-time code and load it: when the view is made, when the page
         * lands on the relay's sign-in page (its device cookie ended), and when a code was refused.
         * Bounded, so a relay that keeps refusing is not asked in a loop.
         */
        fun handoff() {
            if (handoffJob?.isActive == true) return
            if (handoffs >= MAX_HANDOFFS) {
                Log.w(TAG, "relay handoff given up after $handoffs tries")
                failed = true
                return
            }
            handoffs++
            handoffJob = activity.lifecycleScope.launch {
                val outcome = try {
                    handoff(target)
                } catch (e: RuntimeException) {
                    RelayHandoffOutcome.Failed(e.javaClass.simpleName)
                }
                if (page !== this@Page) return@launch
                when (outcome) {
                    is RelayHandoffOutcome.Code -> {
                        Log.i(TAG, "relay handoff: loading a new code (try $handoffs)")
                        view.loadUrl(outcome.enterUrl)
                    }
                    RelayHandoffOutcome.Refused -> {
                        // The device token itself is refused; the app's own calls will say "pair again".
                        Log.w(TAG, "relay refused the web view handoff")
                        failed = true
                    }
                    RelayHandoffOutcome.Unsupported -> {
                        // A relay older than 0.5.0: no page for this host until it changes.
                        Log.i(TAG, "relay has no web view handoff")
                        crashes[target.key] = MAX_RENDERER_CRASHES
                        teardown(rendererGone = false)
                    }
                    is RelayHandoffOutcome.Failed -> {
                        Log.w(TAG, "relay handoff failed: ${outcome.reason}")
                        failed = true
                    }
                }
            }
        }

        // LEFT, not START: the pane's bounds are physical pixels from the window's left edge in every
        // layout direction, so START would mirror the view away from the chat in right-to-left locales.
        @SuppressLint("RtlHardcoded")
        private fun place(presence: UiAddonsPresence) {
            val next = intArrayOf(presence.left, presence.top, presence.width, presence.height)
            if (placed?.contentEquals(next) == true) return
            placed = next
            val params = (view.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(presence.width, presence.height)
            params.width = presence.width.coerceAtLeast(1)
            params.height = presence.height.coerceAtLeast(1)
            // Absolute left and top: the pane's bounds are physical, whatever the layout direction.
            params.gravity = Gravity.TOP or Gravity.LEFT
            params.leftMargin = presence.left
            params.topMargin = presence.top
            view.layoutParams = params
        }

        /** Where the view's alpha is going; it starts invisible. */
        private var fadeTarget = 0f

        private fun fade(alpha: Float) {
            if (fadeTarget == alpha) return
            fadeTarget = alpha
            // Quick, and done in one frame with animations switched off (the system's own duration scale).
            view.animate().cancel()
            view.animate().alpha(alpha).setDuration(if (alpha > 0f) FADE_IN_MS else FADE_OUT_MS).start()
        }

        fun sendState(presence: UiAddonsPresence) {
            val scale = dpr ?: view.resources.displayMetrics.density
            presence.chrome?.let { uiAddonsInsets(it, scale) }?.let { insets ->
                if (insets != sentInsets && post(insets)) sentInsets = insets
            }
            if (presence.dark != sentDark && post(UiSyncHostMessage.Theme(presence.dark))) sentDark = presence.dark
            sendMotion()
            val session = presence.sessionId
            if (session != null && session != sentSession) {
                if (session != followedOnce) followRefusals = 0
                val requestId = UiSyncState.requestId("f")
                if (post(UiSyncHostMessage.FollowSession(session, requestId))) {
                    sentSession = session
                    followedOnce = session
                    followRequest = requestId
                }
            }
        }

        /** `host/motion` for a page that has the feature, when the system setting differs from what it was sent. */
        fun sendMotion() {
            if (!greeted || UiSyncFeature.MOTION !in features) return
            val reduce = reduceMotion()
            if (reduce != sentMotion && post(UiSyncHostMessage.Motion(reduce))) sentMotion = reduce
        }

        /** The session the refusal count belongs to. */
        private var followedOnce: String? = null

        /**
         * A 0.1.0 page refused to follow: right after it boots its session list is still loading and
         * the harness's navigation throws for a session it does not list yet. Asked again a few times
         * with a growing pause; a session that stays unknown is left alone. A page with `follow-wait`
         * does this waiting itself (see [onFollowAck]).
         */
        fun onFollowRefused() {
            followRequest = null
            sentSession = null
            if (followRefusals >= FOLLOW_RETRIES) return
            val wait = FOLLOW_RETRY_MS shl followRefusals
            followRefusals++
            main.postDelayed({
                if (page === this && greeted) presence?.let { sendState(it) }
            }, wait)
        }

        private fun onFollowAck(message: UiSyncPageMessage.Ack) {
            followRequest = null
            if (message.ok) {
                followRefusals = 0
                return
            }
            Log.i(TAG, "page did not follow the session: ${message.errorCode}")
            if (UiSyncFeature.FOLLOW_WAIT !in features) {
                onFollowRefused()
                return
            }
            when (message.errorCode) {
                // Nothing came up in time: asked again with the next state the chat sends, or after the next ready.
                UiSyncErrorCode.NOT_READY -> sentSession = null
                // A newer request replaced it; the list has no such session: nothing to repeat.
                else -> Unit
            }
        }

        fun post(message: UiSyncHostMessage): Boolean = postJson(message.encode())

        // The reply proxy only exists on the message-listener path, which is taken only where the
        // WebView has WEB_MESSAGE_LISTENER (see modernBridge).
        @SuppressLint("RequiresFeature")
        fun postJson(json: String): Boolean {
            if (legacy) {
                if (!ready) return false
                view.evaluateJavascript("window.dshUiSync&&window.dshUiSync.receive(${JSONObject.quote(json)})", null)
                return true
            }
            val reply = proxy ?: return false
            return try {
                reply.postMessage(json)
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "bridge post failed: ${e.javaClass.simpleName}")
                false
            }
        }

        /** A new document is loading: nothing it says has been heard yet, and it shows nothing until it is ready. */
        fun onNavigation() {
            ready = false
            greeted = false
            proxy = null
            features = emptySet()
            sentInsets = null
            sentDark = null
            sentSession = null
            sentMotion = null
            followRequest = null
            followRefusals = 0
            view.router.region = UiSyncHitRegion.Empty
            view.router.enabled = false
            view.accessible = false
            back.reset()
            focus.supported = false
            fade(0f)
            state.detach()
        }

        /** The document committed: on the fallback path this is where the start script goes in. */
        fun onCommitted(url: String?) {
            if (script != null) return
            if (url?.toHttpUrlOrNull()?.let { originOf(it) } != target.origin) return
            view.evaluateJavascript(uiAddonsStartScript(target), null)
        }

        fun onPageMessage(data: String, reply: JavaScriptReplyProxy?) {
            val message = state.onPageMessage(data) ?: return
            when (message) {
                is UiSyncPageMessage.Ready -> {
                    if (message.mode != UiSyncState.PHONE_MODE) {
                        // Not the phone shell: never drawn, never touched.
                        ready = false
                        apply(mode, presence)
                        return
                    }
                    if (reply != null) proxy = reply
                    message.dpr?.let { dpr = it.toFloat() }
                    features = message.features
                    ready = true
                    handoffs = 0
                    focus.supported = UiSyncFeature.FOCUS in features
                    state.attach { json -> main.post { if (page === this) postJson(json) } }
                    if (!greeted) {
                        greeted = true
                        post(UiSyncHostMessage.Hello(APP_NAME, BuildConfig.VERSION_NAME))
                    }
                    // Sends the chat's state; a session the page could not follow yet is asked for again.
                    apply(mode, presence)
                }
                is UiSyncPageMessage.HitRects -> {
                    message.dpr?.let { dpr = it.toFloat() }
                    view.router.region = UiSyncHitRegion.of(message, view.resources.displayMetrics.density)
                }
                is UiSyncPageMessage.Focus -> focus.onPageFocus(message.editable, message.itemId)
                is UiSyncPageMessage.Dialogs -> back.update(dialogs = message.items)
                is UiSyncPageMessage.Ack -> when {
                    message.requestId == followRequest -> onFollowAck(message)
                    back.onAck(message.requestId, message.ok, message.handled) -> Unit
                    !message.ok -> Log.i(TAG, "page refused a request: ${message.errorCode}")
                }
                is UiSyncPageMessage.Error -> Log.i(TAG, "page reported ${message.code}")
                else -> Unit
            }
        }
    }

    // RequiresFeature: the listener and the start script are only added when modernBridge checked both
    // features. RtlHardcoded: see Page.place.
    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature", "RtlHardcoded")
    private fun create(target: UiAddonsTarget): Page? {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return null
        val view = try {
            UiAddonsWebView(activity)
        } catch (e: RuntimeException) {
            // No WebView on this phone right now (missing, disabled, being updated).
            Log.w(TAG, "no web view: ${e.javaClass.simpleName}")
            return null
        }
        var made: Page? = null
        try {
            // Must come first: the profile is fixed at the view's first use.
            val isolated = attachProfile(view)
            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
            configure(view)
            val origins = setOf(target.origin)
            val script = if (modernBridge) {
                WebViewCompat.addWebMessageListener(view, UiSyncProtocol.NATIVE_OBJECT, origins) { _, message, sourceOrigin, isMainFrame, reply ->
                    onMessage(view, message, sourceOrigin, isMainFrame, reply)
                }
                WebViewCompat.addDocumentStartJavaScript(view, uiAddonsStartScript(target), origins)
            } else {
                view.addJavascriptInterface(LegacyBridge(view), UiSyncProtocol.NATIVE_OBJECT)
                null
            }
            Log.i(TAG, if (modernBridge) "add-on page: message listener bridge" else "add-on page: fallback bridge (older WebView)")
            val page = Page(target, view, isolated, script, legacy = !modernBridge)
            made = page
            view.webViewClient = Client(page)
            view.webChromeClient = Chrome()
            view.alpha = 0f
            content.addView(view, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
            page.placed = null
            this.page = page
            livePages.incrementAndGet()
            when (val auth = target.auth) {
                is UiAddonsAuth.Session -> cookieManager(isolated).setCookie(auth.cookieUrl, auth.cookieLine) { stored ->
                    if (this.page !== page) return@setCookie
                    if (stored != true) Log.w(TAG, "harness session not stored in the web view")
                    view.loadUrl(target.pageUrl)
                }
                // A fresh code for every new view: a code works once, within a minute.
                UiAddonsAuth.RelayHandoff -> page.handoff()
            }
            return page
        } catch (e: RuntimeException) {
            Log.w(TAG, "add-on layer not created: ${e.javaClass.simpleName}")
            if (made != null && page === made) {
                page = null
                livePages.decrementAndGet()
            }
            discard(view, rendererGone = false, release = false)
            return null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: UiAddonsWebView) {
        view.setBackgroundColor(Color.TRANSPARENT)
        with(view.settings) {
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
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        // The page sets its own light or dark palette from `host/theme`; the platform must not invert it.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
        }
        // The page fills the chat pane and gets its chrome as `host/insets`. Window insets reaching the
        // view as well would shrink the page under the keyboard and count the composer twice.
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, _ -> WindowInsetsCompat.CONSUMED }
        view.setDownloadListener { _, _, _, _, _ -> }
    }

    private fun onMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, reply: JavaScriptReplyProxy) {
        val live = page?.takeIf { it.view === view } ?: return
        if (!isMainFrame || sourceOrigin.toString().trimEnd('/') != live.target.origin) return
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val data = message.data ?: return
        live.onPageMessage(data, reply)
    }

    /**
     * The fallback page-to-app transport for a WebView without message listeners. A JavaScript
     * interface is offered to every frame, so a message counts only while the view's own document is
     * the harness (navigation elsewhere is blocked anyway). Called on the WebView's bridge thread.
     */
    private inner class LegacyBridge(private val view: WebView) {
        @JavascriptInterface
        fun postMessage(json: String?) {
            if (json == null) return
            main.post {
                val live = page?.takeIf { it.view === view } ?: return@post
                if (view.url?.toHttpUrlOrNull()?.let { originOf(it) } != live.target.origin) return@post
                live.onPageMessage(json, null)
            }
        }
    }

    private inner class Client(private val page: Page) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url ?: return true
            val scheme = url.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return true
            if (!request.isForMainFrame) return false
            // The relay's sign-in page means its device cookie ended: a new handoff, never that page.
            if (isRelaySignIn(page.target, url.toString())) {
                Log.i(TAG, "relay sent the page to its sign-in: new handoff")
                if (this@UiAddonsHost.page === page) page.handoff()
                return true
            }
            // The page may move within the harness (a route change that reloads); nowhere else.
            val same = url.toString().toHttpUrlOrNull()?.let { originOf(it) } == page.target.origin
            if (!same) Log.i(TAG, "blocked a navigation away from the harness")
            return !same
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (this@UiAddonsHost.page !== page) return
            page.onNavigation()
            // A redirect the client hook did not see still never shows the relay's sign-in page.
            if (isRelaySignIn(page.target, url)) {
                view?.stopLoading()
                page.handoff()
                return
            }
            page.onCommitted(url)
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            // A second chance for the fallback flag; harmless when it is already set.
            if (this@UiAddonsHost.page === page) page.onCommitted(url)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame != true || this@UiAddonsHost.page !== page) return
            page.failed = true
            Log.w(TAG, "page failed to load: ${error?.errorCode}")
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            if (request?.isForMainFrame != true) return
            // A relay code that was used, expired or never issued: ask for a new one (bounded).
            val path = request.url?.path.orEmpty()
            if (page.target.relay && path.startsWith(RELAY_ENTER_PATH) && this@UiAddonsHost.page === page) {
                Log.w(TAG, "relay refused the code (${errorResponse?.statusCode}); asking again")
                page.handoff()
                return
            }
            // Most likely a session the harness no longer accepts; the page then never says it is ready.
            Log.w(TAG, "page answered ${errorResponse?.statusCode}")
        }

        // A certificate the platform refuses is accepted only when it carries the key pinned for this
        // host; there is no "proceed anyway".
        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            val pin = page.target.pin
            val presented = error?.certificate?.let { x509Of(it) }?.let { RelayTls.spkiPin(it) }
            if (pin != null && presented == pin) {
                handler?.proceed()
            } else {
                handler?.cancel()
                Log.w(TAG, "certificate refused")
            }
        }

        // Returning false would take the whole app down with the renderer.
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            if (this@UiAddonsHost.page !== page) return true
            crashes[page.target.key] = (crashes[page.target.key] ?: 0) + 1
            Log.w(TAG, "page renderer gone (crash=${detail?.didCrash()})")
            teardown(rendererGone = true)
            main.post { sync() }
            return true
        }
    }

    private class Chrome : WebChromeClient() {
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
            return true
        }
    }

    // ------------------------------------------------------------------ teardown

    /**
     * Take the page down. [release]: no new page follows right away (the layer is off, the activity
     * or the host is going), so the add-on profile's renderer may be let go too (see [discard]).
     */
    // RequiresFeature: a page that is not legacy was made on the path that checked both features.
    @SuppressLint("RequiresFeature")
    private fun teardown(rendererGone: Boolean, release: Boolean = true) {
        val dead = page ?: return
        // Focus and Back are about this view; settle them while it is still the page.
        focus.reset()
        back.reset()
        page = null
        livePages.decrementAndGet()
        dead.handoffJob?.cancel()
        state.detach()
        if (!rendererGone) {
            if (dead.legacy) {
                runCatching { dead.view.removeJavascriptInterface(UiSyncProtocol.NATIVE_OBJECT) }
            } else {
                runCatching { dead.script?.remove() }
                runCatching { WebViewCompat.removeWebMessageListener(dead.view, UiSyncProtocol.NATIVE_OBJECT) }
            }
        }
        discard(dead.view, rendererGone, release = release && dead.isolated)
        if (!dead.isolated) forgetCookie(dead.target)
    }

    /**
     * The teardown order the workspace browser uses: out of the tree, stopped, emptied, destroyed.
     *
     * [release]: the view lived in the add-on profile, and no new page is about to take its place.
     * WebView keeps a profile's renderer running, cached, after its last view is destroyed: about
     * 150 to 280 MB that "Show plugin add-ons" off is meant to give back. Renderers are never shared
     * between profiles, so once no add-on page is left anywhere in the app, that renderer is ended.
     * The other web views of the app (previews, the terminal) use the default profile and are not
     * touched. The check waits a moment, so a page made again right away (the switch back on, the
     * activity recreated) keeps it.
     */
    @SuppressLint("RequiresFeature")
    private fun discard(view: WebView, rendererGone: Boolean, release: Boolean) {
        val renderer: WebViewRenderProcess? = if (release && !rendererGone && canEndRenderer) {
            runCatching { WebViewCompat.getWebViewRenderProcess(view) }.getOrNull()
        } else {
            null
        }
        step { view.animate().cancel() }
        step { (view.parent as? ViewGroup)?.removeView(view) }
        if (!rendererGone) {
            step { view.stopLoading() }
            step { view.webViewClient = Discarded }
            step { view.webChromeClient = null }
            step { view.setDownloadListener(null) }
            step { view.loadUrl("about:blank") }
            step { view.onPause() }
        }
        step { view.removeAllViews() }
        step { view.destroy() }
        if (renderer != null) {
            main.postDelayed({
                if (livePages.get() == 0 && runCatching { renderer.terminate() }.getOrDefault(false)) {
                    Log.i(TAG, "add-on renderer ended")
                }
            }, RENDERER_RELEASE_MS)
        }
    }

    /** The web view can hand out its renderer and end it (androidx.webkit features). */
    private val canEndRenderer: Boolean by lazy {
        runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.GET_WEB_VIEW_RENDERER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_TERMINATE)
        }.getOrDefault(false)
    }

    /** The client a discarded view keeps: a renderer that ends under it must not take the app with it. */
    private object Discarded : WebViewClient() {
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean = true
    }

    private inline fun step(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStart(owner: LifecycleOwner) {
        started = true
        sync()
    }

    override fun onStop(owner: LifecycleOwner) {
        started = false
        sync()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroy()
    }

    /** A whole web client is the first thing to give back: in the background, or when memory is critical. */
    override fun onTrimMemory(level: Int) {
        @Suppress("DEPRECATION")
        val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (critical || (!started && level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)) {
            if (page != null) Log.i(TAG, "add-on page released (trim level $level)")
            teardown(rendererGone = false)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        teardown(rendererGone = false)
    }

    // ------------------------------------------------------------------ profile and cookie

    /** Cookies and storage in a profile of their own; false on a WebView too old for profiles. */
    @SuppressLint("RequiresFeature")
    private fun attachProfile(view: WebView): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return false
        return runCatching { WebViewCompat.setProfile(view, PROFILE); true }.getOrDefault(false)
    }

    @SuppressLint("RequiresFeature")
    private fun cookieManager(isolated: Boolean): CookieManager =
        if (isolated) ProfileStore.getInstance().getOrCreateProfile(PROFILE).cookieManager else CookieManager.getInstance()

    /** In the shared default profile the sign-in (a harness session, a relay device cookie) must not outlive the layer. */
    private fun forgetCookie(target: UiAddonsTarget) {
        val url = (target.auth as? UiAddonsAuth.Session)?.cookieUrl ?: target.pageUrl
        runCatching { CookieManager.getInstance().setCookie(url, "${target.auth.cookieName}=; Max-Age=0; Path=/") }
    }

    private companion object {
        const val TAG = "UiAddons"
        const val PROFILE = "dsh-ui-addons"
        const val APP_NAME = "DSH Mobile"
        const val FADE_IN_MS = 150L
        const val FADE_OUT_MS = 90L

        /** How often a 0.1.0 page's refused `host/follow-session` is asked again, starting this many ms apart and doubling. */
        const val FOLLOW_RETRIES = 6
        const val FOLLOW_RETRY_MS = 500L

        /** A page whose renderer died this many times for one host is not made again until the host changes. */
        const val MAX_RENDERER_CRASHES = 2

        /** Relay codes one page may ask for before it is ready; a ready page starts the count again. */
        const val MAX_HANDOFFS = 4

        /** How long a discarded page's renderer waits for a new page before it is ended. */
        const val RENDERER_RELEASE_MS = 2_000L

        /** Add-on pages alive in this process, over every activity: the renderer is ended only at zero. */
        val livePages = AtomicInteger(0)
    }
}

/** The X.509 form of a WebView certificate, to compare its key with a pin. */
private fun x509Of(certificate: SslCertificate): X509Certificate? {
    if (Build.VERSION.SDK_INT >= 29) return certificate.x509Certificate
    val der = SslCertificate.saveState(certificate)?.getByteArray("x509-certificate") ?: return null
    return runCatching {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }.getOrNull()
}
