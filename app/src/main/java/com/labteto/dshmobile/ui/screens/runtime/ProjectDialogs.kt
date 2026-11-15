package com.labteto.dshmobile.ui.screens.runtime

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.ViewModel
import com.labteto.dshmobile.R
import com.labteto.dshmobile.runtime.BuiltInHarnessHost
import com.labteto.dshmobile.runtime.workspace.ExportFilter
import com.labteto.dshmobile.runtime.workspace.GitClone
import com.labteto.dshmobile.runtime.workspace.JobFailure
import com.labteto.dshmobile.runtime.workspace.JobKind
import com.labteto.dshmobile.runtime.workspace.ProjectJob
import com.labteto.dshmobile.runtime.workspace.ProjectList
import com.labteto.dshmobile.runtime.workspace.ProjectOperations
import com.labteto.dshmobile.runtime.workspace.TransferProblem
import com.labteto.dshmobile.runtime.workspace.WorkspaceTree
import com.labteto.dshmobile.runtime.workspace.WorkspacesProvider
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.NoCopyTextToolbar
import com.labteto.dshmobile.ui.components.SecureContent
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.components.inkFieldFrame
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.screens.main.dialogTextFieldColors
import com.labteto.dshmobile.ui.screens.main.preview.formatBytes
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The built-in harness's projects, for any screen: the Projects page, the new-session dialog, the workspace list. */
@HiltViewModel
class ProjectsViewModel @Inject constructor(
    val operations: ProjectOperations,
    val host: BuiltInHarnessHost,
) : ViewModel()

/** What a picker or dialog started, so one job dialog can follow it. */
enum class ProjectStart { EMPTY, CLONE, FOLDER, ZIP }

/**
 * The system pickers for "Import a folder" and "Import a .zip", and the dialogs for a new empty
 * project and a clone. [request] opens one; [open] makes the new project a session's workspace.
 */
class ProjectStarters(
    val request: (ProjectStart) -> Unit,
)

@Composable
fun rememberProjectStarters(operations: ProjectOperations, open: Boolean, onStarted: () -> Unit, onCreated: (String) -> Unit = {}): ProjectStarters {
    var dialog by rememberSaveable { mutableStateOf<ProjectStart?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && operations.importFolder(uri, open)) onStarted()
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && operations.importZip(uri, open)) onStarted()
    }
    when (dialog) {
        ProjectStart.EMPTY -> NewEmptyProjectDialog(operations, open, onDismiss = { dialog = null }, onCreated = {
            dialog = null
            onCreated(it)
        })
        ProjectStart.CLONE -> CloneDialog(operations, open, onDismiss = { dialog = null }, onStarted = {
            dialog = null
            onStarted()
        })
        else -> Unit
    }
    return remember(operations, open) {
        ProjectStarters { start ->
            when (start) {
                ProjectStart.EMPTY, ProjectStart.CLONE -> dialog = start
                ProjectStart.FOLDER -> runCatching { folderPicker.launch(null) }
                ProjectStart.ZIP -> runCatching { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
            }
        }
    }
}

@Composable
private fun NewEmptyProjectDialog(operations: ProjectOperations, open: Boolean, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<ProjectList.NameProblem?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DsDialog(
        title = stringResource(R.string.projects_new_empty), onDismiss = onDismiss,
        body = {
            TextField(
                value = name,
                onValueChange = {
                    name = it
                    problem = null
                    failed = false
                },
                modifier = Modifier.fillMaxWidth().inkFieldFrame().focusRequester(focus),
                singleLine = true,
                label = { Text(stringResource(R.string.projects_name_label), style = DsType.std14) },
                isError = problem != null,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                colors = dialogTextFieldColors(),
                shape = DsShapes.control,
            )
            problem?.let { Text(nameProblemText(it), style = DsType.small13, color = colors.error) }
            if (failed) Text(stringResource(R.string.projects_change_failed), style = DsType.small13, color = colors.error)
        },
        actions = {
            DialogButtons(
                confirm = stringResource(R.string.projects_create),
                enabled = name.isNotBlank() && !busy,
                onDismiss = onDismiss,
                onConfirm = {
                    busy = true
                    scope.launch {
                        when (val r = operations.createEmpty(name, open)) {
                            is ProjectOperations.CreateResult.Created -> {
                                haptics.confirm()
                                onCreated(r.project.name)
                            }
                            is ProjectOperations.CreateResult.Problem -> {
                                haptics.reject()
                                problem = r.problem
                            }
                            is ProjectOperations.CreateResult.Failed -> {
                                haptics.reject()
                                failed = true
                            }
                        }
                        busy = false
                    }
                },
            )
        },
    )
}

