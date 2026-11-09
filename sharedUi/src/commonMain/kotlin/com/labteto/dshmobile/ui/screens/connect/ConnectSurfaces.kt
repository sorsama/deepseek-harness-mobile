package com.labteto.dshmobile.ui.screens.connect

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.parseHostInput
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.common_cancel
import com.labteto.dshmobile.shared.resources.connect_connecting_to
import com.labteto.dshmobile.shared.resources.connect_fail_certificate
import com.labteto.dshmobile.shared.resources.connect_fail_certificate_title
import com.labteto.dshmobile.shared.resources.connect_fail_dns
import com.labteto.dshmobile.shared.resources.connect_fail_fence_title
import com.labteto.dshmobile.shared.resources.connect_fail_invalid
import com.labteto.dshmobile.shared.resources.connect_fail_not_harness
import com.labteto.dshmobile.shared.resources.connect_fail_other
import com.labteto.dshmobile.shared.resources.connect_fail_pairing
import com.labteto.dshmobile.shared.resources.connect_fail_pairing_title
import com.labteto.dshmobile.shared.resources.connect_fail_refused
import com.labteto.dshmobile.shared.resources.connect_fail_relay_session
import com.labteto.dshmobile.shared.resources.connect_fail_relay_session_title
import com.labteto.dshmobile.shared.resources.connect_fail_streams
import com.labteto.dshmobile.shared.resources.connect_fail_subnet
import com.labteto.dshmobile.shared.resources.connect_fail_timeout
import com.labteto.dshmobile.shared.resources.connect_fail_tls
import com.labteto.dshmobile.shared.resources.connect_fail_unauthenticated
import com.labteto.dshmobile.shared.resources.connect_failed
import com.labteto.dshmobile.shared.resources.connect_failed_fence
import com.labteto.dshmobile.shared.resources.connect_forget_harness
import com.labteto.dshmobile.shared.resources.connect_lost_attempt
import com.labteto.dshmobile.shared.resources.connect_lost_body
import com.labteto.dshmobile.shared.resources.connect_lost_choose
import com.labteto.dshmobile.shared.resources.connect_lost_reconnecting
import com.labteto.dshmobile.shared.resources.connect_lost_retry_now
import com.labteto.dshmobile.shared.resources.connect_lost_tag
import com.labteto.dshmobile.shared.resources.connect_pair_again
import com.labteto.dshmobile.shared.resources.connect_sign_in
import com.labteto.dshmobile.shared.resources.connect_step_address
import com.labteto.dshmobile.shared.resources.connect_step_reaching
import com.labteto.dshmobile.shared.resources.connect_step_streams
import com.labteto.dshmobile.shared.resources.connect_step_verify
import com.labteto.dshmobile.shared.resources.connect_title
import com.labteto.dshmobile.shared.resources.connect_try_again
import com.labteto.dshmobile.shared.resources.connect_unreachable
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.localizedCount
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.components.formatDurationMs
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import org.jetbrains.compose.resources.stringResource

/** Tones for a compact status tag; each maps onto one of the Ink semantic families. */
internal enum class TagTone { Neutral, Success, Warn, Error }

/**
 * The kit's `.tag`: a 24dp, 6dp-radius label. Not a [com.labteto.dshmobile.ui.components.DsPill]
 * because that component only carries neutral / accent / warn tónes, and "Reachable" needs the
 * success family while "Unreachable" needs the error one.
 */
