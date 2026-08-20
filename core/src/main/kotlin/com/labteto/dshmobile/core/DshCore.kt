package com.labteto.dshmobile.core

/** Core module placeholder for the baseline build; wire protocol code lands here. */
object DshCore {
    /**
     * The harness release this client's DTOs and call shapes were ported from and verified
     * against. Display-only: nothing is gated on it, and the app never compares it against
     * `host.describe.version` — see `docs/COMPATIBILITY.md`. Where a shape genuinely differs
     * between releases the client reads the difference off the wire instead, as
     * `DshApiClient.acceptsCommandImages` does.
     */
    const val PROTOCOL_BASELINE = "0.1.0-rc.8"
}
