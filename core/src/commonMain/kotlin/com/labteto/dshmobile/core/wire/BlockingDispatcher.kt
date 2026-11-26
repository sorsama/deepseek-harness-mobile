package com.labteto.dshmobile.core.wire

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Where code that blocks its thread runs: reading or writing a kotlinx-io `Source` that is backed by
 * a connection or a file.
 *
 * `expect`/`actual` only because `Dispatchers.IO` is declared in kotlinx-coroutines' concurrent
 * source set, which a project's `commonMain` does not see (the metadata compile reports
 * `Unresolved reference 'IO'`), while every platform this module targets has one. Both actuals are
 * the platform's `Dispatchers.IO`.
 *
 * Public so code outside `core` that blocks the same way (the session store reading a picked file into an upload)
 * has one common name for it instead of `Dispatchers.IO`, which its own `commonMain` cannot see either.
 */
expect val blockingDispatcher: CoroutineDispatcher
