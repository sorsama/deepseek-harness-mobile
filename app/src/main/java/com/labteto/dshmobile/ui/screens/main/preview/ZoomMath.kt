package com.labteto.dshmobile.ui.screens.main.preview

import kotlin.math.max
import kotlin.math.min

/**
 * The arithmetic behind pinch-zoom and pan, kept free of UI types so it can be tested.
 *
 * Everything is measured from the centre of the viewport. A picture is drawn at its "base" size
 * (fitted inside the viewport, or filling it) and the gesture state scales and shifts that.
 */
internal object ZoomMath {

    /**
     * The size the picture is drawn at before any zoom: scaled to sit inside the viewport when
     * [fill] is false, or to cover it when true. Returns (0, 0) for an empty picture or viewport.
     */
    fun baseSize(viewW: Float, viewH: Float, imageW: Float, imageH: Float, fill: Boolean): Pair<Float, Float> {
        if (viewW <= 0f || viewH <= 0f || imageW <= 0f || imageH <= 0f) return 0f to 0f
        val scale = if (fill) max(viewW / imageW, viewH / imageH) else min(viewW / imageW, viewH / imageH)
        return imageW * scale to imageH * scale
    }

    /** How far the picture may be dragged either way on one axis: zero until it outgrows the viewport. */
    fun maxPan(viewSize: Float, contentSize: Float, scale: Float): Float =
        max(0f, (contentSize * scale - viewSize) / 2f)

    /**
     * The new offset after zooming from [oldScale] to [newScale] with the fingers (or the tap) at
     * [focus], measured from the viewport centre, so the point under them stays put.
     */
    fun zoomAbout(offset: Float, focus: Float, oldScale: Float, newScale: Float): Float {
        if (oldScale <= 0f) return offset
        return offset + (focus - offset) * (1f - newScale / oldScale)
    }

    /** [value] held between [-limit, limit]. */
    fun clampPan(value: Float, limit: Float): Float = value.coerceIn(-limit, limit)

    /** A zoom factor kept inside [min, max]. */
    fun clampScale(scale: Float, minScale: Float, maxScale: Float): Float = scale.coerceIn(minScale, maxScale)
}
