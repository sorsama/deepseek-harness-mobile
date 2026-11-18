package com.labteto.dshmobile.core.account

import com.labteto.dshmobile.core.wire.dto.AccountView

/** Where an attempt that is still running has got to, in the three steps a person can tell apart. */
enum class SignInStep {
    /** The harness is asking DeepSeek for a sign-in link. */
    Starting,

    /** The link is ready and the harness is waiting for the browser to come back. */
    WaitingForBrowser,

    /** The browser came back; the harness is exchanging the code and saving the sign-in. */
    Finishing,
}

/**
 * A sign-in the harness is running now, whoever started it.
 *
 * [authorizeUrl] is a one-time link into DeepSeek. It is kept out of [toString], so a log line or a
 * crash report that prints this value cannot carry it.
 */
class SignInProgress(
    val attemptId: String,
    val step: SignInStep,
    val authorizeUrl: String?,
    /** Epoch milliseconds after which the harness gives up on the attempt. */
    val expiresAtMs: Long?,
) {
    /** The attempt can still be cancelled; once the sign-in is being saved the harness settles it first. */
    val cancellable: Boolean get() = step != SignInStep.Finishing

    override fun equals(other: Any?): Boolean = other is SignInProgress &&
        attemptId == other.attemptId && step == other.step && authorizeUrl == other.authorizeUrl && expiresAtMs == other.expiresAtMs

    override fun hashCode(): Int = ((attemptId.hashCode() * 31 + step.hashCode()) * 31 + (authorizeUrl?.hashCode() ?: 0)) * 31 + (expiresAtMs?.hashCode() ?: 0)

    override fun toString(): String = "SignInProgress(attemptId=$attemptId, step=$step, hasLink=${authorizeUrl != null})"
}

/** Why a sign-in that ended badly ended, in the words a screen can give each one. */
enum class SignInProblem {
    /** The harness could not reach DeepSeek at all (`no-response`: no connection, or it timed out). */
    PlatformUnreachable,

    /** The harness could not save the sign-in in its own storage. */
    Storage,

    /** DeepSeek answered with something the harness could not use (HTTP error, bad response). */
    Generic,
}

/** How the last attempt ended, kept by the harness until the next one. */
sealed interface SignInOutcome {
    data class Failed(val problem: SignInProblem) : SignInOutcome

    /** The link was not used in time. */
    data object Expired : SignInOutcome

    /**
     * The attempt was cancelled, here or on the harness computer. The page used to go straight back to
     * "Not signed in" with no word about it, which read as if nothing had happened.
     */
    data object Cancelled : SignInOutcome
}

/**
 * What the Account page needs from `account/getState`, with the harness's retention rules applied.
 *
 * The harness keeps the latest attempt after it ends: a signed-in harness still carries a
 * `succeeded` attempt, and a signed-out one carries the `cancelled`, `failed` or `expired` attempt of
 * the last try. So the attempt's phase alone is not "something is running": [progress] is set only for
 * a phase that is, and [outcome] only for how the last try ended on a harness that is signed out.
 */
data class AccountSnapshot(
    val signedIn: Boolean,
    val progress: SignInProgress?,
    val outcome: SignInOutcome?,
    /** Identifies the stored credential's origin so the screen knows to read the profile again; null while signed out. */
    val credentialKey: String?,
)

private const val STORED = "credential-stored"

/** One `account/getState` answer as the page reads it. A phase this app does not know is neither progress nor outcome. */
fun AccountView.toSnapshot(): AccountSnapshot {
    val signedIn = status == STORED
    val attempt = attempt
    val progress = attempt?.let { a ->
        val step = when (a.phase) {
            "initializing" -> SignInStep.Starting
            "waiting-browser" -> SignInStep.WaitingForBrowser
            "exchanging", "committing" -> SignInStep.Finishing
            else -> null
        }
        step?.let { SignInProgress(a.id, it, a.authorizeUrl, a.expiresAt) }
    }
    val outcome = if (signedIn || progress != null) null else when (attempt?.phase) {
        "failed" -> SignInOutcome.Failed(signInProblem(attempt.errorCode))
        "expired" -> SignInOutcome.Expired
        "cancelled" -> SignInOutcome.Cancelled
        else -> null
    }
    // A new succeeded attempt over a stored credential is a different sign-in (an account switch), so its id is part of the key.
    val key = if (!signedIn) null else (attempt?.takeIf { it.phase == "succeeded" }?.id ?: "")
    return AccountSnapshot(signedIn, progress, outcome, key)
}

/** The reason an attempt failed, from the harness's `SignInErrorCode`. */
fun signInProblem(errorCode: String?): SignInProblem = when (errorCode) {
    "no-response" -> SignInProblem.PlatformUnreachable
    "storage" -> SignInProblem.Storage
    // `network` (an HTTP error), `protocol` (a response it could not use) and anything newer.
    else -> SignInProblem.Generic
}

/** How often to ask the harness again while a sign-in runs: quickly once the browser has come back, since that part is short. */
fun accountPollDelayMs(step: SignInStep): Long = when (step) {
    SignInStep.Starting -> 500L
    SignInStep.WaitingForBrowser -> 2000L
    SignInStep.Finishing -> 700L
}
