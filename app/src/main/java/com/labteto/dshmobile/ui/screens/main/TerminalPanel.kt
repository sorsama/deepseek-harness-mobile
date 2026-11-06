package com.labteto.dshmobile.ui.screens.main

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.dto.*
import com.labteto.dshmobile.data.SessionStore
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.inkFieldFrame
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DsTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.json.JSONObject
import java.util.UUID

/**
 * The terminal tab: shell picker, open terminals, the xterm surface, and a key row for the keys a
 * phone keyboard does not have.
 *
 * The xterm bridge is untouched — the WebView still owns input and rendering, the same attachment
 * protocol drives it. What changed is the chrome around it: an Ink shell strip above, a code-block
 * surface with a hairline instead of a raw WebView, and a scrollable row of terminal keys below, so
 * `Tab`, `Esc`, arrows and `Ctrl-C` (and a clear) are reachable without a hardware keyboard.
 */
@Composable
internal fun TerminalPanel(store: SessionStore, state: PanelState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val key = state.key
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var shellMenu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<String?>(null) }
    fun operation(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }
    suspend fun refresh() {
        val api = store.apiForHost(key.host) ?: error(context.getString(R.string.common_offline))
        state.terminals = api.terminalList(key.sessionId).requireValue()
        state.shells = api.terminalShells(key.sessionId).requireValue()
        if (state.terminals.none { it.id == state.selectedTerminal }) state.selectedTerminal = state.terminals.firstOrNull()?.id
    }
    LaunchedEffect(key) { operation { refresh() } }
    val colors = DsTheme.colors
    // The raw Material dropdown item has no haptic vocabulary of its own.
    val haptics = rememberDsHaptics()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        // Shell strip: the shell the next terminal opens with, then its own actions.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
            Box {
                DsPill(
                    text = state.shells.firstOrNull { it.path == state.shellPath }?.name ?: stringResource(R.string.terminal_shell),
                    selected = true,
                    onClick = {
                        if (!busy) {
                            shellMenu = true
                        }
                    },
                )
                DropdownMenu(shellMenu, { shellMenu = false }) {
                    state.shells.forEach { shell ->
                        DropdownMenuItem(text = { Text(shell.name, color = colors.labelPrimary) }, onClick = {
                            haptics.selection()
                            state.shellPath = shell.path
                            shellMenu = false
                        })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            DsIconButton(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.terminal_new),
                onClick = {
                    operation {
                        val api = store.apiForHost(key.host) ?: error(context.getString(R.string.common_offline))
                        val info = api.terminalCreate(key.sessionId, TerminalCreateRequest(UUID.randomUUID().toString(), 80, 24, state.shellPath)).requireValue()
                        state.selectedTerminal = info.id
                        refresh()
                    }
                },
                enabled = !busy,
                tint = colors.accent,
            )
            DsIconButton(
                icon = FeatherIcons.RefreshCw,
                contentDescription = stringResource(R.string.common_retry),
                onClick = { operation { refresh() } },
                enabled = !busy,
                iconSize = 18.dp,
            )
        }
        // Open terminals as pills; the live one is selected.
        if (state.terminals.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                state.terminals.forEach { terminal ->
                    DsPill(
                        text = terminal.title,
                        selected = terminal.id == state.selectedTerminal,
                        onClick = {
                            state.selectedTerminal = terminal.id
                        },
                    )
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.borderL1)
        error?.let { message ->
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
                    onClick = { operation { refresh() } },
                    enabled = !busy,
                    variant = DsButtonVariant.Outline,
                    size = DsButtonSize.Small,
                )
            }
        }
        val terminal = state.terminals.firstOrNull { it.id == state.selectedTerminal }
        if (terminal == null) {
            EmptyHero(headline = stringResource(R.string.terminal_empty), subtitle = null, showPreview = false)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    terminal.title,
                    style = DsType.rowTitle,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                DsIconButton(
                    icon = FeatherIcons.Edit3,
                    contentDescription = stringResource(R.string.common_rename),
                    onClick = { rename = terminal.title },
                    iconSize = 18.dp,
                )
                DsIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.common_close),
                    enabled = !busy,
                    onClick = {
                        operation {
                            store.apiForHost(key.host)?.terminalClose(key.sessionId, terminal.id)?.requireValue()
                                ?: error(context.getString(R.string.common_offline))
                            refresh()
                        }
                    },
                    iconSize = 18.dp,
                )
            }
            key(terminal.id) { TerminalScreen(store, key, terminal, Modifier.weight(1f)) }
            rename?.let { title ->
                DsDialog(stringResource(R.string.common_rename), { if (!busy) rename = null }) {
                    TextField(
                        value = title,
                        onValueChange = { rename = it },
                        modifier = Modifier.fillMaxWidth().inkFieldFrame(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.common_rename), style = DsType.caption11) },
                        textStyle = DsType.mono13,
                        colors = dialogTextFieldColors(),
                        shape = DsShapes.control,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        DsButton(
                            text = stringResource(R.string.common_save),
                            onClick = {
                                    operation {
                                    store.apiForHost(key.host)?.terminalRename(key.sessionId, terminal.id, title)?.requireValue()
                                        ?: error(context.getString(R.string.common_offline))
                                    rename = null; refresh()
                                }
                            },
                            enabled = title.isNotBlank() && title.length <= 120 && !busy,
                            variant = DsButtonVariant.Primary,
                        )
                        DsButton(
                            text = stringResource(R.string.common_cancel),
                            onClick = { rename = null },
                            enabled = !busy,
                            variant = DsButtonVariant.Outline,
                        )
                    }
                }
            }
        }
    }
}

