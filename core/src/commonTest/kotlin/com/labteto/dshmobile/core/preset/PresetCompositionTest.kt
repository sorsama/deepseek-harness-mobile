package com.labteto.dshmobile.core.preset

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the composition YAML that `agentPresets/read` answers with.
 *
 * Fixture source: `packages/preset/agent-preset-registry/tests/registry.spec.ts` in harness
 * 0.2.1-rc.2, "renders a declaration as entry-list YAML for reading, conditions included". It asserts
 * the document starts with `- id: contribute\n  name: ` and contains
 * `\n  config:\n    tool: standard\n- id: windows-only\n  name: missing\n  disabled: !!js process.platform !== 'win32'\n`.
 * The module name between `name:` and `config:` is the plugin's package, taken here as the
 * `@deepseek-ai/dsh-agent-preset` row name the registry README documents.
 */
class PresetCompositionTest {

    private val registryDocument =
        "- id: contribute\n" +
            "  name: '@deepseek-ai/dsh-agent-preset-contribute'\n" +
            "  config:\n" +
            "    tool: standard\n" +
            "- id: windows-only\n" +
            "  name: missing\n" +
            "  disabled: !!js process.platform !== 'win32'\n"

    @Test
    fun theRegistryDocumentSplitsIntoItsTwoRows() {
        val composition = parsePresetComposition(registryDocument)
        assertEquals(2, composition.entries.size)
        assertEquals("", composition.preamble)
        val first = composition.entries[0]
        assertEquals("contribute", first.id)
        assertEquals("@deepseek-ai/dsh-agent-preset-contribute", first.module)
        assertEquals(PresetEntryState.Enabled, first.state)
        val second = composition.entries[1]
        assertEquals("windows-only", second.id)
        assertEquals("missing", second.module)
        assertEquals(PresetEntryState.Conditional("process.platform !== 'win32'"), second.state)
    }

    @Test
    fun aJsConditionKeepsItsExpressionWithoutTheTag() {
        val state = parsePresetComposition(registryDocument).entries[1].state
        assertTrue(state is PresetEntryState.Conditional)
        assertEquals("process.platform !== 'win32'", state.expression)
    }

    @Test
    fun renderingGivesTheDocumentBackByteForByte() {
        assertEquals(registryDocument, parsePresetComposition(registryDocument).render())
    }

    @Test
    fun aLineTheReaderDoesNotUnderstandIsKeptInItsEntry() {
        // An unknown key, a nested block and a block scalar: none is interpreted, all survive.
        val document =
            "- id: a\n" +
                "  name: pkg-a\n" +
                "  futureKey: [1, 2, 3]\n" +
                "  config:\n" +
                "    prompt: |\n" +
                "      - not an entry\n" +
                "      second line\n" +
                "- id: b\n" +
                "  name: pkg-b\n"
        val composition = parsePresetComposition(document)
        assertEquals(2, composition.entries.size)
        assertTrue(composition.entries[0].text.contains("futureKey: [1, 2, 3]"))
        assertTrue(composition.entries[0].text.contains("      - not an entry\n"))
        assertEquals(document, composition.render())
    }

    @Test
    fun aDeeperKeyNamedIdDoesNotReplaceTheRowsOwnId() {
        val document =
            "- id: outer\n" +
                "  name: pkg\n" +
                "  config:\n" +
                "    id: inner\n" +
                "    name: other\n"
        val entry = parsePresetComposition(document).entries.single()
        assertEquals("outer", entry.id)
        assertEquals("pkg", entry.module)
    }

    @Test
    fun disabledTrueAndFalseReadAsOffAndOn() {
        val document =
            "- id: a\n  name: x\n  disabled: true\n" +
                "- id: b\n  name: y\n  disabled: false\n"
        val entries = parsePresetComposition(document).entries
        assertEquals(PresetEntryState.Disabled, entries[0].state)
        assertEquals(PresetEntryState.Enabled, entries[1].state)
    }

    @Test
    fun quotedValuesLoseTheirQuotes() {
        val document = "- id: \"quoted id\"\n  name: 'it''s'\n"
        val entry = parsePresetComposition(document).entries.single()
        assertEquals("quoted id", entry.id)
        assertEquals("it's", entry.module)
    }

    @Test
    fun aRowWithoutIdOrNameStillBecomesAnEntry() {
        val entry = parsePresetComposition("- config:\n    a: 1\n").entries.single()
        assertNull(entry.id)
        assertNull(entry.module)
        assertEquals(PresetEntryState.Enabled, entry.state)
    }

    @Test
    fun aCommentBeforeTheFirstRowStaysInThePreamble() {
        val document = "# composed by the host\n- id: a\n  name: x\n"
        val composition = parsePresetComposition(document)
        assertEquals("# composed by the host\n", composition.preamble)
        assertEquals(1, composition.entries.size)
        assertEquals(document, composition.render())
    }

    @Test
    fun anEmptyListHasNoEntriesAndIsKeptWhole() {
        val composition = parsePresetComposition("[]\n")
        assertTrue(composition.entries.isEmpty())
        assertEquals("[]\n", composition.preamble)
        assertEquals("[]\n", composition.render())
    }

    @Test
    fun anEmptyStringIsEmpty() {
        val composition = parsePresetComposition("")
        assertTrue(composition.entries.isEmpty())
        assertEquals("", composition.render())
    }

    @Test
    fun windowsLineBreaksSurviveAndStillSplit() {
        val document = "- id: a\r\n  name: x\r\n- id: b\r\n  name: y\r\n"
        val composition = parsePresetComposition(document)
        assertEquals(2, composition.entries.size)
        assertEquals("a", composition.entries[0].id)
        assertEquals("y", composition.entries[1].module)
        assertEquals(document, composition.render())
    }

    @Test
    fun aDocumentWithoutAFinalLineBreakRoundTrips() {
        val document = "- id: a\n  name: x"
        val composition = parsePresetComposition(document)
        assertEquals("x", composition.entries.single().module)
        assertEquals(document, composition.render())
    }
}
