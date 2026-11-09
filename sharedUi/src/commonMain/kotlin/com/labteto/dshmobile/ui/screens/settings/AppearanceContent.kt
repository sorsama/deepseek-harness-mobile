package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.shared.resources.*
import com.labteto.dshmobile.ui.components.DsMenu
import com.labteto.dshmobile.ui.components.DsSegment
import com.labteto.dshmobile.ui.components.DsSegmented
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.AccentPalette
import com.labteto.dshmobile.ui.theme.CUSTOM_ACCENT_KEY
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.FontSizeOption
import com.labteto.dshmobile.ui.theme.HapticStrength
import com.labteto.dshmobile.ui.theme.ThemeCustomization
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.labteto.dshmobile.ui.theme.deriveAccentRamp
import com.labteto.dshmobile.ui.theme.resolveCustomAccent
import com.labteto.dshmobile.ui.theme.isDsDark
import com.labteto.dshmobile.ui.theme.parseAccentHex
import com.labteto.dshmobile.ui.theme.toAccentHex
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The appearance card, the language row and the helpers they draw, shared by both apps.
 *
 * This is the first slice of the Settings page iOS can have: everything that changes how the app
 * looks or which language it speaks. The rest of the Android page (the Termux card, plugins, the
 * update checker, the data wipe) is Android-only and stays in `:app`.
 *
 * Values come from [AppearanceSettings]; a change goes back through the caller's `onSet`, which
 * Android answers with `HostsStore.setSetting` and iOS with `UserDefaultsSettingsStore`. Nothing
 * here knows which platform is drawing it.
 */

data class LanguageOption(val tag: String?, val label: String?, val labelRes: StringResource? = null)

val LanguageOptions = listOf(
    LanguageOption(null, null, com.labteto.dshmobile.shared.resources.Res.string.settings_language_system),
    LanguageOption("en", "English"),
    LanguageOption("zh", "中文"),
    LanguageOption("hi", "हिन्दी"),
    LanguageOption("es", "Español"),
    LanguageOption("fr", "Français"),
    LanguageOption("ar", "العربية"),
    LanguageOption("bn", "বাংলা"),
    LanguageOption("pt", "Português"),
    LanguageOption("ru", "Русский"),
    LanguageOption("ur", "اردو"),
    LanguageOption("th", "ไทย"),
)


/** One settings group as a raised card, so groups read as blocks rather than a running list. */
@Composable
fun SettingsCard(title: String, content: @Composable () -> Unit) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        SectionHeader(title)
        Spacer(Modifier.height(DsSpacing.small))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.block)
                .background(colors.bgLayer1)
                .border(1.dp, colors.borderL2, DsShapes.block)
                .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            content()
        }
    }
}

