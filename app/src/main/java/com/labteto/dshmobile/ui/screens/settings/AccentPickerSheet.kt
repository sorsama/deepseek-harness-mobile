package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DetentCounter
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.HapticTicker
import com.labteto.dshmobile.ui.components.LocalDsSheetHide
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.rememberHapticTicker
import com.labteto.dshmobile.ui.screens.connect.InkTextField
import com.labteto.dshmobile.ui.theme.AccentQuickPicks
import com.labteto.dshmobile.ui.theme.AccentRamp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsThemeTokens
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.HexInput
import com.labteto.dshmobile.ui.theme.Hsv
import com.labteto.dshmobile.ui.theme.classifyHexInput
import com.labteto.dshmobile.ui.theme.deriveAccentRamp
import com.labteto.dshmobile.ui.theme.hsvToColor
import com.labteto.dshmobile.ui.theme.parseAccentHex
import com.labteto.dshmobile.ui.theme.toAccentHex
import com.labteto.dshmobile.ui.theme.toHsv
import com.labteto.dshmobile.ui.theme.withAccent
import kotlin.math.roundToInt

/** Height of the saturation/brightness area, and the diameter of the thumbs drawn on the controls. */
private val AreaHeight = 176.dp
private val ThumbSize = 26.dp

/** How far one accessibility action moves saturation or brightness. */
private const val ACTION_STEP = 0.05f

/** One detent per tenth of saturation or brightness. */
private const val DETENT_STEP = 0.1f

/** One detent per this many degrees of hue. */
private const val HUE_DETENT_DEGREES = 10f

/**
 * Where the centre of a thumb of diameter [thumbPx] sits on an axis of [sizePx] at [fraction].
 *
 * The thumb is inset by its own radius at both ends, so the ring is drawn whole and cannot spill
 * past the control — which, in a sheet, means it cannot spill off the screen either. A control too
 * narrow for the whole thumb centres it instead of producing a negative travel.
 */
internal fun thumbCentre(fraction: Float, sizePx: Float, thumbPx: Float): Float {
    if (sizePx <= thumbPx) return sizePx / 2f
    return thumbPx / 2f + fraction.coerceIn(0f, 1f) * (sizePx - thumbPx)
}

/**
 * The custom accent picker.
 *
 * One colour goes in and one comes out; the dark and light ramps are derived from it (see
 * `deriveAccentRamp`), and the two previews at the top show exactly what the app will paint in each
 * scheme — including the lightness adjustment that keeps text readable, which is why the preview is
 * worth having. Nothing is persisted from in here: [onApply] is called once, on Apply, and Cancel
 * or a swipe-away leaves the stored colour untouched.
 *
 * The saturation/brightness area and the hue strip are drawn controls, so each carries its own
 * semantics (a range for the strip, custom actions for the area) and the hex field is the
 * non-gesture route to any colour.
 */
