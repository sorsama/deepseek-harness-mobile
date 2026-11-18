package com.labteto.dshmobile.ui.screens.harnesssettings

import androidx.annotation.StringRes
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.settings.FieldTraits
import com.labteto.dshmobile.core.settings.SettingsField
import com.labteto.dshmobile.core.settings.SettingsNamespaces
import com.labteto.dshmobile.core.settings.SettingsPage
import com.labteto.dshmobile.core.settings.humanizeSettingsName

/**
 * The words and the extra rules for one field of a page the app knows.
 *
 * The harness's schemas carry no labels and no help text, and the English it would send could not
 * be translated, so the five known pages are written here and in the string files. A field the app
 * has no entry for falls back to [fallbackFieldText].
 */
internal data class FieldText(
    val label: String? = null,
    @StringRes val labelRes: Int? = null,
    @StringRes val hint: Int? = null,
    /** The argument of [hint], for a hint that quotes an address. Shown isolated from the right-to-left text around it. */
    val hintArg: String? = null,
    @StringRes val unit: Int? = null,
    val traits: FieldTraits = FieldTraits(),
    /** A value read as code: an address or a path. Set in the monospace face, left to right in every language. */
    val technical: Boolean = false,
)

/** An example address quoted in the endpoint hints; the same ones the harness's own page quotes. */
internal const val MESSAGES_EXAMPLE = "https://api.deepseek.com/anthropic/v1"
internal const val SEARCH_EXAMPLE = "https://api.deepseek.com/v1/tools"

private val SHELL = setOf(SettingsNamespaces.BASH, SettingsNamespaces.PWSH)

/**
 * The entry for a field of a known page, or null for any other.
 *
 * The shell limits are bare numbers in the harness's schema; a timeout or a byte count of 0, a
 * negative or a fraction cannot work, so the page requires a whole number of at least one. The
 * search endpoints are bare strings; they must be `http(s)` addresses.
 */
internal fun knownFieldText(ns: String, key: String): FieldText? = when {
    ns == SettingsNamespaces.AGENT_LOOP && key == "maxParallelToolCalls" ->
        FieldText(labelRes = R.string.hs_f_parallel, hint = R.string.hs_f_parallel_hint)

    ns == SettingsNamespaces.SESSION_LOG && key == "enabled" ->
        FieldText(labelRes = R.string.hs_f_session_log, hint = R.string.hs_f_session_log_hint)

    ns in SHELL && key == "timeoutMs" -> FieldText(
        labelRes = R.string.hs_f_timeout, hint = R.string.hs_f_timeout_hint, unit = R.string.hs_unit_ms,
        traits = FieldTraits(whole = true, min = 1.0),
    )
    ns in SHELL && key == "maxOutputBytes" -> FieldText(
        labelRes = R.string.hs_f_output, hint = R.string.hs_f_output_hint, unit = R.string.hs_unit_bytes,
        traits = FieldTraits(whole = true, min = 1.0),
    )

    ns == SettingsNamespaces.SUBAGENT && key == "maxDepth" ->
        FieldText(labelRes = R.string.hs_f_depth, hint = R.string.hs_f_depth_hint)
    ns == SettingsNamespaces.SUBAGENT && key == "maxActiveSubagents" ->
        FieldText(labelRes = R.string.hs_f_width, hint = R.string.hs_f_width_hint)

    ns == SettingsNamespaces.WEB_SEARCH && key == "baseURL" -> FieldText(
        labelRes = R.string.hs_f_endpoint_messages, hint = R.string.hs_f_endpoint_messages_hint, hintArg = MESSAGES_EXAMPLE,
        traits = FieldTraits(url = true), technical = true,
    )
    ns == SettingsNamespaces.WEB_SEARCH && key == "maxUses" ->
        FieldText(labelRes = R.string.hs_f_max_uses, hint = R.string.hs_f_max_uses_hint)
    ns == SettingsNamespaces.WEB_API && key == "baseURL" -> FieldText(
        labelRes = R.string.hs_f_endpoint_search, hint = R.string.hs_f_endpoint_search_hint, hintArg = SEARCH_EXAMPLE,
        traits = FieldTraits(url = true), technical = true,
    )
    ns == SettingsNamespaces.WEB_API && key == "count" ->
        FieldText(labelRes = R.string.hs_f_count, hint = R.string.hs_f_count_hint)

    else -> null
}

/**
 * Words for a field of a namespace the app does not know: its name made readable, and whatever
 * description the plugin sent. Addresses and paths are not guessed at; only what the schema says counts.
 */
internal fun fallbackFieldText(field: SettingsField): FieldText =
    FieldText(label = humanizeSettingsName(field.key))

@StringRes
internal fun pageTitleRes(page: SettingsPage): Int? = when (page) {
    SettingsPage.WebSearch -> R.string.hs_page_web_search
    SettingsPage.Shell -> R.string.hs_page_shell
    SettingsPage.Subagents -> R.string.hs_page_subagents
    SettingsPage.AgentLoop -> R.string.hs_page_agent_loop
    SettingsPage.SessionLog -> R.string.hs_page_session_log
    SettingsPage.Account -> R.string.acct_title
    is SettingsPage.Generic -> null
}

@StringRes
internal fun pageSummaryRes(page: SettingsPage): Int? = when (page) {
    SettingsPage.WebSearch -> R.string.hs_page_web_search_desc
    SettingsPage.Shell -> R.string.hs_page_shell_desc
    SettingsPage.Subagents -> R.string.hs_page_subagents_desc
    SettingsPage.AgentLoop -> R.string.hs_page_agent_loop_desc
    SettingsPage.SessionLog -> R.string.hs_page_session_log_desc
    SettingsPage.Account -> R.string.acct_summary
    is SettingsPage.Generic -> null
}

/** The name of a page the app has no words for: its namespace id, made readable. */
internal fun genericPageTitle(page: SettingsPage.Generic): String = humanizeSettingsName(page.ns)
