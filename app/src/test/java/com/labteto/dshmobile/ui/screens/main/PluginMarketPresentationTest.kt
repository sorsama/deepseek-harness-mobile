package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.market.MarketCheck
import com.labteto.dshmobile.core.market.MarketEntry
import com.labteto.dshmobile.core.market.MarketFailure
import com.labteto.dshmobile.core.market.MarketInstallStatus
import com.labteto.dshmobile.core.market.MarketInstallation
import com.labteto.dshmobile.core.market.MarketPage
import com.labteto.dshmobile.core.market.MarketQuery
import com.labteto.dshmobile.core.market.marketLanguageChain
import com.labteto.dshmobile.core.market.parseMarketSearch
import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the Discover view over a real answer: `pluginMarket/search` of the 0.2.1-rc.2 harness (687f282ef)
 * booted with the plugin-market bundle, over the index in core's `market/index.source.json`. See
 * `core/src/jvmTest/resources/market` for the other captured answers.
 */
class PluginMarketPresentationTest {
    private val page: MarketPage = parseMarketSearch(
        WireJson.parseToJsonElement(javaClass.getResource("/market/search-all.json")!!.readText())
            .jsonObject.getValue("result").jsonObject.getValue("value"),
    )!!
    private val english = marketLanguageChain("en")
    private fun entry(name: String): MarketEntry = page.entries.single { it.name == name }
    private val review get() = entry("@example/dsh-auto-review")
    private val foo get() = entry("dsh-plugin-foo")
    private val future get() = entry("@example/dsh-future")
    private val ralph get() = entry("@deepseek-ai/dsh-experimental-ralph-bundle")
    private fun status(status: MarketInstallStatus, installed: String? = null) = MarketInstallation(status, installed)
    private val notInstalled = status(MarketInstallStatus.NOT_INSTALLED)

    // ---- cards ----

    @Test fun `a card shows the title the version the publisher and one line`() {
        val card = marketCard(review, notInstalled, english)
        assertEquals("Auto Review", card.title)
        assertFalse(card.technicalTitle)
        assertEquals("0.2.0-rc.2", card.version)
        assertEquals("Example Labs", card.publisher)
        assertEquals("Per-tool LLM authorization review", card.description)
        assertEquals(MarketCardState.NONE, card.state)
    }

    @Test fun `a card follows the language of the app`() {
        assertEquals("自动审查", marketCard(review, notInstalled, marketLanguageChain("zh-CN")).title)
        assertEquals("Auto Review", marketCard(review, notInstalled, marketLanguageChain("de")).title)
    }

    @Test fun `an entry with no title is drawn by its short package name as a technical name`() {
        val card = marketCard(ralph.copy(title = null), notInstalled, english)
        assertEquals("experimental-ralph-bundle", card.title)
        assertTrue(card.technicalTitle)
        assertEquals("dsh-plugin-foo", marketCard(foo.copy(title = null), notInstalled, english).title)
    }

    @Test fun `with no author the publisher is the host of the homepage or the source page`() {
        assertEquals("example.com", marketPublisher(review.copy(author = null)))
        assertEquals("github.com", marketPublisher(review.copy(author = null, homepage = null)))
        assertNull(marketPublisher(foo))
        assertEquals("example.com", marketHost("https://www.example.com:8443/a/b?c=1#d"))
        assertNull(marketHost("not a link"))
        assertNull(marketHost(null))
    }

    @Test fun `a card has one state and says it in words`() {
        assertEquals(MarketCardState.INSTALLED, marketCardState(review, status(MarketInstallStatus.INSTALLED, "0.2.0-rc.2")))
        assertEquals(MarketCardState.INSTALLED, marketCardState(review, status(MarketInstallStatus.DIFFERENT_VERSION, "9.0.0")))
        assertEquals(MarketCardState.UPDATE_AVAILABLE, marketCardState(review, status(MarketInstallStatus.UPDATE_AVAILABLE, "0.1.0")))
        assertEquals(MarketCardState.PROVIDED, marketCardState(ralph, ralph.installation))
        // Captured: nothing listed for this harness is compatible, and it is not installed.
        assertEquals(MarketCardState.NEEDS_NEWER, marketCardState(future, future.installation))
        assertEquals(MarketCardState.NONE, marketCardState(foo, foo.installation))
    }

    @Test fun `a one line description is the first line that says something`() {
        assertEquals("Second", marketFirstLine("\n  \nSecond\nThird"))
        assertNull(marketFirstLine("  \n "))
        assertNull(marketFirstLine(null))
    }

