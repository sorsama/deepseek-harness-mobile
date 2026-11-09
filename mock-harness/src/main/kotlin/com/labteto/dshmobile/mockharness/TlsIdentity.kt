package com.labteto.dshmobile.mockharness

import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64

/**
 * A self-signed certificate for a fixture relay, and the pin a client would be handed for it.
 *
 * This is the relay's default posture (`dsh-relay` generates a self-signed certificate and pairs by its
 * key), so it is what the pinned and trust-on-first-use code has to be shown against: no public CA
 * vouches for it, and the only thing that identifies the relay is [spkiPin], the
 * `base64(sha256(DER SubjectPublicKeyInfo))` the pairing QR carries.
 *
 * The certificate is made with the JDK's own `keytool`, so the mock harness needs no certificate library:
 * the JDK that runs it ships the tool next to `java`, and a fixture that cannot make its certificate says
 * so instead of serving plaintext.
 */
class TlsIdentity private constructor(
    val keyStore: KeyStore,
    val alias: String,
    private val password: String,
) {
    /** The password of the key store and of the key in it. */
    fun password(): CharArray = password.toCharArray()

    /** `base64(sha256(DER SubjectPublicKeyInfo))` of the certificate: the value `RelayTls.spkiPin` computes. */
    val spkiPin: String
        get() {
            val key = keyStore.getCertificate(alias).publicKey
            // `getEncoded()` of a public key is its X.509 SubjectPublicKeyInfo, which is what is pinned.
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(key.encoded))
        }

    companion object {
        private const val ALIAS = "dsh-fixture-relay"
        private const val PASSWORD = "fixture-password"

        /** A fresh EC P-256 certificate for `127.0.0.1` and `localhost`, valid for ten years. */
        fun generate(): TlsIdentity {
            val directory = File.createTempFile("dsh-fixture-tls", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }
            val store = File(directory, "relay.p12").apply { deleteOnExit() }
            val bin = File(System.getProperty("java.home"), "bin")
            val keytool = listOf("keytool", "keytool.exe").map { File(bin, it) }.firstOrNull { it.isFile }
                ?: error("keytool was not found in ${bin.path}; the TLS fixture needs a JDK")
            val process = ProcessBuilder(
                keytool.path,
                "-genkeypair",
                "-alias", ALIAS,
                "-keyalg", "EC",
                "-keysize", "256",
                "-sigalg", "SHA256withECDSA",
                "-dname", "CN=127.0.0.1",
                "-ext", "san=ip:127.0.0.1,dns:localhost",
                "-validity", "3650",
                "-storetype", "PKCS12",
                "-keystore", store.path,
                "-storepass", PASSWORD,
                "-keypass", PASSWORD,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "keytool failed: $output" }
            val keyStore = KeyStore.getInstance("PKCS12")
            store.inputStream().use { keyStore.load(it, PASSWORD.toCharArray()) }
            return TlsIdentity(keyStore, ALIAS, PASSWORD)
        }
    }
}
