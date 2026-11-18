package com.labteto.dshmobile.core.settings

import com.labteto.dshmobile.core.wire.dto.ModelProviderGroup
import com.labteto.dshmobile.core.wire.dto.SettingsDescribeValue
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.core.wire.dto.SettingsPathOpView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The settings namespaces the app has pages for. The ids are the harness's profile entry ids
 * (`packages/client/ui-settings-*` spells them the same way: a client package may not import them).
 */
object SettingsNamespaces {
    const val WEB = "web"
    const val WEB_SEARCH = "web-search-deepseek"
    const val WEB_API = "web-deepseek-api"
    const val BASH = "bash-sandbox"
    const val PWSH = "pwsh-sandbox"
    const val SUBAGENT = "subagent"
    const val SUBAGENT_MODELS = "subagent-model-selection-settings"
    const val AGENT_LOOP = "agent-loop"
    const val SESSION_LOG = "session-log-deepseek"

    /** The credential reference the search providers resolve when their section names none. */
    const val DEFAULT_KEY_REF = "DEEPSEEK_API_KEY"

    /** Every namespace a known page claims; the generic pages are for the rest. */
    val CLAIMED = setOf(WEB, WEB_SEARCH, WEB_API, BASH, PWSH, SUBAGENT, SUBAGENT_MODELS, AGENT_LOOP, SESSION_LOG)
}

/** One page of the Harness settings area. */
sealed interface SettingsPage {
    /** Stable across launches and locales; a key for lists and saved state. */
    val key: String

    data object WebSearch : SettingsPage { override val key = "web-search" }

    data object Shell : SettingsPage { override val key = "shell" }

    data object Subagents : SettingsPage { override val key = "subagents" }

    data object AgentLoop : SettingsPage { override val key = "agent-loop" }

    data object SessionLog : SettingsPage { override val key = "session-log" }

    /** A namespace the app has no page for: its fields come from what the harness describes. */
    data class Generic(val ns: String) : SettingsPage { override val key get() = "ns:$ns" }
}

/**
 * The pages this harness offers, in the order the area lists them: the five the app knows, then
 * the namespaces it does not, which the harness allows a client to generate a page for.
 *
 * A known page is offered when the namespace the web client mounts it on is described, so a harness
 * built without a plugin shows no trace of it. A generic page also needs a field to show.
 */
fun settingsPages(described: SettingsDescribeValue): List<SettingsPage> {
    val served = described.namespaces.associateBy { it.ns }
    val known = buildList {
        if (SettingsNamespaces.WEB_SEARCH in served) add(SettingsPage.WebSearch)
        if (shellNamespace(served.keys) != null) add(SettingsPage.Shell)
        if (SettingsNamespaces.SUBAGENT in served || SettingsNamespaces.SUBAGENT_MODELS in served) add(SettingsPage.Subagents)
        if (SettingsNamespaces.AGENT_LOOP in served) add(SettingsPage.AgentLoop)
        if (SettingsNamespaces.SESSION_LOG in served) add(SettingsPage.SessionLog)
    }
    val generic = described.namespaces
        .filter { it.ns !in SettingsNamespaces.CLAIMED && it.autoGenerate && settingsFields(it).isNotEmpty() }
        .sortedBy { it.ns }
        .map { SettingsPage.Generic(it.ns) }
    return known + generic
}

/**
 * The shell executor namespace to edit: the base bundle composes exactly one per platform, the
 * PowerShell one on Windows and the bash one elsewhere (the phone's own harness is bash).
 */
fun shellNamespace(served: Collection<String>): String? = when {
    SettingsNamespaces.PWSH in served -> SettingsNamespaces.PWSH
    SettingsNamespaces.BASH in served -> SettingsNamespaces.BASH
    else -> null
}

/** Whether any namespace in [views] only applies after a harness restart. */
fun needsRestart(views: Collection<SettingsNamespaceView>): Boolean = views.any { it.applies == "restart" }

// ---------------------------------------------------------------------------- web search

/**
 * How the web-search page's three namespaces and one credential fit together
 * (`packages/client/ui-settings-web-search`).
 *
 * `web` picks the search and the fetch provider; the switch writes both at once, to the Search and
 * Fetch APIs or to Messages search with plain HTTP fetch. `web-search-deepseek` holds the Messages
 * settings and `web-deepseek-api` the Search API's; the page edits the endpoint and the limit of
 * whichever is selected. The key is neither: it goes through the credentials domain, under the
 * reference the selected section names.
 */
object WebSearchRules {
    const val SEARCH_API = "deepseek-search-api"
    const val FETCH_API = "deepseek-fetch-api"
    const val MESSAGES_SEARCH = "deepseek-official"
    const val HTTP_FETCH = "http"

    private const val SEARCH = "searchProvider"
    private const val FETCH = "fetchProvider"

    /** The switch exists only while the harness serves the selection and the Search API's section. */
    fun switchAvailable(served: Map<String, SettingsNamespaceView>): Boolean =
        SettingsNamespaces.WEB in served && SettingsNamespaces.WEB_API in served

    /** Whether the search provider in force is the Search API. */
    fun usesSearchApi(web: SettingsNamespaceView?): Boolean =
        web != null && stringIn(web.value, SEARCH) == SEARCH_API

