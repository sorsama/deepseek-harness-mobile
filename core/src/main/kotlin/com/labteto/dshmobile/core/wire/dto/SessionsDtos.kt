package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Sessions-domain DTOs, ported from `packages/api/session-controller/src/types.ts`,
 * `packages/attachment/attachment/src/types.ts` and `packages/client/file-upload/src/types.ts`
 * (v0.1.3-alpha.1). Includes the request/response values of the `session` namespace methods
 * (list/search/create/rename/fork/selectModel/prompt/attachment/updateQueue/cancel), the
 * model-catalog shapes, and the image and file attachment shapes.
 */

/** One Session list entry. */
@Serializable
data class SessionSummary(
    @SerialName("sessionId") val sessionId: String,
    /** The later of creation and the latest human-authored prompt (epoch ms). */
    @SerialName("updatedAt") val updatedAt: Long,
    /** Status of the attached agent; always false for cold (unattached) sessions. */
    @SerialName("running") val running: Boolean,
    /** Derived conversation-not-started bit: true while no turn has run. */
    @SerialName("blank") val blank: Boolean,
    /** fork/spawn lineage; absent for root sessions. */
    @SerialName("parentSessionId") val parentSessionId: String? = null,
    /** Coarse durable origin used by navigation surfaces; never proves resumability. */
    @SerialName("origin") val origin: String? = null,
    /** Session working directory (header.cwd passthrough); absent when unrecorded. */
    @SerialName("cwd") val cwd: String? = null,
    /** Agent preset this session's agent was composed from; absent when unset. */
    @SerialName("agentPreset") val agentPreset: String? = null,
    /** Projection baseline for this row; absent when no value is available. */
    @SerialName("projections") val projections: SessionProjectionsBlock? = null,
)

/** The projection baseline block riding history tail pages and list rows. */
@Serializable
data class SessionProjectionsBlock(
    /** Seq of the last event the values reflect; -1 for an empty log. */
    @SerialName("asOfSeq") val asOfSeq: Int,
    /** Whole current value per registered projection key. */
    @SerialName("values") val values: Map<String, JsonElement> = emptyMap(),
)

/**
 * The `modelSelection` projection: what this session last used, and what it will use next.
 *
 * Harness 0.1.2 moved the per-session selection out of the model listing and into a durable
 * projection, because a catalog describes the host generation while a selection describes one
 * session. `next` is a chosen-but-not-yet-used selection; it clears once a request commits with
 * it, so the session's effective choice is `next` when present and `lastUsed` otherwise.
 */
@Serializable
data class ModelSelectionProjection(
    /** The selection the most recent request actually used; absent before the first turn. */
    @SerialName("lastUsed") val lastUsed: ModelSelection? = null,
    /** A selection chosen for the next request; absent when the last used one still stands. */
    @SerialName("next") val next: ModelSelection? = null,
)

/** Complete provider/model selection for one session. */
@Serializable
data class ModelSelection(
    /** Registered provider route. */
    @SerialName("provider") val provider: String,
    /** Provider-owned model id. */
    @SerialName("model") val model: String,
    /** Adapter-owned reasoning effort; absence preserves adapter/provider default behavior. */
    @SerialName("reasoningEffort") val reasoningEffort: String? = null,
)

/** One adapter-owned reasoning effort displayed for an exact model route. */
@Serializable
data class ModelReasoningEffort(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String? = null,
)

/** Selectable reasoning metadata for one exact model route. */
@Serializable
data class ModelReasoning(
    /** Efforts in adapter-preferred display order. */
    @SerialName("efforts") val efforts: List<ModelReasoningEffort> = emptyList(),
    /** Adapter-configured default; absence preserves the provider default. */
    @SerialName("defaultEffort") val defaultEffort: String? = null,
)

/** One model displayed inside its provider group. */
@Serializable
data class ModelCatalogModel(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String? = null,
    @SerialName("reasoning") val reasoning: ModelReasoning? = null,
)

/** One provider and the models it advertised successfully. */
@Serializable
data class ModelProviderGroup(
    /** Provider route id used for requests. */
    @SerialName("id") val id: String,
    /** Provider display name. */
    @SerialName("name") val name: String,
    /** Models in provider-preferred order. */
    @SerialName("models") val models: List<ModelCatalogModel> = emptyList(),
)

/** A provider whose asynchronous catalog lookup failed. */
@Serializable
data class ModelCatalogFailure(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("message") val message: String,
)

/** Detached model-directory snapshot for one session (`session.models` value). */
@Serializable
data class SessionModelsValue(
    /** Model selection for the session's next assembled step. */
    @SerialName("current") val current: ModelSelection,
    /** Whether an adapter currently serves `current.provider` (can this session start a turn). */
    @SerialName("routable") val routable: Boolean,
    /** Successfully loaded provider groups. */
    @SerialName("groups") val groups: List<ModelProviderGroup> = emptyList(),
    /** Provider-local failures; successful groups remain usable. */
    @SerialName("failures") val failures: List<ModelCatalogFailure> = emptyList(),
)

