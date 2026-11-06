package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import com.labteto.dshmobile.core.wire.dto.*
import com.labteto.dshmobile.ui.screens.main.preview.FileType

internal class PreviewTab(val path: String) {
    val scroll = androidx.compose.foundation.ScrollState(0)
    var stat by mutableStateOf<WorkspaceFileStat?>(null)
    var text by mutableStateOf<String?>(null)
    var bytes by mutableStateOf<ByteArray?>(null)
    var nextLine by mutableIntStateOf(1)
    var eof by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** What the file is: decided from its name, or from its first bytes when the name says nothing. */
    var type by mutableStateOf<FileType?>(null)

    /** The first bytes of the file, kept for a hex dump of something the app cannot draw. */
    var head by mutableStateOf<ByteArray?>(null)

    /** The file is bigger than the preview will hold; the body offers to save a copy instead. */
    var tooLarge by mutableStateOf(false)

    /** A text file stopped growing at the preview's text cap although the host has more. */
    var capped by mutableStateOf(false)

    /** The face chosen for this file (`rendered`, `source`, `tree`...), or null for the kind's first. */
    var mode by mutableStateOf<String?>(null)

    /** Wrap long lines in the code view. */
    var wrap by mutableStateOf(true)

    /** Show line numbers in the code view. */
    var numbers by mutableStateOf(true)

    /** A short fact a renderer learned about the file ("1920 × 1080", "12 pages"), shown in the header. */
    var detail by mutableStateOf<String?>(null)

    /** A copy is being written to the device. */
    var saving by mutableStateOf(false)

    /** The full-screen viewer is open on this file. */
    var fullOpen by mutableStateOf(false)

    /** Branches of the JSON tree that are open. */
    var jsonOpen by mutableStateOf(setOf("$"))

    private val lists = mutableMapOf<String, LazyListState>()
    private val scrolls = mutableMapOf<String, ScrollState>()

    /** A list position that outlives the tab being switched away from. [key] names the face and the surface. */
    fun listState(key: String): LazyListState = lists.getOrPut(key) { LazyListState() }

    /** As [listState], for a plain scrolling column. */
    fun scrollState(key: String): ScrollState = scrolls.getOrPut(key) { ScrollState(0) }
}

internal class PanelState(val key: ComposerKey) {
    val directoryScroll = mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>()
    var section by mutableIntStateOf(0)
    var directory by mutableStateOf(".")
    var listing by mutableStateOf<WorkspaceDirectoryListing?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    val previews = mutableStateListOf<PreviewTab>()
    var selectedPreview by mutableIntStateOf(0)
    var terminals by mutableStateOf<List<WebTerminalInfo>>(emptyList())
    var selectedTerminal by mutableStateOf<String?>(null)
    var shells by mutableStateOf<List<TerminalShell>>(emptyList())
    var shellPath by mutableStateOf<String?>(null)
    /**
     * Show Files at [path]. The listing is cleared so the panel fetches it when it next composes,
     * which is the same route the Files toolbar takes.
     */
    fun browse(path: String) {
        // Canonical workspace path: the root listing is "" on the wire and "." here, so the
        // toolbar/entry joins never build "/name" (host: `workspace-file/not-found: no entry at
        // "/name"`) and a reference's backslashes do not survive into a request.
        directory = workspacePath(path)
        listing = null
        error = null
        section = 0
    }

    fun open(path: String) {
        val target = workspacePath(path)
        val index = previews.indexOfFirst { it.path == target }
        if (index >= 0) selectedPreview = index else {
            // Bound decoded documents retained by this panel; the selected document can be reloaded.
            previews.forEach { it.bytes = null }
            previews.add(PreviewTab(target)); selectedPreview = previews.lastIndex
        }
        section = 1
    }
}

internal class PanelRepository {
    private val panels = mutableMapOf<ComposerKey, PanelState>()
    fun get(key: ComposerKey): PanelState = panels.getOrPut(key) { PanelState(key) }
}
