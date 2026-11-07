package com.labteto.dshmobile.core.session

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SurfaceHistoryTest {
    private fun event(seq: Long, op: String = "\"append\"") = SessionEventEnvelope(
        "system/message", seq, 100 + seq, Json.parseToJsonElement("""{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"system-$seq"}]}}"""),
        surfaceIntent = Json.parseToJsonElement(op), sourceEventSeqs = listOf(0), ignorable = true,
    )
    @Test fun `replacement of an earlier replacement removes its surviving position`() {
        val events = listOf(event(0), event(1), event(2, """{"op":"replace","startSeq":0,"endSeq":1}"""),
            event(3, """{"op":"replace","startSeq":2,"endSeq":2}"""))
        assertEquals(listOf(3L), effectiveSurfaceEvents(events).map { it.seq })
        val snapshot = EventFold("s").fold(events)
        assertEquals(events, snapshot.journal)
        assertEquals(listOf(0L, 1L), snapshot.nodes.map { it.seq })
        assertEquals(listOf(3L), snapshot.effectiveSurface.map { it.seq })
        assertEquals(3L, snapshot.lastSeq)
        assertFalse(snapshot.gap)
        assertEquals(listOf(0), snapshot.journal.first().sourceEventSeqs)
    }
    @Test fun `partial history can replace a range preceding the loaded window`() {
        assertEquals(listOf(12L), effectiveSurfaceEvents(listOf(event(10), event(11),
            event(12, """{"op":"replace","startSeq":2,"endSeq":11}"""))).map { it.seq })
    }
    @Test fun `bounded reads allow exactly the cap and consume only one excess byte`() {
        assertArrayEquals(byteArrayOf(1,2,3), readImageBounded(byteArrayOf(1,2,3).inputStream(), 3))
        val input = ByteArray(100) { 1 }.inputStream()
        assertNull(readImageBounded(input, 3))
        assertEquals(96, input.available())
        assertArrayEquals(byteArrayOf(), readImageBounded(byteArrayOf().inputStream(), 3))
    }
}
