package com.labteto.dshmobile.ui.addons

/**
 * Keyboard focus between the native chat and a plugin text field in the add-on page (companion
 * feature `focus`, docs/BRIDGE.md "Focus and the keyboard").
 *
 * The web view is not focusable by default, so a tap on a pet or a button never takes the keyboard
 * from the native composer. The page reports where its focus is; only a text field in a lifted item
 * (or a plugin dialog) makes the view focusable, focused and the keyboard shown. Focus that leaves
 * the page's items makes it unfocusable again. A native view taking focus while the page still has
 * an item focused sends `host/blur`, so the page does not keep a field focused behind the composer.
 *
 * Main thread only. The effects are the view's; this class holds the rules.
 */
internal class UiAddonsFocus(private val effects: Effects) {
    interface Effects {
        /** Whether the web view may hold focus at all. */
        fun allowFocus(allowed: Boolean)

        /** Focus the web view and show the keyboard for it. */
        fun focusAndShowKeyboard()

        /** Hide the keyboard, but only while the web view holds focus (a native field may own it). */
        fun hideKeyboardIfFocused()

        /** Give up focus if the web view holds it (the keyboard goes with it), and refuse it from now on. */
        fun releaseFocus()

        /** Send `host/blur`; false when the page could not be reached. */
        fun sendBlur(): Boolean
    }

    /** The page has the `focus` feature; without it the view never takes focus, as before. */
    var supported: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) reset()
        }

    /** The item the page last said holds its focus, or null. */
    var itemId: String? = null
        private set

    /** Whether the page last said a text field has its focus. */
    var editable: Boolean = false
        private set

    /** A `ui-sync/focus` report. */
    fun onPageFocus(editable: Boolean, itemId: String?) {
        if (!supported) return
        this.itemId = itemId
        this.editable = editable && itemId != null
        when {
            this.editable -> {
                effects.allowFocus(true)
                effects.focusAndShowKeyboard()
            }
            // Focus moved to a button of the same dialog, say: it stays in the page, without a keyboard.
            itemId != null -> {
                effects.allowFocus(true)
                effects.hideKeyboardIfFocused()
            }
            else -> effects.releaseFocus()
        }
    }

    /** A native view took focus. */
    fun onNativeFocus() {
        if (!supported || itemId == null) return
        // The page answers with focus false and no item, which releases the view.
        if (!effects.sendBlur()) {
            itemId = null
            editable = false
            effects.releaseFocus()
        }
    }

    /** A new document, or the page went away: nothing it said about focus holds. */
    fun reset() {
        itemId = null
        editable = false
        effects.releaseFocus()
    }
}

/**
 * Back while a plugin dialog is open over the chat (companion feature `back`, docs/BRIDGE.md
 * `host/back`): the dialog closes the way it would on Escape, and Back goes on to do what it does
 * without the layer when the page did not close one.
 *
 * The callback is enabled only while the page lists open dialogs on a shown layer. Back sends
 * `host/back` with a request id; an ack with `handled: true` ends it. `handled: false`, a failed
 * request, no ack within [timeoutMs], or a message that could not be sent hands Back on
 * ([Effects.fallThrough]). A second Back while one is waiting is swallowed.
 */
internal class UiAddonsBack(
    private val effects: Effects,
    private val timeoutMs: Long = BACK_ACK_TIMEOUT_MS,
) {
    interface Effects {
        /** Turn the Back callback on or off; turning it on puts it in front of the callbacks added before. */
        fun setEnabled(enabled: Boolean)

        /** Send `host/back` with [requestId]; false when the page could not be reached. */
        fun sendBack(requestId: String): Boolean

        /** The Back the page did not use: dispatch it again with this callback out of the way. */
        fun fallThrough()

        /** Run [block] after [delayMs]; the answer cancels it. */
        fun schedule(delayMs: Long, block: () -> Unit): () -> Unit

        fun newRequestId(): String
    }

    private var supported = false
    private var shown = false
    private var dialogs: List<String> = emptyList()
    private var pending: String? = null
    private var cancelTimeout: (() -> Unit)? = null
    private var enabled = false

    /** Whether the page wants Back now: it has the feature, the layer is shown, and a dialog is open. */
    val wanted: Boolean get() = supported && shown && dialogs.isNotEmpty()

    /** The request waiting for its ack, if any. */
    val waiting: String? get() = pending

    fun update(supported: Boolean = this.supported, shown: Boolean = this.shown, dialogs: List<String> = this.dialogs) {
        this.supported = supported
        this.shown = shown
        this.dialogs = dialogs
        sync()
    }

    /** The callback fired. */
    fun onBack() {
        if (pending != null) return
        if (!wanted) {
            effects.fallThrough()
            return
        }
        val requestId = effects.newRequestId()
        if (!effects.sendBack(requestId)) {
            effects.fallThrough()
            return
        }
        pending = requestId
        cancelTimeout = effects.schedule(timeoutMs) {
            if (pending == requestId) {
                pending = null
                cancelTimeout = null
                effects.fallThrough()
                sync()
            }
        }
        sync()
    }

    /** A `ui-sync/ack`; true when it answered the Back in flight. */
    fun onAck(requestId: String, ok: Boolean, handled: Boolean?): Boolean {
        if (requestId != pending) return false
        cancelTimeout?.invoke()
        cancelTimeout = null
        pending = null
        if (!(ok && handled == true)) effects.fallThrough()
        sync()
        return true
    }

    /** The page went away: a Back in flight is not coming back. */
    fun reset() {
        cancelTimeout?.invoke()
        cancelTimeout = null
        pending = null
        dialogs = emptyList()
        supported = false
        sync()
    }

    private fun sync() {
        val next = wanted || pending != null
        if (next != enabled) {
            enabled = next
            effects.setEnabled(next)
        }
    }

    companion object {
        /** The bridge doc's "about a second" before Back goes on without an answer. */
        const val BACK_ACK_TIMEOUT_MS = 1_000L
    }
}
