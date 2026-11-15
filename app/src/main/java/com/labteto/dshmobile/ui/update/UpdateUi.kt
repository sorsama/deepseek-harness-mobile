package com.labteto.dshmobile.ui.update

import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.update.ApkRejection
import com.labteto.dshmobile.update.AvailableUpdate
import com.labteto.dshmobile.update.InstallFailure
import com.labteto.dshmobile.update.PauseReason
import com.labteto.dshmobile.update.RejectReason
import com.labteto.dshmobile.update.UpdaterState
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the update UI can ask of the updater, hoisted to the root so the activity-result
 * launchers it needs (the "install unknown apps" page, the system's install prompt) outlive the
 * dialog or the Settings card that started them.
 */
@Stable
class UpdateUiController(
    val state: StateFlow<UpdaterState>,
    /** The newest release ahead of this build, declined or not; see `UpdateChecker.latest`. */
    val latest: StateFlow<AvailableUpdate?>,
    val download: (AvailableUpdate, allowMetered: Boolean) -> Unit,
    val cancel: () -> Unit,
    val installAnyway: () -> Unit,
    val install: () -> Unit,
    val requestPermission: () -> Unit,
    val acknowledge: () -> Unit,
    val openUrl: (String) -> Unit,
)

/** Provided by `AppRoot`; null in previews and anywhere composed without it. */
val LocalUpdateUi = staticCompositionLocalOf<UpdateUiController?> { null }

/** A byte count the way the system's own storage screens write it, in the app's language. */
@Composable
private fun bytes(value: Long): String = Formatter.formatShortFileSize(LocalContext.current, value.coerceAtLeast(0))

@Composable
private fun timeLeft(seconds: Long): String =
    if (seconds < 60) stringResource(R.string.update_eta_seconds, seconds.toInt().coerceAtLeast(1))
    else stringResource(R.string.update_eta_minutes, ((seconds + 59) / 60).toInt())

/**
 * The in-app download, verification and install for [update], driven by [state].
 *
 * The same panel sits in the update dialog and in Settings → About, so whichever one the person
 * used, the other shows the same progress. [onHide] closes the surrounding dialog while the work
 * carries on (null in Settings, which has nothing to close). [feedback] plays the haptics for
 * outcomes: only one of the two panels should, or a refusal would buzz twice.
 */
@Composable
fun UpdatePanel(
    update: AvailableUpdate,
    state: UpdaterState,
    ui: UpdateUiController,
    modifier: Modifier = Modifier,
    onHide: (() -> Unit)? = null,
    feedback: Boolean = false,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val current = if (state.update?.version == update.version) state else UpdaterState.Idle
    if (feedback) {
        val kind = current::class
        LaunchedEffect(kind) {
            when (current) {
                is UpdaterState.Rejected, is UpdaterState.InstallFailed -> haptics.reject()
                is UpdaterState.ReadyToInstall, is UpdaterState.Installed -> haptics.confirm()
                else -> Unit
            }
        }
    }
    AnimatedContent(
        targetState = current,
        contentKey = { it.stage() },
        transitionSpec = { DsAnimations.cardEnter togetherWith DsAnimations.cardExit },
        modifier = modifier.fillMaxWidth(),
        label = "updateStage",
    ) { shown ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            when (val s = shown) {
                UpdaterState.Idle -> {
                    val apk = update.apk
                    if (apk == null) {
                        Message(stringResource(R.string.update_no_apk))
                    } else {
                        Action(
                            if (apk.size > 0) stringResource(R.string.update_download_install_size, bytes(apk.size))
                            else stringResource(R.string.update_download_install),
                            DsButtonVariant.Info,
                        ) { ui.download(update, false) }
                    }
                }
                is UpdaterState.ConfirmMetered -> {
                    Message(
                        if (s.size > 0) stringResource(R.string.update_metered, bytes(s.size))
                        else stringResource(R.string.update_metered_unknown),
                        tone = Tone.Warn,
                    )
                    Action(stringResource(R.string.update_download_anyway), DsButtonVariant.Info) { ui.download(update, true) }
                    Action(stringResource(R.string.update_not_now), DsButtonVariant.Ghost) { ui.acknowledge() }
                }
                is UpdaterState.Downloading -> {
                    DownloadProgress(s.done, s.total)
                    ProgressCaption(
                        primary = if (s.total > 0) stringResource(R.string.update_progress_bytes, bytes(s.done), bytes(s.total)) else bytes(s.done),
                        secondary = when {
                            s.reconnecting -> stringResource(R.string.update_reconnecting)
                            s.bytesPerSecond != null && s.secondsLeft != null ->
                                stringResource(R.string.update_progress_rate, bytes(s.bytesPerSecond), timeLeft(s.secondsLeft))
                            s.bytesPerSecond != null -> stringResource(R.string.update_progress_rate_only, bytes(s.bytesPerSecond))
                            else -> null
                        },
                        warn = s.reconnecting,
                    )
                    if (onHide != null) Action(stringResource(R.string.update_hide), DsButtonVariant.Outline, onHide)
                    Action(stringResource(R.string.update_cancel_download), DsButtonVariant.Ghost) { ui.cancel() }
                }
                is UpdaterState.Paused -> {
                    // Only when something is saved: "0 B of 26 MB" above "not enough space" is noise.
                    if (s.total > 0 && s.done > 0) {
                        DownloadProgress(s.done, s.total)
                        ProgressCaption(stringResource(R.string.update_progress_bytes, bytes(s.done), bytes(s.total)), null)
                    }
                    Message(
                        when (val reason = s.reason) {
                            PauseReason.Network -> stringResource(R.string.update_paused_network)
                            PauseReason.Interrupted -> stringResource(R.string.update_paused_interrupted)
                            is PauseReason.NoSpace -> stringResource(R.string.update_no_space, bytes(reason.shortBy))
                            is PauseReason.Server -> stringResource(R.string.update_paused_server, reason.code)
                            PauseReason.ChecksumUnreachable -> stringResource(R.string.update_paused_checksum)
                        },
                        tone = if (s.reason == PauseReason.Interrupted) Tone.Plain else Tone.Warn,
                    )
                    val resumes = s.reason == PauseReason.Network || s.reason == PauseReason.Interrupted
                    Action(
                        stringResource(if (resumes) R.string.update_resume else R.string.update_try_again),
                        DsButtonVariant.Info,
                    ) { ui.download(update, false) }
                    Action(stringResource(R.string.update_cancel_download), DsButtonVariant.Ghost) { ui.cancel() }
                }
                is UpdaterState.Verifying -> {
                    DownloadProgress(1, 1)
                    ProgressCaption(stringResource(R.string.update_verifying), null)
                }
                is UpdaterState.ConfirmUnverified -> {
                    Message(
                        stringResource(if (s.entryMissing) R.string.update_no_checksum_entry else R.string.update_no_checksum),
                        tone = Tone.Warn,
                    )
                    Action(stringResource(R.string.update_install_anyway), DsButtonVariant.Attention) { ui.installAnyway() }
                    Action(stringResource(R.string.update_cancel_download), DsButtonVariant.Ghost) { ui.cancel() }
                }
                is UpdaterState.ReadyToInstall -> {
                    Message(stringResource(R.string.update_ready), tone = Tone.Good)
                    Action(stringResource(R.string.update_install), DsButtonVariant.Info) { ui.install() }
                }
                is UpdaterState.NeedsPermission -> {
                    Message(stringResource(R.string.update_permission))
                    if (s.askedBefore) Message(stringResource(R.string.update_permission_still_off), tone = Tone.Warn)
                    Action(stringResource(R.string.update_permission_allow), DsButtonVariant.Info) { ui.requestPermission() }
                    if (onHide != null) Action(stringResource(R.string.update_not_now), DsButtonVariant.Ghost, onHide)
                }
                is UpdaterState.Installing -> {
                    DownloadProgress(1, 1)
                    ProgressCaption(stringResource(R.string.update_installing), null)
                    if (s.stalled) {
                        Message(stringResource(R.string.update_installing_stalled), tone = Tone.Warn)
                        Action(stringResource(R.string.update_try_again), DsButtonVariant.Info) { ui.install() }
                    }
                }
                is UpdaterState.InstallFailed -> {
                    Message(failureText(s.failure), tone = Tone.Error)
                    if (s.failure == InstallFailure.Unknown && s.detail != null) {
                        Text(
                            stringResource(R.string.update_failed_detail, s.detail),
                            style = DsType.mono12,
                            color = colors.labelTertiary,
                        )
                    }
                    if (s.failure.retryable) {
                        Action(stringResource(R.string.update_try_again), DsButtonVariant.Info) { ui.install() }
                    }
                    Action(stringResource(R.string.update_close), DsButtonVariant.Ghost) { ui.acknowledge() }
                }
                is UpdaterState.Rejected -> {
                    Message(rejectionText(s.reason), tone = Tone.Error)
                    val again = when (val reason = s.reason) {
                        RejectReason.ChecksumMismatch, RejectReason.SizeMismatch -> true
                        is RejectReason.Apk -> reason.rejection == ApkRejection.Unreadable
                        is RejectReason.Host -> false
                    }
                    if (again) {
                        Action(stringResource(R.string.update_download_again), DsButtonVariant.Info) { ui.download(update, false) }
                    }
                    if ((s.reason as? RejectReason.Apk)?.rejection == ApkRejection.DebugBuild) {
                        Action(stringResource(R.string.update_open), DsButtonVariant.Outline) { ui.openUrl(update.url) }
                    }
                    Action(stringResource(R.string.update_close), DsButtonVariant.Ghost) { ui.acknowledge() }
                }
                is UpdaterState.Installed -> Message(stringResource(R.string.update_installed), tone = Tone.Good)
            }
        }
    }
}

