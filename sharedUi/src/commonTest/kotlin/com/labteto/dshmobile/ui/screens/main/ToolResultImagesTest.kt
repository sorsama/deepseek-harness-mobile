package com.labteto.dshmobile.ui.screens.main

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultImagesTest {
    @Test fun `read_image result yields its durable attachment`() {
        val content = Json.parseToJsonElement(
            """[{"type":"text","text":"<path>a.png</path>"},
               {"type":"image","attachment":{"attachmentId":"sha256:abc","mediaType":"image/png","bytes":10,"width":640,"height":480,"name":"a.png"}}]""",
        )
        val images = toolResultImages(content)
        assertEquals(listOf("sha256:abc"), images.map { it.attachmentId })
        assertEquals(640 to 480, images.single().width to images.single().height)
    }

    @Test fun `text, malformed and bare-string results yield no images`() {
        assertTrue(toolResultImages(null).isEmpty())
        assertTrue(toolResultImages(JsonPrimitive("plain output")).isEmpty())
        assertTrue(toolResultImages(Json.parseToJsonElement("""[{"type":"text","text":"x"}]""")).isEmpty())
        assertTrue(toolResultImages(Json.parseToJsonElement("""[{"type":"image","attachment":{"attachmentId":"x"}}]""")).isEmpty())
        assertTrue(toolResultImages(Json.parseToJsonElement("""[{"type":"image"}]""")).isEmpty())
    }
}
