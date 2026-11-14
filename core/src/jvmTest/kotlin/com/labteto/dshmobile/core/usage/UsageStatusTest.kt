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
    @Test fun anyProviderTheHostReportsGetsItsWindowsAndNoneIsGatedByAList() { assertEquals(99,quotaRow("opencode-go",ProviderQuota(true,listOf(QuotaWindow("weekly",1.0))),now).windows.single().percent) }
    @Test fun unsupportedProviderStillShowsNoWindowsWhateverItReports() { assertTrue(quotaRow("pool-gpt",ProviderQuota(false,listOf(QuotaWindow("weekly",1.0))),now).windows.isEmpty()) }

    // Balance strings use the grammar the harness documents (the one Platform hands to big.js).
    private fun shown(balance:String,currency:String="USD")=usageFooter(walletRow("deepseek-account",AccountWallet("ready",listOf(CurrencyWallet(currency,balance))),now),now,true)
    @Test fun exponentZeroIsARealZeroNotAnInvalidBalance() { assertEquals("0$",shown("0E-16")) }
    @Test fun trailingZerosAreCutNotRounded() { assertEquals("5$",shown("5.0000000000000000")); assertEquals("5.99$",shown("5.999999999")) }
    @Test fun missingIntegerOrFractionPartIsStillADecimal() { assertEquals("0.50$",shown(".5")); assertEquals("7$",shown("7.")) }
    @Test fun exponentShiftsTheDecimalPointExactly() {
        assertEquals("1500$",shown("1.5E3")); assertEquals("0.12$",shown("12e-2")); assertEquals("0.03$",shown("3E-2"))
        assertEquals("0.12$",shown("1.2345E-1")); assertEquals("123456789.12$",shown("123456789.12"))
    }
    @Test fun longDecimalsNeverTouchAFloat() { assertEquals("9007199254740993.01$",shown("9007199254740993.019")) }
    @Test fun subCentExponentIsNotZero() { assertEquals("<0.01$",shown("5E-3")) }
    @Test fun nanInfinityAndGarbageAreRejected() {
        listOf("NaN","Infinity","-Infinity","","abc","1e","1..2","1,5","0x10","1e999","."," 5","5 ","--1").forEach { assertNull(it,parseDecimal(it)) }
        assertEquals(BalanceLike(false,"0","0000000000000000"),parseDecimal("0E-16")!!.let { BalanceLike(it.negative,it.whole,it.fraction) })
    }
    @Test fun negativeBalanceKeepsItsSign() { assertEquals("-1.50$",shown("-1.5")); assertEquals("0$",shown("-0")) }
    @Test fun chineseYuanIsFormattedTheSameWay() { assertEquals("10.20¥",shown("10.2","CNY")) }
    @Test fun bonusWalletsAreCarriedApartFromTheBalance() {
        val row=walletRow("deepseek-account",AccountWallet("ready",listOf(CurrencyWallet("USD","3")),listOf(CurrencyWallet("USD","1E1"),CurrencyWallet("EUR","9"))),now)
        assertEquals(listOf("3"),row.wallets.map{it.balance}); assertEquals(listOf("1E1"),row.bonusWallets.map{it.balance})
        assertEquals("3$",usageFooter(row,now,true))
        assertEquals("10$",walletText(row.bonusWallets.single(),UsageLabels()))
    }
    @Test fun failedOrForeignWalletRowsCarryNothing() {
        assertTrue(walletRow("deepseek-account",AccountWallet("failed",listOf(CurrencyWallet("USD","1"))),now).wallets.isEmpty())
        assertNull(walletRow("deepseek-account",null,now).sampledAt)
    }

    @Test fun compactCountCutsInsteadOfRounding() {
        val labels=UsageLabels()
        assertEquals("0",compactCount(0,labels)); assertEquals("842",compactCount(842,labels)); assertEquals("999",compactCount(999,labels))
        assertEquals("1k",compactCount(1_000,labels)); assertEquals("12.4k",compactCount(12_450,labels)); assertEquals("999.9k",compactCount(999_999,labels))
        assertEquals("1M",compactCount(1_000_000,labels)); assertEquals("3.2M",compactCount(3_299_999,labels)); assertEquals("256.2M",compactCount(256_200_000,labels))
        assertEquals("1.5B",compactCount(1_500_000_000,labels)); assertEquals("0",compactCount(-5,labels))
    }
    @Test fun compactCountFollowsTheReadersDigitsAndDecimalMark() {
        assertEquals("12,4k",compactCount(12_450,UsageLabels(decimalSeparator=",")))
        assertEquals("١٢٫٤k",compactCount(12_450,UsageLabels(decimalSeparator="٫",digits="٠١٢٣٤٥٦٧٨٩")))
        assertEquals("٨٢%",percentText(82,UsageLabels(digits="٠١٢٣٤٥٦٧٨٩")))
    }
    private data class BalanceLike(val negative:Boolean,val whole:String,val fraction:String)
}