/**
 * Browser-submitted prompt content; the host promotes image bytes to durable references.
 *
 * A [File] part carries the opaque receipt a preceding upload on the same session returned
 * (harness 0.1.3; see `DshApiClient.uploadFileBinary`). The bytes never ride the prompt: the
 * host resolves the receipt back to the stored file and refuses one it did not mint for this
 * session, so a wire caller can never cite an attachment it did not upload.
 */
@Serializable
sealed class PromptContentPart {
    @Serializable
    @SerialName("text")
    data class Text(
        @SerialName("text") val text: String,
    ) : PromptContentPart()

    @Serializable
    @SerialName("image")
    data class Image(
        /** Raster image media type accepted by the version-one browser wire. */
        @SerialName("mediaType") val mediaType: String,
        /** Raw image bytes (base64) as submitted by the browser. */
        @SerialName("data") val data: String,
        @SerialName("name") val name: String? = null,
    ) : PromptContentPart()

    @Serializable
    @SerialName("file")
    data class File(
        @SerialName("receiptId") val receiptId: String,
    ) : PromptContentPart()
}

/** A client-requested mutation of one still-pending queue item. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("kind")
sealed class QueueAction {
    @Serializable
    @SerialName("edit")
    data class Edit(
        @SerialName("content") val content: List<ContentBlock> = emptyList(),
    ) : QueueAction()

    @Serializable
    @SerialName("remove")
    class Remove : QueueAction()

    @Serializable
    @SerialName("steer")
    class Steer : QueueAction()
}

// ---- session.* request payloads ----

/** Request payload of `session.list` (cursor is a reserved seat, unimplemented in v1). */
@Serializable
data class SessionListRequest(
    @SerialName("cursor") val cursor: String? = null,
)

/** Request payload of `session.search`. */
@Serializable
data class SessionSearchRequest(
    @SerialName("query") val query: String,
)

/** One session-content search result. */
@Serializable
data class SessionSearchItem(
    @SerialName("sessionId") val sessionId: String,
    /** Plain-text excerpt around the strongest matching visible message. */
    @SerialName("snippet") val snippet: String,
)

/** Request payload of `session.create` (at most one of workspaceId / cwd). */
@Serializable
data class SessionCreateRequest(
    @SerialName("workspaceId") val workspaceId: String? = null,
    @SerialName("cwd") val cwd: String? = null,
    @SerialName("sessionId") val sessionId: String? = null,
    @SerialName("agentPreset") val agentPreset: String? = null,
)

/** Request payload of `session.rename`. */
@Serializable
data class SessionRenameRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("title") val title: String,
)

/** Request payload of `session.fork` (atSeq anchors the completed-turn cut). */
@Serializable
data class SessionForkRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("atSeq") val atSeq: Int? = null,
)

/** Request payload of `session.models`. */
@Serializable
data class SessionModelsRequest(
    @SerialName("sessionId") val sessionId: String,
)

/** Request payload of `session.selectModel`. */
@Serializable
data class SessionSelectModelRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("provider") val provider: String,
    @SerialName("model") val model: String,
    @SerialName("reasoningEffort") val reasoningEffort: String? = null,
)

/** Request payload of `session.prompt`. */
@Serializable
data class SessionPromptRequest(
    /**
     * Identity of this one human message, minted by the sender before the call. Host-required.
     *
     * The host persists it on the exact message the prompt is accepted as, which is what lets a
     * sender match its own optimistic echo to the message that came back. It is per *message*,
     * not per call: a resend of the same message keeps its id, and a new message never reuses one.
     */
    @SerialName("requestId") val requestId: String,
    @SerialName("sessionId") val sessionId: String,
    /** mode maps 1:1 — queue→send, steer→steer. */
    @SerialName("mode") val mode: String,
    @SerialName("content") val content: List<PromptContentPart> = emptyList(),
    /** Optional browser zone sampled for this exact human prompt. */
    @SerialName("clientTimeZone") val clientTimeZone: String? = null,
)

/** A raster size in pixels, as `ImageAttachmentRef.originalDimensions` carries it. */
@Serializable
data class ImageDimensions(
    @SerialName("width") val width: Int,
    @SerialName("height") val height: Int,
)

/**
 * Durable image reference returned from the authenticated session lookup.
 *
 * Since harness 0.1.1-rc.2 the host normalizes images on ingest, so [mediaType], [bytes],
 * [width] and [height] describe the *stored* image, which may be a re-encode of what the
 * client uploaded — and [attachmentId] is the digest of those normalized bytes, never of
 * the upload.
 */
@Serializable
data class ImageAttachmentRef(
    /** Opaque storage identifier; never a filesystem path or bearer URL. */
    @SerialName("attachmentId") val attachmentId: String,
    /** Media type verified from the stored bytes. */
    @SerialName("mediaType") val mediaType: String,
    /** Exact encoded byte length. */
    @SerialName("bytes") val bytes: Int,
    /** Intrinsic encoded width in pixels. */
    @SerialName("width") val width: Int,
    /** Intrinsic encoded height in pixels. */
    @SerialName("height") val height: Int,
    /** Optional display name stripped of local path information. */
    @SerialName("name") val name: String? = null,
    /** Pixel size of the upload before the host normalized it; absent when nothing was scaled. */
    @SerialName("originalDimensions") val originalDimensions: ImageDimensions? = null,
)

