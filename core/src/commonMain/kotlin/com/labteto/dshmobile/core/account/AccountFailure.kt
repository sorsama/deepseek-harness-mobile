package com.labteto.dshmobile.core.account

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult

/**
 * Why an account call did not land, in the ways a screen words differently. The harness's own
 * message is English and meant for a log: it is kept as `detail` for a "Technical details" line, with
 * any link in it cut down to its origin, and never shown as the headline.
 */
sealed interface AccountFailure {
    /** The harness has no account service: an older harness, or its account plugin is switched off. */
    data object Unavailable : AccountFailure

    /** The harness is there but could not reach DeepSeek (`account: no-response`: no connection, or a timeout). */
    data class PlatformUnreachable(val detail: String) : AccountFailure

    /** The harness could not read or write its stored sign-in (`account: storage`). */
    data class Storage(val detail: String) : AccountFailure

    /** The harness read the request and refused it, or DeepSeek's answer was not one it could use. */
    data class Rejected(val detail: String) : AccountFailure

    /** A dropped connection, a refused session, a reply that was not a harness's. */
    data class Unreachable(val detail: String) : AccountFailure
}

/**
 * The codes that mean the harness has no such call. The first four are what the gateway says for a
 * method or namespace it does not export; `gateway/service-unavailable` is what a harness with the
 * account plugin switched off answers, because the controller is still exported but its service is
 * not running (captured from 0.2.1-rc.2 with `deepseek-account` disabled).
 */
private val MISSING_METHOD = setOf(
    "capability-unavailable", "gateway/invocation-unavailable", "gateway/definition-unavailable",
    "method-not-found", "-32601", "gateway/service-unavailable",
)

/** The client's own codes for a failure that is not the harness's answer at all (`DshApiClient.transportError`). */
private val CARRIER = setOf("internal", "unauthenticated", "forbidden")

/** `PlatformAuthError` reaches the wire as `gateway/internal` with the message `account: <code>`. */
private val ACCOUNT_MESSAGE = Regex("^account: (no-response|network|protocol|expired|storage)$")

fun RpcError.toAccountFailure(): AccountFailure {
    val detail = redactUrls(message)
    if (code in MISSING_METHOD) return AccountFailure.Unavailable
    if (code in CARRIER) return AccountFailure.Unreachable(detail)
    return when (ACCOUNT_MESSAGE.matchEntire(message.trim())?.groupValues?.get(1)) {
        "no-response" -> AccountFailure.PlatformUnreachable(detail)
        "storage" -> AccountFailure.Storage(detail)
        else -> AccountFailure.Rejected(detail)
    }
}

/**
 * The same result, with `gateway/service-unavailable` spelled the way the app's capability record
 * understands (`capability-unavailable`), so a harness whose account plugin is off hides the page like
 * a harness that has no account calls at all.
 */
fun <T> RpcResult<T>.withAccountCapability(): RpcResult<T> = when {
    this is RpcResult.Err && error.code == "gateway/service-unavailable" -> RpcResult.Err(error.copy(code = "capability-unavailable"))
    else -> this
}

/** What signing out would stop, so the confirmation can say so. */
enum class SignOutImpact { Idle, TasksRunning, Unknown }

/** The sign-out confirmation's wording follows what the harness says is running; a failed question is not "nothing". */
fun signOutImpact(result: RpcResult<Boolean>): SignOutImpact = when (result) {
    is RpcResult.Ok -> if (result.value) SignOutImpact.TasksRunning else SignOutImpact.Idle
    is RpcResult.Err -> SignOutImpact.Unknown
}

private val URL_IN_TEXT = Regex("""(https?://[^\s/?#"'<>)\]]+)[^\s"'<>)\]]*""", RegexOption.IGNORE_CASE)

/**
 * [text] with every web address cut down to its origin, so a harness message that quotes a sign-in
 * link cannot put the link (which carries a one-time code) into a details line or a log.
 */
fun redactUrls(text: String): String = URL_IN_TEXT.replace(text) { match ->
    val full = match.value
    // Punctuation that ends a sentence is not part of the address.
    val address = full.trimEnd(',', '.', ';', ':', '!', '?')
    val origin = match.groupValues[1]
    if (address.length <= origin.length) full else origin + "/…" + full.substring(address.length)
}
