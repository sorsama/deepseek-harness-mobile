@file:OptIn(
    kotlinx.serialization.InternalSerializationApi::class,
)

package com.labteto.dshmobile.core.wire.dto

import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Subagents-domain DTOs, ported from `packages/host/apiproxy/src/api/subagents.schema.ts` and
 * `packages/host/apiproxy/src/api/subagents.ts` (v0.1.0-rc.7).
 */

/**
 * Healthy and diagnostic durable catalog rows (`subagent.list`).
 *
 * The union discriminates on `kind` first ('child' | 'diagnostic'); within `child`, `mode`
 * splits one-shot from continuable (the harness sub-discriminant). A custom serializer ports
 * that two-level dispatch exactly; unknown kinds fall back to [UnknownSubagentListEntry].
 */
@Serializable(with = SubagentListEntrySerializer::class)
sealed class SubagentListEntry {
    /** The wire `kind` discriminant. */
    abstract val kind: String

    /** A healthy one-shot child (`kind: 'child'`, `mode: 'one-shot'`). */
    @Serializable
    data class ChildOneShot(
        @SerialName("id") val id: String,
        /** Whether the child Agent driver is running at the Host sampling boundary. */
        @SerialName("activity") val activity: String,
        /** Whether a direct descendant has durable `origin: 'subagent'`. */
        @SerialName("hasChildren") val hasChildren: Boolean,
        @SerialName("mode") val mode: String = "one-shot",
        @SerialName("label") val label: String? = null,
    ) : SubagentListEntry() {
        override val kind: String get() = "child"
    }

    /** A healthy continuable child (`kind: 'child'`, `mode: 'continuable'`). */
    @Serializable
    data class ChildContinuable(
        @SerialName("id") val id: String,
        @SerialName("activity") val activity: String,
        @SerialName("hasChildren") val hasChildren: Boolean,
        @SerialName("mode") val mode: String = "continuable",
        @SerialName("label") val label: String,
    ) : SubagentListEntry() {
        override val kind: String get() = "child"
    }

    /** A durable row whose transcript cannot be classified by this runtime. */
    @Serializable
    data class Diagnostic(
        @SerialName("id") val id: String,
        @SerialName("reason") val reason: String,
    ) : SubagentListEntry() {
        override val kind: String get() = "diagnostic"
    }
}

/** A catalog row of an unknown `kind`; the complete raw row is preserved. */
data class UnknownSubagentListEntry(
    override val kind: String,
    val raw: JsonElement,
) : SubagentListEntry()

/** Custom two-level (kind → mode) serializer for [SubagentListEntry]. */
object SubagentListEntrySerializer : KSerializer<SubagentListEntry> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SubagentListEntry") {
        element("kind", String.serializer().descriptor)
        element("id", String.serializer().descriptor, isOptional = true)
        element("mode", String.serializer().descriptor, isOptional = true)
        element("reason", String.serializer().descriptor, isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: SubagentListEntry) {
        val json: JsonElement = when (value) {
            is SubagentListEntry.ChildOneShot ->
                encodeToJsonElement(SubagentListEntry.ChildOneShot.serializer(), value).withKind(value.kind)
            is SubagentListEntry.ChildContinuable ->
                encodeToJsonElement(SubagentListEntry.ChildContinuable.serializer(), value).withKind(value.kind)
            is SubagentListEntry.Diagnostic ->
                encodeToJsonElement(SubagentListEntry.Diagnostic.serializer(), value).withKind(value.kind)
            is UnknownSubagentListEntry -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): SubagentListEntry {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        val kind = json["kind"]?.jsonPrimitive?.contentOrNull ?: ""
        return when (kind) {
            "child" -> {
                val mode = json["mode"]?.jsonPrimitive?.contentOrNull
                if (mode == "continuable") {
                    decodeFromJsonElement(SubagentListEntry.ChildContinuable.serializer(), json)
                } else {
                    decodeFromJsonElement(SubagentListEntry.ChildOneShot.serializer(), json)
                }
            }
            "diagnostic" -> decodeFromJsonElement(SubagentListEntry.Diagnostic.serializer(), json)
            else -> UnknownSubagentListEntry(kind, json)
        }
    }
}

/**
 * Concrete serializers omit the computed [SubagentListEntry.kind]; restore it for the wire.
 * `mode` distinguishes child subtypes and survives because `WireJson` encodes defaults.
 */
private fun JsonElement.withKind(kind: String): JsonElement = JsonObject(
    linkedMapOf<String, JsonElement>().apply {
        putAll(this@withKind.jsonObject)
        put("kind", JsonPrimitive(kind))
    },
)

/** Complete direct-child catalog plus the delivery-time parent availability hint. */
@Serializable
data class SubagentCatalog(
    @SerialName("entries") val entries: List<SubagentListEntry> = emptyList(),
    @SerialName("parentAvailable") val parentAvailable: Boolean,
)

/** Durable parent/child address that selects subagent transport in the client. */
@Serializable
data class SubagentAddress(
    @SerialName("parentSessionId") val parentSessionId: String,
    @SerialName("childSessionId") val childSessionId: String,
    /** 'one-shot' | 'continuable'. */
    @SerialName("mode") val mode: String,
)

/** Request payload of `subagent.list`. */
@Serializable
data class SubagentListRequest(
    @SerialName("parentSessionId") val parentSessionId: String,
)

/** Request payload of `subagent.history`. */
@Serializable
data class SubagentHistoryRequest(
    @SerialName("parentSessionId") val parentSessionId: String,
    @SerialName("childSessionId") val childSessionId: String,
    @SerialName("mode") val mode: String,
    @SerialName("beforeSeq") val beforeSeq: Int? = null,
    @SerialName("maxMessages") val maxMessages: Int? = null,
)

/** Value of `subagent.history`. */
@Serializable
data class SubagentHistoryValue(
    @SerialName("events") val events: List<HistoryEntry> = emptyList(),
    @SerialName("hasMore") val hasMore: Boolean,
    @SerialName("projections") val projections: SessionProjectionsBlock? = null,
)

/** Request payload of `subagent.prompt` (continuable children only). */
@Serializable
data class SubagentPromptRequest(
    @SerialName("parentSessionId") val parentSessionId: String,
    @SerialName("childSessionId") val childSessionId: String,
    @SerialName("mode") val mode: String = "continuable",
    @SerialName("content") val content: List<ContentBlock> = emptyList(),
    /** Optional browser zone sampled for this exact human prompt. */
    @SerialName("clientTimeZone") val clientTimeZone: String? = null,
)

/** Value of `subagent.prompt` (the inbox receipt). */
@Serializable
data class SubagentPromptValue(
    @SerialName("messageId") val messageId: String,
)

/** Request payload of `subagent.interrupt` (continuable children only). */
@Serializable
data class SubagentInterruptRequest(
    @SerialName("parentSessionId") val parentSessionId: String,
    @SerialName("childSessionId") val childSessionId: String,
    @SerialName("mode") val mode: String = "continuable",
)

/** Value of `subagent.interrupt`. */
@Serializable
data class SubagentInterruptValue(
    @SerialName("accepted") val accepted: Boolean = true,
)
