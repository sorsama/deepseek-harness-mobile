package com.labteto.dshmobile.ui.screens.pair

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.labteto.dshmobile.core.wire.PairingPayloadResult
import com.labteto.dshmobile.core.wire.RelayPairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture path of the scanner, proved without a camera.
 *
 * "Choose a photo" exists because the camera route fails for reasons the phone cannot fix — a code
 * on a laptop screen across the room, a lens that will not focus, a device whose camera another app
 * holds. It is also the one part of the flow a device test cannot exercise reliably, so it is tested
 * here the way it is actually used: a real QR, drawn by ZXing, read back, and handed to the very
 * parser the pairing screen will hand it to.
 *
 * A picture path that returns text the parser rejects would look exactly like a broken scanner, so
 * the last assertion is the one that matters: the decoded payload is a pairing invitation this build
 * accepts, not merely a string.
 */
class QrImageDecoderTest {

    /** A payload in the shape `dsh-relay` prints at `/relay/pair`, key included. */
    private val payload: String =
        "{\"v\":1,\"kind\":\"dsh-relay-pair\",\"url\":\"https://192.168.1.5:3443\"," +
            "\"fingerprint\":\"BgFDBrBiruEnQE8l+avdOQObzT+s47sIXqsTPFpX6xI=\"," +
            "\"code\":\"12345678\",\"expiresAt\":4102444800000}"

    @Test
    fun `decodes a QR the relay would print`() {
        val (pixels, size) = renderQr(payload)

        assertEquals(payload, QrImageDecoder.decode(pixels, size, size))
    }

    @Test
    fun `decoded payload is a pairing invitation this build accepts`() {
        val (pixels, size) = renderQr(payload)

        val text = requireNotNull(QrImageDecoder.decode(pixels, size, size))
        val parsed = RelayPairing.parsePayload(text)

        assertTrue("expected a valid pairing payload, got $parsed", parsed is PairingPayloadResult.Valid)
    }

    @Test
    fun `reads a light-on-dark QR too`() {
        val (pixels, size) = renderQr(payload, inverted = true)

        assertEquals(payload, QrImageDecoder.decode(pixels, size, size))
    }

    @Test
    fun `a picture without a QR reads as nothing`() {
        val size = 320
        val blank = IntArray(size * size) { 0xFFFFFFFF.toInt() }

        assertNull(QrImageDecoder.decode(blank, size, size))
    }

    @Test
    fun `refuses nonsense dimensions instead of crashing`() {
        assertNull(QrImageDecoder.decode(IntArray(4), 0, 0))
        assertNull(QrImageDecoder.decode(IntArray(4), 3, 3))
    }

    private fun renderQr(text: String, inverted: Boolean = false): Pair<IntArray, Int> {
        val size = 480
        val matrix: BitMatrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
            ),
        )
        val dark = if (inverted) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val light = if (inverted) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            val x = index % matrix.width
            val y = index / matrix.width
            if (matrix[x, y]) dark else light
        }
        return pixels to matrix.width
    }
}
