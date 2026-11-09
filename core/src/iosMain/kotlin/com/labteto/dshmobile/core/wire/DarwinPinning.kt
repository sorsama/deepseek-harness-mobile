package com.labteto.dshmobile.core.wire

import io.ktor.client.engine.darwin.ChallengeHandler
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.io.IOException
import platform.Foundation.*
import platform.Security.*
import platform.posix.memcpy

/**
 * Certificate pinning for `dsh-relay` on the Darwin engine: the same decision `RelayTls.pinnedClient`
 * makes on the JVM, in `NSURLSession`'s terms.
 *
 * The relay's default posture is a self-signed certificate whose SHA-256 SubjectPublicKeyInfo it
 * publishes in the pairing QR, so system trust evaluation can never pass and must not be asked to.
 * The pin *is* the trust decision, exactly as in `RelayTls`:
 *
 * - the **leaf** certificate's key is hashed ([SpkiPin], proven equal to the JVM's value) and
 *   compared with the pin from pairing; an exact match accepts the connection with a credential built
 *   from the server trust the system handed over; anything else cancels the challenge;
 * - there is **no fallback to CA validation** when the pin misses: a relay whose key changed is an
 *   event the person has to see (a regenerated certificate, or someone else answering on that
 *   address), not one to paper over;
 * - it **fails closed**: no server trust, no certificate, a certificate that cannot be read, or an
 *   empty pin all take the cancel path, never the accept one;
 * - the hostname is deliberately not checked (the key is the identity, and the relay's certificate
 *   covers only the addresses it saw at generation time), as in `RelayTls`.
 *
 * Challenges that are not about the server's trust (HTTP auth, client certificates) get default
 * handling; the relay asks for none of them, and none can be used to skip the pin.
 *
 * A cancelled challenge surfaces from the engine as a bare `NSURLErrorCancelled`, which says nothing
 * about *why*. [PinMismatchLog] bridges that: the handler records the mismatch before it cancels, and
 * [darwinHttpClient] turns the engine's failure into an [IosPinMismatchException] when it matches a
 * mismatch just recorded for the same host, which is what [hasPinMismatch] looks for.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal fun pinnedChallengeHandler(expectedPin: String, mismatches: PinMismatchLog): ChallengeHandler =
    { _, _, challenge, completionHandler ->
        val space = challenge.protectionSpace
        if (space.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
        } else {
            val trust = space.serverTrust
            val presented = if (trust == null) null else leafPin(trust)
            if (trust != null && presented != null && expectedPin.isNotEmpty() && presented == expectedPin) {
                completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
            } else {
                mismatches.record(space.host)
                completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            }
        }
    }

/**
 * Trust on first use, the iOS counterpart of `RelayTls.trustOnFirstUseClient`: accept whatever key the
 * server presents and record it in [observed].
 *
 * It exists for the two flows that cannot pin in advance (passcode pairing, where the fingerprint
 * only arrives with the claim, and probing an address before the device has any relationship with
 * it). Neither sends a credential, and the claim that follows is a single-use code; but whoever
 * answers on that address during pairing does end up holding the enrolment, which is why this is
 * not a substitute for [pinnedChallengeHandler] and why the UI has to say so. Like the JVM
 * version, the hostname is not checked and system trust is not consulted. Unlike the JVM version it
 * still fails closed on a certificate it cannot read: no server trust, no certificate or an
 * unreadable one cancels the challenge, so [observed] is only ever set to a real pin.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal fun learningChallengeHandler(observed: ObservedKey): ChallengeHandler =
    { _, _, challenge, completionHandler ->
        val space = challenge.protectionSpace
        if (space.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
        } else {
            val trust = space.serverTrust
            val presented = if (trust == null) null else leafPin(trust)
            if (trust != null && presented != null) {
                observed.pin = presented
                completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
            } else {
                completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            }
        }
    }

/** The pin of the server's leaf certificate, or null when there is none or it cannot be read. */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private fun leafPin(trust: SecTrustRef): String? {
    if (SecTrustGetCertificateCount(trust) < 1) return null
    // Index 0 of the chain is the leaf (the server's own certificate), as `chain[0]` is on the JVM.
    val certificate = SecTrustGetCertificateAtIndex(trust, 0) ?: return null
    val dataRef = SecCertificateCopyData(certificate) ?: return null
    val der = (CFBridgingRelease(dataRef) as NSData).toByteArray()
    return try {
        SpkiPin.of(der)
    } catch (e: IllegalArgumentException) {
        null
    }
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private fun NSData.toByteArray(): ByteArray {
    val result = ByteArray(length.toInt())
    if (result.isEmpty()) return result
    result.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return result
}

/**
 * Remembers that a pinned challenge was just refused for a host.
 *
 * The engine reports a cancelled challenge as `NSURLErrorCancelled` with nothing else, so this is
 * the side channel that says it was the pin. A mismatch explains a failure only for the same host
 * and only for a few seconds, so an unrelated cancellation later is not misread as one.
 */
internal class PinMismatchLog {
    private class Entry(val host: String, val at: TimeMark)

    private val latest = atomic<Entry?>(null)

    fun record(host: String) {
        latest.value = Entry(host.lowercase(), TimeSource.Monotonic.markNow())
    }

    fun explains(host: String, error: DarwinHttpRequestException): Boolean = explains(host, error.origin)

    @OptIn(UnsafeNumber::class)
    fun explains(host: String, error: NSError): Boolean {
        if (error.code != NSURLErrorCancelled) return false
        val entry = latest.value ?: return false
        return entry.host == host.lowercase() && entry.at.elapsedNow() < WINDOW
    }

    private companion object {
        val WINDOW = 10.seconds
    }
}

/**
 * The relay's key is not the one pinned when this device paired.
 *
 * Wraps the engine's own failure, and is what [hasPinMismatch] finds on the cause chain — the iOS
 * counterpart of the JVM's `PinMismatchException` under OkHttp's `SSLHandshakeException`.
 */
internal class IosPinMismatchException(cause: Throwable) :
    IOException("relay public key does not match the pin recorded when this device paired", cause)
