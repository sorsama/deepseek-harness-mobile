package com.labteto.dshmobile.ios

import androidx.compose.runtime.Composable

/**
 * What the host asked the app to do when it opened, beyond showing the Connect screen.
 *
 * The Swift host fills this in from launch arguments in debug builds only, which is how CI drives
 * the app in a simulator without a person typing: [address] pre-fills the field and [autoConnect]
 * connects at once, down the same path as a tap on Connect. [onOutcome] hears the one result line
 * (see [logLine]) so a script can read what happened.
 */
internal class LaunchRequest(
    val address: String? = null,
    val autoConnect: Boolean = false,
    val onOutcome: ((String) -> Unit)? = null,
)

/** The whole app for now: the Connect screen. */
@Composable
internal fun DshApp(request: LaunchRequest = LaunchRequest()) {
    DshTheme {
        ConnectScreen(request)
    }
}
