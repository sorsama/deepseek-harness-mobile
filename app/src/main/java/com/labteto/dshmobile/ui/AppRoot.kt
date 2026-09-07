package com.labteto.dshmobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.termux.HarnessControl
import com.labteto.dshmobile.termux.HarnessControlFailure
import com.labteto.dshmobile.termux.TermuxPaths
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.screens.connect.ConnectScreen
import com.labteto.dshmobile.ui.screens.main.MainScreen
import com.labteto.dshmobile.ui.screens.pair.PairScreen
import com.labteto.dshmobile.ui.screens.settings.SettingsScreen
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.labteto.dshmobile.update.AvailableUpdate

/** Application root: theme + locale-aware shell, connect vs. main routing. */
@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val themePreference = remember(settings.themePreference) {
        runCatching { ThemePreference.valueOf(settings.themePreference.uppercase()) }
            .getOrDefault(ThemePreference.SYSTEM)
    }

    val update by viewModel.availableUpdate.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.checkForUpdate(BuildConfig.VERSION_NAME) }

    DshTheme(preference = themePreference) {
        val toast = rememberDsToast()
        // Hidden, not dismissed: an install that was handed to Termux should not also record
        // the release as declined, or a failed download would never be offered again.
        var updateHidden by rememberSaveable { mutableStateOf(false) }
        val harnessControl by viewModel.harnessControl.collectAsStateWithLifecycle()
        val installFailed = (harnessControl as? HarnessControl.Failed)?.reason as? HarnessControlFailure.InstallFailed
        val installFailedText = installFailed?.let { stringResource(R.string.update_install_failed, it.detail) }
        LaunchedEffect(installFailedText) {
            if (installFailedText != null) {
                toast.second(installFailedText)
                viewModel.acknowledgeHarnessControl()
                updateHidden = false
            }
        }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        // Pairing is a detour off the connect screen rather than a mode of it: it owns the camera,
        // it can succeed against an address the connect screen never listed, and it ends by
        // connecting — at which point the routing below carries on as if the relay had always been
        // remembered. `pairUrl` is saveable because the scan launches another activity, and coming
        // back to an empty address field would lose the one thing the user had already supplied.
        var showPair by rememberSaveable { mutableStateOf(false) }
        var pairUrl by rememberSaveable { mutableStateOf<String?>(null) }
        val showMain = connection.phase == ConnectionPhase.CONNECTED ||
            (connection.phase == ConnectionPhase.RECONNECTING && connection.hasConnected)
        Box(Modifier.fillMaxSize()) {
            when {
                showSettings -> SettingsScreen(onClose = { showSettings = false })
                showPair -> PairScreen(onClose = { showPair = false }, prefillUrl = pairUrl)
                showMain -> MainScreen(onOpenSettings = { showSettings = true })
                else -> ConnectScreen(
                    onOpenSettings = { showSettings = true },
                    onPair = { url ->
                        pairUrl = url
                        showPair = true
                    },
                )
            }
            DsToastHost(toast, modifier = Modifier.fillMaxWidth())
        }

        // Offered over whatever is on screen, and only once per release: dismissing records the
        // version, so the next launch is quiet until there is a newer one.
        update?.takeIf { !updateHidden }?.let { available ->
            val installStarted = stringResource(R.string.update_install_started)
            val copied = stringResource(R.string.common_copied)
            UpdateDialog(
                update = available,
                installCommand = viewModel.installCommand(available),
                onInstallViaTermux = if (viewModel.termuxInstalled && available.apk != null) {
                    {
                        viewModel.installViaTermux(available)
                        toast.second(installStarted)
                        updateHidden = true
                    }
                } else {
                    null
                },
                hasTermuxPermission = viewModel::hasTermuxPermission,
                onCopied = { toast.second(copied) },
                onDismiss = { viewModel.dismissUpdate(available.version) },
            )
        }
    }
}

/**
 * "There is a newer release."
 *
 * The app still cannot update itself: the release page is a link out. What it can do, on a phone
 * with Termux, is hand Termux the download — `curl` the APK, check it against the published
 * `SHA256SUMS.txt`, and open the package installer — which needs no install permission of its
 * own. The same command is offered for the clipboard, for a phone administered over SSH.
 */
@Composable
private fun UpdateDialog(
    update: AvailableUpdate,
    installCommand: String?,
    onInstallViaTermux: (() -> Unit)?,
    hasTermuxPermission: () -> Boolean,
    onCopied: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onInstallViaTermux?.invoke() }
    DsDialog(title = stringResource(R.string.update_available_title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.update_available_body, update.version, BuildConfig.VERSION_NAME),
            style = DsType.std14,
            color = colors.labelSecondary,
            modifier = Modifier.padding(bottom = DsSpacing.medium),
        )
        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            if (onInstallViaTermux != null) {
                DsButton(
                    text = stringResource(R.string.update_install_termux),
                    onClick = {
                        if (hasTermuxPermission()) onInstallViaTermux() else permissionLauncher.launch(TermuxPaths.PERMISSION)
                    },
                    variant = DsButtonVariant.Info,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DsButton(
                text = stringResource(R.string.update_open),
                onClick = {
                    runCatching { uriHandler.openUri(update.url) }
                    onDismiss()
                },
                variant = if (onInstallViaTermux == null) DsButtonVariant.Info else DsButtonVariant.Outline,
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DsButton(
                text = stringResource(R.string.update_later),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
