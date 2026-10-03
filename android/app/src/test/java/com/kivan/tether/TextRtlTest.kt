package com.kivan.tether

import org.junit.Assert.assertEquals
import org.junit.Test

class TextRtlTest {
    @Test
    fun hebrewIsRtl() {
        assertEquals(true, textRtl("חלב"))
        assertEquals(true, textRtl("  לקנות לחם"))
    }

    @Test
    fun englishIsLtr() {
        assertEquals(false, textRtl("milk"))
    }

    @Test
    fun theFirstStrongCharacterDecides() {
        assertEquals(false, textRtl("iPhone מטען"))
        assertEquals(true, textRtl("מטען ל-iPhone"))
        assertEquals(true, textRtl("3 ביצים"))
        assertEquals(false, textRtl("12 eggs 🥚 ביצים"))
    }

    @Test
    fun noStrongCharacterIsLtr() {
        assertEquals(false, textRtl("123 - 4.5"))
        assertEquals(false, textRtl("🥚🍞 ✕"))
        assertEquals(false, textRtl(""))
    }
}
