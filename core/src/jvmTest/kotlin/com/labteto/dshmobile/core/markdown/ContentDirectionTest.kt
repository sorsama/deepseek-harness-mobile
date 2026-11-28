package com.labteto.dshmobile.core.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentDirectionTest {

    @Test
    fun englishIsLeftToRight() {
        assertEquals(false, firstStrongIsRtl("Done. The notes cover the last six changes."))
    }

    @Test
    fun arabicAndHebrewAreRightToLeft() {
        assertEquals(true, firstStrongIsRtl("تم. الملاحظات تغطي آخر ستة تغييرات."))
        assertEquals(true, firstStrongIsRtl("שלום עולם"))
        assertEquals(true, firstStrongIsRtl("یہ اردو ہے"))
    }

    @Test
    fun leadingDigitsAndPunctuationDoNotDecide() {
        assertEquals(false, firstStrongIsRtl("1. **release** notes?"))
        assertEquals(true, firstStrongIsRtl("٣ ملفات"))
        assertEquals(true, firstStrongIsRtl("— (3) ملفات"))
    }

    @Test
    fun inlineCodeIsSkipped() {
        assertEquals(true, firstStrongIsRtl("`npm test` يفشل الآن"))
        assertEquals(false, firstStrongIsRtl("`تجربة` is the word"))
    }

    @Test
    fun noLetterAtAllIsUndecided() {
        assertEquals(null, firstStrongIsRtl("1 / 2"))
        assertEquals(null, firstStrongIsRtl(""))
        assertEquals(null, firstStrongIsRtl("`code only`"))
    }
}
