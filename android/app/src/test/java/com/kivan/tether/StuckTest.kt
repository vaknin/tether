package com.kivan.tether

import org.junit.Assert.assertEquals
import org.junit.Test

class StuckTest {
    private val now = 1_800_000_000_000L

    @Test
    fun anEmptyOutboxClearsTheNotice() {
        assertEquals(false, stuckNotice(0uL, null, now))
        assertEquals(false, stuckNotice(0uL, now - STUCK_AFTER_MS * 2, now))
    }

    @Test
    fun aRecentItemLeavesTheNoticeAlone() {
        assertEquals(null, stuckNotice(1uL, now, now))
        assertEquals(null, stuckNotice(3uL, now - STUCK_AFTER_MS + 1, now))
    }

    @Test
    fun anItemQueuedThirtyMinutesIsStuck() {
        assertEquals(true, stuckNotice(1uL, now - STUCK_AFTER_MS, now))
        assertEquals(true, stuckNotice(2uL, now - 2 * 60 * 60_000L, now))
    }
}
