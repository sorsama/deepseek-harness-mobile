package com.labteto.dshmobile.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which URLs the updater is willing to fetch.
 *
 * Checked on the first request and again on every redirect hop, so a release asset that bounces
 * from `github.com` to GitHub's download CDN is followed while a hop to anywhere else stops the
 * download before a single byte is read from it.
 */
fun interface DownloadHostPolicy {
    fun allows(url: HttpUrl): Boolean

    companion object {
        /**
         * GitHub itself and the hosts GitHub serves release downloads from. A release asset URL
         * answers with a redirect to a short-lived signed URL on one of the `githubusercontent.com`
         * hosts; which one has changed over the years, so the three GitHub has used are listed.
         * Nothing else, and only over HTTPS on the default port.
         */
        val GITHUB_HOSTS: Set<String> = setOf(
            "github.com",
            "api.github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com",
        )

        val GitHub = DownloadHostPolicy { url ->
            url.isHttps && url.port == 443 && url.host in GITHUB_HOSTS &&
                url.username.isEmpty() && url.password.isEmpty()
        }
    }
}

/** [DownloadHostPolicy.allows] for a URL that may not parse; anything unparseable is refused. */
fun DownloadHostPolicy.allowsUrl(url: String): Boolean = url.toHttpUrlOrNull()?.let(::allows) ?: false

/** Where the app asks for the latest release, and which hosts it may download that release from. */
data class UpdateFeed(
    val latestReleaseUrl: String,
    val releasesPageUrl: String,
    val policy: DownloadHostPolicy,
) {
    companion object {
        const val REPO = "sorsama/deepseek-harness-mobile"

        val GitHub = UpdateFeed(
            latestReleaseUrl = "https://api.github.com/repos/$REPO/releases/latest",
            releasesPageUrl = "https://github.com/$REPO/releases/latest",
            policy = DownloadHostPolicy.GitHub,
        )
    }
}

/** The feed to use right now. An interface so the checker can be driven by a fake in tests. */
fun interface UpdateFeedSource {
    fun current(): UpdateFeed
}

/**
 * GitHub, unless a debug build was pointed at a local test feed.
 *
 * `UpdateFeedOverride` has two implementations: the one in `src/debug` reads a file a developer
 * puts in the app's private storage (see its documentation), and the one in `src/release` always
 * answers null, so a release build has no way to fetch updates from anywhere but GitHub.
 */
@Singleton
class DefaultUpdateFeedSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateFeedSource {
    override fun current(): UpdateFeed = UpdateFeedOverride.read(context) ?: UpdateFeed.GitHub
}