@Composable
internal fun AccentPickerSheet(
    initial: Color,
    onApply: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    DsBottomSheet(
        title = stringResource(R.string.appearance_custom_title),
        subtitle = stringResource(R.string.appearance_custom_hint),
        onDismiss = onDismiss,
    ) {
        AccentPickerContent(initial = initial, onApply = onApply, onDismiss = onDismiss)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPickerContent(
    initial: Color,
    onApply: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    // One tick stream for both pickers: the area and the strip are never dragged together.
    val ticker = rememberHapticTicker()
    val keyboard = LocalSoftwareKeyboardController.current
    // Read inside the sheet's content: the hide-then-act hook is provided to it, not around it.
    val hide = LocalDsSheetHide.current

    var picked by remember { mutableStateOf(initial) }
    var hsv by remember { mutableStateOf(initial.toHsv()) }
    var hexText by remember { mutableStateOf(initial.toAccentHex()) }
    val hexState = classifyHexInput(hexText)
    val ramp = remember(picked) { deriveAccentRamp(picked) }

    fun choose(color: Color, from: Hsv = color.toHsv()) {
        picked = color
        hsv = from
        hexText = color.toAccentHex()
    }

    fun move(next: Hsv) = choose(hsvToColor(next.h, next.s, next.v), next)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        // Both schemes at once, because the pick is judged in whichever one the phone is not in.
        Text(stringResource(R.string.appearance_custom_preview), style = DsType.label12, color = colors.labelTertiary)
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            AccentPreview(ramp = ramp, dark = true, modifier = Modifier.weight(1f))
            AccentPreview(ramp = ramp, dark = false, modifier = Modifier.weight(1f))
        }

        SaturationBrightnessArea(hsv = hsv, onChange = ::move, ticker = ticker)
        HueStrip(hsv = hsv, onChange = ::move, ticker = ticker)

        InkTextField(
            value = hexText,
            onValueChange = { raw ->
                hexText = raw
                parseAccentHex(raw)?.let { color ->
                    picked = color
                    hsv = color.toHsv()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.appearance_custom_hex),
            placeholder = stringResource(R.string.appearance_custom_hex_placeholder),
            mono = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        )
        if (hexState == HexInput.Invalid) {
            Text(
                stringResource(R.string.appearance_custom_hex_invalid),
                style = DsType.caption11,
                color = colors.error,
            )
        }

        Text(stringResource(R.string.appearance_custom_quick_picks), style = DsType.label12, color = colors.labelTertiary)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            AccentQuickPicks.forEach { color ->
                QuickPick(
                    color = color,
                    selected = color == picked,
                    onClick = {
                        haptics.selection()
                        choose(color)
                    },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = {
                    haptics.tap()
                    hide { onDismiss() }
                },
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Large,
                modifier = Modifier.weight(1f),
            )
            DsButton(
                text = stringResource(R.string.appearance_custom_apply),
                onClick = {
                    haptics.confirm()
                    // Applied first, then the sheet slides away: the theme changes under it, so the
                    // result is already on screen by the time the page is back.
                    onApply(picked)
                    hide { onDismiss() }
                },
                enabled = hexState == HexInput.Valid,
                size = DsButtonSize.Large,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A sample of the app in one scheme, painted from the derived [ramp] over that scheme's own tokens:
 * an accent button, a chip and a toggle, and a link. Not interactive — it is a picture.
 */
@Composable
private fun AccentPreview(ramp: AccentRamp, dark: Boolean, modifier: Modifier = Modifier) {
    val scheme = (if (dark) DsThemeTokens.dark else DsThemeTokens.light).withAccent(ramp, dark)
    val name = stringResource(if (dark) R.string.settings_appearance_dark else R.string.settings_appearance_light)
    Column(
        modifier = modifier
            .clip(DsShapes.block)
            .background(scheme.bgBase)
            .border(1.dp, scheme.borderL2, DsShapes.block)
            .padding(DsSpacing.small + DsSpacing.tiny)
            .clearAndSetSemantics { contentDescription = name },
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Text(name, style = DsType.label12, color = scheme.labelTertiary)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .clip(DsShapes.buttonCapsule)
                .background(scheme.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.appearance_custom_sample_button), style = DsType.button, color = scheme.onAccent)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(DsShapes.chip)
                    .background(scheme.accentTertiary)
                    .border(1.dp, scheme.accentBorder, DsShapes.chip)
                    .padding(horizontal = DsSpacing.xsmall, vertical = DsSpacing.tiny),
            ) {
                Text(stringResource(R.string.appearance_custom_sample_chip), style = DsType.caption11Strong, color = scheme.accent)
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 24.dp)
                    .clip(DsShapes.pillFull)
                    .background(scheme.accent),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(scheme.onAccent),
                )
            }
        }
        Text(
            stringResource(R.string.appearance_custom_sample_link),
            style = DsType.small13.copy(textDecoration = TextDecoration.Underline),
            color = scheme.accent,
        )
    }
}

/**
 * Saturation along the x axis and brightness up the y axis, over the current hue.
 *
 * Touch-down already moves the thumb — a picker that waits for a drag slop feels broken — and the
 * whole gesture is consumed so the sheet does not scroll or dismiss under a finger that is picking.
 */
@Composable
private fun SaturationBrightnessArea(
    hsv: Hsv,
    onChange: (Hsv) -> Unit,
    ticker: HapticTicker,
) {
    val colors = DsTheme.colors
    val current by rememberUpdatedState(hsv)
    val change by rememberUpdatedState(onChange)
    // A detent every tenth of saturation and of brightness: sliding across the area ticks off the
    // columns and the rows, the way a wheel ticks off its notches.
    val saturationDetents = remember { DetentCounter(DETENT_STEP) }
    val brightnessDetents = remember { DetentCounter(DETENT_STEP) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val hue = remember(hsv.h) { hsvToColor(hsv.h, 1f, 1f) }
    val label = stringResource(R.string.appearance_custom_area)
    val state = stringResource(
        R.string.appearance_custom_area_state,
        (hsv.s * 100f).roundToInt(),
        (hsv.v * 100f).roundToInt(),
    )
    val moreSaturation = stringResource(R.string.appearance_custom_more_saturation)
    val lessSaturation = stringResource(R.string.appearance_custom_less_saturation)
    val moreBrightness = stringResource(R.string.appearance_custom_more_brightness)
    val lessBrightness = stringResource(R.string.appearance_custom_less_brightness)

    fun report(position: Offset) {
        if (size.width <= 0 || size.height <= 0) return
        val saturation = (position.x / size.width).coerceIn(0f, 1f)
        val brightness = 1f - (position.y / size.height).coerceIn(0f, 1f)
        val passed = saturationDetents.crossed(saturation) + brightnessDetents.crossed(brightness)
        if (passed > 0) ticker.cross()
        change(current.copy(s = saturation, v = brightness))
    }

    val thumbPx = with(LocalDensity.current) { ThumbSize.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(AreaHeight)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    ticker.reset()
                    saturationDetents.reset()
                    brightnessDetents.reset()
                    report(down.position)
                    drag(down.id) { moved ->
                        moved.consume()
                        report(moved.position)
                    }
                    ticker.settle()
                }
            }
            .semantics {
                contentDescription = label
                stateDescription = state
                customActions = listOf(
                    CustomAccessibilityAction(moreSaturation) {
                        change(current.copy(s = (current.s + ACTION_STEP).coerceIn(0f, 1f))); true
                    },
                    CustomAccessibilityAction(lessSaturation) {
                        change(current.copy(s = (current.s - ACTION_STEP).coerceIn(0f, 1f))); true
                    },
                    CustomAccessibilityAction(moreBrightness) {
                        change(current.copy(v = (current.v + ACTION_STEP).coerceIn(0f, 1f))); true
                    },
                    CustomAccessibilityAction(lessBrightness) {
                        change(current.copy(v = (current.v - ACTION_STEP).coerceIn(0f, 1f))); true
                    },
                )
            },
    ) {
        // The painted area is its own clipped layer: clipping the box that also holds the thumb
        // sliced the ring in half wherever the pick sat on an edge.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(DsShapes.code)
                .drawBehind {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hue)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                }
                .border(1.dp, colors.borderL2, DsShapes.code),
        )
        PickerThumb(
            x = thumbCentre(hsv.s, size.width.toFloat(), thumbPx),
            y = thumbCentre(1f - hsv.v, size.height.toFloat(), thumbPx),
            fill = hsvToColor(hsv.h, hsv.s, hsv.v),
        )
    }
}

