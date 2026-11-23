package com.labteto.dshmobile.core.uisync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * The bridge between the harness web page in phone mode and this app, protocol version 1.
 *
 * Ported from the companion plugin `dsh-ui-sync` (`docs/BRIDGE.md`, `src/shared/protocol.ts`,
 * 0.1.0, with the 0.2.0 additions announced in [UiSyncFeature]). The page is the real harness web
 * client in a transparent web view over the native chat;
 * the companion keeps the desktop tree invisible and lifts only plugin items (a pet, composer and
 * header buttons, the dialogs they open). It tells the app where those items are and which exist;
 * the app tells it its insets, its light or dark scheme and the session the chat shows.
 *
 * Every message is one JSON object `{ "v": 1, "type": ..., ... }` sent as a string. Page messages
 * are `ui-sync/...`, app messages `host/...`. Coordinates are CSS pixels of the page viewport.
 *
 * The page is a process boundary running plugin code, so parsing here is lenient in the way the
 * rest of the wire layer is: a message that is not JSON, has another version, an unknown type or a
 * missing field is dropped (null), and one bad entry in a list drops that entry only.
 */
object UiSyncProtocol {
    /** The protocol version every message carries in `v`. */
    const val VERSION: Int = 1

    /**
     * The harness settings namespace the companion mounts. Its presence in `settings/describe` is
     * what tells the app the companion is installed (see [uiSyncPresence]).
     */
    const val NAMESPACE: String = "ui-sync"

    /** The object name the page posts to: `window.DshUiSyncNative.postMessage(json)`. */
    const val NATIVE_OBJECT: String = "DshUiSyncNative"

    /** Injected before any page script; the companion's only switch into phone mode. */
    const val PHONE_FLAG_SCRIPT: String = "window.__DSH_UI_SYNC_HOST__ = 'phone';"

    internal val json = Json { ignoreUnknownKeys = true; isLenient = false }
}

/**
 * The additions a page announces in `ui-sync/ready.features` (companion 0.2.0). Protocol 1 only
 * grows: a page without the list is 0.1.0 and has none of them, and a name this app does not know
 * is ignored.
 */
object UiSyncFeature {
    /** The hidden desktop tree is inert and hidden from screen readers; lifted items are named groups. */
    const val A11Y: String = "a11y"

    /** `ui-sync/focus` and `host/blur`. */
    const val FOCUS: String = "focus"

    /** `host/back` with `result.handled` in its ack, and `ui-sync/dialogs`. */
    const val BACK: String = "back"

    /** `host/motion`. */
    const val MOTION: String = "motion"

    /** `host/follow-session` waits for the page's session list instead of refusing early. */
    const val FOLLOW_WAIT: String = "follow-wait"

    /** The page records its items in settings namespace `ui-sync`, field `items`. */
    const val ITEM_STORE: String = "item-store"
}

/** Refusal codes of `host/follow-session` the app tells apart. */
object UiSyncErrorCode {
    /** The page's loaded session list does not have the session. */
    const val UNKNOWN_SESSION: String = "ui-sync/unknown-session"

    /** Navigation or the session list did not come up in time (`follow-wait`); ask again after the next ready. */
    const val NOT_READY: String = "ui-sync/not-ready"

    /** A newer `host/follow-session` replaced this one (`follow-wait`). */
    const val SUPERSEDED: String = "ui-sync/superseded"
}

/** Where an item is for. Stored per item in settings namespace `ui-sync`, field `targets`. */
enum class UiSyncTarget(val wire: String) {
    BOTH("both"), PHONE("phone"), DESKTOP("desktop"), OFF("off");

    companion object {
        fun of(value: String?): UiSyncTarget? = entries.firstOrNull { it.wire == value }
    }
}

/** Where the phone shell shows a lifted slot. */
enum class UiSyncPlacement(val wire: String) {
    OVERLAY("overlay"), INPUT_LEFT("input-left"), INPUT_RIGHT("input-right"), HEADER_ACTIONS("header-actions");