@Composable
fun LanguageRow(settings: AppearanceSettings, onSelect: (String?) -> Unit) {
    val colors = DsTheme.colors
    // A dropdown, not a grid of twelve cells. The grid spent four rows of a settings page on a
    // choice that is made once and then never touched, and at three per row the longer endonyms had
    // to be ellipsised to fit — so the control was both the largest thing on the screen and unable
    // to spell out its own options.
    val labels = LanguageOptions.associateWith { option ->
        option.label ?: option.labelRes?.let { stringResource(it) }.orEmpty()
    }
    val current = LanguageOptions.firstOrNull { it.tag == settings.localeOverride }
        ?: LanguageOptions.first()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.settings_language),
            style = DsType.std14,
            color = colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
        DsMenu(
            // A select is a control, not a status pill: the 12dp corner every other input and button has.
            anchorShape = DsShapes.control,
            anchor = {
                Row(
                    modifier = Modifier
                        .height(DsSizes.buttonCompact)
                        .clip(DsShapes.control)
                        .background(colors.bgLayer2)
                        .border(1.dp, colors.borderL2, DsShapes.control)
                        .padding(horizontal = DsSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Text(
                        labels[current].orEmpty(),
                        style = DsType.small13,
                        color = colors.labelPrimary,
                        maxLines = 1,
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = colors.labelSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            },
            items = LanguageOptions.map { option ->
                MenuItem(
                    text = labels[option].orEmpty(),
                    icon = Icons.Filled.Check.takeIf { option.tag == settings.localeOverride },
                ) { onSelect(option.tag) }
            },
        )
    }
}

/**
 * The customizable theme card: scheme, accent, OLED canvas, text size and touch feedback.
 *
 * Everything here writes through [SettingsViewModel.set] like the rest of the page, so a change is
 * persisted and re-themes the whole app in the same frame. The controls are previews as well as
 * pickers — the segmented thumb, the accent swatch and the sample text are already rendered in the
 * scheme being chosen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceCard(
    settings: AppearanceSettings,
    onSet: ((AppearanceSettings) -> AppearanceSettings) -> Unit,
    hapticsPreview: @Composable () -> Unit = {},
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val preference = ThemePreference.of(settings.themePreference)
    val accent = AccentPalette.of(settings.themeAccent)
    // The colour in force, if the accent is the user's own. The stored colour on its own is not
    // that: it is kept behind a preset too, so the Custom swatch can keep showing it.
    val customInForce = resolveCustomAccent(settings.themeAccent, settings.themeCustomAccent)
    val storedCustom = parseAccentHex(settings.themeCustomAccent)
    val fontSize = FontSizeOption.of(settings.fontSize)
    val dark = isDsDark(
        ThemeCustomization(
            preference = preference,
            accent = accent,
            customAccent = customInForce,
            amoled = settings.amoledBlack,
            fontSize = fontSize,
            haptics = settings.hapticsEnabled,
        ),
    )
    var pickerOpen by remember { mutableStateOf(false) }
    SettingsCard(stringResource(Res.string.settings_appearance)) {
        // Scheme.
        DsSegmented(
            segments = listOf(
                DsSegment("light", stringResource(Res.string.settings_appearance_light)),
                DsSegment("dark", stringResource(Res.string.settings_appearance_dark)),
                DsSegment("system", stringResource(Res.string.settings_appearance_system)),
            ),
            selectedKey = preference.name.lowercase(),
            onSelect = { mode ->
                onSet { it.copy(themePreference = mode.lowercase()) }
            },
            stretch = true,
            role = Role.Tab,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(Res.string.appearance_customize_hint),
            style = DsType.small13,
            color = colors.labelTertiary,
            modifier = Modifier.padding(top = DsSpacing.tiny, bottom = DsSpacing.medium),
        )

        // Accent.
        Text(stringResource(Res.string.appearance_accent), style = DsType.label12, color = colors.labelTertiary)
        Spacer(Modifier.height(DsSpacing.small))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            AccentPalette.entries.forEach { palette ->
                AccentSwatch(
                    palette = palette,
                    selected = customInForce == null && palette == accent,
                    dark = dark,
                    onClick = {
                        haptics.selection()
                        onSet { it.copy(themeAccent = palette.key) }
                    },
                )
            }
            CustomAccentSwatch(
                color = storedCustom,
                selected = customInForce != null,
                dark = dark,
                onClick = {
                    haptics.tap()
                    pickerOpen = true
                },
            )
        }
        Spacer(Modifier.height(DsSpacing.small))

        // OLED canvas. Disabled in the light scheme, where it would change nothing and read as a
        // broken switch.
        ToggleRow(
            label = stringResource(Res.string.appearance_amoled),
            checked = settings.amoledBlack,
            hint = stringResource(Res.string.appearance_amoled_hint),
            enabled = dark,
            onChange = { onSet { it.copy(amoledBlack = !it.amoledBlack) } },
        )

        // Text size, applied as a multiplier on top of the system font scale.
        Text(
            stringResource(Res.string.appearance_text_size),
            style = DsType.label12,
            color = colors.labelTertiary,
            modifier = Modifier.padding(top = DsSpacing.small),
        )
        Spacer(Modifier.height(DsSpacing.small))
        DsSegmented(
            segments = listOf(
                DsSegment(FontSizeOption.Compact.key, stringResource(Res.string.appearance_text_size_compact)),
                DsSegment(FontSizeOption.Default.key, stringResource(Res.string.appearance_text_size_default)),
                DsSegment(FontSizeOption.Large.key, stringResource(Res.string.appearance_text_size_large)),
                DsSegment(FontSizeOption.Larger.key, stringResource(Res.string.appearance_text_size_larger)),
            ),
            selectedKey = fontSize.key,
            onSelect = { key -> onSet { it.copy(fontSize = key) } },
            stretch = true,
            modifier = Modifier.fillMaxWidth(),
        )

        ToggleRow(
            label = stringResource(Res.string.appearance_haptics),
            checked = settings.hapticsEnabled,
            hint = stringResource(Res.string.appearance_haptics_hint),
            onChange = {
                haptics.toggle(!settings.hapticsEnabled)
                onSet { it.copy(hapticsEnabled = !it.hapticsEnabled) }
            },
        )
        // The platform's own haptics panel (strength, the try-it buttons, the practice wheel):
        // Android's needs a Vibrator, so it is a slot rather than shared code.
        AnimatedVisibility(visible = settings.hapticsEnabled) { hapticsPreview() }

        // Reset to the ink defaults, keeping the light/dark choice: resetting appearance should not
        // also undo the scheme the user is comfortable in.
        Text(
            stringResource(Res.string.appearance_reset),
            style = DsType.small13Strong,
            color = colors.accent,
            modifier = Modifier
                .padding(top = DsSpacing.small)
                .clip(DsShapes.pillFull)
                .clickable {
                    haptics.tap()
                    onSet {
                        it.copy(
                            themeAccent = AccentPalette.Ink.key,
                            // The picked colour goes too: "reset" that left a custom colour behind
                            // would reset to Ink and still show a swatch for something not in use.
                            themeCustomAccent = null,
                            amoledBlack = false,
                            fontSize = FontSizeOption.Default.key,
                            hapticsEnabled = true,
                            hapticStrength = HapticStrength.Normal.key,
                        )
                    }
                }
                .padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
    if (pickerOpen) {
        AccentPickerSheet(
            // Where the reader already is: the stored pick if there is one, else the accent that
            // is painting the app right now.
            initial = storedCustom ?: colors.accent,
            onApply = { picked ->
                onSet { it.copy(themeAccent = CUSTOM_ACCENT_KEY, themeCustomAccent = picked.toAccentHex()) }
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/** One accent choice: the colour disc plus its name, checked when active. */
@Composable
private fun AccentSwatch(
    palette: AccentPalette,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val fill = if (dark) palette.dark else palette.light
    val onFill = if (dark) palette.onDark else palette.onLight
    SwatchFrame(
        label = stringResource(accentLabelRes(palette)),
        selected = selected,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize().background(fill), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = onFill,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The swatch that opens the picker: the user's colour once one is set (drawn the way the app will
 * paint it in the active scheme, like the presets beside it), a colour wheel with a plus until then.
 */
@Composable
private fun CustomAccentSwatch(
    color: Color?,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val ramp = remember(color) { color?.let(::deriveAccentRamp) }
    val label = stringResource(Res.string.appearance_accent_custom)
    val description = color?.let { stringResource(Res.string.appearance_accent_custom_value, it.toAccentHex()) } ?: label
    val wheel = remember { Brush.sweepGradient((0..6).map { Color.hsv(it * 60f, 0.75f, 1f) }) }
    SwatchFrame(
        label = label,
        description = description,
        selected = selected,
        onClick = onClick,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (ramp != null) Modifier.background(ramp.fill(dark)) else Modifier.background(wheel)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                selected && ramp != null -> Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = ramp.on(dark),
                    modifier = Modifier.size(18.dp),
                )
                ramp == null -> Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Shared chrome of an accent swatch: a 36dp disc over its name, one selectable radio target.
 *
 * The whole column is the target — at least 48dp each way, so a swatch is as easy to hit as any
 * other control — and it speaks as one radio button: the name (or [description]), and whether it
 * is the one in force.
 */
@Composable
private fun SwatchFrame(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = label,
    disc: @Composable () -> Unit,
) {
    val colors = DsTheme.colors
    Column(
        modifier = modifier
            .heightIn(min = DsSpacing.touchTarget)
            .widthIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.row)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = DsSpacing.tiny),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(DsShapes.pillFull)
                .border(if (selected) 2.dp else 1.dp, if (selected) colors.labelPrimary else colors.borderL2, DsShapes.pillFull),
        ) { disc() }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = DsType.caption11,
            color = if (selected) colors.labelPrimary else colors.labelTertiary,
            maxLines = 1,
        )
    }
}

private fun accentLabelRes(palette: AccentPalette): StringResource = when (palette) {
    AccentPalette.Ink -> Res.string.appearance_accent_ink
    AccentPalette.Violet -> Res.string.appearance_accent_violet
    AccentPalette.Teal -> Res.string.appearance_accent_teal
    AccentPalette.Green -> Res.string.appearance_accent_green
    AccentPalette.Amber -> Res.string.appearance_accent_amber
    AccentPalette.Rose -> Res.string.appearance_accent_rose
    AccentPalette.Mono -> Res.string.appearance_accent_mono
}



