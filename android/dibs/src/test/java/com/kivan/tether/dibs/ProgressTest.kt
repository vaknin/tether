package com.kivan.tether.dibs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTest {
    private val now = 100_000L
    private fun p(eta: Long? = now + 900, lo: Long? = now + 780, hi: Long? = now + 1080, guess: Boolean = false) =
        Progress("build", "building, step 2 of 5", now - 1800, eta, lo, hi, guess)

    @Test
    fun aSureEstimateIsOneNumber() {
        assertEquals("done in ~15 min", leftWords(p(), now))
        assertEquals("building, step 2 of 5 · done in ~15 min", progressLine(p(), now))
    }

    @Test
    fun anUnsureOneIsARange() {
        assertEquals("done in ~10–30 min", leftWords(p(lo = now + 600, hi = now + 1800), now))
        assertEquals("done in ~40 min – 2 h 30 min", leftWords(p(eta = now + 5400, lo = now + 2400, hi = now + 9000), now))
    }

    @Test
    fun fewToGoBySaysSoAndItsTimePassingSaysAnyMinute() {
        assertEquals("done in ~15 min (rough guess)", leftWords(p(guess = true), now))
        assertEquals("any minute now", leftWords(p(eta = now - 10, lo = now - 100, hi = now + 30), now))
    }

    @Test
    fun noEstimateShowsTheStageOnlyAndNoBar() {
        val q = p(eta = null, lo = null, hi = null)
        assertEquals("building, step 2 of 5", progressLine(q, now))
        assertNull(barFill(q, now))
    }

    @Test
    fun theBarIsSureUpToTheLatestFinishAndLightUpToTheEarliest() {
        val f = barFill(p(lo = now + 600, hi = now + 1800), now)!!
        assertEquals(0.5f, f.sure, 0.001f)
        assertEquals(0.75f, f.maybe, 0.001f)
        assertEquals(0.98f, barFill(p(eta = now - 60, lo = now - 120, hi = now - 10), now)!!.sure, 0.001f)
    }
}
