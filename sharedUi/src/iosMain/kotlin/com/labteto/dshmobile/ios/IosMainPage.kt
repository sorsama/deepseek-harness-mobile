package com.labteto.dshmobile.ios

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.labteto.dshmobile.data.wallClockMillis
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.chatlist_move_announce
import com.labteto.dshmobile.shared.resources.ink_chat_today
import com.labteto.dshmobile.shared.resources.ink_chat_yesterday
import com.labteto.dshmobile.ui.LocalSessionStore
import com.labteto.dshmobile.ui.adaptive.AdaptiveShell
import com.labteto.dshmobile.ui.adaptive.planShell
import com.labteto.dshmobile.ui.screens.main.DrawerCalendar
import com.labteto.dshmobile.ui.screens.main.MainFeatures
import com.labteto.dshmobile.ui.screens.main.SORT_MANUAL
import com.labteto.dshmobile.ui.screens.main.SessionDrawer
import com.labteto.dshmobile.ui.screens.main.SessionDrawerDialogs
import com.labteto.dshmobile.ui.screens.main.SessionDrawerPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.stringResource
// A wildcard: `localTimeZone` and `dateWithTimeIntervalSince1970` are declared in Foundation categories, which
// Kotlin/Native turns into extensions with their own import names.
import platform.Foundation.*
import platform.UIKit.UIAccessibilityAnnouncementNotification
import platform.UIKit.UIAccessibilityPostNotification

/** What iOS has of the main screen for now: the session list and Disconnect, no Settings. */
internal val IosMainFeatures = MainFeatures(settings = false, disconnect = true)

/**
 * The main screen on iOS, for as much of it as has moved: the shared session list beside (or, on a phone, in a drawer
 * over) the chat pane. The chat itself is a later step of the port, so the pane is [chatPane]: for now the Connected
 * page, which says which harness this is and how many sessions it has, with a button that opens the list where the
 * list is a drawer.
 *
 * The layout is the Android one: [planShell] decides it from the window's size, so an iPhone gets the drawer, and an
 * iPad (or an iPhone Pro Max on its side) docks the list as a pane, and [AdaptiveShell] draws it. iOS has no hinge to
 * report, so the plan never splits on one.
 *
 * [openDrawer] (the debug launch argument `-DshOpenDrawer YES`) opens the drawer as soon as the page is shown, so a
 * script can take a screenshot of the list on a phone. [onDrawerShown] hears `DSH_DRAWER_RESULT shown …` once the
 * list is on screen with sessions in it, which is how the CI scenario fails when the list is missing.
 */
@Composable
internal fun IosMainPage(
    openDrawer: Boolean,
    onDrawerShown: ((String) -> Unit)?,
    onDisconnect: () -> Unit,
    chatPane: @Composable (onOpenSessions: (() -> Unit)?) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val prefs = remember { IosDrawerPrefs() }
    val dialogs = remember { SessionDrawerDialogs() }
    val store = LocalSessionStore.current
    val sessions by store.sessions.collectAsState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val plan = remember(maxWidth, maxHeight, rtl) { planShell(maxWidth, maxHeight, hinge = null, rtl = rtl) }
        // A window that grows wide enough to dock the list takes it out of the drawer.
        LaunchedEffect(plan.listDocked) { if (plan.listDocked) drawerState.snapTo(DrawerValue.Closed) }
        LaunchedEffect(Unit) {
            if (openDrawer && !plan.listDocked) {
                // After the first layout, when the drawer knows how wide it is.
                delay(OPEN_DRAWER_DELAY_MS)
                drawerState.open()
            }
        }

        val listOnScreen = plan.listDocked || drawerState.currentValue == DrawerValue.Open
        if (onDrawerShown != null) {
            LaunchedEffect(listOnScreen, sessions.size) {
                if (listOnScreen && sessions.isNotEmpty()) {
                    onDrawerShown("DSH_DRAWER_RESULT shown docked=${plan.listDocked} sessions=${sessions.size}")
                }
            }
        }

        AdaptiveShell(
            plan = plan,
            drawerState = drawerState,
            listShown = true,
            detailsShown = false,
            list = { docked ->
                IosSessionDrawer(
                    prefs = prefs,
                    dialogs = dialogs,
                    onClose = { if (!docked) scope.launch { drawerState.close() } },
                    onDisconnect = onDisconnect,
                )
            },
            details = {},
            chat = { chatPane(if (plan.listDocked) null else ({ scope.launch { drawerState.open() } })) },
        )
    }
}

