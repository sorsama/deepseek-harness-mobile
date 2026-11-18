package com.labteto.dshmobile.ui.screens.harnesssettings

import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.settings.FieldTraits
import com.labteto.dshmobile.core.settings.SettingsNamespaces
import com.labteto.dshmobile.core.settings.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words and the extra rules the five known pages bring, and the names of the pages. */
class HarnessSettingsTextTest {

    /** Every field each known page shows, as (namespace, key). */
    private val knownFields = listOf(
        SettingsNamespaces.AGENT_LOOP to "maxParallelToolCalls",
        SettingsNamespaces.SESSION_LOG to "enabled",
        SettingsNamespaces.BASH to "timeoutMs",
        SettingsNamespaces.BASH to "maxOutputBytes",
        SettingsNamespaces.PWSH to "timeoutMs",
        SettingsNamespaces.PWSH to "maxOutputBytes",
        SettingsNamespaces.SUBAGENT to "maxDepth",
        SettingsNamespaces.SUBAGENT to "maxActiveSubagents",
        SettingsNamespaces.WEB_SEARCH to "baseURL",
        SettingsNamespaces.WEB_SEARCH to "maxUses",
        SettingsNamespaces.WEB_API to "baseURL",
        SettingsNamespaces.WEB_API to "count",
    )

    @Test
    fun `every field of a known page has its own label and help text`() {
        knownFields.forEach { (ns, key) ->
            val words = knownFieldText(ns, key)
            assertNotNull("$ns/$key", words)
            assertNotNull("$ns/$key label", words!!.labelRes)
            assertNotNull("$ns/$key hint", words.hint)
        }
    }

    @Test
    fun `a field the app has no words for is not claimed`() {
        assertNull(knownFieldText("plugin-x", "anything"))
        assertNull(knownFieldText(SettingsNamespaces.AGENT_LOOP, "somethingNew"))
        // The shell executors carry more fields than the page edits.
        assertNull(knownFieldText(SettingsNamespaces.PWSH, "graceMs"))
        assertNull(knownFieldText(SettingsNamespaces.WEB_API, "sendOriginalQuestion"))
    }

    @Test
    fun `the shell limits are whole numbers of at least one with their units`() {
        listOf(SettingsNamespaces.BASH, SettingsNamespaces.PWSH).forEach { ns ->
            val timeout = knownFieldText(ns, "timeoutMs")!!
            assertEquals(FieldTraits(whole = true, min = 1.0), timeout.traits)
            assertEquals(R.string.hs_unit_ms, timeout.unit)
            val output = knownFieldText(ns, "maxOutputBytes")!!
            assertEquals(FieldTraits(whole = true, min = 1.0), output.traits)
            assertEquals(R.string.hs_unit_bytes, output.unit)
        }
    }

    @Test
    fun `the search addresses must be http addresses and read as code`() {
        listOf(SettingsNamespaces.WEB_SEARCH to MESSAGES_EXAMPLE, SettingsNamespaces.WEB_API to SEARCH_EXAMPLE).forEach { (ns, example) ->
            val words = knownFieldText(ns, "baseURL")!!
            assertTrue(ns, words.traits.url)
            assertTrue(ns, words.technical)
            // The example is passed through technicalDisplay by the screen.
            assertEquals(example, words.hintArg)
        }
        assertFalse(knownFieldText(SettingsNamespaces.WEB_API, "count")!!.technical)
    }

    @Test
    fun `the page names and one line summaries are the app's own for the known pages`() {
        val known = listOf(SettingsPage.Account, SettingsPage.WebSearch, SettingsPage.Shell, SettingsPage.Subagents, SettingsPage.AgentLoop, SettingsPage.SessionLog)
        known.forEach {
            assertNotNull(it.key, pageTitleRes(it))
            assertNotNull(it.key, pageSummaryRes(it))
        }
        assertEquals(known.size, known.map { pageTitleRes(it) }.toSet().size)
        // Any other page is named from its namespace, with nothing to summarise.
        assertNull(pageTitleRes(SettingsPage.Generic("plugin-x")))
        assertNull(pageSummaryRes(SettingsPage.Generic("plugin-x")))
        assertEquals("Plugin x", genericPageTitle(SettingsPage.Generic("plugin-x")))
        assertEquals("Web search deepseek", genericPageTitle(SettingsPage.Generic("web-search-deepseek")))
    }

    @Test
    fun `a limit is written the way a person writes it`() {
        assertEquals("50", formatBound(50.0))
        assertEquals("0", formatBound(0.0))
        assertEquals("0.5", formatBound(0.5))
        assertEquals("-3", formatBound(-3.0))
        assertEquals("9007199254740991", formatBound(9007199254740991.0))
    }
}
