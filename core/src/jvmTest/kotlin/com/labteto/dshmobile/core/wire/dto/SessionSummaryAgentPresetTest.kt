package com.labteto.dshmobile.core.wire.dto

import com.labteto.dshmobile.core.wire.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The harness publishes a session's agent preset inside `projections.values.agentPreset`, never on
 * the `SessionSummary` row itself, so the effective preset has to come from the projection. These
 * tests pin both the wire contract and the fallback the UI surfaces depend on.
 */
class SessionSummaryAgentPresetTest {

    @Test
    fun `effective preset comes from the projection when the row has no top-level field`() {
        // This is the shape every harness version (0.1.6-alpha.1, 0.1.7-rc.x, 0.2.0-rc.1 and
        // master at the time of writing) answers `session/list` with.
        val summary = decodeFromString<SessionSummary>(
            """{"sessionId":"session-1","updatedAt":1,"running":false,"blank":true,
                "projections":{"asOfSeq":2,"values":{"agentPreset":"minimal"}}}""",
        )
        assertNull(summary.agentPreset)
        assertEquals("minimal", summary.agentPresetEffective)
    }

    @Test
    fun `a top-level preset wins when a host does send one`() {
        val summary = decodeFromString<SessionSummary>(
            """{"sessionId":"session-1","updatedAt":1,"running":false,"blank":true,
                "agentPreset":"standard","projections":{"asOfSeq":2,"values":{"agentPreset":"minimal"}}}""",
        )
        assertEquals("standard", summary.agentPresetEffective)
    }

    @Test
    fun `no projection and no top-level field means no preset`() {
        val summary = decodeFromString<SessionSummary>(
            """{"sessionId":"session-1","updatedAt":1,"running":false,"blank":true}""",
        )
        assertNull(summary.agentPresetEffective)
    }
}
