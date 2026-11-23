package com.labteto.dshmobile.ui.addons

import android.annotation.SuppressLint
import android.content.Context
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
 * It never takes focus, so the native composer keeps the keyboard and Back and key presses stay
 * native; a plugin text field cannot be typed into from the phone in this version.
 */
@SuppressLint("ViewConstructor")
internal class UiAddonsWebView(context: Context) : WebView(context) {
    val router = UiSyncTouchRouter()

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        // The page keeps the whole desktop client in its tree, only see-through; it is not something
        // TalkBack should walk. The chat below stays reachable, because hover events outside the
        // rectangles fall through (see dispatchHoverEvent).
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
        // TalkBack's explore by touch arrives as hover from the touchscreen. The layer is hidden from
        // accessibility, so all of it goes to the chat: every spot of the chat stays explorable.
        val claimed = !event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN) && router.claims(event.x, event.y)
        if (!claimed) {
            // A mouse that leaves an item: let the page see it go, so its hover state does not stick,
            // and still hand the event on to the chat below (false).
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
        if (event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER) && !router.claims(event.x, event.y)) return false
        return super.dispatchGenericMotionEvent(event)
    }

    // Never focusable: a page script calling focus() must not pull the keyboard or focus off the composer.
    override fun onCheckIsTextEditor(): Boolean = false
}
