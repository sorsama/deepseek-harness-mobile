package com.labteto.dshmobile.ui

import com.labteto.dshmobile.connection.ConnectionPhase

/** The top-level screens an app root switches between. */
enum class RootRoute { Connect, Main, Settings, Pair }

/**
 * Which top-level screen to show, the one decision both roots make: the Android `AppRoot` and the iOS `DshApp`.
 *
 * Settings and Pair are places the person went to, so they win over whatever the connection is doing: Settings
 * holds the connection controls, and pairing ends by connecting, which must not pull the Pair screen away while
 * it reports its result. Otherwise the main screen shows while the link is up, and stays while a link that has
 * been up reconnects (the chat says it is reconnecting; sending the person back to the Connect form would lose
 * their place for a drop of a few seconds). A first connection that is still on its way is the Connect screen,
 * which shows its own progress.
 *
 * iOS has no Settings screen yet and passes false for [showSettings].
 */
fun rootRouteOf(phase: ConnectionPhase, hasConnected: Boolean, showSettings: Boolean, showPair: Boolean): RootRoute = when {
    showSettings -> RootRoute.Settings
    showPair -> RootRoute.Pair
    phase == ConnectionPhase.CONNECTED -> RootRoute.Main
    phase == ConnectionPhase.RECONNECTING && hasConnected -> RootRoute.Main
    else -> RootRoute.Connect
}
