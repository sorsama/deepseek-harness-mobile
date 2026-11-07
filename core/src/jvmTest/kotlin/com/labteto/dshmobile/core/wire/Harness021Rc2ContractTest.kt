package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.DshCore
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.OtherNode
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.wire.dto.RemoteStreamClientMessage
import com.labteto.dshmobile.core.wire.dto.RemoteStreamClientMessageSerializer
import com.labteto.dshmobile.core.wire.dto.TerminalEnvironment
import com.labteto.dshmobile.core.wire.dto.TerminalFrame
import com.labteto.dshmobile.core.wire.dto.WorkspaceFollowFrame
import com.labteto.dshmobile.core.wire.dto.WorkspaceFollowFrameSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes transcribed from `dsh-v0.2.1-rc.2` (`protocol/687f282.json`), the current baseline. */
class Harness021Rc2ContractTest {
    private val fixture = WireJson.parseToJsonElement(javaClass.getResource("/protocol/687f282.json")!!.readText()).jsonObject

    @Test fun `the fixture is the pinned baseline`() {
        assertEquals(DshCore.PROTOCOL_COMMIT, fixture.getValue("upstreamCommit").jsonPrimitive.content)
        assertEquals(DshCore.PROTOCOL_BASELINE, fixture.getValue("release").jsonPrimitive.content)
    }

    @Test fun `duplex terminal frames decode with control and the uplink window`() {
        val env = WireJson.decodeFromJsonElement(TerminalEnvironment.serializer(), fixture.getValue("terminalEnvironment"))
        assertEquals(262144, env.maxBufferedBytes)
        val snapshot = WireJson.decodeFromJsonElement(TerminalFrame.serializer(), fixture.getValue("terminalSnapshot"))
        assertEquals(true, snapshot.writable)
        assertEquals(35, snapshot.inputWindow?.frameOverheadBytes)
        assertEquals(null, snapshot.info?.controllerId)
        val state = WireJson.decodeFromJsonElement(TerminalFrame.serializer(), fixture.getValue("terminalState"))
        assertEquals(false, state.writable)
        val refused = WireJson.decodeFromJsonElement(TerminalFrame.serializer(), fixture.getValue("terminalRefusedAck"))
        assertEquals("read-only", refused.refusal)
        assertEquals(2L, refused.sequence)
    }

    @Test fun `uplink item and end encode exactly as the gateway parses them`() {
        val item = RemoteStreamClientMessage.Item(
            streamId = "7",
            value = fixture.getValue("uplinkItem").jsonObject.getValue("value"),
        )
        assertEquals(fixture.getValue("uplinkItem"), WireJson.encodeToJsonElement(RemoteStreamClientMessageSerializer, item))
        assertEquals(
            fixture.getValue("uplinkEnd"),
            WireJson.encodeToJsonElement(RemoteStreamClientMessageSerializer, RemoteStreamClientMessage.End(streamId = "7")),
        )
    }

    @Test fun `job registration and settlement stay out of the transcript`() {
        val events = listOf("jobRegistered", "jobSettled").map { key ->
            val e = fixture.getValue(key).jsonObject
            SessionEventEnvelope(
                e.getValue("type").jsonPrimitive.content,
                e.getValue("seq").jsonPrimitive.long,
                e.getValue("time").jsonPrimitive.long,
                e.getValue("data"),
            )
        }
        val snapshot = EventFold("s").fold(events)
        assertFalse(snapshot.nodes.any { it is OtherNode && it.type.startsWith("job/") })
    }

    @Test fun `a workspace replace restates the baseline`() {
        val frame = WireJson.decodeFromJsonElement(WorkspaceFollowFrameSerializer, fixture.getValue("workspaceReplace"))
        assertTrue(frame is WorkspaceFollowFrame.Baseline)
        frame as WorkspaceFollowFrame.Baseline
        assertEquals(listOf("gone"), frame.archivedSessionIds)
        assertEquals(listOf("kept"), frame.pinnedSessionIds)
        assertTrue(frame.workspaces.isEmpty())
        assertTrue(fixture.getValue("workspaceReplace") is JsonObject)
    }
}
