package com.labteto.dshmobile.ui.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictationTextTest {

    private fun insert(draft: String, cursor: Int, spoken: String) = insertDictation(draft, cursor, spoken)!!

    // ---- inserting at the caret ----

    @Test fun anEmptyDraftTakesThePhraseAsIs() {
        val result = insert("", 0, "hello world")
        assertEquals("hello world", result.text)
        assertEquals(11, result.cursor)
    }

    @Test fun phraseGoesAtTheCaretAndTheCaretEndsAfterIt() {
        val result = insert("fix the bug", 7, "failing")
        assertEquals("fix the failing bug", result.text)
        assertEquals("fix the failing".length, result.cursor)
    }

    @Test fun appendingAfterAWordAddsOneSpace() {
        val result = insert("hello", 5, "world")
        assertEquals("hello world", result.text)
        assertEquals(11, result.cursor)
    }

    @Test fun noSecondSpaceWhenTheDraftAlreadyEndsInOne() {
        val result = insert("hello ", 6, "world")
        assertEquals("hello world", result.text)
    }

    @Test fun noSpaceAfterANewline() {
        val result = insert("first line\n", 11, "second")
        assertEquals("first line\nsecond", result.text)
    }

    @Test fun insertingIntoTheMiddleOfAWordSplitsItWithSpacesOnBothSides() {
        val result = insert("foobar", 3, "and")
        assertEquals("foo and bar", result.text)
        assertEquals(7, result.cursor)
    }

    @Test fun nothingIsAddedBeforeClosingPunctuationOrAfterAnOpeningOne() {
        assertEquals("see (note)", insert("see ()", 5, "note").text)
        assertEquals("a note, b", insert("a, b", 1, "note").text)
        assertEquals("@file", insert("@", 1, "file").text)
        assertEquals("/permission", insert("/", 1, "permission").text)
    }

    @Test fun aSentenceEndAndTheNextTakeAreSeparatedByASpace() {
        assertEquals("Done. Next", insert("Done.", 5, "Next").text)
    }

    @Test fun surroundingWhitespaceOfThePhraseIsTrimmed() {
        assertEquals("a b", insert("a", 1, "  b \n").text)
    }

    @Test fun aBlankPhraseChangesNothing() {
        assertNull(insertDictation("keep me", 4, ""))
        assertNull(insertDictation("keep me", 4, "   \n "))
    }

    @Test fun theExistingDraftIsNeverLostOrReordered() {
        val draft = "line one\nline two with @file.md and more"
        // Wherever the caret is, everything before it and everything after it is still there,
        // byte for byte, and only the phrase (plus at most a space on a side) sits between.
        for (cursor in listOf(0, 4, 8, 9, 20, draft.length)) {
            val result = insert(draft, cursor, "spoken")
            assertEquals("start, cursor $cursor", draft.take(cursor), result.text.take(cursor))
            assertEquals("end, cursor $cursor", draft.drop(cursor), result.text.takeLast(draft.length - cursor))
            val middle = result.text.drop(cursor).dropLast(draft.length - cursor)
            assertEquals("middle, cursor $cursor", "spoken", middle.trim())
        }
    }

    @Test fun aCaretOutsideTheDraftIsClamped() {
        assertEquals("ab c", insert("ab", 99, "c").text)
        assertEquals("c ab", insert("ab", -5, "c").text)
    }

    @Test fun aCaretInTheMiddleOfASurrogatePairMovesBeforeIt() {
        val draft = "a😀b" // a, one emoji, b
        val result = insert(draft, 2, "x")
        assertEquals("a x 😀b", result.text)
    }

    // ---- scripts written without spaces ----

    @Test fun twoChineseTakesJoinWithoutASpace() {
        val result = insert("你好", 2, "世界")
        assertEquals("你好世界", result.text)
        assertEquals(4, result.cursor)
    }

    @Test fun chineseIntoTheMiddleOfAChineseSentenceNeedsNoSpaces() {
        val result = insert("我喜欢苹果", 3, "吃")
        assertEquals("我喜欢吃苹果", result.text)
        assertEquals(4, result.cursor)
    }

    @Test fun twoThaiTakesJoinWithoutASpace() {
        val result = insert("สวัสดี", "สวัสดี".length, "ครับ")
        assertEquals("สวัสดีครับ", result.text)
        assertEquals("สวัสดีครับ".length, result.cursor)
    }

    @Test fun thaiIntoTheMiddleOfThaiNeedsNoSpaces() {
        val draft = "ฉันชอบกิน"
        // Between "ฉันชอบ" (6 code units) and "กิน".
        val result = insert(draft, 6, "มาก")
        assertEquals("ฉันชอบมากกิน", result.text)
        assertEquals(9, result.cursor)
    }

    @Test fun aLatinWordBesideChineseOrThaiIsNotPushedAway() {
        assertEquals("打开Android设置", insert("打开设置", 2, "Android").text)
        assertEquals("เปิดGitHub", insert("เปิด", 4, "GitHub").text)
    }

    @Test fun japaneseKanaAndKanjiJoinWithoutASpace() {
        assertEquals("こんにちは世界", insert("こんにちは", 5, "世界").text)
        assertEquals("ありがとう", insert("ありが", 3, "とう").text)
    }

    @Test fun fullWidthPunctuationNeedsNoSpaceEither() {
        assertEquals("你好，世界", insert("你好，", 3, "世界").text)
        assertEquals("你好。", insert("你好", 2, "。").text)
    }

    @Test fun koreanKeepsItsSpacesBetweenWords() {
        assertEquals("안녕하세요 세계", insert("안녕하세요", 5, "세계").text)
    }

    @Test fun arabicAndHindiKeepTheirSpacesAndTheirOwnPunctuationRules() {
        assertEquals("مرحبا بالعالم", insert("مرحبا", 5, "بالعالم").text)
        assertEquals("नमस्ते दुनिया", insert("नमस्ते", 6, "दुनिया").text)
        // The Arabic comma and the Devanagari danda close a clause, so nothing is put before them.
        assertEquals("مرحبا، بالعالم", insert("مرحبا،", 6, "بالعالم").text)
        val hindi = "मैं जा रहा हूँ"
        assertEquals("मैं जा रहा हूँ।", insert(hindi, hindi.length, "।").text)
    }

    // ---- merging partial results ----

    @Test fun aPartialWithOnlyStableTextIsThatText() {
        assertEquals("hello wor", mergePartial("hello wor", null))
        assertEquals("hello wor", mergePartial("hello wor", ""))
    }

    @Test fun aPartialWithOnlyUnstableTextIsThatText() {
        assertEquals("hello", mergePartial(null, "hello"))
        assertEquals("hello", mergePartial("   ", "hello"))
    }

    @Test fun stableAndUnstableTailAreJoinedWithASpace() {
        assertEquals("hello world", mergePartial("hello", "world"))
    }

    @Test fun anUnstableTextThatRepeatsTheStableOneIsNotDoubled() {
        assertEquals("hello world", mergePartial("hello", "hello world"))
        assertEquals("hello world", mergePartial("hello world", "hello"))
        assertEquals("hello world", mergePartial("hello world", "hello world"))
    }

    @Test fun mergedChineseAndThaiPartialsStayWithoutASpace() {
        assertEquals("你好世界", mergePartial("你好", "世界"))
        assertEquals("สวัสดีครับ", mergePartial("สวัสดี", "ครับ"))
    }

    @Test fun aBlankPartialIsEmpty() {
        assertEquals("", mergePartial(null, null))
        assertEquals("", mergePartial(" ", " "))
    }

    // ---- final text ----

    @Test fun theFinalResultWinsOverTheLastPartial() {
        assertEquals("hello world.", chooseFinalText(listOf("hello world."), "hello wor"))
    }

    @Test fun anEmptyFinalFallsBackToTheLastPartial() {
        assertEquals("hello wor", chooseFinalText(emptyList(), "hello wor"))
        assertEquals("hello wor", chooseFinalText(listOf("  "), " hello wor "))
        assertEquals("hello wor", chooseFinalText(null, "hello wor"))
    }

    @Test fun nothingAtAllIsEmpty() {
        assertEquals("", chooseFinalText(null, ""))
    }

    // ---- level ----

    @Test fun theLevelIsClampedToTheIndicatorsRange() {
        assertEquals(0f, normalizeRms(-2f), 0f)
        assertEquals(0f, normalizeRms(-30f), 0f)
        assertEquals(1f, normalizeRms(10f), 0f)
        assertEquals(1f, normalizeRms(55f), 0f)
        assertEquals(0.5f, normalizeRms(4f), 0.0001f)
        assertEquals(0f, normalizeRms(Float.NaN), 0f)
    }
}
