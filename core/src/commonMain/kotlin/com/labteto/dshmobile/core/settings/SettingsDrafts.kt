package com.labteto.dshmobile.core.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Why a typed value cannot be saved, as data. The screen writes the sentence (in the person's
 * language, with the numbers in it); this only says which rule failed.
 */
sealed interface FieldProblem {
    /** Empty, where the plugin has no default to fall back to. */
    data object Required : FieldProblem

    data object NotANumber : FieldProblem

    data object NotWhole : FieldProblem

    data class NotMultiple(val step: Double) : FieldProblem

    data class TooSmall(val min: Double) : FieldProblem

    data class TooLarge(val max: Double) : FieldProblem

    data class TooShort(val min: Int) : FieldProblem

    data class TooLong(val max: Int) : FieldProblem

    /** Not the shape the plugin's pattern asks for. */
    data object WrongFormat : FieldProblem

    /** Not an `http` or `https` address. */
    data object NotAnUrl : FieldProblem

    /** Not one of the values the field offers. */
    data object NotAnOption : FieldProblem

    data class TooFewItems(val min: Int) : FieldProblem

    data class TooManyItems(val max: Int) : FieldProblem

    /** A pasted `NAME=value` line, quotes, or spaces inside a key. */
    data object KeyFormat : FieldProblem
}

/** What committing a draft would do. */
sealed interface DraftResult {
    /** Write this value. */
    data class Write(val value: JsonElement) : DraftResult

    /** Clear the override, so the field goes back to the deployment's value. */
    data object Reset : DraftResult

    data class Invalid(val problem: FieldProblem) : DraftResult
}

/**
 * Rules the app adds on top of what a schema says, for the pages it knows.
 *
 * The shell timeout is declared as a bare number, though `0` or `-5` cannot be a timeout; the
 * search endpoint is a bare string, though only an `http(s)` address works. The harness stays the
 * judge of everything else.
 */
data class FieldTraits(
    val url: Boolean = false,
    val whole: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
)

/** The largest whole number a JavaScript number holds exactly; the harness cannot store more. */
private const val MAX_SAFE = 9007199254740991.0

/**
 * The draft a text box holds, as a write, a reset or a problem.
 *
 * Empty text is "use the default": a reset, the same gesture as the "Reset to default" action,
 * which is how the harness's own page treats a blanked field. Only a field the plugin gives no
 * default and marks required refuses it.
 */
fun SettingsField.draftFromText(text: String, traits: FieldTraits = FieldTraits()): DraftResult = when (val kind = kind) {
    is FieldKind.Number -> numberDraft(kind, text, traits)
    is FieldKind.Text -> textDraft(kind, text, traits)
    is FieldKind.Secret -> secretDraft(text)
    else -> DraftResult.Invalid(FieldProblem.WrongFormat)
}

private fun SettingsField.blankDraft(): DraftResult =
    if (required && baseValue == null) DraftResult.Invalid(FieldProblem.Required) else DraftResult.Reset

private fun SettingsField.textDraft(kind: FieldKind.Text, raw: String, traits: FieldTraits): DraftResult {
    val text = raw.trim()
    if (text.isEmpty()) return blankDraft()
    if (traits.url && !looksLikeHttpUrl(text)) return DraftResult.Invalid(FieldProblem.NotAnUrl)
    kind.minLength?.let { if (text.length < it) return DraftResult.Invalid(FieldProblem.TooShort(it)) }
    kind.maxLength?.let { if (text.length > it) return DraftResult.Invalid(FieldProblem.TooLong(it)) }
    kind.pattern?.let { source ->
        // A JavaScript pattern is not always a valid Kotlin one; an unreadable one is the harness's to enforce.
        val regex = try { Regex(source) } catch (_: IllegalArgumentException) { null }
        if (regex != null && !regex.containsMatchIn(text)) return DraftResult.Invalid(FieldProblem.WrongFormat)
    }
    return DraftResult.Write(JsonPrimitive(text))
}

private fun SettingsField.numberDraft(kind: FieldKind.Number, raw: String, traits: FieldTraits): DraftResult {
    val text = normalizeNumberText(raw)
    if (text.isEmpty()) return blankDraft()
    if (!NUMBER.matches(text)) return DraftResult.Invalid(FieldProblem.NotANumber)
    val number = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return DraftResult.Invalid(FieldProblem.NotANumber)
    val isWhole = floor(number) == number
    if ((kind.whole || traits.whole) && !isWhole) return DraftResult.Invalid(FieldProblem.NotWhole)
    val min = listOfNotNull(kind.min, traits.min).maxOrNull()
    val max = listOfNotNull(kind.max, traits.max, MAX_SAFE).minOrNull()
    if (min != null && number < min) return DraftResult.Invalid(FieldProblem.TooSmall(min))
    if (max != null && number > max) return DraftResult.Invalid(FieldProblem.TooLarge(max))
    val step = kind.step
    if (step != null && step > 0 && step != 1.0 && !isMultipleOf(number, kind.min ?: 0.0, step)) {
        return DraftResult.Invalid(FieldProblem.NotMultiple(step))
    }
    return DraftResult.Write(if (isWhole && abs(number) <= MAX_SAFE) JsonPrimitive(number.roundToLong()) else JsonPrimitive(number))
}