/** One key per layout, so the panel cross-fades between steps but not on every progress tick. */
private fun UpdaterState.stage(): String = when (this) {
    is UpdaterState.Downloading -> "downloading"
    is UpdaterState.Installing -> "installing:$stalled"
    is UpdaterState.NeedsPermission -> "permission:$askedBefore"
    else -> this::class.simpleName.orEmpty()
}

@Composable
private fun failureText(failure: InstallFailure): String = stringResource(
    when (failure) {
        InstallFailure.Aborted -> R.string.update_failed_aborted
        InstallFailure.Verification -> R.string.update_failed_verification
        InstallFailure.Blocked -> R.string.update_failed_blocked
        InstallFailure.Conflict -> R.string.update_failed_conflict
        InstallFailure.Incompatible -> R.string.update_failed_incompatible
        InstallFailure.Invalid -> R.string.update_failed_invalid
        InstallFailure.Storage -> R.string.update_failed_storage
        InstallFailure.Timeout -> R.string.update_failed_timeout
        InstallFailure.Unknown -> R.string.update_failed_unknown
    },
)

@Composable
private fun rejectionText(reason: RejectReason): String = when (reason) {
    RejectReason.ChecksumMismatch -> stringResource(R.string.update_rejected_checksum)
    RejectReason.SizeMismatch -> stringResource(R.string.update_rejected_size)
    is RejectReason.Host -> stringResource(R.string.update_rejected_host, reason.host)
    is RejectReason.Apk -> when (val rejection = reason.rejection) {
        is ApkRejection.WrongPackage -> stringResource(R.string.update_rejected_package, rejection.found)
        ApkRejection.DebugBuild -> stringResource(R.string.update_rejected_debug)
        is ApkRejection.NotNewer -> stringResource(R.string.update_rejected_not_newer, rejection.candidateVersion)
        ApkRejection.SignatureMismatch -> stringResource(R.string.update_rejected_signature)
        ApkRejection.Unreadable -> stringResource(R.string.update_rejected_unreadable)
    }
}

