package com.labteto.dshmobile.ui.screens.runtime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.runtime.BuiltInHarnessHost
import com.labteto.dshmobile.runtime.BuiltInHarnessState
import com.labteto.dshmobile.runtime.CommandPermission
import com.labteto.dshmobile.runtime.offersCommandPermissionRestart
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.screens.connect.ConnectButtonVariant
import com.labteto.dshmobile.ui.screens.connect.DsConnectButton
import com.labteto.dshmobile.ui.screens.main.ChoiceCard
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** Where the choice is shown: the two pages word the same block a little differently. */
internal enum class CommandChoicePlace { SetUp, Control }

/**
 * "When the agent wants to run a command": Run it or Ask me first, for the built-in harness.
 *
 * Shown with its one-time explanation the first time: on the set-up page while the download runs,
 * or on the control page for someone who set up before the choice existed. It counts as seen once
 * it is on screen, and stays open for that visit until "Got it" (or the page closes), so it does
 * not jump away under the finger that picked an option.
 *
 * A change while the harness runs offers a restart: the choice is the preset new sessions start on,
 * read when the harness starts. Sessions that exist keep theirs.
 *
 * Hidden by the caller when the installed (or, during set-up, the downloading) release has no
 * `runtime.permissions`; [CommandChoiceUnsupported] stands in for it there.
 */
@Composable
internal fun CommandChoiceBlock(
    host: BuiltInHarnessHost,
    state: BuiltInHarnessState,
    place: CommandChoicePlace,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
    /** "Got it" was tapped; the set-up page folds the whole block away then. */
    onDone: () -> Unit = {},
    /** Install the update a check found (control page only). */
    onUpdate: (() -> Unit)? = null,
    /** Look for an update (control page only). */
    onCheck: (() -> Unit)? = null,
    checking: Boolean = false,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val choice by host.commandPermission.collectAsStateWithLifecycle()
    val explained by host.commandPermissionExplained.collectAsStateWithLifecycle()
    val startedWith by host.startedWithCommandPermission.collectAsStateWithLifecycle()
    // Open for this visit of the page once it has been shown, until "Got it".
    var keepOpen by rememberSaveable { mutableStateOf(false) }
    var askRestart by rememberSaveable { mutableStateOf(false) }
    val explaining = !explained || keepOpen
    // Shown once: it counts as seen the moment it is on screen, and stays for this visit.
    LaunchedEffect(explained) {
        if (!explained) {
            keepOpen = true
            host.markCommandPermissionExplained()
        }
    }

    fun pick(next: CommandPermission) {
        haptics.selection()
        if (next == choice) return
        host.setCommandPermission(next)
        if (offersCommandPermissionRestart(state.runtime, startedWith, next)) askRestart = true
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        AnimatedVisibility(
            visible = explaining,
            enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
            exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
        ) {
            CommandExplanation()
        }
        Text(
            stringResource(R.string.cmdperm_title),
            style = DsType.small13Strong,
            color = colors.labelPrimary,
            modifier = Modifier.padding(top = DsSpacing.xsmall).semantics { heading() },
        )
        CommandChoiceCards(choice = choice, onPick = ::pick)
        // A runtime with permissions version 1 cut an approval's text at 2,000 characters: "Ask me
        // first" could then ask about a command whose end the person never sees. Say so, and offer
        // the update (version 2 sends the whole text, release 2026.11.15-2).
        val installed = state.installed
        if (installed != null && installed.commandPermissions && !installed.askFirstTrusted) {
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                Text(stringResource(R.string.cmdperm_v1_update), style = DsType.small13, color = colors.warnLabel)
                val canUpdate = state.update != null && state.updateNeedsApp == null && !state.runtime.busy
                when {
                    canUpdate && onUpdate != null -> DsConnectButton(
                        text = stringResource(R.string.runtime_update_button),
                        onClick = onUpdate,
                    )
                    state.update == null && onCheck != null -> DsConnectButton(
                        text = stringResource(if (checking) R.string.runtime_update_checking else R.string.runtime_update_check),
                        onClick = onCheck,
                        variant = ConnectButtonVariant.Outline,
                        enabled = !checking && !state.runtime.busy,
                    )
                }
            }
        }
        // Changed while the harness runs, and the restart was put off: say what is still in force.
        val pending = offersCommandPermissionRestart(state.runtime, startedWith, choice)
        AnimatedVisibility(
            visible = pending && !askRestart,
            enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
            exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                Text(
                    stringResource(R.string.cmdperm_pending, commandChoiceLabel(startedWith ?: choice)),
                    style = DsType.small13,
                    color = colors.warnLabel,
                )
                DsConnectButton(
                    text = stringResource(R.string.cmdperm_restart_now),
                    onClick = {
                        haptics.confirm()
                        onRestart()
                    },
                    variant = ConnectButtonVariant.Outline,
                )
            }
        }
        AnimatedVisibility(
            visible = explaining,
            enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
            exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                Text(stringResource(R.string.cmdperm_explain_later), style = DsType.small13, color = colors.labelTertiary)
                DsConnectButton(
                    text = stringResource(R.string.cmdperm_got_it),
                    onClick = {
                        haptics.confirm()
                        keepOpen = false
                        onDone()
                    },
                    variant = if (place == CommandChoicePlace.SetUp) ConnectButtonVariant.Outline else ConnectButtonVariant.Primary,
                )
            }
        }
    }

    if (askRestart) {
        DsDialog(
            title = stringResource(R.string.cmdperm_restart_title), onDismiss = { askRestart = false },
            body = {
                Text(stringResource(R.string.cmdperm_restart_body), style = DsType.std14, color = colors.labelSecondary)
            },
            actions = {
                DialogButtons(
                    dismiss = stringResource(R.string.cmdperm_later),
                    confirm = stringResource(R.string.cmdperm_restart_now),
                    onDismiss = {
                        haptics.tap()
                        askRestart = false
                    },
                    onConfirm = {
                        askRestart = false
                        haptics.confirm()
                        onRestart()
                    },
                )
            },
        )
    }
}

