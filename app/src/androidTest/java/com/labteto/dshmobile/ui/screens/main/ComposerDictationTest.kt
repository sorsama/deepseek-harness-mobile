package com.labteto.dshmobile.ui.screens.main

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.PermissionSelect
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.voice.DictationProblem
import com.labteto.dshmobile.ui.voice.SpeechEngine
import com.labteto.dshmobile.ui.voice.SpeechListener
import com.labteto.dshmobile.ui.voice.noticeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** A recogniser that is there and silent: it makes the microphone appear and nothing else. */
internal object IdleSpeechEngine : SpeechEngine {
    override val available = true
    override fun start(language: String?, listener: SpeechListener) = Unit
    override fun stopListening() = Unit
    override fun cancel() = Unit
    override fun release() = Unit
}

/** A phone with no speech service. */
internal object UnavailableSpeechEngine : SpeechEngine {
    override val available = false
    override fun start(language: String?, listener: SpeechListener) = Unit
    override fun stopListening() = Unit
    override fun cancel() = Unit
    override fun release() = Unit
}

/** A recogniser the test plays: what the composer asked for is recorded, and the test answers. */
internal class ScriptedSpeechEngine : SpeechEngine {
    override val available = true
    var listener: SpeechListener? = null
    var starts = 0
    var stops = 0
    var cancels = 0
    override fun start(language: String?, listener: SpeechListener) { starts++; this.listener = listener }
    override fun stopListening() { stops++ }
    override fun cancel() { cancels++ }
    override fun release() = Unit
}

/**
 * Dictation in the composer, end to end up to the recogniser: the microphone, the listening state,
 * partial text staying out of the draft, the final text going in at the caret, cancel and errors.
 *
 * The recogniser is scripted, because an emulator may have no speech service; what the real one does
 * is a device check (see the pull request). The microphone permission is granted up front: the
 * system's own dialog is not something a test can drive.
 */
class ComposerDictationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = ScriptedSpeechEngine()
    private var draft by mutableStateOf("hello world")
    private var cursor by mutableIntStateOf(5)
    private var noticeSeen by mutableStateOf(true)
    private var noticed = 0
    private var language: String? = "unset"
    private var tick by mutableIntStateOf(0)

    @Before fun grantTheMicrophone() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    private fun show(speech: SpeechEngine = engine) {
        compose.setContent {
            DshTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    Composer(
                        draft = draft, onDraftChange = { draft = it }, attachments = emptyList(),
                        onRemoveAttachment = {}, onRetryAttachment = {},
                        permissions = PermissionSelect(currentValue = "custom"), pendingPermission = null, onPermissionPick = {},
                        contextBreakdown = null, contextPressure = null,
                        running = false, enabled = true, onOpenSheet = {}, onSend = {}, onStop = {},
                        cursor = cursor, onCursorChange = { cursor = it },
                        dictation = DictationConfig(
                            languageChoice = null, appLanguage = "th", noticeSeen = noticeSeen, sessionKey = "session-1",
                            toggleTick = tick,
                            onNoticeSeen = { noticed++; noticeSeen = true },
                            onLanguageChoice = { language = it },
                            engineFactory = { speech },
                        ),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = context.getString(id)

    private fun tapMicrophone() = compose.onNodeWithContentDescription(text(R.string.voice_start)).performClick()

    private fun tapStop() = compose.onNodeWithContentDescription(text(R.string.voice_stop)).performClick()

    private fun fromTheRecogniser(block: SpeechListener.() -> Unit) {
        compose.runOnUiThread { engine.listener!!.block() }
        compose.waitForIdle()
    }

    private fun listen() {
        tapMicrophone()
        compose.waitForIdle()
        assertEquals(1, engine.starts)
        fromTheRecogniser { onReady() }
    }

    @Test fun listeningIsShownInWordsAndTheButtonBecomesStop() {
        show()
        listen()
        compose.onNodeWithText(text(R.string.voice_listening)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.common_cancel)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.voice_stop)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.voice_start)).assertDoesNotExist()
    }

    @Test fun whatIsHeardStaysOutOfTheDraftUntilTheTakeEnds() {
        show()
        listen()
        fromTheRecogniser { onPartial("there", null) }
        assertEquals("hello world", draft)
        assertEquals(5, cursor)
    }

    @Test fun stoppingInsertsTheFinalTextAtTheCaretAsOrdinaryText() {
        show()
        listen()
        fromTheRecogniser { onPartial("ther", null) }
        tapStop()
        compose.waitForIdle()
        assertEquals(1, engine.stops)
        fromTheRecogniser { onResults(listOf("there")) }
        assertEquals("hello there world", draft)
        assertEquals("hello there".length, cursor)
        // Back to idle, and nothing was sent.
        compose.onNodeWithContentDescription(text(R.string.voice_start)).assertIsDisplayed()
    }

    @Test fun cancelThrowsTheTakeAwayAndKeepsTheDraft() {
        show()
        listen()
        fromTheRecogniser { onPartial("never mind", null) }
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.waitForIdle()
        assertEquals("hello world", draft)
        assertTrue(engine.cancels > 0)
        compose.onNodeWithContentDescription(text(R.string.voice_start)).assertIsDisplayed()
    }

    @Test fun anErrorShowsAShortMessageThatGoesAwayAndNeverTouchesTheDraft() {
        show()
        listen()
        // The message's own timer runs on the test clock, which is driven by hand so it cannot
        // run out between the error and the assertion.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { engine.listener!!.onProblem(DictationProblem.NoMatch) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(text(R.string.voice_err_no_match)).assertIsDisplayed()
        assertEquals("hello world", draft)
        compose.mainClock.advanceTimeBy(DictationProblem.NoMatch.noticeMillis() + 500)
        compose.onNodeWithText(text(R.string.voice_err_no_match)).assertDoesNotExist()
        assertEquals("hello world", draft)
    }

    @Test fun aBlockedPermissionOffersTheSystemSettings() {
        show()
        listen()
        fromTheRecogniser { onProblem(DictationProblem.PermissionBlocked) }
        compose.onNodeWithText(text(R.string.voice_perm_blocked)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.voice_open_settings)).assertIsDisplayed()
    }

    @Test fun theFirstUseSaysWhereTheSpeechGoesBeforeListening() {
        noticeSeen = false
        show()
        tapMicrophone()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.voice_notice_body)).assertIsDisplayed()
        assertEquals("nothing listens before the note is accepted", 0, engine.starts)
        compose.onNodeWithText(text(R.string.voice_notice_continue)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { engine.starts == 1 }
        assertEquals(1, noticed)
    }

    @Test fun holdingTheMicrophoneOffersTheLanguages() {
        show()
        compose.onNodeWithContentDescription(text(R.string.voice_start)).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.voice_language_follow_app)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.voice_language_device)).assertIsDisplayed()
        compose.onNodeWithText("Português").assertIsDisplayed()
        compose.onNodeWithText("ไทย").performClick()
        compose.waitForIdle()
        assertEquals("th", language)
    }

    @Test fun theShortcutStartsAndStopsLikeATap() {
        show()
        compose.runOnUiThread { tick++ }
        compose.waitForIdle()
        assertEquals(1, engine.starts)
        fromTheRecogniser { onReady() }
        compose.runOnUiThread { tick++ }
        compose.waitForIdle()
        assertEquals(1, engine.stops)
    }

    @Test fun aPhoneWithNoSpeechServiceHasNoMicrophone() {
        show(UnavailableSpeechEngine)
        compose.onNodeWithContentDescription(text(R.string.voice_start)).assertDoesNotExist()
        assertFalse(engine.starts > 0)
    }
}
