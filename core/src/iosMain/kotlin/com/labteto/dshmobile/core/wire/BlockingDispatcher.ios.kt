package com.labteto.dshmobile.core.wire

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** iOS actual: Kotlin/Native's `Dispatchers.IO`, a worker pool that tolerates blocking calls. */
internal actual val blockingDispatcher: CoroutineDispatcher get() = Dispatchers.IO
