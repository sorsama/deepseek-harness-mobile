package com.labteto.dshmobile.core.wire

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** JVM actual: the shared IO thread pool. */
actual val blockingDispatcher: CoroutineDispatcher get() = Dispatchers.IO
