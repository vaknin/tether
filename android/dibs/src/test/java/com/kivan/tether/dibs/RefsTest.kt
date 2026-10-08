package com.kivan.tether.dibs

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// The rule for links in text, run against the cases dibs, the desktop app and this app share
// (links-cases.json is a copy of dibs's docs/links-cases.json).
class RefsTest {
    private val cases = JSONObject(javaClass.getResource("/links-cases.json")!!.readText())

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    @Test
    fun linksAreFoundAsDibsFindsThem() {
        for (c in cases.getJSONArray("find").objects()) {
            val text = c.getString("text")
            val want = c.getJSONArray("links").let { a -> (0 until a.length()).map { a.getJSONArray(it) } }
                .map { Triple(it.getString(0), it.getString(1), it.getInt(2)) }
            val got = findRefs(text).map { Triple(text.substring(it.start, it.end), it.kind.name.lowercase(), it.n) }
            assertEquals(text, want, got)
        }
    }

    @Test
    fun segmentsKeepEveryCharacterAndDropUnknownNumbers() {
        val segs = refSegments("see #1 and #2.") { _, n -> n == 2 }
        assertEquals("see #1 and #2.", segs.joinToString("") { it.text })
        assertEquals(listOf("#2"), segs.filter { it.ref != null }.map { it.text })
        assertEquals(listOf(Seg("")), refSegments(""))
    }

    private val index = RefIndex.parse(cases.getJSONObject("picker").getJSONObject("index"))

    @Test
    fun thePickerListsAsDibsOrdersIt() {
        for (c in cases.getJSONObject("picker").getJSONArray("cases").objects()) {
            val q = c.getString("q")
            val want = c.getJSONArray("want").let { a -> (0 until a.length()).map { a.get(it).toString() } }
            assertEquals(q, want, pick(index, q).map { if (it.kind == RefKind.TASK) "${it.n}" else "i${it.n}" })
        }
    }

    @Test
    fun theIndexParsesFromItsFile() {
        val i = RefIndex.parse("""{"tasks":[{"n":5,"t":"A","s":"Done","g":"done","p":"tether","a":"x","f":9},{"n":0,"t":"gone"}],"ideas":[{"n":2,"t":"I","s":"active"}]}""")!!
        assertEquals(1, i.tasks.size)
        assertEquals(9L, i.task(5)!!.f)
        assertNull(i.task(6))
        assertNotNull(i.idea(2))
        assertEquals(true, i.knows(RefKind.IDEA, 2))
        assertEquals(false, i.knows(RefKind.TASK, 2))
        assertNull(RefIndex.parse("not json"))
    }

    @Test
    fun thePickerOpensOnAHashAtTheStartOfAWordAndClosesAfterALinkOrALineBreak() {
        assertEquals(PickQuery(4, "23"), pickQuery("see #23", 7))
        assertEquals(PickQuery(0, ""), pickQuery("#", 1))
        assertEquals(PickQuery(1, "wait go"), pickQuery("(#wait go", 9))
        assertNull(pickQuery("a#1", 3))
        assertNull(pickQuery("#230 and", 8))
        assertNull(pickQuery("#ab\ncd", 6))
        assertNull(pickQuery("no hash", 7))
    }

    @Test
    fun aPickReplacesWhatWasTypedAndLeavesOneSpace() {
        assertEquals("see #230 now" to 9, insertPick("see #wai now", 4, 8, RefKind.TASK, 230))
        assertEquals("idea 45 " to 8, insertPick("#4", 0, 2, RefKind.IDEA, 45))
    }
}
