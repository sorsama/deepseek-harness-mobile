package com.labteto.dshmobile.ui.screens.main

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.core.usage.*
import com.labteto.dshmobile.core.wire.dto.*
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.File

/** Synthetic data in the actual production card/footer, not live account or Store e2e. */
class UsageCardTest {
    @get:Rule val compose=createComposeRule()
    private val now=1_800_000_000_000L
    private val row=UsageRow("codex",listOf(RemainingWindow("session",25,now+3600000),RemainingWindow("weekly",58,now+54*3600000)),sampledAt=now)
    private val stats=SessionStatsView(turns=2,steps=3,llmMs=1000,toolMs=2000)
    private val tokens=TokenUsageView(uncachedInputTokens=123,outputTokens=45)
    private fun capture(name:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null),"$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun quotaOnlyInExpandedTokenTextAndSecondTapCollapses() {
        compose.setContent { DshTheme { StatsFooter(stats,tokens,quotaText="нед:17% · 2д 23ч") } }
        compose.onNodeWithText("нед:17%",substring=true).assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).onFirst().performClick()
        compose.onNode(hasText("123",substring=true) and hasText("45",substring=true) and hasText("нед:17% · 2д 23ч",substring=true)).assertExists().assert(hasNoClickAction())
        capture("usage-refined-expanded")
        compose.onAllNodes(hasClickAction()).onFirst().performClick()
        compose.onNodeWithText("нед:17%",substring=true).assertDoesNotExist()
    }
    @Test fun balanceSharesTokenTextAndQuotaWithoutStatsStaysHidden() {
        compose.setContent { DshTheme { Column { StatsFooter(stats,tokens,quotaText="54$");StatsFooter(null,null,quotaText="999$") } } }
        compose.onNodeWithText("999$",substring=true).assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).onFirst().performClick()
        compose.onNode(hasText("123",substring=true) and hasText("54$",substring=true)).assertExists()
    }
    @Test fun productionCardBarsRefreshAndAllExpansionWork() {
        var refreshed=0
        compose.setContent { DshTheme { Column(Modifier.fillMaxWidth().padding(16.dp)) { UsageCard(UsageSnapshot(listOf(row,UsageRow("zai",listOf(RemainingWindow("weekly",50,null)))),subscriptions=true,checkedAt=now),"codex",now){refreshed++} } } }
        capture("usage-refined-card")
        compose.onNodeWithTag("quota-window-0-0").assertExists()
        compose.onNodeWithTag("quota-bar-0-0").assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(.25f,0f..1f))
        compose.onNodeWithTag("quota-refresh").performClick();compose.runOnIdle {assertEquals(1,refreshed)}
        compose.onNodeWithTag("quota-all").performClick();compose.onNodeWithText("zai").assertExists()
    }
    @Test fun unscopedOtherAndScopedDuplicateWindowsRetained() {
        val other=UsageRow("grok",listOf(RemainingWindow("other",37,null),RemainingWindow("weekly",42,null,"Opus"),RemainingWindow("weekly",15,null,"Sonnet")),sampledAt=now)
        compose.setContent { DshTheme { UsageCard(UsageSnapshot(listOf(other)),"grok",now){} } }
        compose.onNodeWithText("Other limit").assertExists();compose.onNodeWithText("37%").assertExists()
        compose.onNodeWithText("Opus").assertExists();compose.onNodeWithText("42%").assertExists()
        compose.onNodeWithText("Sonnet").assertExists();compose.onNodeWithText("15%").assertExists()
    }
    @Test fun chineseDurationResourcesUseHours() = localizedCard("zh-CN", false, "用量", "5小时", "小时")
    @Test fun urduResourcesAndRuntimeDigitsAgree() = localizedCard("ur", true, "استعمال", "5 گھنٹے", "گھ")
    @Test fun arabicResourcesRenderAtLargeFontRtl() = localizedCard("ar", true, "الاستخدام", "٥ س", "س")
    private fun localizedCard(language: String, rtl: Boolean, title: String, session: String, hour: String) {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val locale=java.util.Locale.forLanguageTag(language)
        val config=android.content.res.Configuration(base.resources.configuration).apply { setLocale(locale) }
        val localized=base.createConfigurationContext(config)
        compose.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides localized,
                androidx.compose.ui.platform.LocalConfiguration provides config,
                LocalLayoutDirection provides if(rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density,1.5f),
            ) { DshTheme { UsageCard(UsageSnapshot(listOf(row),subscriptions=true),"codex",now){} } }
        }
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText(session).assertExists()
        val symbols=java.text.DecimalFormatSymbols.getInstance(locale)
        assertEquals(session.first().toString(), (symbols.zeroDigit + 5).toString())
        val percent=("25").map { symbols.zeroDigit+(it-'0') }.joinToString("")+"%"
        compose.onNodeWithText(percent).assertExists()
        val countdown = when(language) {
            "zh-CN" -> "2天 6小时"
            "ur" -> "2دن 6گھ"
            else -> "٢ي ٦س"
        }
        compose.onNodeWithText(countdown,substring=true).assertExists()
        compose.onNodeWithTag("quota-refresh").assertHasClickAction()
    }
    @Test fun productionComponentsSupportLargeFontRtlAndNeverShowUnknownOrZero() {
        compose.setContent { val density=LocalDensity.current.density;CompositionLocalProvider(LocalDensity provides Density(density,1.5f),LocalLayoutDirection provides LayoutDirection.Rtl) { DshTheme { Column { UsageCard(UsageSnapshot(),"zai",now){};StatsFooter(stats,TokenUsageView(uncachedInputTokens=256_200_000,outputTokens=336_300),quotaText="5ч:25% · нед:17% · 2д 23ч") } } } }
        compose.onNodeWithTag("quota-card").assertExists();compose.onNodeWithText("0%").assertDoesNotExist()
        // Nothing to recheck and nothing to say about quotas: no dead refresh button, no "unknown" line.
        compose.onNodeWithTag("quota-refresh").assertDoesNotExist();compose.onNodeWithTag("quota-all").assertDoesNotExist()
        compose.onNodeWithText("No quota data").assertDoesNotExist();compose.onNodeWithTag("usage-empty").assertExists()
        compose.onAllNodes(hasClickAction()).onLast().performClick()
        compose.onNodeWithText("нед:17%",substring=true).assertExists()
        capture("usage-refined-rtl-large")
    }

    // ---- the card no longer depends on a subscription service: tokens, balance and the limit line are the floor.
    private val opencode="OpenCode Go · DeepSeek V4 Flash"
    private val spent=TokenUsageView(uncachedInputTokens=12_000,outputTokens=3_100,cacheReadTokens=400)
    @Test fun sessionRowNamesTheRouteAndShowsCompactTokensForAnyProvider() {
        compose.setContent { DshTheme { UsageCard(UsageSnapshot(),"opencode-go",now,route=opencode,tokens=spent){} } }
        compose.onNodeWithText(opencode).assertExists()
        compose.onNodeWithTag("usage-input").assert(hasText("12.4k",substring=true))
        compose.onNodeWithTag("usage-output").assert(hasText("3.1k",substring=true))
        compose.onNodeWithTag("usage-cache").assert(hasText("3%",substring=true))
        compose.onNodeWithTag("usage-total").assert(hasText("15.5k",substring=true))
        compose.onNodeWithTag("usage-empty").assertDoesNotExist()
        compose.onNodeWithText("No quota data").assertDoesNotExist()
        compose.onNodeWithTag("quota-refresh").assertDoesNotExist();compose.onNodeWithTag("quota-all").assertDoesNotExist()
        capture("usage-session-only")
    }
    @Test fun cacheRowStaysHiddenWhenTheProviderReportedNoCacheReads() {
        compose.setContent { DshTheme { UsageCard(UsageSnapshot(),"opencode-go",now,tokens=TokenUsageView(uncachedInputTokens=900,outputTokens=100)){} } }
        compose.onNodeWithTag("usage-total").assert(hasText("1k",substring=true))
        compose.onNodeWithTag("usage-cache").assertDoesNotExist()
    }
    @Test fun aSessionWithNoTokensYetSaysSoInsteadOfShowingZeros() {
        compose.setContent { DshTheme { UsageCard(UsageSnapshot(),"codex",now,tokens=TokenUsageView()){} } }
        compose.onNodeWithTag("usage-empty").assertExists();compose.onNodeWithTag("usage-input").assertDoesNotExist()
    }
    @Test fun balanceRowShowsExactWalletsAndKeepsBonusApart() {
        val ready=BalanceState.Ready(UsageRow("deepseek-account",wallets=listOf(CurrencyWallet("USD","0E-16"),CurrencyWallet("CNY","54.129")),bonusWallets=listOf(CurrencyWallet("USD","1.5E1")),sampledAt=now))
        compose.setContent { DshTheme { UsageCard(UsageSnapshot(balance=ready),"opencode-go",now,route=opencode,tokens=spent){} } }
        compose.onNodeWithTag("usage-balance").assertExists()
        compose.onNodeWithText("DeepSeek balance").assertExists()
        compose.onNodeWithText("0$ · 54.12¥").assertExists()
        compose.onNodeWithText("Bonus: 15$").assertExists()
        compose.onNodeWithTag("quota-refresh").assertHasClickAction()
        capture("usage-balance")
    }
    @Test fun failedBalanceLookupSaysSoBriefly() {
        compose.setContent { DshTheme { UsageCard(UsageSnapshot(balance=BalanceState.Failed),"opencode-go",now,tokens=spent){} } }
        compose.onNodeWithTag("usage-balance-failed").assertExists();compose.onNodeWithText("Couldn't load the balance").assertExists()
    }
    @Test fun signedOutBalanceLeavesNoRowAtAll() {
        compose.setContent { DshTheme { UsageCard(UsageSnapshot(balance=BalanceState.Hidden),"opencode-go",now,tokens=spent){} } }
        compose.onNodeWithTag("usage-balance").assertDoesNotExist();compose.onNodeWithTag("usage-balance-failed").assertDoesNotExist()
        compose.onNodeWithText("DeepSeek balance").assertDoesNotExist()
    }
    @Test fun limitLineNamesQuotaAndRateLimitAndHidesForAnotherProvider() {
        var limit by mutableStateOf<LimitHit?>(LimitHit(LimitKind.QUOTA,"opencode-go"))
        var provider by mutableStateOf("opencode-go")
        compose.setContent { DshTheme { UsageCard(UsageSnapshot(),provider,now,tokens=spent,limit=limit){} } }
        compose.onNodeWithTag("usage-limit").assertExists();compose.onNodeWithText("Usage limit reached").assertExists()
        capture("usage-limit-reached")
        compose.runOnIdle { limit=LimitHit(LimitKind.RATE_LIMIT,"opencode-go") }
        compose.onNodeWithText("Rate limited, try again shortly").assertExists();compose.onNodeWithText("Usage limit reached").assertDoesNotExist()
        compose.runOnIdle { provider="codex" }
        compose.onNodeWithTag("usage-limit").assertDoesNotExist()
    }
    @Test fun hostWithoutSubscriptionsHidesTheQuotaSectionEntirely() {
        compose.setContent { DshTheme { UsageCard(UsageSnapshot(subscriptions=false),"codex",now,tokens=spent){} } }
        compose.onNodeWithTag("quota-all").assertDoesNotExist();compose.onNodeWithTag("quota-window-0-0").assertDoesNotExist()
        compose.onNodeWithTag("usage-total").assertExists()
    }
    @Test fun footerStaysShortAndNeverSaysUnknown() {
        var limit by mutableStateOf<LimitHit?>(null)
        var snapshot by mutableStateOf(UsageSnapshot())
        compose.setContent { DshTheme { Text(usageFooterText(snapshot,"opencode-go",limit,now),Modifier.testTag("footer")) } }
        compose.onNodeWithTag("footer").assertTextEquals("")
        compose.runOnIdle { snapshot=UsageSnapshot(balance=BalanceState.Ready(UsageRow("deepseek-account",wallets=listOf(CurrencyWallet("USD","54.12")),sampledAt=now))) }
        compose.onNodeWithTag("footer").assertTextEquals("54.12$")
        compose.runOnIdle { limit=LimitHit(LimitKind.QUOTA,"opencode-go") }
        compose.onNodeWithTag("footer").assertTextEquals("Limit reached")
        compose.runOnIdle { limit=LimitHit(LimitKind.RATE_LIMIT) }
        compose.onNodeWithTag("footer").assertTextEquals("Rate limited")
        // Another provider's limit is not this session's.
        compose.runOnIdle { limit=LimitHit(LimitKind.QUOTA,"codex") }
        compose.onNodeWithTag("footer").assertTextEquals("54.12$")
    }
}
