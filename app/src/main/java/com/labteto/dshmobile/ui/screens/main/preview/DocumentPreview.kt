package com.labteto.dshmobile.ui.screens.main.preview

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.rememberCopier
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.screens.main.ComposerKey
import com.labteto.dshmobile.ui.screens.main.PreviewTab
import com.labteto.dshmobile.ui.theme.DsSpacing
import kotlinx.coroutines.launch

/**
 * One open file in the Preview tab: its header, and its body as a quick look. "Full preview" opens
 * the same file in [FullPreview] on top; both read the one [PreviewTab], so what one has loaded the
 * other already has, and each tab keeps its scroll position and chosen face while another is shown.
 *
 * Loading lives here ([PreviewLoader]), and so does the one launcher that writes a copy to the
 * device, which the header and the viewer both use.
 */
@Composable
internal fun DocumentPreview(
    store: SessionStore,
    key: ComposerKey,
    tab: PreviewTab,
    modifier: Modifier,
    onClose: () -> Unit,
    onDirectory: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberDsHaptics()
    val copy = rememberCopier()
    val directory by rememberUpdatedState(onDirectory)
    val loader = remember(tab, store, key) {
        PreviewLoader(store, key, tab, context, scope) { directory() }
    }
    LaunchedEffect(tab) { loader.load() }
    LaunchedEffect(tab.stat) { if (tab.stat == null) loader.load() }

    val saved = stringResource(R.string.preview_saved)
    val saveFailed = stringResource(R.string.preview_save_failed)
    // One contract instance per content type: a new instance on every recomposition would make the
    // launcher unregister and register again while the picker may still be open.
    val mime = tab.type?.mime ?: "application/octet-stream"
    val contract = remember(mime) { ActivityResultContracts.CreateDocument(mime) }
    val picker = rememberLauncherForActivityResult(contract) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = loader.saveTo(uri)
                if (ok) haptics.confirm() else haptics.reject()
                Toast.makeText(context, if (ok) saved else saveFailed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val actions = PreviewActions(
        canSave = tab.stat != null,
        saving = tab.saving,
        onSave = { picker.launch(splitPath(tab.path).second.ifEmpty { "file" }) },
        onOpenFull = { tab.fullOpen = true },
    )
    val text = tab.text
    val commands = FileCommands(
        onCopy = if (tab.type?.isText == true) {
            { text?.let(copy) }
        } else null,
        onCopyPath = { copy(tab.stat?.absolutePath ?: tab.path) },
        onRefresh = loader::refresh,
        onClose = onClose,
    )

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        PreviewHeader(tab, actions, commands)
        PreviewBody(
            store = store,
            key = key,
            tab = tab,
            loader = loader,
            surface = PreviewSurface.Inline,
            actions = actions,
            viewer = null,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            suspended = tab.fullOpen,
        )
    }
    if (tab.fullOpen) {
        FullPreview(
            store = store,
            key = key,
            tab = tab,
            loader = loader,
            actions = actions,
            commands = commands,
            onDismiss = { tab.fullOpen = false },
        )
    }
}
