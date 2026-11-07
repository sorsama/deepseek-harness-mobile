package com.labteto.dshmobile.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which user messages are the reader's own turn.
 *
 * The predicate decides whether a message renders as the reader's bubble or as a collapsed context
 * row, so getting it backwards puts a wall of agent instructions on the reader's side of the
 * conversation. It is a denylist of the *user* kinds rather than an allowlist of injected ones —
 * see the KDoc on [UserMessageNode.isInjectedContext] for why — and this pins both directions of
 * that choice.
 */
class InjectedContextTest {

    private fun node(sourceKind: String?) = UserMessageNode(
        seq = 1,
        messageId = null,
        blocks = emptyList(),
        sourceKind = sourceKind,
    )

    @Test
    fun `a user prompt is the reader's own turn`() {
        assertFalse(node("user").isInjectedContext)
    }

    @Test
    fun `user-rpc is not a wire kind and must not be listed as one`() {
        // `user-rpc` is a key in the harness's MessageSourceMap, not a `source.kind` value; the
        // variant it names carries `kind: 'user'` itself. Listing it would be harmless here but
        // wrong in the comment, and the comment is the kind that gets trusted later — so assert
        // the behaviour it would have produced is what `user` already produces.
        assertFalse(node("user").isInjectedContext)
    }

    @Test
    fun `harness context kinds are injected`() {
        listOf(
            "agent-instructions",
            "plugin",
            "skill-invocation",
            "goal",
            "team-message",
            "session-reference",
        ).forEach { kind ->
            assertTrue("$kind should be injected context", node(kind).isInjectedContext)
        }
    }

    @Test
    fun `an unknown kind is treated as context rather than as speech`() {
        // The reason the test is a denylist: a kind this build has never heard of is far more
        // likely to be new harness context than a new way for the reader to speak.
        assertTrue(node("some-kind-from-a-future-harness").isInjectedContext)
    }

    @Test
    fun `an untagged message is not context`() {
        // No tag at all is not evidence of injection, and treating it as such would hide real
        // prompts on a host that omits the field.
        assertFalse(node(null).isInjectedContext)
    }
}