@Composable
private fun CloneDialog(operations: ProjectOperations, open: Boolean, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    var url by rememberSaveable { mutableStateOf("") }
    var branch by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var private by rememberSaveable { mutableStateOf(false) }
    // The token is never saved state: not in the bundle, not in a view model, only here while the dialog is up.
    var token by remember { mutableStateOf("") }
    var tokenShown by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val check = GitClone.checkUrl(url, withToken = private && token.isNotEmpty())
    val branchOk = GitClone.branchOk(branch)
    LaunchedEffect(check) {
        if (!nameEdited && check is GitClone.UrlCheck.Ok) name = check.suggestedName
    }
    DsDialog(
        title = stringResource(R.string.projects_clone), onDismiss = onDismiss,
        body = {
            if (private) SecureContent()
            TextField(
                value = url,
                onValueChange = {
                    url = it
                    refused = false
                },
                modifier = Modifier.fillMaxWidth().inkFieldFrame().focusRequester(focus),
                singleLine = true,
                label = { Text(stringResource(R.string.projects_clone_url), style = DsType.std14) },
                placeholder = { Text("https://github.com/owner/repo", style = DsType.mono12) },
                isError = tried && check is GitClone.UrlCheck.Bad,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                textStyle = DsType.mono13.copy(color = colors.labelPrimary),
                colors = dialogTextFieldColors(),
                shape = DsShapes.control,
            )
            val bad = check as? GitClone.UrlCheck.Bad
            if (bad != null && (tried || bad.problem == GitClone.UrlProblem.SSH_NOT_SUPPORTED || bad.problem == GitClone.UrlProblem.CREDENTIALS_IN_URL) && bad.problem != GitClone.UrlProblem.EMPTY) {
                Text(urlProblemText(bad.problem), style = DsType.small13, color = colors.error)
            }
            TextField(
                value = branch,
                onValueChange = { branch = it },
                modifier = Modifier.fillMaxWidth().inkFieldFrame(),
                singleLine = true,
                label = { Text(stringResource(R.string.projects_clone_branch), style = DsType.std14) },
                isError = !branchOk,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                textStyle = DsType.mono13.copy(color = colors.labelPrimary),
                colors = dialogTextFieldColors(),
                shape = DsShapes.control,
            )
            if (!branchOk) Text(stringResource(R.string.projects_clone_bad_branch), style = DsType.small13, color = colors.error)
            TextField(
                value = name,
                onValueChange = {
                    name = it
                    nameEdited = true
                },
                modifier = Modifier.fillMaxWidth().inkFieldFrame(),
                singleLine = true,
                label = { Text(stringResource(R.string.projects_name_label), style = DsType.std14) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                colors = dialogTextFieldColors(),
                shape = DsShapes.control,
            )
            ToggleRow(stringResource(R.string.projects_clone_private), private, stringResource(R.string.projects_clone_private_hint)) {
                private = !private
                if (!private) token = ""
            }
            if (private) {
                val toolbar = LocalTextToolbar.current
                val safeToolbar = remember(toolbar) { NoCopyTextToolbar(toolbar) }
                CompositionLocalProvider(LocalTextToolbar provides safeToolbar) {
                    TextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth().inkFieldFrame(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.projects_clone_token), style = DsType.std14) },
                        visualTransformation = if (tokenShown) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        trailingIcon = {
                            DsIconButton(
                                icon = if (tokenShown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(if (tokenShown) R.string.models_key_hide else R.string.models_key_show),
                                onClick = {
                                    haptics.tap()
                                    tokenShown = !tokenShown
                                },
                                tint = colors.labelTertiary,
                            )
                        },
                        textStyle = DsType.mono13.copy(color = colors.labelPrimary),
                        colors = dialogTextFieldColors(),
                        shape = DsShapes.control,
                    )
                }
                Text(stringResource(R.string.projects_clone_token_hint), style = DsType.small13, color = colors.labelSecondary)
            }
            if (refused) Text(stringResource(R.string.projects_in_use), style = DsType.small13, color = colors.error)
        },
        actions = {
            DialogButtons(
                confirm = stringResource(R.string.projects_clone_start),
                enabled = url.isNotBlank() && branchOk && name.isNotBlank(),
                onDismiss = onDismiss,
                onConfirm = {
                    tried = true
                    val ok = check as? GitClone.UrlCheck.Ok
                    if (ok == null) {
                        haptics.reject()
                        return@DialogButtons
                    }
                    val secret = if (private && token.isNotEmpty()) token.toCharArray() else null
                    if (operations.clone(ok, branch, name, secret, open)) {
                        token = ""
                        haptics.confirm()
                        onStarted()
                    } else {
                        secret?.fill('\u0000')
                        haptics.reject()
                        refused = true
                    }
                },
            )
        },
    )
}

