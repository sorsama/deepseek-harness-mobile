package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session format v4 (harness 0.1.7) against the v3 logs this client has always read.
 *
 * The two `tool/result` payloads are the shapes the harness's own v3-to-v4 migration
 * (`packages/session/session-format-v3-to-v4/src/tool-role.ts`) maps between, so a pair that folds
 * to different nodes is a regression against one harness or the other.
 */
class SessionFormatV4FoldTest {

    private fun event(type: String, seq: Long, json: String) =
        SessionEventEnvelope(type, seq, seq, WireJson.parseToJsonElement(json))

    private val call = event(
        "tool/call", 1,
        """{"turn":1,"step":1,"callId":"c1","name":"bash","arguments":"{\"command\":\"ls\"}"}""",
    )

    private val v3Failure = event(
        "tool/result", 2,
        """{"turn":1,"step":1,"meta":{"exitCode":2},"error":{"name":"ToolError","message":"x"},
           "message":{"id":"m2","role":"user","source":{"kind":"tool","callId":"c1"},
             "content":[{"type":"tool-result","toolCallId":"c1","isError":true,
               "content":[{"type":"text","text":"ls: cannot access 'nope'"}]}]}}""",
    )

    private val v4Failure = event(
        "tool/result", 2,
        """{"turn":1,"step":1,"meta":{"exitCode":2},"error":{"name":"ToolError","message":"x"},
           "message":{"id":"m2","role":"tool","source":{"kind":"tool","callId":"c1"},
             "toolCallId":"c1","isError":true,
             "content":[{"type":"text","text":"ls: cannot access 'nope'"}]}}""",
    )

    private fun resultOf(vararg events: SessionEventEnvelope): ToolResultNode =
        EventFold("s1").fold(events.toList()).nodes.filterIsInstance<ToolResultNode>().single()

    @Test
    fun aV4ResultPairsWithItsCall() {
        val result = resultOf(call, v4Failure)
        assertEquals("c1", result.callId)
        assertTrue(result.isError)
        assertEquals(2, (result.meta as JsonObject)["exitCode"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun bothFormatsFoldToTheSameNode() {
        assertEquals(resultOf(call, v3Failure), resultOf(call, v4Failure))
    }

    @Test
    fun theBodyIsTheToolsOwnBlocksInEitherFormat() {
        for (result in listOf(resultOf(call, v3Failure), resultOf(call, v4Failure))) {
            val body = result.content as JsonArray
            assertEquals("text", body.single().jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun aV4SuccessWithoutIsErrorIsNotAFailure() {
        val ok = event(
            "tool/result", 2,
            """{"turn":1,"step":1,"message":{"id":"m2","role":"tool","source":{"kind":"tool","callId":"c1"},
                 "toolCallId":"c1","content":[{"type":"text","text":"a\nb"}]}}""",
        )
        val result = resultOf(call, ok)
        assertEquals("c1", result.callId)
        assertFalse(result.isError)
    }

    @Test
    fun theSourceCallIdStandsInWhenTheMessageOmitsItsOwn() {
        val bare = event(
            "tool/result", 2,
            """{"turn":1,"step":1,"message":{"role":"tool","source":{"kind":"tool","callId":"c1"},"content":[]}}""",
        )
        assertEquals("c1", resultOf(call, bare).callId)
    }

    @Test
    fun aForkClosersSyntheticResultStillPairs() {
        // Harness 0.1.7 closes a mid-turn fork cut with a synthetic failed result per open call.
        val closer = event(
            "tool/result", 2,
            """{"turn":1,"step":1,"error":{"name":"ToolNotStartedError","code":"TOOL_NOT_STARTED","message":"not started"},
               "message":{"id":"forked-tool-result-c1-2","role":"tool","source":{"kind":"tool","callId":"c1"},
                 "toolCallId":"c1","isError":true,"content":[{"type":"text","text":"Tool call was not started."}]}}""",
        )
        val result = resultOf(call, closer)
        assertEquals("c1", result.callId)
        assertTrue(result.isError)
    }

    @Test
    fun aToolSetChangeFoldsToADeveloperMessageNode() {
        val change = event(
            "developer/message", 3,
            """{"turn":1,"step":2,"headerSeq":1,
               "message":{"id":"d1","role":"developer","source":{"kind":"tool-registry"},
                 "content":[{"type":"tool-addition","toolName":"web_search"},
                            {"type":"tool-removal","toolName":"bash"}]}}""",
        )
        val node = EventFold("s1").fold(listOf(change)).nodes.single() as DeveloperMessageNode
        assertEquals(listOf("web_search"), node.addedTools)
        assertEquals(listOf("bash"), node.removedTools)
        assertEquals("tool-registry", node.sourceKind)
    }

    @Test
    fun aPluginSourceRenamedInV4IsStillInjectedContext() {
        // v4 replaced `{kind:'plugin', plugin:'goal'}` with `{kind:'goal'}`; neither is a person.
        val injected = event(
            "user/message", 1,
            """{"id":"u1","role":"user","source":{"kind":"goal"},"content":[{"type":"text","text":"continue"}]}""",
        )
        val node = EventFold("s1").fold(listOf(injected)).nodes.single() as UserMessageNode
        assertTrue(node.isInjectedContext)
    }

    @Test
    fun theV3WrapperStaysInTheJournalVerbatim() {
        val snapshot = EventFold("s1").fold(listOf(call, v3Failure))
        val content = snapshot.journal.last().data.jsonObject["message"]!!.jsonObject["content"]!!.jsonArray
        assertEquals("tool-result", content.single().jsonObject["type"]!!.jsonPrimitive.content)
    }
}
