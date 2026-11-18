package com.labteto.dshmobile.core.settings

import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.ModelCatalog
import com.labteto.dshmobile.core.wire.dto.ModelCatalogModel
import com.labteto.dshmobile.core.wire.dto.ModelProviderGroup
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.core.wire.dto.SettingsPathOpView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which pages a harness gets, and the rules of the two pages that are more than one field. */
class SettingsPagesTest {

    private fun describe(vararg namespaces: String) = SettingsDescribeValue(
        writable = true, hasDocument = true,
        namespaces = namespaces.map { fixtureRow(it) },
    )

    @Test
    fun `a harness that describes the five namespaces gets the five pages in order`() {
        assertEquals(
            listOf(SettingsPage.WebSearch, SettingsPage.Shell, SettingsPage.Subagents, SettingsPage.AgentLoop, SettingsPage.SessionLog),
            settingsPages(describedFixture()),
        )
    }

    @Test
    fun `the namespaces another surface owns get no generic page`() {
        // agent-default-model and permission are described, but the harness marks them not for a client-made page.
        val generic = settingsPages(describedFixture()).filterIsInstance<SettingsPage.Generic>()
        assertEquals(emptyList(), generic)
    }

    @Test
    fun `only the pages whose namespace is described are offered`() {
        assertEquals(emptyList(), settingsPages(SettingsDescribeValue(writable = true, hasDocument = false)))
        assertEquals(listOf(SettingsPage.AgentLoop), settingsPages(describe("agent-loop", "permission")))
        assertEquals(listOf(SettingsPage.SessionLog), settingsPages(describe("session-log-deepseek")))
    }

    @Test
    fun `web search needs its own namespace not just the selection`() {
        assertEquals(emptyList(), settingsPages(describe("web", "web-deepseek-api")))
        assertEquals(listOf(SettingsPage.WebSearch), settingsPages(describe("web-search-deepseek")))
    }

    @Test
    fun `either subagent namespace brings the subagent page`() {
        assertEquals(listOf(SettingsPage.Subagents), settingsPages(describe("subagent")))
        assertEquals(listOf(SettingsPage.Subagents), settingsPages(describe("subagent-model-selection-settings")))
    }

    @Test
    fun `the shell page binds the executor the harness composed`() {
        assertEquals("pwsh-sandbox", shellNamespace(listOf("pwsh-sandbox")))
        assertEquals("bash-sandbox", shellNamespace(listOf("bash-sandbox", "agent-loop")))
        assertEquals("pwsh-sandbox", shellNamespace(listOf("bash-sandbox", "pwsh-sandbox")))
        assertNull(shellNamespace(listOf("agent-loop")))
        val onPhone = SettingsDescribeValue(writable = true, hasDocument = true, namespaces = listOf(fixtureRow("bash-sandbox")))
        assertEquals(listOf(SettingsPage.Shell), settingsPages(onPhone))
    }

    @Test
    fun `a namespace the app has no page for becomes a generic page when the harness allows it`() {
        val extra = syntheticRow("plugin-x")
        val pages = settingsPages(describedFixture().let { it.copy(namespaces = it.namespaces + extra) })
        assertEquals(SettingsPage.Generic("plugin-x"), pages.last())
        assertEquals("ns:plugin-x", pages.last().key)
        // Not when the harness says another surface owns it, and not when there is nothing to show.
        assertEquals(
            emptyList(),
            settingsPages(describe().copy(namespaces = listOf(syntheticRow("hidden", autoGenerate = false)))),
        )
        assertEquals(emptyList(), settingsPages(describe().copy(namespaces = listOf(emptyRow("plugin-z")))))
    }

    @Test
    fun `generic pages are listed in name order after the known ones`() {
        val rows = listOf(syntheticRow("plugin-b"), fixtureRow("agent-loop"), syntheticRow("plugin-a"))
        val pages = settingsPages(describe().copy(namespaces = rows))
        assertEquals(listOf<SettingsPage>(SettingsPage.AgentLoop, SettingsPage.Generic("plugin-a"), SettingsPage.Generic("plugin-b")), pages)
    }

