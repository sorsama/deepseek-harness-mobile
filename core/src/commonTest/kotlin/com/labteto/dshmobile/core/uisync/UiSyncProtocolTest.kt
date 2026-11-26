package com.labteto.dshmobile.core.uisync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bridge messages as the companion `dsh-ui-sync` 0.1.0 sends them (docs/BRIDGE.md), and the
 * ones this app sends back. Names avoid commas and slashes: Kotlin/Native refuses them in test names.
 */
class UiSyncProtocolTest {

    private val pet = "dsh-ui-sync-example-pet|shell.overlay|example-pet.pet"
    private val treat = "dsh-ui-sync-example-pet|conversation.input.right|example-pet.treat"

    @Test
    fun `ready from a phone page reads every field`() {
        val m = parseUiSyncPageMessage(
            """{"v":1,"type":"ui-sync/ready","version":"0.1.0","protocol":1,"mode":"phone","viewport":{"w":390,"h":844},"dpr":2.625,"settings":true}""",
        )
        val ready = assertIs<UiSyncPageMessage.Ready>(m)
        assertEquals("0.1.0", ready.version)
        assertEquals(1, ready.protocol)
        assertEquals("phone", ready.mode)
        assertEquals(UiSyncViewport(390.0, 844.0), ready.viewport)
        assertEquals(2.625, ready.dpr)
        assertTrue(ready.settings)
    }

    @Test
    fun `items keep good entries and drop broken ones`() {
        val m = parseUiSyncPageMessage(
            """
            {"v":1,"type":"ui-sync/items","items":[
              {"id":"$pet","package":"dsh-ui-sync-example-pet","slot":"shell.overlay","cell":"example-pet.pet","title":"Cat","placement":"overlay","target":"both","defaultTarget":"both","visible":true},
              {"id":"$treat","package":"dsh-ui-sync-example-pet","slot":"conversation.input.right","cell":"example-pet.treat","title":null,"placement":"input-right","target":"desktop","defaultTarget":"both","visible":false},
              {"id":"","target":"both"},
              {"id":"no-target"},
              {"id":"bad-target","target":"everywhere"},
              "not an object",
              {"id":"$pet","target":"off"}
            ]}
            """.trimIndent(),
        )
        val items = assertIs<UiSyncPageMessage.Items>(m).items
        assertEquals(listOf(pet, treat), items.map { it.id })
        val cat = items[0]
        assertEquals("dsh-ui-sync-example-pet", cat.pkg)
        assertEquals("Cat", cat.title)
        assertEquals(UiSyncPlacement.OVERLAY, cat.placement)
        assertEquals(UiSyncTarget.BOTH, cat.target)
        assertTrue(cat.visible)
        val button = items[1]
        assertNull(button.title)
        assertEquals(UiSyncPlacement.INPUT_RIGHT, button.placement)
        assertEquals(UiSyncTarget.DESKTOP, button.target)
        assertEquals(UiSyncTarget.BOTH, button.defaultTarget)
        assertFalse(button.visible)
    }

    @Test
    fun `an unknown placement is a slot that is not lifted`() {
        val m = parseUiSyncPageMessage(
            """{"v":1,"type":"ui-sync/items","items":[{"id":"p|sidebar.panellist|x","target":"both","placement":null},{"id":"p|other|y","target":"phone","placement":"floating"}]}""",
        )
        val items = assertIs<UiSyncPageMessage.Items>(m).items
        assertEquals(listOf(null, null), items.map { it.placement })
        assertEquals(UiSyncTarget.PHONE, items[1].defaultTarget)
    }

    @Test
    fun `hit rects read numbers and drop rectangles that are not`() {
        val m = parseUiSyncPageMessage(
            """
            {"v":1,"type":"ui-sync/hit-rects","dpr":2,"viewport":{"w":390,"h":844},"rects":[
              {"item":"$pet","x":294,"y":631,"w":72,"h":73},
              {"item":"$treat","x":"10","y":20,"w":30,"h":40},
              {"item":"$treat","x":10,"y":20,"w":0,"h":40},
              {"item":"$treat","x":10,"y":20,"w":-5,"h":40},
              {"x":1,"y":2,"w":3,"h":4}
            ]}
            """.trimIndent(),
        )
        val rects = assertIs<UiSyncPageMessage.HitRects>(m)
        assertEquals(2.0, rects.dpr)
        assertEquals(listOf(UiSyncRect(pet, 294.0, 631.0, 72.0, 73.0), UiSyncRect("", 1.0, 2.0, 3.0, 4.0)), rects.rects)
    }