    // ---- chips ----

    @Test fun `chips are All then categories then labels each in the order of their titles`() {
        val chips = marketChips(page.header, MarketQuery(), english, "All")
        assertEquals(listOf("All", "Productivity", "Tools", "Experimental", "Official"), chips.map { it.title })
        assertEquals(listOf(MarketChipKind.ALL, MarketChipKind.CATEGORY, MarketChipKind.CATEGORY, MarketChipKind.LABEL, MarketChipKind.LABEL), chips.map { it.kind })
        assertEquals(listOf(true, false, false, false, false), chips.map { it.selected })
    }

    @Test fun `a market that declares nothing has no chips`() {
        assertTrue(marketChips(page.header.copy(categories = emptyList(), labels = emptyList()), MarketQuery(), english, "All").isEmpty())
    }

    @Test fun `tapping a chip changes the query as the chip says`() {
        val chips = marketChips(page.header, MarketQuery(), english, "All").associateBy { it.title }
        val productivity = MarketQuery("x").tap(chips.getValue("Productivity"))
        assertEquals("productivity", productivity.category)
        assertEquals("x", productivity.text)
        // Choosing it again clears it; another category replaces it.
        assertNull(productivity.tap(chips.getValue("Productivity")).category)
        assertEquals("tools", productivity.tap(chips.getValue("Tools")).category)
        // Labels add up and toggle off.
        val labelled = productivity.tap(chips.getValue("Official")).tap(chips.getValue("Experimental"))
        assertEquals(listOf("official", "experimental"), labelled.labels)
        assertEquals(listOf("experimental"), labelled.tap(chips.getValue("Official")).labels)
        // All clears every filter and keeps the text.
        val all = labelled.tap(chips.getValue("All"))
        assertTrue(all.category == null && all.labels.isEmpty())
        assertEquals("x", all.text)
        val selected = marketChips(page.header, labelled, english, "All").filter { it.selected }.map { it.title }
        assertEquals(listOf("Productivity", "Experimental", "Official"), selected)
    }

    // ---- messages ----

    @Test fun `an empty market and a search with no matches are told apart`() {
        val none = page.copy(entries = emptyList(), total = 0)
        assertEquals(MarketEmpty.INDEX, marketEmpty(none, MarketQuery()))
        assertEquals(MarketEmpty.INDEX, marketEmpty(none, null))
        assertEquals(MarketEmpty.MATCHES, marketEmpty(none, MarketQuery("zzzz")))
        assertEquals(MarketEmpty.MATCHES, marketEmpty(none, MarketQuery(category = "tools")))
        assertNull(marketEmpty(page, MarketQuery("a")))
        assertNull(marketEmpty(null, MarketQuery()))
        // Entries the app could not read are not an empty market.
        assertNull(marketEmpty(none.copy(total = 3, dropped = 3), MarketQuery()))
    }

    @Test fun `every failure has a sentence and the hosts words stay in the details`() {
        assertEquals(R.string.market_failed_fetch, marketFailureText(MarketFailure.Fetch("HTTP 404")))
        assertEquals(R.string.market_failed_invalid, marketFailureText(MarketFailure.InvalidIndex("$.plugins[0].name", "bad")))
        assertEquals(R.string.market_failed_missing, marketFailureText(MarketFailure.UnknownIndex))
        assertEquals(R.string.market_failed_changed, marketFailureText(MarketFailure.Changed))
        assertEquals(R.string.ux_a_load_error, marketFailureText(MarketFailure.Other("offline", null)))
        assertEquals("HTTP 404", marketFailureDetails(MarketFailure.Fetch("HTTP 404")))
        assertEquals("$.plugins[0].name\nbad", marketFailureDetails(MarketFailure.InvalidIndex("$.plugins[0].name", "bad")))
        assertNull(marketFailureDetails(MarketFailure.UnknownIndex))
    }

    @Test fun `a refused source says why`() {
        assertEquals(R.string.market_source_duplicate, marketSourceFailureText(MarketFailure.InvalidUrl("duplicate")))
        assertEquals(R.string.market_source_invalid, marketSourceFailureText(MarketFailure.InvalidUrl("scheme")))
        assertEquals(R.string.market_source_invalid, marketSourceFailureText(MarketFailure.InvalidUrl(null)))
        assertEquals(R.string.market_source_unavailable, marketSourceFailureText(MarketFailure.SettingsUnavailable))
        assertEquals(R.string.market_source_failed, marketSourceFailureText(MarketFailure.Other("x", null)))
    }

