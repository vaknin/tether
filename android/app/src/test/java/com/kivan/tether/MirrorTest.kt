package com.kivan.tether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorTest {
    @Test
    fun messagesJoinNewestLast() {
        val text = Mirror.notifText(listOf("Your code is 1234", null, "", "Code 5678"), "big", "2 new messages")
        assertEquals("Your code is 1234\nCode 5678", text)
    }

    @Test
    fun bigTextThenText() {
        assertEquals("full body", Mirror.notifText(null, "full body", "short"))
        assertEquals("short", Mirror.notifText(emptyList(), " ", "short"))
        assertEquals("short", Mirror.notifText(listOf(null), null, "short"))
        assertEquals("", Mirror.notifText(null, null, null))
    }

    @Test
    fun volumeRoundTrips() {
        assertEquals(100, Mirror.volumePercent(15, 15))
        assertEquals(0, Mirror.volumePercent(0, 15))
        assertEquals(46, Mirror.volumePercent(7, 15))
        assertNull(Mirror.volumePercent(3, 0))
        assertEquals(15, Mirror.volumeIndex(100, 15))
        assertEquals(0, Mirror.volumeIndex(0, 15))
        assertEquals(8, Mirror.volumeIndex(50, 15))
        assertEquals(15, Mirror.volumeIndex(200, 15))
        for (i in 0..25) assertEquals(i, Mirror.volumeIndex(Mirror.volumePercent(i, 25)!!, 25))
    }

    @Test
    fun positionExtrapolatesOnlyWhilePlaying() {
        assertEquals(12_000, Mirror.positionNow(10_000, 1_000, 3_000, 1f, true, 60_000))
        assertEquals(14_000, Mirror.positionNow(10_000, 1_000, 3_000, 2f, true, 60_000))
        assertEquals(10_000, Mirror.positionNow(10_000, 1_000, 3_000, 1f, false, 60_000))
        assertEquals(60_000, Mirror.positionNow(59_000, 1_000, 9_000, 1f, true, 60_000))
        assertEquals(0, Mirror.positionNow(-1, 1_000, 9_000, 1f, true, 60_000))
        assertEquals(18_000, Mirror.positionNow(10_000, 1_000, 9_000, 1f, true, 0))
    }
}
