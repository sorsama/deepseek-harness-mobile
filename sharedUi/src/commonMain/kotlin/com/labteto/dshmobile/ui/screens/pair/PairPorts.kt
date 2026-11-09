package com.labteto.dshmobile.ui.screens.pair

import androidx.compose.runtime.Composable
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.RelayIdentity
import com.labteto.dshmobile.core.wire.ObservedKey
import com.labteto.dshmobile.core.wire.PlainHttp

/*
 * What `PairController` asks of a platform.
 *
 * One interface, because pairing is one exchange and each of its parts is something only the platform
 * can do: the HTTP client (and which TLS trust it applies), the place the relay and its token are
 * kept, and the connection that follows. On Android it is `AndroidPairPorts` over `HostsStore`,
 * `RelayCredentialStore`, `HarnessClientFactory` and `ConnectionManager`, so no stored data moved;
 * on iOS it is `IosPairPorts` over the Darwin client, `NSUserDefaults` and the Keychain.
 */
interface PairPorts {
    /**
     * A name the operator will recognise in the relay's device list.
     *
     * The platform's own: "Pixel 8" answers the question "which one do I revoke" where a generated id
     * does not.
     */
    val deviceName: String

    /** Epoch milliseconds now; the controller asks it whether a scanned code has already expired. */
    fun nowMillis(): Long

    /**
     * Run [block] with an HTTP client that speaks to a relay the way this pairing needs, then let it go.
     *
     * - [fingerprint] set: the relay's key came from a scanned QR, so every TLS connection must present
     *   exactly that key (`RelayTls.pinnedClient` on Android, `darwinHttpClient(pinnedSpkiSha256)` on
     *   iOS). There is no fallback to system validation, and a refused key is reported as
     *   `TransportFailure.CERTIFICATE_PIN`.
     * - [observed] set: nothing is known about the key yet (a typed `https` address), so the client
     *   accepts what the relay presents and records it there (trust on first use).
     * - neither: the platform's ordinary client, for plaintext and for a certificate the system trusts.
     *
     * Never both. A client built for the call is closed when [block] returns (iOS builds one
     * `NSURLSession` per call; Android's are shared and live on).
     */
    suspend fun <T> withHttp(fingerprint: String?, observed: ObservedKey?, block: suspend (PlainHttp) -> T): T

    /**
     * Remember the relay that just answered the claim and return its record.
     *
     * [relay] replaces the whole relay identity of an endpoint that was paired before (a re-pair mints
     * a new device id and may land on a regenerated key), and decides the transport: a relay paired
     * over `https` is reached over TLS whatever the caller thought.
     */
    suspend fun rememberRelay(host: String, port: Int, basePath: String, relay: RelayIdentity): HostConfig

    /** Keep the bearer token the relay showed once, against the id [rememberRelay] returned. */
    suspend fun storeToken(hostId: String, token: String)

    /** Start connecting to the relay that was just paired. Progress and failure are the connection's own. */
    suspend fun connect(config: HostConfig)
}

/**
 * The scanner the Pair screen opens over itself.
 *
 * A host-provided slot rather than a shared composable, because a camera cannot be: Android reads
 * the preview with ZXing (`QrScanSheet`, in `:app`, which also owns the camera permission, the
 * flashlight and the photo picker) and iOS with AVFoundation (`IosQrScanner`). Both are full-screen
 * sheets with the same three exits:
 *
 * - [onClose]: leave without a code;
 * - [onDecoded]: a QR was read; the text goes to [PairController.onScanned], which pairs at once;
 * - [onTypeCode]: close the scanner and focus the code field, the way out that needs no camera.
 */
typealias PairScanner = @Composable (onClose: () -> Unit, onDecoded: (String) -> Unit, onTypeCode: () -> Unit) -> Unit
