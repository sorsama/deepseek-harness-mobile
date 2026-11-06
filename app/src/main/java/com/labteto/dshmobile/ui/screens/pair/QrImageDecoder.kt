package com.labteto.dshmobile.ui.screens.pair

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * Reads a QR code out of still pixels — the "choose a photo" half of the scanner.
 *
 * The camera decodes off its own preview, but the picture path has no scanner behind it: people
 * screenshot the relay's pairing page, or photograph a laptop screen from across the room, and
 * expecting them to then aim a camera at their own gallery is a dead end that reads as "the QR code
 * does not work". This takes the pixels of that picture and hands back the payload.
 *
 * Deliberately free of `android.graphics`: keeping it to plain ARGB ints means the picture path can
 * be proved on the JVM — encode a QR, decode it back, check the pairing parser accepts the result —
 * instead of only on a device that happens to have a camera and a gallery.
 */
object QrImageDecoder {

    /**
     * QR only. The pairing code is never a barcode, and narrowing the formats keeps a stray
     * supermarket barcode from being reported as a pairing code.
     */
    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * The text of the QR code in [pixels] (row-major ARGB, `width * height` of them), or null.
     *
     * Both polarities are tried: a screenshot is dark-on-light, but a photo of an inverted theme,
     * a negative image, or a reflection can come out light-on-dark, and ZXing's reader does not
     * flip the image for itself.
     */
    fun decode(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val source = RGBLuminanceSource(width, height, pixels)
        return read(source) ?: read(source.invert())
    }

    private fun read(source: LuminanceSource): String? = try {
        MultiFormatReader()
            .decode(BinaryBitmap(HybridBinarizer(source)), hints)
            .text
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    } catch (_: ReaderException) {
        null
    }
}
