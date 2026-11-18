package com.labteto.dshmobile.core.settings

import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One field of one namespace: `web-deepseek-api` and `count`. */
data class FieldId(val ns: String, val key: String)

/** One literal a [FieldKind.Choice] or [FieldKind.ChoiceList] offers, with the words to show for it. */
data class ChoiceOption(val value: JsonPrimitive, val label: String)

/**
 * How a field is edited, decided from its schema node.
 *
 * The set is closed on purpose: a type this app cannot edit well is [ReadOnly], which shows the
 * current value and offers nothing, instead of a control that might write something the plugin
 * does not expect.
 */
sealed interface FieldKind {
    /** A string. [credentialRef] marks a field that names a credential (`apiKeyEnv`), not a key. */
    data class Text(
        val minLength: Int?,
        val maxLength: Int?,
        val pattern: String?,
        val credentialRef: Boolean = false,
    ) : FieldKind

    /** A number; [step] 1 is a whole number. */
    data class Number(val min: Double?, val max: Double?, val step: Double?) : FieldKind {
        val whole: Boolean get() = step == 1.0
    }

    data object Toggle : FieldKind

    data class Choice(val options: List<ChoiceOption>) : FieldKind

    /** An array of strings. */
    data class StringList(val minItems: Int?, val maxItems: Int?, val itemMinLength: Int?) : FieldKind

    /** An array whose items are each one of [options]. */
    data class ChoiceList(val options: List<ChoiceOption>, val minItems: Int?, val maxItems: Int?) : FieldKind

    /** A value the wire never returns: written with `settings/mutate`, shown only as set or not. */
    data object Secret : FieldKind

    /** A type this app does not edit; [typeName] is the schema's own word for it. */
    data class ReadOnly(val typeName: String) : FieldKind
}

/**
 * One editable (or displayed) field, joined from the three things a `settings/describe` row says
 * about it: its schema node, its layered values and its secret slot.
 */
data class SettingsField(
    val ns: String,
    val key: String,
    val kind: FieldKind,
    /** What is in force now: user layer over the deployment's, over the schema default. */
    val value: JsonElement?,
    /** What the field reverts to when cleared: the deployment's value, else the schema default. */
    val baseValue: JsonElement?,
    /** Whether the user layer carries this field. Presence, not inequality: an override equal to the default is still one. */
    val overridden: Boolean,
    /** For a [FieldKind.Secret]: whether the slot holds a value now. */
    val secretSet: Boolean,
    val required: Boolean,
    /** The plugin's own description, for a field the app has no words for. */
    val description: String?,
) {
    val id: FieldId get() = FieldId(ns, key)
}

/**
 * The fields of one namespace, in the order the plugin declared them, hidden ones left out.
 *
 * A namespace whose schema cannot be read still lists: every key in its value comes back
 * [FieldKind.ReadOnly], so the person sees what is set rather than nothing.
 */
fun settingsFields(view: SettingsNamespaceView): List<SettingsField> {
    val value = view.value as? JsonObject
    val base = view.base as? JsonObject
    val user = view.user as? JsonObject
    val slots = view.secrets.filter { it.path.size == 1 }.associate { it.path.single() to it.set }
    val declared = (parseSettingsSchema(view.schema) as? SchemaObject)?.fields

    if (declared == null) {
        return value.orEmpty().map { (key, current) ->
            SettingsField(
                ns = view.ns, key = key, kind = FieldKind.ReadOnly("unknown"), value = current,
                baseValue = base?.get(key), overridden = user?.containsKey(key) == true,
                secretSet = false, required = false, description = null,
            )
        }
    }
    return declared.mapNotNull { (key, node) ->
        if (node.meta.hidden) return@mapNotNull null
        val kind = if (key in slots) FieldKind.Secret else kindOf(node)
        SettingsField(
            ns = view.ns,
            key = key,
            kind = kind,
            value = (value?.get(key) ?: node.meta.default)?.takeUnless { it is JsonNull },
            baseValue = (base?.get(key) ?: node.meta.default)?.takeUnless { it is JsonNull },
            overridden = user?.containsKey(key) == true,
            secretSet = slots[key] == true,
            required = node.meta.required,
            description = node.meta.description,
        )
    }
}

private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

private fun kindOf(node: SettingsSchema): FieldKind {
    if (node.meta.disabled) return FieldKind.ReadOnly(typeNameOf(node))
    return when (node) {
        is SchemaString ->
            if (node.meta.role == "secret") FieldKind.Secret
            else FieldKind.Text(
                minLength = node.meta.min?.toInt(),
                maxLength = node.meta.max?.toInt(),
                pattern = node.meta.pattern,
                credentialRef = node.meta.role == "credential-ref",
            )
        is SchemaNumber -> FieldKind.Number(node.meta.min, node.meta.max, node.meta.step)
        is SchemaBoolean -> FieldKind.Toggle
        is SchemaUnion -> optionsOf(node)?.let { FieldKind.Choice(it) } ?: FieldKind.ReadOnly("union")
        is SchemaArray -> listKindOf(node)
        else -> FieldKind.ReadOnly(typeNameOf(node))
    }
}

private fun listKindOf(array: SchemaArray): FieldKind {
    val minItems = array.meta.min?.toInt()
    val maxItems = array.meta.max?.toInt()
    return when (val inner = array.inner) {
        is SchemaString ->
            if (inner.meta.role == "secret") FieldKind.ReadOnly("array")
            else FieldKind.StringList(minItems, maxItems, inner.meta.min?.toInt())
        is SchemaUnion -> optionsOf(inner)?.let { FieldKind.ChoiceList(it, minItems, maxItems) } ?: FieldKind.ReadOnly("array")
        else -> FieldKind.ReadOnly("array")
    }
}

/** The options of a union made only of literals; anything else is not an enumeration. */
private fun optionsOf(union: SchemaUnion): List<ChoiceOption>? {
    if (union.members.isEmpty()) return null
    return union.members.map { member ->
        val literal = (member as? SchemaConst)?.value as? JsonPrimitive ?: return null
        if (literal is JsonNull) return null
        ChoiceOption(literal, member.meta.description ?: literal.contentOrNull.orEmpty())
    }
}

private fun typeNameOf(node: SettingsSchema): String = when (node) {
    is SchemaString -> "string"
    is SchemaNumber -> "number"
    is SchemaBoolean -> "boolean"
    is SchemaConst -> "const"
    is SchemaUnion -> "union"
    is SchemaArray -> "array"
    is SchemaObject -> "object"
    is SchemaDict -> "dict"
    is SchemaOther -> node.type
}

/**
 * A name for a field or namespace the app has no words for: `maxParallelToolCalls` becomes
 * "Max parallel tool calls", `baseURL` "Base URL", `web-search-deepseek` "Web search deepseek".
 * Display only.
 */
fun humanizeSettingsName(name: String): String {
    val spaced = buildString {
        name.forEachIndexed { index, c ->
            val previous = name.getOrNull(index - 1)
            val next = name.getOrNull(index + 1)
            when {
                c == '-' || c == '_' || c == '.' -> append(' ')
                // A word boundary inside camelCase: "maxParallel" and the "U" of "baseURL" both start one.
                c.isUpperCase() && previous != null && (previous.isLowerCase() || previous.isDigit()) -> {
                    append(' ')
                    // An acronym keeps its capitals; an ordinary word is lowercased.
                    append(if (next != null && next.isUpperCase()) c else c.lowercaseChar())
                }
                else -> append(c)
            }
        }
    }.trim().replace(Regex("\\s+"), " ")
    return spaced.replaceFirstChar { it.uppercaseChar() }
}
