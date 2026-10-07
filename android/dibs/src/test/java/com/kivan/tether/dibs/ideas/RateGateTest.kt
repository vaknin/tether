package com.kivan.tether.dibs.ideas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Ported from Capture's RateGateTest. */
class RateGateTest {
    @Test
    fun oneAtATimeAndSpaced() = runBlocking {
        val gate = RateGate(minIntervalMs = 150)
        val running = AtomicInteger(0)
        val spans = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        coroutineScope {
            repeat(3) {
                launch(Dispatchers.Default) {
                    gate.withPermit {
                        assertEquals("two requests at once", 1, running.incrementAndGet())
                        val start = System.currentTimeMillis()
                        delay(20)
                        running.decrementAndGet()
                        spans += start to System.currentTimeMillis()
                    }
                }
            }
        }
        val sorted = spans.sortedBy { it.first }
        assertEquals(3, sorted.size)
        for (i in 1 until sorted.size) assertTrue("gap ${sorted[i].first - sorted[i - 1].second}", sorted[i].first - sorted[i - 1].second >= 150)
    }

    @Test
    fun holdDelaysTheNextRequest() = runBlocking {
        val gate = RateGate(minIntervalMs = 0)
        var end = 0L
        gate.withPermit { gate.holdFor(200); end = System.currentTimeMillis() }
        val start = gate.withPermit { System.currentTimeMillis() }!!
        assertTrue("waited ${start - end}", start - end >= 190)
    }

    @Test
    fun longHoldIsLeftToTheCaller() = runBlocking {
        val gate = RateGate(minIntervalMs = 0)
        gate.withPermit { gate.holdFor(60_000) }
        var ran = false
        assertNull(gate.withPermit(maxWaitMs = 1_000) { ran = true })
        assertTrue("block must not run", !ran)
    }
}
