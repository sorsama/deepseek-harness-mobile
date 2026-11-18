package com.labteto.dshmobile.core.market

import com.labteto.dshmobile.core.wire.dto.PluginBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketInstalledTest {
    private fun bundle(name: String, version: String? = null, installed: Boolean = true, availability: String? = null) =
        PluginBundle(name = name, version = version, enabled = true, installed = installed, availability = availability)

    private fun entry(status: MarketInstallStatus, versions: List<String> = listOf("1.2.0"), default: String? = versions.first()): MarketEntry {
        val name = "@example/dsh-x"
        val rows = versions.map { MarketFixtures.version(name, it) }
        val parsed = parseMarketSearch(MarketFixtures.answer(listOf(MarketFixtures.entry(name, versions = rows, defaultVersion = default))))!!.entries.single()
        return parsed.copy(installation = MarketInstallation(status))
    }

    @Test fun versionsOrderAsSemverDoes() {
        assertEquals(1, compareVersions("1.2.0", "1.1.9"))
        assertEquals(-1, compareVersions("0.9.0", "0.10.0"))
        assertEquals(0, compareVersions("1.0.0", "1.0.0+build.7"))
        // A release outranks its own prerelease.
        assertEquals(1, compareVersions("0.2.0", "0.2.0-rc.2"))
        assertEquals(-1, compareVersions("0.2.0-rc.1", "0.2.0"))
        // semver 2.0 section 11 example chain.
        val chain = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        chain.zipWithNext().forEach { (low, high) -> assertEquals("$low < $high", -1, compareVersions(low, high)); assertEquals(1, compareVersions(high, low)) }
        assertEquals(1, compareVersions("1.0.0-rc.2", "1.0.0-rc.1"))
    }

    @Test fun aVersionThatIsNotSemverHasNoOrderAndAHugeNumberDoesNotOverflow() {
        assertNull(compareVersions("latest", "1.0.0"))
        assertNull(compareVersions("1.0", "1.0.0"))
        assertNull(compareVersions("1.0.0-", "1.0.0"))
        assertEquals(1, compareVersions("1.0." + "9".repeat(60), "1.0.9"))
        assertEquals(-1, compareVersions("1.0.0-" + "1".repeat(50), "1.0.0-" + "1".repeat(51)))
    }

    @Test fun theHostsOwnStatusIsUsedAsItIs() {
        val installed = entry(MarketInstallStatus.UPDATE_AVAILABLE)
        // Even with a bundle list that would say otherwise: the host measured it against its own state.
        val result = marketInstallationFor(installed, listOf(bundle("@example/dsh-x", "1.2.0")))
        assertEquals(MarketInstallStatus.UPDATE_AVAILABLE, result.status)
    }

    @Test fun anUnknownStatusIsWorkedOutFromTheBundlesByName() {
        val e = entry(MarketInstallStatus.UNKNOWN, listOf("1.2.0"))
        assertEquals(MarketInstallStatus.NOT_INSTALLED, marketInstallationFor(e, emptyList()).status)
        assertEquals(MarketInstallStatus.NOT_INSTALLED, marketInstallationFor(e, listOf(bundle("@example/other", "1.2.0"))).status)
        assertEquals(MarketInstallation(MarketInstallStatus.INSTALLED, "1.2.0"), marketInstallationFor(e, listOf(bundle("@example/dsh-x", "1.2.0"))))
        assertEquals(MarketInstallation(MarketInstallStatus.UPDATE_AVAILABLE, "1.1.0"), marketInstallationFor(e, listOf(bundle("@example/dsh-x", "1.1.0"))))
        // Installed newer than the market lists, or not valid semver: different, never "update".
        assertEquals(MarketInstallation(MarketInstallStatus.DIFFERENT_VERSION, "2.0.0"), marketInstallationFor(e, listOf(bundle("@example/dsh-x", "2.0.0"))))
        assertEquals(MarketInstallation(MarketInstallStatus.DIFFERENT_VERSION, "nightly"), marketInstallationFor(e, listOf(bundle("@example/dsh-x", "nightly"))))
        assertEquals(MarketInstallStatus.DIFFERENT_VERSION, marketInstallationFor(e, listOf(bundle("@example/dsh-x", null))).status)
    }

    @Test fun aCatalogBundleTheHarnessShipsIsProvidedNotInstalled() {
        val e = entry(MarketInstallStatus.UNKNOWN)
        assertEquals(MarketInstallStatus.PROVIDED, marketInstallationFor(e, listOf(bundle("@example/dsh-x", "1.2.0", installed = false, availability = "installation"))).status)
        assertEquals(MarketInstallStatus.NOT_INSTALLED, marketInstallationFor(e, listOf(bundle("@example/dsh-x", null, installed = false, availability = "missing"))).status)
    }

    @Test fun theInstalledBundleIsFoundByNameOnlyWhenThePageListsItAsInstalled() {
        val installed = bundle("@example/dsh-x", "1.0.0")
        assertEquals(installed, marketInstalledBundle("@example/dsh-x", listOf(bundle("@example/other"), installed)))
        assertNull(marketInstalledBundle("@example/dsh-x", listOf(bundle("@example/dsh-x", installed = false, availability = "missing"))))
        assertNull(marketInstalledBundle("@example/dsh-x", emptyList()))
        // A name that merely contains the entry's is another package.
        assertNull(marketInstalledBundle("@example/dsh-x", listOf(bundle("@example/dsh-x-extra", "1.0.0"))))
        assertTrue(marketInstalledBundle("@example/dsh-x", listOf(bundle("@example/dsh-x", installed = false, availability = "installation"))) != null)
    }
}
