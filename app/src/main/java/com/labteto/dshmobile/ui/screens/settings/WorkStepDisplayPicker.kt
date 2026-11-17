package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.WorkStepDisplay
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The four work-step levels as a list, each with a line saying what it changes.
 *
 * A list rather than a segmented control: the descriptions are the point (the levels differ in how
 * the transcript is folded, which a four-word label cannot say), and a row of four labels does not
 * fit a phone in every language the app ships.
 */
@Composable
internal fun WorkStepDisplayPicker(selected: WorkStepDisplay, onSelect: (WorkStepDisplay) -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Text(
        stringResource(R.string.settings_work_steps),
        style = DsType.label12,
        color = colors.labelTertiary,
        modifier = Modifier.padding(top = DsSpacing.small),
    )
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        WorkStepDisplay.entries.forEach { level ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DsSpacing.touchTarget)
                    .selectable(selected = level == selected, role = Role.RadioButton) {
                        haptics.selection()
                        onSelect(level)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                RadioButton(
                    selected = level == selected,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = colors.accent,
                        unselectedColor = colors.labelTertiary,
                    ),
                )
                Column(Modifier.weight(1f).padding(vertical = DsSpacing.small)) {
                    Text(stringResource(levelTitle(level)), style = DsType.std14, color = colors.labelPrimary)
                    Text(stringResource(levelHint(level)), style = DsType.caption11, color = colors.labelTertiary)
                }
            }
        }
    }
    Text(
        stringResource(R.string.settings_work_steps_hint),
        style = DsType.caption11,
        color = colors.labelTertiary,
    )
}

private fun levelTitle(level: WorkStepDisplay): Int = when (level) {
    WorkStepDisplay.Compact -> R.string.work_steps_compact
    WorkStepDisplay.Standard -> R.string.work_steps_standard
    WorkStepDisplay.Detailed -> R.string.work_steps_detailed
    WorkStepDisplay.Verbose -> R.string.work_steps_verbose
}

private fun levelHint(level: WorkStepDisplay): Int = when (level) {
    WorkStepDisplay.Compact -> R.string.work_steps_compact_hint
    WorkStepDisplay.Standard -> R.string.work_steps_standard_hint
    WorkStepDisplay.Detailed -> R.string.work_steps_detailed_hint
    WorkStepDisplay.Verbose -> R.string.work_steps_verbose_hint
}
