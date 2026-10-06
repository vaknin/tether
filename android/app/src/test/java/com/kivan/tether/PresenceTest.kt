package com.kivan.tether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceTest {
    @Test
    fun payloads() {
        assertEquals("""{"op":"present","why":"unlock","ts":1700000000000}""", Presence.present("unlock", 1_700_000_000_000))
        assertEquals("""{"op":"present","why":"use","ts":5}""", Presence.present("use", 5))
        assertEquals("""{"op":"off","ts":7}""", Presence.off(7))
    }

    @Test
    fun anUnlockIsSentAtMostOnceAMinute() {
        val g = Presence.Gate()
        assertTrue(g.unlock(1_000))
        assertFalse(g.unlock(1_000 + 59_999))
        assertTrue(g.unlock(1_000 + 60_000))
        // A debounced unlock doesn't move the window.
        assertFalse(g.unlock(61_000 + 30_000))
        assertTrue(g.unlock(61_000 + 60_000))
    }

    @Test
    fun renewalsFollowTheLastPresentSent() {
        val g = Presence.Gate()
        assertEquals("nothing sent yet: a full period", Presence.RENEW_MS, g.renewIn(0))
        g.unlock(10_000)
        assertEquals(Presence.RENEW_MS, g.renewIn(10_000))
        // Screen off and on again 20 s later: the unlock is debounced, the renewal keeps its time.
        assertFalse(g.unlock(30_000))
        assertEquals(Presence.RENEW_MS - 20_000, g.renewIn(30_000))
        g.renewed(10_000 + Presence.RENEW_MS)
        assertEquals(Presence.RENEW_MS, g.renewIn(10_000 + Presence.RENEW_MS))
        // Overdue (the timer ran late): at once, never negative.
        assertEquals(0L, g.renewIn(10_000 + 3 * Presence.RENEW_MS))
    }
}