private enum class Tone { Plain, Warn, Error, Good }

@Composable
private fun Message(text: String, tone: Tone = Tone.Plain) {
    val colors = DsTheme.colors
    Text(
        text,
        style = DsType.std14,
        color = when (tone) {
            Tone.Plain -> colors.labelSecondary
            Tone.Warn -> colors.warnLabel
            Tone.Error -> colors.error
            Tone.Good -> colors.success
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Action(text: String, variant: DsButtonVariant, onClick: () -> Unit) {
    DsButton(text = text, onClick = onClick, variant = variant, allowWrap = true, modifier = Modifier.fillMaxWidth())
}

/**
 * The download bar: the Ink 3dp track with the accent fill, following the bytes on a spring so the
 * many small updates read as one smooth movement. Fills from the start edge, so it runs right to
 * left in Arabic and Urdu.
 */
@Composable
private fun DownloadProgress(done: Long, total: Long) {
    val colors = DsTheme.colors
    val target = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    val fraction by animateFloatAsState(target, DsAnimations.progress, label = "updateProgress")
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = DsSpacing.tiny)
            .height(4.dp)
            .clip(CircleShape)
            .background(colors.borderL1)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f) },
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .clip(CircleShape)
                .background(colors.accent),
        )
    }
}

@Composable
private fun ProgressCaption(primary: String, secondary: String?, warn: Boolean = false) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(primary, style = DsType.small13Strong, color = colors.labelPrimary)
        if (secondary != null) {
            Text(secondary, style = DsType.small13, color = if (warn) colors.warnLabel else colors.labelTertiary)
        }
    }
}

/**
 * The release notes, behind a "What's new" row so the download button stays near the top of the
 * dialog. Rendered with the app's own Markdown renderer; GitHub's generated notes are headings,
 * lists and links, which it handles.
 */
@Composable
fun ReleaseNotes(notes: String, title: String = stringResource(R.string.update_whats_new), initiallyOpen: Boolean = false) {
    val colors = DsTheme.colors
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    val rotation by animateFloatAsState(if (open) 90f else 0f, DsAnimations.chevron, label = "notesChevron")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = DsSpacing.touchTarget)
                .clip(CircleShape)
                .clickable(role = Role.Button) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Icon(
                FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer {
                        // The chevron points along the reading direction; turned, it points down.
                        rotationZ = if (rtl) -rotation else rotation
                        scaleX = if (rtl) -1f else 1f
                    },
            )
            Text(title, style = DsType.std14Strong, color = colors.labelPrimary)
        }
        AnimatedVisibility(visible = open, enter = DsAnimations.dockEnter, exit = DsAnimations.dockExit) {
            MarkdownText(notes, Modifier.padding(top = DsSpacing.tiny, bottom = DsSpacing.small))
        }
    }
}
