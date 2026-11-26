package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.runtime.workspace.ProjectOperations
import com.labteto.dshmobile.ui.components.DsIconMenu
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.rememberHostsStore
import com.labteto.dshmobile.ui.screens.runtime.ProjectStarters
import com.labteto.dshmobile.ui.screens.runtime.ProjectsViewModel
import com.labteto.dshmobile.ui.screens.runtime.rememberProjectStarters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.Flow

/**
 * The sessions drawer, as Android hosts it.
 *
 * [SessionDrawer] is the drawer itself and reads nothing that is Android's. This host gives it what
 * only Android has: the phone-side preferences in [HostsStore], the calendar from `java.time`, the
 * TalkBack announcement through the view, and the built-in harness's projects ([ProjectsViewModel]:
 * start, clone, import, export), whose job dialog and page it shows itself.
 */
@Composable
fun ChatListDrawer(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAutomations: () -> Unit = {},
) {
    val hostsStore = rememberHostsStore()
    val prefs = remember(hostsStore) { HostsStoreDrawerPrefs(hostsStore) }
    val dialogs = remember { SessionDrawerDialogs() }
    // The built-in harness's projects: start one (empty, clone, import) and open a session in it.
    val projectsVm: ProjectsViewModel = hiltViewModel()
    val projectOps = projectsVm.operations
    val onBuiltInHarness by projectOps.onBuiltInState.collectAsStateWithLifecycle()
    val projectJob by projectOps.job.collectAsStateWithLifecycle()
    var projectJobShown by remember { mutableStateOf(false) }
    var projectsPageOpen by rememberSaveable { mutableStateOf(false) }
    val projectStarters = rememberProjectStarters(
        projectOps,
        open = true,
        onStarted = {
            dialogs.newSession = false
            projectJobShown = true
        },
        onCreated = {
            dialogs.newSession = false
            onClose()
        },
    )
    // Where a moved row landed, said to TalkBack through the view.
    val view = LocalView.current
    val moveAnnounceFormat = stringResource(R.string.chatlist_move_announce)

    SessionDrawer(
        prefs = prefs,
        calendar = JavaTimeDrawerCalendar,
        dialogs = dialogs,
        onClose = onClose,
        onOpenSettings = onOpenSettings,
        onOpenAutomations = onOpenAutomations,
        announceMove = { position, size ->
            view.announceForAccessibility(String.format(moveAnnounceFormat, position, size))
        },
        projects = if (onBuiltInHarness) {
            DrawerProjects(
                onOpen = { projectsPageOpen = true },
                newSessionRows = {
                    ProjectStartRows(
                        starters = projectStarters,
                        onManage = {
                            dialogs.newSession = false
                            projectsPageOpen = true
                        },
                    )
                },
                workspaceExtras = { dismiss ->
                    projectWorkspaceExtras(projectOps, onProjectJob = { projectJobShown = true }, onDismiss = dismiss)
                },
            )
        } else {
            null
        },
    )

    if (projectJobShown) {
        val job = projectJob
        if (job == null) {
            projectJobShown = false
        } else {
            // Made from the new-session dialog: once it is done and its session is open, the drawer has done its part.
            LaunchedEffect(job) {
                if (job is com.labteto.dshmobile.runtime.workspace.ProjectJob.Done && onBuiltInHarness &&
                    job.kind != com.labteto.dshmobile.runtime.workspace.JobKind.EXPORT_ZIP && job.kind != com.labteto.dshmobile.runtime.workspace.JobKind.SAVE_COPY
                ) {
                    projectOps.dismiss()
                    projectJobShown = false
                    onClose()
                }
            }
            com.labteto.dshmobile.ui.screens.runtime.ProjectJobDialog(
                projectOps,
                job,
                onHide = { projectJobShown = false },
                onOpened = {
                    projectJobShown = false
                    onClose()
                },
            )
        }
    }

    if (projectsPageOpen) {
        com.labteto.dshmobile.ui.screens.runtime.ProjectsPage(projectOps, onClose = { projectsPageOpen = false })
    }
}

/** [HostsStore] is where Android keeps the drawer's phone-side preferences. */
private class HostsStoreDrawerPrefs(private val hosts: HostsStore) : SessionDrawerPrefs {
    override val sessionSort: Flow<String> get() = hosts.sessionSort

    override suspend fun setSessionSort(value: String) {
        hosts.setSessionSort(value)
    }

    override fun ungroupedOrder(hostKey: String?): Flow<List<String>> = hosts.ungroupedOrder(hostKey)

    override suspend fun setUngroupedOrder(hostKey: String, ids: List<String>) {
        hosts.setUngroupedOrder(hostKey, ids)
    }

    override fun collapsedProjects(hostKey: String?): Flow<Set<String>> = hosts.collapsedProjects(hostKey)

    override suspend fun setProjectCollapsed(hostKey: String, projectKey: String, collapsed: Boolean) {
        hosts.setProjectCollapsed(hostKey, projectKey, collapsed)
    }
}

