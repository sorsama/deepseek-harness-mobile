package com.labteto.dshmobile.ui.addons

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.labteto.dshmobile.connection.HarnessSessionStore
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.connection.RelayHandoffOutcome
import com.labteto.dshmobile.connection.RelaySessionHandoff
import com.labteto.dshmobile.core.uisync.uiSyncDiscoveryNeed
import com.labteto.dshmobile.core.uisync.uiSyncPluginFingerprint
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.LocalThemeCustomization
import com.labteto.dshmobile.ui.theme.isDsDark
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlin.math.roundToInt

/** The activity's add-on layer, for the chat to report itself to; null outside the app's root. */
internal val LocalUiAddonsHost = staticCompositionLocalOf<UiAddonsHost?> { null }

/** Where the chat's top bar ends and its composer starts, measured by the chat itself; null outside a layer. */
internal val LocalUiAddonsChrome = staticCompositionLocalOf<UiAddonsChrome?> { null }

/** The chat's chrome, in root pixels, as the chat measured it last. */
@Stable
internal class UiAddonsChrome {
    /** Top of the conversation area, just below the top bar. */
    var contentTop by mutableStateOf<Float?>(null)

    /** The composer block: the field and what sits directly above it. */
    var composer by mutableStateOf<Rect?>(null)
}

/** Reports the top of the conversation area to the add-on layer; nothing outside one. */
internal fun Modifier.uiAddonsContentTop(chrome: UiAddonsChrome?): Modifier =
    if (chrome == null) this else onGloballyPositioned { chrome.contentTop = it.boundsInRoot().top }

/** Reports the composer block to the add-on layer; nothing outside one. */
internal fun Modifier.uiAddonsComposer(chrome: UiAddonsChrome?): Modifier =
    if (chrome == null) this else onGloballyPositioned { chrome.composer = it.boundsInRoot() }

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UiAddonsEntryPoint {
    /** Named for this entry point alone: two entry points with one getter name clash in Hilt. */
    fun uiAddonsHarnessSessions(): HarnessSessionStore

    fun uiAddonsHostsStore(): HostsStore

    fun uiAddonsRelayHandoff(): RelaySessionHandoff
}

/**
 * Whether "Show plugin add-ons" reads as on where nobody has set it, for this device: 6 GB of memory
 * or more, and not a low-RAM phone (see [uiAddonsDefaultOn]).
 */
internal fun uiAddonsDefaultOn(context: Context): Boolean {
    val manager = context.getSystemService(ActivityManager::class.java) ?: return false
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    return uiAddonsDefaultOn(info.totalMem, manager.isLowRamDevice)
}

/**
 * The activity's add-on layer: made once per activity at the root, destroyed with it.
 *
 * It follows the connection from here, not from the chat, so a host switch or a disconnect takes the
 * page down even while Settings is open: the page belongs to a host, and nothing of one host's page
 * may survive into another's.
 */