    // ---- errors belong to the market they are about ----

    @Test fun `a market that has loaded since is not in error whatever the host still lists`() {
        val stale = "Could not fetch http://127.0.0.1:3099/index.json: fetch failed: connect ECONNREFUSED 127.0.0.1:3099"
        // The device run: the server came back, the host still carried this error for the market it was not asked about again.
        assertNull(marketSourceProblem(stale, loadedSince = true, failure = null))
        assertNull(marketSourceProblem(stale, loadedSince = true, failure = MarketFailure.Fetch("HTTP 500")))
        assertEquals(stale, marketSourceProblem(stale, loadedSince = false, failure = null))
        // A failure this session saw is shown even when the host has recorded nothing.
        assertEquals("HTTP 404", marketSourceProblem(null, loadedSince = false, failure = MarketFailure.Fetch("HTTP 404")))
        assertNull(marketSourceProblem(null, loadedSince = false, failure = null))
    }

    @Test fun `a failure is named for its market by title then by host`() {
        val titled = com.labteto.dshmobile.core.market.MarketIndexInfo(
            "http://127.0.0.1:3099/index.json", com.labteto.dshmobile.core.market.MarketText("Integration Test Market"), null, null,
        )
        val untitled = com.labteto.dshmobile.core.market.MarketIndexInfo("http://127.0.0.1:3099/dead.json", null, null, "x")
        val sources = listOf(titled, untitled)
        assertEquals("Integration Test Market", marketLabel(titled.url, sources, english))
        // The second market of the device run, which had no title: the host of its address, not another market's name.
        assertEquals("127.0.0.1", marketLabel(untitled.url, sources, english))
        assertEquals("example.org", marketLabel("https://www.example.org/index.json", null, english))
    }

    // ---- the entry page ----

    @Test fun `the action follows the hosts installation status`() {
        val version = review.presented!!
        assertEquals(MarketAction.Install(version), marketAction(review, notInstalled, null))
        assertEquals(MarketAction.Update(version, "@example/dsh-auto-review"), marketAction(review, status(MarketInstallStatus.UPDATE_AVAILABLE, "0.1.0"), "@example/dsh-auto-review"))
        assertEquals(MarketAction.OpenInstalled("b"), marketAction(review, status(MarketInstallStatus.INSTALLED, "0.2.0-rc.2"), "b"))
        assertEquals(MarketAction.OpenInstalled("b"), marketAction(review, status(MarketInstallStatus.DIFFERENT_VERSION, "9.0.0"), "b"))
        // Installed but not (yet) in the page's list, or shipped with no page to open: no action to offer.
        assertEquals(MarketAction.None, marketAction(review, status(MarketInstallStatus.INSTALLED), null))
        assertEquals(MarketAction.None, marketAction(ralph, ralph.installation, null))
        assertEquals(MarketAction.OpenInstalled("r"), marketAction(ralph, ralph.installation, "r"))
    }

    @Test fun `a version the harness does not accept offers no install`() {
        assertEquals(MarketAction.Incompatible, marketAction(future, future.installation, null))
        assertEquals(MarketAction.Incompatible, marketAction(future, status(MarketInstallStatus.UPDATE_AVAILABLE, "1.0.0"), "x"))
        // An exemption the profile holds makes it installable again, and the confirmation says so.
        val exempted = future.copy(versions = future.versions.map { it.copy(compatibility = it.compatibility.copy(exempted = true)) })
        assertEquals(MarketAction.Install(exempted.presented!!), marketAction(exempted, notInstalled, null))
        assertTrue(marketInstallNote(exempted, exempted.presented!!, page.header, notInstalled, english).exempted)
    }

    @Test fun `only what the harness ships is trusted and everything a market lists is a claim`() {
        assertEquals(MarketTrust.UNVERIFIED, marketTrust(notInstalled))
        assertEquals(MarketTrust.UNVERIFIED, marketTrust(status(MarketInstallStatus.INSTALLED, "1")))
        assertEquals(MarketTrust.UNVERIFIED, marketTrust(status(MarketInstallStatus.UNKNOWN)))
        assertEquals(MarketTrust.SHIPPED, marketTrust(ralph.installation))
        // A label named "Official" in the market's own words changes nothing: the index format has no such mark.
        assertEquals(listOf("official"), review.labels)
        assertEquals(MarketTrust.UNVERIFIED, marketTrust(review.installation))
    }

