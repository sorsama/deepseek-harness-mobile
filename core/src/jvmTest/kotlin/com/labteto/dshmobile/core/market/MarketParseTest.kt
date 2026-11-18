package com.labteto.dshmobile.core.market

import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketParseTest {
    private val page = MarketFixtures.page("search-all")
    private fun entry(name: String) = page.entries.single { it.name == name }

    // ---- real answers (captured from the 0.2.1-rc.2 harness, see MarketFixtures) ----

    @Test fun aRealAnswerKeepsEveryEntryInIndexOrder() {
        assertEquals(
            listOf("@example/dsh-auto-review", "dsh-plugin-foo", "@example/dsh-future", "@example/dsh-notes", "@deepseek-ai/dsh-experimental-ralph-bundle"),
            page.entries.map { it.name },
        )
        assertEquals(5, page.total)
        assertNull(page.nextOffset)
        assertEquals(0, page.dropped)
        assertEquals("http://127.0.0.1:56394/index.json", page.header.url)
    }

    @Test fun theHeaderCarriesTheMarketTitleAndItsDeclarations() {
        val header = page.header
        assertEquals("Example Market", header.title!!.resolve(listOf("fr", "en")))
        assertEquals("示例市场", header.title!!.resolve(marketLanguageChain("zh-CN")))
        assertEquals(listOf("productivity", "tools"), header.categories.map { it.id })
        assertEquals(listOf("official", "experimental"), header.labels.map { it.id })
        assertEquals("Productivity", header.categories.first().title.resolve(listOf("de", "en")))
    }

    @Test fun anNpmEntryKeepsItsInstallArgumentsExactly() {
        val review = entry("@example/dsh-auto-review")
        assertEquals("0.2.0-rc.2", review.defaultVersion)
        assertEquals(listOf("0.2.0-rc.2", "0.2.0-rc.1"), review.versions.map { it.version })
        val version = review.presented!!
        assertEquals("@example/dsh-auto-review@0.2.0-rc.2", version.installSpec)
        assertNull(version.installRegistry)
        assertEquals(MarketInstallSource.Npm(null), version.source)
        assertEquals(">=0.2.0 <0.3.0", version.dshRange)
        assertTrue(version.compatibility.compatible)
        assertEquals("Example Labs", review.author)
        assertEquals("https://example.com/dsh-auto-review", review.homepage)
        assertEquals(MarketRepository("https://github.com/example/dsh-plugins", "auto-review"), review.repository)
        assertEquals("productivity", review.category)
        assertEquals(listOf("official"), review.labels)
    }

    @Test fun aGitVersionIsPinnedToItsCommitAndNamesNoRegistry() {
        val foo = entry("dsh-plugin-foo")
        val version = foo.presented!!
        assertEquals(
            MarketInstallSource.Git("https://github.com/author/dsh-plugins", "3f9c2e1d8a4b6c0e7f1a2b3c4d5e6f708192a3b4", "packages/foo"),
            version.source,
        )
        assertEquals("git+https://github.com/author/dsh-plugins.git#3f9c2e1d8a4b6c0e7f1a2b3c4d5e6f708192a3b4&path:/packages/foo", version.installSpec)
        assertNull(version.installRegistry)
        assertEquals("Foo", foo.title!!.resolve(listOf("zh")))
        assertNull(foo.description)
    }

    @Test fun aVersionTheHarnessRunningDoesNotSatisfyHasNoDefaultAndSaysWhatItNeeds() {
        val future = entry("@example/dsh-future")
        assertNull(future.defaultVersion)
        // With no compatible version the entry still presents its newest, so the page can say why it cannot be installed.
        val version = future.presented!!
        assertEquals("9.1.0", version.version)
        assertFalse(version.compatibility.compatible)
        assertFalse(version.compatibility.installable)
        assertEquals("0.2.1-rc.2", version.compatibility.runtimeVersion)
        assertEquals(">=9.0.0", version.compatibility.requirement)
        // The registry the index named for this version is the one that installs it.
        assertEquals("https://registry.example.com/", version.installRegistry)
        assertEquals(MarketInstallSource.Npm("https://registry.example.com/"), version.source)
    }

    @Test fun aBundleTheHarnessShipsReadsAsProvided() {
        val ralph = entry("@deepseek-ai/dsh-experimental-ralph-bundle")
        assertEquals(MarketInstallation(MarketInstallStatus.PROVIDED, "0.2.1-rc.2"), ralph.installation)
        assertEquals(MarketInstallation(MarketInstallStatus.NOT_INSTALLED), entry("@example/dsh-notes").installation)
    }

    @Test fun localizedTextFollowsTheLanguageChainThenEnglish() {
        val notes = entry("@example/dsh-notes")
        assertEquals("笔记", notes.title!!.resolve(marketLanguageChain("zh-CN")))
        assertEquals("Notes", notes.title!!.resolve(marketLanguageChain("fr-CA")))
        assertEquals("Garde de courtes notes à côté d'une session", notes.description!!.resolve(marketLanguageChain("fr-CA")))
        // No German text: English.
        assertEquals("Keep short notes next to a session", notes.description!!.resolve(marketLanguageChain("de")))
        assertEquals(listOf("zh-cn", "zh"), marketLanguageChain("zh_CN"))
        assertEquals(listOf("fr"), marketLanguageChain("fr"))
        assertEquals(emptyList<String>(), marketLanguageChain(" "))
    }

    @Test fun searchAnswersCarryTheirTotalsAndTheNextPageOffset() {
        val first = MarketFixtures.page("search-page2")
        assertEquals(listOf("@example/dsh-future", "@example/dsh-notes"), first.entries.map { it.name })
        assertEquals(5, first.total)
        assertEquals(4, first.nextOffset)
        assertEquals(listOf("@example/dsh-auto-review"), MarketFixtures.page("search-text").entries.map { it.name })
        assertEquals(listOf("@example/dsh-auto-review"), MarketFixtures.page("search-labels").entries.map { it.name })
        assertEquals(3, MarketFixtures.page("search-category").total)
        val none = MarketFixtures.page("search-none")
        assertEquals(0, none.total)
        assertTrue(none.entries.isEmpty())
        // An empty answer still names the market, so the page can say what is empty.
        assertEquals("Example Market", none.header.title!!.resolve(listOf("en")))
    }

    @Test fun theSourceListShowsTheTitleOfAGoodReadAndTheErrorOfABadOne() {
        val sources = parseMarketIndexes(MarketFixtures.value("indexes-final"))!!
        assertEquals(3, sources.size)
        assertEquals("Example Market", sources[0].title!!.resolve(listOf("en")))
        assertNull(sources[0].error)
        assertNull(sources[1].title)
        assertTrue(sources[1].error!!.contains("must be a valid npm package name"))
        assertTrue(sources[2].error!!.endsWith("HTTP 404"))
        assertEquals("http://127.0.0.1:56394/broken.json", sources[1].url)
    }

    // ---- statuses the capture could not produce (no plugin may be installed during it) ----

    @Test fun everyInstallationStatusTheHarnessDefinesIsRead() {
        // packages/boot/plugin-market/src/types.ts: MarketInstallationStatus.
        fun status(raw: String): MarketInstallation = parseMarketSearch(
            MarketFixtures.answer(listOf(MarketFixtures.entry(installation = """{"status":"$raw","installedVersion":"0.9.0"}"""))),
        )!!.entries.single().installation
        assertEquals(MarketInstallStatus.INSTALLED, status("installed").status)
        assertEquals(MarketInstallStatus.UPDATE_AVAILABLE, status("update-available").status)
        assertEquals(MarketInstallStatus.DIFFERENT_VERSION, status("different-version").status)
        assertEquals(MarketInstallStatus.PROVIDED, status("provided").status)
        assertEquals(MarketInstallStatus.NOT_INSTALLED, status("not-installed").status)
        assertEquals("0.9.0", status("update-available").installedVersion)
        // A status a newer harness adds is not guessed.
        assertEquals(MarketInstallStatus.UNKNOWN, status("quarantined").status)
    }

    // ---- not an answer, and answers that are partly wrong ----

    @Test fun aValueThatIsNotAnAnswerIsRefusedWholeAndNeverThrows() {
        assertNull(parseMarketSearch(JsonNull))
        assertNull(parseMarketSearch(JsonArray(emptyList())))
        assertNull(parseMarketSearch(JsonPrimitive("hello")))
        assertNull(parseMarketSearch(WireJson.parseToJsonElement("""{"entries":"nope","url":"https://m.example/i.json"}""")))
        assertNull(parseMarketSearch(WireJson.parseToJsonElement("""{"entries":[]}""")))
        assertNull(parseMarketSearch(WireJson.parseToJsonElement("""{"entries":[],"url":"javascript:alert(1)"}""")))
        assertNull(parseMarketIndexes(JsonPrimitive(1)))
        assertEquals(emptyList<MarketIndexInfo>(), parseMarketIndexes(JsonArray(listOf(JsonPrimitive(1), JsonNull))))
    }

    @Test fun oneBrokenEntryIsLeftOutAndCountedWhileTheRestStay() {
        val good = MarketFixtures.entry("@example/dsh-good")
        val answer = MarketFixtures.answer(listOf("42", """{"name":"@example/dsh-nameless-versions"}""", good, """{"versions":[]}""", """{"name":"x","versions":"no"}"""))
        val parsed = parseMarketSearch(answer)!!
        assertEquals(listOf("@example/dsh-good"), parsed.entries.map { it.name })
        assertEquals(4, parsed.dropped)
    }

    @Test fun anEntryRepeatedByTheHostIsShownOnce() {
        val one = MarketFixtures.entry("@example/dsh-twice")
        val parsed = parseMarketSearch(MarketFixtures.answer(listOf(one, one)))!!
        assertEquals(1, parsed.entries.size)
        assertEquals(1, parsed.dropped)
    }

    // ---- limits ----

    @Test fun aHugeDescriptionIsCutWithAnEllipsisAndAHugeTitleToo() {
        val huge = "word ".repeat(100_000)
        val answer = MarketFixtures.answer(listOf(MarketFixtures.entry(fields = """"description":"$huge","title":"${"T".repeat(5_000)}","author":"${"A".repeat(5_000)}"""")))
        val entry = parseMarketSearch(answer)!!.entries.single()
        val description = entry.description!!.resolve(emptyList())!!
        assertEquals(MarketLimits.DESCRIPTION, description.length)
        assertTrue(description.endsWith("…"))
        assertEquals(MarketLimits.TITLE, entry.title!!.resolve(emptyList())!!.length)
        assertEquals(MarketLimits.AUTHOR, entry.author!!.length)
    }

    @Test fun aCutNeverEndsOnHalfOfASurrogatePair() {
        val text = "a".repeat(MarketLimits.TITLE - 2) + "😀😀😀"
        val cut = cleanMarketText(text, MarketLimits.TITLE)!!
        assertTrue(cut.length <= MarketLimits.TITLE)
        assertTrue(cut.endsWith("…"))
        assertFalse(cut.dropLast(1).last().isHighSurrogate())
    }

    @Test fun controlAndBidiCharactersAreRemovedFromText() {
        // U+202E (right-to-left override) would make "gpj.exe" read as "exe.jpg".
        assertEquals("Safe name", cleanMarketText("Safe\u0000 ‮name⁦\u0007", 100))
        assertEquals("one two", cleanMarketText("one\n\n  two", 100))
        assertEquals("line one\n\nline two", cleanMarketText("line one\r\n\r\n\r\n\r\nline two", 100, multiline = true))
        assertNull(cleanMarketText(" ‮\u0001 \n", 100))
    }

    @Test fun moreEntriesThanAskedForAreCutAndCounted() {
        val rows = (1..MarketLimits.MAX_ENTRIES_PER_ANSWER + 30).map { MarketFixtures.entry("@example/dsh-n$it") }
        val parsed = parseMarketSearch(MarketFixtures.answer(rows))!!
        assertEquals(MarketLimits.MAX_ENTRIES_PER_ANSWER, parsed.entries.size)
        assertEquals(30, parsed.dropped)
    }

    @Test fun versionsAreCappedButTheDefaultStays() {
        val name = "@example/dsh-many"
        // The host lists newest first; the default (1.0.0) is the oldest here.
        val versions = (200 downTo 1).map { MarketFixtures.version(name, "1.0.$it") } + MarketFixtures.version(name, "1.0.0")
        val entry = parseMarketSearch(MarketFixtures.answer(listOf(MarketFixtures.entry(name, versions = versions))))!!.entries.single()
        assertEquals(MarketLimits.MAX_VERSIONS, entry.versions.size)
        assertEquals("1.0.0", entry.defaultVersion)
        assertEquals("1.0.0", entry.versions.last().version)
        assertEquals("1.0.200", entry.versions.first().version)
    }

    @Test fun labelsAndDeclarationsAreCapped() {
        val labels = (1..50).joinToString(",") { "\"l$it\"" }
        val entry = parseMarketSearch(MarketFixtures.answer(listOf(MarketFixtures.entry(fields = """"metadata":{"labels":[$labels]}"""))))!!.entries.single()
        assertEquals(MarketLimits.MAX_ENTRY_LABELS, entry.labels.size)
        val declared = (1..80).joinToString(",") { "\"c$it\":{\"title\":{\"en\":\"C$it\"}}" }
        val header = parseMarketSearch(MarketFixtures.answer(emptyList(), metadata = """{"categories":{$declared},"labels":{},"sortKeys":{}}"""))!!.header
        assertEquals(MarketLimits.MAX_DECLARATIONS, header.categories.size)
    }

    @Test fun aSourceListIsCappedAndAnOverlongUrlIsNotListed() {
        val long = "https://m.example/" + "a".repeat(MarketLimits.URL)
        val rows = (1..40).joinToString(",") { """{"url":"https://m.example/$it.json"}""" }
        val sources = parseMarketIndexes(WireJson.parseToJsonElement("""[{"url":"$long"},$rows]"""))!!
        // The long URL is skipped (never shown cut into a different address) and the cap counts what is listed.
        assertEquals(MarketLimits.MAX_SOURCES, sources.size)
        assertEquals("https://m.example/1.json", sources.first().url)
        assertTrue(sources.none { it.url.length > MarketLimits.URL })
    }

    // ---- what is never shortened, and what is refused instead ----

    @Test fun aNameOverTheCapDropsTheEntryInsteadOfBeingCut() {
        val name = "@example/" + "x".repeat(MarketLimits.NAME)
        val parsed = parseMarketSearch(MarketFixtures.answer(listOf(MarketFixtures.entry(name))))!!
        assertTrue(parsed.entries.isEmpty())
        assertEquals(1, parsed.dropped)
    }

    @Test fun aVersionWhoseSpecIsNotWhatTheFormatFixesIsNotOffered() {
        val name = "@example/dsh-sneaky"
        // The index says version 1.0.0 but the spec names another package: a redirect.
        val redirected = """{"version":"1.0.0","publishTimestamp":1,"source":{"type":"npm"},"engines":{"dsh":"*"},"install":{"spec":"evil-package@1.0.0"},"compatibility":{"compatible":true}}"""
        val tagged = """{"version":"1.0.1","publishTimestamp":1,"source":{"type":"npm"},"engines":{"dsh":"*"},"install":{"spec":"@example/dsh-sneaky@latest"},"compatibility":{"compatible":true}}"""
        val fine = MarketFixtures.version(name, "1.0.2")
        val entry = parseMarketSearch(MarketFixtures.answer(listOf(MarketFixtures.entry(name, versions = listOf(redirected, tagged, fine)))))!!.entries.single()
        assertEquals(listOf("1.0.2"), entry.versions.map { it.version })
        // And when no version is left, the entry is not shown.
        assertTrue(parseMarketSearch(MarketFixtures.answer(listOf(MarketFixtures.entry(name, versions = listOf(redirected, tagged)))))!!.entries.isEmpty())
    }

    @Test fun aRegistryTheIndexDidNotNameForTheVersionIsNotFollowed() {
        val name = "@example/dsh-registry"
        val swapped = """{"version":"1.0.0","publishTimestamp":1,"source":{"type":"npm"},"engines":{"dsh":"*"},"install":{"spec":"$name@1.0.0","registry":"https://registry.attacker.example/"},"compatibility":{"compatible":true}}"""
        val dropped = """{"version":"1.0.1","publishTimestamp":1,"source":{"type":"npm","registry":"https://registry.example.com/"},"engines":{"dsh":"*"},"install":{"spec":"$name@1.0.1"},"compatibility":{"compatible":true}}"""
        val broken = """{"version":"1.0.2","publishTimestamp":1,"source":{"type":"npm","registry":"ftp://nope"},"engines":{"dsh":"*"},"install":{"spec":"$name@1.0.2","registry":"ftp://nope"},"compatibility":{"compatible":true}}"""
        assertTrue(parseMarketSearch(MarketFixtures.answer(listOf(MarketFixtures.entry(name, versions = listOf(swapped, dropped, broken)))))!!.entries.isEmpty())
    }

    @Test fun aGitVersionNeedsAFullLowercaseCommit() {
        val name = "@example/dsh-git"
        fun row(commit: String) = """{"version":"1.0.0","publishTimestamp":1,"source":{"type":"git","url":"https://github.com/a/b","commit":"$commit"},"engines":{"dsh":"*"},"install":{"spec":"git+https://github.com/a/b.git#$commit"},"compatibility":{"compatible":true}}"""
        assertTrue(parseMarketSearch(MarketFixtures.answer(listOf(MarketFixtures.entry(name, versions = listOf(row("main"))))))!!.entries.isEmpty())
        assertTrue(parseMarketSearch(MarketFixtures.answer(listOf(MarketFixtures.entry(name, versions = listOf(row("3F9C2E1D8A4B6C0E7F1A2B3C4D5E6F708192A3B4"))))))!!.entries.isEmpty())
        assertEquals(1, parseMarketSearch(MarketFixtures.answer(listOf(MarketFixtures.entry(name, versions = listOf(row("3f9c2e1d8a4b6c0e7f1a2b3c4d5e6f708192a3b4"))))))!!.entries.size)
    }

    @Test fun anUnknownSourceKindIsNotInstallable() {
        val name = "@example/dsh-tarball"
        val row = """{"version":"1.0.0","publishTimestamp":1,"source":{"type":"tarball","url":"https://x.example/a.tgz"},"engines":{"dsh":"*"},"install":{"spec":"https://x.example/a.tgz"},"compatibility":{"compatible":true}}"""
        assertTrue(parseMarketSearch(MarketFixtures.answer(listOf(MarketFixtures.entry(name, versions = listOf(row)))))!!.entries.isEmpty())
    }

    @Test fun linksThatAreNotPlainHttpAreDropped() {
        assertEquals("https://example.com/a?b=1", marketHttpUrl("https://example.com/a?b=1"))
        assertEquals("http://localhost:8080/x", marketHttpUrl("http://localhost:8080/x"))
        assertNull(marketHttpUrl("javascript:alert(1)"))
        assertNull(marketHttpUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(marketHttpUrl("file:///etc/passwd"))
        assertNull(marketHttpUrl("https://user:pass@example.com/"))
        assertNull(marketHttpUrl("https://example.com/ a"))
        assertNull(marketHttpUrl("https://"))
        assertNull(marketHttpUrl("https://example.com/" + "a".repeat(MarketLimits.URL)))
        assertNull(marketHttpUrl(null))
        val entry = parseMarketSearch(MarketFixtures.answer(listOf(MarketFixtures.entry(fields = """"homepage":"javascript:alert(1)","repository":{"url":"https://u:p@github.com/a/b"}"""))))!!.entries.single()
        assertNull(entry.homepage)
        assertNull(entry.repository)
    }

    @Test fun theExpectedSpecMatchesTheFormatsOwnExamples() {
        // The format note's own examples (npm; git with a package directory and with a repository that already ends in .git).
        assertEquals("@example/dsh-auto-review@0.2.0-rc.2", marketExpectedSpec("@example/dsh-auto-review", "0.2.0-rc.2", MarketInstallSource.Npm(null)))
        assertEquals(
            "git+https://github.com/author/dsh-plugins.git#3f9c2e1d8a4b6c0e7f1a2b3c4d5e6f708192a3b4&path:/packages/foo",
            marketExpectedSpec("x", "1.0.0", MarketInstallSource.Git("https://github.com/author/dsh-plugins", "3f9c2e1d8a4b6c0e7f1a2b3c4d5e6f708192a3b4", "packages/foo")),
        )
        assertEquals(
            "git+https://gitlab.example.com/g/s/r.git#3f9c2e1d8a4b6c0e7f1a2b3c4d5e6f708192a3b4",
            marketExpectedSpec("x", "1.0.0", MarketInstallSource.Git("https://gitlab.example.com/g/s/r.git", "3f9c2e1d8a4b6c0e7f1a2b3c4d5e6f708192a3b4", null)),
        )
    }

    @Test fun aMissingCompatibilityBlockIsNotTreatedAsCompatible() {
        val row = """{"version":"1.0.0","publishTimestamp":1,"source":{"type":"npm"},"engines":{"dsh":"*"},"install":{"spec":"@example/dsh-x@1.0.0"}}"""
        val entry = parseMarketSearch(MarketFixtures.answer(listOf(MarketFixtures.entry(versions = listOf(row)))))!!.entries.single()
        assertFalse(entry.presented!!.compatibility.compatible)
        assertNotNull(entry.presented)
    }
}