    /** The namespace holding the selected API's endpoint. */
    fun sectionNamespace(searchApi: Boolean): String =
        if (searchApi) SettingsNamespaces.WEB_API else SettingsNamespaces.WEB_SEARCH

    /** The credential reference the section names, or the providers' default. */
    fun keyRef(section: SettingsNamespaceView?): String =
        section?.let { stringIn(it.value, "apiKeyEnv") }?.takeIf { it.isNotBlank() } ?: SettingsNamespaces.DEFAULT_KEY_REF

    /**
     * The writes that select one API. A target equal to the deployment's own value clears the
     * override instead of storing a copy of it, so the deployment default keeps applying; a field
     * already at its target needs nothing.
     */
    fun switchOps(web: SettingsNamespaceView, searchApi: Boolean): List<SettingsPathOpView> {
        val targets = listOf(
            SEARCH to if (searchApi) SEARCH_API else MESSAGES_SEARCH,
            FETCH to if (searchApi) FETCH_API else HTTP_FETCH,
        )
        val user = web.user as? JsonObject
        return targets.mapNotNull { (field, target) ->
            val overridden = user?.containsKey(field) == true
            when {
                stringIn(web.base, field) == target -> if (overridden) SettingsPathOpView.Unset(listOf(field)) else null
                stringIn(web.value, field) == target && overridden -> null
                else -> SettingsPathOpView.Set(listOf(field), JsonPrimitive(target))
            }
        }
    }

    private fun stringIn(element: JsonElement?, key: String): String? =
        ((element as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
}

// ---------------------------------------------------------------------------- subagent models

/** One exact provider and model the harness may offer an agent to start a subagent on. */
data class AllowedRoute(val provider: String, val model: String)

/** One row of the allowlist editor: a catalog model joined with the stored routes. */
data class ModelCandidate(
    val route: AllowedRoute,
    val providerName: String,
    val modelName: String,
    /** The harness advertises it now; a stored route that no longer exists stays removable. */
    val available: Boolean,
    val selected: Boolean,
)

/** The stored routes of a `subagent-model-selection-settings` row; anything unreadable is skipped. */
fun allowedRoutes(view: SettingsNamespaceView?): List<AllowedRoute> {
    val list = (view?.value as? JsonObject)?.get("allowedModels") as? JsonArray ?: return emptyList()
    return list.mapNotNull { item ->
        val row = item as? JsonObject ?: return@mapNotNull null
        val provider = (row["provider"] as? JsonPrimitive)?.contentOrNull
        val model = (row["model"] as? JsonPrimitive)?.contentOrNull
        if (provider.isNullOrEmpty() || model.isNullOrEmpty()) null else AllowedRoute(provider, model)
    }
}

/** Whether the model-selection switch is on in [view]. */
fun modelSelectionEnabled(view: SettingsNamespaceView?): Boolean =
    ((view?.value as? JsonObject)?.get("enabled") as? JsonPrimitive)?.contentOrNull == "true"

/**
 * The catalog joined with what is stored: every advertised model, then every stored route the
 * catalog no longer advertises, so a retired model can still be taken off the list.
 */
fun modelCandidates(groups: List<ModelProviderGroup>, stored: List<AllowedRoute>): List<ModelCandidate> {
    val selected = stored.toSet()
    val advertised = groups.flatMap { group ->
        group.models.map { model ->
            val route = AllowedRoute(group.id, model.id)
            ModelCandidate(route, group.name, model.name, available = true, selected = route in selected)
        }
    }
    val known = advertised.map { it.route }.toSet()
    val retired = stored.filter { it !in known }.distinct()
        .map { ModelCandidate(it, it.provider, it.model, available = false, selected = true) }
    return advertised + retired
}

/** What tapping a model does. */
sealed interface RouteToggle {
    data class Write(val ops: List<SettingsPathOpView>) : RouteToggle

    /** The last model of an enabled list: turning the switch off is the way to stop offering models. */
    data object KeepOne : RouteToggle
}

/**
 * The write for tapping [route]. The harness refuses an enabled list with nothing in it, so the
 * last model cannot come off while the switch is on, and the first one chosen after the switch is
 * turned on writes the switch and the list together.
 *
 * @param armed the person turned the switch on, but no model is chosen yet: nothing was written.
 */
fun routeToggle(stored: List<AllowedRoute>, enabled: Boolean, armed: Boolean, route: AllowedRoute): RouteToggle {
    val next = if (route in stored) stored - route else stored + route
    if (enabled && next.isEmpty()) return RouteToggle.KeepOne
    val ops = buildList<SettingsPathOpView> {
        add(SettingsPathOpView.Set(listOf("allowedModels"), routesJson(next)))
        if (armed && !enabled && next.isNotEmpty()) add(SettingsPathOpView.Set(listOf("enabled"), JsonPrimitive(true)))
    }
    return RouteToggle.Write(ops)
}

/** The writes for the switch itself: turning it off keeps the chosen models, as the harness's own page does. */
fun modelSelectionSwitchOps(enable: Boolean): List<SettingsPathOpView> =
    listOf(SettingsPathOpView.Set(listOf("enabled"), JsonPrimitive(enable)))

private fun routesJson(routes: List<AllowedRoute>): JsonElement = JsonArray(
    routes.map { JsonObject(mapOf("provider" to JsonPrimitive(it.provider), "model" to JsonPrimitive(it.model))) },
)
