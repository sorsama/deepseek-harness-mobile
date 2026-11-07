package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.dto.SessionHistoryRecord
import com.labteto.dshmobile.core.wire.dto.SessionWireEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a harness 0.1.2 packed delta run.
 *
 * The regression these guard is issue #20: a `chunks` record read as one ordinary event collapses
 * the whole run to its first `seq`, and the hole that leaves raises a permanent "Reconnecting…"
 * banner over a healthy connection.
 */
class ChunkRowsTest {

    private fun row(type: String, seq: Int, time: Long, data: JsonObject): SessionHistoryRecord =
        SessionHistoryRecord.Chunks(event = SessionWireEvent(type = type, seq = seq, time = time, data = data))

    private fun textRun(vararg texts: String, gaps: List<Long>, turn: Int = 1, step: Int = 0, index: Int = 0) =
        buildJsonObject {
            put("turn", turn)
            put("step", step)
            put("index", index)
            putJsonArray("texts") { texts.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("dt") { gaps.forEach { add(JsonPrimitive(it)) } }
        }

    private fun chunkOf(event: SessionWireEvent): JsonObject =
        event.data.jsonObject.getValue("chunk").jsonObject

    private fun envelope(event: SessionWireEvent): SessionEventEnvelope =
        SessionEventEnvelope(event.type, event.seq.toLong(), event.time, event.data)

    @Test
    fun packedTextRunExpandsToOneEventPerMember() {
        val record = row("chunkrow/text-chunks", seq = 7, time = 1_000, data = textRun("Hel", "lo", " world", gaps = listOf(20L, 35L)))

        val events = ChunkRows.expand(record)

        assertEquals(3, events.size)
        assertEquals(listOf(7, 8, 9), events.map { it.seq })
        assertTrue(events.all { it.type == "assistant/chunk" })
        // Times accumulate from the run's own stamp through the recorded gaps.
        assertEquals(listOf(1_000L, 1_020L, 1_055L), events.map { it.time })
        assertEquals(
            listOf("Hel", "lo", " world"),
            events.map { chunkOf(it).getValue("text").jsonPrimitive.content },
        )
        assertEquals("text-delta", chunkOf(events[0]).getValue("type").jsonPrimitive.content)
    }

    @Test
    fun negativeGapIsPreservedRatherThanClamped() {
        // The host's wall clock can step backwards between two events; the format records what
        // happened rather than what is tidy.
        val record = row("chunkrow/text-chunks", seq = 0, time = 500, data = textRun("a", "b", gaps = listOf(-30L)))

        assertEquals(listOf(500L, 470L), ChunkRows.expand(record).map { it.time })
    }

    @Test
    fun packedReasoningRunCarriesReasoningDeltas() {
        val record = row("chunkrow/reasoning-chunks", seq = 4, time = 0, data = textRun("think", "ing", gaps = listOf(5L)))

        val events = ChunkRows.expand(record)

        assertEquals(listOf(4, 5), events.map { it.seq })
        assertTrue(events.all { chunkOf(it).getValue("type").jsonPrimitive.content == "reasoning-delta" })
    }

    @Test
    fun packedToolCallRunCarriesIdAndArgumentFragments() {
        val data = buildJsonObject {
            put("turn", 2)
            put("step", 1)
            put("index", 0)
            put("id", "call-9")
            put("name", "Read")
            putJsonArray("args") { add(JsonPrimitive("{\"pa")); add(JsonPrimitive("th\":1}")) }
            putJsonArray("dt") { add(JsonPrimitive(12L)) }
        }
        val record = row("chunkrow/tool-call-chunks", seq = 30, time = 100, data = data)

        val events = ChunkRows.expand(record)

        assertEquals(listOf(30, 31), events.map { it.seq })
        val first = chunkOf(events[0])
        assertEquals("tool-call-delta", first.getValue("type").jsonPrimitive.content)
        assertEquals("call-9", first.getValue("id").jsonPrimitive.content)
        assertEquals("Read", first.getValue("name").jsonPrimitive.content)
        assertEquals("{\"pa", first.getValue("argumentsDelta").jsonPrimitive.content)
    }

    @Test
    fun runWhoseGapCountDisagreesWithItsMembersIsDroppedWhole() {
        // Half-expanding would open the very sequence gap this code exists to avoid.
        val record = row("chunkrow/text-chunks", seq = 2, time = 0, data = textRun("a", "b", "c", gaps = listOf(1L)))

        assertTrue(ChunkRows.expand(record).isEmpty())
    }

    @Test
    fun runMissingItsBlockCoordinatesIsDroppedWhole() {
        val data = buildJsonObject {
            put("turn", 1)
            // no `step`, no `index`
            putJsonArray("texts") { add(JsonPrimitive("a")) }
            putJsonArray("dt") { }
        }

        assertTrue(ChunkRows.expand(row("chunkrow/text-chunks", seq = 1, time = 0, data = data)).isEmpty())
    }

    @Test
    fun toolCallRunWithoutAnIdIsDroppedWhole() {
        val data = buildJsonObject {
            put("turn", 1)
            put("step", 0)
            put("index", 0)
            putJsonArray("args") { add(JsonPrimitive("{}")) }
            putJsonArray("dt") { }
        }

        assertTrue(ChunkRows.expand(row("chunkrow/tool-call-chunks", seq = 1, time = 0, data = data)).isEmpty())
    }

    @Test
    fun ordinaryRecordYieldsItsSingleEventUntouched() {
        val event = SessionWireEvent(type = "turn/start", seq = 3, time = 9, data = JsonObject(emptyMap()))

        assertEquals(listOf(event), ChunkRows.expand(SessionHistoryRecord.Event(event = event)))
    }

    @Test
    fun sequenceSpanCoversTheWholeRun() {
        val record = row("chunkrow/text-chunks", seq = 12, time = 0, data = textRun("a", "b", "c", gaps = listOf(1L, 1L)))

        assertEquals(12..14, ChunkRows.sequenceSpan(record))
        assertEquals(
            5..5,
            ChunkRows.sequenceSpan(
                SessionHistoryRecord.Event(event = SessionWireEvent("turn/end", 5, 0, JsonObject(emptyMap()))),
            ),
        )
    }

    @Test
    fun chunksRecordDecodesToPackedVariantAndUnknownClassReadsAsEvent() {
        val packed: JsonElement = buildJsonObject {
            put("type", "chunks")
            put(
                "event",
                buildJsonObject {
                    put("type", "chunkrow/text-chunks")
                    put("seq", 1)
                    put("time", 0)
                    put("data", textRun("a", "b", gaps = listOf(3L)))
                },
            )
        }
        val future: JsonElement = buildJsonObject {
            put("type", "some-future-class")
            put(
                "event",
                buildJsonObject {
                    put("type", "turn/start")
                    put("seq", 4)
                    put("time", 0)
                    put("data", JsonObject(emptyMap()))
                },
            )
        }

        assertTrue(decodeFromJsonElement(SessionHistoryRecord.serializer(), packed) is SessionHistoryRecord.Chunks)
        assertTrue(decodeFromJsonElement(SessionHistoryRecord.serializer(), future) is SessionHistoryRecord.Event)
    }

    /** The issue #20 regression itself, end to end: a packed page must not read as gapped. */
    @Test
    fun pageCarryingAPackedRunFoldsWithoutAGap() {
        val page = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "event")
                    put(
                        "event",
                        buildJsonObject {
                            put("type", "turn/start")
                            put("seq", 0)
                            put("time", 0)
                            put("data", buildJsonObject { put("turn", 1) })
                        },
                    )
                },
            )
            add(
                buildJsonObject {
                    put("type", "chunks")
                    put(
                        "event",
                        buildJsonObject {
                            put("type", "chunkrow/text-chunks")
                            put("seq", 1)
                            put("time", 10)
                            put("data", textRun("Hel", "lo", "!", gaps = listOf(5L, 5L)))
                        },
                    )
                },
            )
            add(
                buildJsonObject {
                    put("type", "event")
                    put(
                        "event",
                        buildJsonObject {
                            put("type", "turn/end")
                            put("seq", 4)
                            put("time", 30)
                            put("data", buildJsonObject { put("turn", 1) })
                        },
                    )
                },
            )
        }
        val records = page.map { decodeFromJsonElement(SessionHistoryRecord.serializer(), it) }

        val expanded = ChunkRows.expandAll(records)
        assertEquals(listOf(0, 1, 2, 3, 4), expanded.map { it.seq })
        assertFalse("a packed run must not read as a journal gap", EventFold("s1").fold(expanded.map(::envelope)).gap)

        // Read as plain events instead — what shipped in 0.11.0 — and the hole is back.
        assertTrue(EventFold("s1").fold(records.map { envelope(it.event) }).gap)
    }
}
