package com.labteto.dshmobile.core.market

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// Lenient, capped reading of the harness's market answers. A malformed entry is left out (and
// counted); nothing here throws on any JSON the host could send. See [MarketLimits].

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.array(): JsonArray? = this as? JsonArray
private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

/** Bidirectional overrides and isolates, and the byte-order mark: they reorder or hide text and mean nothing in a title. */
private fun Char.isHostile(): Boolean = this in '‪'..'‮' || this in '⁦'..'⁩' || this == '﻿'

/**
 * Text for display: control and bidi-override characters removed, cut to [max] with an ellipsis.
 * [multiline] keeps line breaks (at most one blank line in a row); otherwise every run of whitespace
 * becomes one space. Null when nothing is left.
 */
fun cleanMarketText(raw: String, max: Int, multiline: Boolean = false): String? {
    val out = StringBuilder(minOf(raw.length, max + 1))
    var newlines = 0
    var pendingSpace = false
    for (ch in raw) {
        when {
            ch == '\r' -> continue
            ch == '\n' -> if (multiline) { newlines++; pendingSpace = false } else pendingSpace = out.isNotEmpty()
            ch == '\t' || ch == ' ' || ch == ' ' -> pendingSpace = out.isNotEmpty() && newlines == 0
            ch.isISOControl() || ch.isHostile() -> continue
            else -> {
                if (newlines > 0 && out.isNotEmpty()) out.append(if (newlines > 1) "\n\n" else "\n")
                else if (pendingSpace) out.append(' ')
                newlines = 0; pendingSpace = false
                out.append(ch)
            }
        }
        // Stop reading a hostile megabyte once there is more than the cap plus the ellipsis.
        if (out.length > max + 1) break
    }
    val text = out.toString().trim()
    if (text.isEmpty()) return null
    if (text.length <= max) return text
    // Never end on half of a surrogate pair.
    var end = (max - 1).coerceAtLeast(0)
    if (end > 0 && text[end - 1].isHighSurrogate()) end--
    return text.substring(0, end).trimEnd() + "…"
}

/** A value that picks what is installed or opened: kept whole or not at all. No whitespace, no control characters. */
private fun String?.identity(max: Int): String? =
    this?.takeIf { it.isNotEmpty() && it.length <= max && it.none { ch -> ch.isISOControl() || ch.isWhitespace() || ch.isHostile() } }

/** An absolute http(s) URL with no credentials, kept whole; anything else is null. */
fun marketHttpUrl(raw: String?, max: Int = MarketLimits.URL): String? {
    val url = raw.identity(max) ?: return null
    val rest = when {
        url.startsWith("https://", ignoreCase = true) -> url.substring(8)
        url.startsWith("http://", ignoreCase = true) -> url.substring(7)
        else -> return null
    }
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isEmpty() || '@' in authority) return null
    return url
}

/** A `LocalizedText`: a string, or an object of strings by language. Null when it holds no usable text. */
fun parseMarketText(element: JsonElement?, max: Int, multiline: Boolean = false): MarketText? {
    element.string()?.let { raw -> return cleanMarketText(raw, max, multiline)?.let { MarketText(it) } }
    val map = element.obj() ?: return null
    val variants = LinkedHashMap<String, String>()
    for ((key, value) in map) {
        if (variants.size >= MarketLimits.TEXT_VARIANTS) break
        val language = key.trim().lowercase()
        if (language.isEmpty() || language.length > 35) continue
        val text = value.string()?.let { cleanMarketText(it, max, multiline) } ?: continue
        variants[language] = text
    }
    return if (variants.isEmpty()) null else MarketText(null, variants)
}

/** A category or label id: sent back to the host as written, so it is kept whole or not at all. Spaces are allowed. */
private fun String?.declaredId(): String? =
    this?.takeIf { it.isNotBlank() && it.length <= MarketLimits.DECLARATION_ID && it.none { ch -> ch.isISOControl() || ch.isHostile() } }

private fun parseDeclarations(element: JsonElement?): List<MarketDeclaration> {
    val map = element.obj() ?: return emptyList()
    val out = ArrayList<MarketDeclaration>()
    for ((id, value) in map) {
        if (out.size >= MarketLimits.MAX_DECLARATIONS) break
        val key = id.declaredId() ?: continue
        val title = parseMarketText(value.obj()?.get("title"), MarketLimits.DECLARATION_TITLE)
            ?: cleanMarketText(key, MarketLimits.DECLARATION_TITLE)?.let { MarketText(it) }
            ?: continue
        out += MarketDeclaration(key, title)
    }
    return out
}