/**
 * Durable, serializable reference to one verbatim stored file (harness 0.1.3).
 *
 * Files are stored byte-for-byte with no normalization, unlike images; [attachmentId] is the
 * sha256 digest of exactly those bytes and [name] the sanitized display filename, which is also
 * the stored object's leaf name.
 */
@Serializable
data class FileAttachmentRef(
    /** Opaque content-addressed storage identifier; never a filesystem path or bearer URL. */
    @SerialName("attachmentId") val attachmentId: String,
    @SerialName("name") val name: String,
    /** Exact byte length. */
    @SerialName("bytes") val bytes: Long,
)

/**
 * Durable receipt for one staged file upload — the value of both upload routes.
 *
 * [receiptId] is a per-upload authority accepted only inside the session it was minted for, and
 * only until the prompt that cites it is observed; a receipt is spent by one message.
 */
@Serializable
data class FileUploadValue(
    @SerialName("receiptId") val receiptId: String,
    @SerialName("file") val file: FileAttachmentRef,
)

/** The `fileUploads/upload` Remote's request: canonical base64 of the exact file bytes. */
@Serializable
data class EncodedFileUploadRequest(
    @SerialName("data") val data: String,
    /** Optional display name; the host sanitizes it into the stored leaf name. */
    @SerialName("name") val name: String? = null,
)

/** Request payload of `session.attachment`. */
@Serializable
data class SessionAttachmentRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("attachmentId") val attachmentId: String,
)

/** Request payload of `session.updateQueue`. */
@Serializable
data class SessionUpdateQueueRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("itemId") val itemId: String,
    @SerialName("action") val action: QueueAction,
)

/** Request payload of `session.cancel`. */
@Serializable
data class SessionCancelRequest(
    @SerialName("sessionId") val sessionId: String,
)

// ---- session.* response values ----

/** Value of `session.list`. */
@Serializable
data class SessionListValue(
    @SerialName("items") val items: List<SessionSummary> = emptyList(),
)

/** Value of `session.search`. */
@Serializable
data class SessionSearchValue(
    @SerialName("items") val items: List<SessionSearchItem> = emptyList(),
    @SerialName("hasMore") val hasMore: Boolean,
)

/** Value of `session.create`. */
@Serializable
data class SessionCreateValue(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("agentPreset") val agentPreset: String? = null,
)

/** Value of `session.rename` (the normalized accepted title and its event seq). */
@Serializable
data class SessionRenameValue(
    @SerialName("title") val title: String,
    @SerialName("seq") val seq: Int,
)

/** Value of `session.fork` (the child session id). */
@Serializable
data class SessionForkValue(
    @SerialName("sessionId") val sessionId: String,
)

/** Value of `session.selectModel`. */
@Serializable
data class SessionSelectModelValue(
    @SerialName("selected") val selected: ModelSelection,
)

/**
 * The command slot the `session.prompt` schema still declares.
 *
 * Dead on arrival: the host's prompt handler never inspects the content for a leading slash and
 * therefore never fills this in. Commands are executed through the `commands/execute` remote (see
 * `docs/PROTOCOL.md`). Kept only so the schema still decodes if a host ever populates it.
 */
@Serializable
data class PromptCommand(
    @SerialName("kind") val kind: String = "success",
    @SerialName("text") val text: String? = null,
)

/** Value of `session.prompt`. [command] is always absent in practice — see [PromptCommand]. */
@Serializable
data class SessionPromptValue(
    @SerialName("accepted") val accepted: Boolean = true,
    @SerialName("command") val command: PromptCommand? = null,
)

/** Value of `session.attachment`. */
@Serializable
data class SessionAttachmentValue(
    @SerialName("attachment") val attachment: ImageAttachmentRef,
    /** Raw image bytes (base64). */
    @SerialName("data") val data: String,
)

/** Value of `session.updateQueue`. */
@Serializable
data class SessionUpdateQueueValue(
    @SerialName("accepted") val accepted: Boolean = true,
)

/** Value of `session.cancel`. */
@Serializable
data class SessionCancelValue(
    @SerialName("accepted") val accepted: Boolean = true,
)

/**
 * Value of `session/modelCatalog` — every currently routable model for a host generation.
 *
 * Replaces both `session.models` and `llm.models`. It is not session-specific despite living in
 * the session namespace: the catalog belongs to the host generation, and a per-session current
 * selection is read from that session's projections instead.
 */
@Serializable
data class ModelCatalog(
    /** The deployment default applied when a new agent names no model. */
    @SerialName("default") val default: ModelSelection,
    /** Provider routes currently able to serve a request, including those with empty catalogs. */
    @SerialName("routableProviders") val routableProviders: List<String> = emptyList(),
    @SerialName("groups") val groups: List<ModelProviderGroup> = emptyList(),
    /** Providers whose catalog lookup failed; isolated so one bad route does not empty the list. */
    @SerialName("failures") val failures: List<ModelCatalogFailure> = emptyList(),
)
