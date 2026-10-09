package com.kivan.tether.dibs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which pages an intent's PAGE extra may open: read-only ones, never a root request. */
class DibsActivityPageTest {
    private fun page(name: String?, task: Long = -1, note: String? = null) = DibsActivity.pageFor(name, task, note)

    @Test
    fun pagesWithoutATaskOpenByName() {
        assertEquals(Page.Status, page("status"))
        assertEquals(Page.Stories, page("stories"))
    }

    @Test
    fun aTaskPageNeedsItsTask() {
        assertEquals(Page.Transcript(5), page("transcript", 5))
        assertEquals(Page.Report(5), page("report", 5))
        assertEquals(Page.Story(5), page("story", 5))
        assertNull(page("transcript"))
        assertNull(page("report"))
        assertNull(page("story"))
    }

    @Test
    fun anIdeaNeedsItsNote() {
        assertEquals(Page.Idea("n1"), page("idea", note = "n1"))
        assertNull(page("idea"))
        assertNull(page("idea", note = " "))
    }

    @Test
    fun rootPagesAndUnknownNamesOpenNothing() {
        assertNull(page("root", 3))
        assertNull(page("rootkey", 3))
        assertNull(page("RootKey"))
        assertNull(page("nonsense"))
        assertNull(page(null, 3))
    }
}
