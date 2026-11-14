package com.labteto.dshmobile.core.usage

import com.labteto.dshmobile.core.wire.RpcError
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class UsageRepositoryTest {
    private fun roster(vararg names: String) = SubscriptionRoster(names.associateWith { SubscriptionProviderStatus(listOf(SubscriptionAccount("selector-$it", true))) })
    private fun quota(percent: Double = 40.0) = ProviderQuota(true, listOf(QuotaWindow("weekly", percent)), QuotaObservation(1000, false))
    private fun ready(vararg usd: String) = AccountWallet("ready", usd.map { CurrencyWallet("USD", it) })

    /** A host that has a subscription service for codex and no signed-in DeepSeek account. */
    private open class Source : UsageSource {
        var reads = 0
        var wallets = 0
        val requested = mutableListOf<String>()
        override suspend fun status(): UsageFetch<SubscriptionRoster> { reads++; return UsageFetch.Ok(SubscriptionRoster(mapOf("codex" to SubscriptionProviderStatus(listOf(SubscriptionAccount("a", true)))))) }
        override suspend fun quota(provider: String, account: String): UsageFetch<ProviderQuota> { requested.add(provider); return UsageFetch.Ok(ProviderQuota(true, listOf(QuotaWindow("weekly", 40.0)), QuotaObservation(1000, false))) }
        override suspend fun wallet(): UsageFetch<AccountWallet?> { wallets++; return UsageFetch.Ok(null) }
    }

    /** A harness as it ships today: no `subscriptions-auth` at all. */
    private class NoSubscriptions(private val account: AccountWallet? = null) : UsageSource {
        var statusCalls = 0
        var walletCalls = 0
        override suspend fun status(): UsageFetch<SubscriptionRoster> { statusCalls++; return UsageFetch.Unsupported }
        override suspend fun quota(provider: String, account: String): UsageFetch<ProviderQuota> = error("never reached without a roster")
        override suspend fun wallet(): UsageFetch<AccountWallet?> { walletCalls++; return UsageFetch.Ok(account) }
    }

    @Test fun currentSubscriptionReadsOnlyItsOwnProvider() = runTest {
        val r = UsageRepository { 1000 }
        val s = object : Source() {
            override suspend fun status(): UsageFetch<SubscriptionRoster> { reads++; return UsageFetch.Ok(roster("codex", "claude")) }
        }
        r.bind("h"); r.refresh("h", s, "codex")
        assertEquals(listOf("codex"), s.requested)
        assertEquals(2, s.reads)
        assertEquals(listOf("codex"), r.state.value.rows.map { it.provider })
        assertEquals(true, r.state.value.subscriptions)
    }

    @Test fun balanceIsReadWhateverTheSessionProviderIs() = runTest {
        val r = UsageRepository { 1000 }
        val s = NoSubscriptions(ready("12.50"))
        r.bind("h"); r.refresh("h", s, "opencode-go")
        assertEquals(1, s.walletCalls)
        val balance = r.state.value.balance as BalanceState.Ready
        assertEquals("12.50", balance.row.wallets.single().balance)
        assertEquals(1000L, balance.row.sampledAt)
    }

    @Test fun signedOutAccountHidesTheBalance() = runTest {
        val r = UsageRepository { 1000 }
        r.bind("h"); r.refresh("h", NoSubscriptions(null), "opencode-go")
        assertEquals(BalanceState.Hidden, r.state.value.balance)
    }

    @Test fun failedLookupIsShownAsFailedNotAsZero() = runTest {
        val r = UsageRepository { 1000 }
        r.bind("h"); r.refresh("h", NoSubscriptions(AccountWallet("failed")), "deepseek-account")
        assertEquals(BalanceState.Failed, r.state.value.balance)
    }

    @Test fun readyWithNothingUsableIsHiddenNotAnEmptyCard() = runTest {
        val r = UsageRepository { 1000 }
        r.bind("h"); r.refresh("h", NoSubscriptions(AccountWallet("ready", listOf(CurrencyWallet("USD", "NaN")))), "deepseek-account")
        assertEquals(BalanceState.Hidden, r.state.value.balance)
    }

    @Test fun exponentBalanceIsKeptExactAndBonusStaysApart() = runTest {
        val r = UsageRepository { 1000 }
        val wallet = AccountWallet("ready", listOf(CurrencyWallet("USD", "0E-16"), CurrencyWallet("CNY", "5.0000000000000000")), listOf(CurrencyWallet("USD", "1.5E1")))
        r.bind("h"); r.refresh("h", NoSubscriptions(wallet), "deepseek-account")
        val row = (r.state.value.balance as BalanceState.Ready).row
        assertEquals(listOf("0E-16", "5.0000000000000000"), row.wallets.map { it.balance })
        assertEquals(listOf("1.5E1"), row.bonusWallets.map { it.balance })
    }

    @Test fun missingSubscriptionServiceIsRememberedAndNeverAskedAgain() = runTest {
        val r = UsageRepository { 1000 }
        val s = NoSubscriptions()
        r.bind("h", "host-1"); r.refresh("h", s, "codex")
        assertEquals(1, s.statusCalls)
        assertEquals(false, r.state.value.subscriptions)
        assertTrue(r.state.value.rows.isEmpty())
        r.refresh("h", s, "codex", recheck = true)
        r.refresh("h", s, "codex", includeAll = true, recheck = true)
        assertEquals("no second probe on the same host connection", 1, s.statusCalls)
        assertEquals("the balance is still polled", 3, s.walletCalls)
        assertFalse(r.state.value.loading)
    }

    @Test fun theEvidenceSurvivesASessionOrProviderSwitchOnTheSameHost() = runTest {
        val r = UsageRepository { 1000 }
        val s = NoSubscriptions(ready("3"))
        r.bind("host|s1|codex", "host"); r.refresh("host|s1|codex", s, "codex")
        r.bind("host|s2|opencode-go", "host")
        assertEquals("balance carries over without a blank flash", BalanceState.Ready::class, r.state.value.balance::class)
        assertEquals(false, r.state.value.subscriptions)
        r.refresh("host|s2|opencode-go", s, "opencode-go")
        assertEquals(1, s.statusCalls)
    }

    @Test fun aNewHostConnectionProbesAgainAndForgetsTheBalance() = runTest {
        val r = UsageRepository { 1000 }
        val first = NoSubscriptions(ready("3"))
        r.bind("a", "host-a"); r.refresh("a", first, "codex")
        r.bind("b", "host-b")
        assertEquals(UsageSnapshot(), r.state.value)
        // A future harness that adds the call lights the section up on its own.
        val later = Source()
        r.refresh("b", later, "codex")
        assertEquals(true, r.state.value.subscriptions)
        assertEquals(listOf("codex"), r.state.value.rows.map { it.provider })
    }

    @Test fun aTransientStatusFailureProvesNothingAboutTheHost() = runTest {
        val r = UsageRepository { 1000 }
        var failing = true
        val s = object : Source() {
            override suspend fun status(): UsageFetch<SubscriptionRoster> = if (failing) UsageFetch.Failed else super.status()
        }
        r.bind("h"); r.refresh("h", s, "codex")
        assertNull(r.state.value.subscriptions)
        failing = false
        r.refresh("h", s, "codex", recheck = true)
        assertEquals(true, r.state.value.subscriptions)
        assertEquals(1, r.state.value.rows.size)
    }

    @Test fun aProviderTheHostNeverListedGetsNoRowAndNoQuotaCall() = runTest {
        val r = UsageRepository { 1000 }
        val s = Source()
        r.bind("h"); r.refresh("h", s, "opencode-go")
        assertTrue(s.requested.isEmpty())
        assertTrue(r.state.value.rows.isEmpty())
        assertEquals("the host answered, so the section stays available", true, r.state.value.subscriptions)
    }

    @Test fun walletServiceMissingIsRememberedToo() = runTest {
        val r = UsageRepository { 1000 }
        var calls = 0
        val s = object : Source() {
            override suspend fun wallet(): UsageFetch<AccountWallet?> { calls++; return UsageFetch.Unsupported }
        }
        r.bind("h", "host"); r.refresh("h", s, "codex"); r.refresh("h", s, "codex", recheck = true)
        assertEquals(1, calls)
        assertEquals(BalanceState.Hidden, r.state.value.balance)
    }

    @Test fun aBlipKeepsTheLastBalanceTheReaderSaw() = runTest {
        var clock = 1000L
        val r = UsageRepository { clock }
        var answer: UsageFetch<AccountWallet?> = UsageFetch.Ok(ready("8"))
        val s = object : Source() { override suspend fun wallet(): UsageFetch<AccountWallet?> = answer }
        r.bind("h"); r.refresh("h", s, "codex")
        answer = UsageFetch.Failed; clock = 100_000
        r.refresh("h", s, "codex", recheck = true)
        val kept = r.state.value.balance as BalanceState.Ready
        assertEquals("the old timestamp stays so the card can call it outdated", 1000L, kept.row.sampledAt)
    }

    @Test fun walletDoesNotNeedTheSubscriptionRosterToBeKnown() = runTest {
        val r = UsageRepository { 1000 }
        val s = object : Source() { override suspend fun wallet(): UsageFetch<AccountWallet?> = UsageFetch.Ok(ready("1")) }
        r.bind("h"); r.refresh("h", s, "deepseek-account")
        assertTrue(r.state.value.balance is BalanceState.Ready)
    }

    @Test fun ttlCoalescesRefresh() = runTest {
        val r = UsageRepository { 1000 }; val s = Source()
        r.bind("h1"); r.refresh("h1", s, "codex"); val reads = s.reads
        r.refresh("h1", s, "codex"); assertEquals(reads, s.reads)
        assertEquals(60, r.state.value.rows.first { it.provider == "codex" }.windows.single().percent)
    }

    @Test fun changedRosterDropsQuota() = runTest {
        val r = UsageRepository { 1000 }
        val s = object : Source() {
            override suspend fun status(): UsageFetch<SubscriptionRoster> { reads++; return UsageFetch.Ok(SubscriptionRoster(mapOf("codex" to SubscriptionProviderStatus(listOf(SubscriptionAccount(if (reads == 1) "a" else "b", true)))))) }
        }
        r.bind("h"); r.refresh("h", s, "codex")
        assertTrue("the account changed mid-read, so no number is shown", r.state.value.rows.isEmpty())
    }

    @Test fun oldHostResultCannotOverwriteNewHostPublication() = runTest {
        val gate = CompletableDeferred<Unit>(); val r = UsageRepository { 1000 }
        val old = object : Source() { override suspend fun quota(provider: String, account: String): UsageFetch<ProviderQuota> { gate.await(); return super.quota(provider, account) } }
        val newer = object : Source() { override suspend fun quota(provider: String, account: String) = UsageFetch.Ok(ProviderQuota(true, listOf(QuotaWindow("weekly", 10.0)), QuotaObservation(1000, false))) }
        r.bind("old"); val oldJob = launch { r.refresh("old", old, "codex") }; testScheduler.runCurrent()
        r.bind("new"); val newJob = launch { r.refresh("new", newer, "codex") }; testScheduler.runCurrent()
        gate.complete(Unit); oldJob.join(); newJob.join()
        assertEquals(90, r.state.value.rows.single { it.provider == "codex" }.windows.single().percent)
        assertFalse(r.state.value.loading)
        r.refresh("old", old, "codex", recheck = true)
        assertEquals(90, r.state.value.rows.single { it.provider == "codex" }.windows.single().percent)
    }

    @Test fun anOldHostCannotMarkTheNewHostAsMissingTheService() = runTest {
        val gate = CompletableDeferred<Unit>(); val r = UsageRepository { 1000 }
        val old = object : Source() { override suspend fun status(): UsageFetch<SubscriptionRoster> { gate.await(); return UsageFetch.Unsupported } }
        r.bind("old", "host-old"); val oldJob = launch { r.refresh("old", old, "codex") }; testScheduler.runCurrent()
        r.bind("new", "host-new"); val newJob = launch { r.refresh("new", Source(), "codex") }; testScheduler.runCurrent()
        gate.complete(Unit); oldJob.join(); newJob.join()
        assertEquals(true, r.state.value.subscriptions)
        assertEquals(1, r.state.value.rows.size)
    }

    @Test fun manualRecheckBypassesOnlyLocalTtl() = runTest {
        val r = UsageRepository { 1000 }; val s = Source(); r.bind("h")
        r.refresh("h", s, "codex"); assertEquals(2, s.reads)
        r.refresh("h", s, "codex", recheck = false); assertEquals(2, s.reads)
        r.refresh("h", s, "codex", recheck = true); assertEquals(4, s.reads)
    }

    @Test fun explicitAllCoverageReadsWhateverTheHostListsNotAFixedFive() = runTest {
        val listed = listOf("codex", "claude", "some-future-provider")
        val s = object : Source() {
            override suspend fun status(): UsageFetch<SubscriptionRoster> { reads++; return UsageFetch.Ok(roster(*listed.toTypedArray())) }
            override suspend fun quota(provider: String, account: String): UsageFetch<ProviderQuota> {
                assertEquals("selector-$provider", account); requested.add(provider)
                return UsageFetch.Ok(ProviderQuota(true, listOf(QuotaWindow("weekly", 40.0)), QuotaObservation(1000, false)))
            }
            override suspend fun wallet(): UsageFetch<AccountWallet?> { wallets++; return UsageFetch.Ok(AccountWallet("ready", listOf(CurrencyWallet("USD", "0")))) }
        }
        val r = UsageRepository { 1000 }; r.bind("h"); r.refresh("h", s, provider = "codex", includeAll = true)
        assertEquals(listed.toSet(), s.requested.toSet())
        assertEquals(1, s.wallets); assertEquals(2, s.reads)
        assertEquals(listed.toSet(), r.state.value.rows.map { it.provider }.toSet())
        r.refresh("h", s, provider = "codex", includeAll = true)
        assertEquals(3, s.requested.size); assertEquals(1, s.wallets)
    }

    @Test fun rowsWithoutWindowsAreNeverPublished() = runTest {
        val s = object : Source() { override suspend fun quota(provider: String, account: String): UsageFetch<ProviderQuota> = UsageFetch.Ok(ProviderQuota(false)) }
        val r = UsageRepository { 1000 }; r.bind("h"); r.refresh("h", s, "codex")
        assertTrue(r.state.value.rows.isEmpty())
    }

    @Test fun exceptionEndsLoadingWithoutRawErrors() = runTest {
        val r = UsageRepository { 1000 }; r.bind("h")
        r.refresh("h", object : Source() { override suspend fun status(): UsageFetch<SubscriptionRoster> = error("secret body") }, "codex")
        assertFalse(r.state.value.loading)
    }

    @Test fun onlyMissingMethodErrorsCountAsAMissingService() {
        fun err(code: String) = RpcResult.Err(RpcError(code, "boom", JsonObject(emptyMap())))
        assertEquals(UsageFetch.Unsupported, err("capability-unavailable").toUsageFetch())
        assertEquals(UsageFetch.Unsupported, err("gateway/invocation-unavailable").toUsageFetch())
        assertEquals(UsageFetch.Unsupported, err("method-not-found").toUsageFetch())
        assertEquals(UsageFetch.Unsupported, err("-32601").toUsageFetch())
        assertEquals("a refusal says nothing about the host's methods", UsageFetch.Failed, err("forbidden").toUsageFetch())
        assertEquals(UsageFetch.Failed, err("unauthenticated").toUsageFetch())
        assertEquals(UsageFetch.Failed, err("internal").toUsageFetch())
        assertEquals(UsageFetch.Ok(5), RpcResult.Ok(5).toUsageFetch())
    }
}