private class TerminalBridge(private val receive: (String) -> Unit) {
    @JavascriptInterface fun postMessage(message: String) { if (message.length <= 131072) receive(message) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TerminalScreen(store: SessionStore, key: ComposerKey, initial: WebTerminalInfo, modifier: Modifier) {
    val context = LocalContext.current
    val connection by store.connectionState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var reconnect by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf(initial) }
    var writable by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var environment by remember { mutableStateOf<TerminalEnvironment?>(null) }
    val attachmentId = remember(reconnect) { UUID.randomUUID().toString() }
    val input = remember { Channel<String>(64) }
    val colors = DsTheme.colors
    // The xterm palette follows the Ink tokens: the mouse surface is the code-block colour, text is
    // the primary label. The same values are pushed into the page's setTheme() as it changes.
    val background = "#%06x".format(colors.codeBlockBg.toArgb() and 0xffffff)
    val foreground = "#%06x".format(colors.labelPrimary.toArgb() and 0xffffff)
    val view = remember {
        WebView.setWebContentsDebuggingEnabled(com.labteto.dshmobile.BuildConfig.DEBUG)
        WebView(context).apply {
            // Legacy WebViews can lose the containing Compose dialog's surface with GPU drawing.
            if (android.os.Build.VERSION.SDK_INT <= 30) setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            }
            addJavascriptInterface(TerminalBridge { message ->
                scope.launch {
                    val json = runCatching { JSONObject(message) }.getOrNull() ?: return@launch
                    if (json.optString("type") == "ready") ready = true else input.send(message)
                }
            }, "TerminalHost")
            val js = context.assets.open("terminal/xterm.js").bufferedReader().use { it.readText() }
            val fit = context.assets.open("terminal/addon-fit.js").bufferedReader().use { it.readText() }
            val css = context.assets.open("terminal/xterm.css").bufferedReader().use { it.readText() }
            loadDataWithBaseURL("https://terminal.invalid/", """
                <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
                <style>html,body,#terminal{width:100%;height:100%;margin:0;overflow:hidden} $css</style></head><body><div id="terminal"></div>
                <script>
                // Android 11 can ship a WebView older than replaceChildren (Chrome 86).
                for (const proto of [Element.prototype,DocumentFragment.prototype]) {
                    if (!proto.replaceChildren) proto.replaceChildren=function(...children){
                        while(this.firstChild)this.removeChild(this.firstChild);
                        for(const child of children)this.appendChild(typeof child==='string'?document.createTextNode(child):child);
                    };
                }
                </script><script>$js</script><script>$fit</script><script>
                const term = new Terminal({fontSize:14,scrollback:1000,disableStdin:true});
                const fit = new FitAddon.FitAddon();term.loadAddon(fit);term.open(document.getElementById('terminal'));
                function post(value){TerminalHost.postMessage(JSON.stringify(value))}
                function measure(){
                    // Older Android WebViews resolve percentage/vh heights to zero in a dialog.
                    const height=Math.max(1,window.innerHeight)+'px';
                    document.documentElement.style.height=height;document.body.style.height=height;
                    document.getElementById('terminal').style.height=height;
                    const d=fit.proposeDimensions();if(d)post({type:'resize',cols:d.cols,rows:d.rows});
                }
                window.renderFrame=function(frame){if(frame.type==='snapshot'){term.reset();term.resize(frame.info.cols,frame.info.rows);term.write(frame.screen||'')}else if(frame.type==='output')term.write(frame.data||'')};
                window.setWritable=function(value){term.options.disableStdin=!value;if(value){measure();term.focus()}};
                window.setTheme=function(bg,fg){term.options.theme={background:bg,foreground:fg,cursor:fg};document.body.style.background=bg};
                term.onData(data=>post({type:'input',data}));window.addEventListener('resize',measure);post({type:'ready'});
                </script></body></html>
            """.trimIndent(), "text/html", "utf-8", null)
        }
    }
    DisposableEffect(view) { onDispose { input.close(); view.removeJavascriptInterface("TerminalHost"); view.destroy() } }
    LaunchedEffect(ready, background, foreground) {
        if (ready) view.evaluateJavascript("setTheme(${JSONObject.quote(background)},${JSONObject.quote(foreground)})", null)
    }
    LaunchedEffect(ready, writable) { if (ready) view.evaluateJavascript("setWritable($writable)", null) }
    LaunchedEffect(ready, reconnect, connection, store.muxForHost(key.host)) {
        if (!ready) return@LaunchedEffect
        connected = false; writable = false; error = null
        while (input.tryReceive().isSuccess) { /* Discard input meant for the previous attachment. */ }
        try {
            val api = store.apiForHost(key.host) ?: error(context.getString(R.string.common_offline))
            val mux = store.muxForHost(key.host) ?: error(context.getString(R.string.common_offline))
            val env = api.terminalEnvironment(key.sessionId).requireValue()
            environment = env
            val attached = attachDuplex(mux, api, key, initial, env, view, input,
                onFrame = { frame, isWritable ->
                    if (frame.type == "snapshot") connected = true
                    frame.info?.let { info = it }
                    if (isWritable != null) writable = connected && info.state == "running" && isWritable
                },
                onInputRefused = { error = context.getString(R.string.panel_too_large) },
                gapMessage = context.getString(R.string.panel_changed),
            )
            if (!attached) {
                // A host before 0.2.1-alpha.3 has no duplex attach. It streams the screen on
                // `terminal/follow` and takes input as separate calls, with control decided by
                // which attachment id the host names as controller.
                followLegacy(mux, api, key, initial, attachmentId, env, view, input,
                    onFrame = { frame ->
                        if (frame.type == "snapshot") connected = true
                        frame.info?.let { info = it }
                        writable = connected && info.state == "running" && info.controllerId == attachmentId
                    },
                    isWritable = { writable },
                    onInputFailed = { message -> writable = false; error = message },
                    gapMessage = context.getString(R.string.panel_changed),
                    tooLargeMessage = context.getString(R.string.panel_too_large),
                )
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        finally { connected = false; writable = false }
    }
    // Hold the terminal open for as long as this tab is on screen.
    //
    // Current-master hosts reclaim a terminal after a couple of hours of confirmed idle unless
    // some window is holding it, and `terminal/follow` deliberately does not count — it is a
    // subscription to the screen, not a claim on the process. Without this a terminal left open on
    // a phone is collected underneath the person while its tab still looks live.
    //
    // The hold is its own stream and carries no data: the first frame is the acknowledgement and
    // the rest of its life is just staying open. A host that predates the endpoint fails the
    // stream instead, which is the same "does not offer that" answer a 404 gives elsewhere and is
    // ignored for the same reason — there is nothing to hold and nothing to tell the person.
    LaunchedEffect(initial.id, attachmentId, reconnect, connection, store.muxForHost(key.host)) {
        val mux = store.muxForHost(key.host) ?: return@LaunchedEffect
        try {
            mux.openStream("terminal/retain", buildJsonObject {
                put("sessionId", JsonPrimitive(key.sessionId)); put("id", JsonPrimitive(initial.id))
            }).collect { /* The stream's existence is the hold; its frames carry nothing to read. */ }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* No retention service composed, or the terminal is already gone. */ }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        if (!connected || !writable) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(DsShapes.code)
                    .background(colors.bgLayer2)
                    .border(1.dp, colors.borderL1, DsShapes.code)
                    .padding(start = DsSpacing.medium, end = DsSpacing.small, top = DsSpacing.tiny, bottom = DsSpacing.tiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (connected) stringResource(R.string.terminal_readonly) else stringResource(R.string.common_offline),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                    modifier = Modifier.weight(1f),
                )
                DsButton(
                    text = stringResource(if (connected) R.string.terminal_control else R.string.common_retry),
                    onClick = { reconnect++ },
                    variant = DsButtonVariant.Outline,
                    size = DsButtonSize.Small,
                )
            }
        }
        if (info.state != "running") {
            val label = stringResource(if (info.state == "failed") R.string.common_error else R.string.chat_stopped) +
                (info.exitCode?.let { " ($it)" } ?: "")
            Text(label, style = DsType.small13, color = colors.labelSecondary, softWrap = true)
        }
        error?.let { Text(it, style = DsType.small13, color = colors.error, softWrap = true) }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(DsShapes.code)
                .background(colors.codeBlockBg)
                .border(1.dp, colors.borderL1, DsShapes.code),
        ) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        }
        TerminalKeyRow(enabled = writable) { data ->
            scope.launch {
                // Dropped rather than parked when the buffer is full: a key pressed while the host
                // is behind should not surface minutes later as a stray character.
                runCatching { input.trySend(buildJsonObject { put("type", JsonPrimitive("input")); put("data", JsonPrimitive(data)) }.toString()) }
            }
        }
    }
}

