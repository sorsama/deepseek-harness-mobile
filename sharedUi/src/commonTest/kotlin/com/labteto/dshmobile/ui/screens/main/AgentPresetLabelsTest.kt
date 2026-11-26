package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.preset_code_desc
import com.labteto.dshmobile.shared.resources.preset_code_name
import com.labteto.dshmobile.shared.resources.preset_cordis_desc
import com.labteto.dshmobile.shared.resources.preset_cordis_name
import com.labteto.dshmobile.shared.resources.preset_minimal_desc
import com.labteto.dshmobile.shared.resources.preset_minimal_name
import com.labteto.dshmobile.shared.resources.preset_standard_desc
import com.labteto.dshmobile.shared.resources.preset_standard_name
import com.labteto.dshmobile.core.wire.dto.AgentPresetEntry
import com.labteto.dshmobile.core.wire.dto.AgentPresetTrust
import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertNotNull
import com.labteto.dshmobile.testing.assertNull
import com.labteto.dshmobile.testing.assertTrue
import kotlin.test.Test

/**
 * Pins how the agent-preset roster turns into localized copy.
 *
 * The regression this guards: harness 0.2.1 sends `agentPresets/list` rows as
 * `{id, order, isDefault}` — no `trust`, no `name`, no `description`. The old rule only translated
 * `trust == SYSTEM` rows, so every shipped preset arrived UNKNOWN and the picker, the top-bar chip
 * and the session details panel all showed raw ids with no descriptions.
 */
class AgentPresetLabelsTest {

    private fun entry(
        id: String,
        trust: AgentPresetTrust = AgentPresetTrust.UNKNOWN,
        name: String? = null,
        description: String? = null,
    ) = AgentPresetEntry(id = id, trust = trust, isDefault = false, name = name, description = description)

    // ----- the roster harness 0.2.1 actually sends -----

    @Test
    fun `the shipped four resolve with no trust field and no host copy`() {
        // Exactly the rows the live host answered: id + order + isDefault, nothing else.
        val standard = builtInPresetStrings(entry("standard"))
        assertEquals(Res.string.preset_standard_name, standard?.first)
        assertEquals(Res.string.preset_standard_desc, standard?.second)

        val ptc = builtInPresetStrings(entry("ptc"))
        assertEquals(Res.string.preset_code_name, ptc?.first)
        assertEquals(Res.string.preset_code_desc, ptc?.second)

        val minimal = builtInPresetStrings(entry("minimal"))
        assertEquals(Res.string.preset_minimal_name, minimal?.first)
        assertEquals(Res.string.preset_minimal_desc, minimal?.second)

        val cordis = builtInPresetStrings(entry("cordis"))
        assertEquals(Res.string.preset_cordis_name, cordis?.first)
        assertEquals(Res.string.preset_cordis_desc, cordis?.second)
    }

    @Test
    fun `the older code id stays an alias for ptc`() {
        assertEquals(builtInPresetStrings("ptc"), builtInPresetStrings("code"))
    }

    @Test
    fun `an explicit system tier still resolves`() {
        assertEquals(Res.string.preset_standard_name, builtInPresetStrings(entry("standard", AgentPresetTrust.SYSTEM))?.first)
        assertEquals(Res.string.preset_code_name, builtInPresetStrings(entry("ptc", AgentPresetTrust.SYSTEM))?.first)
    }

    // ----- what must not be claimed as shipped -----

    @Test
    fun `an authored preset keeps its own row`() {
        // Someone's own preset is never translated, whatever it is called.
        assertNull(builtInPresetStrings(entry("my-preset", AgentPresetTrust.USER, "标准模式")))
        assertNull(builtInPresetStrings(entry("standard", AgentPresetTrust.USER, "My standard")))
    }

    @Test
    fun `an unknown id has no translation to reach for`() {
        assertNull(builtInPresetStrings("my-org-preset"))
        assertNull(builtInPresetStrings("custom"))
        assertNull(builtInPresetStrings(""))
        assertNull(builtInPresetStrings(entry("my-org-preset", AgentPresetTrust.UNKNOWN, "Anything")))
    }

    @Test
    fun `a custom preset that borrows a shipped name is not misclassified`() {
        // Same display name, different id: still the deployment's own row.
        assertNull(builtInPresetStrings(entry("team-preset", AgentPresetTrust.UNKNOWN, "Standard mode")))
        assertNull(builtInPresetStrings(entry("team-preset", AgentPresetTrust.SYSTEM, "Standard mode")))
    }
}
