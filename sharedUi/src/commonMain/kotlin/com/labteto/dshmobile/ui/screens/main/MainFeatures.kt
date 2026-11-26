package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Immutable

/**
 * What the main screen offers that not every platform has yet, so a shared screen can leave out what its host
 * cannot do instead of drawing a control that does nothing. What only Android has for good (the built-in
 * harness's projects, automations) reaches the drawer as nullable slots instead; these are the flags.
 *
 * Android has all of it ([Android]). iOS has no Settings screen yet, so its way back to the Connect screen is a
 * Disconnect entry in the drawer's menu.
 */
@Immutable
class MainFeatures(
    /** The gear and a tap on the harness pill open Settings. */
    val settings: Boolean,
    /** The drawer's overflow menu ends with Disconnect, for a platform with no Settings to hold it. */
    val disconnect: Boolean,
) {
    companion object {
        /** What the Android app has always offered. */
        val Android = MainFeatures(settings = true, disconnect = false)
    }
}
