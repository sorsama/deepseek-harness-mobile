package com.labteto.dshmobile.runtime

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/**
 * Checks a release manifest's Ed25519 signature against a key compiled into the app.
 *
 * `java.security.Signature("Ed25519")` exists only from API 33 and the app supports 26, so the
 * check runs on EdDSA-Java (`net.i2p.crypto:eddsa`, 63 KB, no dependencies). That library accepts
 * a signature whose scalar `S` is not reduced (CVE-2020-36843: a second valid signature for an
 * already signed message, not a forgery). The check below refuses those itself, so a signature is
 * valid here exactly when RFC 8032 says it is.
 *
 * This runs on the raw bytes of `manifest.json`, before any of it is parsed.
 */
class ManifestVerifier(private val publicKey: ByteArray = PINNED_PUBLIC_KEY) {

    init {
        require(publicKey.size == 32) { "an Ed25519 public key is 32 bytes" }
    }

    /** [signatureFile] is the content of `manifest.json.sig`: base64, surrounding whitespace allowed. */
    fun verify(manifest: ByteArray, signatureFile: ByteArray): Boolean {
        val signature = decodeSignature(signatureFile) ?: return false
        if (!isCanonicalScalar(signature)) return false
        return try {
            val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
            val key = EdDSAPublicKey(EdDSAPublicKeySpec(publicKey, spec))
            val engine = EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm))
            engine.initVerify(key)
            engine.verifyOneShot(manifest, signature)
        } catch (e: Exception) {
            // A malformed point or a library complaint is a failed check, never a crash.
            false
        }
    }

    companion object {
        /**
         * The runtime signing key, raw 32 bytes, from `keys/runtime-signing-ed25519.pub.pem` in the
         * runtime repository. Replacing it means shipping an app with the new key before the first
         * release signed by it.
         */
        const val PINNED_PUBLIC_KEY_BASE64 = "RZAmCAbwfFeuxZ4JVxXsgYDS49uOycb4bnjt4DoW00w="

        /** First 16 hex digits of SHA-256 of the raw key, as the manifest's `signing.keyId` states it. */
        const val PINNED_KEY_ID = "20edea657d2e377a"

        val PINNED_PUBLIC_KEY: ByteArray get() = Base64.getDecoder().decode(PINNED_PUBLIC_KEY_BASE64)

        /** The group order of Ed25519, `2^252 + 27742317777372353535851937790883648493`. */
        private val L = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))

        fun keyId(publicKey: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(publicKey).joinToString("") { "%02x".format(it) }.take(16)

        internal fun decodeSignature(file: ByteArray): ByteArray? {
            val text = file.decodeToString().filterNot { it.isWhitespace() }
            val bytes = try {
                Base64.getDecoder().decode(text)
            } catch (e: IllegalArgumentException) {
                return null
            }
            return bytes.takeIf { it.size == 64 }
        }

        /** RFC 8032 5.1.7: the second half of the signature, little-endian, must be below L. */
        internal fun isCanonicalScalar(signature: ByteArray): Boolean {
            val s = BigInteger(1, signature.copyOfRange(32, 64).reversedArray())
            return s < L
        }
    }
}
