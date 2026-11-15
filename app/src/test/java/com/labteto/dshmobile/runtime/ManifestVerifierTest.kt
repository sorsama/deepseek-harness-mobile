package com.labteto.dshmobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.util.Base64

class ManifestVerifierTest {

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/runtime/$name")) { "missing fixture $name" }.use { it.readBytes() }

    private val message = """{"schemaVersion":1,"releaseId":"test"}""".toByteArray()

    @Test
    fun `a signature from the JDK's Ed25519 verifies`() {
        val key = TestSigningKey()
        assertTrue(ManifestVerifier(key.rawPublicKey).verify(message, key.signatureFile(message)))
    }

    @Test
    fun `whitespace around the base64 signature is ignored`() {
        val key = TestSigningKey()
        val sig = "\r\n  " + Base64.getEncoder().encodeToString(key.sign(message)) + " \n\n"
        assertTrue(ManifestVerifier(key.rawPublicKey).verify(message, sig.toByteArray()))
    }

    @Test
    fun `one changed byte in the manifest fails`() {
        val key = TestSigningKey()
        val sig = key.signatureFile(message)
        val tampered = message.copyOf().also { it[5] = (it[5] + 1).toByte() }
        assertFalse(ManifestVerifier(key.rawPublicKey).verify(tampered, sig))
        // Appending is a change too: the exact bytes are what is signed.
        assertFalse(ManifestVerifier(key.rawPublicKey).verify(message + '\n'.code.toByte(), sig))
    }

    @Test
    fun `a signature by another key fails`() {
        val signer = TestSigningKey()
        val other = TestSigningKey()
        assertFalse(ManifestVerifier(other.rawPublicKey).verify(message, signer.signatureFile(message)))
    }

    @Test
    fun `signatures that are not 64 bytes of base64 fail without throwing`() {
        val key = TestSigningKey()
        val verifier = ManifestVerifier(key.rawPublicKey)
        assertFalse(verifier.verify(message, ByteArray(0)))
        assertFalse(verifier.verify(message, "not base64 !!".toByteArray()))
        assertFalse(verifier.verify(message, Base64.getEncoder().encode(key.sign(message).copyOf(63))))
        assertFalse(verifier.verify(message, Base64.getEncoder().encode(ByteArray(64))))
    }

    @Test
    fun `a non-canonical S (S plus the group order) is refused`() {
        // EdDSA-Java alone accepts this second signature for the same message (CVE-2020-36843);
        // the verifier must not.
        val key = TestSigningKey()
        val sig = key.sign(message)
        val l = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
        val s = BigInteger(1, sig.copyOfRange(32, 64).reversedArray())
        val bumped = s.add(l)
        val little = bumped.toByteArray().reversedArray().copyOf(32)
        val malleated = sig.copyOfRange(0, 32) + little
        assertFalse(ManifestVerifier.isCanonicalScalar(malleated))
        assertFalse(ManifestVerifier(key.rawPublicKey).verify(message, Base64.getEncoder().encode(malleated)))
    }

    @Test
    fun `the pinned key is the README's key and its id matches`() {
        assertEquals(32, ManifestVerifier.PINNED_PUBLIC_KEY.size)
        assertEquals(ManifestVerifier.PINNED_KEY_ID, ManifestVerifier.keyId(ManifestVerifier.PINNED_PUBLIC_KEY))
        assertEquals("20edea657d2e377a", ManifestVerifier.PINNED_KEY_ID)
    }

    @Test
    fun `the real 2026-11-14-1 release verifies against the pinned key and parses`() {
        val manifest = resource("release-2026.11.14-1.manifest.json")
        val sig = resource("release-2026.11.14-1.manifest.json.sig")
        assertTrue(ManifestVerifier().verify(manifest, sig))
        val parsed = RuntimeManifest.parse(manifest)
        assertEquals("2026.11.14-1", parsed.releaseId)
        assertEquals(ManifestVerifier.PINNED_KEY_ID, parsed.signing.keyId)
        assertEquals(listOf("base", "harness"), parsed.archs.getValue("x86_64").layers.map { it.name })
        assertEquals("dsh web: http://127.0.0.1:", parsed.runtime.readinessPrefix)
        // And one flipped byte in it does not.
        assertFalse(ManifestVerifier().verify(manifest.copyOf().also { it[40] = (it[40] + 1).toByte() }, sig))
    }
}
