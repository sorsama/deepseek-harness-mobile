package com.labteto.dshmobile.ui.screens.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.runtime.workspace.ProjectInfo
import com.labteto.dshmobile.runtime.workspace.ProjectJob
import com.labteto.dshmobile.runtime.workspace.ProjectList
import com.labteto.dshmobile.runtime.workspace.ProjectOperations
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsFullScreenDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsMenu
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.inkFieldFrame
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.screens.connect.ConnectButtonVariant
import com.labteto.dshmobile.ui.screens.connect.DsConnectButton
import com.labteto.dshmobile.ui.screens.main.ConfirmDialog
import com.labteto.dshmobile.ui.screens.main.dialogTextFieldColors
import com.labteto.dshmobile.ui.screens.main.preview.formatBytes
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

/**
 * The built-in harness's projects: what is on the phone, how big, when it last changed, and
 * everything to do with one (start a session, open it in Files, share it as a zip, save a copy,
 * rename, delete), plus the four ways to start a new one.
 */
@Composable
internal fun ProjectsPage(operations: ProjectOperations, onClose: () -> Unit) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    val haptics = rememberDsHaptics()
    val toast = rememberDsToast()
    val scope = rememberCoroutineScope()
    val page = rememberPageVisibility(onClose)
    val projects by operations.projects.collectAsStateWithLifecycle()
    val job by operations.job.collectAsStateWithLifecycle()
    var exporting by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { operations.refresh() }
    val noFiles = stringResource(R.string.projects_no_files_app)
    val inUse = stringResource(R.string.projects_in_use)
    val failed = stringResource(R.string.projects_change_failed)
    val onBuiltIn = operations.onBuiltIn()
    val starters = rememberProjectStarters(operations, open = false, onStarted = {})

    DsFullScreenDialog(onDismissRequest = page::hide, visibleState = page.state, onWindowReady = page::show) {
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DsIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        onClick = page::hide,
                        tint = colors.labelPrimary,
                    )
                    Spacer(Modifier.width(DsSpacing.small))
                    Text(
                        stringResource(R.string.projects_title),
                        style = DsType.large20,
                        color = colors.labelPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                }
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = DsSpacing.comfortable),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(bottom = DsSpacing.large),
                        verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
                    ) {
                        Text(
                            stringResource(R.string.projects_intro, isolated(stringResource(R.string.app_name))),
                            style = DsType.std14,
                            color = colors.labelSecondary,
                        )
                        ProjectStartButtons(starters)
                        job?.let { current ->
                            Section(jobTitle(current)) {
                                Column(Modifier.padding(vertical = DsSpacing.small), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                                    ProjectJobBody(current)
                                    ButtonRow {
                                        if (current is ProjectJob.Running) {
                                            DsConnectButton(stringResource(R.string.common_cancel), onClick = {
                                                haptics.tap()
                                                operations.cancel()
                                            }, variant = ConnectButtonVariant.Outline)
                                        } else {
                                            if (current is ProjectJob.Done && current.shareUri != null) {
                                                DsConnectButton(stringResource(R.string.projects_job_share), onClick = {
                                                    shareZip(context, current.shareUri, current.name)
                                                }, variant = ConnectButtonVariant.Outline)
                                            }
                                            DsConnectButton(stringResource(R.string.common_ok), onClick = {
                                                haptics.tap()
                                                operations.dismiss()
                                            }, variant = ConnectButtonVariant.Ghost)
                                        }
                                    }
                                }
                            }
                        }
                        val total = operations.totalBytes()
                        Section(
                            if (projects.isEmpty()) {
                                stringResource(R.string.projects_section_list)
                            } else {
                                pluralStringResource(R.plurals.projects_count, projects.size, projects.size) + " · " +
                                    (total?.let { stringResource(R.string.projects_space_used, isolated(formatBytes(it))) } ?: stringResource(R.string.projects_counting))
                            },
                        ) {
                            if (projects.isEmpty()) {
                                Text(
                                    stringResource(R.string.projects_empty),
                                    style = DsType.std14,
                                    color = colors.labelSecondary,
                                    modifier = Modifier.padding(vertical = DsSpacing.medium),
                                )
                            }
                            projects.forEach { project ->
                                ProjectRow(
                                    project = project,
                                    onBuiltIn = onBuiltIn,
                                    onSession = {
                                        scope.launch {
                                            if (operations.openSession(project.guestPath)) {
                                                haptics.confirm()
                                                page.hide()
                                            }
                                        }
                                    },
                                    onFiles = { if (!openInFiles(context, project.documentId)) toast.second(noFiles) },
                                    onShare = { exporting = project.name to false },
                                    onSave = { exporting = project.name to true },
                                    onRename = { renaming = project.name },
                                    onDelete = { deleting = project.name },
                                )
                            }
                            Spacer(Modifier.padding(bottom = DsSpacing.xsmall))
                        }
                        ButtonRow {
                            DsConnectButton(stringResource(R.string.projects_open_files), onClick = {
                                haptics.tap()
                                if (!openInFiles(context)) toast.second(noFiles)
                            }, variant = ConnectButtonVariant.Outline)
                        }
                    }
                }
            }
            DsToastHost(toast, modifier = Modifier.fillMaxWidth())
        }
    }

    exporting?.let { (name, save) ->
        ExportDialog(operations, name, saveCopy = save, onDismiss = { exporting = null }, onStarted = { exporting = null })
    }
    renaming?.let { name ->
        RenameProjectDialog(operations, name, onDismiss = { renaming = null }, onResult = { message ->
            renaming = null
            message?.let { toast.second(it) }
        }, inUse = inUse, failed = failed)
    }
    deleting?.let { name ->
        ConfirmDialog(
            title = stringResource(R.string.projects_delete_title, name),
            body = stringResource(R.string.projects_delete_body),
            confirmLabel = stringResource(R.string.common_delete),
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                scope.launch {
                    when (operations.delete(name)) {
                        ProjectOperations.ChangeResult.Done -> haptics.confirm()
                        ProjectOperations.ChangeResult.InUse -> {
                            haptics.reject()
                            toast.second(inUse)
                        }
                        else -> {
                            haptics.reject()
                            toast.second(failed)
                        }
                    }
                }
            },
        )
    }
}

