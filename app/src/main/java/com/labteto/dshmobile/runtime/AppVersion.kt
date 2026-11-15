package com.labteto.dshmobile.runtime

/**
 * Semantic versions, as far as `minAppVersion` needs them.
 *
 * `major.minor.patch`, an optional `-prerelease` that sorts before the release it precedes, and
 * `+build` metadata that is ignored. A leading `v` is tolerated because release tags carry one.
 */
internal data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String>,
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // A version without a prerelease ranks above one with it.
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return compareValues(other.prerelease.isEmpty(), prerelease.isEmpty()).let { -it }
        }
        for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val a = prerelease[i]
            val b = other.prerelease[i]
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val CORE = Regex("""(\d+)(?:\.(\d+))?(?:\.(\d+))?""")

        /** Null for anything that is not a version, so callers can fail closed. */
        fun parse(text: String): AppVersion? {
            val clean = text.trim().removePrefix("v").substringBefore('+')
            val core = clean.substringBefore('-')
            val pre = if ('-' in clean) clean.substringAfter('-') else ""
            val match = CORE.matchEntire(core) ?: return null
            val parts = match.groupValues.drop(1).map { if (it.isEmpty()) 0 else it.toIntOrNull() ?: return null }
            val prerelease = if (pre.isEmpty()) emptyList() else pre.split('.')
            if (prerelease.any { it.isEmpty() }) return null
            return AppVersion(parts[0], parts[1], parts[2], prerelease)
        }

        /** Whether [appVersion] is at least [minVersion]; false when either cannot be read. */
        fun satisfies(appVersion: String, minVersion: String): Boolean {
            val app = parse(appVersion) ?: return false
            val min = parse(minVersion) ?: return false
            return app >= min
        }
    }
}
