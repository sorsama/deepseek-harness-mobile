package com.labteto.dshmobile.core.market

import com.labteto.dshmobile.core.wire.dto.PluginInspection

/**
 * What the harness's own look at a package (`pluginManager/inspect`) says about the install an entry is
 * about to make. A market is a claim: the index names a package and a version, and the harness reads the
 * real one. The two must agree before anything is offered for confirmation.
 */
sealed interface MarketCheck {
    /** Nothing disagrees: the package is the one listed, or it is a git commit (pinned), which the harness reads only at install. */
    data object Proceed : MarketCheck

    /** The package the harness found is not the one the market listed. [name] and [version] are what it found. */
    data class Mismatch(val name: String?, val version: String?) : MarketCheck

    /** The harness refused the spec; [problem] is its code (`not-found`, `network`, `already-installed`, ...). */
    data class Refused(val problem: String?, val reason: String?) : MarketCheck

    /** The harness has no `pnpm` to install with, so no package can be installed on it. */
    data object NoPackageManager : MarketCheck
}

private val MISSING_TOOL = Regex("enoent|not recognized|not found|no such file", RegexOption.IGNORE_CASE)

/**
 * The refusal a host without `pnpm` gives, in whatever words its shell uses: `spawn pnpm ENOENT` (Node),
 * `'pnpm' is not recognized as an internal or external command` (Windows, captured from 0.2.1-rc.2) and
 * `pnpm: not found` (a POSIX shell). Only an unclassified refusal can be this one, and it must name pnpm.
 */
fun looksLikeMissingPackageManager(problem: String?, reason: String?): Boolean =
    problem == "unknown" && reason != null && reason.contains("pnpm", ignoreCase = true) && MISSING_TOOL.containsMatchIn(reason)

/** Compare the harness's inspection of [version]'s install spec with what the market listed for [entry]. */
fun marketCheck(entry: MarketEntry, version: MarketVersion, inspection: PluginInspection): MarketCheck {
    if (inspection.status != "accepted") {
        val reason = inspection.reason?.let { cleanMarketText(it, MarketLimits.REASON) }
        return if (looksLikeMissingPackageManager(inspection.problem, inspection.reason)) MarketCheck.NoPackageManager
        else MarketCheck.Refused(inspection.problem, reason)
    }
    // A git spec says nothing but its form here; the commit pins it.
    val name = inspection.name ?: return MarketCheck.Proceed
    val found = inspection.version
    val sameName = name == entry.name
    val sameVersion = found == null || found == version.version
    return if (sameName && sameVersion) MarketCheck.Proceed
    else MarketCheck.Mismatch(cleanMarketText(name, MarketLimits.TITLE), found?.let { cleanMarketText(it, MarketLimits.VERSION) })
}
