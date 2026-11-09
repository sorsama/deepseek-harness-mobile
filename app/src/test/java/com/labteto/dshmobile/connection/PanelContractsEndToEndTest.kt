package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import com.labteto.dshmobile.mockharness.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PanelContractsEndToEndTest(private val kind: TransportKind) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun kinds(): List<TransportKind> = TransportKind.parameters()
    }

    @Test fun panelsUseRealHttpAndPreserveBusinessFailures() = runBlocking {
        val harness = MockHarness()
        val scenario = PanelScenario(harness)
        val port = harness.start()
        try {
            val api = DshApiClient(kind.rpc("http://127.0.0.1:$port", 5000, 5000))
            assertEquals("auto", (api.permissionCatalog() as RpcResult.Ok).value.options.last().value)
            assertTrue((api.workspaceUnarchiveSession("archived-demo") as RpcResult.Ok).value.archivedSessionIds.isEmpty())
            assertTrue(scenario.archived.isEmpty())
            val listing = (api.workspaceFileList("cold-child", ".") as RpcResult.Ok).value
            assertEquals(2, listing.entries.size)
            val page = (api.workspaceFileRead("cold-child", "README.md", WorkspaceFileRange(1, 1)) as RpcResult.Ok).value
            assertEquals("# Preview fixture", page.text)
            assertFalse(page.eof)
            val rest = (api.workspaceFileRead("cold-child", "README.md", WorkspaceFileRange(2, 500)) as RpcResult.Ok).value
            assertTrue(rest.eof)
            assertEquals(page.version, rest.version)
            // Harness 0.1.7: one binary read, answered as multipart, in both of the file panel's shapes.
            val readme = scenario.files.getValue("README.md")
            val whole = (api.workspaceFileReadBytes("cold-child", "README.md") as RpcResult.Ok).value
            assertArrayEquals(readme, whole.data)
            assertTrue(whole.eof)
            val related = (api.workspaceFileReadBytes("cold-child", "README.md", WorkspaceByteReadOptions(baseFile = "index.html")) as RpcResult.Ok).value
            assertArrayEquals(readme, related.data)
            val window = (api.workspaceFileReadBytes("cold-child", "README.md", WorkspaceByteReadOptions(range = WorkspaceByteRange(2, 7))) as RpcResult.Ok).value
            assertArrayEquals(readme.copyOfRange(2, 9), window.data)
            assertEquals("workspace-file/not-found", (api.workspaceFileReadBytes("cold-child", "gone.png") as RpcResult.Err).error.code)

            // A session with running work is refused until the archive asks to stop it.
            val refused = api.workspaceArchiveSession(WorkspaceArchiveSessionRequest("busy-demo")) as RpcResult.Err
            assertEquals("workspace/session-active", refused.error.code)
            val details = decodeFromJsonElement(WorkspaceSessionActiveDetails.serializer(), refused.error.details)
            assertEquals(listOf("turn", "job"), details.activity.map { it.kind })
            val archived = api.workspaceArchiveSession(WorkspaceArchiveSessionRequest("busy-demo", stopActivity = true)) as RpcResult.Ok
            assertTrue("busy-demo" in archived.value.archivedSessionIds)

            assertEquals(listOf("a"), (api.workspacePinSession("a") as RpcResult.Ok).value.pinnedSessionIds)
            assertEquals(listOf("b", "a"), (api.workspacePinSession("b") as RpcResult.Ok).value.pinnedSessionIds)
            assertEquals(listOf("b"), (api.workspaceUnpinSession("a") as RpcResult.Ok).value.pinnedSessionIds)

            assertEquals("requested", (api.jobKill("s", "bash-1") as RpcResult.Ok).value.outcome)
            assertEquals("already-finished", (api.jobKill("s", "bash-1") as RpcResult.Ok).value.outcome)
            assertEquals("job/not-found", (api.jobKill("s", "bash-9") as RpcResult.Err).error.code)
            val first = (api.messageFeedbackPut(MessageFeedbackPutRequest("s", "m", "positive", null, "Keep this note")) as RpcResult.Ok).value
            assertTrue(first.ok)
            val conflict = (api.messageFeedbackPut(MessageFeedbackPutRequest("s", "m", "negative", null, "Do not discard")) as RpcResult.Ok).value
            assertFalse(conflict.ok)
            assertEquals(first.value, conflict.error?.current)
            assertEquals("Keep this note", (api.messageFeedbackList("s") as RpcResult.Ok).value.value?.items?.single()?.note)
            val deleted = (api.messageFeedbackDelete(MessageFeedbackDeleteRequest("s", "m", first.value!!.version)) as RpcResult.Ok).value
            assertTrue(deleted.ok)
            assertTrue((api.messageFeedbackList("s") as RpcResult.Ok).value.value!!.items.isEmpty())
            val terminal = (api.terminalCreate("s", TerminalCreateRequest("t", 80, 24)) as RpcResult.Ok).value
            assertEquals("t", terminal.id)
            assertTrue(api.terminalWrite("s", "t", "wrong-controller", "whoami\r") is RpcResult.Err)
            assertTrue(scenario.terminalInputs.isEmpty())
            assertTrue(api.terminalRename("s", "t", "Build") is RpcResult.Ok)
            assertEquals("Build", (api.terminalList("s") as RpcResult.Ok).value.single().title)
            assertTrue(api.terminalClose("s", "t") is RpcResult.Ok)
            assertTrue((api.terminalList("s") as RpcResult.Ok).value.isEmpty())
        } finally { harness.stop() }
    }
}
