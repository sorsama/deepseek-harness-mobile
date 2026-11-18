package com.labteto.dshmobile.core.settings

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Number text on the values where the JVM and Kotlin/Native are known to differ: whole doubles,
 * 2^53 and its neighbours, exponents, tiny fractions, negative zero, and digits from other scripts.
 * Every expectation here is a rule of the app, not whatever one platform happens to print.
 *
 * Non-ASCII digits are written as escapes so the file reads the same under any editor encoding:
 * `١٢` is Arabic-Indic 12, `۱۲` Persian 12, `１２` full-width 12.
 */
@OptIn(ExperimentalSerializationApi::class)
class SettingsNumbersTest {

    private val count get() = field("web-deepseek-api", "count") // 1 to 50, whole
    private val loop get() = field("agent-loop", "maxParallelToolCalls") // at least 1
    private val timeout get() = field("pwsh-sandbox", "timeoutMs") // a bare number

    private fun invalid(problem: FieldProblem) = DraftResult.Invalid(problem)

    private fun written(result: DraftResult): String =
        ((result as DraftResult.Write).value as JsonPrimitive).content

    // ---------------------------------------------------------------------------- reading digits

    @Test
    fun `a whole number written as a double is read as that whole number`() {
        assertEquals(DecimalText(false, "3", ""), parseDecimal("3.0"))
        assertEquals(DecimalText(false, "3", ""), parseDecimal("3.000"))
        assertEquals(DecimalText(false, "7", ""), parseDecimal("007"))
        assertEquals(DecimalText(false, "4", ""), parseDecimal("+4"))
    }

    @Test
    fun `negative zero is zero`() {
        assertEquals(DecimalText(false, "0", ""), parseDecimal("-0"))
        assertEquals(DecimalText(false, "0", ""), parseDecimal("-0.0"))
        assertEquals(DecimalText(false, "0", ""), parseDecimal("-0e5", allowExponent = true))
        assertEquals("0", parseDecimal("-0.00")?.plain())
    }

    @Test
    fun `only digits a point and a sign are a number unless an exponent is allowed`() {
        for (text in listOf("", " ", "1e3", "1E3", ".5", "5.", ".", "-", "+", "--1", "1,5", "1 2", "NaN", "Infinity", "0x10", "1_000", "12a")) {
            assertNull(parseDecimal(text), "'$text' must not parse")
        }
        assertEquals(DecimalText(false, "1000", ""), parseDecimal("1e3", allowExponent = true))
        assertEquals(DecimalText(false, "1000", ""), parseDecimal("1E+3", allowExponent = true))
        assertNull(parseDecimal("1e", allowExponent = true))
        assertNull(parseDecimal("1e+", allowExponent = true))
        assertNull(parseDecimal("1e99999", allowExponent = true))
    }

    @Test
    fun `an exponent moves the point on the digits`() {
        assertEquals("1000000000000000000000", parseDecimal("1e21", allowExponent = true)?.plain())
        assertEquals("1000000000000000000000", parseDecimal("1.0E21", allowExponent = true)?.plain())
        assertEquals("1000000000000000000000", parseDecimal("1e+21", allowExponent = true)?.plain())
        assertEquals("0.0000001", parseDecimal("1e-7", allowExponent = true)?.plain())
        assertEquals("0.00000015", parseDecimal("1.5e-7", allowExponent = true)?.plain())
        assertEquals("12.5", parseDecimal("125e-1", allowExponent = true)?.plain())
        assertEquals("150", parseDecimal("1.5e2", allowExponent = true)?.plain())
    }

    @Test
    fun `the safe range is decided on the digits`() {
        fun exceeds(text: String) = parseDecimal(text)!!.exceedsSafeRange
        assertFalse(exceeds("9007199254740991"))
        assertFalse(exceeds("-9007199254740991"))
        assertFalse(exceeds("9007199254740990.0"))
        assertTrue(exceeds("9007199254740992")) // 2^53
        assertTrue(exceeds("9007199254740993")) // 2^53 + 1, which a double cannot hold
        assertTrue(exceeds("9007199254740991.5"))
        assertTrue(exceeds("-9007199254740992"))
        assertTrue(exceeds("10000000000000000"))
        assertTrue(exceeds("1" + "0".repeat(400)))
        assertFalse(exceeds("000000000000000000009007199254740991"))
    }