/** `120 000`, `٣`, `4.5`: spaces out, any script's digits to ASCII, `٫` to a point. */
internal fun normalizeNumberText(raw: String): String = buildString {
    for (c in raw.trim()) {
        when {
            c.isWhitespace() || c == ' ' || c == ' ' || c == ' ' -> Unit
            c == '٫' -> append('.')
            c in '0'..'9' -> append(c)
            else -> append(c.digitToIntOrNull()?.let { '0' + it } ?: c)
        }
    }
}

private val NUMBER = Regex("^[+-]?[0-9]+(\\.[0-9]+)?$")

private fun isMultipleOf(value: Double, from: Double, step: Double): Boolean {
    val quotient = (value - from) / step
    return abs(quotient - quotient.roundToLong().toDouble()) < 1e-9
}

/** `http` or `https` and something after it, no spaces. */
fun looksLikeHttpUrl(text: String): Boolean {
    val value = text.trim()
    val rest = when {
        value.startsWith("https://", ignoreCase = true) -> value.substring(8)
        value.startsWith("http://", ignoreCase = true) -> value.substring(7)
        else -> return false
    }
    return rest.isNotEmpty() && !rest.startsWith("/") && rest.none { it.isWhitespace() }
}

private val ENV_LINE = Regex("^[A-Z][A-Z0-9_]*=[^=]")
private val PRINTABLE_WORD = Regex("^[\\x21-\\x7E]+$")

/**
 * A key typed or pasted for a secret field: one printable word, not a `NAME=value` line and not
 * quoted. The same judgement the Models screen makes of a provider key.
 */
fun secretDraft(raw: String): DraftResult {
    val value = raw.trim()
    if (value.isEmpty()) return DraftResult.Invalid(FieldProblem.Required)
    val quoted = value.length > 1 && value.first() in "\"'`" && value.endsWith(value.first())
    if (ENV_LINE.containsMatchIn(value) || quoted || !PRINTABLE_WORD.matches(value)) {
        return DraftResult.Invalid(FieldProblem.KeyFormat)
    }
    return DraftResult.Write(JsonPrimitive(value))
}

/** A single choice: it must be one the field offers. */
fun SettingsField.draftFromChoice(choice: JsonPrimitive): DraftResult {
    val kind = kind as? FieldKind.Choice ?: return DraftResult.Invalid(FieldProblem.NotAnOption)
    return if (kind.options.any { valuesEquivalent(it.value, choice) }) DraftResult.Write(choice)
    else DraftResult.Invalid(FieldProblem.NotAnOption)
}

/** A list of strings: blanks dropped, the count and each item's length held to the schema. */
fun SettingsField.draftFromList(items: List<String>): DraftResult {
    val kind = kind as? FieldKind.StringList ?: return DraftResult.Invalid(FieldProblem.NotAnOption)
    val kept = items.map { it.trim() }.filter { it.isNotEmpty() }
    kind.minItems?.let { if (kept.size < it) return DraftResult.Invalid(FieldProblem.TooFewItems(it)) }
    kind.maxItems?.let { if (kept.size > it) return DraftResult.Invalid(FieldProblem.TooManyItems(it)) }
    kind.itemMinLength?.let { min -> if (kept.any { it.length < min }) return DraftResult.Invalid(FieldProblem.TooShort(min)) }
    return DraftResult.Write(JsonArray(kept.map { JsonPrimitive(it) }))
}

/** A list of choices: each must be offered, the count held to the schema. */
fun SettingsField.draftFromSelection(values: List<JsonPrimitive>): DraftResult {
    val kind = kind as? FieldKind.ChoiceList ?: return DraftResult.Invalid(FieldProblem.NotAnOption)
    if (values.any { v -> kind.options.none { valuesEquivalent(it.value, v) } }) return DraftResult.Invalid(FieldProblem.NotAnOption)
    kind.minItems?.let { if (values.size < it) return DraftResult.Invalid(FieldProblem.TooFewItems(it)) }
    kind.maxItems?.let { if (values.size > it) return DraftResult.Invalid(FieldProblem.TooManyItems(it)) }
    return DraftResult.Write(JsonArray(values))
}

/**
 * The text a box starts with for [value]: a number without an exponent, a string as it is, nothing
 * for a missing or `null` value (the placeholder then shows the default).
 */
fun fieldText(value: JsonElement?): String {
    val primitive = value as? JsonPrimitive ?: return ""
    if (primitive is JsonNull) return ""
    val content = primitive.contentOrNull ?: return ""
    if (primitive.isString) return content
    val number = primitive.doubleOrNull
    if (number != null && (content.contains('e') || content.contains('E'))) {
        return if (floor(number) == number && abs(number) <= MAX_SAFE) number.roundToLong().toString() else content
    }
    return content
}

/**
 * Whether two values mean the same thing: `4` and `4.0` do, which a plain comparison of their
 * JSON text would miss; this is what keeps a save from writing a field back to itself.
 */
fun valuesEquivalent(a: JsonElement?, b: JsonElement?): Boolean {
    if (a == null || a is JsonNull) return b == null || b is JsonNull
    if (b == null || b is JsonNull) return false
    if (a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString) {
        val x = a.doubleOrNull
        val y = b.doubleOrNull
        if (x != null && y != null) return x == y
        val p = a.booleanOrNull
        val q = b.booleanOrNull
        if (p != null && q != null) return p == q
    }
    return a == b
}
