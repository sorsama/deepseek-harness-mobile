package com.labteto.dshmobile.update

import com.labteto.dshmobile.connection.HostsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** The subset of a GitHub release this app reads. */
@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
)

/** One file attached to a release, by the name GitHub lists it under. */
data class ReleaseAsset(val name: String, val url: String)

/**
 * A release newer than the running build.
 *
 * [apk] and [checksums] are what "install via Termux" needs and are null when the release
 * carries no such files — the release page at [url] is always there.
 */
data class AvailableUpdate(
    val version: String,
    val url: String,
    val apk: ReleaseAsset? = null,
    val checksums: ReleaseAsset? = null,
)

/**
 * The APK to install and the checksum file to verify it with, out of a release's assets.
 *
 * The release workflow uploads `app-release.apk` and `SHA256SUMS.txt`; the APK is matched by
 * that name first and by extension second, so a renamed asset still installs. Only `https://`
 * downloads are considered: the URL is spliced into a shell command in Termux.
 */
internal fun pickReleaseAssets(assets: List<ReleaseAsset>): Pair<ReleaseAsset?, ReleaseAsset?> {
    val safe = assets.filter { it.url.startsWith("https://") && it.name.isNotBlank() }
    val apk = safe.firstOrNull { it.name == "app-release.apk" }
        ?: safe.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
    val sums = safe.firstOrNull { it.name.equals("SHA256SUMS.txt", ignoreCase = true) }
    return apk to sums
}

/**
 * Is [candidate] a later version than [current]?
 *
 * Compares the numeric core only, so a `v` prefix and any `-rc1`/`-beta` suffix are ignored — which
 * also means a pre-release of a version already installed does not count as newer. Anything
 * unparseable in a component reads as 0 rather than throwing: a malformed tag should mean "no
 * update", never a crash on a screen the user did not ask for.
 *
 * A free function so the comparison can be tested without a network.
 */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    fun parts(value: String): List<Int> = value.trim()
        .removePrefix("v")
        .substringBefore('-')
        .substringBefore('+')
        .split('.')
        .map { it.trim().toIntOrNull() ?: 0 }

    val a = parts(candidate)
    val b = parts(current)
    if (a.all { it == 0 }) return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrElse(i) { 0 }
        val right = b.getOrElse(i) { 0 }
        if (left != right) return left > right
    }
    return false
}

/**
 * Asks GitHub whether a newer release exists.
 *
 * This is the only request the app itself makes to anything other than the harness the user
 * pointed it at, which is why it is behind a setting and why it never blocks anything: a failure
 * — offline, rate limited, no releases yet — leaves [available] null and is not reported. A
 * release the user has already declined stays declined until a later one appears. (Installing
 * through Termux downloads the APK too, but that is Termux fetching a file the user just asked
 * for, not this app phoning anywhere.)
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val client: OkHttpClient,
    private val hostsStore: HostsStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<AvailableUpdate?>(null)

    /** The update to offer, or null when there is none, none wanted, or none confirmed yet. */
    val available: StateFlow<AvailableUpdate?> = _available.asStateFlow()

    /** Run at most once per process; the release list does not change while the app is open. */
    @Volatile
    private var checked = false

    suspend fun checkOnce(currentVersion: String) {
        if (checked) return
        checked = true
        val settings = runCatching { hostsStore.settingsOnce() }.getOrNull() ?: return
        if (!settings.updateCheckEnabled) return

        val release = fetchLatest() ?: return
        val version = release.tagName.trim().removePrefix("v")
        if (version.isEmpty() || !isNewerVersion(version, currentVersion)) return
        if (settings.dismissedUpdate == version) return
        val (apk, checksums) = pickReleaseAssets(release.assets.map { ReleaseAsset(it.name, it.downloadUrl) })
        _available.value = AvailableUpdate(version, release.htmlUrl.ifBlank { RELEASES_URL }, apk, checksums)
    }

    /** Stop offering [version]; a later release will still be offered. */
    suspend fun dismiss(version: String) {
        _available.value = null
        runCatching { hostsStore.setDismissedUpdate(version) }
    }

    private suspend fun fetchLatest(): GithubRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                json.decodeFromString(GithubRelease.serializer(), body)
            }
        }.getOrNull()
    }

    private companion object {
        const val REPO = "sorsama/deepseek-harness-mobile"
        const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
        const val RELEASES_URL = "https://github.com/$REPO/releases/latest"
    }
}
