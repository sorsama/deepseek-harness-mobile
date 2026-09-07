package com.labteto.dshmobile.ui.screens.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.connection.DiscoveryEngine
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.connection.ProbeTimeouts
import com.labteto.dshmobile.connection.RelayCredentialStore
import com.labteto.dshmobile.termux.HarnessControl
import com.labteto.dshmobile.termux.TermuxHarnessController
import com.labteto.dshmobile.ui.screens.connect.LoopbackStatus
import com.labteto.dshmobile.ui.screens.connect.toLoopbackStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-language choices: the 11 shipped locales, plus following the system.
 *
 * A null [tag] clears the override. Without it the picker is a one-way door — once a language is
 * chosen there is no way back to whatever the device is set to.
 */
data class LanguageOption(val tag: String?, val label: String?, val labelRes: Int? = null)

val LanguageOptions = listOf(
    LanguageOption(null, null, com.labteto.dshmobile.R.string.settings_language_system),
    LanguageOption("en", "English"),
    LanguageOption("zh", "中文"),
    LanguageOption("hi", "हिन्दी"),
    LanguageOption("es", "Español"),
    LanguageOption("fr", "Français"),
    LanguageOption("ar", "العربية"),
    LanguageOption("bn", "বাংলা"),
    LanguageOption("pt", "Português"),
    LanguageOption("ru", "Русский"),
    LanguageOption("ur", "اردو"),
    LanguageOption("th", "ไทย"),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val hostsStore: HostsStore,
    private val credentials: RelayCredentialStore,
    private val connectionManager: ConnectionManager,
    private val termux: TermuxHarnessController,
    private val discoveryEngine: DiscoveryEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    /** What the Termux controller is doing; the card shows it and re-probes when it settles. */
    val harnessControl: StateFlow<HarnessControl> = termux.state

    val termuxInstalled: Boolean get() = termux.termuxInstalled

    private val _loopback = MutableStateFlow<LoopbackStatus>(LoopbackStatus.Unknown)

    /** What the harness on this device is doing, probed once when the card appears. */
    val loopback: StateFlow<LoopbackStatus> = _loopback.asStateFlow()

    /** The port the "this phone" record uses; 3080 until the connect screen says otherwise. */
    val loopbackPort: StateFlow<Int> = hostsStore.hosts
        .map { hosts -> hosts.firstOrNull { it.isLoopback && !it.isRelay }?.port ?: DEFAULT_PORT }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_PORT)

    val connectionState: StateFlow<ConnectionUiState> = connectionManager.state.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ConnectionUiState()
    )

    init {
        viewModelScope.launch {
            hostsStore.settings.collect { _state.value = it }
        }
        viewModelScope.launch {
            var previous: HarnessControl = HarnessControl.Idle
            termux.state.collect { control ->
                val settledAfterStop = previous is HarnessControl.Stopping && control is HarnessControl.Idle
                if (control is HarnessControl.Started || settledAfterStop) refreshLoopback()
                previous = control
            }
        }
    }

    /** Probe `127.0.0.1:port` with the cookie this app holds for it, as the connect screen does. */
    fun refreshLoopback() {
        viewModelScope.launch {
            val port = loopbackPort.value
            val known = hostsStore.hosts.first().firstOrNull { it.isLoopback && !it.isRelay && it.port == port }
            _loopback.value = runCatching {
                discoveryEngine.probeOutcome(LOOPBACK, port, ProbeTimeouts.Manual, preflight = true, config = known)
            }.getOrElse { ProbeOutcome.Other(it.message ?: "probe failed") }.toLoopbackStatus()
        }
    }

    fun hasTermuxPermission(): Boolean = termux.hasPermission()

    fun openTermuxIntent(): Intent? = termux.launchTermuxIntent()

    fun startHarness() = termux.start(loopbackPort.value)

    /**
     * Stop the harness on this device, leaving the app disconnected on purpose.
     *
     * Stopping the very host the app is connected to would otherwise leave the manager retrying a
     * socket that is never coming back, with the chat still on screen; disconnecting first puts
     * the connect screen up, where the card says the harness is down.
     */
    fun stopHarness() {
        val active = connectionManager.state.value.host
        if (active != null && active.isLoopback && active.port == loopbackPort.value) {
            connectionManager.disconnect()
        }
        termux.stop(loopbackPort.value)
    }

    fun acknowledgeHarnessControl() = termux.acknowledge()

    fun set(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            hostsStore.setSetting(transform)
        }
    }

    fun disconnect() {
        connectionManager.disconnect()
    }

    /**
     * Forget every remembered harness; the connect screen starts from discovery again.
     *
     * Relay credentials go with them. `removeHost` already drops each one, and the sweep afterwards
     * catches anything orphaned by an earlier build — a stored bearer token nothing can present any
     * more is only a liability. Revoking the device entry itself happens on the relay, not here.
     */
    fun forgetHosts(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            hostsStore.hosts.first().forEach { hostsStore.removeHost(it.id) }
            credentials.clear()
            onDone()
        }
    }

    /** Forget which session to reopen per harness; the app lands on the newest one next time. */
    fun clearLastSessions(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            hostsStore.clearLastSessions()
            onDone()
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val DEFAULT_PORT = 3080
    }
}
