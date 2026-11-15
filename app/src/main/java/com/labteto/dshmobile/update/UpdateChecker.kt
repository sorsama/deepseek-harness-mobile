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
    /** The release notes, in GitHub's Markdown. */
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = -1,
)

/** One file attached to a release, by the name GitHub lists it under; [size] is -1 when unknown. */
data class ReleaseAsset(val name: String, val url: String, val size: Long = -1)

/**
 * A release newer than the running build.
 *
 * [apk] and [checksums] are what the in-app download and "install via Termux" need, and are null
 * when the release carries no such files; the release page at [url] is always there. [notes] are
 * the release notes in Markdown, when the release has any.
 */
data class AvailableUpdate(
    val version: String,
    val url: String,
    val apk: ReleaseAsset? = null,
    val checksums: ReleaseAsset? = null,
    val notes: String? = null,
)

/** Release notes longer than this are cut, with a link to the release page for the rest. */
internal const val MAX_NOTES_CHARS = 6_000

/**
 * The APK to install and the checksum file to verify it with, out of a release's assets.
 *
 * The release workflow uploads `app-release.apk` and `SHA256SUMS.txt`; the APK is matched by
 * that name first and by extension second, so a renamed asset still installs. Only URLs [allowed]
 * accepts are considered: by default `https://` ones, because the URL is spliced into a shell
 * command in Termux; the in-app updater passes its [DownloadHostPolicy], which is stricter still.
 */
internal fun pickReleaseAssets(
    assets: List<ReleaseAsset>,
    allowed: (String) -> Boolean = { it.startsWith("https://") },
): Pair<ReleaseAsset?, ReleaseAsset?> {
    val safe = assets.filter { allowed(it.url) && it.name.isNotBlank() }
    val apk = safe.firstOrNull { it.name == "app-release.apk" }
        ?: safe.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
    val sums = safe.firstOrNull { it.name.equals("SHA256SUMS.txt", ignoreCase = true) }
    return apk to sums
}

/** Release notes as the dialog shows them: trimmed, empty as null, and cut at [MAX_NOTES_CHARS]. */
internal fun trimNotes(body: String?): String? {
    val text = body?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (text.length > MAX_NOTES_CHARS) text.take(MAX_NOTES_CHARS).trimEnd() + "\n\n…" else text
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
    private val feeds: UpdateFeedSource,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<AvailableUpdate?>(null)

    /** The update to offer, or null when there is none, none wanted, or none confirmed yet. */
    val available: StateFlow<AvailableUpdate?> = _available.asStateFlow()

    private val _latest = MutableStateFlow<AvailableUpdate?>(null)

    /**
     * The newest release known to be ahead of this build, whether or not the person declined it.
     * Settings shows it beside the version, so "Later" in the dialog never hides the way to update.
     */
    val latest: StateFlow<AvailableUpdate?> = _latest.asStateFlow()

    /** When the automatic check last ran, so a warm reopen can decide whether to run it again. */
    @Volatile
    private var lastAutoCheckAt = 0L

    /**
     * The automatic check: on start, and again when the app comes back to the foreground after
     * [AUTO_CHECK_INTERVAL_MS]. The old once-per-process latch meant a phone that keeps the app in
     * memory for days never asked again, which is why a release published after the last cold
     * start was never offered.
     */
    suspend fun checkOnce(currentVersion: String) {
        val now = System.currentTimeMillis()
        if (now - lastAutoCheckAt < AUTO_CHECK_INTERVAL_MS) return
        val settings = runCatching { hostsStore.settingsOnce() }.getOrNull() ?: return
        if (!settings.updateCheckEnabled) return
        // Stamped only once a check really runs, so switching the check on is honoured at the
        // next resume rather than up to six hours later.
        lastAutoCheckAt = now
        // A check that never reached GitHub (no network yet at launch, a connect that timed out)
        // does not count: the next return to the app tries again instead of six hours later.
        if (check(currentVersion, ignoreDismissed = false) == CheckOutcome.Unreachable) lastAutoCheckAt = 0L
    }

    /**
     * The manual check from Settings. Ignores the automatic-check switch (the user just asked) and
     * the dismissed version (asking again is withdrawing the dismissal), and reports every outcome
     * — including "up to date" and "could not reach GitHub" — because a button that says nothing
     * when there is nothing to offer looks broken.
     */
    suspend fun checkNow(currentVersion: String): CheckOutcome = check(currentVersion, ignoreDismissed = true)

    /**
     * The check the daily background job makes: like the automatic one, it respects a declined
     * release, but it does not touch the foreground rate limit.
     */
    suspend fun checkInBackground(currentVersion: String): CheckOutcome = check(currentVersion, ignoreDismissed = false)

    private suspend fun check(currentVersion: String, ignoreDismissed: Boolean): CheckOutcome {
        val feed = feeds.current()
        val release = fetchLatest(feed) ?: return CheckOutcome.Unreachable
        val version = release.tagName.trim().removePrefix("v")
        if (version.isEmpty() || !isNewerVersion(version, currentVersion)) {
            _available.value = null
            _latest.value = null
            return CheckOutcome.UpToDate(version.ifEmpty { currentVersion })
        }
        val (apk, checksums) = pickReleaseAssets(
            release.assets.map { ReleaseAsset(it.name, it.downloadUrl, it.size) },
            allowed = { feed.policy.allowsUrl(it) },
        )
        val pageUrl = release.htmlUrl.takeIf { it.startsWith("https://") || feed.policy.allowsUrl(it) }
            ?: feed.releasesPageUrl
        val update = AvailableUpdate(version, pageUrl, apk, checksums, trimNotes(release.body))
        _latest.value = update
        if (!ignoreDismissed) {
            val dismissed = runCatching { hostsStore.settingsOnce().dismissedUpdate }.getOrNull()
            if (dismissed == version) return CheckOutcome.Dismissed(version)
        }
        _available.value = update
        return CheckOutcome.Available(update)
    }

    /**
     * Offer [update] without asking GitHub: a download the updater found on disk after a restart,
     * still ahead of this build. A newer release found since wins.
     */
    fun offer(update: AvailableUpdate) {
        if (_latest.value?.let { isNewerVersion(it.version, update.version) } == true) return
        _latest.value = update
        _available.value = update
    }

    /** Stop offering [version]; a later release will still be offered. */
    suspend fun dismiss(version: String) {
        // [latest] keeps it: Settings still offers the update after "Later".
        _available.value = null
        runCatching { hostsStore.setDismissedUpdate(version) }
    }

    private suspend fun fetchLatest(feed: UpdateFeed): GithubRelease? = withContext(Dispatchers.IO) {
        if (!feed.policy.allowsUrl(feed.latestReleaseUrl)) return@withContext null
        val request = Request.Builder()
            .url(feed.latestReleaseUrl)
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

    /** What a check found. */
    sealed interface CheckOutcome {
        data class Available(val update: AvailableUpdate) : CheckOutcome
        data class UpToDate(val latest: String) : CheckOutcome
        data class Dismissed(val version: String) : CheckOutcome
        data object Unreachable : CheckOutcome
    }

    internal companion object {
        /** Six hours: often enough to notice a release the same day, rare enough to be free. */
        const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
