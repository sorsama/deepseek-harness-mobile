package com.labteto.dshmobile.ui.screens.main.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsHaptics
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.HapticTicker
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.media.sampleSizeFor
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.math.max

/** A picture the platform managed to decode. */
internal sealed interface DecodedImage {
    /** The picture's own size, before it was sampled down for the screen. */
    val originalWidth: Int
    val originalHeight: Int

    class Still(
        val bitmap: ImageBitmap,
        override val originalWidth: Int,
        override val originalHeight: Int,
    ) : DecodedImage

    /** A moving picture (animated GIF or WebP). The drawable belongs to whoever decoded it. */
    class Animated(
        val drawable: Drawable,
        override val originalWidth: Int,
        override val originalHeight: Int,
    ) : DecodedImage
}

/**
 * Decodes [bytes] for the screen, sampled so the longest edge is about [maxEdge] pixels or less.
 * Returns null when this phone cannot decode the format (HEIC and AVIF depend on the Android
 * version) or the data is damaged. Run it off the main thread.
 *
 * From Android 9 the platform `ImageDecoder` is used: it reads HEIF and, from Android 12, AVIF,
 * keeps the EXIF turn, and hands back an animated drawable for GIF and WebP. Before that the older
 * `BitmapFactory` draws the first frame of a still image and turns a JPEG by its EXIF tag itself.
 */
internal fun decodeImage(bytes: ByteArray, maxEdge: Int): DecodedImage? =
    if (Build.VERSION.SDK_INT >= 28) decodeWithImageDecoder(bytes, maxEdge) else decodeWithBitmapFactory(bytes, maxEdge)

