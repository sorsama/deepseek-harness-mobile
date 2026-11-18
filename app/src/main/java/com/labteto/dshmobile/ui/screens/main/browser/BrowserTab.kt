package com.labteto.dshmobile.ui.screens.main.browser

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.core.browser.AddressInput
import com.labteto.dshmobile.core.browser.AddressRefusal
import com.labteto.dshmobile.core.browser.AddressScope
import com.labteto.dshmobile.core.browser.BrowserConnection
import com.labteto.dshmobile.core.browser.BrowserReach
import com.labteto.dshmobile.core.browser.browserConnectionOf
import com.labteto.dshmobile.core.browser.browserReach
import com.labteto.dshmobile.core.browser.mentionedLocalAddresses
import com.labteto.dshmobile.core.browser.parseBrowserAddress
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsIconMenu
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.screens.main.ComposerKey
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import java.net.IDN

/** `java.net.IDN` for [parseBrowserAddress]: a Unicode host in the ASCII form the web view connects to. */
internal fun idnToAscii(host: String): String? = try {
    IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
} catch (_: IllegalArgumentException) {
    null
}

/** What the tab needs to know about the panel's harness to decide what an address reaches. */
internal data class BrowserEndpoint(val connection: BrowserConnection, val host: String?, val port: Int?, val secure: Boolean)

internal fun browserEndpointOf(active: HostConfig?, panelHost: String): BrowserEndpoint {
    val host = active?.takeIf { "${it.baseUrl}|${it.id}" == panelHost }
    return BrowserEndpoint(
        connection = browserConnectionOf(connected = host != null, isRelay = host?.isRelay == true, isLoopback = host?.isLoopback == true),
        host = host?.host,
        port = host?.port,
        secure = host?.useTls == true,
    )
}

/** The problem [raw] would be on [endpoint], or null when the tab may load it. */
internal fun browserProblemFor(raw: String, endpoint: BrowserEndpoint): BrowserProblem? =
    when (val input = parseBrowserAddress(raw, ::idnToAscii)) {
        is AddressInput.Refused -> BrowserProblem.Refused(input.reason, input.scheme)
        is AddressInput.Web -> when (val reach = browserReach(input.address, endpoint.connection, endpoint.host, endpoint.port, endpoint.secure)) {
            BrowserReach.Open -> null
            BrowserReach.HarnessItself -> BrowserProblem.HarnessItself(input.address.url)
            is BrowserReach.HarnessLoopback -> BrowserProblem.HarnessLoopback(
                input.address.url, reach.onHarnessAddress, relay = endpoint.connection == BrowserConnection.Relay,
            )
        }
    }

/**
 * The workspace Browser tab: back, forward, reload or stop, the page title and an overflow above the
 * address field, then the page.
 *
 * The page is an Android web view with the restrictions [BrowserViewHolder] describes. Which pages
 * it can reach depends on how the app reaches the harness ([BrowserConnection]); the tab says so on
 * its start page and explains a page it will not load rather than loading the wrong one.
 */