/** "Share as .zip" and "Save a copy to the phone": which folders go along, then the zip or the picker. */
@Composable
fun ExportDialog(operations: ProjectOperations, project: String, saveCopy: Boolean, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    var skipHeavy by rememberSaveable { mutableStateOf(true) }
    var includeGit by rememberSaveable { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    val filter = ExportFilter(skipHeavy = skipHeavy, includeGit = includeGit)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (operations.saveCopy(project, filter, uri)) onStarted() else refused = true
    }
    DsDialog(
        title = stringResource(if (saveCopy) R.string.projects_save_title else R.string.projects_share_title, project),
        onDismiss = onDismiss,
        body = {
            ToggleRow(stringResource(R.string.projects_export_skip), skipHeavy, stringResource(R.string.projects_export_skip_hint)) { skipHeavy = !skipHeavy }
            ToggleRow(stringResource(R.string.projects_export_git), includeGit, stringResource(R.string.projects_export_git_hint)) { includeGit = !includeGit }
            Text(
                stringResource(if (saveCopy) R.string.projects_save_note else R.string.projects_export_note),
                style = DsType.small13,
                color = colors.labelSecondary,
            )
            if (refused) Text(stringResource(R.string.projects_in_use), style = DsType.small13, color = colors.error)
        },
        actions = {
            DialogButtons(
                confirm = stringResource(if (saveCopy) R.string.projects_save_choose else R.string.projects_export_make),
                enabled = true,
                onDismiss = onDismiss,
                onConfirm = {
                    haptics.tap()
                    if (saveCopy) {
                        runCatching { picker.launch(null) }
                    } else if (operations.exportZip(project, filter)) {
                        onStarted()
                    } else {
                        refused = true
                    }
                },
            )
        },
    )
}

/** Progress and result of the one project operation, with Cancel, Share and Open. */
@Composable
fun ProjectJobDialog(operations: ProjectOperations, job: ProjectJob, onHide: () -> Unit, onOpened: () -> Unit = {}) {
    val context = LocalContext.current
    val haptics = rememberDsHaptics()
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    var shared by rememberSaveable(job) { mutableStateOf(false) }
    LaunchedEffect(job) {
        when (job) {
            is ProjectJob.Done -> haptics.confirm()
            is ProjectJob.Failed -> haptics.reject()
            else -> Unit
        }
    }
    DsDialog(
        title = jobTitle(job),
        onDismiss = {
            if (job !is ProjectJob.Running) operations.dismiss()
            onHide()
        },
        body = { ProjectJobBody(job) },
        actions = {
            when (job) {
                is ProjectJob.Running -> {
                    DsButton(stringResource(R.string.projects_job_hide), onClick = onHide, variant = DsButtonVariant.Ghost)
                    DsButton(stringResource(R.string.common_cancel), onClick = {
                        haptics.tap()
                        operations.cancel()
                    }, variant = DsButtonVariant.Outline)
                }
                is ProjectJob.Done -> {
                    DsButton(stringResource(R.string.common_done), onClick = {
                        operations.dismiss()
                        onHide()
                    }, variant = DsButtonVariant.Ghost)
                    if (job.kind == JobKind.EXPORT_ZIP && job.shareUri != null) {
                        DsButton(stringResource(R.string.projects_job_share), onClick = {
                            shared = true
                            shareZip(context, job.shareUri, job.name)
                        }, variant = DsButtonVariant.Info)
                    } else if (job.kind in CREATING && operations.onBuiltIn()) {
                        DsButton(stringResource(R.string.projects_action_session), onClick = {
                            scope.launch {
                                operations.openSession(WorkspaceTree.GUEST_ROOT + "/" + job.name)
                                operations.dismiss()
                                onOpened()
                            }
                        }, variant = DsButtonVariant.Info)
                    }
                }
                else -> DsButton(stringResource(R.string.common_ok), onClick = {
                    operations.dismiss()
                    onHide()
                }, variant = DsButtonVariant.Ghost)
            }
        },
    )
    // The zip is what the person asked for: offer the share sheet at once, once.
    LaunchedEffect(job) {
        if (job is ProjectJob.Done && job.kind == JobKind.EXPORT_ZIP && job.shareUri != null && !shared) {
            shared = true
            shareZip(context, job.shareUri, job.name)
        }
    }
}