/** The shared [SessionDrawer] with what iOS gives it: its preferences, its calendar, VoiceOver, no Settings. */
@Composable
private fun IosSessionDrawer(
    prefs: SessionDrawerPrefs,
    dialogs: SessionDrawerDialogs,
    onClose: () -> Unit,
    onDisconnect: () -> Unit,
) {
    // The positions are filled in here, after the move, so the template is read once in composition.
    val moveAnnounce = stringResource(Res.string.chatlist_move_announce)
    SessionDrawer(
        prefs = prefs,
        calendar = IosDrawerCalendar,
        dialogs = dialogs,
        onClose = onClose,
        onOpenSettings = {},
        onOpenAutomations = null,
        announceMove = { position, size ->
            val text = moveAnnounce.replace("%1\$d", position.toString()).replace("%2\$d", size.toString())
            UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, text)
        },
        projects = null,
        features = IosMainFeatures,
        onDisconnect = onDisconnect,
    )
}

private const val DAY_MS = 86_400_000L

/** How long a launch with `-DshOpenDrawer` waits before it opens the drawer. */
private const val OPEN_DRAWER_DELAY_MS = 500L

/**
 * The list's date headings on iOS. A day is the number of whole days since 1970 on the device's own calendar day
 * (the epoch milliseconds shifted by the time zone's offset at that instant), which is what Android's `java.time`
 * epoch day is; a heading reads "Today", "Yesterday" or the date in the person's medium style (`NSDateFormatter`,
 * which follows the region the person chose).
 */
internal object IosDrawerCalendar : DrawerCalendar {
    override fun dayOf(epochMs: Long): Long {
        val offsetSeconds = NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))
        return (epochMs + offsetSeconds * 1000L).floorDiv(DAY_MS)
    }

    @Composable
    override fun label(day: Long): String {
        val today = dayOf(wallClockMillis())
        return when (day) {
            today -> stringResource(Res.string.ink_chat_today)
            today - 1 -> stringResource(Res.string.ink_chat_yesterday)
            else -> remember(day) { mediumDate(day) }
        }
    }

    /** Midday of [day] in UTC, written for UTC: the calendar date never moves under a time zone. */
    private fun mediumDate(day: Long): String {
        val formatter = NSDateFormatter()
        formatter.dateStyle = NSDateFormatterMediumStyle
        formatter.timeStyle = NSDateFormatterNoStyle
        formatter.timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
        return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(day * 86_400.0 + 43_200.0))
    }
}

/**
 * The list's own preferences on iOS, in `NSUserDefaults` as Android keeps them in its `HostsStore`: the order, the
 * order of the sessions in no workspace per harness, and the project headers folded shut per harness. `-DshReset`
 * forgets them with everything else.
 */
internal class IosDrawerPrefs(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : SessionDrawerPrefs {
    private val sort = MutableStateFlow(defaults.stringForKey(SORT_KEY) ?: SORT_MANUAL)
    private val ungrouped = MutableStateFlow(readLists(UNGROUPED_KEY))
    private val folded = MutableStateFlow(readLists(FOLDED_KEY))

    override val sessionSort: Flow<String> = sort

    override suspend fun setSessionSort(value: String) {
        sort.value = value
        defaults.setObject(value, forKey = SORT_KEY)
    }

    override fun ungroupedOrder(hostKey: String?): Flow<List<String>> =
        ungrouped.map { all -> hostKey?.let { all[it] }.orEmpty() }.distinctUntilChanged()

    override suspend fun setUngroupedOrder(hostKey: String, ids: List<String>) {
        ungrouped.update { it + (hostKey to ids) }
        writeLists(UNGROUPED_KEY, ungrouped.value)
    }

    override fun collapsedProjects(hostKey: String?): Flow<Set<String>> =
        folded.map { all -> hostKey?.let { all[it] }.orEmpty().toSet() }.distinctUntilChanged()

    override suspend fun setProjectCollapsed(hostKey: String, projectKey: String, collapsed: Boolean) {
        folded.update { all ->
            val now = all[hostKey].orEmpty().toMutableSet()
            if (collapsed) now += projectKey else now -= projectKey
            all + (hostKey to now.sorted())
        }
        writeLists(FOLDED_KEY, folded.value)
    }

    private fun readLists(key: String): Map<String, List<String>> =
        defaults.stringForKey(key)?.let { runCatching { Json.decodeFromString(ListsSerializer, it) }.getOrNull() }.orEmpty()

    private fun writeLists(key: String, value: Map<String, List<String>>) {
        defaults.setObject(Json.encodeToString(ListsSerializer, value), forKey = key)
    }

    private companion object {
        const val SORT_KEY = "dsh.drawer.sessionSort"
        const val UNGROUPED_KEY = "dsh.drawer.ungroupedOrder"
        const val FOLDED_KEY = "dsh.drawer.collapsedProjects"
        val ListsSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    }
}
