package com.labteto.dshmobile.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlreadyInTargetTest {
    private val chinese = "我需要先看一下这个文件的结构，然后再决定怎么修改，因为它依赖另一个模块的接口。"
    private val english = "I need to look at the structure of this file first, and then decide how to change it, because it depends on the interface of another module."
    private val arabic = "أحتاج أولًا إلى النظر في بنية هذا الملف ثم أقرر كيف أعدله لأنه يعتمد على واجهة وحدة أخرى."
    private val russian = "Сначала мне нужно посмотреть структуру этого файла, а потом решить, как его менять, потому что он зависит от другого модуля."

    @Test fun `chinese text is already in chinese`() {
        assertTrue(looksAlreadyInTarget(chinese, "zh-CN"))
        assertTrue(looksAlreadyInTarget(chinese, "zh"))
        assertFalse(looksAlreadyInTarget(english, "zh-CN"))
    }

    @Test fun `chinese with a few english identifiers still counts as chinese`() {
        assertTrue(looksAlreadyInTarget("我需要修改 ChatNodeItem 里的 ReasoningContent 函数，让它在读者展开思考过程之后再请求翻译，而不是在会话刚打开的时候就把整个历史记录全部发出去。", "zh-CN"))
    }

    @Test fun `japanese is not taken for chinese`() {
        val japanese = "まずこのファイルの構造を確認してから、どう変更するかを決めます。別のモジュールに依存しているためです。"
        assertFalse(looksAlreadyInTarget(japanese, "zh-CN"))
        assertTrue(looksAlreadyInTarget(japanese, "ja"))
    }

    @Test fun `each script is recognised for its own language`() {
        assertTrue(looksAlreadyInTarget(arabic, "ar"))
        assertTrue(looksAlreadyInTarget(russian, "ru"))
        assertTrue(looksAlreadyInTarget("먼저 이 파일의 구조를 살펴본 다음 어떻게 바꿀지 정하겠습니다.", "ko"))
        assertTrue(looksAlreadyInTarget("पहले मुझे इस फ़ाइल की बनावट देखनी होगी, फिर तय करना होगा कि इसे कैसे बदला जाए।", "hi"))
        assertTrue(looksAlreadyInTarget("ก่อนอื่นผมต้องดูโครงสร้างของไฟล์นี้ แล้วค่อยตัดสินใจว่าจะแก้อย่างไร", "th"))
        assertTrue(looksAlreadyInTarget("প্রথমে আমাকে এই ফাইলের কাঠামো দেখতে হবে, তারপর ঠিক করব কীভাবে বদলাব।", "bn"))
    }

    @Test fun `text in another script is never skipped`() {
        assertFalse(looksAlreadyInTarget(arabic, "ru"))
        assertFalse(looksAlreadyInTarget(russian, "ar"))
        assertFalse(looksAlreadyInTarget(chinese, "ru"))
        assertFalse(looksAlreadyInTarget(english, "hi"))
    }

    @Test fun `english is told by its function words`() {
        assertTrue(looksAlreadyInTarget(english, "en"))
        assertTrue(looksAlreadyInTarget(english, "en-US"))
        assertFalse(looksAlreadyInTarget(chinese, "en"))
        assertFalse(looksAlreadyInTarget(russian, "en"))
    }

    @Test fun `other latin languages are never skipped`() {
        val spanish = "Primero necesito revisar la estructura de este archivo y después decidir cómo cambiarlo, porque depende de otro módulo."
        assertFalse(looksAlreadyInTarget(spanish, "es"))
        assertFalse(looksAlreadyInTarget(spanish, "fr"))
        assertFalse(looksAlreadyInTarget(english, "es"))
        assertFalse(looksAlreadyInTarget(english, "pt-BR"))
        // And spanish is not mistaken for english.
        assertFalse(looksAlreadyInTarget(spanish, "en"))
    }

    @Test fun `code and identifiers are not taken for english prose`() {
        assertFalse(looksAlreadyInTarget("fun translateAfterRest(key: TranslationKey, restMs: Long) { engine.translate(key) { fetch() } }", "en"))
    }

    @Test fun `text too short to judge is never skipped`() {
        assertFalse(looksAlreadyInTarget("好的", "zh-CN"))
        assertFalse(looksAlreadyInTarget("Okay, done.", "en"))
        assertFalse(looksAlreadyInTarget("", "zh-CN"))
    }

    @Test fun `only the start of a huge text is read`() {
        // A long English text that turns Chinese after 2000 characters is judged on its start.
        val text = english.repeat(40) + chinese.repeat(200)
        assertTrue(looksAlreadyInTarget(text, "en"))
        assertFalse(looksAlreadyInTarget(text, "zh-CN"))
    }
}
