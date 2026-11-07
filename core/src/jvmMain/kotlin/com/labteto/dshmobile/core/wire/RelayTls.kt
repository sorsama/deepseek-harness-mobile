package com.labteto.dshmobile.core.wire

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Transport trust for a `dsh-relay` listener, pinned to its public key.
 *
 * The relay's default posture is a self-signed certificate whose SHA-256 SubjectPublicKeyInfo it
 * publishes in the pairing QR. Pinning the *key* rather than the certificate is deliberate on the
 * relay's side — it can renew with the same key without breaking paired devices — and it is what
 * this file compares.
 *
 * OkHttp's own `CertificatePinner` cannot do this job alone. Pinning runs *after* the trust manager
 * has accepted the chain, so a self-signed certificate is rejected by the platform CA store before
 * the pin is ever consulted. The pin has to *be* the trust decision, which is what
 * [pinnedClient] installs.
 *
 * Pure JVM (`javax.net.ssl`), so `:core` stays free of Android imports.
 */
object RelayTls {

    /**
     * SHA-256 of a certificate's DER SubjectPublicKeyInfo, base64.
     *
     * `PublicKey.getEncoded()` on an X.509 certificate is the DER SubjectPublicKeyInfo, so this is
     * byte-for-byte the value the relay publishes (`spkiFingerprint` in the plugin's `src/tls.ts`,
     * which exports the key as `spki`/`der` and base64s its SHA-256).
     */
    fun spkiPin(certificate: X509Certificate): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded),
        )

    /**
     * A client that records the key it is offered and trusts it.
     *
     * This is trust on first use, and it is not a substitute for [pinnedClient]. It exists because
     * two of the relay's own flows cannot pin in advance:
     *
     * - **Passcode pairing.** The user reads a code off the relay's screen and types the address
     *   themselves; there is no QR, so there is no fingerprint until the claim answers with one.
     * - **Liveness probing.** Asking an address whether it is a relay happens before this device has
     *   any relationship with it at all.
     *
     * Neither sends a credential, and the claim that follows is a single-use code that is worthless
     * once consumed — but an attacker who can answer on that address during pairing does end up
     * holding the enrolment. That is a real difference from the QR flow and the UI has to say so
     * rather than let "paired" mean two different things.
     *
     * [observed] receives the presented key, so the caller can store the pin it actually spoke to.
     */
    fun trustOnFirstUseClient(base: OkHttpClient, observed: ObservedKey): OkHttpClient {
        val trustManager = LearningTrustManager(observed)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), null)
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .build()
    }

    /**
     * A client that trusts exactly one public key.
     *
     * The hostname verifier passes unconditionally, and that is not a hole: the trust manager has
     * already refused every certificate but the pinned one, so the key *is* the identity and the
     * SAN adds nothing. It matters in practice because the relay's generated certificate covers the
     * addresses it saw at generation time, and a phone may reach it by an address — a port-forwarded
     * name, a VPN address — that was never in that list.
     *
     * There is deliberately no fallback to CA validation when the pin misses. A relay whose key
     * changed is an event the user has to see, not one to paper over: it means either a
     * regenerated certificate (the plugin mints a new key whenever its address set changes) or
     * somebody else answering on that address.
     */
    fun pinnedClient(base: OkHttpClient, fingerprint: String): OkHttpClient {
        val trustManager = PinnedTrustManager(fingerprint)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), null)
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .build()
    }
}

/**
 * The served certificate did not carry the pinned public key.
 *
 * A distinct type rather than a bare [CertificateException] so the failure survives the wrapping
 * OkHttp does — it reaches the app as an `SSLHandshakeException` with this as a cause — and can be
 * reported as "this relay's certificate changed" instead of a generic TLS error.
 */
class PinMismatchException(
    val expected: String,
    val presented: String,
) : CertificateException("relay public key does not match the pin recorded when this device paired")

/** Trusts one public key and nothing else. See [RelayTls.pinnedClient] for why this replaces the CA store. */
private class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("relay presented no certificate")
        val presented = RelayTls.spkiPin(leaf)
        // Constant-time is pointless here — the expected value is public, printed on the relay's own
        // pairing page — but a plain equality on two base64 strings is also the whole check, so it
        // is written out rather than hidden behind a helper.
        if (presented != fingerprint) throw PinMismatchException(fingerprint, presented)
    }

    /** This client never presents a certificate; the relay never asks for one. */
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("dsh-relay clients do not present certificates")
    }

    /** Empty on purpose: no issuer is acceptable, only the pinned leaf. */
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Somewhere for [RelayTls.trustOnFirstUseClient] to report the key it was offered. */
class ObservedKey {
    @Volatile
    var pin: String? = null
        internal set
}

/** Accepts any server key and records it. See [RelayTls.trustOnFirstUseClient] for what that costs. */
private class LearningTrustManager(private val observed: ObservedKey) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("relay presented no certificate")
        observed.pin = RelayTls.spkiPin(leaf)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("dsh-relay clients do not present certificates")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
