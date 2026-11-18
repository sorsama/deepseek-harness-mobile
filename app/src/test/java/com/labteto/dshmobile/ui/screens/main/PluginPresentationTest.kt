package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.PluginOperationState
import com.labteto.dshmobile.core.wire.dto.ManagedPlugin
import com.labteto.dshmobile.core.wire.dto.PluginBundle
import com.labteto.dshmobile.core.wire.dto.PluginBundleRow
import com.labteto.dshmobile.core.wire.dto.PluginChangeResult
import org.junit.Assert.*
import org.junit.Test

class PluginPresentationTest {
    @Test fun `catalog updates retain disabled selection and use the exact host target`() {
        val bundle = com.labteto.dshmobile.core.wire.dto.PluginBundle("codex", enabled = false, installed = true,
            availability = "profile", installTarget = com.labteto.dshmobile.core.wire.dto.PluginInstallTarget("codex@0.2.1-alpha.2", "0.2.1-alpha.2"))
        val update = bundle.catalogInstall(bundle.enabled)!!
        assertEquals("codex@0.2.1-alpha.2", update.target)
        assertFalse(update.enabled)
        assertEquals(true, update.saveExact)
        val enable = bundle.catalogInstall(true)!!
        val approval = PluginOperationState(subject = enable.target, enabled = enable.enabled, saveExact = enable.saveExact,
            result = PluginChangeResult(false, "failed", "install", "codex", pendingBuilds = listOf("addon@1"))).approvalSubject()!!
        assertTrue(approval.enabled)
        assertEquals(true, approval.saveExact)
        assertEquals(listOf("addon@1"), approval.approvedBuilds)
        assertNull(bundle.copy(installTarget = com.labteto.dshmobile.core.wire.dto.PluginInstallTarget("link:/source", "dev")).catalogInstall(true)?.saveExact)
    }
    @Test fun `approval captures operation package registry and scripts independently of form`() {
        val state = PluginOperationState(host = "host", requestId = "request", subject = "@scope/first@1", registry = "https://registry.example",
            result = PluginChangeResult(false, "failed", "install", "@scope/first", pendingBuilds = listOf("native-addon@2")))
        val approval = state.approvalSubject()!!
        val editedForm = PluginInstallSubject("other@9", "https://other.example")
        assertNotEquals(editedForm.target, approval.target)
        assertEquals("@scope/first@1", approval.target)
        assertEquals("https://registry.example", approval.registry)
        assertEquals(listOf("native-addon@2"), approval.approvedBuilds)
        assertEquals("@scope/first@1", state.copy(busy = false, unknown = true).displaySubject())
    }
    @Test fun `legacy unknown operation never exposes UUID as package or invents approval target`() {
        val state = PluginOperationState(host = "host", requestId = "opaque-id", unknown = true)
        assertNull(state.displaySubject())
        assertNull(state.approvalSubject())
    }
    @Test fun `successful noninstall mutation identifies its returned package`() {
        val state = PluginOperationState(result = PluginChangeResult(true, "applied", "enable", "package"))
        assertEquals("package", state.displaySubject())
        assertNull(state.approvalSubject())
    }

    // ---- Plugins page: short names ----

    @Test fun `short name drops the deepseek scope and one leading dsh prefix`() {
        assertEquals("session-title", pluginShortName("@deepseek-ai/dsh-session-title"))
        assertEquals("cordis-plugin-timer", pluginShortName("@deepseek-ai/cordis-plugin-timer"))
        assertEquals("plugin-manager/tools", pluginShortName("@deepseek-ai/dsh-plugin-manager/tools"))
        assertEquals("dsh-x", pluginShortName("@deepseek-ai/dsh-dsh-x"))
    }

    @Test fun `short name of a file URL is its last path segment`() {
        assertEquals("my-plugin", pluginShortName("file:///home/me/plugins/my-plugin"))
        assertEquals("my-plugin", pluginShortName("file:///home/me/plugins/my-plugin/"))
        assertEquals("tool", pluginShortName("file:///C:/work/tool"))
        assertEquals("file://", pluginShortName("file://"))
    }

