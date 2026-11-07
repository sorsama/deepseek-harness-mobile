package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.WorkspaceDirectoryEntry
import com.labteto.dshmobile.core.wire.dto.WorkspaceDirectoryListing
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsSearchField
import com.labteto.dshmobile.ui.components.DsSegment
import com.labteto.dshmobile.ui.components.DsSegmented
import com.labteto.dshmobile.ui.components.DsFullScreenDialog
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.clickableWithHold
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.skeleton
import com.labteto.dshmobile.ui.screens.main.preview.DocumentPreview
import com.labteto.dshmobile.ui.screens.main.preview.formatBytes
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun <T> RpcResult<T>.requireValue(): T = when (this) {
    is RpcResult.Ok -> value
    is RpcResult.Err -> throw IllegalStateException("${error.code}: ${error.message}")
}

/**
 * The workspace, full screen: an Ink top bar (back, title, working directory, search) above the
 * Files / Preview / Terminal tabs.
 *
 * The window is a [DsFullScreenDialog] because the chat surface underneath is a separate
 * composition with its own overlays; keeping it a window means every sheet and dialog the panels
 * open still lands above the right layer. The content fades and rises in on mount so the panel
 * arrives instead of snapping into place. The window pads for the system bars itself, which is what
 * keeps the terminal key row above the gesture bar.
 */
@Composable
internal fun WorkspacePanels(
    store: SessionStore,
    state: PanelState,
    onDismiss: () -> Unit,
    /** Mentions a held file in the composer; null for a workspace with no composer to mention into. */
    onMention: ((String, Boolean) -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val cwd = sessions.firstOrNull { it.sessionId == state.key.sessionId }?.cwd
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    // Search belongs to the Files tab: leaving it closes the search rather than carrying a hidden
    // filter into the next visit, which would greet the reader with a list that looks truncated.
    LaunchedEffect(state.section) {
        if (state.section != 0 && searchOpen) {
            searchOpen = false
            query = ""
        }
    }
    // Back — the system key and the top bar's arrow alike — closes an open search and forgets its
    // query before it leaves the panel. Wired through the dialog's own `onDismissRequest` rather
    // than a `BackHandler`: this panel is a separate window, the system sends it the back key, and a
    // `BackHandler` composed inside it registers with the activity's dispatcher, which that key
    // never reaches while the window is up.
    val back = {
        if (searchOpen) {
            searchOpen = false
            query = ""
        } else {
            onDismiss()
        }
    }
    DsFullScreenDialog(onDismissRequest = back) {
        WorkspaceTopBar(
            title = stringResource(R.string.panel_workspace),
            subtitle = cwd,
            onBack = back,
            searchVisible = state.section == 0,
            searchOpen = searchOpen,
            onSearch = {
                searchOpen = !searchOpen
                if (!searchOpen) query = ""
            },
        )
        AnimatedVisibility(
            visible = shown,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            enter = fadeIn(tween(180, easing = DsAnimations.inkDecelerate)) +
                slideInVertically(tween(260, easing = DsAnimations.inkDecelerate)) { it / 28 },
            exit = fadeOut(tween(120)),
        ) {
            WorkspacePanelBody(
                store = store,
                state = state,
                modifier = Modifier.fillMaxSize(),
                onMention = onMention,
                searchOpen = searchOpen,
                query = query,
                onQuery = { query = it },
            )
        }
    }
}

/** Back, title, working directory and the Files search trigger. */
@Composable
private fun WorkspaceTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    searchVisible: Boolean,
    searchOpen: Boolean,
    onSearch: () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = DsSpacing.small, end = DsSpacing.small, top = DsSpacing.xsmall, bottom = DsSpacing.xsmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DsIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.common_back),
            onClick = onBack,
        )
        Column(Modifier.weight(1f).padding(horizontal = DsSpacing.xsmall)) {
            Text(
                title,
                style = DsType.large20,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    technicalDisplay(it),
                    style = DsType.mono12,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (searchVisible) {
            DsIconButton(
                icon = if (searchOpen) Icons.Filled.Close else FeatherIcons.Search,
                contentDescription = stringResource(if (searchOpen) R.string.common_close else R.string.ink_files_search_files),
                onClick = onSearch,
                tint = if (searchOpen) colors.accent else colors.labelSecondary,
            )
        }
    }
}

