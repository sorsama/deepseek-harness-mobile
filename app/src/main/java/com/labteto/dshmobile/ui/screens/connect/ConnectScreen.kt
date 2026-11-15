package com.labteto.dshmobile.ui.screens.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.connection.ConnectMode
import com.labteto.dshmobile.runtime.RuntimeAction
import com.labteto.dshmobile.runtime.RuntimeState
import com.labteto.dshmobile.runtime.nextPort
import com.labteto.dshmobile.runtime.termuxSectionStartsOpen
import com.labteto.dshmobile.ui.screens.runtime.BuiltInHarnessCard
import com.labteto.dshmobile.ui.screens.runtime.RuntimeControlPage
import com.labteto.dshmobile.ui.screens.runtime.RuntimeSetupPage
import com.labteto.dshmobile.ui.screens.runtime.RuntimeSystem
import com.labteto.dshmobile.ui.screens.runtime.TermuxDisclosure
import com.labteto.dshmobile.ui.theme.DsSpacing

/**
 * The Android Connect screen: the shared screen (`ConnectContent` in `:sharedUi`) with the things
 * only Android adds.
 *
 *  - The view model, Hilt's, which owns the [ConnectController] the shared screen drives.
 *  - The lifecycle: refresh the Recent liveness when the screen comes back, and watch the harness on
 *    this phone only while the "This phone" card is showing.
 *  - The "This phone" cards: the built-in harness ([BuiltInHarnessCard]) with its set-up and control
 *    pages, and the Termux card ([ThisPhoneCard]), which runs the harness through Termux. Where the
 *    built-in harness cannot run, the Termux card is all there is, exactly as before; where it can,
 *    Termux is the second way, behind "Use Termux instead".
 *
 * Android offers everything ([ConnectFeatures.All]).
 */
@Composable
fun ConnectScreen(
    onOpenSettings: () -> Unit,
    onPair: (prefillUrl: String?) -> Unit,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller = viewModel.controller
    val context = LocalContext.current
    // Also fires when the screen comes back from pairing, settings or a dropped connection, because
    // each of those removes this screen from composition. The view model outlives it, so without
    // this a Recent row keeps whatever verdict it was last given — "unavailable" included.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.refreshRecent() }

    val loopbackMode = state.mode == ConnectMode.LOOPBACK
    // The "this phone" watch lives exactly as long as the card is on screen in that mode. The
    // ViewModel is scoped to the activity, so it cannot tell when this screen went away, and a
    // poll nobody is looking at is two requests a tick for nothing.
    LifecycleResumeEffect(loopbackMode) {
        if (loopbackMode) {
            controller.refreshTermux()
            controller.startLoopbackWatch()
            // The sizes on the card come from the latest release's manifest.
            if (viewModel.builtIn.state.value.runtime == RuntimeState.NotInstalled) viewModel.builtIn.refreshRelease()
        }
        onPauseOrDispose { controller.stopLoopbackWatch() }
    }

    // Saveable: the pages are full-screen windows, and a rotation must not close one mid-set-up.
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    var controlsOpen by rememberSaveable { mutableStateOf(false) }
    var controlsAtLog by rememberSaveable { mutableStateOf(false) }

    fun builtInAction(action: RuntimeAction, recordAttempt: (() -> Unit) -> Unit) {
        when (action) {
            RuntimeAction.SetUp, RuntimeAction.ShowProgress -> setupOpen = true
            RuntimeAction.Start, RuntimeAction.RetryStart -> recordAttempt(controller::startBuiltIn)
            RuntimeAction.Stop -> controller.stopBuiltIn()
            RuntimeAction.Connect -> recordAttempt(controller::connectLoopback)
            RuntimeAction.Restart -> recordAttempt(controller::restartBuiltIn)
            RuntimeAction.RetryInstall -> {
                // On a metered network the page asks first; otherwise go straight on.
                if (!viewModel.builtIn.isMetered()) controller.setUpBuiltIn()
                setupOpen = true
            }
            RuntimeAction.FreeSpace -> RuntimeSystem.openStorageSettings(context)
            RuntimeAction.UpdateApp -> viewModel.builtIn.updateApp { RuntimeSystem.openAppUpdate(context) }
            RuntimeAction.UseOtherPort -> controller.startBuiltInOn(nextPort(state.loopbackPort))
            RuntimeAction.OpenControls, RuntimeAction.ShowLog -> {
                controlsAtLog = action == RuntimeAction.ShowLog
                controlsOpen = true
            }
        }
    }

    // Whether "Use Termux instead" starts open is decided once per app process, when the screen
    // first knows what answers on the port; after that it is the person's to open and close. Kept
    // in the view model: this screen leaves composition for Settings, and deciding again on the
    // way back would read a probe result from before the harness changed.
    var termuxDefault by viewModel.termuxDefault
    var termuxChosen by viewModel.termuxChosen
    val termuxNow = termuxSectionStartsOpen(
        builtIn = state.builtIn,
        termuxInstalled = state.termuxInstalled,
        termuxPermission = state.termuxPermission,
        termuxAutoStart = state.autoStartLoopbackHarness,
        control = state.harnessControl,
        loopback = state.loopback,
    )
    LaunchedEffect(state.loopback is LoopbackStatus.Unknown) {
        if (termuxDefault == null && state.loopback !is LoopbackStatus.Unknown) termuxDefault = termuxNow
    }
    val termuxOpen = termuxChosen ?: termuxDefault ?: termuxNow

    ConnectContent(
        state = state,
        controller = controller,
        features = ConnectFeatures.All,
        onOpenSettings = onOpenSettings,
        onPair = onPair,
        loopbackCard = { recordAttempt ->
            val card = state.builtInCard
            if (card == null) {
                ThisPhoneCard(state = state, viewModel = viewModel, onRecordAttempt = recordAttempt)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
                    BuiltInHarnessCard(
                        card = card,
                        builtIn = state.builtIn,
                        port = state.loopbackPort,
                        onAction = { builtInAction(it, recordAttempt) },
                    )
                    TermuxDisclosure(open = termuxOpen, onToggle = { termuxChosen = !termuxOpen }) {
                        ThisPhoneCard(state = state, viewModel = viewModel, onRecordAttempt = recordAttempt)
                    }
                }
            }
        },
        onBuiltInAction = { action -> builtInAction(action) { it() } },
    )

    if (setupOpen && state.builtIn.supported) {
        RuntimeSetupPage(
            host = viewModel.builtIn,
            state = state.builtIn,
            connectStage = state.stage,
            onSetUp = controller::setUpBuiltIn,
            onConnect = controller::connectLoopback,
            onAction = { action -> builtInAction(action) { it() } },
            // Closing the page changes nothing: the set-up carries on, and ends by itself.
            onClose = { setupOpen = false },
        )
    }
    if (controlsOpen && state.builtIn.supported) {
        RuntimeControlPage(
            host = viewModel.builtIn,
            port = state.loopbackPort,
            autoStart = state.autoStartBuiltInHarness,
            onAutoStartChange = { controller.setAuto("autoStartBuiltIn", it) },
            onStart = controller::startBuiltIn,
            onStop = controller::stopBuiltIn,
            onRestart = controller::restartBuiltIn,
            onSetUp = {
                controlsOpen = false
                setupOpen = true
            },
            onAction = { action -> builtInAction(action) { it() } },
            onClose = { controlsOpen = false },
            onUninstall = { keep -> viewModel.builtIn.uninstall(keepUserData = keep) },
            scrollToLog = controlsAtLog,
        )
    }
}
