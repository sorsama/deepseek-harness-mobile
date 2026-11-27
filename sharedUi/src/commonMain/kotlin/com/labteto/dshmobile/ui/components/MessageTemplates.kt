package com.labteto.dshmobile.ui.components

/** A placeholder Android's string resources use, or the `%%` that writes one percent sign. */
private val PLACEHOLDER = Regex("""%%|%(\d+)\$[sd]""")

/**
 * [template] with each `%1$s` or `%1$d` replaced by the argument it numbers and each `%%` by `%`: what Android's
 * `getString(id, args)` writes for the strings this app has, for text put together after composing (a refusal written
 * when a call comes back). Compose Multiplatform's `stringResource(id, args)` can only be called while composing, so
 * such a message reads its template then and fills it here.
 *
 * A `%1$d` takes its digits already written ([rememberCountFormat]), so a count keeps the reader's own digits as it does
 * on Android. A placeholder with no argument is left as it is.
 */
fun fillTemplate(template: String, vararg args: String): String =
    PLACEHOLDER.replace(template) { match ->
        if (match.value == "%%") "%" else args.getOrNull(match.groupValues[1].toInt() - 1) ?: match.value
    }