    @Test fun `compatibility is one of works needs or allowed`() {
        assertEquals(MarketCompat.Works(">=0.2.0 <0.3.0"), marketCompat(review.presented!!))
        assertEquals(MarketCompat.Needs(">=9.0.0", "0.2.1-rc.2"), marketCompat(future.presented!!))
        val allowed = future.presented!!.copy(compatibility = future.presented!!.compatibility.copy(exempted = true))
        assertEquals(MarketCompat.Allowed, marketCompat(allowed))
    }

    @Test fun `facts name where an npm package comes from exactly`() {
        val facts = marketFacts(review, review.presented!!, page.header, notInstalled, english) { "D$it" }
        val byLabel = facts.associateBy { it.label }
        assertEquals("Example Labs", byLabel.getValue(R.string.market_fact_publisher).value)
        assertEquals("https://example.com/dsh-auto-review", byLabel.getValue(R.string.market_fact_homepage).link)
        assertEquals("https://github.com/example/dsh-plugins · auto-review", byLabel.getValue(R.string.market_fact_source_code).value)
        assertEquals("https://github.com/example/dsh-plugins", byLabel.getValue(R.string.market_fact_source_code).link)
        assertEquals("@example/dsh-auto-review@0.2.0-rc.2", byLabel.getValue(R.string.market_fact_package).value)
        assertTrue(byLabel.getValue(R.string.market_fact_package).technical)
        assertFalse(byLabel.containsKey(R.string.market_fact_registry))
        assertEquals("D1790476800", byLabel.getValue(R.string.market_fact_published).value)
        assertEquals("Productivity", byLabel.getValue(R.string.market_fact_category).value)
        assertEquals("Official", byLabel.getValue(R.string.market_fact_labels).value)
        assertEquals("Example Market", byLabel.getValue(R.string.market_fact_market).value)
        assertFalse(byLabel.containsKey(R.string.market_fact_installed))
    }

    @Test fun `facts name the registry a version asks and the installed version`() {
        val facts = marketFacts(future, future.presented!!, page.header, status(MarketInstallStatus.UPDATE_AVAILABLE, "1.0.0"), english) { "d" }.associateBy { it.label }
        assertEquals("https://registry.example.com/", facts.getValue(R.string.market_fact_registry).value)
        assertEquals("1.0.0", facts.getValue(R.string.market_fact_installed).value)
    }

    @Test fun `facts name the repository the commit and the folder of a git version`() {
        val facts = marketFacts(foo, foo.presented!!, page.header, notInstalled, english) { "d" }.associateBy { it.label }
        assertEquals("https://github.com/author/dsh-plugins", facts.getValue(R.string.market_fact_repository).value)
        assertEquals("3f9c2e1d8a4b6c0e7f1a2b3c4d5e6f708192a3b4", facts.getValue(R.string.market_fact_commit).value)
        assertEquals("packages/foo", facts.getValue(R.string.market_fact_folder).value)
        assertFalse(facts.containsKey(R.string.market_fact_package))
        assertFalse(facts.containsKey(R.string.market_fact_publisher))
        assertEquals("Experimental", facts.getValue(R.string.market_fact_labels).value)
    }

    // ---- the install: the same subject the manual install confirms ----

    @Test fun `the subject carries the exact spec and registry and never the display name`() {
        val version = future.presented!!
        val note = marketInstallNote(future, version, page.header, notInstalled, english)
        val subject = marketInstallSubject(version, note, replaces = null)
        assertEquals("@example/dsh-future@9.1.0", subject.target)
        assertEquals("https://registry.example.com/", subject.registry)
        assertFalse(subject.enabled)
        assertEquals(true, subject.saveExact)
        assertNull(subject.replaces)
        assertNull(subject.approvedBuilds)
        val git = marketInstallSubject(foo.presented!!, marketInstallNote(foo, foo.presented!!, page.header, notInstalled, english), null)
        assertEquals("git+https://github.com/author/dsh-plugins.git#3f9c2e1d8a4b6c0e7f1a2b3c4d5e6f708192a3b4&path:/packages/foo", git.target)
        assertNull(git.registry)
        assertEquals("b", marketInstallSubject(version, note, replaces = "b").replaces)
    }

