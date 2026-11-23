package com.labteto.dshmobile.core.uisync

/**
 * The touch areas of the latest `ui-sync/hit-rects`, in the web view's own pixels.
 *
 * The page reports rectangles in CSS pixels with their device-pixel ratio; a touch arrives in
 * view pixels. [of] scales once, so the hot path ([contains]) is a few float comparisons per
 * rectangle with no allocation. Edges follow the usual half-open rule: left and top are inside,
 * right and bottom are not.
 */
class UiSyncHitRegion private constructor(
    /** Left, top, right, bottom of each rectangle, in view pixels. */
    private val edges: FloatArray,
) {
    val count: Int get() = edges.size / 4
    val isEmpty: Boolean get() = edges.isEmpty()

    fun contains(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        var i = 0
        while (i < edges.size) {
            if (x >= edges[i] && y >= edges[i + 1] && x < edges[i + 2] && y < edges[i + 3]) return true
            i += 4
        }
        return false
    }

    /** Left, top, right, bottom of rectangle [index], in view pixels; for tests and drawing a debug outline. */
    fun bounds(index: Int): List<Float> = (0 until 4).map { edges[index * 4 + it] }

    companion object {
        val Empty: UiSyncHitRegion = UiSyncHitRegion(FloatArray(0))

        /**
         * [message]'s rectangles in view pixels. Its own `dpr` is the scale when the page sent one,
         * because that is the page's CSS-to-device ratio including any page zoom; [fallbackScale]
         * (the display density) is used when it did not. Rectangles that end up empty are dropped.
         */
        fun of(message: UiSyncPageMessage.HitRects, fallbackScale: Float): UiSyncHitRegion =
            of(message.rects, message.dpr?.toFloat() ?: fallbackScale)

        fun of(rects: List<UiSyncRect>, scale: Float): UiSyncHitRegion {
            if (rects.isEmpty() || !scale.isFinite() || scale <= 0f) return Empty
            val out = FloatArray(minOf(rects.size, MAX_RECTS) * 4)
            var n = 0
            for (rect in rects) {
                if (n == out.size) break
                val left = (rect.x * scale).toFloat()
                val top = (rect.y * scale).toFloat()
                val right = ((rect.x + rect.w) * scale).toFloat()
                val bottom = ((rect.y + rect.h) * scale).toFloat()
                if (!(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())) continue
                if (right <= left || bottom <= top) continue
                out[n++] = left
                out[n++] = top
                out[n++] = right
                out[n++] = bottom
            }
            return if (n == 0) Empty else UiSyncHitRegion(out.copyOf(n))
        }
    }
}

/**
 * Who gets a touch: the web layer above the chat, or the native chat below it.
 *
 * The rule from the bridge doc: on the first finger down, a point inside any reported rectangle
 * sends the whole gesture to the web view; anything else goes to the native view underneath.
 * Hover and mouse-wheel events (a mouse on a Chromebook, TalkBack's explore by touch) are decided
 * per event by the same rectangles, so wheel scrolling and touch exploration of the chat work
 * through the transparent layer.
 *
 * While the layer is not [enabled] (still loading, covered by the drawer or Details, off screen)
 * every event is native. Main thread only, like the view that owns it.
 */
class UiSyncTouchRouter {
    var region: UiSyncHitRegion = UiSyncHitRegion.Empty
    var enabled: Boolean = false

    /** Whether the gesture in progress went to the web view on its first finger down. */
    var gestureToWeb: Boolean = false
        private set

    /** The first finger went down at ([x], [y]): decide the gesture and say whether the web view takes it. */
    fun onDown(x: Float, y: Float): Boolean {
        gestureToWeb = claims(x, y)
        return gestureToWeb
    }

    /** A gesture ended (up or cancel). */
    fun onGestureEnd() {
        gestureToWeb = false
    }

    /** Whether a hover or generic pointer event at ([x], [y]) belongs to the web view. */
    fun claims(x: Float, y: Float): Boolean = enabled && region.contains(x, y)
}
