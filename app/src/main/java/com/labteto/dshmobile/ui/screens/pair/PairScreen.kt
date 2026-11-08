package com.labteto.dshmobile.ui.screens.pair

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Enrol this device with a relay, by QR or by typed code (design 05).
 *
 * The screen itself is [PairContent] in `:sharedUi`, the same Compose code the iOS app runs; this is
 * the Android end of it. It reads the Hilt view model's state, and it provides the scanner: the
 * camera sheet stays here ([QrScanSheet] owns the permission, the ZXing preview, the flashlight and
 * the photo picker, none of which iOS has in this form).
 */
@Composable
fun PairScreen(
    onClose: () -> Unit,
    prefillUrl: String? = null,
    viewModel: PairViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PairContent(
        state = state,
        controller = viewModel.controller,
        onClose = onClose,
        scanner = { close, decoded, typeCode ->
            QrScanSheet(onClose = close, onDecoded = decoded, onTypeCode = typeCode)
        },
        prefillUrl = prefillUrl,
    )
}