    @Test fun `the note names what the market claims and carries the one line warning`() {
        val note = marketInstallNote(review, review.presented!!, page.header, notInstalled, marketLanguageChain("zh-CN"))
        assertEquals("自动审查", note.title)
        assertEquals("0.2.0-rc.2", note.version)
        assertEquals("Example Labs", note.publisher)
        assertEquals("示例市场", note.market)
        assertTrue(note.unverified)
        assertFalse(note.exempted)
        assertFalse(marketInstallNote(ralph, ralph.presented!!, page.header, ralph.installation, english).unverified)
        // No title in the index and no market header read yet: the short package name, and no market line.
        val bare = marketInstallNote(foo.copy(title = null), foo.presented!!, null, notInstalled, english)
        assertEquals("dsh-plugin-foo", bare.title)
        assertNull(bare.market)
    }

    @Test fun `a check that did not clear the install says why`() {
        assertNull(marketCheckProblem(MarketCheck.Proceed))
        assertEquals(R.string.market_check_mismatch, marketCheckProblem(MarketCheck.Mismatch("evil", "1.0.0")))
        assertEquals(R.string.market_no_pnpm, marketCheckProblem(MarketCheck.NoPackageManager))
        assertEquals(R.string.ux_a_reason_package, marketCheckProblem(MarketCheck.Refused("not-a-package", "x")))
        assertEquals(R.string.ux_a_reason_network, marketCheckProblem(MarketCheck.Refused("network", "x")))
        assertEquals("evil 1.0.0", marketCheckDetails(MarketCheck.Mismatch("evil", "1.0.0")))
        assertEquals("not-found\nERR_PNPM_FETCH_404", marketCheckDetails(MarketCheck.Refused("not-found", "ERR_PNPM_FETCH_404")))
        assertNull(marketCheckDetails(MarketCheck.NoPackageManager))
    }

    @Test fun `a failed install on a host without pnpm reads as that and not as a generic error`() {
        assertEquals(R.string.market_no_pnpm, pluginReason("pnpm-missing"))
        assertEquals(R.string.ux_a_action_error, pluginReason("something-new"))
    }

    // ---- what a failed check says: one sentence for each thing the harness's answer can tell apart ----

    @Test fun `a package tool that failed says so and never sends the person to refresh`() {
        // The answer a harness gave with an npm that rejects the flag it passes (see core's inspect-tool-failed fixture).
        val failed = MarketCheck.ToolFailed("unknown", "npm error code EUNKNOWNCONFIG\nnpm error Unknown cli flag: --config.fetch-retries")
        assertEquals(R.string.market_tool_failed, marketCheckProblem(failed))
        assertEquals("unknown\nnpm error code EUNKNOWNCONFIG\nnpm error Unknown cli flag: --config.fetch-retries", marketCheckDetails(failed))
        assertTrue(marketCheckProblem(failed) != R.string.ux_a_action_error)
    }

    @Test fun `a package that was not found is told apart from a tool failure and from a network failure`() {
        assertEquals(R.string.market_check_not_found, marketCheckProblem(MarketCheck.Refused("not-found", "ERR_PNPM_FETCH_404")))
        assertEquals(R.string.ux_a_reason_network, marketCheckProblem(MarketCheck.Refused("network", "ECONNRESET")))
        assertEquals(R.string.market_no_pnpm, marketCheckProblem(MarketCheck.NoPackageManager))
        assertEquals(R.string.market_check_mismatch, marketCheckProblem(MarketCheck.Mismatch("evil", "1.0.0")))
    }

    @Test fun `a refusal nothing can classify never falls back to the generic refresh sentence`() {
        assertEquals(R.string.market_check_failed, marketCheckProblem(MarketCheck.Refused("something-new", null)))
        assertEquals(R.string.market_check_failed, marketCheckProblem(MarketCheck.Refused("unknown", null)))
        // A code the manual install already words keeps its words.
        assertEquals(R.string.ux_a_reason_installed, marketCheckProblem(MarketCheck.Refused("already-installed", null)))
    }

    // ---- the confirmation: a git source has a repository and a commit, not a registry ----

    @Test fun `a git entry's confirmation names the repository and the pinned commit`() {
        val version = foo.presented!!
        val note = marketInstallNote(foo, version, page.header, notInstalled, english)
        val git = note.git!!
        val source = version.source as com.labteto.dshmobile.core.market.MarketInstallSource.Git
        assertEquals(source.url, git.url)
        assertEquals(source.commit, git.commit)
        assertTrue(git.url.startsWith("https://"))
        assertTrue(git.commit.length >= 7)
    }

    @Test fun `a registry entry's confirmation has no git lines so it still names its registry`() {
        assertNull(marketInstallNote(review, review.presented!!, page.header, notInstalled, english).git)
        assertNull(marketInstallNote(ralph, ralph.presented!!, page.header, ralph.installation, english).git)
    }
}
