package com.kivan.tether.dibs

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class TasksModelTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDate.of(2026, 10, 5).atTime(21, 40).toEpochSecond(zone)
    private val clock: (Long) -> String = { java.time.Instant.ofEpochSecond(it).atZone(zone).toLocalTime().toString() }

    private fun task(id: Long, project: String, state: String, ts: Long, unread: Boolean = false, ticked: Long? = null) =
        YourTask(id, "task $id", "t$id", project, state, ts, started = ts - 600, unread = unread, ticked = ticked)

    @Test
    fun groupsPutWhatNeedsYouFirstThenNewest() {
        val list = tasksList(
            listOf(
                task(1, "dibs", "working", 100),
                task(2, "tether", "done", 300),
                task(3, "Other", "needs", 50),
                task(4, "tether", "working", 200),
                task(5, "dibs", "done", 90, ticked = 120),
                task(6, "Other", "done", 60, ticked = 400),
            ),
        )
        assertEquals(listOf("Other", "tether", "dibs"), list.groups.map { it.project })
        assertEquals(4, list.open)
        assertEquals("newest tick first", listOf(6L, 5L), list.ticked.map { it.id })
    }

    @Test
    fun insideAGroupNeedsThenUnreadThenWorkingThenReadThenEnded() {
        val list = tasksList(
            listOf(
                task(1, "p", "failed", 900),
                task(2, "p", "done", 800),
                task(3, "p", "paused", 100),
                task(4, "p", "done", 50, unread = true),
                task(5, "p", "needs", 10),
                task(6, "p", "working", 700),
                task(7, "p", "stopped", 950),
            ),
        )
        assertEquals(listOf(5L, 4L, 6L, 3L, 2L, 7L, 1L), list.groups.single().tasks.map { it.id })
    }

    @Test
    fun aTapHereCountsAsTickedBeforeTheViewKnows() {
        val list = tasksList(listOf(task(1, "p", "done", 1), task(2, "p", "done", 2))) { it.id == 1L }
        assertEquals(listOf(2L), list.groups.single().tasks.map { it.id })
        assertEquals(listOf(1L), list.ticked.map { it.id })
    }

    @Test
    fun stateWords() {
        val today = LocalDate.of(2026, 10, 5).atTime(21, 36).toEpochSecond(zone)
        fun w(t: YourTask) = taskWords(t, now, zone, clock)
        assertEquals("Needs you", w(task(1, "p", "needs", 0)))
        assertEquals("Working · 40 min", w(task(1, "p", "working", 0).copy(started = now - 40 * 60)))
        assertEquals("Working · 1 h 5 min", w(task(1, "p", "working", 0).copy(started = now - 65 * 60)))
        assertEquals("Paused", w(task(1, "p", "paused", 0)))
        assertEquals("Done 21:36", w(task(1, "p", "done", 0).copy(finished = today)))
        assertEquals("Done yesterday", w(task(1, "p", "done", 0).copy(finished = today - 86400)))
        assertEquals("Done " + dayWords(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 5)), w(task(1, "p", "done", 0).copy(finished = today - 2 * 86400)))
        assertEquals("Stopped", w(task(1, "p", "stopped", 0)))
        assertEquals("Couldn't finish", w(task(1, "p", "failed", 0)))
    }

    @Test
    fun howLongItRan() {
        assertEquals(95L, ranMinutes(task(1, "p", "done", 0).copy(minutes = 95), now))
        assertEquals(40L, ranMinutes(task(1, "p", "working", 0).copy(started = now - 40 * 60), now))
    }

    @Test
    fun stepsInPlainWords() {
        val steps = listOf(
            Step("Read", "Read src/a.rs"),
            Step("Read", "Read src/b.rs"),
            Step("Edit", "Edited src/recap.rs"),
            Step("Bash", "Run the tests", error = true),
            Step("Edit", "Edited src/recap.rs"),
            Step("Bash", "Build it"),
            Step("Grep", "Searched for foo"),
            Step("ToolSearch", "ToolSearch: x"),
        )
        assertEquals("8 steps: read 2 files, edited recap.rs, ran 2 commands, searched once, 1 other step, 1 failed", stepsSummary(steps))
        assertEquals("Edited src/recap.rs", stepsSummary(listOf(Step("Edit", "Edited src/recap.rs"))))
        assertEquals("Run the tests, failed", stepsSummary(listOf(Step("Bash", "Run the tests", error = true))))
        assertEquals(
            "2 steps: searched the web 2 times",
            stepsSummary(listOf(Step("WebSearch", "Searched the web for a"), Step("WebSearch", "Searched the web for b"))),
        )
    }
}
