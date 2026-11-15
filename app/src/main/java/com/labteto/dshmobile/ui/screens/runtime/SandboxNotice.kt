package com.labteto.dshmobile.ui.screens.runtime

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
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.permission.sandboxRefusalShown
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.wire.dto.PermissionSelect
import com.labteto.dshmobile.runtime.sandboxNoticeTarget
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.screens.connect.DsConnectButton
import com.labteto.dshmobile.ui.screens.main.permissionPresetLabel
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * In the chat: the last request's command was refused with `SANDBOX_UNAVAILABLE`, because the
 * session is on a mode that needs a kernel sandbox (a session from an older runtime, left on
 * Workspace Write). A calm strip says so; on the built-in harness it switches the session to the
 * person's own command choice in one tap. On another harness it only explains, since only the
 * person can tell which of that harness's presets suits the computer it runs on.
 *
 * @param pending the preset a switch is in flight for, so the button does not ask twice.
 */
@Composable
fun SandboxRefusalNotice(
    nodes: List<ChatNode>,
    permissions: PermissionSelect?,
    pending: String?,
    onSwitch: (String) -> Unit,
    viewModel: BuiltInControlsViewModel = hiltViewModel(),
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    val chosen by viewModel.host.commandPermission.collectAsStateWithLifecycle()
    val active = connection.host
    val onBuiltIn = active != null && active.isLoopback && !active.isRelay && active.port == port && viewModel.host.servedThisProcess(port)
    // The phone's own harness can never sandbox a command, which also tells a refusal apart there
    // when the harness drops its code (see sandboxRefusalShown).
    val shown = remember(nodes, permissions?.currentValue, onBuiltIn) { sandboxRefusalShown(nodes, permissions?.currentValue, hostWithoutSandbox = onBuiltIn) }
    val target = sandboxNoticeTarget(onBuiltIn, chosen, permissions?.selectable?.map { it.value }.orEmpty())

    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
        exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
    ) {
        // Held through the exit, so the button does not vanish before the strip folds away.
        var lastTarget by remember { mutableStateOf<String?>(null) }
        if (shown) lastTarget = target
        Surface(color = colors.warnTertiary, border = BorderStroke(1.dp, colors.warnBorder), modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(DsIcons.Warning, contentDescription = null, tint = colors.warnLabel, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                    Spacer(Modifier.width(DsSpacing.small))
                    Text(
                        stringResource(if (lastTarget != null) R.string.sandbox_notice_builtin else R.string.sandbox_notice_other),
                        style = DsType.small13,
                        color = colors.warnLabel,
                        modifier = Modifier.weight(1f),
                    )
                }
                lastTarget?.let { preset ->
                    val name = permissions?.options?.firstOrNull { it.value == preset }?.name
                    DsConnectButton(
                        text = stringResource(R.string.sandbox_notice_switch, permissionPresetLabel(preset, name)),
                        onClick = {
                            haptics.confirm()
                            onSwitch(preset)
                        },
                        enabled = pending == null,
                    )
                }
            }
        }
    }
}
