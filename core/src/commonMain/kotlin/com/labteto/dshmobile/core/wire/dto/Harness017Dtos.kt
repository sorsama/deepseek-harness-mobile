@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.encodeToJsonElement

/**
 * Shapes harness 0.1.7 added to the browser surface, transcribed from the rc.2 sources named on
 * each type (tag `dsh-v0.1.7-rc.2`, `477b4f4`). These shapes are unchanged at `dsh-v0.2.0-rc.1`.
 */

// ------------------------------------------------------------------ jobs (`packages/api/job-controller`)

/** Request of the `job/list` stream. */
@Serializable
data class JobListRequest(@SerialName("sessionId") val sessionId: String)

/**
 * One `job/list` frame: every job the session can see, its own plus unowned ones, as a whole-set
 * replacement. Rows are decoded one at a time by the reader, so a row this build cannot read costs
 * that row rather than the list.
 */
@Serializable
data class JobListFrame(
    @SerialName("type") val type: String = "rows",
    @SerialName("jobs") val jobs: List<JsonElement> = emptyList(),
)

/** Request of the `job/follow` stream. `from` resumes at a prior frame's `next`. */
@Serializable
data class JobFollowRequest(
    @SerialName("jobId") val jobId: String,
    @SerialName("sessionId") val sessionId: String? = null,
    @SerialName("from") val from: Long? = null,
)

/** One chunk of a job's retained output. `at` is an absolute byte offset that never moves. */
@Serializable
data class JobChunk(
    @SerialName("at") val at: Long,
    @SerialName("text") val text: String,
    /** `stdout`, `stderr` or `log`; an unrecognised label reads like an absent one. */
    @SerialName("channel") val channel: String? = null,
    /** Bytes immediately before this chunk were lost, at the producer or to retention. */
    @SerialName("gapBefore") val gapBefore: Boolean = false,
)

/**
 * One frame of `job/follow`: an `opened` anchor, coalesced `output` batches, then one terminal
 * `status` once the job has settled and its output drained, after which the stream ends.
 */
@Serializable(with = JobFollowFrameSerializer::class)
sealed class JobFollowFrame {
    abstract val type: String

    @Serializable
    data class Opened(
        @SerialName("type") override val type: String = "opened",
        @SerialName("job") val job: JobView,
        @SerialName("from") val from: Long = 0,
    ) : JobFollowFrame()

    @Serializable
    data class Output(
        @SerialName("type") override val type: String = "output",
        @SerialName("chunks") val chunks: List<JobChunk> = emptyList(),
        @SerialName("next") val next: Long = 0,
        /** Bytes between the requested offset and [chunks] were already evicted. */
        @SerialName("lossy") val lossy: Boolean = false,
    ) : JobFollowFrame()

    @Serializable
    data class Status(
        @SerialName("type") override val type: String = "status",
        @SerialName("job") val job: JobView,
    ) : JobFollowFrame()

    /** A frame of a type this build does not know, kept verbatim. */
    data class Unknown(override val type: String, val raw: JsonElement) : JobFollowFrame()
}

/** `type`-dispatching serializer for [JobFollowFrame]; an unknown type is kept, not failed. */
object JobFollowFrameSerializer : KSerializer<JobFollowFrame> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("JobFollowFrame") {
        element("type", buildSerialDescriptor("kotlin.String", PrimitiveKind.STRING))
    }

    override fun serialize(encoder: Encoder, value: JobFollowFrame) {
        val json: JsonElement = when (value) {
            is JobFollowFrame.Opened -> encodeToJsonElement(JobFollowFrame.Opened.serializer(), value)
            is JobFollowFrame.Output -> encodeToJsonElement(JobFollowFrame.Output.serializer(), value)
            is JobFollowFrame.Status -> encodeToJsonElement(JobFollowFrame.Status.serializer(), value)
            is JobFollowFrame.Unknown -> value.raw
        }
        (encoder as JsonEncoder).encodeJsonElement(json)
    }

    override fun deserialize(decoder: Decoder): JobFollowFrame {
        val json = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when (val type = json["type"]?.jsonPrimitive?.contentOrNull ?: "") {
            "opened" -> decodeFromJsonElement(JobFollowFrame.Opened.serializer(), json)
            "output" -> decodeFromJsonElement(JobFollowFrame.Output.serializer(), json)
            "status" -> decodeFromJsonElement(JobFollowFrame.Status.serializer(), json)
            else -> JobFollowFrame.Unknown(type, json)
        }
    }
}

