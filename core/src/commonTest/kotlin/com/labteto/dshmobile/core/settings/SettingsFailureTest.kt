package com.labteto.dshmobile.core.settings

import com.labteto.dshmobile.core.wire.RpcError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The refusals a real harness sent, and how each is worded to a person. */
class SettingsFailureTest {

    @Test
    fun `a value the schema refuses is a rejection that keeps the harness words as detail`() {
        val failure = refusal(SettingsFixtures.MUTATE_AGENT_LOOP_INVALID).toSettingsFailure()
        val rejected = assertIs<SettingsFailure.Rejected>(failure)
        assertTrue(rejected.detail.contains("expected number >= 1 but got 0"))
    }

    @Test
    fun `a value of the wrong type is a rejection too`() {
        assertIs<SettingsFailure.Rejected>(refusal(SettingsFixtures.MUTATE_AGENT_LOOP_WRONG_TYPE).toSettingsFailure())
        assertIs<SettingsFailure.Rejected>(refusal(SettingsFixtures.MUTATE_WEB_API_COUNT_INVALID).toSettingsFailure())
    }

    @Test
    fun `a field that is not in the schema is a rejection`() {
        assertIs<SettingsFailure.Rejected>(refusal(SettingsFixtures.MUTATE_UNKNOWN_FIELD).toSettingsFailure())
        assertIs<SettingsFailure.Rejected>(refusal(SettingsFixtures.MUTATE_UNKNOWN_NAMESPACE).toSettingsFailure())
    }

    @Test
    fun `a stale revision is a conflict`() {
        val error = refusal(SettingsFixtures.MUTATE_AGENT_LOOP_STALE)
        assertEquals("settings/conflict", error.code)
        assertEquals(SettingsFailure.Conflict, error.toSettingsFailure())
    }

    @Test
    fun `a harness without the call is unavailable whichever way it says so`() {
        for (code in listOf("capability-unavailable", "gateway/invocation-unavailable", "gateway/definition-unavailable", "method-not-found", "-32601")) {
            assertEquals(SettingsFailure.Unavailable, RpcError(code, "no").toSettingsFailure(), code)
        }
    }

    @Test
    fun `a bad argument or any other business refusal is a rejection`() {
        assertIs<SettingsFailure.Rejected>(RpcError("gateway/arguments-invalid", "bad").toSettingsFailure())
        assertIs<SettingsFailure.Rejected>(RpcError("credentials/not-writable", "read only").toSettingsFailure())
    }

    @Test
    fun `a broken carrier and a refused session are unreachable`() {
        assertEquals(SettingsFailure.Unreachable("socket closed"), RpcError("internal", "socket closed").toSettingsFailure())
        assertEquals(SettingsFailure.Unreachable("no session"), RpcError("unauthenticated", "no session").toSettingsFailure())
        assertEquals(SettingsFailure.Unreachable("fenced"), RpcError("forbidden", "fenced").toSettingsFailure())
    }

    @Test
    fun `a key is taken out of what the harness echoes back`() {
        val echoed = SettingsFailure.Rejected("invalid key sk-abc123 for DEEPSEEK_API_KEY")
        assertEquals(SettingsFailure.Rejected("invalid key ••• for DEEPSEEK_API_KEY"), echoed.masked("sk-abc123"))
        assertEquals(echoed, echoed.masked(null))
        assertEquals(echoed, echoed.masked(""))
        assertEquals(SettingsFailure.Conflict, SettingsFailure.Conflict.masked("sk-abc123"))
        assertEquals("a ••• b •••", maskSecret("a k b k", "k"))
        assertEquals("unchanged", maskSecret("unchanged", null))
    }
}