    @Test
    fun `each page names the namespaces it reads among those served`() {
        val served = describedFixture().namespaces.map { it.ns }
        assertEquals(listOf("web", "web-search-deepseek", "web-deepseek-api"), pageNamespaces(SettingsPage.WebSearch, served))
        assertEquals(listOf("pwsh-sandbox"), pageNamespaces(SettingsPage.Shell, served))
        assertEquals(listOf("subagent", "subagent-model-selection-settings"), pageNamespaces(SettingsPage.Subagents, served))
        assertEquals(listOf("agent-loop"), pageNamespaces(SettingsPage.AgentLoop, served))
        assertEquals(listOf("session-log-deepseek"), pageNamespaces(SettingsPage.SessionLog, served))
        assertEquals(listOf("plugin-x"), pageNamespaces(SettingsPage.Generic("plugin-x"), served + "plugin-x"))
        // A namespace the harness does not serve is not read.
        assertEquals(listOf("subagent"), pageNamespaces(SettingsPage.Subagents, listOf("subagent")))
        assertEquals(emptyList(), pageNamespaces(SettingsPage.Shell, listOf("agent-loop")))
    }

    @Test
    fun `a restart note is owed only when a namespace says restart`() {
        assertFalse(needsRestart(describedFixture().namespaces))
        assertTrue(needsRestart(listOf(fixtureRow("agent-loop").copy(applies = "restart"))))
    }

    // ------------------------------------------------------------------------------ web search

    @Test
    fun `the provider switch needs the selection and the Search API sections`() {
        val served = describedFixture().namespaces.associateBy { it.ns }
        assertTrue(WebSearchRules.switchAvailable(served))
        assertFalse(WebSearchRules.switchAvailable(served - "web"))
        assertFalse(WebSearchRules.switchAvailable(served - "web-deepseek-api"))
    }

    @Test
    fun `messages search is the default and the switch reads the stored selection`() {
        assertFalse(WebSearchRules.usesSearchApi(fixtureRow("web")))
        assertTrue(WebSearchRules.usesSearchApi(writeAnswer(SettingsFixtures.MUTATE_WEB_SELECT_API)))
        assertFalse(WebSearchRules.usesSearchApi(null))
        assertEquals("web-search-deepseek", WebSearchRules.sectionNamespace(false))
        assertEquals("web-deepseek-api", WebSearchRules.sectionNamespace(true))
    }

    @Test
    fun `turning the Search API on writes both providers`() {
        assertEquals(
            listOf(
                SettingsPathOpView.Set(listOf("searchProvider"), JsonPrimitive("deepseek-search-api")),
                SettingsPathOpView.Set(listOf("fetchProvider"), JsonPrimitive("deepseek-fetch-api")),
            ),
            WebSearchRules.switchOps(fixtureRow("web"), searchApi = true),
        )
    }

    @Test
    fun `turning it off again clears the override when that is the deployment default`() {
        // The deployment's own selection is Messages search, so going back is an unset, not a copy of it.
        val on = writeAnswer(SettingsFixtures.MUTATE_WEB_SELECT_API)
        assertEquals(
            listOf(
                SettingsPathOpView.Unset(listOf("searchProvider")),
                SettingsPathOpView.Unset(listOf("fetchProvider")),
            ),
            WebSearchRules.switchOps(on, searchApi = false),
        )
    }

    @Test
    fun `a selection already in force needs no write`() {
        // Messages search is already the deployment default and nothing is overridden.
        assertEquals(emptyList(), WebSearchRules.switchOps(fixtureRow("web"), searchApi = false))
        // Search API already stored: nothing to do either.
        val on = writeAnswer(SettingsFixtures.MUTATE_WEB_SELECT_API)
        assertEquals(emptyList(), WebSearchRules.switchOps(on, searchApi = true))
    }

    @Test
    fun `the key is stored under the reference the selected section names`() {
        assertEquals("DEEPSEEK_API_KEY", WebSearchRules.keyRef(fixtureRow("web-search-deepseek")))
        assertEquals("DEEPSEEK_API_KEY", WebSearchRules.keyRef(null))
        val custom = fixtureRow("web-deepseek-api").copy(value = obj("apiKeyEnv" to JsonPrimitive("MY_SEARCH_KEY")))
        assertEquals("MY_SEARCH_KEY", WebSearchRules.keyRef(custom))
        val blank = fixtureRow("web-deepseek-api").copy(value = obj("apiKeyEnv" to JsonPrimitive("  ")))
        assertEquals("DEEPSEEK_API_KEY", WebSearchRules.keyRef(blank))
    }

