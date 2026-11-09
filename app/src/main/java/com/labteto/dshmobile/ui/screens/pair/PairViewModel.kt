package com.labteto.dshmobile.ui.screens.pair

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.HarnessClientFactory
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.connection.RelayCredentialStore
import com.labteto.dshmobile.connection.RelayIdentity
import com.labteto.dshmobile.core.wire.ObservedKey
import com.labteto.dshmobile.core.wire.OkHttpPlainHttp
import com.labteto.dshmobile.core.wire.PlainHttp
import com.labteto.dshmobile.core.wire.RelayTls
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * The Android owner of the Pair screen.
 *
 * Everything the screen decides is in [PairController] (`:sharedUi`), which iOS drives too; this class
 * is what ties it to Android: it is the Hilt-scoped owner whose `viewModelScope` is the controller's
 * lifetime, and it builds the controller over the app's own singletons ([AndroidPairPorts], adapters
 * over them, so no stored data moved).
 *
 * It is scoped to the activity rather than to the screen, so it outlives a closed pairing screen. That
 * is why [PairUiState.paired] is a one-shot signal the screen consumes.
 */
@HiltViewModel
class PairViewModel @Inject constructor(
    hostsStore: HostsStore,
    credentials: RelayCredentialStore,
    clientFactory: HarnessClientFactory,
    connectionManager: ConnectionManager,
    okHttpClient: OkHttpClient,
) : ViewModel() {

    val controller: PairController = PairController(
        scope = viewModelScope,
        ports = AndroidPairPorts(hostsStore, credentials, clientFactory, connectionManager, okHttpClient),
    )

    val state: StateFlow<PairUiState> = controller.state
}

/**
 * [PairPorts] over what the Android app already has.
 *
 * The HTTP is OkHttp, wrapped in `OkHttpPlainHttp` exactly as the `RelayPairing.claim` extensions do:
 * a pinned client from the factory for a scanned QR, `RelayTls.trustOnFirstUseClient` for a typed
 * `https` address, the app's shared client otherwise. Nothing here is new behaviour; it is the
 * `PairViewModel` of before, cut at the points where iOS does something else.
 */
internal class AndroidPairPorts(
    private val hostsStore: HostsStore,
    private val credentials: RelayCredentialStore,
    private val clientFactory: HarnessClientFactory,
    private val connectionManager: ConnectionManager,
    private val okHttpClient: OkHttpClient,
) : PairPorts {

    /**
     * `Build.MODEL` rather than a generated id: the list is read by a person deciding what to revoke,
     * and "Pixel 8" answers that question where a UUID does not.
     */
    override val deviceName: String =
        listOfNotNull(Build.MANUFACTURER?.takeIf { Build.MODEL?.startsWith(it, ignoreCase = true) == false }, Build.MODEL)
            .joinToString(" ")
            .ifBlank { "Android device" }

    override fun nowMillis(): Long = System.currentTimeMillis()

    override suspend fun <T> withHttp(
        fingerprint: String?,
        observed: ObservedKey?,
        block: suspend (PlainHttp) -> T,
    ): T {
        val client = when {
            fingerprint != null -> clientFactory.httpClient(fingerprint)
            observed != null -> RelayTls.trustOnFirstUseClient(okHttpClient, observed)
            else -> okHttpClient
        }
        return block(OkHttpPlainHttp(client))
    }

    override suspend fun rememberRelay(host: String, port: Int, basePath: String, relay: RelayIdentity): HostConfig =
        hostsStore.rememberHost(
            name = host,
            host = host,
            port = port,
            isLoopback = false,
            basePath = basePath,
            relay = relay,
        )

    override suspend fun storeToken(hostId: String, token: String) = credentials.put(hostId, token)

    override suspend fun connect(config: HostConfig) = connectionManager.connect(config)
}
