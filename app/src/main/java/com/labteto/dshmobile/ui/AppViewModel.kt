package com.labteto.dshmobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.termux.HarnessControl
import com.labteto.dshmobile.termux.TermuxHarnessController
import com.labteto.dshmobile.termux.TermuxScripts
import com.labteto.dshmobile.update.AvailableUpdate
import com.labteto.dshmobile.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    hostsStore: HostsStore,
    connectionManager: ConnectionManager,
    private val updateChecker: UpdateChecker,
    private val termux: TermuxHarnessController,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = hostsStore.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    val connectionState: StateFlow<ConnectionUiState> = connectionManager.state

    /** A newer release to offer, or null. See [UpdateChecker]. */
    val availableUpdate: StateFlow<AvailableUpdate?> = updateChecker.available

    fun checkForUpdate(currentVersion: String) {
        viewModelScope.launch { updateChecker.checkOnce(currentVersion) }
    }

    fun dismissUpdate(version: String) {
        viewModelScope.launch { updateChecker.dismiss(version) }
    }

    // ---- Install via Termux ---------------------------------------------------------

    val termuxInstalled: Boolean get() = termux.termuxInstalled

    /** The Termux controller's state, so the update dialog can report a failed install. */
    val harnessControl: StateFlow<HarnessControl> = termux.state

    fun hasTermuxPermission(): Boolean = termux.hasPermission()

    /** Download [update]'s APK inside Termux, verify it, and open the package installer. */
    fun installViaTermux(update: AvailableUpdate) {
        val apk = update.apk ?: return
        termux.installUpdate(update.version, apk.url, apk.name, update.checksums?.url)
    }

    /** The same download-and-open as one line, for a shell that reaches the phone over SSH. */
    fun installCommand(update: AvailableUpdate): String? {
        val apk = update.apk ?: return null
        return runCatching { TermuxScripts.installOneLiner(apk.url, apk.name) }.getOrNull()
    }

    fun acknowledgeHarnessControl() = termux.acknowledge()
}
