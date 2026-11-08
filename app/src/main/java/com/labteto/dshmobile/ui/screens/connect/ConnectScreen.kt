package com.labteto.dshmobile.ui.screens.connect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.connection.ConnectMode

/**
 * The Android Connect screen: the shared screen (`ConnectContent` in `:sharedUi`) with the three
 * things only Android adds.
 *
 *  - The view model, Hilt's, which owns the [ConnectController] the shared screen drives.
 *  - The lifecycle: refresh the Recent liveness when the screen comes back, and watch the harness on
 *    this phone only while the "This phone" card is showing.
 *  - The "This phone" card itself ([ThisPhoneCard]), because the harness it starts and stops runs in
 *    Termux.
 *
 * Android offers everything ([ConnectFeatures.All]); the screen the person sees is the one the app
 * has always had.
 */
@Composable
fun ConnectScreen(
    onOpenSettings: () -> Unit,
    onPair: (prefillUrl: String?) -> Unit,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller = viewModel.controller
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
        }
        onPauseOrDispose { controller.stopLoopbackWatch() }
    }

    ConnectContent(
        state = state,
        controller = controller,
        features = ConnectFeatures.All,
        onOpenSettings = onOpenSettings,
        onPair = onPair,
        loopbackCard = { recordAttempt ->
            ThisPhoneCard(state = state, viewModel = viewModel, onRecordAttempt = recordAttempt)
        },
    )
}
