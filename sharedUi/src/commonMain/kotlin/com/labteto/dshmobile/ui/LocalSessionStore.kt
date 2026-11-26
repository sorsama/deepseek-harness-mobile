package com.labteto.dshmobile.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.labteto.dshmobile.data.SessionStore

/**
 * The process's one [SessionStore], for every screen under the app root.
 *
 * Each platform's root provides it once: Android's `AppRoot` with the Hilt singleton, iOS with the store
 * its app builds. It is static because the store is made once per process and never replaced, so there is
 * no change for anything to recompose on. A screen composed without a provider fails at once with the
 * reason, rather than reaching for a second store.
 */
val LocalSessionStore = staticCompositionLocalOf<SessionStore> {
    error("No SessionStore here: the app root provides LocalSessionStore")
}
