package com.labteto.dshmobile.core.uisync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The companion 0.2.0 additions to protocol 1 (docs/BRIDGE.md): the feature list, focus, dialogs,
 * Back with its result, reduced motion and blur. Names avoid commas and slashes: Kotlin/Native
 * refuses them in test names.
 */
class UiSyncProtocolFeaturesTest {
    private val treat = "dsh-ui-sync-example-pet|conversation.input.right|example-pet.treat"

    @Test
    fun `ready lists the features a 0 2 0 page adds`() {
        val ready = assertIs<UiSyncPageMessage.Ready>(
            parseUiSyncPageMessage(
                """{"v":1,"type":"ui-sync/ready","version":"0.2.0","protocol":1,"features":["a11y","focus","back","motion","follow-wait","item-store","later-thing",7],"mode":"phone","viewport":{"w":390,"h":844},"dpr":2,"settings":true}""",
            ),
        )
        assertEquals(
            setOf(UiSyncFeature.A11Y, UiSyncFeature.FOCUS, UiSyncFeature.BACK, UiSyncFeature.MOTION, UiSyncFeature.FOLLOW_WAIT, UiSyncFeature.ITEM_STORE, "later-thing"),
            ready.features,
        )
    }

    @Test
    fun `a 0 1 0 ready without features has none`() {
        val ready = assertIs<UiSyncPageMessage.Ready>(
            parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/ready","version":"0.1.0","protocol":1,"mode":"phone","settings":false}"""),
        )
        assertTrue(ready.features.isEmpty())
        val broken = assertIs<UiSyncPageMessage.Ready>(
            parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/ready","version":"0.2.0","features":"focus","mode":"phone"}"""),
        )
        assertTrue(broken.features.isEmpty())
    }

    @Test
    fun `focus in a lifted text field and out of the page`() {
        assertEquals(
            UiSyncPageMessage.Focus(editable = true, itemId = treat),
            parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/focus","editable":true,"itemId":"$treat"}"""),
        )
        assertEquals(
            UiSyncPageMessage.Focus(editable = false, itemId = null),
            parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/focus","editable":false,"itemId":null}"""),
        )
        assertEquals(
            UiSyncPageMessage.Focus(editable = false, itemId = treat),
            parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/focus","editable":false,"itemId":"$treat"}"""),
        )
    }

    @Test
    fun `an editable field with no item never asks for the keyboard`() {
        // A field outside every lifted item would be in the hidden desktop tree.
        assertEquals(
            UiSyncPageMessage.Focus(editable = false, itemId = null),
            parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/focus","editable":true,"itemId":null}"""),
        )
    }

    @Test
    fun `a broken focus message is dropped`() {
        assertNull(parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/focus","itemId":null}"""))
        assertNull(parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/focus","editable":"yes","itemId":null}"""))
        assertNull(parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/focus","editable":true,"itemId":5}"""))
    }

    @Test
    fun `dialogs keep the ids in order and skip what is not one`() {
        assertEquals(
            UiSyncPageMessage.Dialogs(listOf(treat, "b")),
            parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/dialogs","items":["$treat","",3,null,"b"]}"""),
        )
        assertEquals(UiSyncPageMessage.Dialogs(emptyList()), parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/dialogs","items":[]}"""))
        assertNull(parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/dialogs"}"""))
    }

    @Test
    fun `a Back ack carries whether a dialog closed`() {
        val handled = assertIs<UiSyncPageMessage.Ack>(
            parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/ack","requestId":"b1","ok":true,"result":{"handled":true}}"""),
        )
        assertEquals(true, handled.handled)
        val ignored = assertIs<UiSyncPageMessage.Ack>(
            parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/ack","requestId":"b2","ok":true,"result":{"handled":false}}"""),
        )
        assertEquals(false, ignored.handled)
        val plain = assertIs<UiSyncPageMessage.Ack>(parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/ack","requestId":"r","ok":true}"""))
        assertNull(plain.handled)
    }

    @Test
    fun `follow refusal codes are read`() {
        val ack = assertIs<UiSyncPageMessage.Ack>(
            parseUiSyncPageMessage("""{"v":1,"type":"ui-sync/ack","requestId":"f1","ok":false,"error":{"code":"ui-sync/not-ready","message":"x"}}"""),
        )
        assertFalse(ack.ok)
        assertEquals(UiSyncErrorCode.NOT_READY, ack.errorCode)
    }

    @Test
    fun `back motion and blur encode as the page expects`() {
        val back = Json.parseToJsonElement(UiSyncHostMessage.Back("b7").encode()).jsonObject
        assertEquals("host/back", back.getValue("type").jsonPrimitive.content)
        assertEquals("b7", back.getValue("requestId").jsonPrimitive.content)
        assertEquals(1, back.getValue("v").jsonPrimitive.content.toInt())

        val motion = Json.parseToJsonElement(UiSyncHostMessage.Motion(reduce = true).encode()).jsonObject
        assertEquals("host/motion", motion.getValue("type").jsonPrimitive.content)
        assertEquals("true", motion.getValue("reduce").jsonPrimitive.content)
        assertFalse("requestId" in motion)

        val blur = Json.parseToJsonElement(UiSyncHostMessage.Blur().encode()).jsonObject
        assertEquals(setOf("v", "type"), blur.keys)
        assertEquals("host/blur", blur.getValue("type").jsonPrimitive.content)
    }
}
