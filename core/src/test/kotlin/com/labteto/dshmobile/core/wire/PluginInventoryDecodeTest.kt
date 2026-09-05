package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.PluginFiberPhase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/**
 * `pluginInventory/list` decoding.
 *
 * The inventory is the one place the app reads the harness's own composition, so it sees whatever
 * the deployment happens to mount — including rows from plugins this build has never heard of. One
 * unrecognised `fiberPhase` must cost that row and nothing else; emptying the list would report a
 * deployment as having no plugins at all.
 */
class PluginInventoryDecodeTest {

    private class FixedTransport(private val body: String) : RpcTransport {
        override suspend fun post(path: String, body: String): RpcHttpResponse =
            RpcHttpResponse(200, this.body)

        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T =
            error("not used")

        override suspend fun upload(
            path: String,
            contentType: String,
            contentLength: Long,
            body: InputStream,
            onProgress: ((Long) -> Unit)?,
        ): RpcHttpResponse = error("not used")
    }

    private fun clientReturning(value: String) = DshApiClient(
        transport = FixedTransport(
            """{"type":"server-response","rpcId":"1","result":{"ok":true,"value":$value}}""",
        ),
    )

    private suspend fun listOrFail(value: String) =
        when (val r = clientReturning(value).pluginInventoryList()) {
            is RpcResult.Ok -> r.value
            is RpcResult.Err -> error("expected success, got ${r.error.code}: ${r.error.message}")
        }

    @Test
    fun `a full row decodes`() = runTest {
        val snapshot = listOrFail(
            """{"entries":[{"entryId":"e1","moduleName":"@deepseek-ai/dsh-client-ui-plan",""" +
                """"enabled":true,"fiberPhase":"active"}]}""",
        )
        val entry = snapshot.entries.single()
        assertEquals("e1", entry.entryId)
        assertEquals("@deepseek-ai/dsh-client-ui-plan", entry.moduleName)
        assertTrue(entry.enabled)
        assertEquals(PluginFiberPhase.ACTIVE, entry.fiberPhase)
    }

    /** A disabled row never mounts, so the host sends no phase at all. */
    @Test
    fun `an absent phase decodes as null rather than failing`() = runTest {
        val snapshot = listOrFail("""{"entries":[{"entryId":"e1","moduleName":"m","enabled":false}]}""")
        assertNull(snapshot.entries.single().fiberPhase)
    }

    @Test
    fun `an explicit null phase decodes as null`() = runTest {
        val snapshot = listOrFail(
            """{"entries":[{"entryId":"e1","moduleName":"m","enabled":true,"fiberPhase":null}]}""",
        )
        assertNull(snapshot.entries.single().fiberPhase)
    }

    /**
     * `WireJson` coerces an out-of-range value to the property's default, so a phase this build has
     * never heard of costs the phase, not the row. That is the outcome worth having: the plugin is
     * still named and its enablement is still right, and only the one field nobody can interpret
     * goes quiet.
     */
    @Test
    fun `an unknown phase keeps its row and reads as no phase`() = runTest {
        val snapshot = listOrFail(
            """{"entries":[""" +
                """{"entryId":"good","moduleName":"m","enabled":true,"fiberPhase":"active"},""" +
                """{"entryId":"weird","moduleName":"m","enabled":true,"fiberPhase":"reticulating"},""" +
                """{"entryId":"also-good","moduleName":"m","enabled":false}]}""",
        )
        assertEquals(listOf("good", "weird", "also-good"), snapshot.entries.map { it.entryId })
        assertNull(snapshot.entries[1].fiberPhase)
    }

    /** A row missing a required member has nothing to fall back on, so that one is dropped. */
    @Test
    fun `a malformed row drops without emptying the list`() = runTest {
        val snapshot = listOrFail(
            """{"entries":[""" +
                """{"entryId":"good","moduleName":"m","enabled":true},""" +
                """{"moduleName":"m","enabled":true}]}""",
        )
        assertEquals(listOf("good"), snapshot.entries.map { it.entryId })
    }

    @Test
    fun `unrecognised members on a row are ignored`() = runTest {
        val snapshot = listOrFail(
            """{"entries":[{"entryId":"e1","moduleName":"m","enabled":true,"somethingNew":42}]}""",
        )
        assertEquals("e1", snapshot.entries.single().entryId)
    }

    @Test
    fun `a deployment with no plugins decodes as an empty list`() = runTest {
        assertTrue(listOrFail("""{"entries":[]}""").entries.isEmpty())
    }

    /** The codec folds an absent value into `{}`, so the empty case has to survive a missing key. */
    @Test
    fun `a missing entries key decodes as an empty list`() = runTest {
        assertTrue(listOrFail("{}").entries.isEmpty())
    }
}