    /** A namespace that names the credential it reads, as the DeepSeek model provider does. */
    private fun readerOf(ns: String, ref: String, role: String = "credential-ref") = SettingsNamespaceView(
        ns = ns,
        schema = obj(
            "uid" to num(2),
            "refs" to obj(
                "1" to obj("type" to str("string"), "meta" to obj("role" to str(role), "default" to str("DEEPSEEK_API_KEY"))),
                "2" to obj("type" to str("object"), "meta" to obj(), "dict" to obj("apiKeyEnv" to num(1))),
            ),
        ),
        value = obj("apiKeyEnv" to str(ref)),
        revision = 0,
    )

    @Test
    fun `the search key is shared with the model provider that reads the same credential`() {
        val rows = describedFixture().namespaces + readerOf("llm-deepseek", "DEEPSEEK_API_KEY")
        assertEquals(listOf("llm-deepseek"), WebSearchRules.otherUsersOf("DEEPSEEK_API_KEY", rows))
    }

    @Test
    fun `a credential nobody else reads has no other users`() {
        // The two search sections name it themselves and are not counted against it.
        assertEquals(emptyList(), WebSearchRules.otherUsersOf("DEEPSEEK_API_KEY", describedFixture().namespaces))
        val rows = describedFixture().namespaces + readerOf("llm-deepseek", "OTHER_KEY")
        assertEquals(emptyList(), WebSearchRules.otherUsersOf("DEEPSEEK_API_KEY", rows))
    }

    @Test
    fun `only a field the schema marks as a credential name counts`() {
        // The same text in a field that is not a credential reference is just a string.
        val rows = listOf(readerOf("plugin-a", "DEEPSEEK_API_KEY", role = "plain"), readerOf("plugin-b", "DEEPSEEK_API_KEY"))
        assertEquals(listOf("plugin-b"), WebSearchRules.otherUsersOf("DEEPSEEK_API_KEY", rows))
        assertEquals(listOf("plugin-b", "plugin-c"), WebSearchRules.otherUsersOf("DEEPSEEK_API_KEY", rows + readerOf("plugin-c", "DEEPSEEK_API_KEY")))
    }

    // ------------------------------------------------------------------------ subagent models

    private val flash = AllowedRoute("deepseek-official", "deepseek-flash")
    private val pro = AllowedRoute("deepseek-official", "deepseek-v4-pro")
    private val gone = AllowedRoute("old-provider", "old-model")

    private fun catalog(): List<ModelProviderGroup> {
        val value = (WireJson.decodeFromJsonElement(
            ModelCatalog.serializer(),
            (fixtureResult(SettingsFixtures.MODEL_CATALOG) as com.labteto.dshmobile.core.wire.RpcResult.Ok).value,
        ))
        return value.groups
    }

    @Test
    fun `the real model catalog becomes one candidate per advertised model`() {
        val candidates = modelCandidates(catalog(), emptyList())
        assertEquals(listOf(flash, pro), candidates.map { it.route })
        assertEquals("DeepSeek", candidates.first().providerName)
        assertEquals("DeepSeek-V4.1-Flash", candidates.first().modelName)
        assertTrue(candidates.all { it.available && !it.selected })
    }

    @Test
    fun `stored routes are marked and a retired one stays so it can be removed`() {
        val candidates = modelCandidates(catalog(), listOf(pro, gone))
        assertEquals(listOf(flash, pro, gone), candidates.map { it.route })
        assertEquals(listOf(false, true, true), candidates.map { it.selected })
        assertFalse(candidates.last().available)
        assertEquals("old-model", candidates.last().modelName)
    }

    @Test
    fun `the stored routes are read from the row and unreadable ones skipped`() {
        val row = fixtureRow("subagent-model-selection-settings").copy(
            value = obj(
                "enabled" to JsonPrimitive(true),
                "allowedModels" to JsonArray(
                    listOf(
                        obj("provider" to str("a"), "model" to str("b")),
                        obj("provider" to str("only-provider")),
                        str("not an object"),
                        obj("provider" to str(""), "model" to str("x")),
                    ),
                ),
            ),
        )
        assertEquals(listOf(AllowedRoute("a", "b")), allowedRoutes(row))
        assertTrue(modelSelectionEnabled(row))
        assertEquals(emptyList(), allowedRoutes(null))
        assertFalse(modelSelectionEnabled(fixtureRow("subagent-model-selection-settings")))
        assertFalse(modelSelectionEnabled(null))
    }

