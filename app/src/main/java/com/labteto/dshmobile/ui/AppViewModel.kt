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
import com.labteto.dshmobile.update.AppJustUpdatedTracker
import com.labteto.dshmobile.update.AvailableUpdate
import com.labteto.dshmobile.update.InAppUpdater
import com.labteto.dshmobile.update.JustUpdated
import com.labteto.dshmobile.update.UpdateChecker
import com.labteto.dshmobile.update.UpdaterState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    hostsStore: HostsStore,
    connectionManager: ConnectionManager,
    private val updateChecker: UpdateChecker,
    private val termux: TermuxHarnessController,
    private val runtimeUpdates: com.labteto.dshmobile.runtime.RuntimeUpdates,
    private val updater: InAppUpdater,
    justUpdatedTracker: AppJustUpdatedTracker,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = hostsStore.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    val connectionState: StateFlow<ConnectionUiState> = connectionManager.state

    /** A newer release to offer, or null. See [UpdateChecker]. */
    val availableUpdate: StateFlow<AvailableUpdate?> = updateChecker.available

    /** Safe to call on every resume: the checker rate-limits itself. */
    fun checkForUpdate(currentVersion: String) {
        viewModelScope.launch { updateChecker.checkOnce(currentVersion) }
        // The built-in harness's own release check; it rate-limits itself the same way.
        runtimeUpdates.onAppOpen()
    }

    fun dismissUpdate(version: String) {
        viewModelScope.launch { updateChecker.dismiss(version) }
    }

    // ---- In-app update ----------------------------------------------------------------

    /** The newest release ahead of this build, even one the person declined; for Settings. */
    val latestUpdate: StateFlow<AvailableUpdate?> = updateChecker.latest

    /** Download, check and install; see [InAppUpdater]. */
    val updaterState: StateFlow<UpdaterState> = updater.state

    fun downloadUpdate(update: AvailableUpdate, allowMetered: Boolean) = updater.download(update, allowMetered)
    fun cancelUpdate() = updater.cancel()
    fun installUpdateAnyway() = updater.installAnyway()
    fun installUpdate() = updater.install()
    fun acknowledgeUpdate() = updater.acknowledge()
    fun installPermissionRequested() = updater.permissionRequested()
    fun installPermissionReturned() = updater.permissionReturned()
    fun installPromptShown() = updater.confirmShown()
    fun installPromptReturned() = updater.confirmReturned()
    fun installPermissionIntent() = updater.permissionIntent()

    private val _justUpdated = MutableStateFlow(justUpdatedTracker.tracker.consume())

    /** "Updated to x.y.z", once, on the first launch after an in-app update. */
    val justUpdated: StateFlow<JustUpdated?> = _justUpdated.asStateFlow()

    fun dismissJustUpdated() {
        _justUpdated.value = null
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