    @Test
    fun `a whole number in range is an exact long`() {
        assertEquals(9007199254740991L, parseDecimal("9007199254740991")!!.toWholeLongOrNull())
        assertEquals(-9007199254740991L, parseDecimal("-9007199254740991")!!.toWholeLongOrNull())
        assertEquals(30000L, parseDecimal("30000.0")!!.toWholeLongOrNull())
        assertNull(parseDecimal("9007199254740992")!!.toWholeLongOrNull())
        assertNull(parseDecimal("1.5")!!.toWholeLongOrNull())
        assertEquals(9007199254740991.0, parseDecimal("9007199254740991")!!.toDoubleOrNull())
        assertEquals(Double.POSITIVE_INFINITY, parseDecimal("9007199254740993")!!.toDoubleOrNull())
        assertEquals(Double.NEGATIVE_INFINITY, parseDecimal("-1" + "0".repeat(30))!!.toDoubleOrNull())
        assertEquals(0.5, parseDecimal("0.5")!!.toDoubleOrNull())
    }

    // ------------------------------------------------------------------------------- formatting

    @Test
    fun `a number is written without an exponent or a trailing zero`() {
        assertEquals("3", formatNumber(3.0))
        assertEquals("0", formatNumber(0.0))
        assertEquals("0", formatNumber(-0.0))
        assertEquals("-3", formatNumber(-3.0))
        assertEquals("50", formatNumber(50.0))
        assertEquals("0.5", formatNumber(0.5))
        assertEquals("0.1", formatNumber(0.1))
        assertEquals("123456.789", formatNumber(123456.789))
        assertEquals("0.0000001", formatNumber(1e-7))
        assertEquals("0.00000015", formatNumber(1.5e-7))
    }

    @Test
    fun `large numbers are written as their digits`() {
        assertEquals("9007199254740991", formatNumber(9007199254740991.0))
        assertEquals("-9007199254740991", formatNumber(-9007199254740991.0))
        assertEquals("9007199254740992", formatNumber(9007199254740992.0)) // 2^53
        assertEquals("1000000000000000000000", formatNumber(1e21))
    }

    @Test
    fun `what the harness holds is shown as plain digits`() {
        assertEquals("4", fieldText(JsonPrimitive(4.0)))
        assertEquals("4", fieldText(JsonUnquotedLiteral("4.0")))
        assertEquals("9007199254740991", fieldText(JsonPrimitive(9007199254740991)))
        assertEquals("1000000000000000000000", fieldText(JsonUnquotedLiteral("1e21")))
        assertEquals("0.00000015", fieldText(JsonUnquotedLiteral("1.5e-7")))
        assertEquals("0", fieldText(JsonUnquotedLiteral("-0")))
        assertEquals("1.5", fieldText(JsonPrimitive(1.5)))
        // Text stays as it is, even when it looks like a number.
        assertEquals("1e3", fieldText(JsonPrimitive("1e3")))
        assertEquals("007", fieldText(JsonPrimitive("007")))
        assertEquals("true", fieldText(JsonPrimitive(true)))
        assertEquals("", fieldText(JsonNull))
        assertEquals("", fieldText(null))
    }

    @Test
    fun `two numbers are the same when their digits are`() {
        assertTrue(valuesEquivalent(JsonPrimitive(4), JsonPrimitive(4.0)))
        assertTrue(valuesEquivalent(JsonUnquotedLiteral("1e3"), JsonPrimitive(1000)))
        assertTrue(valuesEquivalent(JsonUnquotedLiteral("-0"), JsonPrimitive(0)))
        assertTrue(valuesEquivalent(JsonPrimitive(true), JsonPrimitive(true)))
        assertFalse(valuesEquivalent(JsonPrimitive(4), JsonPrimitive(5)))
        assertFalse(valuesEquivalent(JsonPrimitive(1), JsonPrimitive(true)))
        // A double cannot tell these two apart; the digits can.
        assertFalse(valuesEquivalent(JsonUnquotedLiteral("9007199254740993"), JsonUnquotedLiteral("9007199254740992")))
        assertFalse(valuesEquivalent(JsonPrimitive(1.5), JsonPrimitive("1.5")))
    }