@Composable
internal fun BrowserSection(
    store: SessionStore,
    key: ComposerKey,
    state: BrowserTabState,
    holder: BrowserViewHolder,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val toast = rememberDsToast()
    val connectionState by store.connectionState.collectAsStateWithLifecycle()
    val endpoint = remember(connectionState.host, key.host) { browserEndpointOf(connectionState.host, key.host) }
    fun problemOf(raw: String): BrowserProblem? =
        browserProblemFor(raw, endpoint)?.takeUnless { it is BrowserProblem.HarnessLoopback && state.phoneLoopback }
    holder.gate = { problemOf(it) }

    fun open(raw: String) {
        if (raw.isBlank()) return
        val problem = problemOf(raw)
        if (problem == null) {
            val url = (parseBrowserAddress(raw, ::idnToAscii) as AddressInput.Web).address.url
            holder.load(url)
        } else {
            state.problem = problem
        }
    }
    fun openUnchecked(url: String) = holder.load(url)

    LaunchedEffect(state.pending) {
        state.pending?.let {
            state.pending = null
            open(it)
        }
    }

    val noApp = stringResource(R.string.browser_no_app)
    val cleared = stringResource(R.string.browser_data_cleared)
    val copied = stringResource(R.string.common_copied)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        BrowserToolbar(
            state = state,
            onBack = { holder.goBack() },
            onForward = { holder.goForward() },
            onReload = { holder.reload() },
            onStop = { holder.stop() },
            menu = state.url?.let { url ->
                listOf(
                    MenuItem(stringResource(R.string.browser_open_external), FeatherIcons.ExternalLink) {
                        if (!openInBrowser(context, url)) toast.second(noApp)
                    },
                    MenuItem(stringResource(R.string.browser_copy_link), Icons.Filled.ContentCopy) {
                        clipboard.setText(AnnotatedString(url))
                        toast.second(copied)
                    },
                    MenuItem(stringResource(R.string.browser_clear_data), Icons.Filled.DeleteOutline) {
                        holder.clearSiteData()
                        toast.second(cleared)
                    },
                )
            },
        )
        AddressField(url = state.url, onGo = { open(it) })
        if (state.loading) {
            val loadingLabel = stringResource(R.string.common_loading)
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(5, 100) / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp).semantics { contentDescription = loadingLabel },
                color = DsTheme.colors.accent,
                trackColor = DsTheme.colors.borderL1,
            )
        }
        state.notice?.let { notice ->
            NoticeRow(
                text = stringResource(if (notice == BrowserNotice.Download) R.string.browser_download_notice else R.string.browser_file_notice),
                onOpen = state.url?.let { url -> { if (!openInBrowser(context, url)) toast.second(noApp) } },
                onDismiss = { state.notice = null },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val problem = state.problem
            when {
                problem != null -> ProblemPage(
                    problem = problem,
                    onOpen = { openUnchecked(it) },
                    onOpenOnPhone = {
                        state.phoneLoopback = true
                        openUnchecked(it)
                    },
                    onReload = { holder.reload() },
                )
                state.live && holder.hasView -> PageView(holder)
                else -> StartPage(
                    store = store,
                    key = key,
                    connection = endpoint.connection,
                    lastUrl = state.url,
                    onOpen = { open(it) },
                )
            }
            DsToastHost(toast, belowStatusBar = false)
        }
    }

    state.otherApp?.let { uri ->
        DsDialog(
            title = stringResource(R.string.browser_other_app_title),
            onDismiss = { state.otherApp = null },
            body = {
                Text(stringResource(R.string.browser_other_app_body), style = DsType.std14, color = DsTheme.colors.labelSecondary)
                Spacer(Modifier.height(DsSpacing.small))
                LtrText(uri.take(500), DsType.mono12, DsTheme.colors.labelPrimary, maxLines = 6)
            },
            actions = {
                DsButton(stringResource(R.string.common_cancel), onClick = { state.otherApp = null }, variant = DsButtonVariant.Ghost)
                DsButton(stringResource(R.string.browser_other_app_open), onClick = {
                    state.otherApp = null
                    if (!openOutside(context, uri)) toast.second(noApp)
                })
            },
        )
    }
}

@Composable
private fun BrowserToolbar(
    state: BrowserTabState,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    menu: List<MenuItem>?,
) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DsIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.common_back),
            onClick = onBack,
            enabled = state.live && state.canGoBack,
        )
        DsIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = stringResource(R.string.browser_forward),
            onClick = onForward,
            enabled = state.live && state.canGoForward,
        )
        if (state.loading) {
            DsIconButton(icon = Icons.Filled.Close, contentDescription = stringResource(R.string.browser_stop), onClick = onStop)
        } else {
            DsIconButton(
                icon = FeatherIcons.RefreshCw,
                contentDescription = stringResource(R.string.browser_reload),
                onClick = onReload,
                enabled = state.url != null,
            )
        }
        Text(
            state.title ?: state.url?.let { hostOf(it) }.orEmpty(),
            style = DsType.small13Strong,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = DsSpacing.xsmall),
        )
        if (menu != null) {
            DsIconMenu(
                icon = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.ink_chat_more),
                items = menu,
                tint = colors.labelSecondary,
            )
        }
    }
}

/** The host of [url] for the toolbar while a page has no title yet. */
private fun hostOf(url: String): String = (parseBrowserAddress(url) as? AddressInput.Web)?.address?.host ?: url

/**
 * The address field: URL keyboard, Go action, and a lock or "not secure" mark that is an icon with a
 * spoken label and the scheme spelled out in the address, never colour alone. The address reads left
 * to right in every language.
 */
