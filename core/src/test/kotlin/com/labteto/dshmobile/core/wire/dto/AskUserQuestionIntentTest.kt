package com.labteto.dshmobile.core.wire.dto

import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.decodeFromString
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The intent union is open at the producer, so an unknown tag may cost presentation but never the
 * question card.
 *
 * Harness 0.2.2 added `kind: 'approval'` (experimental CUA plugin) and its own types say a UI that
 * does not know a tag renders the generic option list, with an identical answer encoding. A closed
 * decode instead threw on the whole `AskUserQuestionItem`, and both the `$events` waterfall and the
 * durable `userQuestions` projection then dropped the batch — leaving the turn blocked until
 * timeout. These tests pin both directions of the tolerant codec.
 */
class AskUserQuestionIntentTest {

    @Test
    fun `a plan-review intent still decodes as the modelled tag`() {
        // Harness 0.2.2 also grew an optional `callId` on this tag; it is presentation metadata
        // this build does not need and must not cost the decode.
        val item = decodeFromString<AskUserQuestionItem>(
            """{"id":"plan","question":"Ready to go?","detail":"# The plan",
               "options":[{"label":"Approve"},{"label":"Refuse"}],
               "intent":{"kind":"plan-review","approve":"Approve","callId":"call-1"}}""",
        )
        val intent = item.intent as? AskUserQuestionIntent.PlanReview
            ?: error("expected a plan review, got ${item.intent}")
        assertEquals("Approve", intent.approve)
        assertEquals("plan-review", intent.kind)
        assertEquals("# The plan", item.detail)
        assertEquals(listOf("Approve", "Refuse"), item.options!!.map { it.label })
    }

    @Test
    fun `an approval intent decodes as Other and keeps the whole batch answerable`() {
        val event = decodeFromString<AskUserQuestionRequestEvent>(
            """{"questions":[
                 {"id":"grant","question":"Allow this?","header":"Permission",
                  "options":[{"label":"Allow once","description":"one call"},{"label":"Deny"}],
                  "intent":{"kind":"approval","approve":"Allow once","reject":"Deny",
                            "always":"Always allow",
                            "subject":{"name":"Terminal","icon":"terminal://icon"},
                            "display":{"question":{"en":"Allow terminal access?"}}}},
                 {"id":"after","question":"Anything else?","options":[{"label":"Yes"}]}],
               "wait":{"callId":"call-1","timed":true}}""",
        )
        // The batch survived: no question was dropped by the unknown intent.
        assertEquals(2, event.questions.size)
        assertEquals("call-1", event.wait?.callId)
        val grant = event.questions[0]
        assertEquals("grant", grant.id)
        assertEquals("Allow this?", grant.question)
        assertEquals(listOf("Allow once", "Deny"), grant.options!!.map { it.label })
        assertEquals("one call", grant.options!![0].description)

        val intent = grant.intent as? AskUserQuestionIntent.Other
            ?: error("expected an unknown-tag intent, got ${grant.intent}")
        assertEquals("approval", intent.kind)
        val raw = intent.raw.jsonObject
        assertEquals("Allow once", raw.getValue("approve").jsonPrimitive.content)
        assertEquals("Deny", raw.getValue("reject").jsonPrimitive.content)
        assertEquals("Always allow", raw.getValue("always").jsonPrimitive.content)
        assertEquals("Terminal", raw.getValue("subject").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(
            "Allow terminal access?",
            raw.getValue("display").jsonObject.getValue("question").jsonObject.getValue("en").jsonPrimitive.content,
        )
        // The plan-review card must not claim it: an approval is answered by the generic flow,
        // whose options are the intent's own labels.
        assertFalse(grant.intent is AskUserQuestionIntent.PlanReview)

        assertEquals("after", event.questions[1].id)
    }

    @Test
    fun `a future intent kind decodes as Other instead of failing the batch`() {
        val item = decodeFromString<AskUserQuestionItem>(
            """{"id":"q","question":"Which?","intent":{"kind":"ranking","candidates":["a","b"]}}""",
        )
        val intent = item.intent as? AskUserQuestionIntent.Other
            ?: error("expected an unknown-tag intent, got ${item.intent}")
        assertEquals("ranking", intent.kind)
        assertEquals(2, intent.raw.jsonObject.getValue("candidates").jsonArray.size)
    }

    @Test
    fun `a plan-review intent encodes with its kind tag and round-trips`() {
        val encoded = encodeToJsonElement(
            AskUserQuestionIntent.serializer(),
            AskUserQuestionIntent.PlanReview(approve = "Approve"),
        ).jsonObject
        assertEquals(setOf("kind", "approve"), encoded.keys)
        assertEquals("plan-review", encoded.getValue("kind").jsonPrimitive.content)
        assertEquals("Approve", encoded.getValue("approve").jsonPrimitive.content)

        val decoded = decodeFromJsonElement<AskUserQuestionIntent>(encoded)
        assertTrue(decoded is AskUserQuestionIntent.PlanReview)
        assertEquals("Approve", (decoded as AskUserQuestionIntent.PlanReview).approve)
    }

    @Test
    fun `an Other intent encodes its raw object verbatim and round-trips`() {
        val raw = WireJson.parseToJsonElement(
            """{"kind":"approval","approve":"Allow once","reject":"Deny",
               "subject":{"name":"Terminal"}}""",
        ).jsonObject
        val encoded = encodeToJsonElement(
            AskUserQuestionIntent.serializer(),
            AskUserQuestionIntent.Other(kind = "approval", raw = raw),
        )
        // Byte-for-byte the object that arrived: no re-shaping, no dropped metadata.
        assertEquals(raw, encoded)

        val decoded = decodeFromJsonElement<AskUserQuestionIntent>(encoded)
        assertTrue(decoded is AskUserQuestionIntent.Other)
        assertEquals("approval", (decoded as AskUserQuestionIntent.Other).kind)
        assertEquals(raw, decoded.raw)
    }

    /**
     * The item-level encoding is what the client *reads*, never sends, but the host's answer
     * correlation is keyed by id and its label check by the strings it sent — so a re-encode must
     * not disturb them.
     */
    @Test
    fun `re-encoding an approved item keeps its unknown intent and options intact`() {
        val json = """{"id":"grant","question":"Allow this?",
                       "options":[{"label":"Allow once"},{"label":"Deny"}],
                       "intent":{"kind":"approval","approve":"Allow once","reject":"Deny"}}"""
        val item = decodeFromString<AskUserQuestionItem>(json)
        val reEncoded = encodeToJsonElement(AskUserQuestionItem.serializer(), item).jsonObject
        assertEquals("grant", reEncoded.getValue("id").jsonPrimitive.content)
        val intent = reEncoded.getValue("intent").jsonObject
        assertEquals("approval", intent.getValue("kind").jsonPrimitive.content)
        assertEquals("Deny", intent.getValue("reject").jsonPrimitive.content)
        assertEquals(2, reEncoded.getValue("options").jsonArray.size)
        // Nulls stay absent: the host's schema takes an omitted optional, not a null.
        assertFalse(reEncoded.containsKey("header"))
    }
}
