package com.labteto.dshmobile.runtime

import androidx.compose.runtime.Composable
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.connect_button
import com.labteto.dshmobile.shared.resources.runtime_action_free_space
import com.labteto.dshmobile.shared.resources.runtime_action_open_controls
import com.labteto.dshmobile.shared.resources.runtime_action_restart
import com.labteto.dshmobile.shared.resources.runtime_action_retry
import com.labteto.dshmobile.shared.resources.runtime_action_set_up
import com.labteto.dshmobile.shared.resources.runtime_action_show_log
import com.labteto.dshmobile.shared.resources.runtime_action_show_progress
import com.labteto.dshmobile.shared.resources.runtime_action_start
import com.labteto.dshmobile.shared.resources.runtime_action_stop
import com.labteto.dshmobile.shared.resources.runtime_action_update_app
import com.labteto.dshmobile.shared.resources.runtime_action_use_port
import com.labteto.dshmobile.shared.resources.runtime_issue_app_too_old
import com.labteto.dshmobile.shared.resources.runtime_issue_crashed
import com.labteto.dshmobile.shared.resources.runtime_issue_exited
import com.labteto.dshmobile.shared.resources.runtime_issue_launch_failed
import com.labteto.dshmobile.shared.resources.runtime_issue_network
import com.labteto.dshmobile.shared.resources.runtime_issue_no_build
import com.labteto.dshmobile.shared.resources.runtime_issue_no_space
import com.labteto.dshmobile.shared.resources.runtime_issue_older_release
import com.labteto.dshmobile.shared.resources.runtime_issue_port_in_use
import com.labteto.dshmobile.shared.resources.runtime_issue_storage
import com.labteto.dshmobile.shared.resources.runtime_issue_timeout
import com.labteto.dshmobile.shared.resources.runtime_issue_untrusted
import com.labteto.dshmobile.shared.resources.runtime_issue_update_failed
import com.labteto.dshmobile.shared.resources.runtime_progress_bytes
import com.labteto.dshmobile.shared.resources.runtime_progress_checking
import com.labteto.dshmobile.shared.resources.runtime_progress_files
import com.labteto.dshmobile.shared.resources.runtime_progress_getting_ready
import com.labteto.dshmobile.shared.resources.runtime_progress_signing_in
import com.labteto.dshmobile.shared.resources.runtime_progress_starting
import com.labteto.dshmobile.shared.resources.runtime_progress_waiting_network
import com.labteto.dshmobile.shared.resources.runtime_size_gb
import com.labteto.dshmobile.shared.resources.runtime_size_mb
import com.labteto.dshmobile.shared.resources.runtime_step_check
import com.labteto.dshmobile.shared.resources.runtime_step_download
import com.labteto.dshmobile.shared.resources.runtime_step_sign_in
import com.labteto.dshmobile.shared.resources.runtime_step_start
import com.labteto.dshmobile.shared.resources.runtime_step_unpack
import com.labteto.dshmobile.ui.components.localizedCount
import com.labteto.dshmobile.ui.components.localizedDecimal
import org.jetbrains.compose.resources.stringResource

// The words for the built-in harness that more than one screen says: action labels, the sentence
// for each failure, step names, sizes. Shared resources, so the Connect screen (shared) and the
// set-up and control pages (Android) cannot word the same thing two ways.

/** The label of a button that does [action]. [issue] supplies the port for "Use port N". */
@Composable
fun runtimeActionLabel(action: RuntimeAction, issue: RuntimeIssue? = null): String = when (action) {
    RuntimeAction.SetUp -> stringResource(Res.string.runtime_action_set_up)
    RuntimeAction.ShowProgress -> stringResource(Res.string.runtime_action_show_progress)
    RuntimeAction.Start -> stringResource(Res.string.runtime_action_start)
    RuntimeAction.Stop -> stringResource(Res.string.runtime_action_stop)
    RuntimeAction.Connect -> stringResource(Res.string.connect_button)
    RuntimeAction.Restart -> stringResource(Res.string.runtime_action_restart)
    RuntimeAction.RetryInstall, RuntimeAction.RetryStart -> stringResource(Res.string.runtime_action_retry)
    RuntimeAction.FreeSpace -> stringResource(Res.string.runtime_action_free_space)
    RuntimeAction.UpdateApp -> stringResource(Res.string.runtime_action_update_app)
    RuntimeAction.UseOtherPort -> stringResource(Res.string.runtime_action_use_port, (issue?.nextPort ?: 0).toString())
    RuntimeAction.OpenControls -> stringResource(Res.string.runtime_action_open_controls)
    RuntimeAction.ShowLog -> stringResource(Res.string.runtime_action_show_log)
}

