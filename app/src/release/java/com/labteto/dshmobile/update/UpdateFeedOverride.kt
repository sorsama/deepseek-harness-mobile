package com.labteto.dshmobile.update

import android.content.Context

/**
 * Release builds have no update feed override: they only ever ask GitHub. The debug source set has
 * the implementation that reads a local test feed; this one exists so the shared code compiles.
 */
internal object UpdateFeedOverride {
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    fun read(context: Context): UpdateFeed? = null
}
