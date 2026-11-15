package com.labteto.dshmobile.core.usage

import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelSetupProblemTest {
    private fun event(type: String, seq: Long, data: JsonObject) = SessionEventEnvelope(type, seq, seq, data)
    private fun turnStart(seq: Long, turn: Int = 1) = event("turn/start", seq, buildJsonObject { put("turn", turn) })
    private fun user(seq: Long) = event("user/message", seq, buildJsonObject {
        put("id", "u$seq"); putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "hi") }) }
        putJsonObject("source") { put("kind", "user") }
    })
    private fun assistant(seq: Long, turn: Int = 1) = event("assistant/message", seq, buildJsonObject {
        put("turn", turn); put("step", 1)
        putJsonObject("message") { put("id", "a$seq"); putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "ok") }) } }
    })
    private fun turnEnd(seq: Long, turn: Int = 1, code: String? = null, kind: String = if (code != null) "error" else "completed") = event("turn/end", seq, buildJsonObject {
        put("turn", turn)
        putJsonObject("reason") {
            put("kind", kind)
            if (code != null) putJsonObject("error") { put("message", "llm-deepseek: no API key for provider route"); put("code", code) }
        }
    })
    private fun nodes(vararg events: SessionEventEnvelope): List<ChatNode> = EventFold("s").fold(events.toList()).nodes

    @Test fun everySetupCodeIsRecognisedAndNothingElse() {
        assertEquals(ModelSetupProblem.MissingKey, modelSetupProblemOf("MISSING_CREDENTIAL"))
        assertEquals(ModelSetupProblem.InvalidKey, modelSetupProblemOf("INVALID_CREDENTIAL"))
        assertEquals(ModelSetupProblem.SignIn, modelSetupProblemOf("ACCOUNT_SIGN_IN_REQUIRED"))
        assertEquals(ModelSetupProblem.NoProvider, modelSetupProblemOf("NO_ADAPTER"))
        listOf("QUOTA", "RATE_LIMIT", "AUTH", "missing_credential", "UNKNOWN_MODEL", null, "").forEach {
            assertNull(it, modelSetupProblemOf(it))
        }
    }

    @Test fun aTurnThatFailedForWantOfAKeyIsAProblem() {
        assertEquals(ModelSetupProblem.MissingKey, modelSetupProblem(nodes(user(0), turnStart(1), turnEnd(2, code = "MISSING_CREDENTIAL"))))
        assertEquals(ModelSetupProblem.SignIn, modelSetupProblem(nodes(user(0), turnStart(1), turnEnd(2, code = "ACCOUNT_SIGN_IN_REQUIRED"))))
    }

    @Test fun theMessageTextIsNeverReadOnlyTheCode() {
        // The message names an API key, but the code is a rate limit: no setup problem.
        assertNull(modelSetupProblem(nodes(user(0), turnStart(1), turnEnd(2, code = "RATE_LIMIT"))))
        // An error with no code at all says nothing either.
        assertNull(modelSetupProblem(nodes(user(0), turnStart(1), turnEnd(2, kind = "error"))))
    }

    @Test fun anythingAfterTheFailureClearsIt() {
        val failed = arrayOf(user(0), turnStart(1), turnEnd(2, code = "MISSING_CREDENTIAL"))
        assertNull(modelSetupProblem(nodes(*failed, user(3))))
        assertNull(modelSetupProblem(nodes(*failed, user(3), turnStart(4, turn = 2))))
        assertNull(modelSetupProblem(nodes(*failed, user(3), turnStart(4, turn = 2), assistant(5, turn = 2), turnEnd(6, turn = 2))))
    }

    @Test fun aCompletedOrCancelledTurnIsNoProblem() {
        assertNull(modelSetupProblem(nodes(user(0), turnStart(1), assistant(2), turnEnd(3))))
        assertNull(modelSetupProblem(nodes(user(0), turnStart(1), turnEnd(2, kind = "aborted"))))
        assertNull(modelSetupProblem(emptyList()))
    }
}
