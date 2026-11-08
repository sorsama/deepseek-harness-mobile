package com.labteto.dshmobile.ios

import com.labteto.dshmobile.core.wire.darwinHttpClient
import io.ktor.client.HttpClient

internal actual fun newHarnessHttpClient(): HttpClient = darwinHttpClient()
