package com.labteto.dshmobile.core.settings

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.RpcResultJsonSerializer
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.core.wire.dto.SettingsSecretView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The captured `settings/describe` value: ten of the harness's 21 namespaces, as it sent them. */
internal fun describedFixture(): SettingsDescribeValue =
    WireJson.decodeFromString(SettingsDescribeValue.serializer(), SettingsFixtures.DESCRIBE)

/** One row of the captured describe, or of the derived `bash-sandbox` row. */
internal fun fixtureRow(ns: String): SettingsNamespaceView = when (ns) {
    "bash-sandbox" -> WireJson.decodeFromString(SettingsNamespaceView.serializer(), SettingsFixtures.BASH_SANDBOX_ROW)
    "ui-chat" -> WireJson.decodeFromString(SettingsNamespaceView.serializer(), SettingsFixtures.UI_CHAT_ROW)
    else -> describedFixture().namespaces.single { it.ns == ns }
}

/** The `result` slot of a recorded answer: its value, or its error. */
internal fun fixtureResult(json: String): RpcResult<JsonElement> =
    WireJson.decodeFromString(RpcResultJsonSerializer, json)

/** A recorded write answer as the new namespace row. */
internal fun writeAnswer(json: String): SettingsNamespaceView {
    val result = fixtureResult(json) as RpcResult.Ok
    return WireJson.decodeFromJsonElement(SettingsNamespaceView.serializer(), result.value)
}

/** A recorded refusal as the error the client folds it into. */
internal fun refusal(json: String): RpcError = (fixtureResult(json) as RpcResult.Err).error

internal fun field(ns: String, key: String): SettingsField =
    settingsFields(fixtureRow(ns)).single { it.key == key }

internal fun str(value: String) = JsonPrimitive(value)

internal fun num(value: Number) = JsonPrimitive(value)

internal fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

/** A made-up namespace whose schema is an object with no fields at all. */
internal fun emptyRow(ns: String, autoGenerate: Boolean = true): SettingsNamespaceView = SettingsNamespaceView(
    ns = ns,
    schema = obj("uid" to num(1), "refs" to obj("1" to obj("type" to str("object"), "meta" to obj(), "dict" to obj()))),
    value = obj(),
    revision = 0,
    autoGenerate = autoGenerate,
)

/**
 * A made-up namespace carrying every field type the app edits and several it does not, so the
 * generic form is exercised on shapes no real harness namespace has. Nothing here was captured.
 */
internal fun syntheticRow(ns: String = "plugin-x", autoGenerate: Boolean = true): SettingsNamespaceView {
    val refs = LinkedHashMap<String, JsonElement>()
    fun add(
        id: Int,
        type: String,
        meta: Map<String, JsonElement> = emptyMap(),
        extra: Map<String, JsonElement> = emptyMap(),
    ): JsonPrimitive {
        refs[id.toString()] = JsonObject(mapOf("type" to str(type), "meta" to JsonObject(meta)) + extra)
        return JsonPrimitive(id)
    }

    val ratio = add(1, "number", mapOf("min" to num(0), "max" to num(1), "step" to num(0.5), "default" to num(0.5)))
    val label = add(2, "string", mapOf("min" to num(2), "max" to num(8), "description" to str("Shown name")))
    val flag = add(3, "boolean", mapOf("default" to JsonPrimitive(false)))
    val fast = add(4, "const", mapOf("description" to str("Fast")), mapOf("value" to str("fast")))
    val slow = add(5, "const", extra = mapOf("value" to str("slow")))
    val mode = add(6, "union", mapOf("default" to str("fast")), mapOf("list" to JsonArray(listOf(fast, slow))))
    val word = add(7, "string")
    val tags = add(8, "array", mapOf("max" to num(3)), mapOf("inner" to word))
    val token = add(9, "string", mapOf("role" to str("secret")))
    val nested = add(10, "object", extra = mapOf("dict" to obj("a" to word)))
    val weird = add(11, "tuple", extra = mapOf("list" to JsonArray(listOf(word))))
    val hidden = add(12, "string", mapOf("hidden" to JsonPrimitive(true)))
    val pinned = add(13, "string", mapOf("disabled" to JsonPrimitive(true)))
    val slug = add(14, "string", mapOf("pattern" to obj("source" to str("^[a-z]+$"), "flags" to str(""))))
    val root = add(
        20, "object", mapOf("default" to JsonObject(emptyMap())),
        mapOf(
            "dict" to obj(
                "ratio" to ratio, "label" to label, "enabledFlag" to flag, "mode" to mode, "tags" to tags,
                "token" to token, "nested" to nested, "weird" to weird, "secretHidden" to hidden,
                "pinned" to pinned, "slug" to slug,
            ),
        ),
    )
    return SettingsNamespaceView(
        ns = ns,
        schema = obj("uid" to root, "refs" to JsonObject(refs)),
        value = obj(
            "ratio" to num(0.5), "label" to str("Abc"), "enabledFlag" to JsonPrimitive(false), "mode" to str("fast"),
            "tags" to JsonArray(listOf(str("a"))), "nested" to obj("a" to str("z")),
            "weird" to JsonArray(listOf(str("q"))), "pinned" to str("fixed"),
        ),
        base = obj("ratio" to num(0.5), "label" to str("Abc")),
        user = obj("label" to str("Abc")),
        applies = "live",
        secrets = listOf(SettingsSecretView(listOf("token"), set = true)),
        revision = 3,
        autoGenerate = autoGenerate,
    )
}
