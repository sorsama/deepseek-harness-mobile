package com.labteto.dshmobile.ui.screens.runtime

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.notify.DshNotifications
import com.labteto.dshmobile.runtime.BuiltInHarnessHost
import com.labteto.dshmobile.runtime.ChatNoticeKind
import com.labteto.dshmobile.runtime.RuntimeAction
import com.labteto.dshmobile.runtime.RuntimeInstaller
import com.labteto.dshmobile.runtime.RuntimeState
import com.labteto.dshmobile.runtime.RuntimeUpdates
import com.labteto.dshmobile.runtime.builtInChatNotice
import com.labteto.dshmobile.runtime.runtimeActionLabel
import com.labteto.dshmobile.runtime.runtimeIssueText
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.screens.connect.ConnectButtonVariant
import com.labteto.dshmobile.ui.screens.connect.DsConnectButton
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A notification asked for the built-in harness's control page ("Harness update available").
 * Set from `MainActivity`'s intent; the app root opens the page over whatever is on screen.
 */
object OpenRuntimeControls {
    private val _requested = MutableStateFlow(false)
    val requested: StateFlow<Boolean> = _requested.asStateFlow()

    fun from(intent: Intent?) {
        if (intent?.getStringExtra(DshNotifications.EXTRA_OPEN) != DshNotifications.OPEN_RUNTIME_CONTROLS) return
        // Once: a rotation re-delivers the same intent to onCreate.
        intent.removeExtra(DshNotifications.EXTRA_OPEN)
        _requested.value = true
    }

    fun done() {
        _requested.value = false
    }
}

/**
 * The built-in harness from anywhere in the app: what the control page needs when it is opened
 * from a notification or from the chat, outside the Connect and Settings screens that wire it
 * themselves. Also the update checks of the control page itself.
 */
@HiltViewModel
class BuiltInControlsViewModel @Inject constructor(
    val host: BuiltInHarnessHost,
    private val updates: RuntimeUpdates,
    private val connectionManager: ConnectionManager,
    private val hostsStore: HostsStore,
    private val updateChecker: UpdateChecker,
) : ViewModel() {

    /** The port the "this phone" record uses; 3080 until the Connect screen says otherwise. */
    val port: StateFlow<Int> = hostsStore.hosts
        .map { hosts -> hosts.firstOrNull { it.isLoopback && !it.isRelay }?.port ?: DEFAULT_PORT }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_PORT)

    val autoStart: StateFlow<Boolean> = hostsStore.settings
        .map { it.autoStartBuiltInHarness }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val connection = connectionManager.state

    fun setAutoStart(on: Boolean) {
        viewModelScope.launch { hostsStore.setSetting { it.copy(autoStartBuiltInHarness = on) } }
    }

    fun start() = host.start(port.value)

    fun stop() {
        // Leave a connection to this phone's port first, as Settings does.
        val active = connectionManager.state.value.host
        if (active != null && active.isLoopback && active.port == port.value) connectionManager.disconnect()
        host.stop()
    }

    /** Restart a running harness, or start a stopped or crashed one. The app reconnects by itself. */
    fun restart() {
        if (host.state.value.runtime is RuntimeState.Running) host.restart() else host.start(port.value)
    }

    fun setUp() = host.setUp(port.value)

    fun uninstall(keepUserData: Boolean) {
        val active = connectionManager.state.value.host
        if (active != null && active.isLoopback && active.port == port.value) connectionManager.disconnect()
        host.uninstall(keepUserData)
    }

    /** "Check now": reads the latest release and says what it found. */
    suspend fun checkNow(): RuntimeInstaller.CheckResult = updates.checkNow()

    /**
     * The app's own update path for a harness release that needs a newer app: the app's update
     * check, whose dialog offers the install; the releases page when it finds nothing newer.
     */
    fun updateApp(context: Context) {
        viewModelScope.launch {
            val outcome = runCatching { updateChecker.checkNow(BuildConfig.VERSION_NAME) }.getOrNull()
            if (outcome !is UpdateChecker.CheckOutcome.Available) RuntimeSystem.openAppUpdate(context)
        }
    }

    private companion object {
        const val DEFAULT_PORT = 3080
    }
}

/**
 * The control page (and the set-up page it can lead to) opened from outside Connect and Settings:
 * a notification tap, or "Show log" in the chat's notice.
 */