    @Test fun `short name leaves every other name alone`() {
        assertEquals("@other/dsh-foo", pluginShortName("@other/dsh-foo"))
        assertEquals("cordis:include", pluginShortName("cordis:include"))
        assertEquals("dsh-foo", pluginShortName("dsh-foo"))
        assertEquals("plain", pluginShortName("plain"))
        assertEquals("@deepseek-ai/", pluginShortName("@deepseek-ai/"))
        assertEquals("@deepseek-ai/dsh-", pluginShortName("@deepseek-ai/dsh-"))
    }

    @Test fun `names that would shorten to the same text keep their full name`() {
        val names = pluginShortNames(listOf("@deepseek-ai/dsh-foo", "@deepseek-ai/foo", "@deepseek-ai/dsh-bar", "file:///x/baz", "baz", "qux"))
        assertEquals("@deepseek-ai/dsh-foo", names["@deepseek-ai/dsh-foo"])
        assertEquals("@deepseek-ai/foo", names["@deepseek-ai/foo"])
        assertEquals("bar", names["@deepseek-ai/dsh-bar"])
        assertEquals("file:///x/baz", names["file:///x/baz"])
        assertEquals("baz", names["baz"])
        assertEquals("qux", names["qux"])
    }

    @Test fun `the same name listed twice is not a collision`() {
        assertEquals(mapOf("@deepseek-ai/dsh-foo" to "foo"), pluginShortNames(listOf("@deepseek-ai/dsh-foo", "@deepseek-ai/dsh-foo")))
    }

    // ---- filters and counts ----

    private fun row(id: String, module: String, entry: String? = id) = PluginBundleRow(id, module, entry)
    private fun plugin(entry: String, module: String, enabled: Boolean = true, reason: String? = null) =
        ManagedPlugin(entry, module, enabled, readOnlyReason = reason)
    private fun bundle(name: String, rows: List<PluginBundleRow> = emptyList(), enabled: Boolean = true, description: String? = null) =
        PluginBundle(name, enabled = enabled, installed = true, description = description, rows = rows)

    @Test fun `filter accepts follow the on and off meaning and a stateless row only shows under all`() {
        assertTrue(PluginFilter.ALL.accepts(true) && PluginFilter.ALL.accepts(false) && PluginFilter.ALL.accepts(null))
        assertTrue(PluginFilter.ON.accepts(true)); assertFalse(PluginFilter.ON.accepts(false)); assertFalse(PluginFilter.ON.accepts(null))
        assertTrue(PluginFilter.OFF.accepts(false)); assertFalse(PluginFilter.OFF.accepts(true)); assertFalse(PluginFilter.OFF.accepts(null))
    }

    @Test fun `filter counts add up from the states`() {
        val counts = pluginFilterCounts(listOf(true, false, null, true))
        assertEquals(PluginFilterCounts(all = 4, on = 2, off = 1), counts)
        assertEquals(4, counts[PluginFilter.ALL]); assertEquals(2, counts[PluginFilter.ON]); assertEquals(1, counts[PluginFilter.OFF])
        assertEquals(PluginFilterCounts(0, 0, 0), pluginFilterCounts(emptyList()))
    }

    private val alpha = bundle("@deepseek-ai/dsh-alpha", listOf(row("r1", "@deepseek-ai/dsh-alpha/one", "e1"), row("r2", "@deepseek-ai/dsh-alpha/two", "e2")), enabled = true, description = "First bundle")
    private val beta = bundle("@deepseek-ai/dsh-beta", listOf(row("r3", "@deepseek-ai/dsh-beta/timer", "e3")), enabled = false)
    private val listPlugins = listOf(
        plugin("e1", "@deepseek-ai/dsh-alpha/one"), plugin("e2", "@deepseek-ai/dsh-alpha/two"), plugin("e3", "@deepseek-ai/dsh-beta/timer", enabled = false),
        plugin("s1", "@deepseek-ai/cordis-plugin-timer", enabled = true), plugin("s2", "@other/standalone", enabled = false),
    )