/** The keys a phone keyboard does not have; each writes an ordinary escape sequence. */
@Composable
private fun TerminalKeyRow(enabled: Boolean, onSend: (String) -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = DsSpacing.tiny),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalKey(stringResource(R.string.ink_files_key_tab), enabled) { haptics.tap(); onSend("\t") }
        TerminalKey(stringResource(R.string.ink_files_key_esc), enabled) { haptics.tap(); onSend("\u001B") }
        TerminalKey(stringResource(R.string.ink_files_key_up), enabled) { haptics.tap(); onSend("\u001B[A") }
        TerminalKey(stringResource(R.string.ink_files_key_down), enabled) { haptics.tap(); onSend("\u001B[B") }
        TerminalKey(stringResource(R.string.ink_files_key_ctrl_c), enabled) { haptics.tap(); onSend("\u0003") }
        TerminalKey(stringResource(R.string.ink_files_clear_terminal), enabled) { haptics.tap(); onSend("\u000C") }
        TerminalKey(stringResource(R.string.ink_files_send_enter), enabled, primary = true) { haptics.confirm(); onSend("\r") }
    }
}

@Composable
private fun TerminalKey(label: String, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) DsAnimations.Scale.pressed else DsAnimations.Scale.normal,
        animationSpec = DsAnimations.pressScale,
        label = "keyScale",
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .heightIn(min = DsSizes.chip)
            .alpha(if (enabled) 1f else 0.45f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = DsShapes.pillFull,
        color = if (primary) colors.brandPrimary else colors.bgLayer3,
        contentColor = if (primary) colors.onBrandPrimary else colors.labelSecondary,
        border = if (primary) null else androidx.compose.foundation.BorderStroke(1.dp, colors.borderL2),
        interactionSource = interaction,
    ) {
        Box(Modifier.padding(horizontal = DsSpacing.medium), contentAlignment = Alignment.Center) {
            Text(label, style = DsType.mono12, maxLines = 1)
        }
    }
}

