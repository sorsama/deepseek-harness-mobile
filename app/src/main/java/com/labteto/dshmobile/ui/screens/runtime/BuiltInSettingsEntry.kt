package com.labteto.dshmobile.ui.screens.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.runtime.BuiltInHarnessHost
import com.labteto.dshmobile.runtime.RuntimeAction
import com.labteto.dshmobile.runtime.RuntimeState
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * Settings' "Harness on this phone": one row with the built-in harness's state that opens its
 * control page (and, before it is set up, the set-up page). Only where the built-in harness can run.
 */
@Composable
internal fun BuiltInSettingsEntry(
    host: BuiltInHarnessHost,
    port: Int,
    autoStart: Boolean,
    onAutoStartChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onSetUp: () -> Unit,
    onUninstall: (Boolean) -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val context = LocalContext.current
    val state by host.state.collectAsStateWithLifecycle()
    var controlsOpen by rememberSaveable { mutableStateOf(false) }
    var controlsAtLog by rememberSaveable { mutableStateOf(false) }
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    if (!state.supported) return

    val runtime = state.runtime
    Section(stringResource(R.string.settings_termux)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.row)
                .clickable(role = Role.Button) {
                    haptics.tap()
                    if (state.installed == null && !runtime.busy && runtime !is RuntimeState.Failed) setupOpen = true else controlsOpen = true
                }
                .heightIn(min = 64.dp)
                .padding(vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateDot(
                when (runtime) {
                    is RuntimeState.Running -> StateDotState.Done
                    is RuntimeState.Failed -> StateDotState.Error
                    is RuntimeState.Stopped, RuntimeState.NotInstalled, is RuntimeState.NotSupported -> StateDotState.Idle
                    else -> StateDotState.Running
                },
                size = 8.dp,
            )
            Spacer(Modifier.width(DsSpacing.medium))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.runtime_card_title), style = DsType.std14, color = colors.labelPrimary)
                Text(
                    when (runtime) {
                        RuntimeState.NotInstalled -> stringResource(R.string.runtime_status_not_set_up)
                        is RuntimeState.Running -> stringResource(R.string.runtime_card_running, port.toString())
                        is RuntimeState.Stopped -> stringResource(R.string.runtime_status_stopped)
                        is RuntimeState.Starting -> stringResource(R.string.runtime_card_starting)
                        is RuntimeState.Stopping -> stringResource(R.string.runtime_card_stopping)
                        is RuntimeState.Updating -> stringResource(R.string.runtime_card_updating)
                        is RuntimeState.Failed -> stringResource(R.string.runtime_status_needs_attention)
                        else -> stringResource(R.string.runtime_card_setting_up)
                    },
                    style = DsType.small13,
                    color = colors.labelTertiary,
                )
            }
            if (state.updateWorthShowing) {
                UpdateBadge(onClick = { controlsOpen = true })
                Spacer(Modifier.width(DsSpacing.small))
            }
            Icon(DsIcons.ChevronRight, contentDescription = null, tint = colors.labelTertiary, modifier = Modifier.size(18.dp))
        }
    }

    fun action(action: RuntimeAction) {
        when (action) {
            RuntimeAction.SetUp, RuntimeAction.ShowProgress -> setupOpen = true
            RuntimeAction.Start, RuntimeAction.RetryStart -> onStart()
            RuntimeAction.Stop -> onStop()
            RuntimeAction.Restart -> onRestart()
            RuntimeAction.RetryInstall -> {
                if (!host.isMetered()) onSetUp()
                setupOpen = true
            }
            RuntimeAction.FreeSpace -> RuntimeSystem.openStorageSettings(context)
            RuntimeAction.UpdateApp -> host.updateApp { RuntimeSystem.openAppUpdate(context) }
            RuntimeAction.OpenControls -> controlsOpen = true
            RuntimeAction.ShowLog -> {
                controlsAtLog = true
                controlsOpen = true
            }
            // Connecting and moving ports belong to the Connect screen, where the card offers them.
            RuntimeAction.Connect, RuntimeAction.UseOtherPort -> controlsOpen = true
        }
    }

    if (setupOpen) {
        RuntimeSetupPage(
            host = host,
            state = state,
            connectStage = ConnectStage.Idle,
            onSetUp = onSetUp,
            onConnect = { setupOpen = false },
            canConnect = false,
            onAction = ::action,
            onClose = { setupOpen = false },
        )
    }
    if (controlsOpen) {
        RuntimeControlPage(
            host = host,
            port = port,
            autoStart = autoStart,
            onAutoStartChange = onAutoStartChange,
            onStart = onStart,
            onStop = onStop,
            onRestart = onRestart,
            onSetUp = {
                controlsOpen = false
                setupOpen = true
            },
            onAction = ::action,
            onClose = {
                controlsOpen = false
                controlsAtLog = false
            },
            onUninstall = onUninstall,
            scrollToLog = controlsAtLog,
        )
    }
}