private fun parseCompatibility(element: JsonElement?): MarketCompatibility {
    val obj = element.obj() ?: return MarketCompatibility(compatible = false)
    val compatible = (obj["compatible"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
    if (compatible) return MarketCompatibility(true)
    val requirement = obj["requirements"].obj()?.values?.mapNotNull { it.string()?.let { r -> cleanMarketText(r, MarketLimits.REASON) } }
        ?.joinToString(" ")?.takeIf { it.isNotEmpty() }
    return MarketCompatibility(
        compatible = false,
        runtimeVersion = obj["runtimeVersion"].string().identity(MarketLimits.VERSION),
        requirement = requirement,
        exempted = (obj["exempted"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true",
    )
}

private val HEX40 = Regex("^[0-9a-f]{40}$")

private fun parseInstallSource(element: JsonElement?): MarketInstallSource? {
    val obj = element.obj() ?: return null
    return when (obj["type"].string()) {
        "npm" -> {
            val raw = obj["registry"]
            // A registry that is present but unusable must not turn into "any registry".
            if (raw == null || raw is JsonNull) MarketInstallSource.Npm(null)
            else MarketInstallSource.Npm(marketHttpUrl(raw.string()) ?: return null)
        }
        "git" -> {
            val url = marketHttpUrl(obj["url"].string()) ?: return null
            val commit = obj["commit"].string()?.takeIf { HEX40.matches(it) } ?: return null
            val rawPath = obj["path"]
            val path = if (rawPath == null || rawPath is JsonNull) null else rawPath.string().identity(MarketLimits.PATH) ?: return null
            MarketInstallSource.Git(url, commit, path)
        }
        else -> null
    }
}

/**
 * The install arguments the format fixes for one version (`marketInstallTarget` in the harness): `name@version` for npm,
 * and `git+<repository>.git#<commit>[&path:/<path>]` for git. A spec the host sent that is not this one is not trusted.
 */
fun marketExpectedSpec(name: String, version: String, source: MarketInstallSource): String = when (source) {
    is MarketInstallSource.Npm -> "$name@$version"
    is MarketInstallSource.Git -> {
        val repository = if (source.url.endsWith(".git")) source.url else "${source.url}.git"
        "git+$repository#${source.commit}${source.path?.let { "&path:/$it" }.orEmpty()}"
    }
}

private fun parseVersion(name: String, element: JsonElement?): MarketVersion? {
    val obj = element.obj() ?: return null
    val version = obj["version"].string().identity(MarketLimits.VERSION) ?: return null
    val source = parseInstallSource(obj["source"]) ?: return null
    val install = obj["install"].obj() ?: return null
    val spec = install["spec"].string().identity(MarketLimits.SPEC) ?: return null
    if (spec != marketExpectedSpec(name, version, source)) return null
    val registry = install["registry"].let { raw ->
        if (raw == null || raw is JsonNull) null else marketHttpUrl(raw.string()) ?: return null
    }
    // The registry that installs is the one the index named for this version; a host cannot add or swap one.
    val declared = (source as? MarketInstallSource.Npm)?.registry
    if (registry != declared) return null
    return MarketVersion(
        version = version,
        publishedAt = obj["publishTimestamp"].long()?.coerceAtLeast(0) ?: 0,
        source = source,
        dshRange = obj["engines"].obj()?.get("dsh").string()?.let { cleanMarketText(it, MarketLimits.REASON) },
        installSpec = spec,
        installRegistry = registry,
        compatibility = parseCompatibility(obj["compatibility"]),
    )
}

private fun parseInstallation(element: JsonElement?): MarketInstallation {
    val obj = element.obj() ?: return MarketInstallation(MarketInstallStatus.UNKNOWN)
    val status = when (obj["status"].string()) {
        "not-installed" -> MarketInstallStatus.NOT_INSTALLED
        "installed" -> MarketInstallStatus.INSTALLED
        "update-available" -> MarketInstallStatus.UPDATE_AVAILABLE
        "different-version" -> MarketInstallStatus.DIFFERENT_VERSION
        "provided" -> MarketInstallStatus.PROVIDED
        else -> MarketInstallStatus.UNKNOWN
    }
    return MarketInstallation(status, obj["installedVersion"].string().identity(MarketLimits.VERSION))
}

private fun parseEntry(element: JsonElement): MarketEntry? {
    val obj = element.obj() ?: return null
    val name = obj["name"].string().identity(MarketLimits.NAME) ?: return null
    val defaultRaw = obj["defaultVersion"].string().identity(MarketLimits.VERSION)
    val parsed = (obj["versions"].array() ?: return null).asSequence().take(MarketLimits.MAX_VERSIONS_READ)
        .mapNotNull { parseVersion(name, it) }.distinctBy { it.version }.toList()
    if (parsed.isEmpty()) return null
    var kept = parsed.take(MarketLimits.MAX_VERSIONS)
    val default = parsed.firstOrNull { it.version == defaultRaw }
    // The default is what Install offers: it stays even when the list is cut.
    if (default != null && default !in kept) kept = kept.dropLast(1) + default
    val metadata = obj["metadata"].obj()
    val labels = metadata?.get("labels").array().orEmpty().asSequence()
        .mapNotNull { it.string().declaredId() }
        .distinct().take(MarketLimits.MAX_ENTRY_LABELS).toList()
    val repository = obj["repository"].obj()?.let { repo ->
        marketHttpUrl(repo["url"].string())?.let { url ->
            MarketRepository(url, repo["directory"].string()?.let { cleanMarketText(it, MarketLimits.PATH) })
        }
    }
    return MarketEntry(
        name = name,
        title = parseMarketText(obj["title"], MarketLimits.TITLE),
        description = parseMarketText(obj["description"], MarketLimits.DESCRIPTION, multiline = true),
        author = obj["author"].string()?.let { cleanMarketText(it, MarketLimits.AUTHOR) },
        homepage = marketHttpUrl(obj["homepage"].string()),
        repository = repository,
        category = metadata?.get("category").string().declaredId(),
        labels = labels,
        versions = kept,
        defaultVersion = default?.version,
        installation = parseInstallation(obj["installation"]),
    )
}

/**
 * One `pluginMarket/search` answer. Null when the value is not the object of an answer at all; a bad entry inside a
 * good answer is left out and counted in [MarketPage.dropped].
 */
fun parseMarketSearch(value: JsonElement): MarketPage? {
    val root = value.obj() ?: return null
    val rawEntries = root["entries"].array() ?: return null
    val url = marketHttpUrl(root["url"].string()) ?: return null
    val entries = ArrayList<MarketEntry>()
    val seen = HashSet<String>()
    var accepted = 0
    for (element in rawEntries.take(MarketLimits.MAX_ENTRIES_PER_ANSWER)) {
        val entry = parseEntry(element) ?: continue
        if (seen.add(entry.name)) { entries += entry; accepted++ }
    }
    val metadata = root["metadata"].obj()
    val total = (root["total"].int() ?: entries.size).coerceAtLeast(entries.size)
    return MarketPage(
        header = MarketHeader(
            url = url,
            title = parseMarketText(root["title"], MarketLimits.TITLE),
            description = parseMarketText(root["description"], MarketLimits.DESCRIPTION, multiline = true),
            categories = parseDeclarations(metadata?.get("categories")),
            labels = parseDeclarations(metadata?.get("labels")),
        ),
        fetchedAt = root["fetchedAt"].long() ?: 0,
        entries = entries,
        total = total,
        nextOffset = root["nextOffset"].int()?.takeIf { it >= 0 },
        dropped = (rawEntries.size - accepted).coerceAtLeast(0),
    )
}

/** The `pluginMarket/listIndexes` answer: each configured source with the title of its last good read and its last error. */
fun parseMarketIndexes(value: JsonElement): List<MarketIndexInfo>? {
    val rows = value.array() ?: return null
    return rows.asSequence().mapNotNull { row ->
        val obj = row.obj() ?: return@mapNotNull null
        // A source URL is shown and sent back whole, so one that does not fit is not listed rather than cut.
        val url = obj["url"].string()?.takeIf { it.isNotEmpty() && it.length <= MarketLimits.URL && it.none { ch -> ch.isISOControl() || ch.isHostile() } }
            ?: return@mapNotNull null
        MarketIndexInfo(
            url = url,
            title = parseMarketText(obj["title"], MarketLimits.TITLE),
            description = parseMarketText(obj["description"], MarketLimits.DESCRIPTION, multiline = true),
            error = obj["error"].string()?.let { cleanMarketText(it, MarketLimits.REASON) },
        )
    }.take(MarketLimits.MAX_SOURCES).toList()
}