@Composable
internal fun ConnectStatusTag(text: String, tone: TagTone) {
    val colors = DsTheme.colors
    val (background, content, border) = when (tone) {
        TagTone.Neutral -> Triple(colors.bgLayer3, colors.labelSecondary, null)
        TagTone.Success -> Triple(colors.successTertiary, colors.success, colors.successBorder)
        TagTone.Warn -> Triple(colors.warnTertiary, colors.warnLabel, colors.warnBorder)
        TagTone.Error -> Triple(colors.errorTertiary, colors.error, colors.errorBorder)
    }
    Surface(
        shape = DsShapes.chip,
        color = background,
        contentColor = content,
        border = border?.let { BorderStroke(1.dp, it) },
    ) {
        Box(
            modifier = Modifier
                .height(24.dp)
                .padding(horizontal = DsSpacing.small),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = DsType.xsmall12,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The kit's uppercase section label (`.lbl`): 12sp medium, 0.06em tracking, tertiary.
 *
 * Local rather than the shared `SectionHeader`, which is the app's *list* section header (14sp
 * sentence case with an accent action). These connect sections need the design's editorial label.
 */
@Composable
internal fun ConnectSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = DsType.label12,
            color = DsTheme.colors.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

/**
 * The name to put in "Connecting to …" and "Sign in to …".
 *
 * The remembered record is authoritative — it is the name the person gave the harness when it was
 * paired — and the attempted authority is the fallback for a first-time manual address. A bare
 * `host:port` is stripped to the host so the sentence reads like a sentence.
 */
internal fun connectHostName(attempted: String?, remembered: List<HostConfig>): String? {
    val authority = attempted?.takeIf { it.isNotBlank() } ?: return null
    return remembered.firstOrNull { it.authority == authority }?.name
        ?: parseHostInput(authority)?.host
        ?: authority
}

/**
 * The full-width connecting takeover (design 03).
 *
 * The stages come straight from [ConnectStage]; nothing here invents progress. Two rules keep it
 * honest:
 *  - a stage row exists only once the pipeline has actually passed through it, so a connect that
 *    jumps straight to `OpeningStreams` (a remembered host needs no address validation) shows two
 *    rows rather than four with two ticks that never happened;
 *  - a duration appears only for a stage this screen watched finish, measured from the stage
 *    boundary it observed. No durations are injected or animated.
 */
@Composable
internal fun ConnectConnectingSurface(
    stage: ConnectStage,
    attempted: String?,
    remembered: List<HostConfig>,
    onCancel: () -> Unit,
    onRetry: (() -> Unit)?,
) {
    val colors = DsTheme.colors
    val name = connectHostName(attempted, remembered)
    val host = attempted?.let { parseHostInput(it)?.host } ?: attempted.orEmpty()

    val seen = remember { mutableStateListOf<ConnectStage>() }
    val durations = remember { mutableStateMapOf<ConnectStage, Long>() }
    var tracked by remember { mutableStateOf(stage) }
    var trackedSince by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(stage) {
        val now = nowMs()
        if (stage != tracked) {
            durations[tracked] = (now - trackedSince).coerceAtLeast(0L)
            tracked = stage
            trackedSince = now
        }
        if (stage in ConnectChecklist && stage !in seen) seen.add(stage)
    }

    val currentIndex = ConnectChecklist.indexOf(stage).let { if (it < 0) 0 else it }
    // Observed stages, plus the ones still ahead. Skipped stages never appear.
    val rows = ConnectChecklist.filter { it in seen || ConnectChecklist.indexOf(it) >= currentIndex }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = DsSpacing.xlarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(DsSpacing.xxlarge))
        MascotMark(size = 132.dp, state = MascotState.Connecting)
        Spacer(Modifier.height(DsSpacing.large))
        Text(
            if (name != null) stringResource(Res.string.connect_connecting_to, name) else stringResource(Res.string.connect_title),
            style = DsType.large20,
            color = colors.labelPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (attempted != null) {
            Spacer(Modifier.height(DsSpacing.xsmall))
            Text(
                attempted,
                style = DsType.mono12,
                color = colors.labelTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(DsSpacing.xlarge))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            rows.forEach { row ->
                val rowIndex = ConnectChecklist.indexOf(row)
                val state = when {
                    rowIndex < currentIndex -> ChecklistState.Done
                    rowIndex == currentIndex -> ChecklistState.Active
                    else -> ChecklistState.Pending
                }
                ConnectChecklistRow(
                    label = when (row) {
                        ConnectStage.Validating -> stringResource(Res.string.connect_step_address)
                        ConnectStage.Reaching -> stringResource(Res.string.connect_step_reaching, host)
                        ConnectStage.OpeningStreams -> stringResource(Res.string.connect_step_streams)
                        else -> stringResource(Res.string.connect_step_verify)
                    },
                    state = state,
                    durationMs = durations[row].takeIf { state == ChecklistState.Done },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            DsConnectButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onCancel,
                variant = ConnectButtonVariant.Outline,
            )
            if (onRetry != null) {
                DsConnectButton(
                    text = stringResource(Res.string.connect_try_again),
                    onClick = onRetry,
                    variant = ConnectButtonVariant.Primary,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(DsSpacing.large))
    }
}

/** The stages connecting actually walks, in order. Shared with the row renderer above. */
private val ConnectChecklist = listOf(
    ConnectStage.Validating,
    ConnectStage.Reaching,
    ConnectStage.OpeningStreams,
    ConnectStage.Verifying,
)

private enum class ChecklistState { Done, Active, Pending }

/** One checklist line: state mark, label, and (for finished stages) the measured duration. */
@Composable
private fun ConnectChecklistRow(label: String, state: ChecklistState, durationMs: Long?) {
    val colors = DsTheme.colors
    val labelColor = when (state) {
        ChecklistState.Done, ChecklistState.Active -> colors.labelPrimary
        ChecklistState.Pending -> colors.labelTertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Crossfade(
                targetState = state,
                animationSpec = tween(180),
                label = "checklistMark",
            ) { current ->
                when (current) {
                    ChecklistState.Done -> Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(colors.successTertiary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            DsIcons.Check,
                            contentDescription = null,
                            tint = colors.success,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    ChecklistState.Active -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = colors.accent,
                        strokeWidth = 2.dp,
                    )
                    ChecklistState.Pending -> Box(
                        Modifier
                            .size(14.dp)
                            .border(2.dp, colors.borderL3, CircleShape),
                    )
                }
            }
        }
        Text(
            label,
            style = if (state == ChecklistState.Active) DsType.std14Strong else DsType.std14,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (durationMs != null) {
            Text(
                formatDurationMs(durationMs),
                style = DsType.mono12,
                color = colors.labelTertiary,
                maxLines = 1,
            )
        }
    }
}

/**
 * The connection-lost surface (design 06).
 *
 * It is the connect screen's own version of the design's "studio-pc stopped answering" sheet: the
 * remembered harness with its warning status on top, and a bottom plate that says what is happening,
 * keeps the agent's work visible ("your draft is saved"), and puts the two decisions — retry, or go
 * back and pick another harness — at the bottom where a thumb already is.
 *
 * While the loop is backing off between attempts the surface stays up (`retrying`), so the person
 * sees the progress bar rather than the form flickering back on every half second.
 */
@Composable
internal fun ConnectLostSurface(
    failure: ConnectFailure,
    attempted: String?,
    remembered: List<HostConfig>,
    attempts: Int,
    retrying: Boolean,
    onRetry: (() -> Unit)?,
    onChooseAnother: () -> Unit,
    onPairAgain: () -> Unit,
    onSignIn: () -> Unit,
    onForget: ((HostConfig) -> Unit)?,
) {
    val colors = DsTheme.colors
    val name = connectHostName(attempted, remembered) ?: attempted
    val address = attempted ?: name.orEmpty()
    val tried = remember(attempted, remembered) {
        attempted?.let { authority -> remembered.firstOrNull { h -> h.authority == authority } }
    }
    val title = when {
        retrying && name != null -> stringResource(Res.string.connect_lost_reconnecting, name)
        else -> failureTitle(failure, address)
            ?: stringResource(Res.string.connect_failed, address)
    }
    val body = failureBody(failure, address)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 560.dp
        // Read here, where the BoxWithConstraints receiver is in scope: DSL markers block an outer
        // layout receiver from inside the Column's own scope below.
        val sheetMaxHeight = maxHeight * if (compact) 0.94f else 0.78f
        Column(Modifier.fillMaxSize()) {
            if (!compact) {
                Spacer(Modifier.height(DsSpacing.medium))
                HarnessWarningCard(
                    name = name ?: address,
                    address = address,
                    attempts = attempts,
                    retrying = retrying,
                    modifier = Modifier.padding(horizontal = DsSpacing.medium),
                )
            }
            Spacer(Modifier.weight(1f))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = DsShapes.sheet,
                color = colors.bgLayer2,
                border = BorderStroke(1.dp, colors.borderL1),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = sheetMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = DsSpacing.large)
                        .padding(top = DsSpacing.small, bottom = DsSpacing.large),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                ) {
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(DsShapes.pillFull)
                            .background(colors.borderL3),
                    )
                    Spacer(Modifier.height(DsSpacing.tiny))
                    MascotMark(size = 72.dp, state = MascotState.Offline)
                    Text(
                        title,
                        style = DsType.large20,
                        color = colors.labelPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        body,
                        style = DsType.std14,
                        color = colors.labelSecondary,
                        textAlign = TextAlign.Center,
                    )
                    if (retrying) {
                        Text(
                            stringResource(Res.string.connect_lost_body),
                            style = DsType.small13,
                            color = colors.labelTertiary,
                            textAlign = TextAlign.Center,
                        )
                        IndeterminateProgressBar()
                    }
                    Spacer(Modifier.height(DsSpacing.tiny))
                    // The one action that actually moves things forward leads, whichever kind of
                    // dead end this is: retry for a retryable attempt, sign in for a harness that
                    // does not know this phone, pair again for a relay that revoked it, and only
                    // then "choose another harness".
                    val leading: Pair<String, () -> Unit> = when {
                        onRetry != null -> stringResource(Res.string.connect_lost_retry_now) to onRetry
                        failure is ConnectFailure.Unauthenticated ->
                            stringResource(Res.string.connect_sign_in) to onSignIn
                        failure is ConnectFailure.PairingRequired || failure is ConnectFailure.CertificateChanged ->
                            stringResource(Res.string.connect_pair_again) to onPairAgain
                        else -> stringResource(Res.string.connect_lost_choose) to onChooseAnother
                    }
                    DsConnectButton(
                        text = leading.first,
                        onClick = leading.second,
                        variant = ConnectButtonVariant.Primary,
                        large = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (leading.second !== onChooseAnother) {
                        DsConnectButton(
                            text = stringResource(Res.string.connect_lost_choose),
                            onClick = onChooseAnother,
                            variant = ConnectButtonVariant.Outline,
                            large = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (onForget != null && tried != null) {
                        DsConnectButton(
                            text = stringResource(Res.string.connect_forget_harness),
                            onClick = { onForget(tried) },
                            variant = ConnectButtonVariant.Danger,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The remembered harness, marked as unreachable: name, mono address, attempt count and a warning
 * tag. Deliberately the same shape as the Recent card on the form, so the lost surface reads as the
 * same object in a worse state rather than as a new screen.
 */
@Composable
private fun HarnessWarningCard(
    name: String,
    address: String,
    attempts: Int,
    retrying: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(DsShapes.cube)
            .background(colors.bgLayer3)
            .border(1.dp, colors.borderL3, DsShapes.cube)
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        MascotMark(size = 28.dp, state = if (retrying) MascotState.Connecting else MascotState.Offline)
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = DsType.rowTitle,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
                Text(
                    address,
                    style = DsType.mono12,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (attempts > 0) {
                    Text("·", style = DsType.mono12, color = colors.labelCaption)
                    Text(
                        stringResource(Res.string.connect_lost_attempt, localizedCount(attempts)),
                        style = DsType.mono12,
                        color = colors.labelTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
        ConnectStatusTag(
            text = if (retrying) stringResource(Res.string.connect_lost_tag) else stringResource(Res.string.connect_unreachable),
            tone = if (retrying) TagTone.Warn else TagTone.Error,
        )
    }
}

/** The kit's `.indet` bar: a short accent segment travelling along a `borderL1` track. */
@Composable
private fun IndeterminateProgressBar() {
    val colors = DsTheme.colors
    val transition = rememberInfiniteTransition(label = "connectIndeterminate")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "indeterminate",
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(3.dp),
    ) {
        val radius = size.height / 2f
        drawRoundRect(colors.borderL1, cornerRadius = CornerRadius(radius, radius))
        val segment = size.width * 0.4f
        val x = progress * (size.width + segment) - segment
        drawRoundRect(
            color = colors.accent,
            topLeft = Offset(x, 0f),
            size = Size(segment, size.height),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}

/** A dashed outline card, the kit's stand-in for a row that has not been discovered yet. */
@Composable
internal fun DashedSkeletonCard(content: @Composable () -> Unit) {
    val colors = DsTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .drawBehind {
                val stroke = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(6.dp.toPx(), 6.dp.toPx()),
                    ),
                )
                drawRoundRect(
                    color = colors.borderL2,
                    cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
                    style = stroke,
                )
            }
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
    ) {
        content()
    }
}

// ---- Shared button/field helpers -------------------------------------------
//
// The connect screens distinguish regular (44dp) from large (52dp, design `.btn.lg`) buttons at
// every call site; this thin wrapper keeps that distinction readable while the press feedback,
// haptics and disabled treatment stay in the Lead-owned shared `DsButton`.

enum class ConnectButtonVariant { Primary, Outline, Ghost, Danger }

/** Token-driven Ink button; [large] is the design's `.btn.lg` (52dp, 15sp semibold). */
@Composable
fun DsConnectButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ConnectButtonVariant = ConnectButtonVariant.Primary,
    large: Boolean = false,
    enabled: Boolean = true,
) {
    com.labteto.dshmobile.ui.components.DsButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        variant = when (variant) {
            ConnectButtonVariant.Primary -> com.labteto.dshmobile.ui.components.DsButtonVariant.Primary
            ConnectButtonVariant.Outline -> com.labteto.dshmobile.ui.components.DsButtonVariant.Outline
            ConnectButtonVariant.Ghost -> com.labteto.dshmobile.ui.components.DsButtonVariant.Ghost
            ConnectButtonVariant.Danger -> com.labteto.dshmobile.ui.components.DsButtonVariant.Danger
        },
        size = if (large) {
            com.labteto.dshmobile.ui.components.DsButtonSize.Large
        } else {
            com.labteto.dshmobile.ui.components.DsButtonSize.Normal
        },
    )
}

// ---- Failure copy ----------------------------------------------------------
//
// One place for "what went wrong" and "why", so the inline error (a typo) and the lost surface
// (everything else) can never describe the same failure two different ways.

/** Headline for a failure, or null when the body is the whole message. */
@Composable
internal fun failureTitle(failure: ConnectFailure, authority: String): String? = when {
    failure is ConnectFailure.TrustFence -> stringResource(Res.string.connect_fail_fence_title)
    failure is ConnectFailure.PairingRequired -> stringResource(Res.string.connect_fail_pairing_title)
    failure is ConnectFailure.RelayUnauthenticated -> stringResource(Res.string.connect_fail_relay_session_title)
    failure is ConnectFailure.CertificateChanged -> stringResource(Res.string.connect_fail_certificate_title)
    authority.isBlank() -> null
    else -> stringResource(Res.string.connect_failed, authority)
}

/** Why it failed and (where the app can know it) what fixes it. */
@Composable
internal fun failureBody(failure: ConnectFailure, authority: String): String {
    val address = parseHostInput(authority)
    val port = address?.port ?: if (address?.useTls == true) 443 else 80
    return when (failure) {
        ConnectFailure.InvalidInput -> stringResource(Res.string.connect_fail_invalid)
        is ConnectFailure.DifferentSubnet -> stringResource(
            Res.string.connect_fail_subnet,
            authority,
            failure.localPrefix ?: stringResource(Res.string.connect_unreachable),
        )
        ConnectFailure.Timeout -> stringResource(Res.string.connect_fail_timeout, authority, localizedCount(port))
        ConnectFailure.Refused -> stringResource(Res.string.connect_fail_refused, authority)
        ConnectFailure.TrustFence -> stringResource(Res.string.connect_failed_fence)
        ConnectFailure.Unauthenticated -> stringResource(Res.string.connect_fail_unauthenticated, authority)
        ConnectFailure.PairingRequired -> stringResource(Res.string.connect_fail_pairing)
        ConnectFailure.RelayUnauthenticated -> stringResource(Res.string.connect_fail_relay_session, authority)
        ConnectFailure.CertificateChanged -> stringResource(Res.string.connect_fail_certificate, authority)
        ConnectFailure.DnsFailure -> stringResource(Res.string.connect_fail_dns, authority)
        ConnectFailure.NotAHarness -> stringResource(Res.string.connect_fail_not_harness, authority)
        ConnectFailure.TlsFailure -> stringResource(Res.string.connect_fail_tls, authority)
        ConnectFailure.StreamsBlocked -> stringResource(Res.string.connect_fail_streams, authority)
        is ConnectFailure.Other -> stringResource(Res.string.connect_fail_other, authority, failure.detail)
    }
}

/** Whether trying the same thing again could work. */
internal fun ConnectFailure.isRetryable(): Boolean = when (this) {
    ConnectFailure.InvalidInput,
    ConnectFailure.PairingRequired,
    ConnectFailure.CertificateChanged,
    ConnectFailure.Unauthenticated,
    ConnectFailure.RelayUnauthenticated,
    -> false
    else -> true
}

/** Milliseconds on a monotonic clock; only differences are used (stage durations). */
private fun nowMs(): Long = SurfaceClockOrigin.elapsedNow().inWholeMilliseconds

private val SurfaceClockOrigin = kotlin.time.TimeSource.Monotonic.markNow()
