package com.labteto.dshmobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.screens.connect.ConnectScreen
import com.labteto.dshmobile.ui.screens.main.MainScreen
import com.labteto.dshmobile.ui.screens.pair.PairScreen
import com.labteto.dshmobile.ui.screens.settings.SettingsScreen
import com.labteto.dshmobile.ui.theme.AccentPalette
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.FontSizeOption
import com.labteto.dshmobile.ui.theme.ThemeCustomization
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.labteto.dshmobile.ui.theme.resolveCustomAccent
import com.labteto.dshmobile.update.AvailableUpdate
import kotlinx.coroutines.delay

/** Application root: theme + locale-aware shell, connect vs. main routing. */
@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()

    // The whole appearance row in one value: scheme, accent, OLED canvas, text size and haptics.
    val customization = remember(
        settings.themePreference,
        settings.themeAccent,
        settings.themeCustomAccent,
        settings.amoledBlack,
        settings.fontSize,
        settings.hapticsEnabled,
    ) {
        ThemeCustomization(
            preference = ThemePreference.of(settings.themePreference),
            accent = AccentPalette.of(settings.themeAccent),
            customAccent = resolveCustomAccent(settings.themeAccent, settings.themeCustomAccent),
            amoled = settings.amoledBlack,
            fontSize = FontSizeOption.of(settings.fontSize),
            haptics = settings.hapticsEnabled,
        )
    }

    val update by viewModel.availableUpdate.collectAsStateWithLifecycle()
    // On every return to the foreground, not just the first composition: the checker decides
    // whether enough time has passed to ask GitHub again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.checkForUpdate(BuildConfig.VERSION_NAME) }

    DshTheme(customization = customization) {
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
        val route = when {
            showSettings -> RootRoute.Settings
            showPair -> RootRoute.Pair
            showMain -> RootRoute.Main
            else -> RootRoute.Connect
        }

        // The Ink launch moment: the system splash hands off to this frame, which then fades into
        // whatever route the saved session resolves to. Held just long enough to read the name —
        // not so long that it becomes a second splash to sit through.
        var splashVisible by rememberSaveable { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            delay(720)
            splashVisible = false
        }

        Box(Modifier.fillMaxSize().background(DsTheme.colors.bgBase)) {
            // One motion for every top-level change: the new screen settles in from slightly
            // smaller while the old one gets out of the way. Connecting used to cut straight from
            // the connect form to a full chat in a single frame, which read as a flash rather than
            // as arriving somewhere.
            AnimatedContent(
                targetState = route,
                transitionSpec = { DsAnimations.screenEnter togetherWith DsAnimations.screenExit },
                label = "rootRoute",
            ) { current ->
                when (current) {
                    RootRoute.Settings -> SettingsScreen(onClose = { showSettings = false })
                    RootRoute.Pair -> PairScreen(onClose = { showPair = false }, prefillUrl = pairUrl)
                    RootRoute.Main -> MainScreen(onOpenSettings = { showSettings = true })
                    RootRoute.Connect -> ConnectScreen(
                        onOpenSettings = { showSettings = true },
                        onPair = { url ->
                            pairUrl = url
                            showPair = true
                        },
                    )
                }
            }
            AnimatedVisibility(
                visible = splashVisible,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(420, easing = DsAnimations.inkDecelerate)),
            ) {
                InkSplash(
                    status = when (connection.phase) {
                        ConnectionPhase.RECONNECTING -> connection.host?.name
                            ?.let { stringResource(R.string.splash_reconnecting, it) }
                        ConnectionPhase.CONNECTING -> connection.host?.name
                            ?.let { stringResource(R.string.splash_connecting, it) }
                        else -> null
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
 * The Ink launch screen (design 01).
 *
 * The system splash already carries the mascot; this frame keeps the branded canvas up while the
 * first route resolves and gives the reconnect handshake a place to speak ("Reconnecting to
 * studio-pc…") instead of dumping the user straight into a half-restored chat.
 */
@Composable
private fun InkSplash(status: String?) {
    val colors = DsTheme.colors
    // A small arrival animation: mark, wordmark and tagline settle up into place in sequence.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val entrance by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(520, easing = DsAnimations.inkDecelerate),
        label = "splashEntrance",
    )
    val progress = rememberInfiniteTransition(label = "splashProgress")
    val progressX by progress.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "progress",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bgBase),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .graphicsLayer {
                    alpha = entrance
                    translationY = (1f - entrance) * 18.dp.toPx()
                },
        ) {
            MascotMark(size = 96.dp, state = MascotState.Working)
            Spacer(Modifier.height(28.dp))
            Text(
                "DSH Mobile",
                style = DsType.display24,
                color = colors.labelPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.splash_tagline),
                style = DsType.base16,
                color = colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SplashProgress(progress = progressX)
            if (status != null) {
                Spacer(Modifier.height(18.dp))
                Text(
                    status,
                    style = DsType.mono12,
                    color = colors.labelTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A slim indeterminate bar: track in `borderL1`, a short accent segment travelling left to right. */
@Composable
private fun SplashProgress(progress: Float) {
    val colors = DsTheme.colors
    Box(
        Modifier
            .width(220.dp)
            .height(3.dp)
            .background(colors.borderL1, DsShapes.pillFull),
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.34f)
                .height(3.dp)
                .background(colors.accent, DsShapes.pillFull)
                .graphicsLayer {
                    translationX = (progress * 2f * 220.dp.toPx()) - (0.34f * 220.dp.toPx())
                },
        )
    }
}

/** The top-level screens [AppRoot] switches between. */
private enum class RootRoute { Connect, Main, Settings, Pair }

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