@androidx.annotation.RequiresApi(28)
private fun decodeWithImageDecoder(bytes: ByteArray, maxEdge: Int): DecodedImage? = try {
    var originalWidth = 0
    var originalHeight = 0
    val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        originalWidth = info.size.width
        originalHeight = info.size.height
        val largest = max(originalWidth, originalHeight)
        if (largest > maxEdge) decoder.setTargetSampleSize(sampleSizeFor(largest, maxEdge))
    }
    when (drawable) {
        is AnimatedImageDrawable -> DecodedImage.Animated(drawable, originalWidth, originalHeight)
        is BitmapDrawable -> DecodedImage.Still(drawable.bitmap.asImageBitmap(), originalWidth, originalHeight)
        else -> null
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

private fun decodeWithBitmapFactory(bytes: ByteArray, maxEdge: Int): DecodedImage? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(max(bounds.outWidth, bounds.outHeight), maxEdge)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (decoded == null) {
            null
        } else {
            val degrees = jpegTurn(bytes)
            val upright = if (degrees == 0) decoded else turned(decoded, degrees)
            val swap = degrees == 90 || degrees == 270
            DecodedImage.Still(
                upright.asImageBitmap(),
                if (swap) bounds.outHeight else bounds.outWidth,
                if (swap) bounds.outWidth else bounds.outHeight,
            )
        }
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

/** How far a JPEG's EXIF tag says it must be turned to stand upright (0, 90, 180 or 270). */
private fun jpegTurn(bytes: ByteArray): Int {
    if (bytes.size < 3 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return 0
    return try {
        val exif = android.media.ExifInterface(ByteArrayInputStream(bytes))
        when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }
}

private fun turned(bitmap: Bitmap, degrees: Int): Bitmap =
    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

/**
 * Pinch, pan and double-tap state for one picture. All positions are measured from the centre of
 * the viewport; see [ZoomMath] for the arithmetic.
 */
@Stable
internal class ZoomState(private val maxScale: Float = 8f) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** Cover the viewport instead of fitting inside it. */
    var fill by mutableStateOf(false)
        private set
    var viewport by mutableStateOf(IntSize.Zero)
    var picture by mutableStateOf(IntSize.Zero)

    /**
     * Where the snaps are felt: a firmer tick when a pinch comes back to the fitted picture or hits
     * the largest zoom, and when a double-tap lands. Set by the composable that draws the picture.
     */
    var haptics: DsHaptics? = null
        set(value) {
            if (field !== value) ticker = value?.ticker()
            field = value
        }
    private var ticker: HapticTicker? = null

    private fun center() = Offset(viewport.width / 2f, viewport.height / 2f)

    private fun clamp(candidate: Offset, atScale: Float): Offset {
        val (baseW, baseH) = ZoomMath.baseSize(
            viewport.width.toFloat(), viewport.height.toFloat(), picture.width.toFloat(), picture.height.toFloat(), fill,
        )
        return Offset(
            ZoomMath.clampPan(candidate.x, ZoomMath.maxPan(viewport.width.toFloat(), baseW, atScale)),
            ZoomMath.clampPan(candidate.y, ZoomMath.maxPan(viewport.height.toFloat(), baseH, atScale)),
        )
    }

    /** A pinch or drag frame: [zoom] is the change in scale since the last frame. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val focus = centroid - center()
        val next = ZoomMath.clampScale(scale * zoom, 1f, maxScale)
        val moved = Offset(
            ZoomMath.zoomAbout(offset.x, focus.x, scale, next),
            ZoomMath.zoomAbout(offset.y, focus.y, scale, next),
        ) + pan
        val before = scale
        scale = next
        offset = if (next <= 1f) Offset.Zero else clamp(moved, next)
        // Snaps: back to the fitted picture, or up against the largest zoom. Once each, on arrival.
        if ((before > 1f && next <= 1f) || (before < maxScale && next >= maxScale)) ticker?.settle()
    }

    /** Animates to [target] scale, keeping [at] (a point in the viewport; the centre if null) in place. */
    suspend fun zoomTo(target: Float, at: Offset? = null) {
        val next = ZoomMath.clampScale(target, 1f, maxScale)
        val focus = (at ?: center()) - center()
        val goal = if (next <= 1f) {
            Offset.Zero
        } else {
            clamp(
                Offset(
                    ZoomMath.zoomAbout(offset.x, focus.x, scale, next),
                    ZoomMath.zoomAbout(offset.y, focus.y, scale, next),
                ),
                next,
            )
        }
        val fromScale = scale
        val fromOffset = offset
        animate(0f, 1f, animationSpec = tween(220, easing = DsAnimations.inkDecelerate)) { t, _ ->
            scale = fromScale + (next - fromScale) * t
            offset = Offset(fromOffset.x + (goal.x - fromOffset.x) * t, fromOffset.y + (goal.y - fromOffset.y) * t)
        }
    }

    suspend fun doubleTap(at: Offset) {
        zoomTo(nextZoomStep(scale, maxScale), at)
        ticker?.settle()
    }

    /** Zoom by [factor]. The buttons already tap, so only an arrival at either end adds a tick. */
    suspend fun zoomBy(factor: Float) {
        zoomTo(scale * factor)
        if (scale <= 1f || scale >= maxScale) ticker?.settle()
    }

    fun toggleFill() {
        fill = !fill
        scale = 1f
        offset = Offset.Zero
    }
}

/**
 * One picture: decoded off the main thread, drawn fitted, and, when a [zoom] state is given,
 * pinchable, draggable and double-tappable. Animated GIF and WebP play.
 *
 * [onDecoded] hears the picture's own size once it is known (for the header). A picture this
 * phone cannot decode becomes a notice that offers a saved copy instead.
 */
@Composable
internal fun ImageStage(
    bytes: ByteArray,
    maxEdge: Int,
    actions: PreviewActions,
    modifier: Modifier = Modifier,
    zoom: ZoomState? = null,
    onDecoded: (DecodedImage) -> Unit = {},
) {
    var decoded by remember(bytes) { mutableStateOf<DecodedImage?>(null) }
    var failed by remember(bytes) { mutableStateOf(false) }
    LaunchedEffect(bytes) {
        val result = withContext(Dispatchers.Default) { decodeImage(bytes, maxEdge) }
        if (result == null) {
            failed = true
        } else {
            decoded = result
            zoom?.picture = IntSize(result.originalWidth, result.originalHeight)
            onDecoded(result)
        }
    }
    val picture = decoded
    val haptics = rememberDsHaptics()
    SideEffect { zoom?.haptics = haptics }
    when {
        failed -> NoticeCard(
            icon = FeatherIcons.Image,
            title = stringResource(R.string.preview_cant_show_title),
            body = stringResource(R.string.preview_image_unsupported),
            modifier = modifier,
            action = { SaveCopyButton(actions) },
        )
        picture == null -> ImageSkeleton(modifier)
        else -> {
            val scope = rememberCoroutineScope()
            val gestures = if (zoom == null) {
                if (actions.onOpenFull != null) Modifier.clickable(onClick = actions.onOpenFull) else Modifier
            } else {
                Modifier
                    .onSizeChanged { zoom.viewport = it }
                    .pointerInput(zoom) {
                        detectTapGestures(onDoubleTap = { at -> scope.launch { zoom.doubleTap(at) } })
                    }
                    .pointerInput(zoom) {
                        detectTransformGestures { centroid, pan, gestureZoom, _ -> zoom.transform(centroid, pan, gestureZoom) }
                    }
            }
            PreviewFrame(modifier, framed = zoom == null) {
                val layer = if (zoom == null) {
                    Modifier
                } else {
                    Modifier.graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offset.x
                        translationY = zoom.offset.y
                    }
                }
                val fill = zoom?.fill == true
                Box(Modifier.fillMaxSize().then(gestures).clipToBounds(), contentAlignment = Alignment.Center) {
                    when (picture) {
                        is DecodedImage.Still -> Image(
                            picture.bitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(layer)
                                .then(if (zoom == null) Modifier.padding(DsSpacing.small) else Modifier),
                            contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
                        )
                        is DecodedImage.Animated -> AnimatedPicture(picture.drawable, fill, Modifier.fillMaxSize().then(layer))
                    }
                }
            }
        }
    }
}

/** An animated GIF or WebP in a plain `ImageView`, which is what knows how to run its frames. */
@Composable
private fun AnimatedPicture(drawable: Drawable, fill: Boolean, modifier: Modifier) {
    DisposableEffect(drawable) {
        if (Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable) drawable.stop()
        }
    }
    AndroidView(
        factory = { context -> ImageView(context).apply { setImageDrawable(drawable) } },
        update = { it.scaleType = if (fill) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER },
        modifier = modifier,
    )
}