/**
 * The workspace browser itself — Files / Preview / Terminal — without the screen's header, which
 * [WorkspacePanels] draws around it.
 */
@Composable
internal fun WorkspacePanelBody(
    store: SessionStore,
    state: PanelState,
    modifier: Modifier = Modifier,
    onMention: ((String, Boolean) -> Unit)? = null,
    searchOpen: Boolean = false,
    query: String = "",
    onQuery: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val key = state.key
    val colors = DsTheme.colors
    var heldEntry by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val activeDocument = state.previews.getOrNull(state.selectedPreview)?.path
    // The chat already knows which files this session's turns touched; showing them in the
    // workspace is presentation only, and only when the panel belongs to the live session.
    val conversation by store.currentConversation.collectAsStateWithLifecycle()
    val currentSessionId by store.currentSessionId.collectAsStateWithLifecycle()
    val changedFiles = remember(conversation?.nodes, currentSessionId, key.sessionId) {
        if (key.sessionId == currentSessionId) sessionChangedFiles(conversation?.nodes.orEmpty()).distinct() else emptyList()
    }
    fun listDirectory(path: String) {
        if (state.busy) return
        // `workspaceFiles/list` refuses an empty `path` and answers the root as "" (harness
        // `workspacePathOf`: "Empty for the root"), so normalize before the request: a browse
        // target that is empty or the host's root spelling is ".", never "".
        val directory = workspacePath(path)
        state.busy = true; state.error = null
        scope.launch {
            try {
                val api = store.apiForHost(key.host) ?: error(context.getString(R.string.common_offline))
                when (val result = api.workspaceFileList(key.sessionId, directory)) {
                    is RpcResult.Ok -> {
                        state.listing = result.value
                        // Adopt the host's canonical workspace-relative directory. Empty is the
                        // workspace root, which Files spells "."; taking the answer rather than the
                        // request also keeps a browse that started from an absolute path relative
                        // afterwards, so the toolbar and entry joins stay workspace-scoped.
                        state.directory = workspacePath(result.value.path)
                    }
                    // A reference guessed to be a folder (see isFolderReference) that names a
                    // file: show the file, and leave Files on the folder that holds it.
                    is RpcResult.Err -> if (result.error.code == "workspace-file/not-directory") {
                        state.directory = workspaceParentPath(directory)
                        state.listing = null
                        state.open(directory)
                    } else result.requireValue()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.error = e.message }
            finally { state.busy = false }
        }
    }
    // Keyed on the directory and on a missing listing too: `browse()` clears the listing and may
    // set a new directory, and either must fetch even though the session is unchanged.
    LaunchedEffect(key, state.directory, state.listing == null) { if (state.listing == null) listDirectory(state.directory) }
    // Invalidate previews when the host reports a file observation. OS-only changes are checked
    // by stat each time a tab opens and by the explicit Refresh action.
    //
    // Harness 0.1.7 watches one named target per stream rather than the whole workspace, so each
    // open preview gets a watch of its own; the set follows the tabs. A 0.1.6 host refuses the
    // `path` argument and is watched workspace-wide instead, once.
    val mux = store.muxForHost(key.host)
    val watched = state.previews.map { it.path }.distinct()
    var workspaceWide by remember(key, mux) { mutableStateOf(false) }
    fun invalidate(raw: kotlinx.serialization.json.JsonElement) {
        val frame = com.labteto.dshmobile.core.wire.decodeFromJsonElement(com.labteto.dshmobile.core.wire.dto.WorkspaceFileWatchFrame.serializer(), raw)
        frame.change?.let { change ->
            state.previews.filter { it.stat?.absolutePath == change.absolutePath }.forEach {
                if (change.absent || it.stat?.version != change.version) { it.stat = null; it.bytes = null; it.text = null }
            }
        }
    }
    if (mux != null && !workspaceWide) {
        watched.forEach { path -> key(path) {
            LaunchedEffect(key, mux, path) {
                try {
                    mux.openStream("workspaceFiles/changes", kotlinx.serialization.json.buildJsonObject {
                        put("workspaceFileScopeId", kotlinx.serialization.json.JsonPrimitive(key.sessionId))
                        put("path", kotlinx.serialization.json.JsonPrimitive(path))
                    }).collect { invalidate(it) }
                } catch (e: CancellationException) { throw e }
                catch (e: com.labteto.dshmobile.core.wire.RemoteStreamException) {
                    if (e.error.code == "gateway/arguments-invalid") workspaceWide = true
                }
                catch (_: Exception) { /* File reads remain available without the optional observation feed. */ }
            }
        } }
    }
    if (mux != null && workspaceWide) {
        LaunchedEffect(key, mux) {
            try {
                mux.openStream("workspaceFiles/changes", kotlinx.serialization.json.buildJsonObject {
                    put("workspaceFileScopeId", kotlinx.serialization.json.JsonPrimitive(key.sessionId))
                }).collect { invalidate(it) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* As above: the feed is an optimisation, not a dependency. */ }
        }
    }
    // A link in a previewed document is relative to that document, folders as much as files.
    fun resolve(path: String) = activeDocument?.let { com.labteto.dshmobile.core.session.resolvePreviewReference(it, path) } ?: path
    CompositionLocalProvider(
        com.labteto.dshmobile.ui.components.LocalFileOpener provides { path: String -> state.open(resolve(path)) },
        com.labteto.dshmobile.ui.components.LocalFolderOpener provides { path: String -> state.browse(resolve(path)) },
    ) {
        Column(
            modifier.fillMaxWidth().padding(horizontal = DsSpacing.comfortable),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            DsSegmented(
                segments = listOf(
                    DsSegment("0", stringResource(R.string.panel_files)),
                    DsSegment("1", stringResource(R.string.panel_preview) + state.previews.size.takeIf { it > 0 }?.let { " · $it" }.orEmpty()),
                    DsSegment("2", stringResource(R.string.panel_terminal)),
                ),
                selectedKey = state.section.toString(),
                onSelect = {
                    state.section = it.toInt()
                },
                role = Role.Tab,
                stretch = true,
                minSegmentHeight = 36.dp,
                modifier = Modifier.fillMaxWidth().padding(top = DsSpacing.xsmall),
            )
            when (state.section) {
                0 -> FilesSection(
                    modifier = Modifier.weight(1f),
                    state = state,
                    searchOpen = searchOpen,
                    query = query,
                    onQuery = onQuery,
                    onRoot = { listDirectory(".") },
                    onParent = {
                        listDirectory(workspaceParentPath(state.directory))
                    },
                    onRefresh = { listDirectory(state.directory) },
                    onOpenDirectory = { if (!state.busy) listDirectory(it) },
                    onOpenFile = { state.open(it) },
                    onHold = { path, folder -> heldEntry = path to folder },
                    changedFiles = changedFiles,
                    onOpenChanged = { state.open(it) },
                )
                1 -> PreviewSection(store, key, state, Modifier.weight(1f))
                2 -> TerminalPanel(store, state, Modifier.weight(1f))
            }
        }
    }
    heldEntry?.let { (path, folder) ->
        FileHoldMenu(path = path, folder = folder, onMention = onMention, onDismiss = { heldEntry = null })
    }
}

// ---------------------------------------------------------------------------
// Files
// ---------------------------------------------------------------------------

@Composable
private fun FilesSection(
    modifier: Modifier = Modifier,
    state: PanelState,
    searchOpen: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onRoot: () -> Unit,
    onParent: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onHold: (String, Boolean) -> Unit,
    changedFiles: List<String> = emptyList(),
    onOpenChanged: (String) -> Unit = {},
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val busy = state.busy
    val entries = remember(state.listing, query) {
        state.listing?.entries.orEmpty()
            .sortedWith(compareBy({ it.type != "directory" }, { it.name.lowercase() }))
            .filter { matchesFileQuery(it.name, query) }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(FeatherIcons.Folder, null, Modifier.size(16.dp), tint = colors.accent)
            Spacer(Modifier.width(DsSpacing.xsmall))
            Text(
                technicalDisplay(if (state.directory == ".") "/" else state.directory),
                style = DsType.mono12,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DsIconButton(
                icon = FeatherIcons.Folder,
                contentDescription = stringResource(R.string.panel_root),
                onClick = onRoot,
                enabled = !busy && state.directory != ".",
                iconSize = 18.dp,
            )
            DsIconButton(
                icon = FeatherIcons.CornerLeftUp,
                contentDescription = stringResource(R.string.panel_parent),
                onClick = onParent,
                enabled = !busy && state.directory != ".",
                iconSize = 18.dp,
            )
            DsIconButton(
                icon = FeatherIcons.RefreshCw,
                contentDescription = stringResource(R.string.common_retry),
                onClick = onRefresh,
                enabled = !busy,
                iconSize = 18.dp,
            )
        }
        AnimatedVisibility(
            visible = searchOpen,
            enter = fadeIn(tween(160)) + androidx.compose.animation.expandVertically(),
            exit = fadeOut(tween(120)) + androidx.compose.animation.shrinkVertically(),
        ) {
            // Composed only while the toggle has it open, so `autoFocus` is "the toggle was just
            // tapped": the field takes focus and the keyboard comes up with it.
            DsSearchField(
                value = query,
                onValueChange = onQuery,
                placeholder = stringResource(R.string.ink_files_filter_files),
                autoFocus = true,
            )
        }
        if (busy && state.listing != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.borderL1)
        }
        state.error?.let { message ->
            ErrorNotice(message = message, onRetry = onRefresh)
        }
        if (state.listing?.truncated == true) {
            Text(stringResource(R.string.panel_truncated), style = DsType.caption11, color = colors.warnLabel)
        }
        // The filter reaches the card too: a query that hides every file below but leaves the card
        // full of unrelated paths reads as a search that did nothing. The card lists whole paths,
        // so it matches on the path; the folder listing matches on the entry name.
        val shownChanged = remember(changedFiles, query) { filterChangedFiles(changedFiles, query) }
        if (shownChanged.isNotEmpty()) {
            DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.ink_files_changed_session),
                        style = DsType.label12,
                        color = colors.labelTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        shownChanged.size.toString(),
                        style = DsType.mono12,
                        color = colors.labelCaption,
                    )
                }
                shownChanged.take(5).forEach { path ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 36.dp)
                            .clip(DsShapes.row)
                            .clickable {
                                haptics.tap()
                                onOpenChanged(path)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        Icon(FeatherIcons.FileText, null, Modifier.size(15.dp), tint = colors.warnLabel)
                        Text(
                            technicalDisplay(path),
                            style = DsType.mono12,
                            color = colors.labelPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (shownChanged.size > 5) {
                    Text(
                        "+${shownChanged.size - 5}",
                        style = DsType.caption11,
                        color = colors.labelCaption,
                        modifier = Modifier.padding(start = DsSpacing.tiny),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.ink_files_all_files),
            style = DsType.label12,
            color = colors.labelTertiary,
            modifier = Modifier.padding(start = DsSpacing.tiny, top = DsSpacing.tiny),
        )
        val listing = state.listing
        when {
            busy && listing == null -> SkeletonList()
            listing != null && entries.isEmpty() -> EmptyPane(
                icon = if (query.isNotBlank()) FeatherIcons.Search else FeatherIcons.Folder,
                title = stringResource(if (query.isNotBlank()) R.string.ink_files_no_matches else R.string.panel_empty_folder),
            )
            else -> AnimatedContent(
                targetState = listing,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    (fadeIn(tween(200, easing = DsAnimations.inkDecelerate)) +
                        slideInVertically(tween(240, easing = DsAnimations.inkDecelerate)) { it / 10 })
                        .togetherWith(fadeOut(tween(120)))
                },
                label = "directory",
            ) { target ->
                val rows = (target?.entries.orEmpty())
                    .sortedWith(compareBy({ it.type != "directory" }, { it.name.lowercase() }))
                    .filter { matchesFileQuery(it.name, query) }
                // The listing's `path` is the host's canonical workspace path, and it is the EMPTY
                // STRING for the workspace root (`workspacePathOf`: "Empty for the root"). It has to
                // be normalized before an entry is joined onto it: joining the raw root path built
                // "/name", which the host answers with
                // `workspace-file/not-found: no entry at "/name"`.
                val directory = target?.path?.let { workspacePath(it) } ?: state.directory
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = state.directoryScroll.getOrPut(directory) { androidx.compose.foundation.lazy.LazyListState() },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = DsSpacing.medium),
                ) {
                    itemsIndexed(rows, key = { _, entry -> entry.name }) { _, entry ->
                        val entryPath = workspaceChildPath(directory, entry.name)
                        FileRow(
                            entry = entry,
                            busy = busy,
                            onOpen = {
                                haptics.tap()
                                if (entry.type == "directory") onOpenDirectory(entryPath) else onOpenFile(entryPath)
                            },
                            onHold = {
                                onHold(entryPath, entry.type == "directory")
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    entry: WorkspaceDirectoryEntry,
    busy: Boolean,
    onOpen: () -> Unit,
    onHold: () -> Unit,
) {
    val colors = DsTheme.colors
    val directory = entry.type == "directory"
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(DsShapes.row)
            .clickableWithHold(
                holdLabel = stringResource(R.string.hold_file_actions),
                onHold = onHold,
                enabled = !busy,
                role = Role.Button,
                onClick = onOpen,
            )
            .padding(horizontal = DsSpacing.xsmall, vertical = DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        if (directory) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = colors.labelCaption,
            )
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Icon(
            if (directory) FeatherIcons.Folder else FeatherIcons.FileText,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (directory) colors.accent else colors.labelTertiary,
        )
        Text(
            technicalDisplay(entry.name),
            style = DsType.mono13,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!directory) {
            entry.size?.let {
                Text(
                    formatBytes(it),
                    style = DsType.mono12,
                    color = colors.labelCaption,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Six placeholder rows while a listing is still on the wire. */
@Composable
private fun SkeletonList() {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        repeat(6) { index ->
            Row(
                Modifier.fillMaxWidth().height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Box(Modifier.size(18.dp).skeleton(colors.bgLayer1, colors.borderL2, DsShapes.chip))
                Box(
                    Modifier
                        .fillMaxWidth(if (index % 3 == 0) 0.38f else if (index % 3 == 1) 0.55f else 0.46f)
                        .height(12.dp)
                        .skeleton(colors.bgLayer1, colors.borderL2, DsShapes.chip),
                )
            }
        }
    }
}

@Composable
private fun EmptyPane(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    val colors = DsTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = DsSpacing.xxlarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = colors.labelDimmed)
        Text(title, style = DsType.small13, color = colors.labelTertiary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ErrorNotice(message: String, onRetry: () -> Unit) {
    val colors = DsTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(DsShapes.code)
            .background(colors.errorTertiary)
            .border(1.dp, colors.errorBorder, DsShapes.code)
            .padding(DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        Text(message, style = DsType.small13, color = colors.error, softWrap = true)
        DsButton(
            text = stringResource(R.string.common_retry),
            onClick = onRetry,
            variant = DsButtonVariant.Outline,
            size = DsButtonSize.Small,
        )
    }
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

@Composable
private fun PreviewSection(store: SessionStore, key: ComposerKey, state: PanelState, modifier: Modifier = Modifier) {
    if (state.previews.isEmpty()) {
        EmptyHero(
            headline = stringResource(R.string.panel_preview_empty),
            subtitle = stringResource(R.string.panel_preview_hint),
            showPreview = false,
        )
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        // Open files as pills; the live one is selected.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
            state.previews.forEachIndexed { i, preview ->
                DsPill(
                    text = technicalDisplay(preview.path.substringAfterLast('/').substringAfterLast('\\')),
                    selected = i == state.selectedPreview,
                    onClick = {
                        state.selectedPreview = i
                    },
                )
            }
        }
        val index = state.selectedPreview.coerceIn(0, state.previews.lastIndex)
        val preview = state.previews[index]
        key(preview) {
            DocumentPreview(
                store, key, preview, Modifier.weight(1f),
                onClose = {
                    state.previews.removeAt(index)
                    state.selectedPreview = (index - 1).coerceAtLeast(0)
                },
                onDirectory = {
                    state.previews.remove(preview)
                    state.selectedPreview = (index - 1).coerceAtLeast(0)
                    state.browse(preview.path)
                },
            )
        }
    }
}
