package com.labteto.dshmobile.ui.screens.main.browser

import com.labteto.dshmobile.core.browser.AddressRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The toolbar and the address field only ever describe what the Browser tab actually shows. */
class BrowserShownTest {
    @Test
    fun theStartPageHasNoAddressNoTitleAndNoMark() {
        val shown = browserShown(live = false, url = null, title = null, problem = null)
        assertNull(shown.address)
        assertEquals("", shown.title)
        assertFalse(shown.marked)
        assertFalse(shown.reloadable)
    }

    @Test
    fun aLoadedPageShowsItsAddressTitleAndMark() {
        val shown = browserShown(live = true, url = "http://10.0.2.2:8099/", title = "Test site", problem = null)
        assertEquals("http://10.0.2.2:8099/", shown.address)
        assertEquals("Test site", shown.title)
        assertTrue(shown.marked)
        assertTrue(shown.reloadable)
    }

    @Test
    fun aPageWithoutATitleShowsItsHost() {
        val shown = browserShown(live = true, url = "https://example.com/a/b", title = null, problem = null)
        assertEquals("example.com", shown.title)
    }

    @Test
    fun aClosedPanelLeavesNothingOfTheOldPageOnTheToolbar() {
        val state = BrowserTabState()
        state.url = "http://10.0.2.2:8099/"
        state.title = "Test site"
        state.live = true
        state.problem = BrowserProblem.LoadFailed("http://10.0.2.2:8099/x", "net::ERR_CONNECTION_REFUSED")
        state.canGoBack = true
        state.closePage()
        val shown = browserShown(state.live, state.url, state.title, state.problem)
        assertNull(shown.address)
        assertEquals("", shown.title)
        assertFalse(shown.marked)
        assertFalse(shown.reloadable)
        // Only the address is kept, for the start page's "Open again".
        assertEquals("http://10.0.2.2:8099/", state.lastUrl)
        assertNull(state.problem)
        assertFalse(state.canGoBack)
    }

    @Test
    fun aLinkWaitingForThePanelSurvivesTheClose() {
        val state = BrowserTabState()
        state.pending = "http://localhost:5173/"
        state.closePage()
        assertEquals("http://localhost:5173/", state.pending)
    }

    @Test
    fun theLastPageIsKeptWhenAPanelClosesOnTheStartPage() {
        val state = BrowserTabState()
        state.url = "https://example.com/"
        state.closePage()
        state.closePage()
        assertEquals("https://example.com/", state.lastUrl)
    }

    @Test
    fun theHarnessComputerPageNamesTheAddressItIsAboutNotThePageBehindIt() {
        val problem = BrowserProblem.HarnessLoopback("http://localhost:8099/", "http://10.0.2.2:8099/", relay = false)
        val shown = browserShown(live = true, url = "https://example.com/", title = "Example Domain", problem = problem)
        assertEquals("http://localhost:8099/", shown.address)
        assertEquals("localhost", shown.title)
        assertFalse(shown.marked)
        assertFalse(shown.reloadable)
    }

    @Test
    fun theHarnessItselfPageNamesItsOwnAddress() {
        val shown = browserShown(live = false, url = null, title = null, problem = BrowserProblem.HarnessItself("http://192.168.1.20:3080/"))
        assertEquals("http://192.168.1.20:3080/", shown.address)
        assertFalse(shown.marked)
    }

    @Test
    fun aRefusedAddressKeepsWhatWasTypedAndHasNoMark() {
        val problem = BrowserProblem.Refused(AddressRefusal.UnsupportedScheme, "javascript", typed = "javascript:alert(1)")
        val shown = browserShown(live = true, url = "https://example.com/", title = "Example Domain", problem = problem)
        assertEquals("javascript:alert(1)", shown.address)
        assertEquals("", shown.title)
        assertFalse(shown.marked)
        assertFalse(shown.reloadable)
    }

    @Test
    fun aFailedSecureLoadIsNeverMarkedSecure() {
        val problem = BrowserProblem.LoadFailed("https://example.com/", "net::ERR_CONNECTION_REFUSED")
        val shown = browserShown(live = false, url = "https://example.com/", title = null, problem = problem)
        assertEquals("https://example.com/", shown.address)
        assertFalse(shown.marked)
        assertTrue(shown.reloadable)
    }

    @Test
    fun aCertificateProblemIsNeverMarkedSecure() {
        val shown = browserShown(live = false, url = "https://expired.example/", title = null, problem = BrowserProblem.Tls("https://expired.example/"))
        assertFalse(shown.marked)
        assertTrue(shown.reloadable)
    }

    @Test
    fun aCrashedPageStillOffersRetryForItsAddress() {
        val shown = browserShown(live = false, url = "http://10.0.2.2:8099/", title = "Gone", problem = BrowserProblem.Crashed)
        assertEquals("http://10.0.2.2:8099/", shown.address)
        assertEquals("10.0.2.2", shown.title)
        assertFalse(shown.marked)
        assertTrue(shown.reloadable)
    }
}