@Composable
internal fun ProjectJobBody(job: ProjectJob) {
    val colors = DsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        when (job) {
            is ProjectJob.Running -> {
                RuntimeProgressBar(job.fraction, Modifier.fillMaxWidth())
                val detail = job.clonePhase?.let { clonePhaseText(it) }
                    ?: job.progress?.takeIf { it.bytesTotal > 0 }?.let { stringResource(R.string.projects_job_bytes, isolated(formatBytes(it.bytes)), isolated(formatBytes(it.bytesTotal))) }
                    ?: stringResource(R.string.projects_job_preparing)
                Text(detail, style = DsType.small13, color = colors.labelSecondary)
            }
            is ProjectJob.Done -> {
                Text(doneText(job), style = DsType.std14, color = colors.labelPrimary)
                if (job.skippedLinks > 0) {
                    Text(stringResource(R.string.projects_job_skipped_links, job.skippedLinks), style = DsType.small13, color = colors.labelSecondary)
                }
            }
            is ProjectJob.Failed -> {
                Text(failureText(job.failure), style = DsType.std14, color = colors.error)
                (job.failure as? JobFailure.Clone)?.lastLine?.let {
                    Text(stringResource(R.string.projects_fail_git_said, it), style = DsType.mono12, color = colors.labelTertiary, maxLines = 4)
                }
            }
            is ProjectJob.Cancelled -> Text(stringResource(R.string.projects_job_cancelled), style = DsType.std14, color = colors.labelSecondary)
        }
    }
}

private val CREATING = setOf(JobKind.CLONE, JobKind.IMPORT_FOLDER, JobKind.IMPORT_ZIP)

@Composable
internal fun jobTitle(job: ProjectJob): String = when (job.kind) {
    JobKind.CLONE -> stringResource(R.string.projects_job_clone, job.name)
    JobKind.IMPORT_FOLDER -> stringResource(R.string.projects_job_import_folder, job.name)
    JobKind.IMPORT_ZIP -> stringResource(R.string.projects_job_import_zip, job.name)
    JobKind.EXPORT_ZIP -> stringResource(R.string.projects_job_export, job.name)
    JobKind.SAVE_COPY -> stringResource(R.string.projects_job_save, job.name)
}

@Composable
private fun doneText(job: ProjectJob.Done): String = when (job.kind) {
    JobKind.CLONE -> stringResource(R.string.projects_done_clone, job.name)
    JobKind.IMPORT_FOLDER -> stringResource(R.string.projects_done_import_folder, job.name)
    JobKind.IMPORT_ZIP -> stringResource(R.string.projects_done_import_zip, job.name)
    JobKind.EXPORT_ZIP -> stringResource(R.string.projects_done_export, job.name)
    JobKind.SAVE_COPY -> stringResource(R.string.projects_done_save, job.name)
}

@Composable
private fun clonePhaseText(phase: GitClone.Phase): String = stringResource(
    when (phase) {
        GitClone.Phase.STARTING -> R.string.projects_phase_connecting
        GitClone.Phase.COUNTING, GitClone.Phase.COMPRESSING -> R.string.projects_phase_counting
        GitClone.Phase.RECEIVING -> R.string.projects_phase_receiving
        GitClone.Phase.RESOLVING -> R.string.projects_phase_resolving
        GitClone.Phase.CHECKING_OUT -> R.string.projects_phase_writing
    },
)

