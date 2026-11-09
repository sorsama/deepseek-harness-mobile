package com.labteto.dshmobile.core.usage

import com.labteto.dshmobile.core.wire.dto.CurrencyWallet
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageLocalizationTest {
    private val now = 1_800_000_000_000L
    @Test fun translatedLabelsAreUsedInsteadOfEnglishFallback() {
        val row = UsageRow("codex", listOf(RemainingWindow("session",25,null),RemainingWindow("weekly",58,now+54*3_600_000L)),sampledAt=now)
        assertEquals("5h:25% · sem:58% · 2j 6h", usageFooter(row,now,false,UsageLabels("5h","sem","j","h","min")))
    }
    @Test fun chineseHoursAreDurationUnits() {
        val row=UsageRow("codex",listOf(RemainingWindow("session",25,null),RemainingWindow("weekly",58,now+54*3_600_000L)),sampledAt=now)
        assertEquals("5小时:25% · 周:58% · 2天 6小时",usageFooter(row,now,false,UsageLabels("5小时","周","天","小时","分")))
    }
    @Test fun decimalCommaPreservesBalancePrecisionAndCurrency() {
        val row=UsageRow("deepseek-account",wallets=listOf(CurrencyWallet("USD","54.12")),sampledAt=now)
        assertEquals("54,12$", usageFooter(row,now,false,UsageLabels(decimalSeparator=",")))
    }
    @Test fun localizedDigitsApplyToPercentAndCountdown() {
        val row=UsageRow("codex",listOf(RemainingWindow("weekly",58,now+54*3_600_000L)),sampledAt=now)
        assertEquals("أسب:٥٨% · ٢ي ٦س",usageFooter(row,now,false,UsageLabels(week="أسب",day="ي",hour="س",digits="٠١٢٣٤٥٦٧٨٩")))
    }
}