/** The four ways to start a project. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProjectStartButtons(starters: ProjectStarters) {
    Section(stringResource(R.string.projects_start_header)) {
        StartRow(stringResource(R.string.projects_new_empty), stringResource(R.string.projects_new_empty_hint)) { starters.request(ProjectStart.EMPTY) }
        StartRow(stringResource(R.string.projects_clone), stringResource(R.string.projects_clone_hint)) { starters.request(ProjectStart.CLONE) }
        StartRow(stringResource(R.string.projects_import_folder), stringResource(R.string.projects_import_folder_hint)) { starters.request(ProjectStart.FOLDER) }
        StartRow(stringResource(R.string.projects_import_zip), stringResource(R.string.projects_import_zip_hint)) { starters.request(ProjectStart.ZIP) }
    }
}

@Composable
private fun StartRow(title: String, hint: String, onClick: () -> Unit) {
    com.labteto.dshmobile.ui.screens.main.SheetRow(title = title, subtitle = hint, onClick = onClick)
}

@Composable
private fun ProjectRow(
    project: ProjectInfo,
    onBuiltIn: Boolean,
    onSession: () -> Unit,
    onFiles: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = DsTheme.colors
    val size = project.sizeBytes?.let { isolated(formatBytes(it)) } ?: stringResource(R.string.projects_counting)
    val changed = relativeTime(project.lastChange)
    val more = stringResource(R.string.projects_more_for, project.name)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = DsSpacing.xsmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(project.name, style = DsType.std14Strong, color = colors.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.projects_row_detail, size, changed),
                style = DsType.small13,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DsMenu(
            anchor = {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = null,
                    tint = colors.labelSecondary,
                    modifier = Modifier.semantics { contentDescription = more },
                )
            },
            items = buildList {
                if (onBuiltIn) add(MenuItem(stringResource(R.string.projects_action_session), onClick = onSession))
                add(MenuItem(stringResource(R.string.projects_open_files), onClick = onFiles))
                add(MenuItem(stringResource(R.string.projects_action_share_zip), onClick = onShare))
                add(MenuItem(stringResource(R.string.projects_action_save_copy), onClick = onSave))
                add(MenuItem(stringResource(R.string.common_rename), onClick = onRename))
                add(MenuItem(stringResource(R.string.common_delete), danger = true, onClick = onDelete))
            },
        )
    }
}

@Composable
private fun RenameProjectDialog(
    operations: ProjectOperations,
    current: String,
    onDismiss: () -> Unit,
    onResult: (String?) -> Unit,
    inUse: String,
    failed: String,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(current) }
    var problem by remember { mutableStateOf<ProjectList.NameProblem?>(null) }
    DsDialog(
        title = stringResource(R.string.projects_rename_title), onDismiss = onDismiss,
        body = {
            TextField(
                value = name,
                onValueChange = {
                    name = it
                    problem = null
                },
                modifier = Modifier.fillMaxWidth().inkFieldFrame(),
                singleLine = true,
                label = { Text(stringResource(R.string.projects_name_label), style = DsType.std14) },
                isError = problem != null,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                colors = dialogTextFieldColors(),
                shape = DsShapes.control,
            )
            problem?.let { Text(nameProblemText(it), style = DsType.small13, color = colors.error) }
            Text(stringResource(R.string.projects_rename_note), style = DsType.small13, color = colors.labelSecondary)
        },
        actions = {
            DialogButtons(
                confirm = stringResource(R.string.common_save),
                enabled = name.isNotBlank(),
                onDismiss = onDismiss,
                onConfirm = {
                    scope.launch {
                        when (val r = operations.rename(current, name)) {
                            ProjectOperations.ChangeResult.Done -> {
                                haptics.confirm()
                                onResult(null)
                            }
                            is ProjectOperations.ChangeResult.Problem -> {
                                haptics.reject()
                                problem = r.problem
                            }
                            ProjectOperations.ChangeResult.InUse -> {
                                haptics.reject()
                                onResult(inUse)
                            }
                            ProjectOperations.ChangeResult.Failed -> {
                                haptics.reject()
                                onResult(failed)
                            }
                        }
                    }
                },
            )
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.xsmall),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) { content() }
}