    @Test fun `list model separates standalone plugins from bundled ones and counts both`() {
        val model = pluginListModel(listOf(alpha, beta), listPlugins, "", PluginFilter.ALL)
        assertEquals(listOf(alpha, beta), model.bundles)
        assertEquals(listOf("s1", "s2"), model.standalone.map { it.entryId })
        assertEquals(PluginFilterCounts(all = 4, on = 2, off = 2), model.counts)
        assertEquals("alpha", model.names["@deepseek-ai/dsh-alpha"])
        assertEquals("cordis-plugin-timer", model.names["@deepseek-ai/cordis-plugin-timer"])
        assertEquals("@other/standalone", model.names["@other/standalone"])
    }

    @Test fun `list chips filter bundles and standalone plugins by their own switch`() {
        val on = pluginListModel(listOf(alpha, beta), listPlugins, "", PluginFilter.ON)
        assertEquals(listOf(alpha), on.bundles); assertEquals(listOf("s1"), on.standalone.map { it.entryId })
        val off = pluginListModel(listOf(alpha, beta), listPlugins, "", PluginFilter.OFF)
        assertEquals(listOf(beta), off.bundles); assertEquals(listOf("s2"), off.standalone.map { it.entryId })
        // The counts do not move with the chip.
        assertEquals(on.counts, off.counts)
    }

    @Test fun `list counts are taken after the search text`() {
        val model = pluginListModel(listOf(alpha, beta), listPlugins, "timer", PluginFilter.ALL)
        assertEquals(listOf(beta), model.bundles)
        assertEquals(listOf("s1"), model.standalone.map { it.entryId })
        assertEquals(PluginFilterCounts(all = 2, on = 1, off = 1), model.counts)
        assertEquals(listOf(alpha), pluginListModel(listOf(alpha, beta), listPlugins, "first", PluginFilter.ALL).bundles)
        assertTrue(pluginListModel(listOf(alpha, beta), listPlugins, "nothing like this", PluginFilter.ALL).let { it.bundles.isEmpty() && it.standalone.isEmpty() })
    }

    @Test fun `a chip never renames an item because short names come from the whole list`() {
        val twin = bundle("@deepseek-ai/alpha", enabled = false)
        val all = pluginListModel(listOf(alpha, twin), emptyList(), "", PluginFilter.ALL)
        val on = pluginListModel(listOf(alpha, twin), emptyList(), "", PluginFilter.ON)
        assertEquals(listOf(alpha), on.bundles)
        assertEquals("@deepseek-ai/dsh-alpha", on.names["@deepseek-ai/dsh-alpha"])
        assertEquals(all.names, on.names)
    }

    @Test fun `installed and available follow the availability then the installed flag`() {
        assertTrue(bundle("a").listedAsInstalled())
        assertFalse(bundle("a").copy(installed = false).listedAsInstalled())
        assertFalse(bundle("a").copy(availability = "missing").listedAsInstalled())
        assertTrue(bundle("a").copy(installed = false, availability = "profile").listedAsInstalled())
    }

    // ---- search matches ----

    @Test fun `a bundle matches by name description or any module name ignoring case`() {
        assertTrue(alpha.matchesSearch("ALPHA"))
        assertTrue(alpha.matchesSearch("first bundle"))
        assertTrue(beta.matchesSearch("TIMER"))
        assertFalse(alpha.matchesSearch("timer"))
        assertTrue(alpha.matchesSearch(""))
    }

    private val suite = bundle("@deepseek-ai/dsh-suite", description = "tools",
        rows = listOf("a", "b", "c", "d", "e").mapIndexed { i, n -> row("r$i", "@deepseek-ai/dsh-suite/$n-probe", "e$i") })

    @Test fun `rows that matched name at most three short names and count the rest`() {
        val matches = suite.rowMatches("probe") { pluginShortName(it) }!!
        assertEquals(listOf("suite/a-probe", "suite/b-probe", "suite/c-probe"), matches.names)
        assertEquals(2, matches.more)
        val few = suite.rowMatches("b-probe") { pluginShortName(it) }!!
        assertEquals(listOf("suite/b-probe"), few.names); assertEquals(0, few.more)
        val everything = suite.rowMatches("-probe", limit = 5) { pluginShortName(it) }!!
        assertEquals(5, everything.names.size); assertEquals(0, everything.more)
    }

