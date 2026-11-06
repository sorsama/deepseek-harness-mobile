package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.SessionAddress
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app used to ask for an agent's transcript as if it were an ordinary session, which harness
 * 0.2.2 refuses. These pin how a child's identity is read from the wire and how it decides both the
 * follow address and whether the composer may send at all.
 */
class SubagentSessionTest {

    private fun projection(json: String) = WireJson.parseToJsonElement(json)

    @Test
    fun `a fork or an ordinary session is not a subagent`() {
        assertNull(subagentIdentityOf(origin = null, parentSessionId = "root", projection = null))
        assertNull(subagentIdentityOf(origin = "fork", parentSessionId = "root", projection = null))
        assertNull(subagentIdentityOf(origin = null, parentSessionId = null, projection = null))
    }

    /** Without a creator there is no address the host will validate, so there is no identity. */
    @Test
    fun `a subagent without a creator is not addressable`() {
        assertNull(subagentIdentityOf(origin = "subagent", parentSessionId = null, projection = null))
        assertNull(subagentIdentityOf(origin = "subagent", parentSessionId = "   ", projection = null))
    }

    @Test
    fun `the child's own projection supplies the real mode and label`() {
        val identity = subagentIdentityOf(
            origin = "subagent",
            parentSessionId = "parent-1",
            projection = projection("""{"mode":"continuable","label":"Device lane","seq":4}"""),
        )!!
        assertEquals("parent-1", identity.parentSessionId)
        assertEquals("continuable", identity.mode)
        assertEquals("Device lane", identity.label)
        assertTrue(identity.continuable)
    }

    @Test
    fun `a one-shot child is not continuable`() {
        val identity = subagentIdentityOf(
            origin = "subagent",
            parentSessionId = "parent-1",
            projection = projection("""{"mode":"one-shot","seq":4}"""),
        )!!
        assertEquals("one-shot", identity.mode)
        assertFalse(identity.continuable)
    }

    /**
     * The row's projection block is optional, and a cold row may not carry it yet. The address is
     * still valid with `unknown` — the host skips the mode comparison for it — while the composer
     * stays read-only until the child's own snapshot supplies the mode.
     */
    @Test
    fun `an absent or malformed projection falls back to unknown mode`() {
        val projections = listOf(
            null,
            JsonNull,
            projection("{}"),
            projection("""{"mode":"external"}"""),
            projection("""{"label":"no mode"}"""),
            projection("[]"),
        )
        for (projection in projections) {
            val identity = subagentIdentityOf("subagent", "parent-1", projection)!!
            assertEquals("unknown", identity.mode)
            assertFalse(identity.continuable)
        }
    }

    @Test
    fun `an ordinary session keeps the plain session address`() {
        assertEquals(SessionAddress.Session(sessionId = "session-1"), sessionAddressFor("session-1", null))
    }

    @Test
    fun `a child address names the creator and the mode`() {
        val address = sessionAddressFor(
            "child-1",
            SubagentIdentity(parentSessionId = "parent-1", mode = UNKNOWN_SUBAGENT_MODE),
        ) as SessionAddress.Subagent
        assertEquals("parent-1", address.parentSessionId)
        assertEquals("child-1", address.childSessionId)
        assertEquals(UNKNOWN_SUBAGENT_MODE, address.mode)
    }

    @Test
    fun `an ordinary session sends through session prompt`() {
        assertEquals(PromptTarget.Ordinary, promptTargetFor(null))
    }

    @Test
    fun `a continuable child sends through subagent delivery`() {
        val identity = SubagentIdentity("parent-1", "continuable")
        assertEquals(PromptTarget.Child(identity), promptTargetFor(identity))
    }

    @Test
    fun `a one-shot or not-yet-known child is read-only`() {
        val oneShot = promptTargetFor(SubagentIdentity("parent-1", "one-shot"))
        val unknown = promptTargetFor(SubagentIdentity("parent-1", UNKNOWN_SUBAGENT_MODE))
        assertTrue(oneShot is PromptTarget.ReadOnly)
        assertTrue(unknown is PromptTarget.ReadOnly)
    }

    private fun row(
        id: String,
        origin: String?,
        parent: String?,
        subagent: SubagentIdentity? = null,
    ) = SessionRow(
        sessionId = id,
        title = null,
        running = false,
        blank = false,
        parentSessionId = parent,
        origin = origin,
        cwd = null,
        agentPreset = null,
        updatedAt = 0L,
        pendingInteraction = null,
        subagent = subagent,
    )

    /** A row the list read has not filled in yet must not send the plain address the bug came from. */
    @Test
    fun `a row without its identity projection still addresses the child through its creator`() {
        val identity = openSubagentFor(row("child-1", origin = "subagent", parent = "parent-1"))!!
        assertEquals("parent-1", identity.parentSessionId)
        assertEquals(UNKNOWN_SUBAGENT_MODE, identity.mode)
        val address = sessionAddressFor("child-1", identity)
        assertTrue(address is SessionAddress.Subagent)
        assertEquals("parent-1", (address as SessionAddress.Subagent).parentSessionId)
    }

    @Test
    fun `an ordinary or forked row has no child identity`() {
        assertNull(openSubagentFor(null))
        assertNull(openSubagentFor(row("root", origin = null, parent = null)))
        // Forks record a creator, but they are ordinary sessions in their own right.
        assertNull(openSubagentFor(row("forked", origin = null, parent = "root")))
    }

    @Test
    fun `the parsed row identity keeps the real mode`() {
        val identity = openSubagentFor(
            row(
                "child-1",
                origin = "subagent",
                parent = "parent-1",
                subagent = SubagentIdentity("parent-1", "continuable", "Device lane"),
            ),
        )!!
        assertEquals(SUBAGENT_CONTINUABLE_MODE, identity.mode)
        assertEquals("Device lane", identity.label)
        assertTrue(identity.continuable)
    }
}
