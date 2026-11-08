package com.labteto.dshmobile.termux

// The state of the Termux harness controller, as the Connect screen reads it. Only the types live in
// the shared module: Termux itself (the controller, its bridge, its intents) is Android and stays in :app.

/** How a start left the sign-in: the whole point of starting the harness from the app. */
enum class StartSignIn {
    /** The readiness line carried a token and the harness accepted it; the cookie is stored. */
    SIGNED_IN,

    /** The harness printed no token (older than 0.1.2); sign in with its startup line. */
    NO_TOKEN,

    /** The harness printed a token and then refused it — the line was stale or truncated. */
    REFUSED,

    /** The harness printed the line and then could not be reached for the exchange. */
    UNREACHABLE,
}

/** Why a Termux command did not do what was asked. */
sealed interface HarnessControlFailure {
    data object NotInstalled : HarnessControlFailure
    data object PermissionDenied : HarnessControlFailure
    data object ExternalAppsDisabled : HarnessControlFailure
    data object DshMissing : HarnessControlFailure
    data object NodeMissing : HarnessControlFailure

    /** The harness process ended before it was ready; [tail] is the end of its log. */
    data class Exited(val tail: String) : HarnessControlFailure

    /** The harness never printed its readiness line; [tail] is the end of its log. */
    data class ReadyTimeout(val tail: String) : HarnessControlFailure

    /** Termux did not run the command, or never answered; [detail] is whatever it said. */
    data class TermuxUnavailable(val detail: String?) : HarnessControlFailure

    /** The update could not be downloaded, verified or opened; [detail] names the step. */
    data class InstallFailed(val detail: String) : HarnessControlFailure
}

/** What the controller is doing, or last did. */
sealed interface HarnessControl {
    data object Idle : HarnessControl
    data class Starting(val port: Int) : HarnessControl
    data class Stopping(val port: Int) : HarnessControl
    data class Installing(val version: String) : HarnessControl

    /** A start finished; cleared by [TermuxHarnessController.acknowledge]. */
    data class Started(val port: Int, val signIn: StartSignIn) : HarnessControl

    /** A command failed; cleared by the next command or by [TermuxHarnessController.acknowledge]. */
    data class Failed(val reason: HarnessControlFailure) : HarnessControl

    val busy: Boolean get() = this is Starting || this is Stopping || this is Installing
}