    @Test fun `no row names show when the bundle itself matched or nothing was typed or no row matched`() {
        assertNull(suite.rowMatches("suite") { pluginShortName(it) })
        assertNull(suite.rowMatches("tools") { pluginShortName(it) })
        assertNull(suite.rowMatches("") { pluginShortName(it) })
        assertNull(suite.rowMatches("absent") { pluginShortName(it) })
    }

    @Test fun `only a row only match carries its search text to the detail page`() {
        assertEquals("probe", suite.searchCarriedToDetail("probe"))
        assertEquals("", suite.searchCarriedToDetail("suite"))
        assertEquals("", suite.searchCarriedToDetail(""))
        assertEquals("", suite.searchCarriedToDetail("absent"))
    }

    // ---- bundle detail ----

    private val modulePlugins = listOf(
        plugin("e1", "m1", enabled = true),
        plugin("e2", "m2", enabled = true, reason = "management-required"),
        plugin("e3", "m3", enabled = false),
        plugin("e4", "m4", enabled = false, reason = "unaddressable"),
        plugin("e5", "m5", enabled = false, reason = "management-required"),
    ).associateBy { it.entryId }
    private val moduleBundle = bundle("@deepseek-ai/dsh-suite", listOf(
        row("r1", "m1", "e1"), row("r2", "m2", "e2"), row("r3", "m3", "e3"), row("r4", "m4", "e4"), row("r5", "m5", "e5"),
        row("r6", "m6", null), row("r7", "m7", "ghost"),
    ))
    private val moduleRows = pluginModuleRows(moduleBundle, modulePlugins)

    @Test fun `module rows resolve their managed plugin and keep rows with none`() {
        assertEquals(7, moduleRows.size)
        assertEquals("e1", moduleRows[0].plugin?.entryId)
        assertNull(moduleRows[5].plugin)
        assertNull(moduleRows[6].plugin)
        assertEquals(listOf("r1"), pluginModuleRows(bundle("b", listOf(row("r1", "a", "e1"), row("r1", "a", "e1"))), modulePlugins).map { it.row.rowId })
    }

    @Test fun `enabled count is the enabled rows out of the rows with a managed plugin`() {
        assertEquals(PluginEnabledCount(on = 2, total = 5), pluginEnabledCount(moduleRows))
        assertNull(pluginEnabledCount(pluginModuleRows(bundle("b", listOf(row("r6", "m6", null))), modulePlugins)))
        assertNull(pluginEnabledCount(emptyList()))
    }

    @Test fun `detail rows split into switches then locked groups by reason then plain rows`() {
        val model = pluginDetailModel(moduleRows, "", PluginFilter.ALL)
        assertEquals(listOf("r1", "r3"), model.toggleable.map { it.row.rowId })
        assertEquals(listOf("management-required", "unaddressable"), model.locked.map { it.reason })
        assertEquals(listOf("r2", "r5"), model.locked[0].rows.map { it.row.rowId })
        assertEquals(listOf("r4"), model.locked[1].rows.map { it.row.rowId })
        assertEquals(listOf("r6", "r7"), model.plain.map { it.row.rowId })
        assertEquals(PluginFilterCounts(all = 7, on = 2, off = 3), model.counts)
        assertFalse(model.isEmpty)
    }

    @Test fun `detail chips apply to locked rows too and hide rows with no state`() {
        val on = pluginDetailModel(moduleRows, "", PluginFilter.ON)
        assertEquals(listOf("r1"), on.toggleable.map { it.row.rowId })
        assertEquals(listOf("management-required"), on.locked.map { it.reason })
        assertEquals(listOf("r2"), on.locked[0].rows.map { it.row.rowId })
        assertTrue(on.plain.isEmpty())
        val off = pluginDetailModel(moduleRows, "", PluginFilter.OFF)
        assertEquals(listOf("r3"), off.toggleable.map { it.row.rowId })
        assertEquals(listOf("unaddressable", "management-required"), off.locked.map { it.reason })
        assertEquals(on.counts, off.counts)
    }

