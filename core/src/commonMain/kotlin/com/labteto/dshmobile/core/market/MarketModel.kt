package com.labteto.dshmobile.core.market

/**
 * What the app keeps of a plugin market, as the harness serves it (`pluginMarket/search`).
 *
 * The harness fetches, validates and caches an index; the phone never reads one from the internet.
 * Everything here is still third-party text: a market is whatever its owner published, and the host
 * validates the shape but not the words. So the model is capped (see [MarketLimits]), display
 * strings are cleaned of control and bidi characters, and nothing that picks what gets installed
 * (a name, a version, an install spec, a registry) is ever shortened: a value over its cap makes
 * the version (or the entry) unusable instead of being cut into a different package.
 */
object MarketLimits {
    /** Entries asked for per request: the page a "Show more" adds. The host refuses more than [MAX_PAGE]. */
    const val PAGE_SIZE = 50
    const val MAX_PAGE = 100

    /** Entries one listing ever holds. A market larger than this is narrowed by search, not scrolled. */
    const val MAX_LOADED = 300

    /** Entries read from one answer; a host that sends more than it was asked for is cut here. */
    const val MAX_ENTRIES_PER_ANSWER = 100

    /** Versions kept per entry. The host lists them newest first; the default version is always kept. */
    const val MAX_VERSIONS = 40

    /** Version rows read per entry, so the default can be found even when it is far down a long list. */
    const val MAX_VERSIONS_READ = 400

    /** Category and label declarations kept per market, and labels kept per entry. */
    const val MAX_DECLARATIONS = 48
    const val MAX_ENTRY_LABELS = 12

    /** Index sources read from `listIndexes`. */
    const val MAX_SOURCES = 24

    // Display text: cut with an ellipsis.
    const val TITLE = 100
    const val DESCRIPTION = 2000
    const val AUTHOR = 100
    const val DECLARATION_TITLE = 60
    const val REASON = 300
    const val SEARCH_TEXT = 200

    // Identity and links: never cut. Over the cap, the value is dropped (and a version without a spec is not offered).
    const val NAME = 214
    const val VERSION = 64
    const val URL = 2048
    const val SPEC = 2048
    const val PATH = 512
    const val DECLARATION_ID = 64

    /** Localized variants kept per text. */
    const val TEXT_VARIANTS = 16
}

/**
 * A market's `LocalizedText`: one string for every language, or one string per language (the index
 * format requires an `en` entry). Keys are lower case, as the format fixes them.
 */
data class MarketText(val plain: String?, val byLanguage: Map<String, String> = emptyMap()) {
    /**
     * The text for the first of [languages] the market has, then English, then the plain string, then
     * the first variant it sent. [languages] is a preference chain of lower-case tags, such as
     * `zh-cn, zh` (see [marketLanguageChain]).
     */
    fun resolve(languages: List<String>): String? {
        for (language in languages) byLanguage[language]?.let { return it }
        return byLanguage["en"] ?: plain ?: byLanguage.values.firstOrNull()
    }
}

/** `zh-CN` becomes `zh-cn, zh`: the tag as the format writes it, then its language. */
fun marketLanguageChain(tag: String): List<String> {
    val lower = tag.trim().lowercase().replace('_', '-')
    if (lower.isEmpty()) return emptyList()
    return listOf(lower, lower.substringBefore('-')).distinct()
}

/** A category or label the market declares: an opaque id and the title it shows for it. */
data class MarketDeclaration(val id: String, val title: MarketText)

/** The installable form of one version: npm (optionally from a named registry) or a pinned git commit. */
sealed interface MarketInstallSource {
    data class Npm(val registry: String?) : MarketInstallSource
    data class Git(val url: String, val commit: String, val path: String?) : MarketInstallSource
}

/**
 * Whether the running harness satisfies a version's `engines.dsh`, as its own install check decides.
 * [requirement] is the range that was not met (shown as written); [exempted] means the profile holds an
 * exemption for this exact package and runtime, so installation accepts it anyway.
 */
data class MarketCompatibility(
    val compatible: Boolean,
    val runtimeVersion: String? = null,
    val requirement: String? = null,
    val exempted: Boolean = false,
) {
    /** The harness accepts the version: compatible, or covered by an exemption. */
    val installable: Boolean get() = compatible || exempted
}

/** One version of a plugin, with the exact arguments the harness gives to install it. */
data class MarketVersion(
    val version: String,
    /** Publish time in Unix seconds, as the index states it (not checked against anything). */
    val publishedAt: Long,
    val source: MarketInstallSource,
    /** `engines.dsh` as the index wrote it, or null. */
    val dshRange: String?,
    /** The package spec the install call takes (`name@version`, or a commit-pinned `git+https` URL). */
    val installSpec: String,
    /** The registry asked first, or null for the harness's own. */
    val installRegistry: String?,
    val compatibility: MarketCompatibility,
)

/** How an entry stands against what the harness has installed. */
enum class MarketInstallStatus { NOT_INSTALLED, INSTALLED, UPDATE_AVAILABLE, DIFFERENT_VERSION, PROVIDED, UNKNOWN }

data class MarketInstallation(val status: MarketInstallStatus, val installedVersion: String? = null)

data class MarketRepository(val url: String, val directory: String?)

/** One plugin of a market. */
data class MarketEntry(
    val name: String,
    val title: MarketText?,
    val description: MarketText?,
    val author: String?,
    /** An absolute http(s) link, or null. */
    val homepage: String?,
    val repository: MarketRepository?,
    val category: String?,
    val labels: List<String>,
    /** Newest first, at most [MarketLimits.MAX_VERSIONS]; only versions that carry an install spec. */
    val versions: List<MarketVersion>,
    val defaultVersion: String?,
    val installation: MarketInstallation,
) {
    /** The version the entry presents and installs by default, or its newest when no version is compatible. */
    val presented: MarketVersion? get() = versions.firstOrNull { it.version == defaultVersion } ?: versions.firstOrNull()
}

/** The market behind one configured index source. */
data class MarketHeader(
    val url: String,
    val title: MarketText?,
    val description: MarketText?,
    val categories: List<MarketDeclaration>,
    val labels: List<MarketDeclaration>,
)

/** One page of results, or (after [MarketLoader]) every page loaded so far. */
data class MarketPage(
    val header: MarketHeader,
    /** Fetch time of the document that answered; a different value means a different document. */
    val fetchedAt: Long,
    val entries: List<MarketEntry>,
    /** Matches across all pages, as the host counts them. */
    val total: Int,
    val nextOffset: Int?,
    /** Entries of the answer that could not be read and were left out. */
    val dropped: Int = 0,
)

/** One index source the harness has configured, and what its last read taught about it. */
data class MarketIndexInfo(val url: String, val title: MarketText?, val description: MarketText?, val error: String?)
