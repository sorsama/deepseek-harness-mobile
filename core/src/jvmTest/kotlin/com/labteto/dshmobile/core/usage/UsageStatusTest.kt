package com.labteto.dshmobile.core.usage

import com.labteto.dshmobile.core.wire.dto.*
import org.junit.Assert.*
import org.junit.Test

class UsageStatusTest {
    private val now = 1_800_000_000_000L
    @Test fun sampleAgeCrossingFiveMinutesHidesFooter() {
        val row=quotaRow("codex",ProviderQuota(true,listOf(QuotaWindow("weekly",42.0)),QuotaObservation(now,false)),now)
        assertEquals("нед:58%",usageFooter(row,now,true))
        assertEquals("",usageFooter(row,now+301_000,true))
    }
    @Test fun futureSampleCannotLookFresh() {
        val row=quotaRow("codex",ProviderQuota(true,listOf(QuotaWindow("weekly",42.0)),QuotaObservation(now+1,false)),now)
        assertTrue(row.stale); assertNull(row.sampledAt); assertEquals("",usageFooter(row,now,true))
    }
    @Test fun ambiguousDefaultNotCaller() { assertNull(defaultAccount(SubscriptionProviderStatus(listOf(SubscriptionAccount("a",true),SubscriptionAccount("b"))))) }
    @Test fun missingObservationCannotLookFresh() { assertTrue(quotaRow("codex",ProviderQuota(true),now).stale) }
    @Test fun scopedWindowsRemainInCard() { assertEquals("Opus",quotaRow("antigravity",ProviderQuota(true,listOf(QuotaWindow("other",25.0,scope="Opus"))),now).windows.single().scope) }
    @Test fun subcentIsNotZero() { assertEquals("<0.01$",usageFooter(walletRow("deepseek-account",AccountWallet("ready",listOf(CurrencyWallet("USD","0.005"))),now),now,true)) }
    @Test fun remainingNotUsed() { assertEquals(25, quotaRow("codex", ProviderQuota(true, listOf(QuotaWindow("session",75.0))), now).windows.single().percent) }
    @Test fun invalidNeverBecomesZeroAndScopeNotFooter() { val row=quotaRow("codex",ProviderQuota(true,listOf(QuotaWindow("weekly",Double.NaN),QuotaWindow("session",12.0,scope="Opus")),QuotaObservation(now,false)),now);assertEquals(1,row.windows.size);assertEquals("",usageFooter(row,now,true)) }
    @Test fun unsupportedHasNoWindows() { assertTrue(quotaRow("copilot",ProviderQuota(false,listOf(QuotaWindow("weekly",0.0))),now).windows.isEmpty()) }
    @Test fun staleNotCurrent() { assertTrue(quotaRow("codex",ProviderQuota(true,observation=QuotaObservation(now-100_000,true)),now).stale) }
    @Test fun onlyUniqueExplicitDefault() { assertEquals("opaque",defaultAccount(SubscriptionProviderStatus(listOf(SubscriptionAccount("opaque",true))))); assertNull(defaultAccount(SubscriptionProviderStatus(listOf(SubscriptionAccount("a"),SubscriptionAccount("b"))))) }
    @Test fun duplicateDefaultUnknown() { assertNull(defaultAccount(SubscriptionProviderStatus(listOf(SubscriptionAccount("a",true),SubscriptionAccount("b",true))))) }
    @Test fun zeroWalletIsRealAndOnlyAccountProvider() { val wallet=AccountWallet("ready",listOf(CurrencyWallet("USD","0"))); assertEquals("0$",usageFooter(walletRow("deepseek-account",wallet,now),now,true)); assertTrue(walletRow("deepseek",wallet,now).wallets.isEmpty()) }
    @Test fun currenciesNeverSummed() { assertEquals("54$ · 10¥",usageFooter(walletRow("deepseek-account",AccountWallet("ready",listOf(CurrencyWallet("USD","54.00"),CurrencyWallet("CNY","10"))),now),now,true)) }
    @Test fun footerExactAndResetMilliseconds() { val row=quotaRow("codex",ProviderQuota(true,listOf(QuotaWindow("session",75.0),QuotaWindow("weekly",42.0,now+54*3600000L)),QuotaObservation(now,false)),now); assertEquals("5ч:25% · нед:58% · 2д 6ч",usageFooter(row,now,true)) }
    @Test fun passedResetNotFreshAllowance() { assertTrue(quotaRow("codex",ProviderQuota(true,listOf(QuotaWindow("weekly",75.0,now-1))),now).windows.isEmpty()) }
    @Test fun noResetNoInventedClock() { assertEquals("нед:25%",usageFooter(quotaRow("codex",ProviderQuota(true,listOf(QuotaWindow("weekly",75.0)),QuotaObservation(now,false)),now),now,true)) }
    @Test fun unknownPoolNeverHeuristic() { assertTrue(quotaRow("pool-gpt",ProviderQuota(true,listOf(QuotaWindow("weekly",1.0))),now).windows.isEmpty()) }
}
