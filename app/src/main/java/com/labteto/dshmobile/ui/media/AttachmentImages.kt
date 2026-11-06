package com.labteto.dshmobile.ui.media

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.labteto.dshmobile.core.wire.dto.ImageAttachmentRef
import com.labteto.dshmobile.data.SessionStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoding and caching for session attachments.
 *
 * Attachment bytes do not have a URL: they come back base64-encoded inside an RPC envelope from
 * `session.attachment`, so a general image-loading library would need a custom fetcher, keyer and
 * its own HTTP client reconciled with the harness trust fence — more moving parts than the decode
 * itself. The one thing such a library would genuinely save, a bounds-only decode pass to pick a
 * sample size, is unnecessary here because the intrinsic dimensions travel with the reference.
 */

val LocalAttachmentScope = androidx.compose.runtime.staticCompositionLocalOf<Pair<String, String>?> { null }

/** What a raster looks like while it is being fetched, and after. */
sealed interface AttachmentImageState {
    data object Loading : AttachmentImageState
    data class Ready(val image: ImageBitmap) : AttachmentImageState
    data object Failed : AttachmentImageState
}

/**
 * Process-wide decoded-bitmap cache, sized against the heap rather than a fixed entry count: one
 * screenshot can outweigh a hundred thumbnails.
 */
@Singleton
class AttachmentCache @Inject constructor() {
    private val cache = object : LruCache<String, ImageBitmap>(maxSizeBytes()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, image: ImageBitmap) {
        cache.put(key, image)
    }

    fun clear() = cache.evictAll()

    private companion object {
        fun maxSizeBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/**
 * Load one attachment, decoded no larger than [targetWidthPx] needs.
 *
 * A 4000px screenshot decoded 1:1 is a 64 MB bitmap and an out-of-memory kill on a mid-range
 * device, so the intrinsic width that came over the wire picks a power-of-two sample size before
 * any pixels are allocated.
 */
@Composable
fun rememberAttachmentImage(
    ref: ImageAttachmentRef,
    targetWidthPx: Int,
    store: SessionStore,
    cache: AttachmentCache,
): State<AttachmentImageState> {
    val sample = remember(ref.width, targetWidthPx) {
        sampleSizeFor(ref.width, targetWidthPx)
    }
    val sessionId by store.currentSessionId.collectAsStateWithLifecycle()
    val owner = LocalAttachmentScope.current ?: (store.activeHostKey.orEmpty() to sessionId.orEmpty())
    val key = remember(owner, ref.attachmentId, sample) { "${owner.first}|${owner.second}|${ref.attachmentId}@$sample" }
    // A cache hit resolves during composition so a scrolled-back image does not flash a skeleton.
    val state = remember(key) {
        mutableStateOf(
            cache.get(key)?.let(AttachmentImageState::Ready) ?: AttachmentImageState.Loading,
        )
    }
    LaunchedEffect(key) {
        if (state.value is AttachmentImageState.Loading) {
            state.value = loadAttachment(ref, key, sample, store, cache, owner)
        }
    }
    return state
}

/** Cache hit, else fetch and decode. Returns the terminal state; it never throws. */
private suspend fun loadAttachment(
    ref: ImageAttachmentRef,
    key: String,
    sampleSize: Int,
    store: SessionStore,
    cache: AttachmentCache,
    owner: Pair<String, String>,
): AttachmentImageState {
    cache.get(key)?.let { return AttachmentImageState.Ready(it) }
    val bytes = store.fetchAttachment(ref, owner.second, owner.first)
    if (bytes == null || bytes.isEmpty()) return AttachmentImageState.Failed
    val decoded = withContext(Dispatchers.Default) { decode(bytes, sampleSize) }
        ?: return AttachmentImageState.Failed
    cache.put(key, decoded)
    return AttachmentImageState.Ready(decoded)
}

/** Aspect ratio of the reference, guarded against a zero dimension on a malformed payload. */
fun aspectRatioOf(width: Int, height: Int): Float =
    if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f

/**
 * The smallest power-of-two subsampling that still covers [targetWidthPx]. `BitmapFactory` rounds
 * down to a power of two anyway, so computing it explicitly keeps the result predictable.
 */
internal fun sampleSizeFor(intrinsicWidth: Int, targetWidthPx: Int): Int {
    if (intrinsicWidth <= 0 || targetWidthPx <= 0) return 1
    var sample = 1
    while (intrinsicWidth / (sample * 2) >= targetWidthPx) sample *= 2
    return sample
}

/**
 * `BitmapFactory`, not `ImageDecoder`: the app's minSdk is 26 and `ImageDecoder` arrived in 28.
 * A decode failure returns null rather than throwing — an unreadable attachment is a placeholder,
 * not a crash.
 */
private fun decode(bytes: ByteArray, sampleSize: Int): ImageBitmap? = runCatching {
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()

/** Hilt entry point for the process-wide [AttachmentCache]. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AttachmentCacheEntryPoint {
    fun attachmentCache(): AttachmentCache
}

/**
 * Resolves the process-wide [AttachmentCache]. It must be the singleton, not a per-composition
 * instance — a cache that dies with the screen would re-fetch every raster on each scroll back.
 */
@Composable
fun rememberAttachmentCache(): AttachmentCache {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, AttachmentCacheEntryPoint::class.java)
            .attachmentCache()
    }
}
