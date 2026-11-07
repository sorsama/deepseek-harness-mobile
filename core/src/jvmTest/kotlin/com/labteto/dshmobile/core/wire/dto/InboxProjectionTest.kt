package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rebuilding the pending queue from the `inbox` projection.
 *
 * The host used to hand this over ready-made: `session/control` carried a `queues` map and a
 * `queue` frame, each item already tagged with its placement. Current master deletes both and
 * publishes the raw agent inbox as a projection instead, so the placement is this client's to
 * derive — and a placement is not cosmetic. It decides whether a message shows up as the person's
 * own steering or as context something else injected, and getting it backwards would misattribute
 * a message to the person who is reading it.
 */
class InboxProjectionTest {

    private fun projection(json: String) = Json.parseToJsonElement(json)

    private fun message(id: String, kind: String, text: String, rpcId: String? = null): String {
        val rpc = if (rpcId == null) "" else ""","rpcId":"$rpcId""""
        return """{"id":"$id","role":"user","content":[{"type":"text","text":"$text"}],
            "source":{"kind":"$kind"$rpc}}"""
    }

    @Test
    fun `next-turn is queued and next-step splits on who wrote it`() {
        val items = InboxProjection.itemsFrom(
            projection(
                """{
                  "next-turn": [${message("m1", "user", "later")}],
                  "next-step": [
                    ${message("m2", "user", "actually do this now", rpcId = "rpc-2")},
                    ${message("m3", "plugin", "injected note")}
                  ]
                }""",
            ),
        )

        assertEquals(listOf("m1", "m2", "m3"), items.map { it.id })
        assertEquals(
            listOf(InboxProjection.QUEUED, InboxProjection.STEERING, InboxProjection.CONTEXT),
            items.map { it.placement },
        )
    }

    /**
     * Inbox order is the order the agent will take them in, and next-turn work is not "after"
     * next-step work in the dock's sense — it is a different section. Emitting turn-items first
     * keeps the two groups contiguous, which is what the queue dock renders.
     */
    @Test
    fun `turn items come before step items`() {
        val items = InboxProjection.itemsFrom(
            projection(
                """{
                  "next-step": [${message("step", "user", "steer")}],
                  "next-turn": [${message("turn", "user", "queued")}]
                }""",
            ),
        )
        assertEquals(listOf("turn", "step"), items.map { it.id })
    }

    /**
     * A source kind this build has never seen reads as context, not as steering.
     *
     * The two mistakes are not symmetric: showing a plugin's injected note as if the person had
     * typed it is worse than filing the person's own message one section over, so the unknown case
     * takes the conservative side.
     */
    @Test
    fun `an unfamiliar source kind is context rather than steering`() {
        val items = InboxProjection.itemsFrom(
            projection("""{"next-step": [${message("m", "some-future-kind", "hello")}]}"""),
        )
        assertEquals(listOf(InboxProjection.CONTEXT), items.map { it.placement })
    }

    @Test
    fun `a malformed member costs its own row and no others`() {
        val items = InboxProjection.itemsFrom(
            projection(
                """{"next-turn": [
                  ${message("good-1", "user", "fine")},
                  {"id":"broken"},
                  ${message("good-2", "user", "also fine")}
                ]}""",
            ),
        )
        assertEquals(listOf("good-1", "good-2"), items.map { it.id })
    }

    @Test
    fun `the message survives whole, content included`() {
        val items = InboxProjection.itemsFrom(
            projection("""{"next-turn": [${message("m1", "user", "the text")}]}"""),
        )
        val block = items.single().message.content.single()
        assertTrue("expected a text block, got $block", block is ContentBlock.Text)
        assertEquals("the text", (block as ContentBlock.Text).text)
    }

    /**
     * An absent key is "no such capability", which every other projection reads as nothing pending
     * rather than as a fault. An empty inbox has to be indistinguishable from that, because the
     * host publishes exactly this shape when it has drained the queue.
     */
    @Test
    fun `absent, empty and malformed projections all read as nothing pending`() {
        assertEquals(emptyList<QueuedInboxItem>(), InboxProjection.itemsFrom(null))
        assertEquals(emptyList<QueuedInboxItem>(), InboxProjection.itemsFrom(projection("{}")))
        assertEquals(
            emptyList<QueuedInboxItem>(),
            InboxProjection.itemsFrom(projection("""{"next-turn":[],"next-step":[]}""")),
        )
        assertEquals(emptyList<QueuedInboxItem>(), InboxProjection.itemsFrom(projection("[]")))
        assertEquals(emptyList<QueuedInboxItem>(), InboxProjection.itemsFrom(projection("7")))
    }
}
