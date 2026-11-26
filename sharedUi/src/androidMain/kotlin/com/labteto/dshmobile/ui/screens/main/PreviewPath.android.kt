package com.labteto.dshmobile.ui.screens.main

/** `java.net.URI`'s decoded path, as the chat always read it; a URI that does not parse is null. */
internal actual fun fileUriPath(uri: String): String? = runCatching { java.net.URI(uri).path }.getOrNull()