@Composable
fun BuiltInControlsOverlay(open: Boolean, atLog: Boolean, onClose: () -> Unit, viewModel: BuiltInControlsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.host.state.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    val autoStart by viewModel.autoStart.collectAsStateWithLifecycle()
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    if (!state.supported) return

    fun action(action: RuntimeAction) {
        when (action) {
            RuntimeAction.SetUp, RuntimeAction.ShowProgress -> setupOpen = true
            RuntimeAction.Start, RuntimeAction.RetryStart -> viewModel.start()
            RuntimeAction.Stop -> viewModel.stop()
            RuntimeAction.Restart -> viewModel.restart()
            RuntimeAction.RetryInstall -> {
                if (!viewModel.host.isMetered()) viewModel.setUp()
                setupOpen = true
            }
            RuntimeAction.FreeSpace -> RuntimeSystem.openStorageSettings(context)
            RuntimeAction.UpdateApp -> viewModel.updateApp(context)
            // Already on the control page; connecting and ports belong to the Connect screen.
            RuntimeAction.OpenControls, RuntimeAction.ShowLog, RuntimeAction.Connect, RuntimeAction.UseOtherPort -> Unit
        }
    }

    if (open) {
        RuntimeControlPage(
            host = viewModel.host,
            port = port,
            autoStart = autoStart,
            onAutoStartChange = viewModel::setAutoStart,
            onStart = viewModel::start,
            onStop = viewModel::stop,
            onRestart = viewModel::restart,
            onSetUp = {
                onClose()
                setupOpen = true
            },
            onAction = ::action,
            onClose = onClose,
            onUninstall = viewModel::uninstall,
            scrollToLog = atLog,
        )
    }
    if (setupOpen) {
        RuntimeSetupPage(
            host = viewModel.host,
            state = state,
            connectStage = ConnectStage.Idle,
            onSetUp = viewModel::setUp,
            onConnect = { setupOpen = false },
            canConnect = false,
            onAction = ::action,
            onClose = { setupOpen = false },
        )
    }
}

/**
 * In the chat, when the harness it talks to is the one on this phone and that harness is not up:
 * what happened, in one calm sentence, and the one thing to do (Restart, which reconnects by itself
 * once the harness is ready), plus the log. Nothing while the app is connected; the chat's own
 * "Reconnecting" strip covers a harness that is up but not reached yet.
 */
@Composable
fun BuiltInChatNotice(
    viewModel: BuiltInControlsViewModel = hiltViewModel(),
    /** What the chat shows instead while there is no notice: its connection error and "Reconnecting" strips. */
    otherwise: @Composable () -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val state by viewModel.host.state.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    var logOpen by rememberSaveable { mutableStateOf(false) }
    val active = connection.host
    val onBuiltIn = active != null && active.isLoopback && !active.isRelay && active.port == port && viewModel.host.servedThisProcess(port)
    val notice = if (state.supported) builtInChatNotice(state.runtime, onBuiltIn, connected = connection.phase == ConnectionPhase.CONNECTED) else null
    if (notice == null) otherwise()

    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
        exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
    ) {
        // Held through the exit, so the words do not vanish before the strip has folded away.
        val shown = notice ?: return@AnimatedVisibility
        val live = shown.kind == ChatNoticeKind.Restarting || shown.kind == ChatNoticeKind.Updating
        // Calm: amber, as for anything the app can put right with one tap. Red only for a start
        // that already failed, where the tap may not be enough.
        val alarm = shown.kind == ChatNoticeKind.StartFailed
        Surface(
            color = if (alarm) colors.errorTertiary else colors.warnTertiary,
            border = BorderStroke(1.dp, if (alarm) colors.errorBorder else colors.warnBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    val tint = if (alarm) colors.error else colors.warnLabel
                    if (live) {
                        CircularProgressIndicator(Modifier.padding(top = 2.dp).size(14.dp), color = tint, strokeWidth = 2.dp)
                    } else {
                        Icon(DsIcons.Warning, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                    }
                    Spacer(Modifier.width(DsSpacing.small))
                    Text(
                        when (shown.kind) {
                            ChatNoticeKind.Crashed -> stringResource(R.string.runtime_chat_crashed)
                            ChatNoticeKind.Stopped -> stringResource(R.string.runtime_chat_stopped)
                            ChatNoticeKind.Restarting -> stringResource(R.string.runtime_chat_restarting)
                            ChatNoticeKind.Updating -> stringResource(R.string.runtime_chat_updating)
                            ChatNoticeKind.StartFailed -> shown.issue?.let { runtimeIssueText(it) }.orEmpty()
                        },
                        style = DsType.small13,
                        color = tint,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!live) {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        DsConnectButton(
                            text = runtimeActionLabel(RuntimeAction.Restart),
                            onClick = {
                                haptics.confirm()
                                viewModel.restart()
                            },
                        )
                        DsConnectButton(
                            text = runtimeActionLabel(RuntimeAction.ShowLog),
                            onClick = {
                                haptics.tap()
                                logOpen = true
                            },
                            variant = ConnectButtonVariant.Outline,
                        )
                    }
                }
            }
        }
    }
    BuiltInControlsOverlay(open = logOpen, atLog = true, onClose = { logOpen = false }, viewModel = viewModel)
}
