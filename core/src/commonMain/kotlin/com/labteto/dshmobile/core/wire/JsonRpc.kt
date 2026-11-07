package com.labteto.dshmobile.core.wire

import kotlin.random.Random
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

/**
 * A random RFC 4122 version 4 id, spelt the way `java.util.UUID.randomUUID()` used to spell it:
 * lowercase hex, hyphens at 8-4-4-4-12.
 *
 * Common Kotlin has no `java.util.UUID`, and these ids only have to be unique within one client's
 * conversation with one host — not unguessable in any security sense — so the standard library's
 * [Random] is the right source. [newRpcId] and [newPromptRequestId] both go through here so a future
 * swap (e.g. `kotlin.uuid.Uuid` once stable) is a one-line change.
 */
private fun randomUuid(): String {
    val bytes = ByteArray(16)
    Random.nextBytes(bytes)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte() // version 4
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte() // RFC 4122 variant
    val digits = "0123456789abcdef"
    val hex = buildString(32) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0xf])
        }
    }
    return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) +
        "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32)
}

/** Mint a fresh correlation id for a client-request (the initiator mints; responses echo). */
fun newRpcId(): String = randomUuid()

/**
 * Mint a fresh identity for one human prompt (`requestId` on a session or subagent prompt).
 *
 * Not the same thing as [newRpcId], which names one HTTP call. This names the *message*: the host
 * persists it on the message the prompt is accepted as, so a retry of the same message must carry
 * the id it already had, and two different messages must never share one.
 */
fun newPromptRequestId(): String = randomUuid()

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
