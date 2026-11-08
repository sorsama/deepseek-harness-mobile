package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable

/**
 * [value] written the way the person's language writes a whole number, with no grouping.
 *
 * Android's `getString(id, 3)` formats a `%d` with the app's locale, so Arabic, Bengali, Hindi and
 * Urdu readers see their own digits. Compose Multiplatform fills a `%d` with `toString()` and so
 * always writes Western digits. A screen that moves to shared string resources passes its counts
 * through here first and gives the text a String, which keeps the Android screen as it was.
 */
@Composable
expect fun localizedCount(value: Int): String
