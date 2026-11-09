package com.labteto.dshmobile.ui.components

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.labteto.dshmobile.ui.theme.HapticStrength

/**
 * The Android half of the haptic vocabulary (`DsHaptics` and `HapticPlayer` live in `:sharedUi`):
 * the engine the app has always had, called exactly as before.
 *
 * [view] is the view of the window that is composing, which only matters to the engine's last
 * tier (`View.performHapticFeedback`): a dialog or a bottom sheet has a window, and so a view, of
 * its own.
 */
class AndroidHapticPlayer(
    private val view: View,
    private val engine: HapticEngine = HapticEngine.get(view.context),
) : HapticPlayer {
    override fun play(event: HapticEvent, strength: HapticStrength, weight: Float): Boolean =
        engine.play(view, event, strength, weight)
}

/**
 * Provided once, at the root of the app's composition (`AppRoot`), as `LocalHapticPlatform`. Each
 * composable that asks for haptics gets a player for its own window's view, as it did when
 * `rememberDsHaptics` read `LocalView` itself.
 */
object AndroidHapticPlatform : HapticPlatform {
    @Composable
    override fun rememberPlayer(): HapticPlayer {
        val view = LocalView.current
        return remember(view) { AndroidHapticPlayer(view) }
    }
}