    @Test
    fun `choosing a model writes the whole list`() {
        val toggle = routeToggle(stored = listOf(flash), enabled = true, armed = false, route = pro)
        val ops = assertIs<RouteToggle.Write>(toggle).ops
        assertEquals(1, ops.size)
        val set = assertIs<SettingsPathOpView.Set>(ops.single())
        assertEquals(listOf("allowedModels"), set.path)
        assertEquals(
            JsonArray(
                listOf(
                    obj("provider" to str("deepseek-official"), "model" to str("deepseek-flash")),
                    obj("provider" to str("deepseek-official"), "model" to str("deepseek-v4-pro")),
                ),
            ),
            set.value,
        )
    }

    @Test
    fun `the last model cannot come off an enabled list`() {
        assertEquals(RouteToggle.KeepOne, routeToggle(listOf(flash), enabled = true, armed = false, route = flash))
        // Off, it can: the list is just a remembered choice then.
        val ops = assertIs<RouteToggle.Write>(routeToggle(listOf(flash), enabled = false, armed = false, route = flash)).ops
        assertEquals(JsonArray(emptyList()), assertIs<SettingsPathOpView.Set>(ops.single()).value)
    }

    @Test
    fun `the first model chosen after the switch is turned on writes the switch with it`() {
        val toggle = assertIs<RouteToggle.Write>(routeToggle(emptyList(), enabled = false, armed = true, route = flash))
        assertEquals(listOf(listOf("allowedModels"), listOf("enabled")), toggle.ops.map { (it as SettingsPathOpView.Set).path })
        assertEquals(JsonPrimitive(true), (toggle.ops.last() as SettingsPathOpView.Set).value)
        // Both fields read as their new values while the write is out.
        assertEquals(setOf("allowedModels", "enabled"), toggle.shown.keys)
        assertEquals(listOf(flash), parseRoutes(toggle.shown.getValue("allowedModels")))
    }

    @Test
    fun `a model tapped while the switch is off and not armed only changes the list`() {
        val ops = assertIs<RouteToggle.Write>(routeToggle(emptyList(), enabled = false, armed = false, route = flash)).ops
        assertEquals(1, ops.size)
    }

    @Test
    fun `the switch alone writes enabled and keeps the chosen models`() {
        assertEquals(listOf<SettingsPathOpView>(SettingsPathOpView.Set(listOf("enabled"), JsonPrimitive(false))), modelSelectionSwitchOps(false))
        assertEquals(listOf<SettingsPathOpView>(SettingsPathOpView.Set(listOf("enabled"), JsonPrimitive(true))), modelSelectionSwitchOps(true))
    }

    // ------------------------------------------------------------------------ overrides

    @Test
    fun `a key the user layer carries is an override even when it equals the deployment value`() {
        // Session log off, then on again: the harness keeps `enabled: true` in the user layer.
        val flipped = writeAnswer(SettingsFixtures.MUTATE_SESSION_LOG_BACK_ON)
        assertEquals(flipped.base, flipped.value)
        assertEquals(listOf("enabled"), overriddenKeys(flipped, listOf("enabled")))
        assertTrue(settingsFields(flipped).single().overridden)
        // The untouched row has no override at all.
        assertEquals(emptyList(), overriddenKeys(fixtureRow("session-log-deepseek"), listOf("enabled")))
    }

    @Test
    fun `resetting asks the harness to unset and never to set the default back`() {
        val web = writeAnswer(SettingsFixtures.MUTATE_WEB_SELECT_API)
        assertEquals(
            listOf<SettingsPathOpView>(SettingsPathOpView.Unset(listOf("searchProvider")), SettingsPathOpView.Unset(listOf("fetchProvider"))),
            resetOps(web, listOf("searchProvider", "fetchProvider")),
        )
        // Only what is overridden is touched, and a key that is not there is not invented.
        val half = web.copy(user = obj("searchProvider" to str("deepseek-search-api")))
        assertEquals(listOf<SettingsPathOpView>(SettingsPathOpView.Unset(listOf("searchProvider"))), resetOps(half, listOf("searchProvider", "fetchProvider", "nope")))
    }

