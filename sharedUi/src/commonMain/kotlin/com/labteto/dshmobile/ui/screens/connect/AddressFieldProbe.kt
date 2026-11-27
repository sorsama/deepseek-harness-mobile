package com.labteto.dshmobile.ui.screens.connect

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect

/**
 * A debug hook on the Connect screen's address field: [focusRequester] is attached to the field, and [onBounds] hears
 * where the field is in the window each time it is placed. Only the iOS app's debug launch provides one
 * (`-DshFocusAddress`, the CI scenario that brings the software keyboard up over this screen and checks the field stays
 * above it); without one, which is every other launch and all of Android, the field is exactly as it was.
 */
class AddressFieldProbe(val focusRequester: FocusRequester, val onBounds: (Rect) -> Unit)

/** The [AddressFieldProbe] of this composition, if a debug launch asked for one. */
val LocalAddressFieldProbe = staticCompositionLocalOf<AddressFieldProbe?> { null }