/** The new-session dialog's rows on the built-in harness: the ways to start a project there. */
@Composable
private fun ProjectStartRows(starters: ProjectStarters, onManage: () -> Unit) {
    com.labteto.dshmobile.ui.components.SectionHeader(stringResource(R.string.projects_start_header))
    SheetRow(
        title = stringResource(R.string.projects_new_empty),
        subtitle = stringResource(R.string.projects_new_empty_hint),
        onClick = { starters.request(com.labteto.dshmobile.ui.screens.runtime.ProjectStart.EMPTY) },
    )
    SheetRow(
        title = stringResource(R.string.projects_clone),
        subtitle = stringResource(R.string.projects_clone_hint),
        onClick = { starters.request(com.labteto.dshmobile.ui.screens.runtime.ProjectStart.CLONE) },
    )
    SheetRow(
        title = stringResource(R.string.projects_import_folder),
        subtitle = stringResource(R.string.projects_import_folder_hint),
        onClick = { starters.request(com.labteto.dshmobile.ui.screens.runtime.ProjectStart.FOLDER) },
    )
    SheetRow(
        title = stringResource(R.string.projects_import_zip),
        subtitle = stringResource(R.string.projects_import_zip_hint),
        onClick = { starters.request(com.labteto.dshmobile.ui.screens.runtime.ProjectStart.ZIP) },
    )
    SheetRow(
        title = stringResource(R.string.projects_manage),
        onClick = onManage,
    )
}

/**
 * Files, zip and copy for a workspace under `/workspaces` on the built-in harness, and the export
 * dialog they open. Composed inside the workspace list, so the export in progress lives as long as it.
 */
@Composable
private fun projectWorkspaceExtras(
    ops: ProjectOperations,
    onProjectJob: () -> Unit,
    onDismiss: () -> Unit,
): WorkspaceListExtras {
    var exporting by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val context = LocalContext.current
    return WorkspaceListExtras(
        rowActions = { workspace ->
            val project = projectOfWorkspace(workspace.path)
            if (project != null) {
                ProjectShareMenu(
                    project = project,
                    onFiles = { com.labteto.dshmobile.ui.screens.runtime.openInFiles(context, com.labteto.dshmobile.runtime.workspace.WorkspaceTree.ROOT_ID + "/" + project) },
                    onShare = { exporting = project to false },
                    onSave = { exporting = project to true },
                )
            }
        },
        dialogs = {
            exporting?.let { (name, save) ->
                com.labteto.dshmobile.ui.screens.runtime.ExportDialog(ops, name, saveCopy = save, onDismiss = { exporting = null }, onStarted = {
                    exporting = null
                    onProjectJob()
                    onDismiss()
                })
            }
        },
    )
}

/**
 * The project a workspace on the built-in harness is in: the first folder under `/workspaces`, or
 * null for any other path (a harness elsewhere, or the harness home).
 */
internal fun projectOfWorkspace(path: String): String? {
    val clean = path.trim().replace('\\', '/')
    if (!clean.startsWith(com.labteto.dshmobile.runtime.workspace.WorkspaceTree.GUEST_ROOT + "/")) return null
    val first = clean.removePrefix(com.labteto.dshmobile.runtime.workspace.WorkspaceTree.GUEST_ROOT + "/").substringBefore('/')
    return first.takeIf { com.labteto.dshmobile.runtime.workspace.WorkspaceTree.validSegment(it) && !it.startsWith(com.labteto.dshmobile.runtime.workspace.WorkspaceTree.RESERVED_PREFIX) }
}

/** Open in Files, Share as .zip and Save a copy, for a project row of the workspace list. */
@Composable
private fun ProjectShareMenu(project: String, onFiles: () -> Unit, onShare: () -> Unit, onSave: () -> Unit) {
    DsIconMenu(
        icon = Icons.Outlined.Share,
        contentDescription = stringResource(R.string.projects_more_for, project),
        items = listOf(
            MenuItem(stringResource(R.string.projects_open_files), onClick = onFiles),
            MenuItem(stringResource(R.string.projects_action_share_zip), onClick = onShare),
            MenuItem(stringResource(R.string.projects_action_save_copy), onClick = onSave),
        ),
    )
}

// ---------------------------------------------------------------------------
// The calendar of the date headings
// ---------------------------------------------------------------------------

private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** Days are `java.time` epoch days in the device's zone. */
private object JavaTimeDrawerCalendar : DrawerCalendar {
    override fun dayOf(epochMs: Long): Long =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    @Composable
    override fun label(day: Long): String = dayLabel(LocalDate.ofEpochDay(day))
}

@Composable
private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> stringResource(R.string.ink_chat_today)
        today.minusDays(1) -> stringResource(R.string.ink_chat_yesterday)
        else -> dayFormatter.format(day)
    }
}
