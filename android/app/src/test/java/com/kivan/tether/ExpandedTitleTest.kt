package com.kivan.tether

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpandedTitleTest {
    @Test
    fun aShortTitleStays() {
        assertEquals(null to "body", Notifier.expandedParts("dibs", "Task x done", "body"))
    }

    @Test
    fun aLongTitleMovesWholeIntoTheText() {
        val title = "Install the dibs update? It changes how cards read on the phone"
        assertEquals("dibs" to "$title\nbody", Notifier.expandedParts("dibs", title, "body"))
        assertEquals("dibs" to title, Notifier.expandedParts("dibs", title, " "))
    }
}
