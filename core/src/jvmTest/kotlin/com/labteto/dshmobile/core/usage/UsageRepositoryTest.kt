package com.labteto.dshmobile.core.usage

import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UsageRepositoryTest {
    private open class Source:UsageSource {
        var reads=0
        override suspend fun status():SubscriptionRoster { reads++;return SubscriptionRoster(mapOf("codex" to SubscriptionProviderStatus(listOf(SubscriptionAccount("a",true))))) }
        override suspend fun quota(provider:String,account:String):ProviderQuota?=ProviderQuota(true,listOf(QuotaWindow("weekly",40.0)),QuotaObservation(1000,false))
        override suspend fun wallet():AccountWallet?=null
    }
    @Test fun currentSubscriptionDoesNotReadWalletOrOtherProvider()=runTest {
        val r=UsageRepository{1000};val requested=mutableListOf<String>();var wallets=0
        val s=object:Source(){override suspend fun quota(provider:String,account:String):ProviderQuota? {requested.add(provider);return super.quota(provider,account)};override suspend fun wallet():AccountWallet? {wallets++;return null}}
        r.bind("h");r.refresh("h",s,"codex");assertEquals(listOf("codex"),requested);assertEquals(0,wallets);assertEquals(2,s.reads)
    }
    @Test fun walletDoesNotReadSubscriptionRoster()=runTest { val r=UsageRepository{1000};val s=Source();r.bind("h");r.refresh("h",s,"deepseek-account");assertEquals(0,s.reads) }
    @Test fun ttlCoalescesRefresh()=runTest { val r=UsageRepository{1000};val s=Source();r.bind("h1");r.refresh("h1",s);val reads=s.reads;r.refresh("h1",s);assertEquals(reads,s.reads);assertEquals(60,r.state.value.rows.first{it.provider=="codex"}.windows.single().percent) }
    @Test fun changedRosterDropsQuota()=runTest { val r=UsageRepository{1000};val s=object:Source(){override suspend fun status():SubscriptionRoster {reads++;return SubscriptionRoster(mapOf("codex" to SubscriptionProviderStatus(listOf(SubscriptionAccount(if(reads==1)"a" else "b",true))))) }};r.bind("h");r.refresh("h",s);assertTrue(r.state.value.rows.first{it.provider=="codex"}.windows.isEmpty()) }
    @Test fun oldHostResultCannotOverwriteNewHostPublication()=runTest {
        val gate=CompletableDeferred<Unit>();val r=UsageRepository{1000}
        val old=object:Source(){override suspend fun quota(provider:String,account:String):ProviderQuota? {gate.await();return super.quota(provider,account)}}
        val newer=object:Source(){override suspend fun quota(provider:String,account:String)=ProviderQuota(true,listOf(QuotaWindow("weekly",10.0)),QuotaObservation(1000,false))}
        r.bind("old");val oldJob=launch{r.refresh("old",old)};testScheduler.runCurrent()
        r.bind("new");val newJob=launch{r.refresh("new",newer)};testScheduler.runCurrent()
        gate.complete(Unit);oldJob.join();newJob.join()
        assertEquals(90,r.state.value.rows.single{it.provider=="codex"}.windows.single().percent)
        assertFalse(r.state.value.loading)
        r.refresh("old",old,recheck=true)
        assertEquals(90,r.state.value.rows.single{it.provider=="codex"}.windows.single().percent)
    }
    @Test fun manualRecheckBypassesOnlyLocalTtl()=runTest {
        val r=UsageRepository{1000};val s=Source();r.bind("h")
        r.refresh("h",s);assertEquals(2,s.reads)
        r.refresh("h",s,recheck=false);assertEquals(2,s.reads)
        r.refresh("h",s,recheck=true);assertEquals(4,s.reads)
    }
    @Test fun explicitAllCoverageReadsFiveProvidersAndWallet()=runTest {
        val requested=mutableListOf<String>();var wallets=0
        val s=object:Source(){
            override suspend fun status():SubscriptionRoster {
                reads++;return SubscriptionRoster(subscriptionProviders.associateWith { SubscriptionProviderStatus(listOf(SubscriptionAccount("selector-$it",true))) })
            }
            override suspend fun quota(provider:String,account:String):ProviderQuota? {
                assertEquals("selector-$provider",account);requested.add(provider);return super.quota(provider,account)
            }
            override suspend fun wallet():AccountWallet {wallets++;return AccountWallet("ready",listOf(CurrencyWallet("USD","0")))}
        }
        val r=UsageRepository{1000};r.bind("h");r.refresh("h",s,provider="codex",includeAll=true)
        assertEquals(subscriptionProviders,requested.toSet());assertEquals(5,requested.size)
        assertEquals(1,wallets);assertEquals(2,s.reads);assertEquals(6,r.state.value.rows.size)
        r.refresh("h",s,provider="codex",includeAll=true)
        assertEquals(5,requested.size);assertEquals(1,wallets)
    }
    @Test fun exceptionEndsLoadingWithoutRawErrors()=runTest {val r=UsageRepository{1000};r.bind("h");r.refresh("h",object:Source(){override suspend fun status():SubscriptionRoster=error("secret body")});assertFalse(r.state.value.loading) }
}
