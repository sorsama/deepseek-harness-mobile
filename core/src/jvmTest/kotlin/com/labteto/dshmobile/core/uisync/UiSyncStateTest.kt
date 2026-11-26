package com.labteto.dshmobile.core.uisync

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The item list the app keeps from the page, and the way back to it for Settings > Add-ons. */
class UiSyncStateTest {

    private val pet = "dsh-ui-sync-example-pet|shell.overlay|example-pet.pet"
    private val ready = """{"v":1,"type":"ui-sync/ready","version":"0.1.0","protocol":1,"mode":"phone","viewport":{"w":390,"h":844},"dpr":2,"settings":true}"""
    private val items = """{"v":1,"type":"ui-sync/items","items":[{"id":"$pet","package":"dsh-ui-sync-example-pet","slot":"shell.overlay","cell":"example-pet.pet","title":"Cat","placement":"overlay","target":"both","defaultTarget":"both","visible":true}]}"""

    @Test
    fun `a phone page that is ready is attached and its items are kept`() {
        val state = UiSyncState()
        assertNull(state.page.value)
        assertIs<UiSyncPageMessage.Ready>(state.onPageMessage(ready))
        assertEquals(UiSyncPage("0.1.0", 1, settingsLoaded = true), state.page.value)
        state.onPageMessage(items)
        assertEquals(listOf(pet), state.items.value.map { it.id })
        state.onPageMessage("""{"v":1,"type":"ui-sync/session","sessionId":"session-1"}""")
        assertEquals("session-1", state.pageSession.value)
    }

    @Test
    fun `a page that is not in phone mode is never attached`() {
        val state = UiSyncState()
        state.onPageMessage(ready.replace("\"phone\"", "\"desktop\""))
        assertNull(state.page.value)
    }

    @Test
    fun `a message that is not understood changes nothing`() {
        val state = UiSyncState()
        state.onPageMessage(items)
        assertNull(state.onPageMessage("""{"v":1,"type":"ui-sync/items","items":"none"}"""))
        assertNull(state.onPageMessage("garbage"))
        assertEquals(listOf(pet), state.items.value.map { it.id })
    }

    @Test
    fun `set target goes to the page with a request id`() {
        val state = UiSyncState()
        val sent = mutableListOf<String>()
        state.onPageMessage(ready)
        state.attach { sent += it }
        val requestId = assertNotNull(state.setTarget(pet, UiSyncTarget.DESKTOP))
        val message = Json.parseToJsonElement(sent.single()).jsonObject
        assertEquals("host/set-target", message.getValue("type").jsonPrimitive.content)
        assertEquals(pet, message.getValue("id").jsonPrimitive.content)
        assertEquals("desktop", message.getValue("target").jsonPrimitive.content)
        assertEquals(requestId, message.getValue("requestId").jsonPrimitive.content)
    }

    @Test
    fun `without a page set target says so and sends nothing`() {
        val state = UiSyncState()
        val sent = mutableListOf<String>()
        assertNull(state.setTarget(pet, UiSyncTarget.OFF))
        // A sender alone is not a page: nothing goes out before the page said it is ready.
        state.attach { sent += it }
        assertNull(state.setTarget(pet, UiSyncTarget.OFF))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a transport that throws is a message not sent`() {
        val state = UiSyncState()
        state.onPageMessage(ready)
        state.attach { throw IllegalStateException("detached") }
        assertNull(state.setTarget(pet, UiSyncTarget.PHONE))
    }

    @Test
    fun `detach keeps the last items and reset forgets them`() {
        val state = UiSyncState()
        state.onPageMessage(ready)
        state.onPageMessage(items)
        state.attach { }
        state.detach()
        assertNull(state.page.value)
        assertNull(state.setTarget(pet, UiSyncTarget.BOTH))
        assertEquals(listOf(pet), state.items.value.map { it.id })
        state.reset()
        assertTrue(state.items.value.isEmpty())
    }

    @Test
    fun `acks reach whoever waits for them`() = runTest(UnconfinedTestDispatcher()) {
        val state = UiSyncState()
        var got: UiSyncPageMessage.Ack? = null
        val job = launch { got = state.acks.first() }
        state.onPageMessage("""{"v":1,"type":"ui-sync/ack","requestId":"t1","ok":false,"error":{"code":"ui-sync/core-item","message":"core"}}""")
        job.join()
        assertEquals(UiSyncPageMessage.Ack("t1", false, "ui-sync/core-item", "core"), got)
    }

    @Test
    fun `request ids differ`() {
        val ids = (0 until 50).map { UiSyncState.requestId("t") }.toSet()
        assertEquals(50, ids.size)
        assertTrue(ids.all { it.startsWith("t") })
    }

    private fun describe(vararg ns: String) = RpcResult.Ok(SettingsDescribeValue(writable = true, hasDocument = true, namespaces = ns.map { SettingsNamespaceView(ns = it) }))

    @Test
    fun `the companion is present only while settings list its namespace`() {
        assertEquals(RpcResult.Ok(Unit), describe("web", "ui-sync").uiSyncPresence())
        val missing = assertIs<RpcResult.Err>(describe("web", "ui-theme").uiSyncPresence())
        assertEquals("capability-unavailable", missing.error.code)
        assertEquals("capability-unavailable", assertIs<RpcResult.Err>(describe().uiSyncPresence()).error.code)
    }

    @Test
    fun `a harness without settings reads as no companion and other failures pass through`() {
        val gone = RpcResult.Err(RpcError("gateway/definition-unavailable", "no settings"))
        assertEquals("capability-unavailable", assertIs<RpcResult.Err>(gone.uiSyncPresence()).error.code)
        val auth = RpcResult.Err(RpcError("unauthenticated", "sign in"))
        assertEquals(auth, auth.uiSyncPresence())
    }
}
