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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
 * In the chat: a calm bar above the conversation when the last turn failed for want of a key (from
 * its failure code), or when the connected harness has no provider that can answer. One button to the
 * Models screen.
 *
 * It is a bar pinned above the transcript, not a row of it, so it is in view wherever the reader has
 * scrolled to (a row at the top of a long conversation is never seen at the newest message, which is
 * where a reader is when they try to send). It does not cover anything: the transcript simply gets
 * the height it takes. Where the words would take too much of the screen (a large text size) it folds
 * to one line with the button, and a tap on the words opens the rest ([SetupNotice]).
 */
@Composable
fun ChatModelSetupNotice(problem: ModelSetupProblem?, viewModel: ModelSetupViewModel = hiltViewModel()) {
    val monitor = viewModel.monitor
    val readiness by monitor.connected.collectAsStateWithLifecycle()
    // A turn that names a key problem may be the first sign that readiness changed.
    LaunchedEffect(problem) { if (problem != null) monitor.refreshConnected() }
    val text = when (problem) {
        ModelSetupProblem.MissingKey -> stringResource(R.string.models_chat_missing_key)
        ModelSetupProblem.InvalidKey -> stringResource(R.string.models_chat_invalid_key)
        ModelSetupProblem.SignIn -> stringResource(R.string.models_chat_sign_in)
        ModelSetupProblem.NoProvider -> stringResource(R.string.models_chat_no_provider)
        null -> if (readiness == ModelReadiness.NoneUsable) stringResource(R.string.models_chat_none_ready) else null
    }
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
        exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
    ) {
        // Held through the exit so the words do not vanish before the bar folds away.
        var last by remember { mutableStateOf("") }
        if (text != null) last = text
        SetupNotice(last, onOpen = { monitor.open(ModelsRequest.Connected) })
    }
}

/** The most of the screen's height the notice may take in its full form before it folds to one line. */
internal const val SETUP_NOTICE_MAX_SHARE = 0.2f

/**
 * Whether the notice, which would be [fullHeightDp] tall with its words wrapped and its button below
 * them, takes more of a [screenHeightDp] tall screen than [SETUP_NOTICE_MAX_SHARE] and so is shown
 * folded. A screen of unknown height never folds it.
 */
internal fun setupNoticeIsCompact(fullHeightDp: Float, screenHeightDp: Float): Boolean =
    screenHeightDp > 0f && fullHeightDp > screenHeightDp * SETUP_NOTICE_MAX_SHARE

/**
 * The bar itself. Full: an icon and the words (they wrap), then the button. Folded: the icon, the
 * first line of the words and the button on one line; a tap on the words shows all of them and a
 * second tap folds them again. Whether it is folded is measured, not guessed from the text size: the
 * words are laid out at the width they will have, and their real height decides.
 */
@Composable
private fun SetupNotice(text: String, onOpen: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    // Not saved: this file is on the key path, where nothing goes into a saved-state bundle.
    var open by remember(text) { mutableStateOf(false) }
    Surface(color = colors.warnTertiary, border = BorderStroke(1.dp, colors.warnBorder), modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val barWidth = maxWidth
            val iconSlot = 16.dp + DsSpacing.small
            val textWidth = barWidth - DsSpacing.comfortable * 2 - iconSlot
            val fullHeight = with(density) {
                val words = measurer.measure(text, DsType.small13, constraints = Constraints(maxWidth = textWidth.roundToPx().coerceAtLeast(0))).size.height.toDp()
                words + DsSpacing.touchTarget + DsSpacing.small * 3
            }
            val folded = setupNoticeIsCompact(fullHeight.value, screenHeightDp)
            val button: @Composable (Modifier) -> Unit = { modifier ->
                // Signing in to the DeepSeek account is a browser flow on the harness; the screen
                // can still add a key for another provider, so the button stays.
                DsConnectButton(
                    text = stringResource(R.string.models_open),
                    onClick = {
                        haptics.tap()
                        onOpen()
                    },
                    modifier = modifier,
                )
            }
            if (!folded || open) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Row(
                        Modifier
                            // Only a notice that was folded can be folded again.
                            .then(if (folded) Modifier.clickable(role = Role.Button) { open = false } else Modifier),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(DsIcons.Warning, contentDescription = null, tint = colors.warnLabel, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                        Spacer(Modifier.width(DsSpacing.small))
                        Text(
                            text,
                            style = DsType.small13,
                            color = colors.warnLabel,
                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    button(Modifier)
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    // The words are the target: 48dp tall, and a tap on them opens the full text.
                    Row(
                        Modifier
                            .weight(1f)
                            .heightIn(min = DsSpacing.touchTarget)
                            .clickable(role = Role.Button) { open = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(DsIcons.Warning, contentDescription = null, tint = colors.warnLabel, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(DsSpacing.small))
                        Text(
                            text,
                            style = DsType.small13,
                            color = colors.warnLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.warnLabel, modifier = Modifier.size(16.dp))
                    }
                    button(Modifier.widthIn(max = barWidth * 0.45f))
                }
            }
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
