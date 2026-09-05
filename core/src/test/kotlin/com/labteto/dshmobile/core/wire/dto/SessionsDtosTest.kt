package com.labteto.dshmobile.core.wire.dto

import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.decodeFromString
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attachment reference describes what the host *stored*, which since harness 0.1.1-rc.2 can
 * be a normalized re-encode of the upload. These tests pin that the pre-normalization size rides
 * along when present and that its absence stays an ordinary decode — and, since 0.1.3, that the
 * file vocabulary encodes the way the host's codecs expect it.
 */
class SessionsDtosTest {

    @Test
    fun `an attachment ref carries the upload's size when the host scaled it`() {
        val ref = decodeFromString<ImageAttachmentRef>(
            """{"attachmentId":"sha256:abc","mediaType":"image/webp","bytes":123456,
               "width":2048,"height":1536,
               "originalDimensions":{"width":8000,"height":6000}}""",
        )
        assertEquals(2048, ref.width)
        assertEquals(8000, ref.originalDimensions!!.width)
        assertEquals(6000, ref.originalDimensions!!.height)
    }

    @Test
    fun `a ref without originalDimensions decodes as before`() {
        val ref = decodeFromString<ImageAttachmentRef>(
            """{"attachmentId":"sha256:abc","mediaType":"image/png","bytes":10,
               "width":100,"height":100}""",
        )
        assertNull(ref.originalDimensions)
    }

    @Test
    fun `a file prompt part carries its receipt under the file discriminator`() {
        val parts = encodeToJsonElement(
            ListSerializer(PromptContentPart.serializer()),
            listOf(
                PromptContentPart.Text("see attached"),
                PromptContentPart.File(receiptId = "receipt-1"),
            ),
        ).jsonArray
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        val file = parts[1].jsonObject
        assertEquals("file", file["type"]!!.jsonPrimitive.content)
        assertEquals("receipt-1", file["receiptId"]!!.jsonPrimitive.content)
        // The bytes never ride the prompt: a receipt is all the host wants.
        assertEquals(setOf("type", "receiptId"), file.keys)
    }

    @Test
    fun `an upload receipt decodes with its stored file reference`() {
        val value = decodeFromString<FileUploadValue>(
            """{"receiptId":"r-1","file":{"attachmentId":"sha256:def","name":"notes.txt","bytes":42}}""",
        )
        assertEquals("r-1", value.receiptId)
        assertEquals("notes.txt", value.file.name)
        assertEquals(42L, value.file.bytes)
    }

    @Test
    fun `a follow request only names the assistant stream when it opts in`() {
        val plain = encodeToJsonElement(
            SessionFollowRequest.serializer(),
            SessionFollowRequest(address = SessionAddress.Session(sessionId = "s1")),
        ).jsonObject
        // The host declares the flag as `true | undefined`; an explicit false is not a value it
        // takes, so the key is absent rather than false.
        assertFalse(plain.containsKey("assistantStream"))

        val optedIn = encodeToJsonElement(
            SessionFollowRequest.serializer(),
            SessionFollowRequest(address = SessionAddress.Session(sessionId = "s1"), assistantStream = true),
        ).jsonObject
        assertTrue(optedIn["assistantStream"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `a follow snapshot carries the opted-in assistant baseline`() {
        val frame = WireJson.decodeFromString(
            SessionFollowFrameSerializer,
            """{"type":"snapshot","header":{"version":2,"id":"s1","createdAt":1,"isSeeded":false},
                "cursor":12,"records":[],"hasMore":false,"projections":{"asOfSeq":12,"values":{}},
                "assistantStream":{"revision":3,"activeAttempt":{"attemptId":"a1","startedAfterSeq":12,
                "turn":1,"step":1,"nextIndex":1,"stream":[{"type":"text-chunks","time0":1,"index":0,"dt":[],"texts":["hi"]}]}}}""",
        )
        val snapshot = frame as SessionFollowFrame.Snapshot
        assertEquals(12, snapshot.cursor)
        assertEquals(3, snapshot.assistantStream!!.revision)
        assertEquals("a1", snapshot.assistantStream!!.activeAttempt!!.attemptId)
        assertEquals(1, snapshot.assistantStream!!.activeAttempt!!.stream.size)
    }

    @Test
    fun `an assistant-stream item decodes to its inner frame`() {
        val chunk = WireJson.decodeFromString(
            SessionFollowFrameSerializer,
            """{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a1","revision":4,"index":0,"time":5,
                "chunk":{"type":"text-delta","index":0,"text":"hi"}}}""",
        ) as SessionFollowFrame.AssistantStream
        val inner = chunk.frame as SessionAssistantStreamFrame.Chunk
        assertEquals(0, inner.index)
        assertEquals(5L, inner.time)

        val end = WireJson.decodeFromString(
            SessionFollowFrameSerializer,
            """{"type":"assistant-stream","frame":{"type":"end","attemptId":"a1","revision":5,"index":1,
                "outcome":{"kind":"committed","eventType":"assistant/message","seq":13}}}""",
        ) as SessionFollowFrame.AssistantStream
        val outcome = (end.frame as SessionAssistantStreamFrame.End).outcome as AssistantStreamOutcome.Committed
        assertEquals(13, outcome.seq)

        // A record class this build has never heard of is still an event, never a frame.
        val record = WireJson.decodeFromString(
            SessionFollowFrameSerializer,
            """{"type":"future","event":{"type":"turn/start","seq":14,"time":1,"data":{"turn":2}}}""",
        )
        assertTrue(record is SessionFollowFrame.Entry)
        assertEquals(14, (record as SessionFollowFrame.Entry).record.event.seq)
    }
}
