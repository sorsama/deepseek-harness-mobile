package com.labteto.dshmobile.core.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What typing into each kind of field does, checked before anything is sent. */
class SettingsDraftsTest {

    private val count get() = field("web-deepseek-api", "count") // 1 to 50, whole
    private val loop get() = field("agent-loop", "maxParallelToolCalls") // at least 1, whole
    private val depth get() = field("subagent", "maxDepth") // at least 0, whole
    private val timeout get() = field("pwsh-sandbox", "timeoutMs") // a bare number
    private val endpoint get() = field("web-deepseek-api", "baseURL")
    private val ratio get() = settingsFields(syntheticRow()).single { it.key == "ratio" }
    private val label get() = settingsFields(syntheticRow()).single { it.key == "label" }

    private fun invalid(problem: FieldProblem) = DraftResult.Invalid(problem)

    @Test
    fun `a number in range is written as a number`() {
        assertEquals(DraftResult.Write(JsonPrimitive(12)), count.draftFromText("12"))
        assertEquals(DraftResult.Write(JsonPrimitive(50)), count.draftFromText(" 50 "))
        assertEquals(DraftResult.Write(JsonPrimitive(0)), depth.draftFromText("0"))
    }

    @Test
    fun `an empty box means use the default`() {
        assertEquals(DraftResult.Reset, count.draftFromText(""))
        assertEquals(DraftResult.Reset, count.draftFromText("   "))
        assertEquals(DraftResult.Reset, endpoint.draftFromText(""))
    }

    @Test
    fun `a required field with no default refuses an empty box`() {
        val required = settingsFields(syntheticRow()).single { it.key == "label" }.copy(required = true, baseValue = null)
        assertEquals(invalid(FieldProblem.Required), required.draftFromText(" "))
    }

    @Test
    fun `text that is not a number is refused`() {
        assertEquals(invalid(FieldProblem.NotANumber), count.draftFromText("many"))
        assertEquals(invalid(FieldProblem.NotANumber), count.draftFromText("1,5"))
        assertEquals(invalid(FieldProblem.NotANumber), count.draftFromText("1e3"))
        assertEquals(invalid(FieldProblem.NotANumber), count.draftFromText("--1"))
        assertEquals(invalid(FieldProblem.NotANumber), count.draftFromText("."))
    }

    @Test
    fun `a whole number field refuses a fraction`() {
        assertEquals(invalid(FieldProblem.NotWhole), count.draftFromText("2.5"))
        assertEquals(invalid(FieldProblem.NotWhole), loop.draftFromText("0.5"))
        // But 2.0 is two.
        assertEquals(DraftResult.Write(JsonPrimitive(2)), count.draftFromText("2.0"))
    }

    @Test
    fun `the schema range is enforced on both sides`() {
        assertEquals(invalid(FieldProblem.TooSmall(1.0)), count.draftFromText("0"))
        assertEquals(invalid(FieldProblem.TooLarge(50.0)), count.draftFromText("51"))
        assertEquals(invalid(FieldProblem.TooSmall(1.0)), loop.draftFromText("0"))
        assertEquals(invalid(FieldProblem.TooSmall(0.0)), depth.draftFromText("-1"))
    }

    @Test
    fun `no number may exceed what the harness can store`() {
        assertEquals(invalid(FieldProblem.TooLarge(9007199254740991.0)), loop.draftFromText("9007199254740993"))
        assertEquals(DraftResult.Write(JsonPrimitive(9007199254740991)), loop.draftFromText("9007199254740991"))
    }

