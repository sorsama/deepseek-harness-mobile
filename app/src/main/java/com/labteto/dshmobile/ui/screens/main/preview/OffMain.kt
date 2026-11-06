package com.labteto.dshmobile.ui.screens.main.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Computes something that can take a while (parsing a file, splitting it into lines) off the main
 * thread, and hands back the latest answer, or null until the first one is ready.
 *
 * It runs again whenever one of [keys] changes. The previous answer stays in place until the new
 * one lands, so a view that is being refreshed (a text file that just loaded its next page) keeps
 * showing what it had rather than flashing back to a placeholder.
 */
@Composable
internal fun <T> computedOffMain(vararg keys: Any?, block: suspend () -> T): T? {
    var result by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(*keys) {
        result = withContext(Dispatchers.Default) { block() }
    }
    return result
}