@Composable
private fun AddressField(url: String?, onGo: (String) -> Unit) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var value by remember { mutableStateOf(TextFieldValue(url.orEmpty())) }
    // Follow the page while the reader is not typing; never overwrite what they are typing.
    LaunchedEffect(url, focused) { if (!focused && value.text != url.orEmpty()) value = TextFieldValue(url.orEmpty()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val label = stringResource(R.string.browser_address)
    val address = remember(url) { url?.let { (parseBrowserAddress(it, ::idnToAscii) as? AddressInput.Web)?.address } }
    val secure = address?.secure == true
    // Plain HTTP to this phone's own loopback never leaves the device, so it is not marked.
    val marked = address != null && (secure || address.scope != AddressScope.Loopback)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BasicTextField(
            value = value,
            onValueChange = { value = it.copy(text = it.text.replace('\n', ' ').replace('\r', ' ')) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DsSpacing.touchTarget)
                .clip(DsShapes.control)
                .background(colors.bgLayer2)
                .border(1.dp, if (focused) colors.accent else colors.borderL2, DsShapes.control)
                .onFocusChanged { if (it.isFocused) value = value.copy(selection = TextRange(0, value.text.length)) }
                .semantics { contentDescription = label },
            textStyle = DsType.mono13.copy(color = colors.labelPrimary, textDirection = TextDirection.Ltr),
            cursorBrush = SolidColor(colors.accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = {
                keyboard?.hide()
                focusManager.clearFocus()
                onGo(value.text)
            }),
            interactionSource = interaction,
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget).padding(horizontal = DsSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (marked && !focused) {
                        Icon(
                            if (secure) Icons.Filled.Lock else Icons.Filled.Public,
                            contentDescription = stringResource(if (secure) R.string.browser_secure else R.string.browser_not_secure),
                            modifier = Modifier.size(16.dp),
                            tint = if (secure) colors.labelSecondary else colors.warnText,
                        )
                        Spacer(Modifier.width(DsSpacing.xsmall))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.text.isEmpty()) {
                            Text(label, style = DsType.mono13, color = colors.labelTertiary, maxLines = 1)
                        }
                        inner()
                    }
                }
            },
        )
    }
}

