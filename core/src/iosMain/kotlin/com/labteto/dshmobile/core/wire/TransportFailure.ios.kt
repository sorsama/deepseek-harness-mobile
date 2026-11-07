package com.labteto.dshmobile.core.wire

import kotlinx.io.IOException

/**
 * iOS actual: there is no pin marker yet, so no failure is a pin mismatch.
 *
 * **Provisional.** Certificate pinning on iOS is part of the Darwin transport (M2b), which will
 * decide how a mismatch surfaces — most likely a challenge handler that throws its own marker,
 * the way the JVM trust manager throws `PinMismatchException`. Until it does, reporting a plain TLS
 * failure for a pin miss is the honest answer, and this stays `false`.
 */
internal actual fun hasPinMismatch(t: Throwable): Boolean = false

/**
 * iOS actual: conservative, and **provisional until the Darwin engine shows its real exceptions**.
 *
 * The JVM version leans on `java.net` subtypes that do not exist here, and what the iOS transport
 * throws is not known yet: Ktor's Darwin engine wraps an `NSError` (domain `NSURLErrorDomain`) in an
 * `IOException` subclass, but which codes arrive, and with what text, has not been observed. So this
 * claims only what the shared message rule can say about an I/O failure — a timeout, a refusal, an
 * unreachable network, by the words the system uses — and answers [TransportFailure.OTHER] for
 * everything else, including DNS and TLS, which it cannot yet tell from other failures without
 * guessing. Once M2b has real exceptions in hand this should switch on the `NSURLError` code
 * instead of the text.
 */
internal actual fun classifyPlatformFailure(t: Throwable): TransportFailure = when (t) {
    is IOException -> classifyByMessage(t)
    else -> TransportFailure.OTHER
}