    @Test
    fun `the page can add rules the schema leaves out`() {
        // The shell timeout is a bare number in the schema; a page that knows better says whole and at least one.
        val rules = FieldTraits(whole = true, min = 1.0)
        assertEquals(DraftResult.Write(JsonPrimitive(30000)), timeout.draftFromText("30000", rules))
        assertEquals(invalid(FieldProblem.TooSmall(1.0)), timeout.draftFromText("0", rules))
        assertEquals(invalid(FieldProblem.NotWhole), timeout.draftFromText("1.5", rules))
        // Without the rules the harness would take either.
        assertEquals(DraftResult.Write(JsonPrimitive(1.5)), timeout.draftFromText("1.5"))
        // A stricter bound from the page wins over a looser one from the schema.
        assertEquals(invalid(FieldProblem.TooLarge(20.0)), count.draftFromText("30", FieldTraits(max = 20.0)))
    }

    @Test
    fun `digits from other scripts and spacing are accepted as the number they are`() {
        assertEquals(DraftResult.Write(JsonPrimitive(12)), count.draftFromText("١٢"))
        assertEquals(DraftResult.Write(JsonPrimitive(30)), count.draftFromText("३०"))
        assertEquals(DraftResult.Write(JsonPrimitive(120000)), timeout.draftFromText("120 000"))
        assertEquals(DraftResult.Write(JsonPrimitive(120000)), timeout.draftFromText("120 000"))
        assertEquals(DraftResult.Write(JsonPrimitive(1.5)), timeout.draftFromText("1٫5"))
    }

    @Test
    fun `a step the schema declares must be met`() {
        assertEquals(DraftResult.Write(JsonPrimitive(0.5)), ratio.draftFromText("0.5"))
        assertEquals(DraftResult.Write(JsonPrimitive(1)), ratio.draftFromText("1"))
        assertEquals(invalid(FieldProblem.NotMultiple(0.5)), ratio.draftFromText("0.3"))
        assertEquals(invalid(FieldProblem.TooLarge(1.0)), ratio.draftFromText("1.5"))
    }

    @Test
    fun `an address must be an http address when the page says it is one`() {
        val url = FieldTraits(url = true)
        assertEquals(DraftResult.Write(JsonPrimitive("https://example.com/v1")), endpoint.draftFromText(" https://example.com/v1 ", url))
        assertEquals(DraftResult.Write(JsonPrimitive("http://10.0.0.2:8080")), endpoint.draftFromText("http://10.0.0.2:8080", url))
        assertEquals(invalid(FieldProblem.NotAnUrl), endpoint.draftFromText("example.com", url))
        assertEquals(invalid(FieldProblem.NotAnUrl), endpoint.draftFromText("ftp://example.com", url))
        assertEquals(invalid(FieldProblem.NotAnUrl), endpoint.draftFromText("https://", url))
        assertEquals(invalid(FieldProblem.NotAnUrl), endpoint.draftFromText("https://a b.com", url))
        // Not an address field: any text will do.
        assertEquals(DraftResult.Write(JsonPrimitive("example.com")), endpoint.draftFromText("example.com"))
    }

    @Test
    fun `text length and pattern come from the schema`() {
        assertEquals(invalid(FieldProblem.TooShort(2)), label.draftFromText("a"))
        assertEquals(invalid(FieldProblem.TooLong(8)), label.draftFromText("abcdefghi"))
        assertEquals(DraftResult.Write(JsonPrimitive("ab")), label.draftFromText("  ab "))
        val slug = settingsFields(syntheticRow()).single { it.key == "slug" }
        assertEquals(DraftResult.Write(JsonPrimitive("abc")), slug.draftFromText("abc"))
        assertEquals(invalid(FieldProblem.WrongFormat), slug.draftFromText("ABC"))
    }

    @Test
    fun `a toggle has no text to type`() {
        val toggle = field("session-log-deepseek", "enabled")
        assertEquals(invalid(FieldProblem.WrongFormat), toggle.draftFromText("true"))
    }

    @Test
    fun `a key is one printable word`() {
        assertEquals(DraftResult.Write(JsonPrimitive("sk-abc123")), secretDraft("  sk-abc123 "))
        assertEquals(invalid(FieldProblem.Required), secretDraft("   "))
        assertEquals(invalid(FieldProblem.Required), secretDraft(""))
        assertEquals(invalid(FieldProblem.KeyFormat), secretDraft("sk abc"))
        assertEquals(invalid(FieldProblem.KeyFormat), secretDraft("DEEPSEEK_API_KEY=sk-abc"))
        assertEquals(invalid(FieldProblem.KeyFormat), secretDraft("\"sk-abc\""))
        assertEquals(invalid(FieldProblem.KeyFormat), secretDraft("sk-ключ"))
    }