    @Test
    fun `there is nothing to reset on a row with no user layer`() {
        assertEquals(emptyList(), resetOps(null, listOf("enabled")))
        assertEquals(emptyList(), resetOps(fixtureRow("session-log-deepseek").copy(user = null), listOf("enabled")))
        assertEquals(emptyList(), resetOps(fixtureRow("session-log-deepseek"), listOf("enabled")))
    }

    // ------------------------------------------------------ the model list with nothing in it

    private fun catalogOf(json: String): ModelCatalog =
        WireJson.decodeFromJsonElement(ModelCatalog.serializer(), (fixtureResult(json) as com.labteto.dshmobile.core.wire.RpcResult.Ok).value)

    private val noKey get() = CatalogState.Ready(catalogOf(SettingsFixtures.MODEL_CATALOG_NO_KEY))
    private val withModels get() = CatalogState.Ready(catalogOf(SettingsFixtures.MODEL_CATALOG))

    @Test
    fun `a harness with no provider key answers an empty catalog`() {
        val catalog = catalogOf(SettingsFixtures.MODEL_CATALOG_NO_KEY)
        assertEquals(emptyList(), catalog.groups)
        assertEquals(emptyList(), catalog.failures)
        assertEquals(emptyList(), catalog.routableProviders)
        assertFalse(hasSelectableModels(catalog.groups))
        assertTrue(hasSelectableModels(catalogOf(SettingsFixtures.MODEL_CATALOG).groups))
    }

    @Test
    fun `an empty catalog reads as no models whether or not a provider failed`() {
        val failed = catalogOf(SettingsFixtures.MODEL_CATALOG_PROVIDER_FAILED)
        assertEquals("deepseek-official", failed.failures.single().id)
        assertFalse(hasSelectableModels(failed.groups))
        assertIs<ModelListView.NoModels>(modelListView(CatalogState.Ready(failed), emptyList()))
        assertIs<ModelListView.NoModels>(modelListView(noKey, emptyList()))
    }

    @Test
    fun `the switch is blocked only when the harness answered with no models and none is stored`() {
        assertTrue(modelSwitchBlocked(noKey, emptyList(), enabled = false))
        // Models to choose from: not blocked.
        assertFalse(modelSwitchBlocked(withModels, emptyList(), enabled = false))
        // Not asked yet, still asking, or the ask failed: the switch can still be armed.
        assertFalse(modelSwitchBlocked(CatalogState.Idle, emptyList(), enabled = false))
        assertFalse(modelSwitchBlocked(CatalogState.Loading, emptyList(), enabled = false))
        assertFalse(modelSwitchBlocked(CatalogState.Failed(SettingsFailure.Unreachable("down")), emptyList(), enabled = false))
        // A switch that is already on can always be turned off, and a stored list can be switched on.
        assertFalse(modelSwitchBlocked(noKey, emptyList(), enabled = true))
        assertFalse(modelSwitchBlocked(noKey, listOf(AllowedRoute("deepseek-official", "deepseek-flash")), enabled = false))
    }

    @Test
    fun `stored routes stay on the list when the catalog is empty so they can be taken off`() {
        val stored = listOf(AllowedRoute("deepseek-official", "deepseek-flash"))
        val view = assertIs<ModelListView.NoModels>(modelListView(noKey, stored))
        val retired = view.retired.single()
        assertEquals(stored.single(), retired.route)
        assertFalse(retired.available)
        assertTrue(retired.selected)
    }

    @Test
    fun `models that are advertised show as the list and an unread catalog shows progress or failure`() {
        val view = assertIs<ModelListView.Models>(modelListView(withModels, emptyList()))
        assertEquals(2, view.candidates.size)
        assertEquals(ModelListView.Loading, modelListView(CatalogState.Idle, emptyList()))
        assertEquals(ModelListView.Loading, modelListView(CatalogState.Loading, emptyList()))
        assertEquals(ModelListView.Failed, modelListView(CatalogState.Failed(SettingsFailure.Unavailable), emptyList()))
    }

    @Test
    fun `a catalog model with no models yields no candidates`() {
        assertEquals(emptyList(), modelCandidates(listOf(ModelProviderGroup("p", "P", emptyList<ModelCatalogModel>())), emptyList()))
    }
}
