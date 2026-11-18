package com.labteto.dshmobile.ui.screens.harnesssettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.settings.AllowedRoute
import com.labteto.dshmobile.core.settings.CatalogState
import com.labteto.dshmobile.core.settings.FieldId
import com.labteto.dshmobile.core.settings.HarnessSettingsController
import com.labteto.dshmobile.core.settings.HarnessSettingsState
import com.labteto.dshmobile.core.settings.ModelCandidate
import com.labteto.dshmobile.core.settings.ModelListView
import com.labteto.dshmobile.core.settings.RouteToggle
import com.labteto.dshmobile.core.settings.SettingsNamespaces
import com.labteto.dshmobile.core.settings.SettingsPage
import com.labteto.dshmobile.core.settings.WebSearchRules
import com.labteto.dshmobile.core.settings.allowedRoutes
import com.labteto.dshmobile.core.settings.modelListView
import com.labteto.dshmobile.core.settings.modelSelectionEnabled
import com.labteto.dshmobile.core.settings.modelSelectionSwitchOps
import com.labteto.dshmobile.core.settings.modelSwitchBlocked
import com.labteto.dshmobile.core.settings.overriddenKeys
import com.labteto.dshmobile.core.settings.parseRoutes
import com.labteto.dshmobile.core.settings.routeToggle
import com.labteto.dshmobile.core.settings.settingsFields
import com.labteto.dshmobile.core.settings.shellNamespace
import com.labteto.dshmobile.models.ModelsRequest
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.screens.main.FeatureProgress
import com.labteto.dshmobile.ui.screens.main.technicalDisplay
import com.labteto.dshmobile.ui.screens.models.ModelSetupViewModel
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The body of one page. Five of them are written out here because the harness describes them in
 * several namespaces or with a rule a schema cannot carry (the web search switch, the model
 * allowlist); the others are the same generic form over one namespace.
 */
@Composable
internal fun SettingsPageBody(
    page: SettingsPage,
    state: HarnessSettingsState,
    controller: HarnessSettingsController,
    hostLabel: String,
) {
    when (page) {
        SettingsPage.AgentLoop -> KnownSection(SettingsNamespaces.AGENT_LOOP, listOf("maxParallelToolCalls"), state, controller, hostLabel)
        SettingsPage.SessionLog -> KnownSection(SettingsNamespaces.SESSION_LOG, listOf("enabled"), state, controller, hostLabel)
        SettingsPage.Shell -> shellNamespace(state.namespaces.keys)?.let {
            KnownSection(it, listOf("timeoutMs", "maxOutputBytes"), state, controller, hostLabel)
        }
        SettingsPage.Subagents -> SubagentsBody(state, controller, hostLabel)
        SettingsPage.WebSearch -> WebSearchBody(state, controller, hostLabel)
        // DeepSeek sign-in is not a settings namespace and has its own page, AccountPageScreen.
        SettingsPage.Account -> Unit
        is SettingsPage.Generic -> GenericBody(page.ns, state, controller, hostLabel)
    }
}

/** Some fields of one namespace, in a card, in the order given. A field the harness does not carry is left out. */
@Composable
private fun KnownSection(
    ns: String,
    keys: List<String>,
    state: HarnessSettingsState,
    controller: HarnessSettingsController,
    hostLabel: String,
) {
    val row = state.namespaces[ns] ?: return
    val fields = remember(row) { settingsFields(row).associateBy { it.key } }
    val shown = keys.mapNotNull { fields[it] }
    if (shown.isEmpty()) return
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable)) {
        shown.forEach { field ->
            SettingsFieldControl(field, knownFieldText(ns, field.key) ?: fallbackFieldText(field), state, controller, hostLabel)
        }
    }
}

@Composable
private fun GenericBody(ns: String, state: HarnessSettingsState, controller: HarnessSettingsController, hostLabel: String) {
    val row = state.namespaces[ns] ?: return
    val fields = remember(row) { settingsFields(row) }
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable)) {
        fields.forEach { field ->
            SettingsFieldControl(field, knownFieldText(ns, field.key) ?: fallbackFieldText(field), state, controller, hostLabel)
        }
    }
}

