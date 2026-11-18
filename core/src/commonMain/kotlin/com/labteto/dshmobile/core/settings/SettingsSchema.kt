package com.labteto.dshmobile.core.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * What the harness says about one settings namespace's shape, read from the `schema` of a
 * `settings/describe` row.
 *
 * The harness serializes a Schemastery graph (`schema.toJSON()` in `packages/settings/settings`):
 * `{"uid": 3600, "refs": {"3593": {"type": "string", "meta": {...}}, ..., "3600": {"type":
 * "object", "dict": {"apiKey": 3593}}}}`. `uid` names the root, every child is a number that
 * points back into `refs`, and a node carries only the keys its type uses. The namespaces the
 * harness describes are always objects, but nothing here relies on that: [parseSettingsSchema]
 * reads whatever it is given, and a node type it does not know becomes [SchemaOther] rather than
 * a failure. The wire layer parses leniently on purpose; a plugin this app has never heard of
 * must still list.
 */
sealed interface SettingsSchema {
    val meta: SchemaMeta
}

/**
 * The metadata a Schemastery node carries (`z.number().min(1).default(10)`, `role('secret')`).
 *
 * The labels a person reads are not here: a plugin's schemas in this harness carry no
 * `description`, so the app writes its own copy for the pages it knows. [description] is read for
 * a plugin that does send one, and is the only text a namespace the app does not know has.
 */
data class SchemaMeta(
    val default: JsonElement? = null,
    val required: Boolean = false,
    /** `secret` (a value the wire never returns) or `credential-ref` (the name of a credential). */
    val role: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    /** The regular expression source a string must match; flags are not carried. */
    val pattern: String? = null,
    val hidden: Boolean = false,
    val disabled: Boolean = false,
    val description: String? = null,
)

data class SchemaString(override val meta: SchemaMeta) : SettingsSchema

data class SchemaNumber(override val meta: SchemaMeta) : SettingsSchema

data class SchemaBoolean(override val meta: SchemaMeta) : SettingsSchema

/** One literal value; a union of them is an enumeration. */
data class SchemaConst(override val meta: SchemaMeta, val value: JsonElement) : SettingsSchema

data class SchemaUnion(override val meta: SchemaMeta, val members: List<SettingsSchema>) : SettingsSchema

data class SchemaArray(override val meta: SchemaMeta, val inner: SettingsSchema?) : SettingsSchema

/** Named fields, in the order the plugin declared them. */
data class SchemaObject(override val meta: SchemaMeta, val fields: Map<String, SettingsSchema>) : SettingsSchema

data class SchemaDict(override val meta: SchemaMeta, val inner: SettingsSchema?) : SettingsSchema

/** Any other Schemastery type (`tuple`, `intersect`, `any`, `function`, ...), or a cycle. */
data class SchemaOther(override val meta: SchemaMeta, val type: String) : SettingsSchema

/** The harness's "no upper bound": the largest integer a JavaScript number holds exactly. */
private const val UNBOUNDED_ABOVE = 9.0e15

/** Nesting deeper than any real configuration section; the guard against a hostile graph. */
private const val MAX_DEPTH = 24

/**
 * The schema in a `settings/describe` row, or null when [envelope] is not a schema at all
 * (missing, `null`, a string).
 *
 * Also accepts a bare node (an object with a `type` and no `refs`), so a harness that stops
 * flattening the graph still reads.
 */
fun parseSettingsSchema(envelope: JsonElement?): SettingsSchema? {
    val root = envelope as? JsonObject ?: return null
    val refs = root["refs"] as? JsonObject
    val uid = root["uid"]
    val reader = SchemaReader(refs ?: JsonObject(emptyMap()))
    if (refs != null && uid != null) return reader.resolve(uid, emptySet(), 0)
    return if (root["type"] != null) reader.node(root, emptySet(), 0) else null
}

private class SchemaReader(private val refs: JsonObject) {

    /** A child: a number pointing into `refs`, or (leniently) the node itself. */
    fun resolve(ref: JsonElement?, visiting: Set<String>, depth: Int): SettingsSchema? {
        if (ref == null || ref is JsonNull) return null
        if (ref is JsonObject) return node(ref, visiting, depth)
        val key = (ref as? JsonPrimitive)?.contentOrNull ?: return null
        val target = refs[key] as? JsonObject ?: return null
        if (key in visiting) return SchemaOther(metaOf(target["meta"]), "recursive")
        return node(target, visiting + key, depth)
    }

    fun node(obj: JsonObject, visiting: Set<String>, depth: Int): SettingsSchema {
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: "any"
        val meta = metaOf(obj["meta"])
        if (depth >= MAX_DEPTH) return SchemaOther(meta, type)
        return when (type) {
            "string" -> SchemaString(meta)
            "number" -> SchemaNumber(meta)
            "boolean" -> SchemaBoolean(meta)
            "const" -> SchemaConst(meta, obj["value"] ?: JsonNull)
            "union" -> SchemaUnion(meta, members(obj["list"], visiting, depth))
            "array" -> SchemaArray(meta, resolve(obj["inner"], visiting, depth + 1))
            "dict" -> SchemaDict(meta, resolve(obj["inner"], visiting, depth + 1))
            "object" -> SchemaObject(meta, fields(obj["dict"], visiting, depth))
            // A lazy node is its builder's result; one that was never built has no shape to show.
            "lazy" -> resolve(obj["inner"], visiting, depth + 1) ?: SchemaOther(meta, type)
            else -> SchemaOther(meta, type)
        }
    }

    private fun members(list: JsonElement?, visiting: Set<String>, depth: Int): List<SettingsSchema> =
        ((list as? JsonArray) ?: emptyList<JsonElement>()).mapNotNull { resolve(it, visiting, depth + 1) }

    private fun fields(dict: JsonElement?, visiting: Set<String>, depth: Int): Map<String, SettingsSchema> {
        val result = LinkedHashMap<String, SettingsSchema>()
        (dict as? JsonObject)?.forEach { (name, ref) ->
            resolve(ref, visiting, depth + 1)?.let { result[name] = it }
        }
        return result
    }
}

private fun metaOf(element: JsonElement?): SchemaMeta {
    val meta = element as? JsonObject ?: return SchemaMeta()
    // Read as digits (see SettingsNumbers.kt); a value beyond the safe range is "no limit", not a number.
    fun number(key: String): Double? = (meta[key] as? JsonPrimitive)?.contentOrNull
        ?.let { parseDecimal(it, allowExponent = true) }?.toDoubleOrNull()?.takeIf { it.isFinite() }
    fun flag(key: String): Boolean = (meta[key] as? JsonPrimitive)?.booleanOrNull == true
    val pattern = ((meta["pattern"] as? JsonObject)?.get("source") as? JsonPrimitive)?.contentOrNull
    return SchemaMeta(
        default = meta["default"]?.takeUnless { it is JsonNull },
        required = flag("required"),
        role = (meta["role"] as? JsonPrimitive)?.contentOrNull,
        min = number("min"),
        // The harness writes "no maximum" as the largest safe integer; that is not a limit to show.
        max = number("max")?.takeIf { it < UNBOUNDED_ABOVE },
        step = number("step"),
        pattern = pattern,
        hidden = flag("hidden"),
        disabled = flag("disabled"),
        description = descriptionOf(meta["description"]),
    )
}

/** A plain string, or a locale map: the unnamed locale, then English, then whatever there is. */
private fun descriptionOf(element: JsonElement?): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
    is JsonObject -> listOf("", "en").firstNotNullOfOrNull { (element[it] as? JsonPrimitive)?.contentOrNull }
        ?: element.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
    else -> null
}
