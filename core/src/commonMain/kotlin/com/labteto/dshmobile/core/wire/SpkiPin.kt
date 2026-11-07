package com.labteto.dshmobile.core.wire

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The relay's certificate pin, computed from a DER certificate on a platform with no `java.security`.
 *
 * `dsh-relay` publishes `base64(sha256(DER SubjectPublicKeyInfo))` of its certificate and the app
 * pins that. On the JVM `RelayTls.spkiPin` gets the SubjectPublicKeyInfo from
 * `X509Certificate.getPublicKey().getEncoded()`; Apple's Security framework hands back only the raw
 * key bytes (no algorithm header), and rebuilding the header from a table of key types and sizes is
 * how a pin silently stops matching when a relay changes its key type. So this reads the
 * SubjectPublicKeyInfo straight out of the certificate's own bytes, for any key type, and
 * `SpkiPinTest` checks it byte for byte against the JVM's answer on RSA and EC certificates.
 *
 * It only locates and hashes; it does not validate the certificate. The pin *is* the trust decision
 * (see `RelayTls`), and anything it cannot read is an error, never a pass.
 */
internal object SpkiPin {

    /**
     * `base64(sha256(SubjectPublicKeyInfo))` of the certificate in [certificateDer], the exact value
     * `RelayTls.spkiPin` gives for the same certificate.
     *
     * @throws IllegalArgumentException when [certificateDer] is not a DER X.509 certificate.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun of(certificateDer: ByteArray): String =
        Base64.Default.encode(sha256(subjectPublicKeyInfo(certificateDer)))

    /**
     * The DER `SubjectPublicKeyInfo` of a certificate: the whole element, tag and length included.
     *
     * ```
     * Certificate ::= SEQUENCE {
     *   tbsCertificate SEQUENCE {
     *     [0] version        -- present from v2 on, absent in v1
     *     serialNumber, signature, issuer, validity, subject,
     *     subjectPublicKeyInfo SEQUENCE { … }   -- this one
     *     … },
     *   signatureAlgorithm, signatureValue }
     * ```
     */
    fun subjectPublicKeyInfo(certificateDer: ByteArray): ByteArray {
        val certificate = Der.read(certificateDer, 0, certificateDer.size, SEQUENCE)
        val tbs = Der.read(certificateDer, certificate.contentStart, certificate.contentEnd, SEQUENCE)
        var at = tbs.contentStart
        // The optional explicit version: [0] EXPLICIT INTEGER.
        if (at < tbs.contentEnd && (certificateDer[at].toInt() and 0xff) == CONTEXT_0) {
            at = Der.read(certificateDer, at, tbs.contentEnd, CONTEXT_0).end
        }
        // serialNumber, signature, issuer, validity, subject: five elements to step over.
        repeat(5) { at = Der.next(certificateDer, at, tbs.contentEnd).end }
        val spki = Der.read(certificateDer, at, tbs.contentEnd, SEQUENCE)
        return certificateDer.copyOfRange(spki.start, spki.end)
    }

    private const val SEQUENCE = 0x30
    private const val CONTEXT_0 = 0xA0

    /** Just enough DER to walk a certificate: a tag, a definite length, bounds on every read. */
    private object Der {
        class Element(val start: Int, val contentStart: Int, val end: Int) {
            val contentEnd: Int get() = end
        }

        /** The element at [at], which must carry [tag], and must end within [limit]. */
        fun read(bytes: ByteArray, at: Int, limit: Int, tag: Int): Element {
            val element = next(bytes, at, limit)
            require((bytes[at].toInt() and 0xff) == tag) { "unexpected DER tag at $at" }
            return element
        }

        /** The element at [at], whatever its tag, and must end within [limit]. */
        fun next(bytes: ByteArray, at: Int, limit: Int): Element {
            require(at in 0 until limit && limit <= bytes.size) { "DER element out of bounds" }
            var cursor = at + 1 // past the tag; a high-tag-number form never appears in a certificate
            require(cursor < limit) { "truncated DER length" }
            val first = bytes[cursor++].toInt() and 0xff
            val length: Long = if (first < 0x80) {
                first.toLong()
            } else {
                // Long form: the low bits say how many length bytes follow. 0x80 alone is the
                // indefinite form, which DER forbids.
                val count = first and 0x7f
                require(count in 1..4 && cursor + count <= limit) { "bad DER length" }
                var value = 0L
                repeat(count) { value = (value shl 8) or (bytes[cursor++].toLong() and 0xff) }
                value
            }
            require(length >= 0 && cursor + length <= limit) { "DER element overruns its parent" }
            return Element(start = at, contentStart = cursor, end = cursor + length.toInt())
        }
    }
}

/** SHA-256 of [bytes]; the platform's own implementation on each side. */
internal expect fun sha256(bytes: ByteArray): ByteArray
