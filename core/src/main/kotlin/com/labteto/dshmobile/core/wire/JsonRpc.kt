package com.labteto.dshmobile.core.wire

import java.util.UUID
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/**
 * Shared wire JSON codec and envelope helpers.
 *
 * [WireJson] is lenient by design: unknown keys are ignored (the harness merge-extends its
 * payloads), input values are coerced to declared types, defaults are always encoded (so the
 * literal `type` discriminants and `accepted: true` markers reach the wire), and explicit nulls
 * are suppressed (so absent optional fields stay absent — the harness rejects nulls where it
 * expects an omitted optional).
 */
val WireJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    explicitNulls = false
}

/** Mint a fresh correlation id for a client-request (the initiator mints; responses echo). */
fun newRpcId(): String = UUID.randomUUID().toString()

/**
 * Mint a fresh identity for one human prompt (`requestId` on a session or subagent prompt).
 *
 * Not the same thing as [newRpcId], which names one HTTP call. This names the *message*: the host
 * persists it on the message the prompt is accepted as, so a retry of the same message must carry
 * the id it already had, and two different messages must never share one.
 */
fun newPromptRequestId(): String = UUID.randomUUID().toString()

/** Encode a client-request envelope (POST /api/<method> body). */
fun encodeEnvelope(request: ClientRequest): String =
    WireJson.encodeToString(ClientRequest.serializer(), request)

/** Decode and validate a server-response envelope (the HTTP response body of a unary call). */
fun decodeServerResponse(json: String): ServerResponse {
    val envelope: ServerResponse = WireJson.decodeFromString(ServerResponse.serializer(), json)
    require(envelope.type == "server-response") {
        "expected a server-response envelope but got type \"${envelope.type}\""
    }
    return envelope
}

/** Encode a value to a [JsonElement] using an explicit serializer. */
fun <T> encodeToJsonElement(serializer: SerializationStrategy<T>, value: T): JsonElement =
    WireJson.encodeToJsonElement(serializer, value)

/** Decode a [JsonElement] into a value using an explicit serializer. */
fun <T> decodeFromJsonElement(serializer: DeserializationStrategy<T>, element: JsonElement): T =
    WireJson.decodeFromJsonElement(serializer, element)

/** Decode a [JsonElement] into a value of the inferred type. */
inline fun <reified T> decodeFromJsonElement(element: JsonElement): T =
    WireJson.decodeFromJsonElement(serializer<T>(), element)

/** Convenience for tests and tooling: serialize any @Serializable value to a compact string. */
fun <T> encodeToString(serializer: SerializationStrategy<T>, value: T): String =
    WireJson.encodeToString(serializer, value)

/** Convenience for tests and tooling: deserialize a compact string into a value. */
inline fun <reified T> decodeFromString(json: String): T =
    WireJson.decodeFromString(serializer<T>(), json)
