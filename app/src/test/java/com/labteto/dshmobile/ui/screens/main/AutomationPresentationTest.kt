package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.AutomationTask
import java.io.File
import java.time.*
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class AutomationPresentationTest {
    private val task = AutomationTask("rule", "at", "Title", "Prompt", "2026-10-07T16:16:48.125Z", "chat")
    private fun formatter(language: String = "en", zone: String = "UTC"): ScheduleFormatter {
        val suffix = if (language == "en") "" else "-$language"
        val root = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
        val docs = listOf("strings.xml", "strings_ux_a.xml").map { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, "values$suffix/$it")) }
        fun name(type: Class<*>, id: Int) = type.fields.first { it.getInt(null) == id }.name
        fun element(tag: String, name: String): Element = docs.asSequence().flatMap { doc ->
            val elements = doc.getElementsByTagName(tag)
            (0 until elements.length).asSequence().map { elements.item(it) as Element }
        }.first { it.getAttribute("name") == name }
        return ScheduleFormatter(Locale.forLanguageTag(language), ZoneId.of(zone), { id, args ->
            String.format(Locale.forLanguageTag(language), element("string", name(R.string::class.java, id)).textContent, *args.toTypedArray())
        }, { id, count, number ->
            val items = element("plurals", name(R.plurals::class.java, id)).getElementsByTagName("item")
            val forms = (0 until items.length).map { items.item(it) as Element }.associate { it.getAttribute("quantity") to it.textContent }
            String.format(forms[if (language == "en" && count == 1) "one" else "other"] ?: forms.getValue("other"), number)
        })
    }
    @Test fun `all schedule kinds describe stored rule instead of timestamp`() {
        val f = formatter()
        assertFalse(f.frequency(task).contains("2026"))
        assertEquals(f.frequency(task), f.frequency(task.copy(kind = "after", afterSeconds = 60)))
        assertEquals("Every 1 hour", f.frequency(task.copy(kind = "every", everySeconds = 3600)))
        assertTrue(f.frequency(task.copy(kind = "daily", time = "09:15:02.123", timeZone = "Asia/Bangkok")).contains("09:15:02.123"))
        assertTrue(f.frequency(task.copy(kind = "weekly", time = "09:00", timeZone = "UTC", weekdays = listOf(1, 3))).contains("Mon, Wed"))
        assertTrue(f.frequency(task.copy(kind = "cron", expression = "0 9 * * *", timeZone = "UTC")).contains("Daily at 09:00"))
        assertTrue(f.frequency(task.copy(kind = "cron", expression = "0 9 1,15 * *", timeZone = "UTC")).contains("0 9 1,15 * *"))
    }
    @Test fun `exact intervals do not round to minutes`() {
        assertEquals("Every 61 seconds", formatter().frequency(task.copy(kind = "every", everySeconds = 61)))
        assertEquals("Every 2 minutes", formatter().frequency(task.copy(kind = "every", everySeconds = 120)))
        assertEquals("Every 1 day", formatter().frequency(task.copy(kind = "every", everySeconds = 86400)))
    }
    @Test fun `rule stays in stored zone while next run uses device zone`() {
        val daily = task.copy(kind = "daily", time = "09:00", timeZone = "Asia/Bangkok")
        val utc = formatter(); val bangkok = formatter(zone = "Asia/Bangkok")
        assertEquals(utc.frequency(daily), bangkok.frequency(daily))
        assertTrue(utc.frequency(daily).contains("UTC+07:00"))
        assertNotEquals(utc.absolute(task.scheduledAt), bangkok.absolute(task.scheduledAt))
        assertTrue(bangkok.nextRun(task.scheduledAt, Instant.parse("2026-10-07T16:15:48.125Z")).contains("in 1 minute"))
        assertTrue(utc.nextRun(task.scheduledAt, Instant.parse("2026-10-07T16:18:48.125Z")).contains("2 minutes overdue"))
        assertTrue(utc.nextRun(task.scheduledAt, Instant.parse(task.scheduledAt)).contains("due now"))
    }
    @Test fun `inactive and completed tasks never show overdue next runs`() {
        val now = Instant.parse("2026-10-08T16:16:48Z")
        for (language in listOf("en", "ar")) {
            val f = formatter(language)
            assertEquals(f.nextRun(task.scheduledAt, now), f.runSummary(task, now))
            // 0.2.2's vocabulary is paused|ended; inactive/completed cover older hosts and records.
            for (status in listOf("inactive", "completed", "paused", "ended")) {
                val ended = task.copy(status = status)
                assertNull(f.runSummary(ended, now))
                val delivery = com.labteto.dshmobile.core.wire.dto.DeliveryRecord(task.scheduledAt, "2026-10-07T16:17:00Z", "message")
                val summary = f.runSummary(ended.copy(lastDelivery = delivery), now)!!
                assertTrue(summary.contains(f.absolute(delivery.deliveredAt)))
                assertNotEquals(f.nextRun(task.scheduledAt, now), summary)
                if (language == "en") assertTrue(summary.startsWith("Last run:"))
            }
        }
    }

    @Test fun `English and Thai resource plurals match one and many`() {
        assertEquals("1 second", formatter().amount(1, ScheduleUnit.SECOND))
        assertEquals("2 seconds", formatter().amount(2, ScheduleUnit.SECOND))
        assertEquals("1 วินาที", formatter("th").amount(1, ScheduleUnit.SECOND))
        assertEquals("2 วินาที", formatter("th").amount(2, ScheduleUnit.SECOND))
        assertEquals("ทุก 2 นาที", formatter("th").frequency(task.copy(kind = "every", everySeconds = 120)))
    }
    @Test fun `switching kinds retains independent drafts and stored precision`() {
        val initial = TimingEditorDraft.from(task, ZoneId.of("Asia/Bangkok"))
        assertEquals(task.scheduledAt, initial.value())
        val changed = initial.select("every").edit { it.copy(amount = "61", unit = ScheduleUnit.SECOND) }
            .select("daily").edit { it.copy(time = "08:09:10.125", zone = "Asia/Tokyo") }
        assertEquals("61", changed.select("every").value())
        assertEquals("08:09:10.125", changed.select("daily").value())
        assertEquals(task.scheduledAt, changed.select("at").value())
        assertEquals("Asia/Tokyo", changed.select("daily").current.zone)
        assertEquals("Asia/Bangkok", changed.select("weekly").current.zone)
    }
    @Test fun `save validity covers interval weekdays clock zone and cron`() {
        val initial = TimingEditorDraft.from(task)
        assertTrue(initial.valid)
        assertFalse(initial.select("every").edit { it.copy(amount = "59", unit = ScheduleUnit.SECOND) }.valid)
        assertFalse(initial.select("every").edit { it.copy(amount = "1.5") }.valid)
        assertTrue(initial.select("every").edit { it.copy(amount = "60", unit = ScheduleUnit.SECOND) }.valid)
        assertFalse(initial.select("weekly").edit { it.copy(weekdays = emptySet()) }.valid)
        assertFalse(initial.select("daily").edit { it.copy(time = "25:00") }.valid)
        assertFalse(initial.select("daily").edit { it.copy(zone = "invalid-zone") }.valid)
        assertFalse(initial.select("cron").edit { it.copy(expression = "70 * * * *") }.valid)
        assertFalse(initial.select("cron").edit { it.copy(expression = "* * * * */0") }.valid)
        assertTrue(initial.select("cron").edit { it.copy(expression = "*/15 9-17 * JAN,MAR MON-FRI") }.valid)
    }
    @Test fun `DST gaps cannot silently shift selected wall clock`() {
        assertFalse(TimingEditorDraft.from(task).edit { it.copy(date = LocalDate.of(2026, 3, 8), time = "02:30", zone = "America/New_York") }.valid)
    }
    @Test fun `rrule frequency shows the stored rule instead of unavailable`() {
        val f = formatter()
        val rule = "DTSTART;TZID=Asia/Bangkok:20260101T090000\nRRULE:FREQ=HOURLY;INTERVAL=1"
        val frequency = f.frequency(task.copy(kind = "rrule", rrule = rule))
        assertTrue(frequency.contains("DTSTART"))
        assertTrue(frequency.contains("FREQ=HOURLY"))
        assertFalse(frequency.contains("Unavailable"))
        assertEquals("Unavailable", f.frequency(task.copy(kind = "rrule", rrule = null)))
    }
    @Test fun `rrule task opens a read-only timing draft instead of crashing`() {
        val rule = "DTSTART;TZID=Asia/Bangkok:20260101T090000\nRRULE:FREQ=HOURLY;INTERVAL=1"
        val rrule = task.copy(kind = "rrule", rrule = rule)
        // `from` used to seed a draft keyed by the stored kind; `drafts.getValue("rrule")` threw the
        // moment the editor rendered. It now seeds the displayed "at" draft and marks it read-only.
        val draft = TimingEditorDraft.from(rrule, ZoneId.of("Asia/Bangkok"))
        assertFalse(draft.timingEditable)
        assertEquals("at", draft.kind)
        assertEquals(rrule.scheduledAt, draft.value())
        assertTrue(draft.valid)
        // A kind the app does not know (a later harness addition) falls back the same way.
        val unknown = TimingEditorDraft.from(rrule.copy(kind = "yearly"), ZoneId.of("Asia/Bangkok"))
        assertFalse(unknown.timingEditable)
        assertEquals("at", unknown.kind)
        assertEquals(rrule.scheduledAt, unknown.value())
    }
}
