package com.labteto.dshmobile.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** `fillTemplate` writes what Android's `getString(id, args)` wrote for the chat's runtime messages. */
class MessageTemplatesTest {

    @Test
    fun aNumberedStringPlaceholderTakesItsArgument() {
        assertEquals("Command failed: no such session", fillTemplate("Command failed: %1\$s", "no such session"))
    }

    @Test
    fun aCountPlaceholderTakesDigitsAlreadyWritten() {
        assertEquals("A message can include up to ٢٠ images.", fillTemplate("A message can include up to %1\$d images.", "٢٠"))
    }

    @Test
    fun theEscapedPercentSignIsOneSign() {
        assertEquals("Cache 87%", fillTemplate("Cache %1\$d%%", "87"))
    }

    @Test
    fun argumentsGoWhereTheirNumbersSay() {
        assertEquals("b then a", fillTemplate("%2\$s then %1\$s", "a", "b"))
    }

    @Test
    fun aPlaceholderWithNoArgumentIsLeftAsItIs() {
        assertEquals("/%1\$s takes nothing", fillTemplate("/%1\$s takes nothing"))
    }

    @Test
    fun anArgumentIsNotReadAsATemplate() {
        assertEquals("Unknown command: /x %1\$s %%", fillTemplate("Unknown command: %1\$s", "/x %1\$s %%"))
    }
}
