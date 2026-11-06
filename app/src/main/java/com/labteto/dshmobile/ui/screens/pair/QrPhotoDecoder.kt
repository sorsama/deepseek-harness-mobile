package com.labteto.dshmobile.ui.screens.pair

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What came of looking for a pairing code in a picture the user picked. */
sealed interface PhotoScan {
    data class Read(val text: String) : PhotoScan

    /** A picture was read, but there was no QR code in it. */
    data object NoCode : PhotoScan

    /** The file could not be opened or decoded at all. */
    data object Unreadable : PhotoScan
}

/**
 * Reads a pairing QR out of a picture the user chose, off the main thread.
 *
 * A picked image is whatever the gallery has: a screenshot (already pixel-identical to the QR), a
 * photo of a laptop screen (perspective-skewed but usually decodable with TRY_HARDER), or a camera
 * photo stored sideways, because `BitmapFactory` does not apply EXIF orientation. Rather than pull
 * in an EXIF dependency for one screen, the code is looked for at each quarter turn — four cheap
 * attempts on an already-downscaled bitmap.
 */
suspend fun readQrFromPhoto(context: Context, uri: Uri): PhotoScan = withContext(Dispatchers.IO) {
    val bitmap = downscaled(context, uri) ?: return@withContext PhotoScan.Unreadable
    try {
        var turn = 0
        while (turn < 4) {
            val candidate = if (turn == 0) bitmap else rotated(bitmap, turn * 90f)
            if (candidate != null) {
                val pixels = IntArray(candidate.width * candidate.height)
                candidate.getPixels(pixels, 0, candidate.width, 0, 0, candidate.width, candidate.height)
                val text = QrImageDecoder.decode(pixels, candidate.width, candidate.height)
                if (candidate !== bitmap) candidate.recycle()
                if (text != null) return@withContext PhotoScan.Read(text)
            }
            turn++
        }
        PhotoScan.NoCode
    } finally {
        bitmap.recycle()
    }
}

/**
 * The longest edge kept when decoding. A modern phone photo is 12+ megapixels; decoding that at
 * full size costs a lot of memory for no extra range, because a QR code that is legible at 4000px
 * is legible at 1600px too.
 */
private const val MAX_EDGE = 1600

private fun downscaled(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // A bounds-only decode is *supposed* to return no bitmap, so its null is not a failure and
    // must not be folded into one — the stream is opened, read for the dimensions, and closed
    // separately from the check that the stream existed at all.
    val probe = context.contentResolver.openInputStream(uri) ?: return null
    probe.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= MAX_EDGE || bounds.outHeight / (sample * 2) >= MAX_EDGE) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    }
}

private fun rotated(source: Bitmap, degrees: Float): Bitmap? = try {
    val matrix = Matrix().apply { postRotate(degrees) }
    Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
} catch (_: IllegalArgumentException) {
    // A rotation the device could not allocate is not worth a crash: the other turns still run.
    null
}