/** Gateway answers meaning the host has no such stream, as opposed to refusing this request. */
private val NO_DUPLEX = setOf(
    "gateway/method-unavailable",
    "gateway/invocation-unavailable",
    "gateway/arguments-invalid",
    "capability-unavailable",
)

/**
 * Run one `terminal/attach` attachment (harness 0.2.1-alpha.3 and later) until it ends.
 *
 * Output arrives on the stream's downlink and input leaves on its uplink as numbered commands;
 * [TerminalInputLedger] keeps those within the host's window and releases them on `ack`. Input
 * and resizes from the page are pumped onto the uplink by a sibling coroutine for as long as the
 * stream is open.
 *
 * Returns false, having done nothing visible, when the host has no duplex attach at all, so the
 * caller can fall back to the older follow-and-call protocol.
 */
private suspend fun attachDuplex(
    mux: com.labteto.dshmobile.core.wire.RemoteStreamMux,
    api: com.labteto.dshmobile.core.wire.DshApiClient,
    key: ComposerKey,
    terminal: WebTerminalInfo,
    env: TerminalEnvironment,
    view: WebView,
    input: Channel<String>,
    onFrame: (TerminalFrame, Boolean?) -> Unit,
    onInputRefused: () -> Unit,
    gapMessage: String,
): Boolean = coroutineScope {
    val stream = mux.open("terminal/attach", api.terminalAttachArgs(key.sessionId, terminal.id))
    val ledger = com.labteto.dshmobile.core.wire.TerminalInputLedger(env.maxInputBytes, env.maxBufferedBytes)
    var writable = false
    var cols = terminal.cols
    var rows = terminal.rows
    fun flush() {
        if (!writable) return
        for (command in ledger.drain()) {
            if (!stream.send(command)) error(gapMessage)
        }
    }
    var pump: Job? = null
    try {
        var outputSequence: Long? = null
        while (true) {
            val raw = try {
                stream.receive()
            } catch (e: com.labteto.dshmobile.core.wire.RemoteStreamException) {
                if (outputSequence == null && !e.carrier && e.error.code in NO_DUPLEX) return@coroutineScope false
                throw e
            } ?: break
            val frame = decodeFromJsonElement(TerminalFrame.serializer(), raw)
            when (frame.type) {
                "snapshot" -> {
                    outputSequence = frame.sequence
                    ledger.window = frame.inputWindow
                    frame.info?.let { cols = it.cols; rows = it.rows }
                    writable = frame.writable == true && frame.info?.state == "running"
                    onFrame(frame, frame.writable == true)
                    view.evaluateJavascript("renderFrame($raw)", null)
                    if (pump == null) pump = launch {
                        for (message in input) {
                            val json = runCatching { JSONObject(message) }.getOrNull() ?: continue
                            if (!writable) continue
                            val accepted = when (json.optString("type")) {
                                "input" -> ledger.write(json.optString("data"))
                                "resize" -> {
                                    val c = json.optInt("cols").coerceIn(2, env.maxCols)
                                    val r = json.optInt("rows").coerceIn(1, env.maxRows)
                                    if (c == cols && r == rows) {
                                        true
                                    } else {
                                        ledger.resize(c, r).also { ok ->
                                            if (ok) {
                                                cols = c; rows = r
                                                view.evaluateJavascript("term.resize($c,$r)", null)
                                            }
                                        }
                                    }
                                }
                                else -> true
                            }
                            if (!accepted) onInputRefused() else flush()
                        }
                    }
                }
                "output" -> {
                    val next = frame.sequence ?: error(gapMessage)
                    val previous = outputSequence ?: error(gapMessage)
                    if (next != previous + 1) error(gapMessage)
                    outputSequence = next
                    view.evaluateJavascript("renderFrame($raw)", null)
                }
                "state" -> {
                    writable = frame.writable == true && frame.info?.state == "running"
                    onFrame(frame, frame.writable == true)
                    flush()
                }
                "ack" -> {
                    if (!ledger.acknowledge(frame.sequence ?: -1)) error(gapMessage)
                    if (frame.refusal != null) {
                        writable = false
                        onFrame(frame, false)
                    }
                    flush()
                }
            }
        }
        true
    } finally {
        pump?.cancel()
        stream.cancel()
    }
}

