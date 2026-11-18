package com.labteto.dshmobile.core.settings

import com.labteto.dshmobile.core.wire.RpcError

/**
 * Why a settings call did not land, in the four ways a screen words differently. The harness's own
 * message is English, technical, and meant for a log: it is kept as [detail] for a "Details" line
 * and never shown as the headline.
 */
sealed interface SettingsFailure {
    /** The harness has no such call: an older harness, or a relay that does not carry it. */
    data object Unavailable : SettingsFailure

    /** The namespace changed since it was read (`settings/conflict`): someone else saved first. */
    data object Conflict : SettingsFailure

    /** The harness read the request and refused the value (`settings/rejected`, a bad argument). */
    data class Rejected(val detail: String) : SettingsFailure

    /** Anything else: a dropped connection, a refused session, a reply that was not a harness's. */
    data class Unreachable(val detail: String) : SettingsFailure
}

private val MISSING_METHOD = setOf(
    "capability-unavailable", "gateway/invocation-unavailable", "gateway/definition-unavailable",
    "method-not-found", "-32601",
)

/**
 * The codes the client gives a failure that is not the harness's answer at all: a broken carrier
 * (`internal`) and the two authentication refusals (`DshApiClient.transportError`). Everything
 * else arrived as a well-formed business result, which is the harness refusing the request.
 */
private val CARRIER = setOf("internal", "unauthenticated", "forbidden")

fun RpcError.toSettingsFailure(): SettingsFailure = when {
    code in MISSING_METHOD -> SettingsFailure.Unavailable
    code == "settings/conflict" -> SettingsFailure.Conflict
    code in CARRIER -> SettingsFailure.Unreachable(message)
    else -> SettingsFailure.Rejected(message)
}