    @Test fun `detail search matches the full module name and counts what it found`() {
        val model = pluginDetailModel(moduleRows, "M3", PluginFilter.ALL)
        assertEquals(listOf("r3"), model.toggleable.map { it.row.rowId })
        assertTrue(model.locked.isEmpty() && model.plain.isEmpty())
        assertEquals(PluginFilterCounts(all = 1, on = 0, off = 1), model.counts)
        assertTrue(pluginDetailModel(moduleRows, "nothing", PluginFilter.ALL).isEmpty)
    }

    // ---- one module loaded by two rows: the shape the base bundle really has ----

    private val subagentModule = "@deepseek-ai/dsh-tool-subagent"
    private val baseLike = bundle("@deepseek-ai/dsh-base", listOf(
        row("tool-subagent-control", "@deepseek-ai/dsh-tool-subagent-control", "include:tool-subagent-control"),
        row("tool-subagent-list-agents", "@deepseek-ai/dsh-tool-subagent-control/list-agents", "include:tool-subagent-list-agents"),
        row("tool-subagent", subagentModule, "include:tool-subagent"),
        row("tool-subagent-fork", subagentModule, "include:tool-subagent-fork"),
        row("ptc-runtime", "@deepseek-ai/dsh-ptc-runtime-node", "include:ptc-runtime"),
    ))
    private val baseLikePlugins = baseLike.rows.associate { it.entryId!! to plugin(it.entryId!!, it.moduleName, enabled = false) }

    @Test fun `two rows of one module both stay on the page and draw the same name`() {
        val rows = pluginModuleRows(baseLike, baseLikePlugins)
        assertEquals(5, rows.size)
        val names = pluginShortNames(rows.map { it.row.moduleName })
        assertEquals("tool-subagent", names[subagentModule])
        assertEquals(listOf("tool-subagent", "tool-subagent"), rows.filter { it.row.moduleName == subagentModule }.map { names.getValue(it.row.moduleName) })
    }

    @Test fun `the row id tells the two rows of one module apart`() {
        val rows = pluginModuleRows(baseLike, baseLikePlugins)
        val qualifiers = pluginRowQualifiers(rows, pluginShortNames(rows.map { it.row.moduleName }))
        // The row whose id is the drawn name needs no second line; the fork row shows its own id.
        assertEquals(mapOf("tool-subagent-fork" to "tool-subagent-fork"), qualifiers)
    }

    @Test fun `every row of a shared module is labelled when none of the ids is the drawn name`() {
        val twin = bundle("b", listOf(row("first", "@x/module", "e1"), row("second", "@x/module", "e2"), row("alone", "@x/other", "e3")))
        val rows = pluginModuleRows(twin, emptyMap())
        val qualifiers = pluginRowQualifiers(rows, pluginShortNames(rows.map { it.row.moduleName }))
        assertEquals(mapOf("first" to "first", "second" to "second"), qualifiers)
    }

    @Test fun `rows with their own module name get no label`() {
        val rows = pluginModuleRows(moduleBundle, modulePlugins)
        assertTrue(pluginRowQualifiers(rows, pluginShortNames(rows.map { it.row.moduleName })).isEmpty())
        assertTrue(pluginRowQualifiers(emptyList(), emptyMap()).isEmpty())
    }

    @Test fun `a search that matches both rows of a module names it once`() {
        val twins = bundle("dup", listOf(row("tool-subagent", subagentModule, "e1"), row("tool-subagent-fork", subagentModule, "e2")))
        val matches = twins.rowMatches("subagent") { pluginShortName(it) }!!
        // Two rows matched but they are one module, so the card names it once.
        assertEquals(listOf("tool-subagent"), matches.names)
        assertEquals(0, matches.more)
        val both = baseLike.rowMatches("tool-subagent") { pluginShortName(it) }!!
        assertEquals(listOf("tool-subagent-control", "tool-subagent-control/list-agents", "tool-subagent"), both.names)
        assertEquals(0, both.more)
    }

    // ---- standalone plugins: the locked ones are grouped by their reason ----