// ----------------------------------------------------------------------------------- web search

@Composable
private fun WebSearchBody(state: HarnessSettingsState, controller: HarnessSettingsController, hostLabel: String) {
    val colors = DsTheme.colors
    val web = state.namespaces[SettingsNamespaces.WEB]
    val switchAvailable = web != null && WebSearchRules.switchAvailable(state.namespaces)
    val searchId = FieldId(SettingsNamespaces.WEB, "searchProvider")
    val fetchId = FieldId(SettingsNamespaces.WEB, "fetchProvider")
    // While the selection is being written the page already reads as the one it is becoming.
    val inFlight = (state.writing[searchId] as? JsonPrimitive)?.contentOrNull
    val searchApi = switchAvailable &&
        (inFlight?.let { it == WebSearchRules.SEARCH_API } ?: WebSearchRules.usesSearchApi(web))

    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable)) {
        if (switchAvailable && web != null) {
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                ToggleRow(
                    label = stringResource(R.string.hs_f_search_api),
                    checked = searchApi,
                    hint = stringResource(R.string.hs_f_search_api_hint),
                    enabled = state.writable,
                ) {
                    val enable = !searchApi
                    val ops = WebSearchRules.switchOps(web, enable)
                    controller.write(
                        SettingsNamespaces.WEB,
                        ops,
                        mapOf(
                            searchId to JsonPrimitive(if (enable) WebSearchRules.SEARCH_API else WebSearchRules.MESSAGES_SEARCH),
                            fetchId to JsonPrimitive(if (enable) WebSearchRules.FETCH_API else WebSearchRules.HTTP_FETCH),
                        ),
                    )
                }
                // The switch is two fields; one reset clears whichever of them carries an override.
                val providerKeys = listOf(searchId.key, fetchId.key)
                OverrideLine(
                    label = stringResource(R.string.hs_f_search_api),
                    overridden = overriddenKeys(web, providerKeys).isNotEmpty(),
                    writable = state.writable,
                    busy = searchId in state.writing || fetchId in state.writing,
                    onReset = { controller.resetKeys(SettingsNamespaces.WEB, providerKeys) },
                )
                FailureLine(state.failures[searchId] ?: state.failures[fetchId])
            }
        }
        WebSearchKey(searchApi, state, controller, hostLabel)
        val section = WebSearchRules.sectionNamespace(searchApi)
        val row = state.namespaces[section]
        if (row != null) {
            val fields = remember(row) { settingsFields(row).associateBy { it.key } }
            val limitKey = if (searchApi) "count" else "maxUses"
            listOf("baseURL", limitKey).forEach { key ->
                fields[key]?.let { field ->
                    SettingsFieldControl(field, knownFieldText(section, key) ?: fallbackFieldText(field), state, controller, hostLabel)
                }
            }
        }
    }
    if (controller.cleartext) {
        Text(stringResource(R.string.models_cleartext_plate), style = DsType.small13, color = colors.warnLabel)
    }
}

/** The key, stored through the credentials domain under the reference the selected API's section names. */
@Composable
private fun WebSearchKey(searchApi: Boolean, state: HarnessSettingsState, controller: HarnessSettingsController, hostLabel: String) {
    val section = state.namespaces[WebSearchRules.sectionNamespace(searchApi)]
    val ref = WebSearchRules.keyRef(section)
    val info = state.credentials[ref]
    val stored = info?.configured == true
    // As on the Models screen: a key the harness cannot write (its environment's) is explained, not edited.
    val writable = info?.writable != false
    // The default key is the one the DeepSeek model provider uses too; say so before it is replaced or removed.
    val others = remember(ref, state.namespaces) { WebSearchRules.otherUsersOf(ref, state.namespaces.values) }
    val sharedNote = if (others.isEmpty()) null
    else stringResource(R.string.hs_key_shared, technicalDisplay(others.joinToString(", ")))
    SecretControl(
        label = stringResource(R.string.models_key_label),
        status = when {
            info == null -> ""
            stored -> stringResource(R.string.models_key_saved_line)
            else -> stringResource(R.string.hs_key_unset)
        },
        hint = stringResource(R.string.hs_key_hint),
        writable = writable,
        notWritableText = stringResource(R.string.models_key_from_env),
        canRemove = stored && writable,
        busy = ref in state.credentialWork,
        failure = state.credentialFailures[ref],
        cleartext = controller.cleartext,
        hostLabel = hostLabel,
        removeTitle = stringResource(R.string.hs_key_remove_title),
        removeBody = stringResource(R.string.hs_key_remove_body),
        onSave = { controller.saveKey(ref, it) },
        onRemove = { controller.removeKey(ref) },
        onDismissFailure = { controller.dismissCredentialFailure(ref) },
        sharedNote = sharedNote,
    )
}

