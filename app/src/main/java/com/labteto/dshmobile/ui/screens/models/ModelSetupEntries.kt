package com.labteto.dshmobile.ui.screens.models

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.usage.ModelSetupProblem
import com.labteto.dshmobile.models.ModelReadiness
import com.labteto.dshmobile.models.ModelSetupMonitor
import com.labteto.dshmobile.models.ModelsRequest
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.screens.connect.ConnectButtonVariant
import com.labteto.dshmobile.ui.screens.connect.DsConnectButton
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Hands the entry points their one dependency. */
@HiltViewModel
class ModelSetupViewModel @Inject constructor(val monitor: ModelSetupMonitor) : ViewModel()

/**
 * The end of the built-in harness set-up and its Connect card: whether the harness on this phone
 * can answer yet, read from the harness itself, with the way to the Models screen when it cannot.
 * Asked again each time the harness comes up signed in; nothing is shown while it is not.
 */
@Composable
fun BuiltInModelsHint(port: Int, signedIn: Boolean, modifier: Modifier = Modifier, viewModel: ModelSetupViewModel = hiltViewModel()) {
    val monitor = viewModel.monitor
    val known by monitor.builtIn.collectAsStateWithLifecycle()
    LaunchedEffect(port, signedIn) { if (signedIn) monitor.probeBuiltIn(port) }
    val readiness = if (signedIn) known[port] ?: ModelReadiness.Unknown else ModelReadiness.Unknown

    AnimatedVisibility(
        visible = signedIn,
        enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
        exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
        modifier = modifier,
    ) {
        AnimatedContent(
            targetState = readiness,
            transitionSpec = { fadeIn(DsAnimations.fade) togetherWith fadeOut(DsAnimations.fade) using SizeTransform(clip = false) { _, _ -> DsAnimations.expand } },
            label = "builtInModels",
        ) { shown ->
            when (shown) {
                ModelReadiness.Ready -> Text(
                    stringResource(R.string.models_hint_ready),
                    style = DsType.small13,
                    color = DsTheme.colors.success,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                )
                ModelReadiness.NoneUsable -> SetupPlate(stringResource(R.string.models_hint_none), warning = true) { monitor.open(ModelsRequest.BuiltIn(port)) }
                ModelReadiness.Unknown -> SetupPlate(stringResource(R.string.models_hint_unknown), warning = false) { monitor.open(ModelsRequest.BuiltIn(port)) }
            }
        }
    }
}

/**
 * In the chat: what to tell the reader when the last turn failed for want of a key (from its failure
 * code), or when the connected harness has no provider that can answer; null when there is nothing
 * to say. Shown by [ChatModelSetupStrip] as the first row of the conversation.
 */
@Composable
fun chatModelSetupText(problem: ModelSetupProblem?, viewModel: ModelSetupViewModel = hiltViewModel()): String? {
    val monitor = viewModel.monitor
    val readiness by monitor.connected.collectAsStateWithLifecycle()
    // A turn that names a key problem may be the first sign that readiness changed.
    LaunchedEffect(problem) { if (problem != null) monitor.refreshConnected() }
    return when (problem) {
        ModelSetupProblem.MissingKey -> stringResource(R.string.models_chat_missing_key)
        ModelSetupProblem.InvalidKey -> stringResource(R.string.models_chat_invalid_key)
        ModelSetupProblem.SignIn -> stringResource(R.string.models_chat_sign_in)
        ModelSetupProblem.NoProvider -> stringResource(R.string.models_chat_no_provider)
        null -> if (readiness == ModelReadiness.NoneUsable) stringResource(R.string.models_chat_none_ready) else null
    }
}

/**
 * A calm strip saying [text], with one button to the Models screen. It is a row of the conversation,
 * not a bar over it, so it pushes the transcript down and nothing is drawn under it, whatever the
 * text size (the words wrap and the button stays below them).
 */
@Composable
fun ChatModelSetupStrip(text: String, viewModel: ModelSetupViewModel = hiltViewModel()) {
    val monitor = viewModel.monitor
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Surface(
        color = colors.warnTertiary,
        shape = DsShapes.block,
        border = BorderStroke(1.dp, colors.warnBorder),
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
                Icon(DsIcons.Warning, contentDescription = null, tint = colors.warnLabel, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                Spacer(Modifier.width(DsSpacing.small))
                Text(text, style = DsType.small13, color = colors.warnLabel, modifier = Modifier.weight(1f))
            }
            // Signing in to the DeepSeek account is a browser flow on the harness; the screen
            // can still add a key for another provider, so the button stays.
            DsConnectButton(
                text = stringResource(R.string.models_open),
                onClick = {
                    haptics.tap()
                    monitor.open(ModelsRequest.Connected)
                },
            )
        }
    }
}

/** The Settings row: what the connected harness can answer with, and the way in. */
@Composable
fun ModelsSettingsRow(viewModel: ModelSetupViewModel = hiltViewModel()) {
    val monitor = viewModel.monitor
    val readiness by monitor.connected.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    LaunchedEffect(Unit) { monitor.refreshConnected() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.row)
            .clickable(role = Role.Button) {
                haptics.tap()
                monitor.open(ModelsRequest.Connected)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.models_keys_title), style = DsType.std14, color = colors.labelSecondary)
            when (readiness) {
                ModelReadiness.Ready -> Text(stringResource(R.string.models_status_ready), style = DsType.small13, color = colors.success)
                ModelReadiness.NoneUsable -> Text(stringResource(R.string.models_settings_none), style = DsType.small13, color = colors.warnLabel)
                ModelReadiness.Unknown -> Unit
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.labelTertiary)
    }
}

/** In the model picker, when it has nothing to offer: why, and the way to fix it. */
@Composable
fun ModelsPickerEntry(onOpened: () -> Unit = {}, viewModel: ModelSetupViewModel = hiltViewModel()) {
    val monitor = viewModel.monitor
    SetupPlate(stringResource(R.string.models_picker_none), warning = true) {
        onOpened()
        monitor.open(ModelsRequest.Connected)
    }
}

/** A sentence and the "Set up models" button, in one plate. */
@Composable
private fun SetupPlate(text: String, warning: Boolean, onOpen: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.buttonLarge,
        color = if (warning) colors.warnTertiary else colors.bgLayer1,
        border = BorderStroke(1.dp, if (warning) colors.warnBorder else colors.borderL2),
    ) {
        Column(Modifier.padding(DsSpacing.medium), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            Text(text, style = DsType.small13, color = if (warning) colors.warnLabel else colors.labelSecondary)
            DsConnectButton(
                text = stringResource(R.string.models_open),
                onClick = {
                    haptics.tap()
                    onOpen()
                },
                variant = if (warning) ConnectButtonVariant.Primary else ConnectButtonVariant.Outline,
            )
        }
    }
}

/**
 * The one place the Models screen is drawn, at the app root above every route, so it stays open
 * across the switch from the Connect screen to the chat when a connection opens underneath it.
 */
@Composable
fun ModelsScreenHost(viewModel: ModelSetupViewModel = hiltViewModel()) {
    val monitor = viewModel.monitor
    val request by monitor.request.collectAsStateWithLifecycle()
    val shown = request ?: return
    // Keyed on the request: a new request is a new page with a fresh read.
    androidx.compose.runtime.key(shown) {
        ModelsScreen(
            target = {
                when (shown) {
                    ModelsRequest.Connected -> monitor.connectedTarget()
                    is ModelsRequest.BuiltIn -> monitor.builtInTarget(shown.port)
                }
            },
            onClose = monitor::closed,
            onChanged = monitor::changed,
        )
    }
}