    @Test fun `locked standalone plugins group by reason and the others keep their switch`() {
        val model = pluginStandaloneModel(listOf(
            plugin("s1", "free"),
            plugin("u1", "cordis:include", enabled = true, reason = "unaddressable"),
            plugin("m1", "manager", enabled = true, reason = "management-required"),
            plugin("u2", "picker", enabled = false, reason = "unaddressable"),
        ))
        assertEquals(listOf("s1"), model.open.map { it.entryId })
        assertEquals(listOf("unaddressable", "management-required"), model.locked.map { it.reason })
        assertEquals(listOf("u1", "u2"), model.locked[0].plugins.map { it.entryId })
        assertEquals(listOf("m1"), model.locked[1].plugins.map { it.entryId })
        assertEquals(PluginStandaloneModel(emptyList(), emptyList()), pluginStandaloneModel(emptyList()))
    }

    // ---- operation cards: the same names the list uses, and no line that repeats the tag ----

    private val ralph = bundle("@deepseek-ai/dsh-experimental-ralph-bundle", listOf(row("ralph", "@deepseek-ai/dsh-experimental-ralph", "e-ralph")), enabled = false)
    private val reminder = bundle("@deepseek-ai/dsh-base", listOf(
        row("repeat-tool-reminder", "@deepseek-ai/dsh-repeat-tool-reminder", "include:repeat-tool-reminder"),
        row("a", "@deepseek-ai/dsh-clash", "include:a"),
        row("b", "@deepseek-ai/clash", "include:b"),
    ))
    private val subjectPlugins = listOf(
        plugin("e-ralph", "@deepseek-ai/dsh-experimental-ralph"), plugin("include:repeat-tool-reminder", "@deepseek-ai/dsh-repeat-tool-reminder"),
        plugin("include:a", "@deepseek-ai/dsh-clash"), plugin("include:b", "@deepseek-ai/clash"),
        plugin("s1", "@deepseek-ai/cordis-plugin-timer"), plugin("s2", "@other/standalone"),
    )
    private val subjectNames = pluginSubjectNames(
        listOf(ralph, reminder), subjectPlugins,
        pluginListModel(listOf(ralph, reminder), subjectPlugins, "", PluginFilter.ALL).names,
    )

    @Test fun `an operation card resolves a bundle name and a module entry id to the list names`() {
        assertEquals("experimental-ralph-bundle", subjectNames["@deepseek-ai/dsh-experimental-ralph-bundle"])
        assertEquals("base", subjectNames["@deepseek-ai/dsh-base"])
        assertEquals("repeat-tool-reminder", subjectNames["include:repeat-tool-reminder"])
        assertEquals("experimental-ralph", subjectNames["e-ralph"])
    }

    @Test fun `a standalone plugin entry resolves to the name the list draws for it`() {
        assertEquals("cordis-plugin-timer", subjectNames["s1"])
        assertEquals("@other/standalone", subjectNames["s2"])
    }

    @Test fun `a module that clashes inside its bundle keeps the full name as its page draws it`() {
        assertEquals("@deepseek-ai/dsh-clash", subjectNames["include:a"])
        assertEquals("@deepseek-ai/clash", subjectNames["include:b"])
    }

    @Test fun `an unknown subject is not in the map so the card keeps its own text`() {
        assertNull(subjectNames["@deepseek-ai/dsh-not-installed@1.2.3"])
        assertNull(subjectNames["opaque-id"])
        assertTrue(pluginSubjectNames(emptyList(), emptyList(), emptyMap()).isEmpty())
    }

    @Test fun `a status line that repeats its tag is dropped`() {
        assertNull(pluginStatusLine("applied", busy = false, unknown = false, phase = null))
        assertNull(pluginStatusLine("restart-required", busy = false, unknown = false, phase = null))
        assertNull(pluginStatusLine("failed", busy = false, unknown = false, phase = null))
        assertEquals(com.labteto.dshmobile.R.string.harness_applied, pluginTagLabel("applied", busy = false, unknown = false, failed = false))
    }

    @Test fun `a status line that adds something is kept`() {
        assertEquals(com.labteto.dshmobile.R.string.harness_cancelled, pluginStatusLine("cancelled", busy = false, unknown = false, phase = null))
        assertEquals(com.labteto.dshmobile.R.string.harness_overridden, pluginStatusLine("overridden", busy = false, unknown = false, phase = null))
        assertEquals(pluginPhase("installing"), pluginStatusLine(null, busy = true, unknown = false, phase = "installing"))
        assertEquals(com.labteto.dshmobile.R.string.ux_a_check_status, pluginStatusLine(null, busy = false, unknown = true, phase = null))
    }

