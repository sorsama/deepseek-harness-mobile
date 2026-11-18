package com.labteto.dshmobile.ui.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationProblemTest {

    @Test fun theNumbersMatchThePlatformsConstants() {
        // The mapping spells the codes out; these are what the platform class says they are.
        assertEquals(SpeechRecognizer.ERROR_NETWORK_TIMEOUT, RecognizerCodes.NETWORK_TIMEOUT)
        assertEquals(SpeechRecognizer.ERROR_NETWORK, RecognizerCodes.NETWORK)
        assertEquals(SpeechRecognizer.ERROR_AUDIO, RecognizerCodes.AUDIO)
        assertEquals(SpeechRecognizer.ERROR_SERVER, RecognizerCodes.SERVER)
        assertEquals(SpeechRecognizer.ERROR_CLIENT, RecognizerCodes.CLIENT)
        assertEquals(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, RecognizerCodes.SPEECH_TIMEOUT)
        assertEquals(SpeechRecognizer.ERROR_NO_MATCH, RecognizerCodes.NO_MATCH)
        assertEquals(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, RecognizerCodes.RECOGNIZER_BUSY)
        assertEquals(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, RecognizerCodes.INSUFFICIENT_PERMISSIONS)
        assertEquals(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, RecognizerCodes.TOO_MANY_REQUESTS)
        assertEquals(SpeechRecognizer.ERROR_SERVER_DISCONNECTED, RecognizerCodes.SERVER_DISCONNECTED)
        assertEquals(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, RecognizerCodes.LANGUAGE_NOT_SUPPORTED)
        assertEquals(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, RecognizerCodes.LANGUAGE_UNAVAILABLE)
        assertEquals(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT, RecognizerCodes.CANNOT_CHECK_SUPPORT)
        assertEquals(SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS, RecognizerCodes.CANNOT_LISTEN_TO_DOWNLOAD_EVENTS)
    }

    @Test fun networkCodesAreTheNetworkProblem() {
        assertEquals(DictationProblem.Network, dictationProblemFor(1))
        assertEquals(DictationProblem.Network, dictationProblemFor(2))
    }

    @Test fun silenceAndUnclearSpeechAreTwoDifferentProblems() {
        assertEquals(DictationProblem.Timeout, dictationProblemFor(6))
        assertEquals(DictationProblem.NoMatch, dictationProblemFor(7))
    }

    @Test fun audioAndBusyAndServiceHaveTheirOwn() {
        assertEquals(DictationProblem.Audio, dictationProblemFor(3))
        assertEquals(DictationProblem.Busy, dictationProblemFor(8))
        assertEquals(DictationProblem.Busy, dictationProblemFor(10))
        assertEquals(DictationProblem.Service, dictationProblemFor(4))
        assertEquals(DictationProblem.Service, dictationProblemFor(11))
    }

    @Test fun missingLanguageCodesAreTheLanguageProblemTheControllerRetries() {
        assertEquals(DictationProblem.Language, dictationProblemFor(12))
        assertEquals(DictationProblem.Language, dictationProblemFor(13))
    }

    @Test fun aPermissionLostMidTakeIsTheBlockedProblem() {
        assertEquals(DictationProblem.PermissionBlocked, dictationProblemFor(9))
    }

    @Test fun aClientErrorMidTakeIsAnInterruptionNotAFailureAndNeverAFocusMessage() {
        // A call or another app taking the microphone shows up as the recogniser's own error code.
        assertEquals(DictationProblem.Interrupted, dictationProblemFor(5))
    }

    @Test fun unknownAndNewestCodesAreAGenericFailure() {
        assertEquals(DictationProblem.Failed, dictationProblemFor(14))
        assertEquals(DictationProblem.Failed, dictationProblemFor(15))
        assertEquals(DictationProblem.Failed, dictationProblemFor(0))
        assertEquals(DictationProblem.Failed, dictationProblemFor(-1))
        assertEquals(DictationProblem.Failed, dictationProblemFor(9999))
    }

    @Test fun onlyABlockedPermissionOffersTheSettings() {
        DictationProblem.entries.forEach { problem ->
            assertEquals(problem.name, problem == DictationProblem.PermissionBlocked, problem.offersSettings)
        }
    }

    @Test fun aMessageWithAnActionStaysLongerThanOneWithout() {
        assertTrue(DictationProblem.PermissionBlocked.noticeMillis() > DictationProblem.NoMatch.noticeMillis())
        assertFalse(DictationProblem.NoMatch.noticeMillis() <= 0)
    }
}
