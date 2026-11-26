package com.labteto.dshmobile.ui.voice

import com.labteto.dshmobile.ui.screens.settings.LanguageOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationLanguageTest {

    @Test fun followingTheAppUsesTheAppLanguagesRecogniserTag() {
        assertEquals("th-TH", resolveDictationLanguage(null, "th"))
        assertEquals("zh-CN", resolveDictationLanguage(null, "zh"))
        assertEquals("pt-BR", resolveDictationLanguage(null, "pt"))
        assertEquals("en-US", resolveDictationLanguage(null, "en"))
    }

    @Test fun followingTheAppWithTheSystemLanguageLeavesItToThePhone() {
        assertNull(resolveDictationLanguage(null, null))
    }

    @Test fun deviceDefaultNamesNoLanguageWhateverTheAppIsShownIn() {
        assertNull(resolveDictationLanguage(DictationLanguageChoice.DEVICE, "th"))
        assertNull(resolveDictationLanguage(DictationLanguageChoice.DEVICE, null))
    }

    @Test fun anExplicitLanguageWinsOverTheAppLanguage() {
        assertEquals("fr-FR", resolveDictationLanguage("fr", "en"))
        assertEquals("es-ES", resolveDictationLanguage("es", null))
    }

    @Test fun anUnknownOrStaleChoiceFallsBackToTheDevice() {
        assertNull(resolveDictationLanguage("klingon", "en"))
        assertNull(resolveDictationLanguage("", "en"))
    }

    @Test fun regionsAndScriptsOnATagAreTolerated() {
        assertEquals("zh-CN", resolveDictationLanguage("zh-Hans", null))
        assertEquals("zh-CN", resolveDictationLanguage(null, "zh-CN"))
        assertEquals("pt-BR", resolveDictationLanguage("pt_PT", null))
        assertEquals("ar-SA", resolveDictationLanguage("AR", null))
    }

    @Test fun everyAppLanguageHasARecogniserTag() {
        val appLanguages = LanguageOptions.mapNotNull { it.tag }
        assertEquals(11, appLanguages.size)
        appLanguages.forEach { tag ->
            val resolved = resolveDictationLanguage(tag, null)
            assertTrue("$tag has no recogniser tag", resolved != null && resolved.startsWith("$tag-"))
        }
        assertEquals(appLanguages.toSet(), DictationLanguages.keys)
    }

    @Test fun aNamedLanguageIsFollowedByAnAttemptWithNone() {
        assertEquals(listOf("th-TH", null), languageAttempts(null, "th"))
        assertEquals(listOf("fr-FR", null), languageAttempts("fr", "en"))
    }

    @Test fun anUnnamedLanguageHasJustTheOneAttempt() {
        assertEquals(listOf(null), languageAttempts(null, null))
        assertEquals(listOf(null), languageAttempts(DictationLanguageChoice.DEVICE, "th"))
    }

    @Test fun theChoicesThePickerOffersAreTheKnownOnes() {
        assertTrue(isKnownDictationChoice(null))
        assertTrue(isKnownDictationChoice(DictationLanguageChoice.DEVICE))
        assertTrue(isKnownDictationChoice("ru"))
        assertFalse(isKnownDictationChoice("klingon"))
    }
}
