package com.labteto.dshmobile.ui.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.R
import com.labteto.dshmobile.termux.TermuxPaths
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.update.AvailableUpdate
import com.labteto.dshmobile.update.JustUpdated
import com.labteto.dshmobile.update.UpdaterState

/**
 * "There is a newer release."
 *
 * The one primary action downloads the APK inside the app, checks it and installs it through
 * Android's package installer ([UpdatePanel]). The older routes stay as secondary options: Termux
 * downloading and opening the installer, the same command for the clipboard, and the release page.
 * While something is under way the dialog shows only the panel, and closing it ([onHide]) leaves
 * the work running; Settings → About shows the same progress.
 */
@Composable
fun UpdateDialog(
    update: AvailableUpdate,
    state: UpdaterState,
    ui: UpdateUiController,
    installCommand: String?,
    onInstallViaTermux: (() -> Unit)?,
    hasTermuxPermission: () -> Boolean,
    onCopied: () -> Unit,
    onLater: () -> Unit,
    onHide: () -> Unit,
) {
    val colors = DsTheme.colors
    val clipboard = LocalClipboardManager.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onInstallViaTermux?.invoke() }
    val ownState = state.takeIf { it.update?.version == update.version } ?: UpdaterState.Idle
    val idle = ownState == UpdaterState.Idle
    // Closing an idle dialog declines the release, as it always has; closing one with a download
    // or an outcome on screen only hides it, so the download goes on and the release is still offered.
    val dismiss = if (idle) onLater else onHide
    DsDialog(title = stringResource(R.string.update_available_title), onDismiss = dismiss) {
        Text(
            stringResource(R.string.update_available_body, update.version, BuildConfig.VERSION_NAME),
            style = DsType.std14,
            color = colors.labelSecondary,
        )
        // The panel (with the one primary action) comes before the notes: opening a long "What's
        // new" then grows below the action instead of pushing it out of sight.
        UpdatePanel(update, ownState, ui, onHide = onHide, feedback = true)
        update.notes?.let { ReleaseNotes(it) }
        if (idle) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                if (onInstallViaTermux != null) {
                    DsButton(
                        text = stringResource(R.string.update_install_termux),
                        onClick = {
                            if (hasTermuxPermission()) onInstallViaTermux() else permissionLauncher.launch(TermuxPaths.PERMISSION)
                        },
                        variant = DsButtonVariant.Outline,
                        allowWrap = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                DsButton(
                    text = stringResource(R.string.update_open),
                    onClick = {
                        ui.openUrl(update.url)
                        onLater()
                    },
                    variant = DsButtonVariant.Outline,
                    allowWrap = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (installCommand != null) {
                    DsButton(
                        text = stringResource(R.string.update_copy_command),
                        onClick = {
                            clipboard.setText(AnnotatedString(installCommand))
                            onCopied()
                        },
                        variant = DsButtonVariant.Ghost,
                        allowWrap = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                DsButton(
                    text = stringResource(R.string.update_later),
                    onClick = onLater,
                    variant = DsButtonVariant.Ghost,
                    allowWrap = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The first launch after an in-app update: which version this is now, and what changed. Shown
 * once (see `JustUpdatedTracker`).
 */
@Composable
fun UpdatedDialog(updated: JustUpdated, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = DsTheme.colors
    DsDialog(
        title = stringResource(R.string.update_updated_title, updated.version),
        onDismiss = onDismiss,
        body = {
            Text(stringResource(R.string.update_updated_body), style = DsType.std14, color = colors.labelSecondary)
            updated.notes?.let { ReleaseNotes(it, title = stringResource(R.string.update_view_changes)) }
        },
        // Pinned: the notes can be long once opened, and Done must stay in reach.
        actions = {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                DsButton(
                    text = stringResource(R.string.update_done),
                    onClick = onDismiss,
                    variant = DsButtonVariant.Info,
                    allowWrap = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DsButton(
                    text = stringResource(R.string.update_open),
                    onClick = { onOpenUrl(updated.releaseUrl) },
                    variant = DsButtonVariant.Ghost,
                    allowWrap = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
