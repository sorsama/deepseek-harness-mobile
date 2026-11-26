package com.labteto.dshmobile.ui.screens.main

import platform.Foundation.NSURL

/** `NSURL`'s decoded path; a string `NSURL` refuses is null. */
internal actual fun fileUriPath(uri: String): String? = NSURL.URLWithString(uri)?.path
