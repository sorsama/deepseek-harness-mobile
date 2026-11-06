package com.labteto.dshmobile.ui.screens.main

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.*
import com.labteto.dshmobile.ui.theme.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

@Composable
internal fun AutomationTimingFields(draft: TimingEditorDraft, onChange: (TimingEditorDraft) -> Unit, enabled: Boolean) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val formatter = scheduleFormatter()
    val haptics = rememberDsHaptics()
    val colors = DsTheme.colors
    val current = draft.current
    var zonesOpen by remember { mutableStateOf(false) }
    var zoneSearch by remember { mutableStateOf("") }
    val zones = remember(locale) {
        (listOf(ZoneId.systemDefault().id, current.zone, "UTC") + ZoneId.getAvailableZoneIds().sorted())
            .distinct().map { it to formatter.zone(it) }
    }
    SectionLabel(stringResource(R.string.harness_change_timing))
    DsCard {
        listOf(
            "at" to R.string.harness_at,
            "every" to R.string.harness_every,
            "daily" to R.string.harness_daily,
            "weekly" to R.string.harness_weekly,
            "cron" to R.string.ux_a_cron_advanced,
        ).forEach { (kind, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DsSpacing.touchTarget)
                    .selectable(
                        draft.kind == kind,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = {
                            haptics.selection()
                            onChange(draft.select(kind))
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    draft.kind == kind,
                    onClick = null,
                    enabled = enabled,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = colors.accent,
                        unselectedColor = colors.labelTertiary,
                    ),
                )
                Text(stringResource(label), style = DsType.std14, color = colors.labelSecondary)
            }
        }
    }
    if (draft.kind == "at") {
        SectionLabel(stringResource(R.string.ux_a_date))
        FeatureAction(current.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)), enabled) {
            DatePickerDialog(context, { _, year, month, day ->
                onChange(draft.edit { it.copy(date = LocalDate.of(year, month + 1, day)) })
            }, current.date.year, current.date.monthValue - 1, current.date.dayOfMonth).show()
        }
    }
    if (draft.kind in setOf("at", "daily", "weekly")) {
        SectionLabel(stringResource(R.string.ux_a_time))
        FeatureAction(current.time, enabled) {
            val time = runCatching { LocalTime.parse(current.time) }.getOrDefault(LocalTime.of(9, 0))
            TimePickerDialog(context, { _, hour, minute ->
                onChange(draft.edit { it.copy(time = time.withHour(hour).withMinute(minute).toString()) })
            }, time.hour, time.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
        }
        // Keep seconds and milliseconds editable without forcing ISO offsets or discarding precision.
        FeatureField(current.time, { value -> onChange(draft.edit { it.copy(time = value) }) }, R.string.ux_a_precise_time, enabled, singleLine = true)
        if (runCatching { LocalTime.parse(current.time) }.isFailure) {
            Text(stringResource(R.string.ux_a_time_error), color = colors.error, style = DsType.caption11)
        }
    }
    if (draft.kind == "every") {
        FeatureField(current.amount, { value -> onChange(draft.edit { it.copy(amount = value) }) }, R.string.ux_a_interval_amount, enabled, singleLine = true)
        DsCard {
            ScheduleUnit.entries.forEach { unit ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = DsSpacing.touchTarget)
                        .selectable(current.unit == unit, enabled, Role.RadioButton) {
                            haptics.selection()
                            onChange(draft.edit { it.copy(unit = unit) })
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        current.unit == unit,
                        null,
                        enabled = enabled,
                        colors = RadioButtonDefaults.colors(selectedColor = colors.accent),
                    )
                    Text(formatter.amount(1, unit), style = DsType.std14, color = colors.labelSecondary)
                }
            }
        }
        if (!draft.valid) Text(stringResource(R.string.ux_a_interval_error), color = colors.error, style = DsType.caption11)
    }
    if (draft.kind == "weekly") {
        SectionLabel(stringResource(R.string.harness_weekly))
        DsCard {
            DayOfWeek.entries.forEach { day ->
                ToggleRow(day.getDisplayName(TextStyle.FULL, locale), day.value in current.weekdays, enabled = enabled) {
                    onChange(draft.edit {
                        it.copy(weekdays = if (day.value in it.weekdays) it.weekdays - day.value else it.weekdays + day.value)
                    })
                }
            }
        }
        if (current.weekdays.isEmpty()) Text(stringResource(R.string.ux_a_weekdays_error), color = colors.error, style = DsType.caption11)
    }
    if (draft.kind == "cron") {
        FeatureField(current.expression, { value -> onChange(draft.edit { it.copy(expression = value) }) }, R.string.harness_expression, enabled, singleLine = true)
        Text(
            if (draft.valid) formatter.cronPreview(current.expression) else stringResource(R.string.ux_a_cron_error),
            style = DsType.caption11,
            color = if (draft.valid) colors.labelSecondary else colors.error,
        )
    }
    if (draft.kind != "every") {
        SectionLabel(stringResource(R.string.ux_a_zone))
        DsCard(onClick = if (enabled) {
            { zonesOpen = true }
        } else null) {
            Text(
                formatter.zone(current.zone),
                Modifier.heightIn(min = DsSpacing.touchTarget),
                style = DsType.std14,
                color = colors.labelPrimary,
            )
        }
        if (!draft.valid && draft.kind == "at" && runCatching { LocalTime.parse(current.time) }.isSuccess) {
            Text(stringResource(R.string.ux_a_time_error), color = colors.error, style = DsType.caption11)
        }
    }
    if (zonesOpen) FeaturePage(stringResource(R.string.ux_a_zone), { zonesOpen = false }) {
        FeatureField(zoneSearch, { zoneSearch = it }, R.string.ux_a_zone_search, singleLine = true)
        val matches = zones.filter { (id, label) -> id.contains(zoneSearch, true) || label.contains(zoneSearch, true) }
        if (matches.isEmpty()) EmptyHero(stringResource(R.string.harness_automation_no_matches), null, showPreview = false)
        matches.forEach { (id, label) ->
            DsCard(onClick = {
                onChange(draft.edit { it.copy(zone = id) })
                zonesOpen = false
            }) {
                Text(
                    label,
                    Modifier.heightIn(min = DsSpacing.touchTarget),
                    style = DsType.std14,
                    color = colors.labelPrimary,
                )
            }
        }
    }
}
