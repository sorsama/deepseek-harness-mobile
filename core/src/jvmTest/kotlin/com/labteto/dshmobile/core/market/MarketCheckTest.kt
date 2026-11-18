package com.labteto.dshmobile.core.market

import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.PluginInspection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketCheckTest {
    private val review = MarketFixtures.page("search-all").entries.first { it.name == "@example/dsh-auto-review" }
    private val foo = MarketFixtures.page("search-all").entries.first { it.name == "dsh-plugin-foo" }

    private fun inspected(name: String) = WireJson.decodeFromJsonElement(PluginInspection.serializer(), MarketFixtures.value(name))

    @Test fun aRegistryPackageThatIsTheOneListedProceeds() {
        val answer = PluginInspection(status = "accepted", kind = "registry", name = "@example/dsh-auto-review", version = "0.2.0-rc.2", bundle = true)
        assertEquals(MarketCheck.Proceed, marketCheck(review, review.presented!!, answer))
    }

    @Test fun aPackageWithAnotherNameIsNotWhatTheMarketListed() {
        val answer = PluginInspection(status = "accepted", kind = "registry", name = "evil-package", version = "0.2.0-rc.2", bundle = true)
        assertEquals(MarketCheck.Mismatch("evil-package", "0.2.0-rc.2"), marketCheck(review, review.presented!!, answer))
    }

    @Test fun aPackageWithAnotherVersionIsNotWhatTheMarketListed() {
        val answer = PluginInspection(status = "accepted", kind = "registry", name = "@example/dsh-auto-review", version = "9.9.9", bundle = true)
        assertEquals(MarketCheck.Mismatch("@example/dsh-auto-review", "9.9.9"), marketCheck(review, review.presented!!, answer))
    }

    @Test fun aGitSpecSaysNothingToCompareAndTheRealAnswerProceeds() {
        // Captured: git specs come back accepted with only their form and host.
        val answer = inspected("inspect-git")
        assertEquals("git", answer.kind)
        assertEquals(MarketCheck.Proceed, marketCheck(foo, foo.presented!!, answer))
    }

    @Test fun aRefusalKeepsTheHostsCodeAndWords() {
        val notFound = marketCheck(review, review.presented!!, inspected("inspect-registry")) as MarketCheck.Refused
        assertEquals("not-found", notFound.problem)
        assertTrue(notFound.reason!!.startsWith("ERR_PNPM_FETCH_404"))
        val badSpec = marketCheck(review, review.presented!!, inspected("inspect-bad-spec")) as MarketCheck.Refused
        assertEquals("invalid-spec", badSpec.problem)
    }

    @Test fun aHostWithoutPnpmIsToldApartFromAFailedLookup() {
        // Captured from 0.2.1-rc.2 booted without pnpm on PATH (Windows): the shell's own words.
        assertEquals(MarketCheck.NoPackageManager, marketCheck(review, review.presented!!, inspected("inspect-no-pnpm")))
        assertTrue(looksLikeMissingPackageManager("unknown", "spawn pnpm ENOENT"))
        assertTrue(looksLikeMissingPackageManager("unknown", "sh: 1: pnpm: not found"))
        assertTrue(looksLikeMissingPackageManager("unknown", "'pnpm' is not recognized as an internal or external command"))
        // A registry that cannot be reached, a missing package, or another tool missing are not "no pnpm".
        assertFalse(looksLikeMissingPackageManager("network", "spawn pnpm ENOENT"))
        assertFalse(looksLikeMissingPackageManager("not-found", "ERR_PNPM_FETCH_404 not found"))
        assertFalse(looksLikeMissingPackageManager("unknown", "git: not found"))
        assertFalse(looksLikeMissingPackageManager("unknown", null))
    }

    @Test fun aPackageToolThatRejectsItsOwnFlagIsAToolFailureAndNotAMissingPackage() {
        // Captured with 0.2.1-rc.2 on a machine whose npm 12 rejects the --config.fetch-retries flag the harness
        // passes: refused, unclassified, with npm's own words. Looking again changes nothing, and it is not "no pnpm".
        val verdict = marketCheck(review, review.presented!!, inspected("inspect-tool-failed"))
        verdict as MarketCheck.ToolFailed
        assertEquals("unknown", verdict.problem)
        assertTrue(verdict.reason!!.contains("EUNKNOWNCONFIG"))
        assertTrue(verdict.reason!!.contains("--config.fetch-retries"))
    }

    @Test fun theOtherRefusalsKeepTheirOwnVerdict() {
        // Not found, a bad spec and a network failure carry a code of their own: they are about the package or the network.
        assertTrue(marketCheck(review, review.presented!!, inspected("inspect-registry")) is MarketCheck.Refused)
        assertTrue(marketCheck(review, review.presented!!, inspected("inspect-bad-spec")) is MarketCheck.Refused)
        val network = PluginInspection(status = "refused", problem = "network", reason = "ERR_PNPM_META_FETCH_FAIL connect ETIMEDOUT")
        assertEquals(MarketCheck.Refused("network", "ERR_PNPM_META_FETCH_FAIL connect ETIMEDOUT"), marketCheck(review, review.presented!!, network))
        // No words at all is not enough to blame the tool.
        assertTrue(marketCheck(review, review.presented!!, PluginInspection(status = "refused", problem = "unknown")) is MarketCheck.Refused)
        assertFalse(looksLikeToolFailure("unknown", " "))
        assertTrue(looksLikeToolFailure(null, "boom"))
        assertFalse(looksLikeToolFailure("not-found", "ERR_PNPM_FETCH_404"))
    }

    @Test fun aMissingPnpmIsStillToldApartFromAToolFailure() {
        assertEquals(MarketCheck.NoPackageManager, marketCheck(review, review.presented!!, inspected("inspect-no-pnpm")))
    }

    @Test fun aHostileNameInTheHostsAnswerIsCleaned() {
        val answer = PluginInspection(status = "accepted", kind = "registry", name = "gpj‮.exe", version = "1.0.0", bundle = true)
        assertEquals(MarketCheck.Mismatch("gpj.exe", "1.0.0"), marketCheck(review, review.presented!!, answer))
    }
}
