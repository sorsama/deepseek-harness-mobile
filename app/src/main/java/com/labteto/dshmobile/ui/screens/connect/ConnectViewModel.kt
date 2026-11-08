package com.labteto.dshmobile.ui.screens.connect

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.DiscoveryEngine
import com.labteto.dshmobile.connection.HarnessSessionStore
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.connection.MdnsDiscovery
import com.labteto.dshmobile.termux.TermuxHarnessController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The Android owner of the Connect screen.
 *
 * Everything the screen decides is in [ConnectController] (`:sharedUi`), which iOS drives too; this
 * class is what ties it to Android: it is the Hilt-scoped owner whose `viewModelScope` is the
 * controller's lifetime, it builds the controller over the app's own singletons (the ports are in
 * `ConnectAndroidPorts.kt`, adapters over them, so no stored data moved), and it keeps the one
 * thing that cannot be common, the intent that opens Termux.
 */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    connectionManager: ConnectionManager,
    discoveryEngine: DiscoveryEngine,
    mdnsDiscovery: MdnsDiscovery,
    hostsStore: HostsStore,
    harnessSessions: HarnessSessionStore,
    private val termux: TermuxHarnessController,
) : ViewModel() {

    val controller: ConnectController = ConnectController(
        scope = viewModelScope,
        store = AndroidConnectStore(hostsStore),
        network = AndroidConnectNetwork(discoveryEngine, mdnsDiscovery),
        link = connectionManager,
        sessions = harnessSessions,
        loopbackHarness = TermuxLoopbackHarness(termux),
    )

    val state: StateFlow<ConnectUiState> = controller.state

    /** The intent that opens Termux, or null when it is not installed. */
    fun openTermuxIntent(): Intent? = termux.launchTermuxIntent()
}
