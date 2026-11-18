package com.labteto.dshmobile.core.browser

import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.UserMessageNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MentionedAddressesTest {
    private fun assistant(seq: Long, text: String) =
        AssistantMessageNode(seq, "m$seq", 1, 1, listOf(ChatBlock("text", text)))

    private fun toolText(seq: Long, text: String) = ToolResultNode(
        seq, "c$seq",
        JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))),
        isError = false, turn = 1, step = 1,
    )

    @Test
    fun newestFirstAndOnce() {
        val nodes = listOf(
            toolText(1, "  VITE ready\n  Local:   http://localhost:5173/\n  Network: http://192.168.1.5:5173/"),
            assistant(2, "The app runs at http://localhost:5173/ and the docs at https://vitejs.dev."),
            assistant(3, "Storybook is on http://localhost:6006."),
        )
        assertEquals(
            listOf("http://localhost:6006/", "http://localhost:5173/", "http://192.168.1.5:5173/"),
            mentionedLocalAddresses(nodes),
        )
    }

    @Test
    fun publicAddressesAndUserMessagesAreLeftOut() {
        val nodes = listOf(
            UserMessageNode(1, "u1", listOf(ChatBlock("text", "open http://localhost:1234")), "user"),
            assistant(2, "See https://example.com"),
        )
        assertTrue(mentionedLocalAddresses(nodes).isEmpty())
    }

    @Test
    fun aBareStringResultIsRead() {
        val node = ToolResultNode(1, "c1", JsonPrimitive("Serving on http://0.0.0.0:8000/"), isError = false, turn = 1, step = 1)
        assertEquals(listOf("http://127.0.0.1:8000/"), mentionedLocalAddresses(listOf(node)))
    }

    @Test
    fun theLimitHolds() {
        val nodes = (1..10).map { assistant(it.toLong(), "http://localhost:${3000 + it}") }
        assertEquals(listOf("http://localhost:3010/", "http://localhost:3009/"), mentionedLocalAddresses(nodes, limit = 2))
    }
}
