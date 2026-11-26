package com.labteto.dshmobile.core.uisync

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.classifyCapability
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/** What the live phone page said about itself in its last `ui-sync/ready`. */
data class UiSyncPage(
    val companionVersion: String,
    val protocol: Int,
    /** Whether the page's `ui-sync` settings answered; false means every item shows its default target. */
    val settingsLoaded: Boolean,
    /** What the page adds to protocol 1 ([UiSyncFeature]); empty for a 0.1.0 page. */
    val features: Set<String> = emptySet(),
)

/**
 * The plugin items the harness page reports, and the way back to it.
 *
 * One per app process, owned by the session store and reset when the app switches host. The web
 * layer feeds it every page message and attaches itself as the sender while a phone page is live.
 * Anything else in the app (Settings > Add-ons) reads [items] and changes a target with
 * [setTarget], without knowing about the web view.
 *
 * [items] outlives the page: after the web view is destroyed (the app went to the background, the
 * chat closed) it still holds the last list the page sent for this host, so a list of add-ons has
 * something to show. [page] says whether a page is attached right now.
 */
class UiSyncState {
    private val _items = MutableStateFlow<List<UiSyncItem>>(emptyList())
    val items: StateFlow<List<UiSyncItem>> = _items.asStateFlow()

    private val _page = MutableStateFlow<UiSyncPage?>(null)
    /** The phone page attached right now, or null when there is none. */
    val page: StateFlow<UiSyncPage?> = _page.asStateFlow()

    private val _pageSession = MutableStateFlow<String?>(null)
    /** The session the page shows, as it last said; it follows the native chat after `host/follow-session`. */
    val pageSession: StateFlow<String?> = _pageSession.asStateFlow()

    private val _acks = MutableSharedFlow<UiSyncPageMessage.Ack>(extraBufferCapacity = 32)
    /** Answers to messages sent with a request id (see [setTarget]). */
    val acks: SharedFlow<UiSyncPageMessage.Ack> = _acks.asSharedFlow()

    private val _errors = MutableSharedFlow<UiSyncPageMessage.Error>(extraBufferCapacity = 32)
    /** Failures the page could not attach to a request (a settings load that failed, a bad message). */
    val errors: SharedFlow<UiSyncPageMessage.Error> = _errors.asSharedFlow()

    private var sender: ((String) -> Unit)? = null

    /**
     * Ask the page to set item [id] to [target]. Returns the request id whose [acks] entry answers
     * it, or null when no page is attached; then write `settings/mutate` on namespace
     * [UiSyncProtocol.NAMESPACE] directly instead (both clients follow either path).
     */
    fun setTarget(id: String, target: UiSyncTarget): String? {
        val requestId = requestId("t")
        return if (send(UiSyncHostMessage.SetTarget(id, target, requestId))) requestId else null
    }

    /** Send [message] to the attached page; false when there is none or the transport refused it. */
    fun send(message: UiSyncHostMessage): Boolean {
        val out = sender ?: return false
        if (_page.value == null && message !is UiSyncHostMessage.Hello) return false
        return try {
            out(message.encode())
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /** A web layer can reach a page through [send]: called when its page said it is ready. */
    fun attach(send: (String) -> Unit) {
        sender = send
    }

    /** The page is gone (reloading, destroyed). The item list stays as the page left it. */
    fun detach() {
        sender = null
        _page.value = null
        _pageSession.value = null
    }

    /** The app switched host or disconnected: nothing of the old host's page applies any more. */
    fun reset() {
        detach()
        _items.value = emptyList()
    }

    /**
     * Fold one page message in. Returns the parsed message for the caller's own handling (the web
     * layer applies hit rectangles itself), or null for a message that was not understood.
     */
    fun onPageMessage(text: String): UiSyncPageMessage? {
        val message = parseUiSyncPageMessage(text) ?: return null
        onPageMessage(message)
        return message
    }

    fun onPageMessage(message: UiSyncPageMessage) {
        when (message) {
            is UiSyncPageMessage.Ready -> {
                // Only a page in phone mode is one this app draws; a desktop page has no bridge.
                _page.value = if (message.mode == PHONE_MODE) {
                    UiSyncPage(message.version, message.protocol, message.settings, message.features)
                } else {
                    null
                }
            }
            is UiSyncPageMessage.Items -> if (_items.value != message.items) _items.value = message.items
            is UiSyncPageMessage.Session -> _pageSession.value = message.sessionId
            is UiSyncPageMessage.Ack -> _acks.tryEmit(message)
            is UiSyncPageMessage.Error -> _errors.tryEmit(message)
            // The web layer acts on these itself: they are about its view (touch, focus, Back).
            is UiSyncPageMessage.HitRects, is UiSyncPageMessage.Focus, is UiSyncPageMessage.Dialogs -> Unit
        }
    }

    companion object {
        const val PHONE_MODE: String = "phone"

        /** A request id for a `host/...` message: unique enough to match one ack among a handful in flight. */
        fun requestId(prefix: String): String = prefix + Random.nextLong().toULong().toString(36)
    }
}

/**
 * Whether the harness has the `dsh-ui-sync` companion, as a capability answer.
 *
 * The companion mounts settings namespace [UiSyncProtocol.NAMESPACE]; a `settings/describe` that
 * lists it is [RpcResult.Ok]. One that does not, and a harness without `settings/describe` at all,
 * read `capability-unavailable`, which the feature table records as "not there" rather than as a
 * failure. Every other error (authentication, transport) passes through unchanged: it says nothing
 * about the companion.
 */
fun RpcResult<SettingsDescribeValue>.uiSyncPresence(): RpcResult<Unit> = when (this) {
    is RpcResult.Ok ->
        if (value.namespaces.any { it.ns == UiSyncProtocol.NAMESPACE }) {
            RpcResult.Ok(Unit)
        } else {
            RpcResult.Err(RpcError(CAPABILITY_UNAVAILABLE, "settings namespace ${UiSyncProtocol.NAMESPACE} is not mounted"))
        }
    is RpcResult.Err -> RpcResult.Err(error.classifyCapability())
}

private const val CAPABILITY_UNAVAILABLE = "capability-unavailable"
