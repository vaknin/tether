package com.kivan.tether.dibs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    // A real one, as `dibs task transcript 33` rendered it.
    private val json = javaClass.getResource("/transcript-33.json")!!.readText()

    @Test
    fun parsesARealTranscript() {
        val t = Transcript.parse(json)
        assertEquals(33L, t.task)
        assertEquals("a2d1938a8a", t.rev)
        assertEquals(1791231923L, t.asOf)
        assertEquals(listOf("start"), t.parts.map { it.how })
        assertEquals(21, t.turns.size)
        assertEquals("ask", t.turns[0].who)
        assertTrue(t.turns[0].text!!.startsWith("Used mini PC"))
        val first = t.turns[1]
        assertEquals("steps", first.who)
        assertEquals("Bash", first.steps[0].tool)
        assertEquals("Read the task file and list working directory", first.steps[0].line)
        assertTrue("dibs says where it cut", first.steps[0].output!!.endsWith("(cut at 2 KB of 3 KB)"))
        assertEquals(27, t.turns.sumOf { it.steps.size })
    }

    @Test
    fun rowsFoldEachRunOfSteps() {
        val rows = transcriptRows(Transcript.parse(json))
        assertTrue(rows[0] is AskRow)
        assertEquals(10, rows.count { it is StepsRow })
        assertEquals(10, rows.count { it is AgentRow })
        assertEquals("6 steps: ran 5 commands, 1 other step", (rows[1] as StepsRow).summary)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun seamsGoBeforeTheirPartsFirstTurn() {
        val t = Transcript(
            1, "r", 0,
            listOf(Part("a", "start", 0), Part("b", "handoff", 20), Part("c", "reopen", 40)),
            listOf(
                Turn("ask", 0, "do it", null, emptyList()),
                Turn("agent", 10, "on it", null, emptyList()),
                Turn("agent", 25, "fresh", null, emptyList()),
                Turn("user", 45, "why?", "phone", emptyList()),
            ),
        )
        val rows = transcriptRows(t)
        assertEquals(listOf("AskRow", "AgentRow", "SeamRow", "AgentRow", "SeamRow", "UserRow"), rows.map { it::class.simpleName })
        assertEquals("Continued in a fresh context", seamWords("handoff", 20) { "x" })
        assertEquals("Reopened 22:10", seamWords("reopen", 40) { "22:10" })
        assertEquals("phone", (rows[5] as UserRow).src)
    }

    @Test
    fun fetchedFileNames() {
        assertEquals("transcript" to 31L, fetchedOf("transcript-31-ab12.json.gz"))
        assertEquals("report" to 7L, fetchedOf("report-7-0a1b2c3d4e (1).md"))
        assertEquals("story" to 85L, fetchedOf("story-85-9f3e.md"))
        assertEquals(null, fetchedOf("IMG_1.jpg"))
        assertEquals("aaaaaaaaaaaaaaaa0000000000000001", ideaFileOf("idea-aaaaaaaaaaaaaaaa0000000000000001-0a1b2c3d4e (1).md"))
        assertEquals(null, ideaFileOf("transcript-31-ab12.json.gz"))
    }
}
