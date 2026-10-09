package com.kivan.tether.dibs

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Task #124: every tap shows at once, and says plainly when dibs doesn't answer.
class TapsTest {
    @After
    fun reset() = Taps.reset()

    private fun tap(at: Long = 1_000, views: Int = 0, timeOnly: Boolean = false) = Taps.Tap(at, views, "Stop", timeOnly)

    @Test
    fun noTapIsIdle() {
        assertEquals(TapState.IDLE, Taps.judge(null, 3, 0, 5_000))
    }

    @Test
    fun aTapShowsBusyAtLeastAMomentEvenIfAViewComesFirst() {
        assertEquals(TapState.BUSY, Taps.judge(tap(), views = 1, upSince = 0, nowMs = 1_100))
        assertEquals(TapState.IDLE, Taps.judge(tap(), views = 1, upSince = 0, nowMs = 1_000 + Taps.MIN_BUSY_MS))
    }

    @Test
    fun aLaterViewAnswersIt() {
        assertEquals(TapState.BUSY, Taps.judge(tap(), views = 0, upSince = 0, nowMs = 3_000))
        assertEquals(TapState.IDLE, Taps.judge(tap(), views = 1, upSince = 0, nowMs = 3_000))
    }

    @Test
    fun silenceWithTheLinkUpFails() {
        val failedAt = 1_000 + Taps.FAIL_MS
        assertEquals(TapState.BUSY, Taps.judge(tap(), 0, upSince = 0, nowMs = failedAt - 1))
        assertEquals(TapState.FAILED, Taps.judge(tap(), 0, upSince = 0, nowMs = failedAt))
    }

    @Test
    fun theClockStartsWhenTheLinkCameUpIfThatWasLater() {
        // Pressed offline at 1 s, link up at 60 s: dibs gets until 60 s + FAIL_MS.
        assertEquals(TapState.BUSY, Taps.judge(tap(), 0, upSince = 60_000, nowMs = 60_000 + Taps.FAIL_MS - 1))
        assertEquals(TapState.FAILED, Taps.judge(tap(), 0, upSince = 60_000, nowMs = 60_000 + Taps.FAIL_MS))
    }

    @Test
    fun noLinkIsQueuedNotFailed() {
        assertEquals(TapState.BUSY, Taps.judge(tap(), 0, upSince = null, nowMs = 1_000 + Taps.QUEUED_MS - 1))
        assertEquals(TapState.QUEUED, Taps.judge(tap(), 0, upSince = null, nowMs = 1_000 + Taps.QUEUED_MS))
        assertEquals(TapState.QUEUED, Taps.judge(tap(), 0, upSince = null, nowMs = 10 * 60_000))
    }

    @Test
    fun aTimeOnlyTapIgnoresViews() {
        // An answered question: a view that still lists it doesn't answer it; only dibs dropping it does.
        val t = tap(timeOnly = true)
        assertEquals(TapState.BUSY, Taps.judge(t, views = 5, upSince = 0, nowMs = 5_000))
        assertEquals(TapState.FAILED, Taps.judge(t, views = 5, upSince = 0, nowMs = 1_000 + Taps.FAIL_MS))
    }

    @Test
    fun pressingAgainStartsOver() {
        Taps.upSince = 0
        Taps.press("stop:1", "Stop", nowMs = 1_000)
        assertEquals(TapState.FAILED, Taps.state("stop:1", 1_000 + Taps.FAIL_MS))
        Taps.press("stop:1", "Stop", nowMs = 1_000 + Taps.FAIL_MS)
        assertEquals(TapState.BUSY, Taps.state("stop:1", 1_000 + Taps.FAIL_MS + 100))
    }

    @Test
    fun notesListQueuedFailedAndProgressTaps() {
        Taps.upSince = 0
        Taps.press("a", "Stop", nowMs = 0)
        Taps.press("b", "Open on laptop", progress = "Opening on the laptop…", nowMs = 0)
        // Busy: only b has words to show; a is a plain busy button.
        assertEquals(listOf("b" to TapState.BUSY), Taps.notes(1_000))
        // Silent past the wait: both are notes.
        assertEquals(listOf("a" to TapState.FAILED, "b" to TapState.FAILED), Taps.notes(Taps.FAIL_MS).sortedBy { it.first })
        Taps.clear("a")
        assertNull(Taps.tap("a"))
    }

    @Test
    fun aViewTakesAwayTheTapsItAnswered() {
        Taps.press("stop:1", "Stop", nowMs = Dibs.now() - 5_000)
        Taps.press("q4", "Yes", timeOnly = true, nowMs = Dibs.now() - 5_000)
        Taps.viewArrived()
        assertNull(Taps.tap("stop:1"))
        assertTrue(Taps.tap("q4") != null)
    }

    @Test
    fun anAnswerDibsNeverTookComesBack() {
        Taps.upSince = 0
        Dibs.answered["q9"] = "Yes"
        Taps.press("q9", "Yes", timeOnly = true, nowMs = 1_000)
        Dibs.sweep(1_000 + Taps.FAIL_MS - 1)
        assertTrue("q9" in Dibs.answered)
        Dibs.sweep(1_000 + Taps.FAIL_MS)
        assertTrue("q9" !in Dibs.answered)
        assertEquals(TapState.FAILED, Taps.state("q9", 1_000 + Taps.FAIL_MS))
        Dibs.answered.clear()
    }

    @Test
    fun tickThenUntickLeavesNoStrayTap() {
        Dibs.host = FakeHost(null)
        val t = YourTask(id = 7, title = "X", name = "x", project = "p", state = "done", ts = 0, started = 0)
        Dibs.tick(t)
        Dibs.untick(t)
        assertNull(Taps.tap("tick:7"))
        assertTrue(Taps.tap("untick:7") != null)
        Dibs.answered.clear()
    }

    @Test
    fun dismissingANoteKeepsAShownAnswerRollbackable() {
        Taps.upSince = null
        Dibs.answered["q3"] = "Yes"
        Taps.press("q3", "Yes", timeOnly = true, nowMs = 0)
        Taps.dismiss("q3")
        assertTrue(Taps.notes(10_000).isEmpty())
        Taps.upSince = 20_000
        Dibs.sweep(20_000 + Taps.FAIL_MS)
        assertTrue("q3" !in Dibs.answered)
        Dibs.answered.clear()
    }

    @Test
    fun dismissingAPlainTapForgetsIt() {
        Taps.press("stop:1", "Stop", nowMs = 0)
        Taps.dismiss("stop:1")
        assertNull(Taps.tap("stop:1"))
    }

    @Test
    fun anAnswerWithoutRollbackIsNeverTakenBack() {
        Dibs.host = FakeHost(null)
        Taps.upSince = 0
        Dibs.answered["q5"] = "old"
        Taps.press("q5", "old", timeOnly = true, nowMs = 0)
        Dibs.answer("q5", "Approved", "root-approve", rollback = false)
        assertNull(Taps.tap("q5"))
        Dibs.sweep(10 * Taps.FAIL_MS)
        assertEquals("Approved", Dibs.answered["q5"])
        Dibs.answered.clear()
    }

    @Test
    fun theSameViewSeenAgainIsNoAnswer() {
        val json = Any()
        Taps.viewArrived(json)
        Taps.viewArrived(json)
        assertEquals(1, Taps.views)
        Taps.viewArrived(Any())
        assertEquals(2, Taps.views)
    }
}