/** The two options as radio cards, the chosen one marked. */
@Composable
internal fun CommandChoiceCards(choice: CommandPermission, onPick: (CommandPermission) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        CommandPermission.entries.forEach { option ->
            ChoiceCard(
                icon = commandChoiceIcon(option),
                title = commandChoiceLabel(option),
                description = stringResource(
                    when (option) {
                        CommandPermission.RunIt -> R.string.cmdperm_run_desc
                        CommandPermission.AskFirst -> R.string.cmdperm_ask_desc
                    },
                ),
                selected = option == choice,
                warn = option == CommandPermission.RunIt,
                onSelect = { onPick(option) },
            )
        }
    }
}

@Composable
internal fun commandChoiceLabel(choice: CommandPermission): String = stringResource(
    when (choice) {
        CommandPermission.RunIt -> R.string.cmdperm_run
        CommandPermission.AskFirst -> R.string.cmdperm_ask
    },
)

private fun commandChoiceIcon(choice: CommandPermission): ImageVector = when (choice) {
    CommandPermission.RunIt -> FeatherIcons.Terminal
    CommandPermission.AskFirst -> FeatherIcons.CheckSquare
}

/**
 * What the choice means on a phone, in four short pieces: why it exists, what Android still keeps
 * apart, what it does not, and (under the cards) where to change it later.
 */
@Composable
private fun CommandExplanation() {
    val colors = DsTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.bgLayer1)
            .border(1.dp, colors.borderL2, DsShapes.block)
            .padding(DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Text(
            stringResource(R.string.cmdperm_explain_title),
            style = DsType.rowTitle,
            color = colors.labelPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.cmdperm_explain_body), style = DsType.std14, color = colors.labelSecondary)
        ExplainRow(DsIcons.Shield, colors.success, stringResource(R.string.cmdperm_explain_safe_label), stringResource(R.string.cmdperm_explain_safe))
        ExplainRow(DsIcons.Warning, colors.warnLabel, stringResource(R.string.cmdperm_explain_risk_label), stringResource(R.string.cmdperm_explain_risk))
    }
}

@Composable
private fun ExplainRow(icon: ImageVector, tint: Color, label: String, text: String) {
    val colors = DsTheme.colors
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Spacer(Modifier.width(DsSpacing.small))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = DsType.small13Strong, color = tint)
            Text(text, style = DsType.small13, color = colors.labelSecondary)
        }
    }
}

/**
 * The installed release has no presets (it predates `runtime.permissions`): there is no choice to
 * make yet, only the update that brings it.
 */
@Composable
internal fun CommandChoiceUnsupported(state: BuiltInHarnessState, onUpdate: () -> Unit) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = DsSpacing.small), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Text(stringResource(R.string.cmdperm_unsupported), style = DsType.small13, color = colors.labelSecondary)
        // The update itself, once a check has found one. Until then the check is the Update
        // section's, right under this one; a second "Check for updates" here would only repeat it.
        val canUpdate = state.update != null && state.updateNeedsApp == null && !state.runtime.busy
        if (canUpdate) {
            DsConnectButton(text = stringResource(R.string.runtime_update_button), onClick = onUpdate)
        }
    }
}

/**
 * Whether the release in question has the presets: the installed one, or during a first set-up the
 * one being downloaded.
 */
internal fun commandChoiceSupported(state: BuiltInHarnessState): Boolean =
    state.installed?.commandPermissions ?: state.release?.commandPermissions ?: false