/** Request of `job/kill`. */
@Serializable
data class JobKillRequest(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("jobId") val jobId: String,
)

/** Value of `job/kill`: `requested`, or `already-finished` when the job settled first. */
@Serializable
data class JobKillValue(@SerialName("outcome") val outcome: String)

// ------------------------------------------------------------------ workspace (`packages/api/workspace-controller`)

/** Request of `workspace/pinSession` and `workspace/unpinSession`. */
@Serializable
data class WorkspacePinSessionRequest(@SerialName("sessionId") val sessionId: String)

/** The complete pin set after a pin mutation, most recently pinned first. */
@Serializable
data class WorkspacePinValue(
    @SerialName("pinnedSessionIds") val pinnedSessionIds: List<String> = emptyList(),
)

/** One family of running work that kept a session from being archived. */
@Serializable
data class SessionActivity(
    /** `turn`, `subagent`, `job` or `schedule`; a family this build does not know is still shown. */
    @SerialName("kind") val kind: String,
    /** Items of the family, when it has per-item identity (`turn` has none). */
    @SerialName("items") val items: List<SessionActivityItem> = emptyList(),
)

/** One active item: a subagent session, a job, a schedule. */
@Serializable
data class SessionActivityItem(
    @SerialName("id") val id: String,
    @SerialName("label") val label: String? = null,
)

/** Details of a `workspace/session-active` refusal. */
@Serializable
data class WorkspaceSessionActiveDetails(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("activity") val activity: List<SessionActivity> = emptyList(),
)

// ------------------------------------------------------------------ files (`packages/api/workspace-files`)

/**
 * The `options` argument of `workspaceFiles/readBytes`: omit [range] for the whole file, and set
 * [baseFile] to resolve a relative path from that file's directory.
 */
@Serializable
data class WorkspaceByteReadOptions(
    @SerialName("range") val range: WorkspaceByteRange? = null,
    @SerialName("baseFile") val baseFile: String? = null,
)

/** The JSON half of a `readBytes` value; the bytes arrive beside it (see `RpcMultipart`). */
@Serializable
data class WorkspaceFileBytesMeta(
    @SerialName("absolutePath") val absolutePath: String,
    @SerialName("version") val version: String,
    /** The complete file's size, when the backend reports one. */
    @SerialName("bytes") val bytes: Long? = null,
    @SerialName("offset") val offset: Long = 0,
    @SerialName("eof") val eof: Boolean = true,
)

/** A `readBytes` result with its bytes decoded, from either harness generation. */
class WorkspaceFileContent(
    val absolutePath: String,
    val version: String,
    /** The complete file's size, when the backend reports one. */
    val bytes: Long?,
    val offset: Long,
    val eof: Boolean,
    val data: ByteArray,
)

/**
 * The rows of one `job/list` frame, each decoded on its own so a row this build cannot read costs
 * that row alone. Null when [frame] is not a `rows` frame.
 */
fun jobRowsOf(frame: JsonElement): List<JobView>? {
    val decoded = runCatching { decodeFromJsonElement(JobListFrame.serializer(), frame) }.getOrNull() ?: return null
    if (decoded.type != "rows") return null
    return decoded.jobs.mapNotNull { row -> runCatching { decodeFromJsonElement(JobView.serializer(), row) }.getOrNull() }
}