@Composable
internal fun rememberUiAddonsHost(): UiAddonsHost? {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? ComponentActivity } ?: return null
    val store = rememberSessionStore()
    val entry = remember(context) { EntryPointAccessors.fromApplication(context.applicationContext, UiAddonsEntryPoint::class.java) }
    val sessions = remember(entry) { entry.uiAddonsHarnessSessions() }
    val relayHandoff = remember(entry) { entry.uiAddonsRelayHandoff() }
    val records = remember(context) { UiAddonsDiscoveryRecords(context) }
    val host = remember(activity, store) {
        UiAddonsHost(activity, store.uiAddons, store.uiAddonsDiscovery, records, refreshStore = { store.uiAddonsSettings.refresh() }) handoff@{ target ->
            // Only for the relay the page belongs to: a host switch while the code was on its way drops it.
            val config = store.connectionState.value.host
                ?.takeIf { it.isRelay && uiAddonsTarget(it, null)?.key == target.key }
                ?: return@handoff RelayHandoffOutcome.Failed("host changed")
            relayHandoff.open(config)
        }
    }
    DisposableEffect(host) { onDispose { host.destroy() } }

    val connection by store.connectionState.collectAsStateWithLifecycle()
    val available by store.uiAddonsAvailable.collectAsStateWithLifecycle()
    val addonSettings by store.uiAddonsSettings.state.collectAsStateWithLifecycle()
    val appSettings by remember(entry) { entry.uiAddonsHostsStore().settings }.collectAsStateWithLifecycle(initialValue = null)
    val defaultOn = remember(context) { uiAddonsDefaultOn(context) }
    // Off until the stored settings are read: a page is never made on a guess.
    val enabled = appSettings?.let { it.showUiAddons ?: defaultOn } ?: false
    val config = connection.host
    var cookie by remember(config?.id, config?.baseUrl) { mutableStateOf<String?>(null) }
    LaunchedEffect(config?.id, config?.baseUrl, config?.isRelay) {
        // A relay needs none: the page signs in through the relay's own handoff.
        cookie = config?.takeIf { !it.isRelay }?.let { sessions.cookie(it.id) }
    }
    val target = remember(config, cookie) { config?.let { uiAddonsTarget(it, cookie) } }
    // Discovery: does the item store miss something the plugin list has? Judged only once both are read for this host.
    val inventory by store.uiAddonsInventory.collectAsStateWithLifecycle()
    val session by store.currentSessionId.collectAsStateWithLifecycle()
    val plugins = inventory?.takeIf { it.host == store.activeHostKey }
    val storeRead = addonSettings.row?.let { addonSettings.store }
    val (need, fingerprint) = remember(storeRead, plugins) {
        val need = if (storeRead != null && plugins != null) uiSyncDiscoveryNeed(storeRead, plugins.entries) else null
        need to uiSyncPluginFingerprint(plugins?.entries)
    }
    val binding = UiAddonsBinding(target, available, enabled, addonSettings.phoneItems, storeRead, need, fingerprint, session)
    LaunchedEffect(host, binding) { host.bind(binding) }
    return host
}

/**
 * The chat pane, reported to the add-on layer: where it is, what covers it, its chrome, its scheme
 * and its session. The layer draws over it; nothing here changes how the chat itself lays out.
 * Outside an add-on layer ([LocalUiAddonsHost] null) this is just [content].
 */
@Composable
internal fun UiAddonsChat(covered: Boolean, content: @Composable () -> Unit) {
    val host = LocalUiAddonsHost.current
    if (host == null) {
        content()
        return
    }
    val store = rememberSessionStore()
    val session by store.currentSessionId.collectAsStateWithLifecycle()
    val dark = isDsDark(LocalThemeCustomization.current)
    val chrome = remember { UiAddonsChrome() }
    // Back on the chat (from Settings, from the background): what the host's item store says may
    // have changed meanwhile, so read it again. Only where the companion is there to ask.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(store, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (store.uiAddonsAvailable.value == true) store.refreshUiAddons()
        }
    }
    val pane = remember { mutableStateOf<Rect?>(null) }
    val now by rememberUpdatedState(Triple(covered, dark, session))

    // Read through a snapshot flow, not in composition: the chat does not recompose when the
    // keyboard moves its composer, and the layer hears each change once.
    LaunchedEffect(host) {
        try {
            snapshotFlow {
                val bounds = pane.value ?: return@snapshotFlow null
                val (isCovered, isDark, sessionId) = now
                val composer = chrome.composer
                UiAddonsPresence(
                    left = bounds.left.roundToInt(),
                    top = bounds.top.roundToInt(),
                    width = bounds.width.roundToInt(),
                    height = bounds.height.roundToInt(),
                    chrome = UiAddonsChromeBounds(
                        paneLeft = bounds.left,
                        paneTop = bounds.top,
                        paneRight = bounds.right,
                        paneBottom = bounds.bottom,
                        contentTop = chrome.contentTop,
                        composerLeft = composer?.left,
                        composerTop = composer?.top,
                        composerRight = composer?.right,
                    ),
                    covered = isCovered,
                    dark = isDark,
                    sessionId = sessionId,
                )
            }.collect { host.present(it) }
        } finally {
            host.present(null)
        }
    }

    CompositionLocalProvider(LocalUiAddonsChrome provides chrome) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { pane.value = it.boundsInRoot() }) { content() }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
