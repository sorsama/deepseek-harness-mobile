package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.AutomationTask
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.*

internal enum class ScheduleUnit(val seconds: Long, val plural: Int) {
    DAY(86400, R.plurals.ux_a_days), HOUR(3600, R.plurals.ux_a_hours),
    MINUTE(60, R.plurals.ux_a_minutes), SECOND(1, R.plurals.ux_a_seconds)
}

/** Resources are injected so frequency, precision and zone behavior are testable on the JVM. */
internal class ScheduleFormatter(
    private val locale: Locale,
    private val deviceZone: ZoneId,
    private val text: (Int, List<Any>) -> String,
    private val quantity: (Int, Int, String) -> String,
) {
    private fun t(id: Int, vararg args: Any) = text(id, args.toList())
    fun amount(value: Long, unit: ScheduleUnit): String = quantity(unit.plural, value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        java.text.NumberFormat.getIntegerInstance(locale).format(value))
    fun zone(id: String, now: Instant = Instant.now()): String = runCatching {
        val zone = ZoneId.of(id)
        "UTC${zone.rules.getOffset(now).id.replace("Z", "+00:00")} · ${zone.getDisplayName(TextStyle.FULL, locale)}"
    }.getOrDefault(id)
    fun absolute(iso: String): String = runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
            .format(Instant.parse(iso).atZone(deviceZone))
    }.getOrElse { t(R.string.common_unavailable) }
    fun frequency(task: AutomationTask): String = when (task.kind) {
        "at", "after" -> t(R.string.harness_at)
        "every" -> {
            val seconds = task.everySeconds ?: 0
            val unit = ScheduleUnit.entries.first { seconds % it.seconds == 0L }
            t(R.string.ux_a_every, amount(seconds / unit.seconds, unit))
        }
        "daily" -> t(R.string.ux_a_daily, task.time.orEmpty(), zone(task.timeZone ?: "UTC"))
        "weekly" -> t(R.string.ux_a_weekly, task.weekdays.orEmpty().joinToString(", ") {
            DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale)
        }, task.time.orEmpty(), zone(task.timeZone ?: "UTC"))
        "cron" -> t(R.string.ux_a_cron, cronPreview(task.expression.orEmpty()), zone(task.timeZone ?: "UTC"))
        // Harness 0.2.2 makes every model-created task an RFC 5545 rule; the rule is shown as stored
        // (the app has no RRULE editor), one line so a list row keeps its single-line readout.
        "rrule" -> task.rrule?.trim()?.lineSequence()?.joinToString(" · ") { it.trim() }
            ?: t(R.string.common_unavailable)
        else -> t(R.string.common_unavailable)
    }
    fun cronPreview(expression: String): String {
        val fields = expression.trim().split(Regex("\\s+"))
        if (fields.size == 5 && fields.drop(2).all { it == "*" }) {
            val minute = fields[0].toIntOrNull(); val hour = fields[1].toIntOrNull()
            if (minute in 0..59 && hour in 0..23) return t(R.string.ux_a_daily_clock, "%02d:%02d".format(hour, minute))
            if (fields[1] == "*" && fields[0].startsWith("*/")) fields[0].drop(2).toLongOrNull()?.let {
                if (it in 1..59) return t(R.string.ux_a_every, amount(it, ScheduleUnit.MINUTE))
            }
        }
        return t(R.string.ux_a_cron_expression, expression)
    }
    fun runSummary(task: AutomationTask, now: Instant = Instant.now()): String? =
        if (task.status == "active") nextRun(task.scheduledAt, now)
        else task.lastDelivery?.let { t(R.string.ux_a_last_run, absolute(it.deliveredAt)) }

    /**
     * Just the distance to the next run — "in 5h", "due now", "2 minutes overdue".
     *
     * The list row shows the absolute time elsewhere; this is the compact readout beside the
     * status dot, so it never repeats the full date.
     */
    fun nextRelative(iso: String, now: Instant = Instant.now()): String {
        val target = runCatching { Instant.parse(iso) }.getOrNull() ?: return absolute(iso)
        val seconds = Duration.between(now, target).toMillis() / 1000.0
        val unit = ScheduleUnit.entries.firstOrNull { abs(seconds) >= it.seconds } ?: ScheduleUnit.SECOND
        val count = max(1L, (if (seconds > 0) ceil(abs(seconds) / unit.seconds) else floor(abs(seconds) / unit.seconds)).toLong())
        return if (seconds == 0.0) t(R.string.ux_a_due_now) else t(
            if (seconds > 0) R.string.ux_a_in else R.string.ux_a_overdue, amount(count, unit))
    }

    fun nextRun(iso: String, now: Instant = Instant.now()): String {
        val target = runCatching { Instant.parse(iso) }.getOrNull() ?: return absolute(iso)
        val seconds = Duration.between(now, target).toMillis() / 1000.0
        val unit = ScheduleUnit.entries.firstOrNull { abs(seconds) >= it.seconds } ?: ScheduleUnit.SECOND
        val count = max(1L, (if (seconds > 0) ceil(abs(seconds) / unit.seconds) else floor(abs(seconds) / unit.seconds)).toLong())
        val relative = if (seconds == 0.0) t(R.string.ux_a_due_now) else t(
            if (seconds > 0) R.string.ux_a_in else R.string.ux_a_overdue, amount(count, unit))
        return t(R.string.ux_a_next_run, absolute(iso), relative)
    }
}