    // ------------------------------------------------------------------------------ typed input

    @Test
    fun `the largest number the harness stores is the largest a box takes`() {
        val max = DraftResult.Write(JsonPrimitive(9007199254740991))
        assertEquals(max, loop.draftFromText("9007199254740991"))
        assertEquals(max, timeout.draftFromText("9007199254740991.0"))
        val tooLarge = invalid(FieldProblem.TooLarge(9007199254740991.0))
        assertEquals(tooLarge, loop.draftFromText("9007199254740992"))
        assertEquals(tooLarge, loop.draftFromText("9007199254740993"))
        assertEquals(tooLarge, timeout.draftFromText("9007199254740991.5"))
        assertEquals(tooLarge, timeout.draftFromText("1" + "0".repeat(400)))
        assertEquals(tooLarge, loop.draftFromText("99999999999999999999999"))
    }

    @Test
    fun `a negative number cannot go below minus the largest either`() {
        assertEquals(DraftResult.Write(JsonPrimitive(-9007199254740991)), timeout.draftFromText("-9007199254740991"))
        assertEquals(invalid(FieldProblem.TooSmall(-9007199254740991.0)), timeout.draftFromText("-9007199254740992"))
        assertEquals(invalid(FieldProblem.TooSmall(-9007199254740991.0)), timeout.draftFromText("-1" + "0".repeat(30)))
    }

    @Test
    fun `a whole number typed as a decimal is written as a whole number`() {
        assertEquals(DraftResult.Write(JsonPrimitive(3)), count.draftFromText("3.0"))
        assertEquals("3", written(count.draftFromText("3.0")))
        assertEquals(DraftResult.Write(JsonPrimitive(30000)), timeout.draftFromText("30000.00"))
        assertEquals("30000", written(timeout.draftFromText("30000.00")))
    }

    @Test
    fun `leading zeros and a plus sign do not change the number`() {
        assertEquals(DraftResult.Write(JsonPrimitive(7)), count.draftFromText("007"))
        assertEquals(DraftResult.Write(JsonPrimitive(7)), count.draftFromText("+7"))
        assertEquals("7", written(count.draftFromText("007")))
    }

    @Test
    fun `negative zero is written as zero`() {
        assertEquals("0", written(timeout.draftFromText("-0")))
        assertEquals("0", written(timeout.draftFromText("-0.0")))
        assertEquals("0", written(timeout.draftFromText("0.000")))
    }

    @Test
    fun `an exponent is not a number in a box`() {
        assertEquals(invalid(FieldProblem.NotANumber), count.draftFromText("1e3"))
        assertEquals(invalid(FieldProblem.NotANumber), timeout.draftFromText("1E3"))
        assertEquals(invalid(FieldProblem.NotANumber), timeout.draftFromText("1e21"))
    }

    @Test
    fun `spaces around a number are ignored`() {
        assertEquals(DraftResult.Write(JsonPrimitive(12)), count.draftFromText(" 12 "))
        assertEquals(DraftResult.Write(JsonPrimitive(12)), count.draftFromText("\t12\n"))
        assertEquals(DraftResult.Write(JsonPrimitive(120000)), timeout.draftFromText("120 000"))
        assertEquals(DraftResult.Write(JsonPrimitive(120000)), timeout.draftFromText("120 000"))
        assertEquals(DraftResult.Write(JsonPrimitive(120000)), timeout.draftFromText("1 2 0 0 0 0"))
    }

