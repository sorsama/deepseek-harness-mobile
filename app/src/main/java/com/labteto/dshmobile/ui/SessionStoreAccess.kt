package com.labteto.dshmobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.data.SessionStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point for the process-scoped [SessionStore].
 *
 * The store is a [javax.inject.Singleton], not a [androidx.lifecycle.ViewModel] — it outlives every
 * screen and owns the live mirror of the connected harness — so composables resolve it through an
 * app-scoped entry point rather than `hiltViewModel()`. One declaration serves every screen;
 * duplicating it per file would mean several Hilt-generated accessors for one singleton.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SessionStoreEntryPoint {
    fun sessionStore(): SessionStore
    fun hostsStore(): HostsStore
    fun reasoningTranslations(): com.labteto.dshmobile.translation.ReasoningTranslations
}

/**
 * Resolves the process-wide reasoning translation engine once per composition. One engine serves the
 * transcript, the subagent sheet and Settings, so they share its cache and its request limit.
 */
@Composable
internal fun rememberReasoningTranslationEngine(): com.labteto.dshmobile.translation.ReasoningTranslationEngine {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).reasoningTranslations().engine
    }
}

/** Resolves the process-scoped [SessionStore] once per composition. */
@Composable
internal fun rememberSessionStore(): SessionStore {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).sessionStore()
    }
}

/**
 * Resolves the process-scoped [HostsStore] once per composition.
 *
 * For the handful of preferences a screen owns outright — drawer sort order, for instance — where
 * routing through a ViewModel would add a layer that only forwards.
 */
@Composable
internal fun rememberHostsStore(): HostsStore {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).hostsStore()
    }
}
