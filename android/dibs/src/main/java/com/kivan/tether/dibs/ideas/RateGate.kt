package com.kivan.tether.dibs.ideas

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One Gemini request at a time, spaced at least [minIntervalMs] apart, and held back for as long as
 * Gemini asks after a rate limit. A Mutex (a one-permit semaphore) is enough because every request
 * goes out from the app's one process.
 */
class RateGate(private val minIntervalMs: Long) {
    private val mutex = Mutex()

    /** Wall-clock time before which no request may start; only touched while holding [mutex]. */
    private var nextAllowedAt = 0L

    /**
     * Runs [block] once its turn comes. Returns null without running it when the wait for the turn
     * would be longer than [maxWaitMs], so the caller can come back later instead.
     */
    suspend fun <T> withPermit(maxWaitMs: Long = Long.MAX_VALUE, block: suspend () -> T): T? = mutex.withLock {
        val wait = nextAllowedAt - System.currentTimeMillis()
        if (wait > maxWaitMs) return@withLock null
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            nextAllowedAt = maxOf(nextAllowedAt, System.currentTimeMillis() + minIntervalMs)
        }
    }

    /** Gemini said to wait (HTTP 429): no request starts for [delayMs]. Call inside [withPermit]. */
    fun holdFor(delayMs: Long) {
        nextAllowedAt = maxOf(nextAllowedAt, System.currentTimeMillis() + delayMs)
    }
}
