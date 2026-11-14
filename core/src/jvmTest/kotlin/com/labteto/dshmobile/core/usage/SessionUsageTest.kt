package com.labteto.dshmobile.core.usage

import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.session.TurnEndNode
import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.*
import org.junit.Test

class SessionUsageTest {
    private fun event(type: String, seq: Long, data: JsonObject) = SessionEventEnvelope(type, seq, seq, data)
    private fun turnStart(seq: Long, turn: Int = 1) = event("turn/start", seq, buildJsonObject { put("turn", turn) })
    private fun user(seq: Long) = event("user/message", seq, buildJsonObject {
        put("id", "u$seq"); putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "hi") }) }
        putJsonObject("source") { put("kind", "user") }
    })
    private fun assistant(seq: Long, turn: Int = 1, usage: JsonObject? = null) = event("assistant/message", seq, buildJsonObject {
        put("turn", turn); put("step", 1)
        putJsonObject("message") { put("id", "a$seq"); putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "ok") }) } }
        if (usage != null) put("usage", usage)
    })
    private fun retry(seq: Long, code: String, provider: String = "opencode-go", turn: Int = 1) = event("llm/retry", seq, buildJsonObject {
        put("retryId", "r$seq"); put("turn", turn); put("step", 1); put("provider", provider); put("retry", 1); put("delayMs", 2000)
        putJsonObject("failure") { put("message", "slow down"); put("code", code) }
    })
    private fun retryStarted(seq: Long) = event("llm/retry-started", seq, buildJsonObject { put("retryId", "r"); put("turn", 1); put("step", 1); put("retry", 1) })
    private fun turnEnd(seq: Long, turn: Int = 1, code: String? = null, kind: String = if (code != null) "error" else "completed") = event("turn/end", seq, buildJsonObject {
        put("turn", turn)
        putJsonObject("reason") {
            put("kind", kind)
            if (code != null) putJsonObject("error") { put("message", "provider said no"); put("code", code) }
        }
    })
    private fun nodes(vararg events: SessionEventEnvelope): List<ChatNode> = EventFold("s").fold(events.toList()).nodes

    @Test fun theFoldKeepsTheFailureCodeOfAnErrorEnd() {
        val end = nodes(turnStart(0), turnEnd(1, code = "ACCOUNT_QUOTA")).filterIsInstance<TurnEndNode>().single()
        assertEquals("error", end.reasonKind); assertEquals("ACCOUNT_QUOTA", end.failureCode)
        assertNull(nodes(turnStart(0), turnEnd(1)).filterIsInstance<TurnEndNode>().single().failureCode)
    }

    @Test fun everyLimitCodeIsRecognisedAndNothingElse() {
        assertEquals(LimitKind.QUOTA, limitKindOf("QUOTA")); assertEquals(LimitKind.QUOTA, limitKindOf("ACCOUNT_QUOTA"))
        assertEquals(LimitKind.RATE_LIMIT, limitKindOf("RATE_LIMIT"))
        listOf("AUTH", "TIMEOUT", "SERVER", "HTTP_429", "quota", null, "").forEach { assertNull(it, limitKindOf(it)) }
    }

    @Test fun aTurnThatEndedOnAQuotaErrorIsALimitHit() {
        assertEquals(LimitHit(LimitKind.QUOTA, null), limitHit(nodes(turnStart(0), user(1), turnEnd(2, code = "QUOTA"))))
        assertEquals(LimitKind.QUOTA, limitHit(nodes(turnStart(0), user(1), turnEnd(2, code = "ACCOUNT_QUOTA")))?.kind)
        assertEquals(LimitKind.RATE_LIMIT, limitHit(nodes(turnStart(0), user(1), turnEnd(2, code = "RATE_LIMIT")))?.kind)
    }

    @Test fun theProviderComesFromTheRetriesOfThatTurn() {
        val hit = limitHit(nodes(turnStart(0, 4), user(1), retry(2, "RATE_LIMIT", "opencode-go", turn = 4), retryStarted(3), retry(4, "RATE_LIMIT", "opencode-go", turn = 4), turnEnd(5, 4, "RATE_LIMIT")))
        assertEquals(LimitHit(LimitKind.RATE_LIMIT, "opencode-go"), hit)
        // A retry from an earlier turn is not this turn's provider.
        assertNull(limitHit(nodes(turnStart(0, 1), retry(1, "RATE_LIMIT", "codex", turn = 1), turnEnd(2, 1), turnStart(3, 2), turnEnd(4, 2, "QUOTA")))?.provider)
    }

    @Test fun otherFailuresAndOrdinaryEndsAreNotLimits() {
        assertNull(limitHit(nodes(turnStart(0), user(1), turnEnd(2, code = "AUTH"))))
        assertNull(limitHit(nodes(turnStart(0), user(1), assistant(2), turnEnd(3))))
        assertNull(limitHit(nodes(turnStart(0), user(1), turnEnd(2, kind = "interrupted"))))
        assertNull(limitHit(emptyList()))
    }

    @Test fun aScheduledRetryForALimitShowsWhileTheHarnessBacksOff() {
        assertEquals(LimitHit(LimitKind.RATE_LIMIT, "opencode-go"), limitHit(nodes(turnStart(0), user(1), retry(2, "RATE_LIMIT"))))
        // The attempt starting again does not hide it: the line holds steady between retries.
        assertEquals(LimitKind.RATE_LIMIT, limitHit(nodes(turnStart(0), user(1), retry(2, "RATE_LIMIT"), retryStarted(3)))?.kind)
        assertNull("a retry for an unrelated failure", limitHit(nodes(turnStart(0), user(1), retry(2, "TIMEOUT"))))
    }

    @Test fun anythingThatGotThroughClearsTheLine() {
        val failed = arrayOf(turnStart(0), user(1), turnEnd(2, code = "QUOTA"))
        assertNotNull(limitHit(nodes(*failed)))
        assertNull("a new prompt", limitHit(nodes(*failed, turnStart(3, 2), user(4))))
        assertNull("the retried request answered", limitHit(nodes(turnStart(0), user(1), retry(2, "RATE_LIMIT"), assistant(3))))
        assertNull("the next turn began", limitHit(nodes(*failed, turnStart(3, 2))))
    }

    @Test fun aLimitNamedForAnotherProviderIsNotShownOnThisOne() {
        val hit = LimitHit(LimitKind.QUOTA, "codex")
        assertEquals(hit, hit.appliesTo("codex"))
        assertNull(hit.appliesTo("opencode-go"))
        assertEquals("a failure that named no route is the session's", LimitHit(LimitKind.QUOTA), LimitHit(LimitKind.QUOTA).appliesTo("opencode-go"))
        assertNull((null as LimitHit?).appliesTo("codex"))
    }

    @Test fun tokensFoldFromLoadedMessagesForAHostWithoutTheProjection() {
        fun usage(input: Int, output: Int, read: Int? = null) = buildJsonObject { put("inputTokens", input); put("outputTokens", output); if (read != null) put("cacheReadTokens", read) }
        val folded = tokensFromNodes(nodes(turnStart(0), assistant(1, usage = usage(100, 20, 300)), assistant(2, usage = usage(50, 5)), assistant(3)))!!
        assertEquals(150L, folded.uncachedInputTokens); assertEquals(25L, folded.outputTokens); assertEquals(300L, folded.cacheReadTokens)
        assertEquals(450L, folded.inputTokens); assertEquals(475L, folded.inputTokens + folded.outputTokens)
        assertNull("no message reported usage", tokensFromNodes(nodes(turnStart(0), assistant(1))))
    }

    @Test fun theQuotaSelectionIsTheLastUsedOneWhileATurnRuns() {
        val selection = ModelSelectionProjection(lastUsed = ModelSelection("opencode-go", "deepseek-v4-flash"), next = ModelSelection("codex", "gpt-5"))
        assertEquals("opencode-go", quotaSelectionOf(selection, running = true)?.provider)
        assertEquals("codex", quotaSelectionOf(selection, running = false)?.provider)
        assertEquals("opencode-go", quotaSelectionOf(ModelSelectionProjection(lastUsed = selection.lastUsed), running = false)?.provider)
        assertNull(quotaSelectionOf(null, running = false))
        assertNull(quotaSelectionOf(ModelSelectionProjection(next = selection.next), running = true))
    }

    @Test fun routeLabelPrefersTheCatalogNamesAndFallsBackToIds() {
        val models = SessionModelsValue(
            current = ModelSelection("opencode-go", "deepseek-v4-flash"), routable = true,
            groups = listOf(ModelProviderGroup("opencode-go", "OpenCode Go", listOf(ModelCatalogModel("deepseek-v4-flash", "DeepSeek V4 Flash")))),
        )
        assertEquals("OpenCode Go · DeepSeek V4 Flash", routeLabel(models, ModelSelection("opencode-go", "deepseek-v4-flash")))
        assertEquals("OpenCode Go · other-model", routeLabel(models, ModelSelection("opencode-go", "other-model")))
        assertEquals("deepseek-official · deepseek-v4-pro", routeLabel(models, ModelSelection("deepseek-official", "deepseek-v4-pro")))
        assertEquals("codex · gpt-5", routeLabel(null, ModelSelection("codex", "gpt-5")))
        assertNull(routeLabel(models, null))
    }
}
