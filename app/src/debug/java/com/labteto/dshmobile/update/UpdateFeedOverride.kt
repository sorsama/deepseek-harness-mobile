package com.labteto.dshmobile.update

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/**
 * DEBUG BUILDS ONLY: point the updater at a local test feed instead of GitHub.
 *
 * A release APK cannot replace a debug build (different application id and key), so the in-app
 * update can only be exercised end to end against a feed that serves a debug build. Write the
 * feed's base URL into the app's private files and the next check uses it:
 *
 * ```
 * adb shell run-as com.labteto.dshmobile.debug sh -c 'echo http://10.0.2.2:8787/ > files/update-feed-override.txt'
 * ```
 *
 * The feed must answer `repos/sorsama/deepseek-harness-mobile/releases/latest` under that base the
 * way GitHub does. Only loopback and the emulator's alias for the host computer are accepted, for
 * the base URL and for every download and redirect while the override is on: the override exists
 * to test against a server on the developer's own machine, not to fetch builds from anywhere.
 * Delete the file to go back to GitHub.
 *
 * The release source set has its own `UpdateFeedOverride` that always answers null.
 */
internal object UpdateFeedOverride {
    const val FILE_NAME = "update-feed-override.txt"

    fun read(context: Context): UpdateFeed? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        val base = runCatching { file.readText().trim() }.getOrNull()?.toHttpUrlOrNull() ?: return null
        return localTestFeed(base)
    }
}

/** Hosts a debug build may use as its update feed: this device, or the computer running the emulator. */
internal val LOCAL_TEST_HOSTS: Set<String> = setOf("127.0.0.1", "localhost", "::1", "10.0.2.2")

/** A feed rooted at [base], or null when [base] is not on a [LOCAL_TEST_HOSTS] address. */
internal fun localTestFeed(base: HttpUrl): UpdateFeed? {
    if (base.host !in LOCAL_TEST_HOSTS) return null
    val root = if (base.encodedPath.endsWith("/")) base else base.newBuilder().addPathSegment("").build()
    val latest = root.resolve("repos/${UpdateFeed.REPO}/releases/latest") ?: return null
    val page = root.resolve("releases/latest") ?: return null
    return UpdateFeed(
        latestReleaseUrl = latest.toString(),
        releasesPageUrl = page.toString(),
        policy = DownloadHostPolicy { url -> url.host in LOCAL_TEST_HOSTS },
    )
}