    @Test
    fun `a comma is refused because it means different things`() {
        assertEquals(invalid(FieldProblem.NotANumber), count.draftFromText("1,5"))
        assertEquals(invalid(FieldProblem.NotANumber), timeout.draftFromText("1,500"))
        assertEquals(invalid(FieldProblem.NotANumber), timeout.draftFromText("1,5e3"))
    }

    @Test
    fun `an empty box is refused and neither a reset nor zero`() {
        assertEquals(invalid(FieldProblem.Required), count.draftFromText(""))
        assertEquals(invalid(FieldProblem.Required), timeout.draftFromText("   "))
        // Separators alone are an empty number too.
        assertEquals(invalid(FieldProblem.Required), timeout.draftFromText("\u00A0 \u202F"))
    }

    @Test
    fun `digits from other scripts are the same number`() {
        // Decision: the app ships Arabic, and an Arabic keyboard types these digits, so a box takes
        // any script's decimal digits (Unicode category Nd) and writes plain ASCII to the harness.
        assertEquals(DraftResult.Write(JsonPrimitive(12)), count.draftFromText("١٢")) // Arabic-Indic
        assertEquals(DraftResult.Write(JsonPrimitive(12)), count.draftFromText("۱۲")) // Persian
        assertEquals(DraftResult.Write(JsonPrimitive(12)), count.draftFromText("１２")) // full-width
        assertEquals(DraftResult.Write(JsonPrimitive(30)), count.draftFromText("३०")) // Devanagari
        assertEquals(DraftResult.Write(JsonPrimitive(12)), count.draftFromText("١ ٢")) // mixed spacing
        assertEquals(DraftResult.Write(JsonPrimitive(1200)), timeout.draftFromText("١٬٢٠٠")) // Arabic thousands mark
        assertEquals("12", written(count.draftFromText("١٢")))
    }

    @Test
    fun `the arabic decimal mark is a point`() {
        assertEquals("0.5", written(timeout.draftFromText("٠٫٥")))
        assertEquals("1.5", written(timeout.draftFromText("1٫5")))
        assertEquals(invalid(FieldProblem.NotWhole), count.draftFromText("٢٫٥"))
    }

    @Test
    fun `other script letters and symbols are not digits`() {
        assertEquals(invalid(FieldProblem.NotANumber), count.draftFromText("١٢a"))
        assertEquals(invalid(FieldProblem.NotANumber), count.draftFromText("اب")) // Arabic letters
        assertEquals(invalid(FieldProblem.NotANumber), count.draftFromText("١٭")) // Arabic star
    }

    @Test
    fun `a fraction is written with the digits typed and no exponent`() {
        assertEquals("0.1", written(timeout.draftFromText("0.1")))
        assertEquals("0.0000001", written(timeout.draftFromText("0.0000001")))
        assertEquals("2.5", written(timeout.draftFromText("2.50")))
        assertEquals("-1.5", written(timeout.draftFromText("-1.5")))
        assertEquals("0.30000000000000004", written(timeout.draftFromText("0.30000000000000004")))
        assertEquals(DraftResult.Write(JsonPrimitive(1.5)), timeout.draftFromText("1.5"))
    }

    @Test
    fun `a fraction too close to a whole number to see is still a fraction`() {
        // As a double this is exactly 2.0; as digits it is a fraction, so a whole field refuses it.
        assertEquals(invalid(FieldProblem.NotWhole), count.draftFromText("2.00000000000000000001"))
        assertEquals(invalid(FieldProblem.NotWhole), loop.draftFromText("9007199254740990.1"))
    }

    @Test
    fun `limits still hold on the typed digits`() {
        assertEquals(invalid(FieldProblem.TooLarge(50.0)), count.draftFromText("51"))
        assertEquals(invalid(FieldProblem.TooSmall(1.0)), count.draftFromText("0"))
        assertEquals(invalid(FieldProblem.TooSmall(1.0)), count.draftFromText("-0"))
        assertEquals(DraftResult.Write(JsonPrimitive(1)), count.draftFromText("1"))
        assertEquals(DraftResult.Write(JsonPrimitive(50)), count.draftFromText("50"))
    }
}
