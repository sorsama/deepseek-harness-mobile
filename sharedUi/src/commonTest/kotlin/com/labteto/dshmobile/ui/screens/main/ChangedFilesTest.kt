package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a `workspace/changes` marker lists: the turn's successful writes and edits. */
class ChangedFilesTest {

    private fun call(seq: Long, id: String, name: String, path: String, turn: Int = 1) =
        ToolCallNode(seq, id, name, """{"file_path":"$path"}""", turn, 1)

    private fun result(seq: Long, id: String, error: Boolean = false, turn: Int = 1) =
        ToolResultNode(seq, id, JsonPrimitive("ok"), error, turn, 1)

    @Test
    fun `lists each written or edited file once, in first-touch order`() {
        val nodes = listOf(
            call(1, "a", "read", "src/todos.js"), result(2, "a"),
            call(3, "b", "edit", "src/todos.js"), result(4, "b"),
            call(5, "c", "write", "test/pagination.test.js"), result(6, "c"),
            call(7, "d", "edit", "src/todos.js"), result(8, "d"),
        )
        assertEquals(listOf("src/todos.js", "test/pagination.test.js"), changedFiles(nodes, 1))
    }

    @Test
    fun `a failed edit or another turn's change is not this turn's change`() {
        val nodes = listOf(
            call(1, "a", "edit", "src/failed.js"), result(2, "a", error = true),
            call(3, "b", "write", "src/earlier.js", turn = 0), result(4, "b", turn = 0),
            call(5, "c", "edit", "src/pending.js"),
        )
        assertEquals(emptyList<String>(), changedFiles(nodes, 1))
    }

    @Test
    fun `the marker's turn is read from its payload`() {
        assertEquals(3, changesTurn(buildJsonObject { put("turn", 3) }))
        assertNull(changesTurn(JsonPrimitive("unexpected")))
    }
}
