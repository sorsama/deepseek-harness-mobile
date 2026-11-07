package com.labteto.dshmobile.core.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * The pin has to be the same number the relay printed, computed the same way.
 *
 * The relay publishes `base64(sha256(DER SubjectPublicKeyInfo))`. Java's
 * `X509Certificate.getPublicKey().getEncoded()` *is* that DER SubjectPublicKeyInfo, which is the
 * whole reason this is two lines rather than an ASN.1 walk — but "is the same encoding" is exactly
 * the kind of claim that is either true or silently produces a value that never matches anything.
 */
class RelayTlsTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val other: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun `the pin is base64 sha256 over the DER SubjectPublicKeyInfo`() {
        val certificate = certificateFor(keyPair)
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded),
        )
        assertEquals(expected, RelayTls.spkiPin(certificate))
    }

    /** Two keys must not collide, or the pin proves nothing. */
    @Test
    fun `a different key gives a different pin`() {
        assertNotEquals(RelayTls.spkiPin(certificateFor(keyPair)), RelayTls.spkiPin(certificateFor(other)))
    }

    @Test
    fun `the trust manager accepts only the pinned key`() {
        val certificate = certificateFor(keyPair)
        val manager = trustManagerFor(RelayTls.spkiPin(certificate))
        // No exception: this is the relay this device paired with.
        manager.checkServerTrusted(arrayOf(certificate), "RSA")

        val impostor = trustManagerFor(RelayTls.spkiPin(certificateFor(other)))
        val failure = assertThrows(PinMismatchException::class.java) {
            impostor.checkServerTrusted(arrayOf(certificate), "RSA")
        }
        assertEquals(RelayTls.spkiPin(certificate), failure.presented)
    }

    /** An empty chain is a refusal, not a pass. */
    @Test
    fun `no certificate is refused`() {
        val manager = trustManagerFor("anything")
        assertThrows(CertificateException::class.java) { manager.checkServerTrusted(emptyArray(), "RSA") }
    }

    /**
     * Trust-on-first-use records what it saw, so a passcode pairing can store a pin it never
     * verified — and the UI can say which of the two happened.
     */
    @Test
    fun `first use records the key it was offered`() {
        val certificate = certificateFor(keyPair)
        val observed = ObservedKey()
        assertNull(observed.pin)
        val client = RelayTls.trustOnFirstUseClient(okhttp3.OkHttpClient(), observed)
        val manager = client.x509TrustManager!!
        manager.checkServerTrusted(arrayOf(certificate), "RSA")
        assertEquals(RelayTls.spkiPin(certificate), observed.pin)
    }

    @Test
    fun `a pinned client installs the pin, not the platform CA store`() {
        val pin = RelayTls.spkiPin(certificateFor(keyPair))
        val client = RelayTls.pinnedClient(okhttp3.OkHttpClient(), pin)
        assertTrue(client.x509TrustManager!!.acceptedIssuers.isEmpty())
        assertThrows(PinMismatchException::class.java) {
            client.x509TrustManager!!.checkServerTrusted(arrayOf(certificateFor(other)), "RSA")
        }
    }

    /**
     * A self-signed certificate, built without a certificate library.
     *
     * Only the public key is ever read — the pin is computed over it and nothing else is validated —
     * so a minimal certificate carrying the right key is a faithful stand-in for what the relay
     * serves.
     */
    private fun certificateFor(pair: KeyPair): X509Certificate {
        val holder = javax.security.auth.x500.X500Principal("CN=relay.test")
        return object : X509Certificate() {
            override fun getPublicKey() = pair.public
            override fun getEncoded(): ByteArray = pair.public.encoded
            override fun getSubjectX500Principal() = holder
            override fun getIssuerX500Principal() = holder
            override fun checkValidity() = Unit
            override fun checkValidity(date: java.util.Date?) = Unit
            override fun getVersion() = 3
            override fun getSerialNumber(): BigInteger = BigInteger.ONE
            @Deprecated("Deprecated in Java")
            override fun getIssuerDN() = holder
            @Deprecated("Deprecated in Java")
            override fun getSubjectDN() = holder
            override fun getNotBefore() = java.util.Date(0)
            override fun getNotAfter() = java.util.Date(Long.MAX_VALUE)
            override fun getTBSCertificate(): ByteArray = ByteArray(0)
            override fun getSignature(): ByteArray = ByteArray(0)
            override fun getSigAlgName() = "SHA256withRSA"
            override fun getSigAlgOID() = "1.2.840.113549.1.1.11"
            override fun getSigAlgParams(): ByteArray? = null
            override fun getIssuerUniqueID(): BooleanArray? = null
            override fun getSubjectUniqueID(): BooleanArray? = null
            override fun getKeyUsage(): BooleanArray? = null
            override fun getBasicConstraints() = -1
            override fun verify(key: java.security.PublicKey?) = Unit
            override fun verify(key: java.security.PublicKey?, sigProvider: String?) = Unit
            override fun toString() = "relay.test"
            override fun hasUnsupportedCriticalExtension() = false
            override fun getCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
            override fun getNonCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
            override fun getExtensionValue(oid: String?): ByteArray? = null
        }
    }

    private fun trustManagerFor(pin: String) =
        RelayTls.pinnedClient(okhttp3.OkHttpClient(), pin).x509TrustManager!!
}