    @Test
    fun `an empty rect list is a message that every touch is native`() {
        val m = assertIs<UiSyncPageMessage.HitRects>(parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/hit-rects","dpr":3,"viewport":{"w":1,"h":1},"rects":[]}"""))
        assertTrue(m.rects.isEmpty())
    }

    @Test
    fun `a runaway page cannot hand over unlimited rectangles`() {
        val many = (0 until 1000).joinToString(",") { """{"item":"a","x":$it,"y":0,"w":1,"h":1}""" }
        val m = assertIs<UiSyncPageMessage.HitRects>(parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/hit-rects","rects":[$many]}"""))
        assertEquals(MAX_RECTS, m.rects.size)
        assertNull(m.dpr)
    }

    @Test
    fun `session ack and error read their fields`() {
        assertEquals(UiSyncPageMessage.Session("session-1"), parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/session","sessionId":"session-1"}"""))
        assertEquals(UiSyncPageMessage.Session(null), parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/session","sessionId":null}"""))
        assertEquals(
            UiSyncPageMessage.Ack("t1", true, null, null),
            parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/ack","requestId":"t1","ok":true}"""),
        )
        assertEquals(
            UiSyncPageMessage.Ack("f1", false, "ui-sync/unknown-session", "no such session"),
            parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/ack","requestId":"f1","ok":false,"error":{"code":"ui-sync/unknown-session","message":"no such session"}}"""),
        )
        assertEquals(
            UiSyncPageMessage.Error("ui-sync/namespace-missing", "not mounted"),
            parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/error","code":"ui-sync/namespace-missing","message":"not mounted"}"""),
        )
    }

    @Test
    fun `bad messages are ignored instead of failing`() {
        val bad = listOf(
            "",
            "not json",
            "[]",
            "42",
            "null",
            "\"ui-sync/ready\"",
            """{"type":"ui-sync/session","sessionId":"s"}""",
            """{"v":2,"type":"ui-sync/session","sessionId":"s"}""",
            """{"v":"1","type":"ui-sync/session","sessionId":"s"}""",
            """{"v":1.5,"type":"ui-sync/session","sessionId":"s"}""",
            """{"v":1}""",
            """{"v":1,"type":"ui-sync/unknown"}""",
            """{"v":1,"type":"host/hello"}""",
            """{"v":1,"type":"ui-sync/ready","version":"0.1.0"}""",
            """{"v":1,"type":"ui-sync/items"}""",
            """{"v":1,"type":"ui-sync/items","items":{}}""",
            """{"v":1,"type":"ui-sync/hit-rects","dpr":2}""",
            """{"v":1,"type":"ui-sync/session"}""",
            """{"v":1,"type":"ui-sync/session","sessionId":""}""",
            """{"v":1,"type":"ui-sync/session","sessionId":7}""",
            """{"v":1,"type":"ui-sync/ack","ok":true}""",
            """{"v":1,"type":"ui-sync/ack","requestId":"t1"}""",
            """{"v":1,"type":"ui-sync/ack","requestId":"t1","ok":"yes"}""",
            """{"v":1,"type":"ui-sync/error","message":"no code"}""",
            """{"v":1,"type":"ui-sync/session","sessionId":"s"""",
        )
        for (text in bad) assertNull(parseUiSyncPageMessage(text), text)
    }

    @Test
    fun `a huge message is dropped unread`() {
        val padding = "x".repeat(600 * 1024)
        assertNull(parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/session","sessionId":"$padding"}"""))
    }

    @Test
    fun `a negative or missing dpr reads as none`() {
        val m = assertIs<UiSyncPageMessage.Ready>(
            parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/ready","mode":"phone","dpr":-1,"viewport":{"w":0,"h":10}}"""),
        )
        assertNull(m.dpr)
        assertNull(m.viewport)
        assertFalse(m.settings)
    }

    private fun sent(message: UiSyncHostMessage): JsonObject = Json.parseToJsonElement(message.encode()).jsonObject

    @Test
    fun `host messages carry the version their type and their fields`() {
        val hello = sent(UiSyncHostMessage.Hello("DSH Mobile", "0.26.0"))
        assertEquals(1, hello.getValue("v").jsonPrimitive.content.toInt())
        assertEquals("host/hello", hello.getValue("type").jsonPrimitive.content)
        assertEquals("DSH Mobile", hello.getValue("app").jsonObject.getValue("name").jsonPrimitive.content)
        assertFalse("requestId" in hello)

        val theme = sent(UiSyncHostMessage.Theme(dark = true))
        assertEquals("dark", theme.getValue("scheme").jsonPrimitive.content)
        assertEquals("light", sent(UiSyncHostMessage.Theme(dark = false)).getValue("scheme").jsonPrimitive.content)

        val follow = sent(UiSyncHostMessage.FollowSession("session-9", requestId = "f1"))
        assertEquals("host/follow-session", follow.getValue("type").jsonPrimitive.content)
        assertEquals("session-9", follow.getValue("sessionId").jsonPrimitive.content)
        assertEquals("f1", follow.getValue("requestId").jsonPrimitive.content)

        val target = sent(UiSyncHostMessage.SetTarget(pet, UiSyncTarget.DESKTOP, requestId = "t1"))
        assertEquals(pet, target.getValue("id").jsonPrimitive.content)
        assertEquals("desktop", target.getValue("target").jsonPrimitive.content)
    }

    @Test
    fun `insets the page would refuse are sent as zero`() {
        val insets = sent(UiSyncHostMessage.Insets(top = 56.5, right = -3.0, bottom = Double.NaN, left = Double.POSITIVE_INFINITY))
        assertEquals("host/insets", insets.getValue("type").jsonPrimitive.content)
        assertEquals(56.5, insets.getValue("top").jsonPrimitive.content.toDouble())
        for (edge in listOf("right", "bottom", "left")) assertEquals(0.0, insets.getValue(edge).jsonPrimitive.content.toDouble(), edge)
    }

    @Test
    fun `targets and placements round trip their wire names`() {
        for (t in UiSyncTarget.entries) assertEquals(t, UiSyncTarget.of(t.wire))
        for (p in UiSyncPlacement.entries) assertEquals(p, UiSyncPlacement.of(p.wire))
        assertNull(UiSyncTarget.of("Both"))
        assertNull(UiSyncPlacement.of(null))
    }
}