@Composable
internal fun failureText(failure: JobFailure): String = when (failure) {
    is JobFailure.Transfer -> stringResource(
        when (failure.problem) {
            TransferProblem.NOT_A_ZIP -> R.string.projects_fail_not_zip
            TransferProblem.UNSAFE_ZIP -> R.string.projects_fail_unsafe_zip
            TransferProblem.UNSUPPORTED_ZIP -> R.string.projects_fail_unsupported_zip
            TransferProblem.DAMAGED_ZIP -> R.string.projects_fail_damaged_zip
            TransferProblem.TOO_MANY_ENTRIES -> R.string.projects_fail_too_many
            TransferProblem.NO_SPACE -> R.string.projects_fail_no_space
            TransferProblem.NOT_READABLE -> R.string.projects_fail_not_readable
            TransferProblem.NOT_WRITABLE -> R.string.projects_fail_not_writable
        },
    )
    is JobFailure.Clone -> stringResource(
        when (failure.failure) {
            GitClone.Failure.NOT_FOUND_OR_PRIVATE -> R.string.projects_fail_clone_not_found
            GitClone.Failure.AUTH_FAILED -> R.string.projects_fail_clone_auth
            GitClone.Failure.NETWORK -> R.string.projects_fail_clone_network
            GitClone.Failure.BRANCH_NOT_FOUND -> R.string.projects_fail_clone_branch
            GitClone.Failure.NO_SPACE -> R.string.projects_fail_no_space
            GitClone.Failure.NOT_A_REPOSITORY -> R.string.projects_fail_clone_not_repo
            GitClone.Failure.TIMED_OUT -> R.string.projects_fail_clone_timeout
            GitClone.Failure.OTHER -> R.string.projects_fail_clone_other
        },
    )
    JobFailure.RuntimeBusy -> stringResource(R.string.projects_fail_runtime_busy)
    is JobFailure.Other -> stringResource(R.string.projects_fail_other)
}

@Composable
internal fun nameProblemText(problem: ProjectList.NameProblem): String = stringResource(
    when (problem) {
        ProjectList.NameProblem.INVALID -> R.string.projects_name_invalid
        ProjectList.NameProblem.TAKEN -> R.string.projects_name_taken
    },
)

@Composable
private fun urlProblemText(problem: GitClone.UrlProblem): String = stringResource(
    when (problem) {
        GitClone.UrlProblem.SSH_NOT_SUPPORTED -> R.string.projects_clone_ssh
        GitClone.UrlProblem.CREDENTIALS_IN_URL -> R.string.projects_clone_credentials
        GitClone.UrlProblem.TOKEN_NEEDS_HTTPS -> R.string.projects_clone_token_https
        GitClone.UrlProblem.EMPTY, GitClone.UrlProblem.NOT_A_URL, GitClone.UrlProblem.UNSUPPORTED_SCHEME -> R.string.projects_clone_bad_url
    },
)

@Composable
internal fun DialogButtons(confirm: String, enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit, danger: Boolean = false) {
    // A FlowRow, so two long labels at 320 dp with large text go on two lines instead of squeezing.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small, androidx.compose.ui.Alignment.End),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        DsButton(text = stringResource(R.string.common_cancel), onClick = onDismiss, variant = DsButtonVariant.Ghost)
        DsButton(text = confirm, onClick = onConfirm, variant = if (danger) DsButtonVariant.Danger else DsButtonVariant.Info, enabled = enabled)
    }
}

/** The system share sheet for a project's zip, with read access to that one file. */
fun shareZip(context: Context, uri: Uri, name: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TITLE, "$name.zip")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(send, context.getString(R.string.projects_share_chooser, name))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(chooser) }
}

/**
 * Show [documentId] (a project, or the root) in the system Files app. Newer document UIs open a
 * folder from a VIEW of it; older ones only a root. False when no app takes either.
 */
fun openInFiles(context: Context, documentId: String = WorkspaceTree.ROOT_ID): Boolean {
    val authority = WorkspacesProvider.authority(context)
    val attempts = listOf(
        Intent(Intent.ACTION_VIEW).setDataAndType(DocumentsContract.buildDocumentUri(authority, documentId), DocumentsContract.Document.MIME_TYPE_DIR),
        Intent(Intent.ACTION_VIEW).setDataAndType(DocumentsContract.buildRootUri(authority, WorkspaceTree.ROOT_ID), DocumentsContract.Root.MIME_TYPE_ITEM),
    )
    for (intent in attempts) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return true
        } catch (e: ActivityNotFoundException) {
            continue
        } catch (e: SecurityException) {
            continue
        }
    }
    return false
}

/**
 * [text] as a left-to-right island (U+2066 ... U+2069): a size such as `29.9 KB` or the app's
 * Latin name inside an Arabic or Urdu sentence keeps its own order, and a full stop after it stays
 * at the end of the sentence.
 */
internal fun isolated(text: String): String = "\u2066" + text + "\u2069"
