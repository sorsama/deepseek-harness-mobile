package com.labteto.dshmobile.ios

import androidx.compose.runtime.Composable
import com.labteto.dshmobile.ui.components.HapticEvent
import com.labteto.dshmobile.ui.components.HapticPlatform
import com.labteto.dshmobile.ui.components.HapticPlayer
import com.labteto.dshmobile.ui.theme.HapticStrength
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UISelectionFeedbackGenerator

/**
 * The iOS half of the haptic vocabulary: the same events the Android engine plays, on UIKit's three
 * feedback generators.
 *
 * Android picks a tier per phone (composed primitives, predefined effects, a one-shot, the View's
 * constants). iOS has one tier, the Taptic Engine behind these generators, so the mapping is a
 * table:
 *
 * | Event | Plays |
 * |---|---|
 * | Tap | impact, light |
 * | ToggleOn | impact, medium |
 * | ToggleOff | impact, light |
 * | Selection | selection changed |
 * | Confirm | notification, success |
 * | Reject | notification, error |
 * | Attention | notification, warning |
 * | LongPress | impact, heavy |
 * | Tick | impact, soft |
 * | DragStart | impact, medium |
 * | DragCross | selection changed (a fast stream is already thinned by the ticker) |
 * | DragDrop | impact, rigid |
 *
 * The user's strength moves an impact one step along soft, light, medium, rigid, heavy (Android moves
 * a predefined effect one rung of its own ladder the same way) and the weight of a drag tick is the
 * impact's intensity. The system's own setting (Settings, Sounds & Haptics, System Haptics) is
 * honoured by the generators themselves.
 *
 * Calls come from the main thread, where Compose runs. Unverified on a device: the simulator has no
 * Taptic Engine, so what CI shows is that the calls are made and do not crash.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosHapticPlayer : HapticPlayer {
    override fun play(event: HapticEvent, strength: HapticStrength, weight: Float): Boolean {
        when (event) {
            HapticEvent.Tap -> impact(Step.Light, strength, weight)
            HapticEvent.ToggleOn -> impact(Step.Medium, strength, weight)
            HapticEvent.ToggleOff -> impact(Step.Light, strength, weight)
            HapticEvent.Selection, HapticEvent.DragCross -> UISelectionFeedbackGenerator().apply { prepare() }.selectionChanged()
            HapticEvent.Confirm -> notify(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
            HapticEvent.Reject -> notify(UINotificationFeedbackType.UINotificationFeedbackTypeError)
            HapticEvent.Attention -> notify(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
            HapticEvent.LongPress -> impact(Step.Heavy, strength, weight)
            HapticEvent.Tick -> impact(Step.Soft, strength, weight)
            HapticEvent.DragStart -> impact(Step.Medium, strength, weight)
            HapticEvent.DragDrop -> impact(Step.Rigid, strength, weight)
        }
        return true
    }

    private enum class Step(val style: UIImpactFeedbackStyle) {
        Soft(UIImpactFeedbackStyle.UIImpactFeedbackStyleSoft),
        Light(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight),
        Medium(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium),
        Rigid(UIImpactFeedbackStyle.UIImpactFeedbackStyleRigid),
        Heavy(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy),
    }

    private fun impact(base: Step, strength: HapticStrength, weight: Float) {
        val steps = Step.entries
        val shift = when (strength) {
            HapticStrength.Light -> -1
            HapticStrength.Normal -> 0
            HapticStrength.Strong -> 1
        }
        val step = steps[(base.ordinal + shift).coerceIn(0, steps.lastIndex)]
        val generator = UIImpactFeedbackGenerator(style = step.style)
        generator.prepare()
        generator.impactOccurredWithIntensity(weight.coerceIn(0f, 1f).toDouble())
    }

    private fun notify(type: UINotificationFeedbackType) {
        val generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(type)
    }
}

/** Provided to the theme once, by the iOS entry point. */
internal object IosHapticPlatform : HapticPlatform {
    private val player = IosHapticPlayer()

    @Composable
    override fun rememberPlayer(): HapticPlayer = player
}
