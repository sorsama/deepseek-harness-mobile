@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.labteto.dshmobile.core.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The four wire full-form RPC message envelopes plus the result/error vocabulary, ported from
 * `packages/host/apiproxy/src/api/rpc.ts` and `rpc.schema.ts` (v0.1.0-rc.8).
 *
 * Wire forms:
 * - [ClientRequest]  — POST /api/<method> body (`type: "client-request"`).
 * - [ServerResponse] — the HTTP response body of that POST (`type: "server-response"`).
 * - [ServerRequest]  — one downstream stream frame (`type: "server-request"`).
 * - [ClientResponse] — POST /api/respond body (`type: "client-response"`).
 */

/** RPC error body: `code` (one of the documented RpcError codes), `message`, required `details`. */
@Serializable
data class RpcError(
    @SerialName("code") val code: String,
    @SerialName("message") val message: String,
    @SerialName("details") val details: JsonElement = JsonObject(emptyMap()),
)

/**
 * Business success/failure result: the `result` slot of a response envelope.
 * `ok: true` carries `value`; `ok: false` carries `error`. The type parameter is covariant —
 * errors are valid for any value type.
 */
sealed class RpcResult<out T> {
    /** Business success. */
    data class Ok<T>(val value: T) : RpcResult<T>()

    /** Business failure; the carrier delivered a well-formed error result. */
    data class Err(val error: RpcError) : RpcResult<Nothing>()
}

/** Wire result serializer for [RpcResult] of [JsonElement] — the shape used by every envelope. */
object RpcResultJsonSerializer : KSerializer<RpcResult<JsonElement>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RpcResult") {
        element("ok", buildSerialDescriptor("kotlin.Boolean", PrimitiveKind.BOOLEAN))
        element("value", buildSerialDescriptor("kotlin.Any", SerialKind.CONTEXTUAL), isOptional = true)
        element("error", RpcError.serializer().descriptor, isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: RpcResult<JsonElement>) {
        val json = when (value) {
            is RpcResult.Ok -> buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("value", value.value)
            }
            is RpcResult.Err -> buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("error", encodeToJsonElement(RpcError.serializer(), value.error))
            }
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): RpcResult<JsonElement> {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        val ok = json["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        return if (ok) {
            // A void business result may omit the value slot entirely; an absent value reads
            // as the empty object so empty-object value DTOs decode cleanly.
            RpcResult.Ok(json["value"] ?: JsonObject(emptyMap()))
        } else {
            val error = json["error"]?.let { decodeFromJsonElement(RpcError.serializer(), it) }
                ?: RpcError("internal", "server response carried no error details", JsonObject(emptyMap()))
            RpcResult.Err(error)
        }
    }
}

/** Call initiated by the client (wire carrier: POST /api/<method> body). */
@Serializable
data class ClientRequest(
    @SerialName("type") val type: String = "client-request",
    @SerialName("rpcId") val rpcId: String,
    @SerialName("method") val method: String,
    @SerialName("payload") val payload: JsonElement,
)

/** Response to a ClientRequest (wire carrier: the HTTP response body of that POST); rpcId echoed. */
@Serializable
data class ServerResponse(
    @SerialName("type") val type: String = "server-response",
    @SerialName("rpcId") val rpcId: String,
    @SerialName("result")
    @Serializable(with = RpcResultJsonSerializer::class)
    val result: RpcResult<JsonElement>,
)

/** Message initiated by the server (wire carrier: downstream stream frame). */
@Serializable
data class ServerRequest(
    @SerialName("type") val type: String = "server-request",
    @SerialName("rpcId") val rpcId: String,
    @SerialName("method") val method: String,
    @SerialName("payload") val payload: JsonElement,
)

/** Response to a ServerRequest (wire carrier: POST /api/respond body); rpcId echoed, never minted anew. */
@Serializable
data class ClientResponse(
    @SerialName("type") val type: String = "client-response",
    @SerialName("rpcId") val rpcId: String,
    @SerialName("result")
    @Serializable(with = RpcResultJsonSerializer::class)
    val result: RpcResult<JsonElement>,
)

/** Carrier receipt (the HTTP response body of the POST carrying a client-response). */
@Serializable
data class RpcReceipt(
    @SerialName("accepted") val accepted: Boolean,
    /** Present only when `accepted` is false: 'not-pending' | 'bad-response'. */
    @SerialName("reason") val reason: String? = null,
)