    // ---- rows with no managed plugin behind them ----

    @Test fun `rows of an installed bundle that is off say the bundle is off`() {
        assertEquals(PluginPlainNote.BUNDLE_OFF, bundle("a", enabled = false).plainRowsNote())
        assertEquals(PluginPlainNote.BUNDLE_OFF, bundle("a", enabled = false).copy(installed = false, availability = "installation").plainRowsNote())
    }

    @Test fun `rows of a bundle that is not installed say it is not installed`() {
        assertEquals(PluginPlainNote.NOT_INSTALLED, bundle("a", enabled = false).copy(installed = false, availability = "missing").plainRowsNote())
    }

    @Test fun `a bundle that is on says nothing about rows without a plugin`() {
        assertNull(bundle("a", enabled = true).plainRowsNote())
    }

    @Test fun `detail search and chips appear only above eight rows`() {
        assertFalse(pluginDetailShowsControls(0))
        assertFalse(pluginDetailShowsControls(8))
        assertTrue(pluginDetailShowsControls(9))
    }

    // ---- an operation card before the list has loaded ----

    @Test fun `a module entry id never reads as a raw id while the list loads`() {
        // The real subject from the device run: the list was still loading, so the name map was empty.
        assertEquals("repeat-tool-reminder", pluginSubjectText("include:repeat-tool-reminder", emptyMap()))
        assertEquals("repeat-tool-reminder", pluginSubjectFallback("include:repeat-tool-reminder"))
    }

    @Test fun `the fallback applies the list's short name rule to what follows the prefix`() {
        assertEquals("tool-subagent", pluginSubjectFallback("include:@deepseek-ai/dsh-tool-subagent"))
        assertEquals("@other/pkg", pluginSubjectFallback("include:@other/pkg"))
    }

    @Test fun `a subject with no known prefix or only the prefix keeps its own text`() {
        assertEquals("e-ralph", pluginSubjectFallback("e-ralph"))
        assertEquals("@deepseek-ai/dsh-not-installed@1.2.3", pluginSubjectFallback("@deepseek-ai/dsh-not-installed@1.2.3"))
        assertEquals("include:", pluginSubjectFallback("include:"))
    }

    @Test fun `the list's own name wins over the fallback once the list has loaded`() {
        val names = mapOf("include:a" to "@deepseek-ai/dsh-clash")
        assertEquals("@deepseek-ai/dsh-clash", pluginSubjectText("include:a", names))
    }

    // ---- the meta line under a bundle's name ----

    @Test fun `the meta line joins version and count with a separator that only breaks after the dot`() {
        assertEquals("0.2.1-rc.2 · 71 of 96 on", pluginMetaLine("0.2.1-rc.2", "71 of 96 on"))
        assertEquals("71 of 96 on", pluginMetaLine(null, "71 of 96 on"))
        assertEquals("0.2.1-rc.2", pluginMetaLine("0.2.1-rc.2", null))
        assertEquals("", pluginMetaLine(null, null))
    }

    @Test fun `every language keeps the count phrase on one line`() {
        val res = java.io.File("src/main/res")
        val directories = res.listFiles()!!.filter { it.name == "values" || it.name.startsWith("values-") }
        val counts = directories.mapNotNull { directory ->
            val file = java.io.File(directory, "strings_ux_a.xml").takeIf { it.exists() } ?: return@mapNotNull null
            val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings = document.getElementsByTagName("string")
            (0 until strings.length).map { strings.item(it) }
                .firstOrNull { it.attributes.getNamedItem("name").nodeValue == "plugins_count_on" }
                ?.let { directory.name to it.textContent }
        }
        // The base language and the ten translations.
        assertEquals(11, counts.size)
        counts.forEach { (language, text) ->
            assertTrue("$language keeps both numbers", text.contains("%1\$d") && text.contains("%2\$d"))
            assertFalse("$language has a breaking space inside the count phrase: $text", text.contains(' '))
            assertTrue("$language joins its words with no-break spaces", text.contains(' '))
        }
    }
}
