package com.labteto.dshmobile.ui.screens.main

/**
 * [value]'s identity hash, as `System.identityHashCode` gives it on the JVM: the same for the same object, whatever
 * its `equals` says. The attachment tray keys an image chip on it (two equal images are still two chips).
 */
internal expect fun identityHash(value: Any): Int
