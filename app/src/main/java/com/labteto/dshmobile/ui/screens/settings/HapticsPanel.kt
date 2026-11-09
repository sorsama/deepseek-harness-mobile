package com.labteto.dshmobile.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.AndroidHapticPlayer
import com.labteto.dshmobile.ui.components.DsHaptics
import com.labteto.dshmobile.ui.components.DsSegment
import com.labteto.dshmobile.ui.components.DsSegmented
import com.labteto.dshmobile.ui.components.HapticEngine
import com.labteto.dshmobile.ui.components.HapticTier
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.rememberHapticTicker
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.HapticStrength
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * What sits under the haptics switch in Appearance: how strong the effects are, a row of buttons
 * that play each effect so the choice can be felt straight away, and a small wheel that ticks the
 * way the app's pickers and drag handles do.
 *
 * It also says why nothing may be felt. A phone with Touch feedback turned off in its own settings
 * silences every app, and an app that does not say so just looks broken, so that case gets a
 * notice and a button to the sound settings. A phone with no vibration motor is stated plainly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HapticsPanel(
    strength: HapticStrength,
    onStrength: (HapticStrength) -> Unit,
) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    val view = LocalView.current
    val haptics = rememberDsHaptics()
    val engine = remember { HapticEngine.get(context) }
    // Read again on every return to the screen: the way to fix this is in the system settings.
    var touchFeedbackOn by remember { mutableStateOf(engine.systemTouchFeedbackOn()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { touchFeedbackOn = engine.systemTouchFeedbackOn() }
    var switchOn by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Text(
            stringResource(R.string.haptics_strength),
            style = DsType.label12,
            color = colors.labelTertiary,
            modifier = Modifier.padding(top = DsSpacing.small),
        )
        DsSegmented(
            segments = listOf(
                DsSegment(HapticStrength.Light.key, stringResource(R.string.haptics_strength_light)),
                DsSegment(HapticStrength.Normal.key, stringResource(R.string.haptics_strength_normal)),
                DsSegment(HapticStrength.Strong.key, stringResource(R.string.haptics_strength_strong)),
            ),
            selectedKey = strength.key,
            onSelect = { key ->
                val next = HapticStrength.of(key)
                // The new strength, felt at once: the theme has not caught up with the change yet.
                DsHaptics(AndroidHapticPlayer(view), enabled = true, strength = next).tap()
                onStrength(next)
            },
            stretch = true,
            feedback = false,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            stringResource(R.string.haptics_try),
            style = DsType.label12,
            color = colors.labelTertiary,
            modifier = Modifier.padding(top = DsSpacing.small),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            TryChip(stringResource(R.string.haptics_try_tap)) { haptics.tap() }
            TryChip(stringResource(R.string.haptics_try_switch)) {
                switchOn = !switchOn
                haptics.toggle(switchOn)
            }
            TryChip(stringResource(R.string.haptics_try_confirm)) { haptics.confirm() }
            TryChip(stringResource(R.string.haptics_try_reject)) { haptics.reject() }
            TryChip(stringResource(R.string.haptics_try_alert)) { haptics.attention() }
            TryChip(stringResource(R.string.haptics_try_hold)) { haptics.longPress() }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = DsSpacing.tiny),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
        ) {
            PracticeWheel(Modifier.width(WheelWidth))
            Text(
                stringResource(R.string.haptics_wheel_hint),
                style = DsType.small13,
                color = colors.labelTertiary,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            stringResource(
                when (engine.tier) {
                    HapticTier.Primitives -> R.string.haptics_status_rich
                    HapticTier.Predefined -> R.string.haptics_status_standard
                    HapticTier.OneShot, HapticTier.ViewConstants -> R.string.haptics_status_basic
                    HapticTier.Silent -> R.string.haptics_status_none
                },
            ),
            style = DsType.small13,
            color = colors.labelTertiary,
        )

        if (!touchFeedbackOn) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = DsShapes.block,
                color = colors.warnTertiary,
                border = BorderStroke(1.dp, colors.warnBorder),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = DsSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                ) {
                    Text(stringResource(R.string.haptics_system_off), style = DsType.small13, color = colors.warnText)
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .clip(DsShapes.pillFull)
                            .clickable(role = Role.Button) {
                                haptics.tap()
                                runCatching { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
                            }
                            .heightIn(min = 40.dp)
                            .padding(end = DsSpacing.small),
                    ) {
                        Text(
                            stringResource(R.string.haptics_system_open),
                            style = DsType.small13Strong,
                            color = colors.warnLabel,
                        )
                    }
                }
            }
        }
    }
}

/** One effect to try. Plays nothing of its own on press: the effect is the whole point. */
@Composable
private fun TryChip(label: String, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(DsShapes.control)
            .border(1.dp, colors.borderL3, DsShapes.control)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = DsSpacing.comfortable),
    ) {
        Text(label, style = DsType.small13Strong, color = colors.labelPrimary)
    }
}

private val RowHeight = 40.dp
private val WheelWidth = 96.dp
private const val WheelItems = 60

/**
 * A wheel of numbers that ticks like the app's pickers: one light tick for each row that passes
 * the middle, throttled and lighter when flung, and a firmer one where it comes to rest.
 */
@Composable
private fun PracticeWheel(modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val ticker = rememberHapticTicker()
    val state = rememberLazyListState(initialFirstVisibleItemIndex = 12)
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }
    val center by remember { derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > rowPx / 2f) 1 else 0 } }
    val description = stringResource(R.string.haptics_wheel_description)

    LaunchedEffect(state, ticker) {
        var moved = false
        launch {
            // The first value is where the wheel starts, not a tick.
            snapshotFlow { center }.drop(1).collect {
                moved = true
                ticker.cross()
            }
        }
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && moved) {
                moved = false
                ticker.settle()
            }
        }
    }

    Box(
        modifier = modifier
            .height(RowHeight * 3)
            .clip(DsShapes.control)
            .background(colors.bgLayer2)
            .border(1.dp, colors.borderL2, DsShapes.control)
            .semantics {
                contentDescription = description
                stateDescription = center.toString()
            },
        contentAlignment = Alignment.Center,
    ) {
        // The middle row's plate: what the numbers stop on.
        Box(
            Modifier
                .fillMaxWidth()
                .height(RowHeight)
                .padding(horizontal = 6.dp)
                .clip(DsShapes.segment)
                .background(colors.bgLayer3),
        )
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
            contentPadding = PaddingValues(vertical = RowHeight),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items((0 until WheelItems).toList()) { index ->
                val distance = kotlin.math.abs(index - center)
                Box(Modifier.fillMaxWidth().height(RowHeight), contentAlignment = Alignment.Center) {
                    Text(
                        index.toString().padStart(2, '0'),
                        style = DsType.mono13,
                        color = colors.labelPrimary,
                        modifier = Modifier.alpha(
                            when (distance) {
                                0 -> 1f
                                1 -> 0.45f
                                else -> 0.2f
                            },
                        ),
                    )
                }
            }
        }
    }
}
