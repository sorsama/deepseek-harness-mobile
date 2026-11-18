package com.labteto.dshmobile.core.market

import com.labteto.dshmobile.core.wire.dto.PluginBundle

/** A parsed `MAJOR.MINOR.PATCH[-pre][+build]` version; build metadata never takes part in ordering. */
private class SemVer(val numbers: List<String>, val pre: List<String>)

private val SEMVER = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

private fun parseSemVer(text: String): SemVer? {
    val match = SEMVER.matchEntire(text.trim()) ?: return null
    val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
    if (pre.any { it.isEmpty() }) return null
    return SemVer(listOf(match.groupValues[1], match.groupValues[2], match.groupValues[3]), pre)
}

/** Digit strings compare as numbers without parsing them, so a hostile 40-digit "number" cannot overflow. */
private fun compareNumeric(a: String, b: String): Int {
    val left = a.trimStart('0'); val right = b.trimStart('0')
    return if (left.length != right.length) left.length.compareTo(right.length) else left.compareTo(right)
}

/**
 * Orders two versions as semver 2.0 does (a release outranks its own prereleases); null when either is not a
 * valid version. Used only to say "newer" when the host sent no installation status of its own.
 */
fun compareVersions(a: String, b: String): Int? {
    val left = parseSemVer(a) ?: return null
    val right = parseSemVer(b) ?: return null
    for (i in 0..2) {
        val order = compareNumeric(left.numbers[i], right.numbers[i])
        if (order != 0) return order.coerceIn(-1, 1)
    }
    if (left.pre.isEmpty() || right.pre.isEmpty()) return right.pre.size.compareTo(left.pre.size).coerceIn(-1, 1)
    for (i in 0 until minOf(left.pre.size, right.pre.size)) {
        val x = left.pre[i]; val y = right.pre[i]
        val xNumeric = x.all { it.isDigit() }; val yNumeric = y.all { it.isDigit() }
        val order = when {
            xNumeric && yNumeric -> compareNumeric(x, y)
            xNumeric -> -1
            yNumeric -> 1
            else -> x.compareTo(y)
        }
        if (order != 0) return order.coerceIn(-1, 1)
    }
    return left.pre.size.compareTo(right.pre.size).coerceIn(-1, 1)
}

/** A bundle the harness lists as installed: the Plugins page's "Installed" group (an on-demand catalog bundle counts). */
private fun PluginBundle.isListedInstalled(): Boolean = availability?.let { it != "missing" } ?: installed

/** The bundle that an entry of this name is, when the harness has it listed as installed. */
fun marketInstalledBundle(name: String, bundles: List<PluginBundle>): PluginBundle? =
    bundles.firstOrNull { it.name == name && it.isListedInstalled() }

/**
 * How an entry stands against what is installed. The harness measures it itself, against the entry's
 * default version, and says so in every listing, so its word is used. A status the app does not know (a newer
 * harness adding one, or none sent) is worked out here from the bundles the Plugins page already holds:
 * matched by package name, newer only when both versions are valid semver.
 */
fun marketInstallationFor(entry: MarketEntry, bundles: List<PluginBundle>): MarketInstallation {
    if (entry.installation.status != MarketInstallStatus.UNKNOWN) return entry.installation
    val bundle = bundles.firstOrNull { it.name == entry.name } ?: return MarketInstallation(MarketInstallStatus.NOT_INSTALLED)
    if (!bundle.installed) {
        return if (bundle.availability == "installation") MarketInstallation(MarketInstallStatus.PROVIDED, bundle.version)
        else MarketInstallation(MarketInstallStatus.NOT_INSTALLED)
    }
    val installed = bundle.version ?: return MarketInstallation(MarketInstallStatus.DIFFERENT_VERSION)
    val measured = entry.presented?.version ?: return MarketInstallation(MarketInstallStatus.DIFFERENT_VERSION, installed)
    val status = when {
        installed == measured -> MarketInstallStatus.INSTALLED
        (compareVersions(measured, installed) ?: 0) > 0 -> MarketInstallStatus.UPDATE_AVAILABLE
        else -> MarketInstallStatus.DIFFERENT_VERSION
    }
    return MarketInstallation(status, installed)
}
