package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.ImageAttachmentRef
import com.labteto.dshmobile.ui.media.AttachmentImageState
import com.labteto.dshmobile.ui.media.aspectRatioOf
import com.labteto.dshmobile.ui.media.rememberAttachmentCache
import com.labteto.dshmobile.ui.media.rememberAttachmentImage
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * One raster attachment in the transcript: a placeholder holding the reference's own aspect ratio
 * while it loads, then the decoded image, tappable for a full-screen view.
 *
 * Reserving the right box up front matters more here than usual — the transcript auto-scrolls to
 * the bottom, and an image that grows after layout would drag the view out from under the reader.
 */
@Composable
fun AttachmentImage(
    ref: ImageAttachmentRef,
    modifier: Modifier = Modifier,
    maxHeight: Int = 240,
    contentDescription: String? = null,
) {
    val colors = DsTheme.colors
    val store = rememberSessionStore()
    val cache = rememberAttachmentCache()
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // Decode for the widest the transcript can ever show it, not for the source resolution.
    val targetWidthPx = remember(screenWidthDp) { with(density) { screenWidthDp.dp.roundToPx() } }
    val state by rememberAttachmentImage(
        ref = ref,
        targetWidthPx = targetWidthPx,
        store = store,
        cache = cache,
    )
    var zoomed by remember(ref.attachmentId) { mutableStateOf(false) }
    val ratio = remember(ref.width, ref.height) {
        aspectRatioOf(ref.width, ref.height)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight.dp)
            .clip(DsShapes.block)
            .background(colors.bgModulePlatform),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = state) {
            AttachmentImageState.Loading -> Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio)
                    .skeleton(base = colors.bgModulePlatform, highlight = colors.hover),
            )
            AttachmentImageState.Failed -> Text(
                stringResource(R.string.chat_image_failed),
                style = DsType.caption11,
                color = colors.labelTertiary,
                modifier = Modifier.padding(16.dp),
            )
            is AttachmentImageState.Ready -> Image(
                bitmap = current.image,
                contentDescription = contentDescription ?: stringResource(R.string.chat_image_open),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { zoomed = true },
            )
        }
    }

    val ready = state as? AttachmentImageState.Ready
    if (zoomed && ready != null) ImageViewer(ready.image, contentDescription) { zoomed = false }
}

/** Full-screen image: a fixed close bar above it, bounded pinch zoom and drag pan. */
@Composable
private fun ImageViewer(bitmap: ImageBitmap, title: String?, onClose: () -> Unit) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val colors = DsTheme.colors
    DsFullScreenDialog(onDismissRequest = onClose) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.orEmpty(), Modifier.weight(1f).padding(8.dp), style = DsType.caption11, color = colors.labelTertiary)
            DsIconButton(Icons.Filled.Close, stringResource(R.string.common_close), onClose)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clip(DsShapes.block), contentAlignment = Alignment.Center) {
            val width = with(LocalDensity.current) { maxWidth.toPx() }
            val height = with(LocalDensity.current) { maxHeight.toPx() }
            Image(
                bitmap = bitmap,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().pointerInput(bitmap, width, height) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        // Pan only as far as the scaled image overhangs the viewport.
                        val x = width * (scale - 1) / 2
                        val y = height * (scale - 1) / 2
                        offset = if (scale == 1f) Offset.Zero else Offset(
                            (offset.x + pan.x).coerceIn(-x, x), (offset.y + pan.y).coerceIn(-y, y),
                        )
                    }
                }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            )
        }
    }
}
