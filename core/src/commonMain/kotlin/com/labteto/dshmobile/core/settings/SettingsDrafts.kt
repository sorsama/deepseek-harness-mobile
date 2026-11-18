package com.labteto.dshmobile.core.settings

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Why a typed value cannot be saved, as data. The screen writes the sentence (in the person's
 * language, with the numbers in it); this only says which rule failed.
 */
sealed interface FieldProblem {
    /**
     * Empty, where an empty box is not an answer: a number, an address, a field the plugin requires
     * or one whose schema asks for some text. An empty box is never read as "use the default"; that
     * is the separate Reset action.
     */
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

/** [MAX_SAFE_INTEGER] as a limit in a problem. */
private const val MAX_SAFE = 9007199254740991.0

/**
 * The draft a text box holds, as a write or a problem.
 *
 * An empty box is never a reset: clearing a field to retype it must not drop an override by
 * accident (a number box empty and Done once removed a deliberate timeout without a word). Going
 * back to the deployment's value is the separate "Reset to default" action, which sends `unset`.
 * An empty number is refused. An empty text is written as an empty string only where the schema
 * allows one: no minimum length, a pattern that matches it, not required, and not an address.
 */
fun SettingsField.draftFromText(text: String, traits: FieldTraits = FieldTraits()): DraftResult = when (val kind = kind) {
    is FieldKind.Number -> numberDraft(kind, text, traits)
    is FieldKind.Text -> textDraft(kind, text, traits)
    is FieldKind.Secret -> secretDraft(text)
    else -> DraftResult.Invalid(FieldProblem.WrongFormat)
}

/** An empty box where nothing may be empty. */
private val EMPTY_REFUSED = DraftResult.Invalid(FieldProblem.Required)

private fun SettingsField.textDraft(kind: FieldKind.Text, raw: String, traits: FieldTraits): DraftResult {
    val text = raw.trim()
    if (text.isEmpty()) return emptyTextDraft(kind, traits)
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

/** How the person leaves a text box. */
enum class TextExit {
    /** The keyboard's Done. */
    Done,

    /** Focus moved elsewhere. */
    Blur,

    /** The page is closing with the keyboard still up (Back, a swipe). */
    Dispose,
}

/** What leaving a text box does with the draft in it. */
sealed interface TextAction {
    /** Write this value. */
    data class Send(val value: JsonElement) : TextAction

    /** Put the saved text back: the draft changes nothing, or it is an empty box that is not an answer. */
    data object Restore : TextAction

    /** Leave the draft, and the message about it, where they are. */
    data object Keep : TextAction
}

/**
 * What finishing or leaving a text box does, in one place so the rule is tested and not only drawn.
 *
 * A draft that can be saved is sent, unless it changes nothing. One that cannot is never sent, on
 * Done or on leaving the field. An empty box is the case that needs care: Done keeps it and its
 * message in view (so the refusal can be read), and leaving the field puts the saved value back,
 * because an empty box is not an attempt at a value. Any other refused draft stays until corrected.
 * Going back to the default is the Reset button, never a side effect of an empty box.
 */
fun SettingsField.textAction(text: String, traits: FieldTraits, edited: Boolean, exit: TextExit): TextAction {
    if (!edited) return TextAction.Keep
    return when (val draft = draftFromText(text, traits)) {
        is DraftResult.Write -> if (isUnchangedBy(draft.value)) TextAction.Restore else TextAction.Send(draft.value)
        is DraftResult.Invalid ->
            if (draft.problem == FieldProblem.Required && exit == TextExit.Blur) TextAction.Restore else TextAction.Keep
    }
}

/** Whether Done leaves the cursor in the box: only an empty one that was refused, so its message stays beside it. */
fun SettingsField.keepsFocusOnDone(text: String, traits: FieldTraits, edited: Boolean): Boolean =
    edited && (draftFromText(text, traits) as? DraftResult.Invalid)?.problem == FieldProblem.Required

/**
 * What an empty text box means for this field: an empty string where the schema allows one, a
 * refusal where it does not. An address and the name of a credential are never empty.
 */
private fun SettingsField.emptyTextDraft(kind: FieldKind.Text, traits: FieldTraits): DraftResult {
    if (required || traits.url || kind.credentialRef) return EMPTY_REFUSED
    if ((kind.minLength ?: 0) > 0) return EMPTY_REFUSED
    kind.pattern?.let { source ->
        // A pattern this platform cannot read is the harness's to enforce, as for any other text.
        val regex = try { Regex(source) } catch (_: IllegalArgumentException) { null }
        if (regex != null && !regex.containsMatchIn("")) return EMPTY_REFUSED
    }
    return DraftResult.Write(JsonPrimitive(""))
}

/**
 * Whether writing [value] would change nothing the person can see: it is what is already in force,
 * or it is an empty text over a field that holds nothing (typed into and cleared again), which must
 * not leave an override of `""` behind.
 */
fun SettingsField.isUnchangedBy(value: JsonElement): Boolean {
    if (valuesEquivalent(value, this.value)) return true
    val emptyText = value is JsonPrimitive && value.isString && value.content.isEmpty()
    return emptyText && !overridden && this.value == null
}

/**
 * A typed number, judged on its digits and not on a double: whether it is whole, and whether it
 * is inside the safe range, never depend on how a platform rounds a long string (Kotlin/Native
 * and the JVM do not agree near 2^53). A whole number is written as a `Long`; a fraction is
 * written with the digits the person typed (trailing zeros dropped), never `Double.toString()`.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun SettingsField.numberDraft(kind: FieldKind.Number, raw: String, traits: FieldTraits): DraftResult {
    val text = normalizeNumberText(raw)
    if (text.isEmpty()) return EMPTY_REFUSED
    val typed = parseDecimal(text) ?: return DraftResult.Invalid(FieldProblem.NotANumber)
    if ((kind.whole || traits.whole) && !typed.isWhole) return DraftResult.Invalid(FieldProblem.NotWhole)
    // Beyond the safe range the number is an infinity here, so the limits below decide it.
    val number = typed.toDoubleOrNull() ?: return DraftResult.Invalid(FieldProblem.NotANumber)
    val min = listOfNotNull(kind.min, traits.min, -MAX_SAFE).max()
    val max = listOfNotNull(kind.max, traits.max, MAX_SAFE).min()
    if (number < min) return DraftResult.Invalid(FieldProblem.TooSmall(min))
    if (number > max) return DraftResult.Invalid(FieldProblem.TooLarge(max))
    val step = kind.step
    if (step != null && step > 0 && step != 1.0 && !isMultipleOf(number, kind.min ?: 0.0, step)) {
        return DraftResult.Invalid(FieldProblem.NotMultiple(step))
    }
    val whole = typed.toWholeLongOrNull()
    return DraftResult.Write(if (whole != null) JsonPrimitive(whole) else JsonUnquotedLiteral(typed.plain()))
}

/**
 * `120 000`, `٣`, `4٫5`: spaces out, any script's decimal digits to ASCII, `٫` to a point. A comma
 * stays a comma and is refused, because `1,5` and `1,500` mean different things in different places.
 */
internal fun normalizeNumberText(raw: String): String = buildString {
    for (c in raw.trim()) {
        when {
            c.isWhitespace() || c in SEPARATORS -> Unit
            c == ARABIC_DECIMAL_SEPARATOR -> append('.')
            c in '0'..'9' -> append(c)
            else -> append(c.digitToIntOrNull()?.let { '0' + it } ?: c)
        }
    }
}

/** No-break space, narrow no-break space, thin space, and the Arabic thousands separator. */
private const val SEPARATORS = "\u00A0\u202F\u2009\u066C"
private const val ARABIC_DECIMAL_SEPARATOR = '\u066B'

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
    // Read as digits, not as a double: `4.0` shows as `4`, `1e21` as its digits, `1.5e-7` as `0.00000015`.
    return parseDecimal(content, allowExponent = true)?.plain() ?: content
}

/**
 * Whether two values mean the same thing: `4` and `4.0` do, which a plain comparison of their
 * JSON text would miss; this is what keeps a save from writing a field back to itself.
 */
fun valuesEquivalent(a: JsonElement?, b: JsonElement?): Boolean {
    if (a == null || a is JsonNull) return b == null || b is JsonNull
    if (b == null || b is JsonNull) return false
    if (a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString) {
        // Compared as digits: `4` and `4.0` agree, and `9007199254740993` and `9007199254740992` do not.
        val x = parseDecimal(a.content, allowExponent = true)
        val y = parseDecimal(b.content, allowExponent = true)
        if (x != null && y != null) return x == y
        val p = a.booleanOrNull
        val q = b.booleanOrNull
        if (p != null && q != null) return p == q
    }
    return a == b
}
