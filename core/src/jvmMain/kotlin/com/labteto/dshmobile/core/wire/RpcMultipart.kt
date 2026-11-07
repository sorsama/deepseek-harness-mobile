package com.labteto.dshmobile.core.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartReader
import okio.Buffer

/**
 * A unary answer whose value carries raw bytes, harness 0.1.7's multipart response.
 *
 * A Remote whose result holds a byte array (`workspaceFiles/readBytes`, `officeToPdf/render`) is
 * no longer answered as JSON with base64 inside. The response is `multipart/form-data`: a
 * `metadata` part holding the ordinary response envelope, with `null` at every place a byte array
 * sat, plus one `bytes-N` part per array. The envelope's `attachments` list says which part goes
 * where:
 *
 *     { type: "server-response", rpcId, result: { ok: true, value: { …, data: null } },
 *       attachments: [ { path: ["data"], codec: "bytes", part: "bytes-0" } ] }
 *
 * (`packages/client/connection/src/rpc-host.ts`, `fullResponse`.) Only a successful result is
 * ever multipart; a failure is the usual JSON envelope.
 */
data class BinaryRpcValue(
    /** The result value with each byte array's slot left as the `null` the host wrote there. */
    val value: JsonElement,
    /** Each attachment's bytes, keyed by its path into [value]. */
    val attachments: Map<List<String>, ByteArray>,
) {
    /** The bytes at [path], e.g. `bytes("data")`, or null when the value carried none there. */
    fun bytes(vararg path: String): ByteArray? = attachments[path.toList()]
}

/** Decoder for the multipart response shape described on [BinaryRpcValue]. */
object RpcMultipart {

    /** Whether a response of this `Content-Type` is the multipart shape rather than JSON. */
    fun isMultipart(contentType: String?): Boolean =
        contentType?.trimStart()?.startsWith("multipart/", ignoreCase = true) == true

    /** One multipart response, split into its envelope and its attachments. */
    class Decoded(
        /** The `metadata` part's envelope, with its `attachments` list removed. */
        val envelope: JsonObject,
        val attachments: Map<List<String>, ByteArray>,
    )

    /**
     * Split a multipart body into its envelope and attachments.
     *
     * Throws [IllegalArgumentException] when the body is not the shape above: no boundary, no
     * `metadata` part, or an attachment naming a part that is not there. The unary machinery
     * reports that as "not a harness" like any other unreadable answer.
     */
    fun decode(contentType: String, body: ByteArray): Decoded {
        val boundary = contentType.toMediaTypeOrNull()?.parameter("boundary")
            ?: throw IllegalArgumentException("multipart response carries no boundary")
        val parts = linkedMapOf<String, ByteArray>()
        MultipartReader(Buffer().write(body), boundary).use { reader ->
            while (true) {
                val part = reader.nextPart() ?: break
                val name = partName(part.headers["Content-Disposition"])
                val bytes = part.body.readByteArray()
                if (name != null) parts[name] = bytes
            }
        }
        val metadata = parts["metadata"]
            ?: throw IllegalArgumentException("multipart response has no metadata part")
        val envelope = WireJson.parseToJsonElement(metadata.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("multipart metadata is not an object")
        val attachments = (envelope["attachments"] as? JsonArray).orEmpty().associate { raw ->
            val attachment = raw as? JsonObject
                ?: throw IllegalArgumentException("multipart attachment is not an object")
            val partName = attachment["part"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("multipart attachment names no part")
            val path = (attachment["path"] as? JsonArray).orEmpty().map { segment ->
                val primitive = segment.jsonPrimitive
                primitive.intOrNull?.toString() ?: primitive.content
            }
            path to (parts[partName] ?: throw IllegalArgumentException("multipart part $partName is missing"))
        }
        return Decoded(JsonObject(envelope - "attachments"), attachments)
    }

    /** The `name` parameter of a `Content-Disposition: form-data; name="…"` header. */
    private fun partName(disposition: String?): String? {
        if (disposition == null) return null
        return Regex("""(?:^|;)\s*name\s*=\s*(?:"([^"]*)"|([^;\s]+))""", RegexOption.IGNORE_CASE)
            .find(disposition)
            ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
    }
}