internal data class TimingKindDraft(
    val date: LocalDate, val time: String, val zone: String,
    val amount: String = "1", val unit: ScheduleUnit = ScheduleUnit.HOUR,
    val weekdays: Set<Int> = setOf(1), val expression: String = "0 9 * * *",
    val preferredOffset: ZoneOffset? = null,
)

/**
 * Each kind owns a draft; selecting a different kind never reinterprets another kind's text.
 *
 * [timingEditable] is false for kinds the app can display but not author. Harness 0.2.2 added
 * `kind:"rrule"`; an RRULE has no draft (editing it would need the new `patch.timing = {kind,
 * rrule}` shape), so the editor hides the timing controls instead of throwing when it renders.
 */
internal data class TimingEditorDraft(
    val kind: String,
    val drafts: Map<String, TimingKindDraft>,
    val timingEditable: Boolean = true,
) {
    val current get() = drafts.getValue(kind)
    fun select(kind: String) = copy(kind = kind)
    fun edit(change: (TimingKindDraft) -> TimingKindDraft) = copy(drafts = drafts + (kind to change(current)))
    fun value(): String = when (kind) {
        "at" -> {
            val local = current.date.atTime(LocalTime.parse(current.time))
            val zone = ZoneId.of(current.zone)
            require(zone.rules.getValidOffsets(local).isNotEmpty()) // Reject a DST gap rather than silently moving the clock.
            ZonedDateTime.ofLocal(local, zone, current.preferredOffset).toInstant().toString()
        }
        "every" -> {
            require(current.amount.matches(Regex("[0-9]+")))
            Math.multiplyExact(current.amount.toLong(), current.unit.seconds).toString()
        }
        "cron" -> current.expression
        else -> current.time
    }
    fun change() = com.labteto.dshmobile.core.wire.automationTiming(kind, value(), current.zone, current.weekdays.sorted().joinToString(","))
    val valid get() = runCatching { change(); if (kind == "cron") require(validCron(current.expression)) }.isSuccess
    companion object {
        /** Kinds the timing editor can author; `after` is a creation-only rule shown as a one-off. */
        private val EDITABLE_KINDS = setOf("at", "every", "daily", "weekly", "cron")

        fun from(task: AutomationTask, deviceZone: ZoneId = ZoneId.systemDefault()): TimingEditorDraft {
            val zone = task.timeZone ?: deviceZone.id
            val at = Instant.parse(task.scheduledAt).atZone(ZoneId.of(zone))
            val seconds = task.everySeconds ?: 3600L
            val unit = ScheduleUnit.entries.first { seconds % it.seconds == 0L }
            val seed = TimingKindDraft(at.toLocalDate(), task.time ?: at.toLocalTime().toString(), zone,
                (seconds / unit.seconds).toString(), unit, task.weekdays?.toSet() ?: setOf(at.dayOfWeek.value), task.expression ?: "0 9 * * *", at.offset)
            // `rrule` (harness 0.2.2), and any future kind the app does not know, has no seed in
            // `drafts`; `current`'s getValue would throw the moment the editor rendered. Seed the
            // "at" draft the summary/next-run already displays and mark the timing read-only.
            val kind = when (task.kind) {
                "after" -> "at"
                else -> task.kind.takeIf { it in EDITABLE_KINDS } ?: "at"
            }
            return TimingEditorDraft(kind, listOf("at", "every", "daily", "weekly", "cron").associateWith { seed },
                timingEditable = task.kind in EDITABLE_KINDS)
        }
    }
}

/** Five-field numeric cron with lists, ranges and steps; named months/weekdays remain supported. */
internal fun validCron(expression: String): Boolean {
    val fields = expression.trim().uppercase(Locale.ROOT).split(Regex("\\s+"))
    if (fields.size != 5) return false
    val bounds = listOf(0..59, 0..23, 1..31, 1..12, 0..7)
    return fields.withIndex().all { (index, field) ->
        fun number(value: String): Int? = value.toIntOrNull() ?: when (index) {
            3 -> listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC").indexOf(value).takeIf { it >= 0 }?.plus(1)
            4 -> listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT").indexOf(value).takeIf { it >= 0 }
            else -> null
        }
        field.split(',').all { part ->
            val step = part.split('/')
            if (step.size > 2 || (step.size == 2 && (step[1].toIntOrNull() ?: 0) <= 0)) false
            else if (step[0] == "*") true
            else {
                val range = step[0].split('-'); val values = range.map { number(it) }
                range.size in 1..2 && values.all { it != null && it in bounds[index] } && (values.size == 1 || values[0]!! <= values[1]!!)
            }
        }
    }
}
