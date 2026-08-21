package com.labteto.dshmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
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

        // Offered over whatever is on screen, and only once per release: dismissing records the
        // version, so the next launch is quiet until there is a newer one.
        update?.let { UpdateDialog(it, onDismiss = { viewModel.dismissUpdate(it.version) }) }
    }
}

/** "There is a newer release" — a link out, not an installer; the app cannot update itself. */
@Composable
private fun UpdateDialog(update: AvailableUpdate, onDismiss: () -> Unit) {
    val colors = DsTheme.colors
    val uriHandler = LocalUriHandler.current
    DsDialog(title = stringResource(R.string.update_available_title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.update_available_body, update.version, BuildConfig.VERSION_NAME),
            style = DsType.std14,
            color = colors.labelSecondary,
            modifier = Modifier.padding(bottom = DsSpacing.medium),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsButton(
                text = stringResource(R.string.update_open),
                onClick = {
                    runCatching { uriHandler.openUri(update.url) }
                    onDismiss()
                },
                variant = DsButtonVariant.Info,
            )
            DsButton(
                text = stringResource(R.string.update_later),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
            )
        }
    }
}