/** The hue strip: the whole wheel laid out flat, with a thumb on the current hue. */
@Composable
private fun HueStrip(
    hsv: Hsv,
    onChange: (Hsv) -> Unit,
    ticker: HapticTicker,
) {
    val colors = DsTheme.colors
    val current by rememberUpdatedState(hsv)
    val change by rememberUpdatedState(onChange)
    // A detent every 10 degrees: 36 notches around the wheel, laid out flat.
    val hueDetents = remember { DetentCounter(HUE_DETENT_DEGREES) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val label = stringResource(R.string.appearance_custom_hue)
    val state = stringResource(R.string.appearance_custom_hue_state, hsv.h.roundToInt())
    val stops = remember { (0..6).map { Color.hsv(it * 60f, 1f, 1f) } }
    val thumbPx = with(LocalDensity.current) { ThumbSize.toPx() }

    fun report(x: Float) {
        if (size.width <= 0) return
        // 360 is red again, so the strip stops a hair short of it rather than wrapping to 0.
        val hue = (x / size.width).coerceIn(0f, 1f) * 359.99f
        if (hueDetents.crossed(hue) > 0) ticker.cross()
        change(current.copy(h = hue))
    }

    // The 48dp row is the touch target; the strip drawn inside it is slimmer.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DsSpacing.touchTarget)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    ticker.reset()
                    hueDetents.reset()
                    report(down.position.x)
                    drag(down.id) { moved ->
                        moved.consume()
                        report(moved.position.x)
                    }
                    ticker.settle()
                }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(current.h, 0f..359.99f)
                setProgress { target ->
                    change(current.copy(h = target.coerceIn(0f, 359.99f)))
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clip(DsShapes.pillFull)
                .background(Brush.horizontalGradient(stops))
                .border(1.dp, colors.borderL2, DsShapes.pillFull),
        )
        PickerThumb(
            x = thumbCentre(hsv.h / 359.99f, size.width.toFloat(), thumbPx),
            y = size.height / 2f,
            fill = hsvToColor(hsv.h, 1f, 1f),
        )
    }
}

/**
 * A ring thumb centred on ([x], [y]) in its parent's pixel space.
 *
 * Positioned by a [Layout] rather than `Modifier.absoluteOffset`: an offset is applied on top of
 * wherever the parent's alignment already put the child, so in the hue strip — whose row is
 * centre-aligned — the thumb was displaced by half the row and slid off the screen, and it never
 * tracked the finger. Laying the node out at the parent's full size makes that alignment a no-op,
 * and placing the ring at absolute coordinates keeps it out of RTL mirroring.
 */
@Composable
private fun PickerThumb(x: Float, y: Float, fill: Color) {
    val colors = DsTheme.colors
    val half = with(LocalDensity.current) { ThumbSize.toPx() / 2f }
    Layout(
        content = {
            Box(
                modifier = Modifier
                    .size(ThumbSize)
                    .clip(CircleShape)
                    .background(fill)
                    // Two rings, light over dark, so the thumb is visible on any colour under it.
                    .border(3.dp, Color.White, CircleShape)
                    .border(1.dp, colors.bgBase.copy(alpha = 0.5f), CircleShape),
            )
        },
    ) { measurables, constraints ->
        val thumb = measurables.first().measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            thumb.place((x - half).roundToInt(), (y - half).roundToInt())
        }
    }
}

/** One quick-pick disc. 32dp of colour inside a 48dp target, with the selection a ring and a check. */
@Composable
private fun QuickPick(color: Color, selected: Boolean, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val hex = color.toAccentHex()
    val description = stringResource(R.string.appearance_custom_quick_pick, hex)
    Box(
        modifier = Modifier
            .size(DsSpacing.touchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.labelPrimary else colors.borderL2,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (com.labteto.dshmobile.ui.theme.contrastRatio(Color.White, color) >= com.labteto.dshmobile.ui.theme.contrastRatio(Color.Black, color)) Color.White else Color.Black,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