// -------------------------------------------------------------------------------------- subagents

@Composable
private fun SubagentsBody(state: HarnessSettingsState, controller: HarnessSettingsController, hostLabel: String) {
    if (SettingsNamespaces.SUBAGENT in state.namespaces) {
        SectionHeader(stringResource(R.string.hs_sec_limits))
        KnownSection(SettingsNamespaces.SUBAGENT, listOf("maxDepth", "maxActiveSubagents"), state, controller, hostLabel)
    }
    if (SettingsNamespaces.SUBAGENT_MODELS in state.namespaces) {
        SectionHeader(stringResource(R.string.hs_sec_models))
        DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) { ModelAllowlist(state, controller) }
    }
}

/**
 * The switch that lets an agent choose a model for each subagent, and the models it may choose from.
 * Changes are saved as they are made. The harness refuses an enabled list with nothing in it, so the
 * first model chosen after the switch is turned on writes both, and the last cannot be taken off.
 */
@Composable
private fun ModelAllowlist(state: HarnessSettingsState, controller: HarnessSettingsController) {
    val colors = DsTheme.colors
    val ns = SettingsNamespaces.SUBAGENT_MODELS
    val view = state.namespaces[ns]
    val enabledId = FieldId(ns, "enabled")
    val allowedId = FieldId(ns, "allowedModels")
    val stored = allowedRoutes(view)
    val routes = if (allowedId in state.writing) parseRoutes(state.writing[allowedId]) else stored
    val enabled = if (enabledId in state.writing) {
        (state.writing[enabledId] as? JsonPrimitive)?.booleanOrNull == true
    } else {
        modelSelectionEnabled(view)
    }
    // The switch was turned on with nothing chosen yet: nothing is written until a model is picked.
    var armed by rememberSaveable { mutableStateOf(false) }
    var keepOne by remember { mutableStateOf(false) }

    // The list follows the keys the harness holds, so it is read each time this page opens, and again
    // when the Models page (where a key is added) closes over it. Never once per screen.
    val monitor = hiltViewModel<ModelSetupViewModel>().monitor
    val modelsRequest by monitor.request.collectAsStateWithLifecycle()
    var modelsOpened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { controller.loadCatalog() }
    LaunchedEffect(modelsRequest) {
        if (modelsRequest != null) modelsOpened = true
        else if (modelsOpened) {
            modelsOpened = false
            controller.loadCatalog()
        }
    }

    // With nothing to choose from the switch cannot stay on (the harness refuses an enabled list
    // with no model in it), so it says why instead of arming itself.
    val blocked = modelSwitchBlocked(state.catalog, routes, enabled)
    val listShown = (enabled || armed) && !blocked
    LaunchedEffect(blocked) { if (blocked) armed = false }

    ToggleRow(
        label = stringResource(R.string.hs_f_model_choice),
        checked = listShown,
        hint = stringResource(R.string.hs_f_model_choice_hint),
        enabled = state.writable && !blocked,
    ) {
        keepOne = false
        if (!listShown) {
            if (routes.isEmpty()) {
                armed = true
            } else {
                controller.write(ns, modelSelectionSwitchOps(true), mapOf(enabledId to JsonPrimitive(true)))
            }
        } else {
            armed = false
            if (enabled) controller.write(ns, modelSelectionSwitchOps(false), mapOf(enabledId to JsonPrimitive(false)))
        }
    }
    // The switch and the list are two fields; one reset clears whichever of them carries an override.
    val modelKeys = listOf(enabledId.key, allowedId.key)
    OverrideLine(
        label = stringResource(R.string.hs_f_model_choice),
        overridden = overriddenKeys(view, modelKeys).isNotEmpty(),
        writable = state.writable,
        busy = enabledId in state.writing || allowedId in state.writing,
        onReset = {
            armed = false
            controller.resetKeys(ns, modelKeys)
        },
    )
    FailureLine(state.failures[enabledId] ?: state.failures[allowedId])
    if (blocked) {
        NoModelsNote { monitor.open(ModelsRequest.Connected) }
        return
    }
    if (!listShown) return

    Text(
        stringResource(R.string.hs_models_list_label),
        style = DsType.std14Strong,
        color = colors.labelPrimary,
        modifier = Modifier.semantics { heading() },
    )
    if (armed && routes.isEmpty() && state.catalog is CatalogState.Ready) {
        Text(stringResource(R.string.hs_models_pick_one), style = DsType.small13, color = colors.warnLabel)
    }
    if (keepOne) {
        Text(
            stringResource(R.string.hs_models_keep_one),
            style = DsType.small13,
            color = colors.warnLabel,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    when (val list = modelListView(state.catalog, routes)) {
        ModelListView.Loading -> FeatureProgress()
        ModelListView.Failed -> {
            Text(stringResource(R.string.hs_models_load_failed), style = DsType.small13, color = colors.error)
            SettingsButton(stringResource(R.string.common_retry), controller::loadCatalog)
        }
        is ModelListView.NoModels -> {
            // The harness answered and lists nothing: say why and where to fix it. Stored routes it no
            // longer advertises stay below, so they can still be taken off.
            NoModelsNote { monitor.open(ModelsRequest.Connected) }
            ModelGroups(list.retired, enabled, armed, routes, state, controller, ns) { keepOne = it; if (!it) armed = false }
        }
        is ModelListView.Models ->
            ModelGroups(list.candidates, enabled, armed, routes, state, controller, ns) { keepOne = it; if (!it) armed = false }
    }
}

/** Why the model list is empty, and the way to the page where that is fixed. */
@Composable
private fun NoModelsNote(onOpenModels: () -> Unit) {
    Text(
        stringResource(R.string.hs_models_none_why),
        style = DsType.small13,
        color = DsTheme.colors.labelSecondary,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    SettingsButton(stringResource(R.string.models_open), onOpenModels)
}

/** The models, grouped under their provider, each a checkbox. [onKeepOne] reports whether the last-model rule refused a tap. */
@Composable
private fun ModelGroups(
    candidates: List<ModelCandidate>,
    enabled: Boolean,
    armed: Boolean,
    routes: List<AllowedRoute>,
    state: HarnessSettingsState,
    controller: HarnessSettingsController,
    ns: String,
    onKeepOne: (Boolean) -> Unit,
) {
    val colors = DsTheme.colors
    candidates.groupBy { it.providerName }.forEach { (provider, models) ->
        Text(
            technicalDisplay(provider),
            style = DsType.small13Strong,
            color = colors.labelTertiary,
            modifier = Modifier.padding(top = DsSpacing.small).semantics { heading() },
        )
        models.forEach { candidate ->
            ModelRow(candidate.modelName, candidate.selected, !candidate.available, state.writable) {
                when (val toggle = routeToggle(routes, enabled, armed, candidate.route)) {
                    RouteToggle.KeepOne -> onKeepOne(true)
                    is RouteToggle.Write -> {
                        onKeepOne(false)
                        controller.write(ns, toggle.ops, toggle.shown.mapKeys { FieldId(ns, it.key) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(name: String, selected: Boolean, retired: Boolean, writable: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DsSpacing.touchTarget)
            .toggleable(value = selected, enabled = writable, role = Role.Checkbox) { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = null, enabled = writable, colors = checkboxColors())
        Spacer(Modifier.width(DsSpacing.medium))
        Text(technicalDisplay(name), style = DsType.std14, color = DsTheme.colors.labelPrimary, modifier = Modifier.weight(1f))
        if (retired) DsPill(stringResource(R.string.hs_models_retired), warn = true)
    }
}