    companion object {
        fun of(value: String?): UiSyncPlacement? = entries.firstOrNull { it.wire == value }
    }
}

/** One plugin UI item, as the page reports it. Core harness items are never listed. */
data class UiSyncItem(
    /** `<package>|<slot>|<id or key>`; the key under `targets` in settings. */
    val id: String,
    /** npm package name of the plugin that registered it. */
    val pkg: String,
    val slot: String,
    /** The registration's list `id` or keyed `key`; empty for single and chain slots. */
    val cell: String,
    val title: String?,
    /** Where the phone shell shows it, or null when its slot is not lifted (it can still target the desktop). */
    val placement: UiSyncPlacement?,
    val target: UiSyncTarget,
    val defaultTarget: UiSyncTarget,
    /** Whether the item can be seen on the page right now. */
    val visible: Boolean,
)

/** A page viewport in CSS pixels. */
data class UiSyncViewport(val w: Double, val h: Double)

/** One touch area in CSS pixels, relative to the web view's top-left corner. */
data class UiSyncRect(val item: String, val x: Double, val y: Double, val w: Double, val h: Double)

/** A message from the page. */
sealed interface UiSyncPageMessage {
    /** The companion started (or answered `host/hello`). Only `mode == "phone"` pages are shown. */
    data class Ready(
        val version: String,
        val protocol: Int,
        val mode: String,
        val viewport: UiSyncViewport?,
        val dpr: Double?,
        /** False until the `ui-sync` settings namespace answered once; until then items use defaults. */
        val settings: Boolean,
        /** What the page adds to protocol 1 ([UiSyncFeature]); empty for a 0.1.0 page. */
        val features: Set<String> = emptySet(),
    ) : UiSyncPageMessage

    data class Items(val items: List<UiSyncItem>) : UiSyncPageMessage

    /**
     * Focus moved into, inside or out of a lifted item or plugin dialog (feature `focus`).
     * [editable]: a text field has it. [itemId]: the item that holds it, null when focus is not in one.
     */
    data class Focus(val editable: Boolean, val itemId: String?) : UiSyncPageMessage

    /** The plugin dialogs open over the chat, bottom to top, by the id of the item that opened each (feature `back`). */
    data class Dialogs(val items: List<String>) : UiSyncPageMessage

    /** Where a touch must go to the web view. An empty list means every touch is native. */
    data class HitRects(val dpr: Double?, val viewport: UiSyncViewport?, val rects: List<UiSyncRect>) : UiSyncPageMessage

    /** The session the page shows, null when none. */
    data class Session(val sessionId: String?) : UiSyncPageMessage

    data class Ack(
        val requestId: String,
        val ok: Boolean,
        val errorCode: String?,
        val errorMessage: String?,
        /** `result.handled` of a `host/back` answer; null when the ack carries none. */
        val handled: Boolean? = null,
    ) : UiSyncPageMessage

    /** A failure the page could not attach to a request. */
    data class Error(val code: String, val message: String) : UiSyncPageMessage
}

/** A message to the page. [encode] gives the JSON string to post. */
sealed interface UiSyncHostMessage {
    val requestId: String?

    /** Asks the page for a full snapshot: ready, items, hit rects and session. Safe to repeat. */
    data class Hello(val appName: String, val appVersion: String, override val requestId: String? = null) : UiSyncHostMessage

    /** Space the native chrome covers at each edge, in CSS pixels; all four are clamped to `>= 0`. */
    data class Insets(
        val top: Double,
        val right: Double,
        val bottom: Double,
        val left: Double,
        override val requestId: String? = null,
    ) : UiSyncHostMessage

    /** The page's own light or dark palette; the host's shared `ui-theme` setting is not written. */
    data class Theme(val dark: Boolean, override val requestId: String? = null) : UiSyncHostMessage