    @Test
    fun `a secret field draws on the same rule`() {
        val token = settingsFields(syntheticRow()).single { it.key == "token" }
        assertEquals(DraftResult.Write(JsonPrimitive("abc")), token.draftFromText("abc"))
        assertEquals(invalid(FieldProblem.KeyFormat), token.draftFromText("a b"))
    }

    @Test
    fun `a choice must be one the field offers`() {
        val mode = settingsFields(syntheticRow()).single { it.key == "mode" }
        assertEquals(DraftResult.Write(JsonPrimitive("slow")), mode.draftFromChoice(JsonPrimitive("slow")))
        assertEquals(invalid(FieldProblem.NotAnOption), mode.draftFromChoice(JsonPrimitive("medium")))
        assertEquals(invalid(FieldProblem.NotAnOption), label.draftFromChoice(JsonPrimitive("a")))
    }

    @Test
    fun `a list drops blanks and holds the count`() {
        val tags = settingsFields(syntheticRow()).single { it.key == "tags" }
        assertEquals(
            DraftResult.Write(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))),
            tags.draftFromList(listOf(" a ", "", "b")),
        )
        assertEquals(DraftResult.Write(JsonArray(emptyList())), tags.draftFromList(emptyList()))
        assertEquals(invalid(FieldProblem.TooManyItems(3)), tags.draftFromList(listOf("a", "b", "c", "d")))
    }

    @Test
    fun `a list of choices must each be offered`() {
        val base = settingsFields(syntheticRow()).single { it.key == "mode" }
        val kind = base.kind as FieldKind.Choice
        val list = base.copy(kind = FieldKind.ChoiceList(kind.options, minItems = 1, maxItems = 2))
        assertEquals(DraftResult.Write(JsonArray(listOf(JsonPrimitive("fast")))), list.draftFromSelection(listOf(JsonPrimitive("fast"))))
        assertEquals(invalid(FieldProblem.TooFewItems(1)), list.draftFromSelection(emptyList()))
        assertEquals(invalid(FieldProblem.NotAnOption), list.draftFromSelection(listOf(JsonPrimitive("nope"))))
        assertEquals(invalid(FieldProblem.TooManyItems(2)), list.draftFromSelection(listOf(JsonPrimitive("fast"), JsonPrimitive("slow"), JsonPrimitive("fast"))))
    }

    @Test
    fun `a stored value is shown the way it is typed`() {
        assertEquals("120000", fieldText(JsonPrimitive(120000)))
        assertEquals("1.5", fieldText(JsonPrimitive(1.5)))
        assertEquals("https://x", fieldText(JsonPrimitive("https://x")))
        assertEquals("true", fieldText(JsonPrimitive(true)))
        assertEquals("", fieldText(null))
        assertEquals("", fieldText(JsonNull))
        // A number the harness wrote with an exponent still reads as a number to type over.
        assertEquals("120000", fieldText(kotlinx.serialization.json.Json.parseToJsonElement("1.2e5")))
    }

    @Test
    fun `equal values compare equal however they were written`() {
        assertTrue(valuesEquivalent(JsonPrimitive(4), JsonPrimitive(4.0)))
        assertTrue(valuesEquivalent(null, JsonNull))
        assertTrue(valuesEquivalent(JsonPrimitive("a"), JsonPrimitive("a")))
        assertFalse(valuesEquivalent(JsonPrimitive("4"), JsonPrimitive(4)))
        assertFalse(valuesEquivalent(JsonPrimitive(4), null))
        assertFalse(valuesEquivalent(JsonPrimitive(true), JsonPrimitive(false)))
    }
}
