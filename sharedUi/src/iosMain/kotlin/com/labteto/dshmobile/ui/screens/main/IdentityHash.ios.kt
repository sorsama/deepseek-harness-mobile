package com.labteto.dshmobile.ui.screens.main

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.identityHashCode

@OptIn(ExperimentalNativeApi::class)
internal actual fun identityHash(value: Any): Int = value.identityHashCode()
