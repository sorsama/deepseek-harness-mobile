package com.labteto.dshmobile.core.wire

import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The iOS pin must be the number the JVM pin is, for the same certificate.
 *
 * `RelayTls.spkiPin` asks the JDK for the SubjectPublicKeyInfo; iOS has no such call, so `SpkiPin`
 * reads it out of the certificate's DER. These tests run both over real certificates (RSA, and EC on
 * two curves, one with a subject long enough to need DER long-form lengths) and require the same
 * bytes and the same pin, and then throw malformed input at the reader, which must refuse it: a
 * certificate this code cannot read has to fail the pin, never skip it.
 */
class SpkiPinTest {

    private fun certificate(name: String): X509Certificate =
        checkNotNull(javaClass.getResourceAsStream("/tls/$name.pem")).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    @Test
    fun `the SubjectPublicKeyInfo read from the DER is the one the JDK reports, for every key type`() {
        for (name in listOf("rsa", "ec", "ec384")) {
            val cert = certificate(name)
            assertArrayEquals(name, cert.publicKey.encoded, SpkiPin.subjectPublicKeyInfo(cert.encoded))
        }
    }

    @Test
    fun `the pin is exactly RelayTls spkiPin`() {
        for (name in listOf("rsa", "ec", "ec384")) {
            val cert = certificate(name)
            assertEquals(name, RelayTls.spkiPin(cert), SpkiPin.of(cert.encoded))
        }
    }

    @Test
    fun `different keys have different pins`() {
        assertEquals(3, listOf("rsa", "ec", "ec384").map { SpkiPin.of(certificate(it).encoded) }.toSet().size)
    }

    @Test
    fun `a version 1 certificate, which has no version element, is read too`() {
        val spki = der(0x30, der(0x30, der(0x06, byteArrayOf(0x2a, 0x03))), der(0x03, byteArrayOf(0x00, 0x01, 0x02, 0x03)))
        val tbs = der(
            0x30,
            der(0x02, byteArrayOf(0x01)), // serialNumber
            der(0x30), // signature
            der(0x30), // issuer
            der(0x30), // validity
            der(0x30), // subject
            spki,
        )
        val certificate = der(0x30, tbs, der(0x30), der(0x03, byteArrayOf(0x00)))
        assertArrayEquals(spki, SpkiPin.subjectPublicKeyInfo(certificate))
    }

    @Test
    fun `sha256 agrees with the JDK across the padding boundaries`() {
        for (size in listOf(0, 1, 55, 56, 57, 63, 64, 65, 119, 120, 127, 128, 1000)) {
            val data = ByteArray(size) { (it * 31 + 7).toByte() }
            assertArrayEquals("$size bytes", MessageDigest.getInstance("SHA-256").digest(data), sha256(data))
        }
    }

    @Test
    fun `the pin encoding is standard padded base64`() {
        val cert = certificate("rsa")
        val expected = Base64.getEncoder().encodeToString(sha256(cert.publicKey.encoded))
        assertEquals(expected, SpkiPin.of(cert.encoded))
        assertEquals("a SHA-256 digest is 44 characters of padded base64", 44, expected.length)
    }

    @Test
    fun `anything that is not a certificate is refused`() {
        val real = certificate("rsa").encoded
        val refused = listOf(
            ByteArray(0),
            byteArrayOf(0x30),
            byteArrayOf(0x30, 0x00),
            byteArrayOf(0x04, 0x01, 0x00), // an OCTET STRING, not a SEQUENCE
            "not a certificate".toByteArray(),
            real.copyOf(real.size / 2), // truncated mid-element
            real.copyOf(40),
            byteArrayOf(0x30, 0x80.toByte(), 0x00, 0x00), // indefinite length is not DER
            byteArrayOf(0x30, 0x85.toByte(), 1, 2, 3, 4, 5), // length of more than four bytes
            byteArrayOf(0x30, 0x84.toByte(), 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()), // claims 2 GiB
            // A SEQUENCE of a SEQUENCE with nothing in it: no room for the five fixed elements.
            der(0x30, der(0x30)),
        )
        for ((index, bytes) in refused.withIndex()) {
            assertThrows("input #$index", IllegalArgumentException::class.java) { SpkiPin.of(bytes) }
        }
    }

    @Test
    fun `an element that overruns its parent is refused rather than read past the end`() {
        // tbsCertificate says it is 3 bytes long; its first child says it is 200.
        val bytes = byteArrayOf(0x30, 0x05, 0x30, 0x03, 0x02, 0x81.toByte(), 200.toByte())
        assertThrows(IllegalArgumentException::class.java) { SpkiPin.of(bytes) }
    }

    /** One DER element: [tag], a definite length (short or long form), then [parts]. */
    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val content = parts.fold(ByteArray(0)) { acc, part -> acc + part }
        val length = when {
            content.size < 0x80 -> byteArrayOf(content.size.toByte())
            content.size < 0x100 -> byteArrayOf(0x81.toByte(), content.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (content.size shr 8).toByte(), content.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }
}
