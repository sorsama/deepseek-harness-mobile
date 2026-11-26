package com.labteto.dshmobile.ui.addons

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import com.labteto.dshmobile.core.uisync.UiSyncTouchRouter

/**
 * The add-on layer's web view: transparent, full size over the chat, and touchable only inside the
 * rectangles the page reports.
 *
 * It sits in the activity's content `FrameLayout` above the Compose view, so plain view dispatch
 * does the routing: returning false from [dispatchTouchEvent] for a first finger down outside every
 * rectangle makes the frame hand the gesture to the Compose view underneath, which then owns it to
 * the end (scrolling the transcript, the drawer's swipe, a long press on a message). Hover and
 * generic pointer events (a mouse wheel or a trackpad on a Chromebook, TalkBack's explore by touch)
 * fall through the same way, per event.
 *
 * Focus: never by default, so the native composer keeps the keyboard and Back and key presses stay
 * native. The layer allows it ([focusAllowed]) only while the page says one of its plugin text
 * fields has focus (see [UiAddonsFocus]).
 *
 * Accessibility: hidden until the page says its hidden desktop tree is out of the screen reader's
 * way (companion feature `a11y`); then [accessible] lets TalkBack reach the lifted items, and explore
 * by touch inside their rectangles goes to the page.
 */
@SuppressLint("ViewConstructor")
internal class UiAddonsWebView(context: Context) : WebView(context) {
    val router = UiSyncTouchRouter()

    /** Whether the view may take focus: only while a plugin text field in the page wants the keyboard. */
    var focusAllowed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            isFocusable = value
            isFocusableInTouchMode = value
        }

    /** Whether TalkBack may read the page (its lifted items); hidden otherwise. */
    var accessible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            importantForAccessibility = if (value) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        // Until the page says otherwise, it keeps the whole desktop client in its tree, only
        // see-through; that is not something TalkBack should walk. The chat below stays reachable,
        // because hover events outside the rectangles fall through (see dispatchHoverEvent).
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!router.onDown(event.x, event.y)) return false
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val toWeb = router.gestureToWeb
                router.onGestureEnd()
                if (!toWeb) return false
                return super.dispatchTouchEvent(event)
            }
        }
        if (!router.gestureToWeb) return false
        return super.dispatchTouchEvent(event)
    }

    private var hovering = false

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        // TalkBack's explore by touch arrives as hover from the touchscreen. While the page is hidden
        // from accessibility all of it goes to the chat; once the page is readable, the part inside
        // its items' rectangles goes to the page. Every other spot of the chat stays explorable.
        val fromTouch = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
        val claimed = (accessible || !fromTouch) && router.claims(event.x, event.y)
        if (!claimed) {
            // A pointer that leaves an item: let the page see it go, so its hover state does not
            // stick, and still hand the event on to the chat below (false).
            if (hovering) {
                hovering = false
                super.dispatchHoverEvent(event)
            }
            return false
        }
        hovering = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
        return super.dispatchHoverEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && !router.claims(event.x, event.y)) return false
        return super.dispatchGenericMotionEvent(event)
    }

    // A page script calling focus() must not pull the keyboard or focus off the composer.
    override fun onCheckIsTextEditor(): Boolean = focusAllowed && super.onCheckIsTextEditor()

    override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean =
        focusAllowed && super.requestFocus(direction, previouslyFocusedRect)
}