/** One sentence for what went wrong. An update that failed says first that nothing changed. */
@Composable
fun runtimeIssueText(issue: RuntimeIssue): String {
    val reason = when (issue.kind) {
        RuntimeIssueKind.NoSpace -> stringResource(Res.string.runtime_issue_no_space, formatBytes(issue.neededBytes), formatBytes(issue.availableBytes))
        RuntimeIssueKind.Network -> stringResource(Res.string.runtime_issue_network)
        RuntimeIssueKind.Untrusted -> stringResource(Res.string.runtime_issue_untrusted)
        RuntimeIssueKind.AppTooOld -> stringResource(Res.string.runtime_issue_app_too_old, issue.minAppVersion)
        RuntimeIssueKind.OlderRelease -> stringResource(Res.string.runtime_issue_older_release)
        RuntimeIssueKind.NoBuildForDevice -> stringResource(Res.string.runtime_issue_no_build)
        RuntimeIssueKind.Storage -> stringResource(Res.string.runtime_issue_storage)
        RuntimeIssueKind.LaunchFailed -> stringResource(Res.string.runtime_issue_launch_failed)
        RuntimeIssueKind.ExitedBeforeReady -> stringResource(Res.string.runtime_issue_exited)
        RuntimeIssueKind.ReadyTimeout -> stringResource(Res.string.runtime_issue_timeout)
        RuntimeIssueKind.Crashed -> stringResource(Res.string.runtime_issue_crashed)
        RuntimeIssueKind.PortInUse -> stringResource(Res.string.runtime_issue_port_in_use, issue.port.toString())
    }
    return if (issue.updateFailed && issue.kind != RuntimeIssueKind.OlderRelease) {
        stringResource(Res.string.runtime_issue_update_failed) + " " + reason
    } else {
        reason
    }
}

@Composable
fun setupStepLabel(step: SetupStep): String = when (step) {
    SetupStep.Download -> stringResource(Res.string.runtime_step_download)
    SetupStep.Check -> stringResource(Res.string.runtime_step_check)
    SetupStep.Unpack -> stringResource(Res.string.runtime_step_unpack)
    SetupStep.Start -> stringResource(Res.string.runtime_step_start)
    SetupStep.SignIn -> stringResource(Res.string.runtime_step_sign_in)
}

/**
 * The line under the active step: bytes for a download, files for an unpack, what is happening
 * otherwise. Speed and time left are added by the caller, which is the one that keeps the samples.
 */
@Composable
fun setupProgressDetail(progress: SetupProgress): String = when {
    progress.waitingForNetwork -> stringResource(Res.string.runtime_progress_waiting_network)
    progress.checking -> stringResource(Res.string.runtime_progress_checking)
    progress.step == SetupStep.Download && progress.bytesTotal > 0 ->
        stringResource(Res.string.runtime_progress_bytes, formatBytes(progress.bytesDone), formatBytes(progress.bytesTotal))
    progress.step == SetupStep.Download -> stringResource(Res.string.runtime_progress_getting_ready)
    progress.step == SetupStep.Check -> stringResource(Res.string.runtime_progress_checking)
    progress.step == SetupStep.Unpack && progress.entriesTotal > 0 ->
        stringResource(Res.string.runtime_progress_files, localizedCount(progress.entriesDone), localizedCount(progress.entriesTotal))
    progress.step == SetupStep.Unpack -> stringResource(Res.string.runtime_progress_getting_ready)
    progress.step == SetupStep.Start -> stringResource(Res.string.runtime_progress_starting)
    else -> stringResource(Res.string.runtime_progress_signing_in)
}

/**
 * A size the way a person says it: whole megabytes up to a gigabyte (one decimal under ten), one
 * decimal of a gigabyte above. Decimal units, like the system's own storage screen.
 */
@Composable
fun formatBytes(bytes: Long): String {
    val mb = bytes.coerceAtLeast(0) / 1_000_000.0
    return when {
        bytes <= 0 -> stringResource(Res.string.runtime_size_mb, localizedCount(0))
        // A few kilobytes is not "0.0 MB": the smallest size said is a tenth of one.
        mb < 0.1 -> stringResource(Res.string.runtime_size_mb, localizedDecimal(0.1, 1))
        mb >= 1000 -> stringResource(Res.string.runtime_size_gb, localizedDecimal(mb / 1000, 1))
        mb >= 10 -> stringResource(Res.string.runtime_size_mb, localizedCount(mb.toInt()))
        else -> stringResource(Res.string.runtime_size_mb, localizedDecimal(mb, 1))
    }
}