/** The pre-alpha.3 terminal: `terminal/follow` for the screen, unary write and resize for input. */
private suspend fun followLegacy(
    mux: com.labteto.dshmobile.core.wire.RemoteStreamMux,
    api: com.labteto.dshmobile.core.wire.DshApiClient,
    key: ComposerKey,
    terminal: WebTerminalInfo,
    attachmentId: String,
    env: TerminalEnvironment,
    view: WebView,
    input: Channel<String>,
    onFrame: (TerminalFrame) -> Unit,
    isWritable: () -> Boolean,
    onInputFailed: (String?) -> Unit,
    gapMessage: String,
    tooLargeMessage: String,
) = coroutineScope {
    val pump = launch {
        for (message in input) {
            if (!isWritable()) continue
            try {
                val json = JSONObject(message)
                when (json.optString("type")) {
                    "input" -> {
                        val data = json.optString("data")
                        if (data.toByteArray(Charsets.UTF_8).size > env.maxInputBytes) error(tooLargeMessage)
                        api.terminalWrite(key.sessionId, terminal.id, attachmentId, data).requireValue()
                    }
                    "resize" -> {
                        val cols = json.optInt("cols").coerceIn(2, env.maxCols)
                        val rows = json.optInt("rows").coerceIn(1, env.maxRows)
                        api.terminalResize(key.sessionId, terminal.id, attachmentId, cols, rows).requireValue()
                        view.evaluateJavascript("term.resize($cols,$rows)", null)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onInputFailed(e.message) }
        }
    }
    try {
        var sequence: Long? = null
        mux.openStream("terminal/follow", api.terminalFollowArgs(key.sessionId, terminal.id, attachmentId)).collect { raw ->
            val frame = decodeFromJsonElement(TerminalFrame.serializer(), raw)
            if (frame.type == "snapshot") sequence = frame.sequence
            else if (frame.type == "output") {
                val next = frame.sequence ?: error(gapMessage)
                if (sequence == null || next != sequence!! + 1) error(gapMessage)
                sequence = next
            }
            onFrame(frame)
            view.evaluateJavascript("renderFrame($raw)", null)
        }
    } finally {
        pump.cancel()
    }
}