/** The page itself. The view is the panel's, so leaving the tab keeps its page, history and scroll. */
@Composable
private fun PageView(holder: BrowserViewHolder) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(holder, lifecycle) {
        holder.resume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> holder.pause()
                Lifecycle.Event.ON_RESUME -> holder.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            holder.pause()
        }
    }
    key(holder.generation) {
        AndroidView(
            factory = { holder.obtain().also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = Modifier.fillMaxSize(),
            onRelease = { (it.parent as? ViewGroup)?.removeView(it) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartPage(
    store: SessionStore,
    key: ComposerKey,
    connection: BrowserConnection,
    lastUrl: String?,
    onOpen: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val conversation by store.currentConversation.collectAsStateWithLifecycle()
    val currentSessionId by store.currentSessionId.collectAsStateWithLifecycle()
    val mentioned = remember(conversation?.nodes, currentSessionId, key.sessionId) {
        if (key.sessionId == currentSessionId) mentionedLocalAddresses(conversation?.nodes.orEmpty()) else emptyList()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Icon(FeatherIcons.Globe, contentDescription = null, modifier = Modifier.size(28.dp), tint = colors.accent)
        Text(stringResource(R.string.browser_start_title), style = DsType.title17, color = colors.labelPrimary)
        Text(stringResource(R.string.browser_start_body), style = DsType.std14, color = colors.labelSecondary)
        connectionNote(connection)?.let { Text(stringResource(it), style = DsType.std14, color = colors.labelSecondary) }
        if (lastUrl != null) {
            SectionLabel(stringResource(R.string.browser_last_page))
            AddressChoice(lastUrl, onClick = { onOpen(lastUrl) }, actionLabel = stringResource(R.string.browser_open_again))
        }
        if (mentioned.isNotEmpty()) {
            SectionLabel(stringResource(R.string.browser_from_chat))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                mentioned.forEach { url -> AddressChoice(url, onClick = { onOpen(url) }) }
            }
        }
    }
}

/** One or two plain sentences on what this connection can reach, or null when there is nothing to add. */
internal fun connectionNote(connection: BrowserConnection): Int? = when (connection) {
    BrowserConnection.ThisDevice -> R.string.browser_note_this_device
    BrowserConnection.LocalNetwork -> R.string.browser_note_lan
    BrowserConnection.Relay -> R.string.browser_note_relay
    BrowserConnection.Offline -> null
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = DsType.label12, color = DsTheme.colors.labelTertiary, modifier = Modifier.padding(top = DsSpacing.small))
}

/** An address the reader can open with one tap: a full 48dp button whose label is the address itself. */
@Composable
private fun AddressChoice(url: String, onClick: () -> Unit, actionLabel: String? = null) {
    val colors = DsTheme.colors
    val description = actionLabel?.let { "$it: $url" } ?: url
    Row(
        Modifier
            .heightIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.control)
            .background(colors.bgLayer2)
            .border(1.dp, colors.borderL2, DsShapes.control)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        Icon(FeatherIcons.Globe, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.labelSecondary)
        LtrText(url, DsType.mono12, colors.labelPrimary, maxLines = 2)
    }
}

/** Text that is an address or a link: always left to right, whatever the language. */
@Composable
private fun LtrText(text: String, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color, maxLines: Int) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(text, style = style.copy(textDirection = TextDirection.Ltr), color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NoticeRow(text: String, onOpen: (() -> Unit)?, onDismiss: () -> Unit) {
    val colors = DsTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(DsShapes.control)
            .background(colors.warnTertiary)
            .border(1.dp, colors.warnBorder, DsShapes.control)
            .padding(start = DsSpacing.medium, top = DsSpacing.small, end = DsSpacing.small, bottom = DsSpacing.tiny),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(FeatherIcons.Info, contentDescription = null, modifier = Modifier.padding(top = 3.dp).size(16.dp), tint = colors.warnText)
            Spacer(Modifier.width(DsSpacing.small))
            Text(text, style = DsType.small13, color = colors.labelPrimary, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (onOpen != null) DsButton(stringResource(R.string.browser_open_external), onClick = onOpen, variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
            DsButton(stringResource(R.string.browser_dismiss_notice), onClick = onDismiss, variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
        }
    }
}

@Composable
private fun ProblemPage(
    problem: BrowserProblem,
    onOpen: (String) -> Unit,
    onOpenOnPhone: (String) -> Unit,
    onReload: () -> Unit,
) {
    val colors = DsTheme.colors
    val title: String
    val body: String
    var url: String? = null
    when (problem) {
        is BrowserProblem.HarnessLoopback -> {
            title = stringResource(R.string.browser_loopback_title)
            body = stringResource(if (problem.relay) R.string.browser_loopback_relay else R.string.browser_loopback_lan)
            url = problem.url
        }
        is BrowserProblem.HarnessItself -> {
            title = stringResource(R.string.browser_harness_title)
            body = stringResource(R.string.browser_harness_body)
            url = problem.url
        }
        is BrowserProblem.Refused -> {
            title = stringResource(
                if (problem.reason == AddressRefusal.UnsupportedScheme) R.string.browser_scheme_title else R.string.browser_refused_title,
            )
            body = when (problem.reason) {
                AddressRefusal.UnsupportedScheme -> stringResource(R.string.browser_scheme_body, problem.scheme.orEmpty())
                AddressRefusal.Credentials -> stringResource(R.string.browser_credentials_body)
                AddressRefusal.Empty, AddressRefusal.TooLong, AddressRefusal.Invalid -> stringResource(R.string.browser_invalid_body)
            }
        }
        is BrowserProblem.LoadFailed -> {
            title = stringResource(R.string.browser_load_title)
            body = stringResource(R.string.browser_load_body)
            url = problem.url
        }
        is BrowserProblem.Tls -> {
            title = stringResource(R.string.browser_tls_title)
            body = stringResource(R.string.browser_tls_body)
            url = problem.url
        }
        BrowserProblem.Crashed -> {
            title = stringResource(R.string.browser_load_title)
            body = stringResource(R.string.browser_crashed)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Icon(FeatherIcons.AlertTriangle, contentDescription = null, modifier = Modifier.size(28.dp), tint = colors.warnText)
        Text(title, style = DsType.title17, color = colors.labelPrimary)
        url?.let { LtrText(it, DsType.mono12, colors.labelSecondary, maxLines = 3) }
        Text(body, style = DsType.std14, color = colors.labelSecondary)
        (problem as? BrowserProblem.LoadFailed)?.detail?.let {
            LtrText(it, DsType.mono12, colors.labelTertiary, maxLines = 2)
        }
        when (problem) {
            is BrowserProblem.HarnessLoopback -> {
                problem.alternative?.let { alternative ->
                    val hostLabel = (parseBrowserAddress(alternative) as? AddressInput.Web)?.address?.let { a ->
                        a.host + (a.port?.let { ":$it" } ?: "")
                    } ?: alternative
                    DsButton(stringResource(R.string.browser_try_on, hostLabel), onClick = { onOpen(alternative) }, allowWrap = true)
                }
                DsButton(
                    stringResource(R.string.browser_open_on_phone),
                    onClick = { onOpenOnPhone(problem.url) },
                    variant = DsButtonVariant.Outline,
                    allowWrap = true,
                )
            }
            is BrowserProblem.LoadFailed, is BrowserProblem.Crashed ->
                DsButton(stringResource(R.string.common_retry), onClick = onReload, variant = DsButtonVariant.Outline)
            else -> Unit
        }
    }
}
