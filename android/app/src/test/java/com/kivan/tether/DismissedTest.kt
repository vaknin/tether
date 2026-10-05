package com.kivan.tether

import org.junit.Assert.assertEquals
import org.junit.Test

class DismissedTest {
    private val a = Dismissed("dibs", "talk", "u1")
    private val b = Dismissed("dibs", "talk", "s2")
    private val c = Dismissed("teen", "notes", "n1")

    @Test
    fun hiddenWhileTheViewStillListsIt() {
        val views = mapOf("dibs" to mapOf("talk" to setOf("u1", "s2")), "teen" to mapOf("notes" to setOf("n1")))
        assertEquals(setOf(a, b, c), keepDismissed(setOf(a, b, c)) { views[it] })
    }

    @Test
    fun forgottenOnceTheViewDropsIt() {
        val views = mapOf("dibs" to mapOf("talk" to setOf("s2")), "teen" to mapOf("other" to setOf("n1")))
        assertEquals(setOf(b), keepDismissed(setOf(a, b, c)) { views[it] })
    }

    @Test
    fun keptWhileTheChannelHasNoView() {
        assertEquals(setOf(a), keepDismissed(setOf(a)) { null })
    }
}