    /** Show this session in the page, so session-scoped items belong to the chat on screen. */
    data class FollowSession(val sessionId: String, override val requestId: String? = null) : UiSyncHostMessage

    /** Write one item's target through the page's own connection. */
    data class SetTarget(val id: String, val target: UiSyncTarget, override val requestId: String? = null) : UiSyncHostMessage

    /** Close the topmost plugin dialog (feature `back`); always sent with a request id, the ack says whether one closed. */
    data class Back(override val requestId: String) : UiSyncHostMessage

    /** The app's reduce-motion setting (feature `motion`). */
    data class Motion(val reduce: Boolean, override val requestId: String? = null) : UiSyncHostMessage

    /** Take focus out of the page's lifted items: a native view took it (feature `focus`). */
    data class Blur(override val requestId: String? = null) : UiSyncHostMessage

    fun encode(): String = UiSyncProtocol.json.encodeToString(JsonObject.serializer(), toJson())

    fun toJson(): JsonObject = buildJsonObject {
        put("v", UiSyncProtocol.VERSION)
        when (val m = this@UiSyncHostMessage) {
            is Hello -> {
                put("type", "host/hello")
                put("app", buildJsonObject {
                    put("name", m.appName)
                    put("version", m.appVersion)
                })
            }
            is Insets -> {
                put("type", "host/insets")
                put("top", m.top.atLeastZero())
                put("right", m.right.atLeastZero())
                put("bottom", m.bottom.atLeastZero())
                put("left", m.left.atLeastZero())
            }
            is Theme -> {
                put("type", "host/theme")
                put("scheme", if (m.dark) "dark" else "light")
            }
            is FollowSession -> {
                put("type", "host/follow-session")
                put("sessionId", m.sessionId)
            }
            is SetTarget -> {
                put("type", "host/set-target")
                put("id", m.id)
                put("target", m.target.wire)
            }
            is Back -> put("type", "host/back")
            is Motion -> {
                put("type", "host/motion")
                put("reduce", m.reduce)
            }
            is Blur -> put("type", "host/blur")
        }
        requestId?.let { put("requestId", it) }
    }
}

/** A negative, NaN or infinite inset is refused by the page as a whole; send zero for it instead. */
private fun Double.atLeastZero(): Double = if (isFinite() && this > 0.0) this else 0.0

/**
 * Read one page message, or null when it is not one this app understands.
 *
 * Null for: text that is not a JSON object, a `v` other than [UiSyncProtocol.VERSION], a type
 * outside `ui-sync/...` this version knows, and a message missing a field it cannot do without.
 * The bridge doc asks the native side to ignore types it does not know, which this does by
 * returning null rather than failing.
 */
fun parseUiSyncPageMessage(text: String): UiSyncPageMessage? {
    if (text.length > MAX_MESSAGE_CHARS) return null
    val root = try {
        UiSyncProtocol.json.parseToJsonElement(text)
    } catch (_: Exception) {
        return null
    } as? JsonObject ?: return null
    if (root.int("v") != UiSyncProtocol.VERSION) return null
    return when (root.string("type")) {
        "ui-sync/ready" -> UiSyncPageMessage.Ready(
            version = root.string("version") ?: "",
            protocol = root.int("protocol") ?: UiSyncProtocol.VERSION,
            mode = root.string("mode") ?: return null,
            viewport = root.viewport(),
            dpr = root.positive("dpr"),
            settings = root.bool("settings") ?: false,
            // A missing or broken list is a 0.1.0 page; a name that is not a string is skipped.
            features = (root["features"] as? JsonArray)?.asSequence()
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.take(MAX_FEATURES)?.toSet().orEmpty(),
        )
        "ui-sync/items" -> {
            val items = root["items"] as? JsonArray ?: return null
            UiSyncPageMessage.Items(items.mapNotNull { (it as? JsonObject)?.item() }.distinctBy { it.id })
        }
        "ui-sync/focus" -> {
            val editable = root.bool("editable") ?: return null
            val itemId = when (val value = root["itemId"]) {
                null, is JsonNull -> null
                is JsonPrimitive -> value.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
                else -> return null
            }
            // Only a field inside a lifted item counts as editable: the page says so, and a field
            // with no owner would be one in the hidden tree, which the keyboard must never open for.
            UiSyncPageMessage.Focus(editable = editable && itemId != null, itemId = itemId)
        }
        "ui-sync/dialogs" -> {
            val items = root["items"] as? JsonArray ?: return null
            UiSyncPageMessage.Dialogs(
                items.asSequence()
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { id -> id.isNotEmpty() } }
                    .take(MAX_RECTS)
                    .toList(),
            )
        }
        "ui-sync/hit-rects" -> {
            val rects = root["rects"] as? JsonArray ?: return null
            UiSyncPageMessage.HitRects(
                dpr = root.positive("dpr"),
                viewport = root.viewport(),
                rects = rects.asSequence().mapNotNull { (it as? JsonObject)?.rect() }.take(MAX_RECTS).toList(),
            )
        }
        "ui-sync/session" -> {
            val value = root["sessionId"] ?: return null
            when {
                value is JsonNull -> UiSyncPageMessage.Session(null)
                value is JsonPrimitive && value.isString && value.content.isNotEmpty() -> UiSyncPageMessage.Session(value.content)
                else -> null
            }
        }
        "ui-sync/ack" -> {
            val requestId = root.string("requestId") ?: return null
            val ok = root.bool("ok") ?: return null
            val error = root["error"] as? JsonObject
            val result = root["result"] as? JsonObject
            UiSyncPageMessage.Ack(requestId, ok, error?.string("code"), error?.string("message"), result?.bool("handled"))
        }
        "ui-sync/error" -> UiSyncPageMessage.Error(
            code = root.string("code") ?: return null,
            message = root.string("message") ?: "",
        )
        else -> null
    }
}

/** A page that floods the bridge with a huge message is not drawing a pet; drop it unread. */
private const val MAX_MESSAGE_CHARS = 512 * 1024

/** More rectangles than any overlay needs; the rest are dropped so a runaway page cannot stall hit tests. */
internal const val MAX_RECTS = 256

/** More feature names than protocol 1 will ever announce. */
private const val MAX_FEATURES = 64

private fun JsonObject.item(): UiSyncItem? {
    val id = string("id")?.takeIf { it.isNotEmpty() } ?: return null
    val target = UiSyncTarget.of(string("target")) ?: return null
    return UiSyncItem(
        id = id,
        pkg = string("package") ?: "",
        slot = string("slot") ?: "",
        cell = string("cell") ?: "",
        title = string("title")?.takeIf { it.isNotBlank() },
        placement = UiSyncPlacement.of(string("placement")),
        target = target,
        defaultTarget = UiSyncTarget.of(string("defaultTarget")) ?: target,
        visible = bool("visible") ?: false,
    )
}

private fun JsonObject.rect(): UiSyncRect? {
    val x = number("x") ?: return null
    val y = number("y") ?: return null
    val w = number("w")?.takeIf { it > 0.0 } ?: return null
    val h = number("h")?.takeIf { it > 0.0 } ?: return null
    return UiSyncRect(string("item") ?: "", x, y, w, h)
}

private fun JsonObject.viewport(): UiSyncViewport? {
    val viewport = this["viewport"] as? JsonObject ?: return null
    val w = viewport.positive("w") ?: return null
    val h = viewport.positive("h") ?: return null
    return UiSyncViewport(w, h)
}

private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.content

private fun JsonObject.bool(key: String): Boolean? = primitive(key)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.number(key: String): Double? =
    primitive(key)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

private fun JsonObject.positive(key: String): Double? = number(key)?.takeIf { it > 0.0 }

private fun JsonObject.int(key: String): Int? {
    val n = number(key) ?: return null
    return if (n == kotlin.math.floor(n) && n in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) n.toInt() else null
}
