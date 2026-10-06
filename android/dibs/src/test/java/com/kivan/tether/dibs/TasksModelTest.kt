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

    @Test
    fun titlesLoseTheirAsides() {
        // As dibs cut them on 2026-10-06: the first clause, ending inside a bracket.
        assertEquals("When the user adds a new Capture note", plainTitle("When the user adds a new Capture note (their ideas app", "When the user adds a new Capture note (their ideas app, …)"))
        assertEquals("Cut phone notification clutter", plainTitle("Cut phone notification clutter (the user", ""))
        assertEquals("Fix the link now", plainTitle("Fix the link (word 12) now", ""))
        assertEquals("A brain-set title stays", "Typed answers go only to the brain", plainTitle("Typed answers go only to the brain", "typed answers: go only to the brain"))
    }

    @Test
    fun aTagTitleTakesTheWordsAfterIt() {
        assertEquals(
            "Plan how dibs spends less usage",
            plainTitle("PLAN ONLY", "PLAN ONLY: plan how dibs spends less usage (the user, word 90), then report"),
        )
        assertEquals("Build the Ideas tab", plainTitle("RESEARCH and plan", "RESEARCH and plan: dibs: build the Ideas tab. Then ask."))
        assertEquals("a bare tag stays", "PLAN", plainTitle("PLAN", "PLAN"))
        assertEquals("a link isn't a repo", "Https://x.io/y fix it", plainTitle("PLAN ONLY", "PLAN ONLY: https://x.io/y fix it"))
        assertEquals("nor a time", "12:30 call", plainTitle("PLAN ONLY", "PLAN ONLY: 12:30 call"))
    }

    @Test
    fun aLongClauseIsCutAtAWord() {
        val t = firstClause("one two three four five six seven eight nine ten eleven twelve thirteen fourteen")
        assertEquals("One two three four five six seven eight nine ten eleven…", t)
        assert(t.length <= TITLE_MAX + 1)
    }

    @Test
    fun theBadgeSaysWhatItCounts() {
        assertEquals(null, forYouWords(0, 0))
        assertEquals("1 for you: 1 asking you", forYouWords(0, 1))
        assertEquals("3 for you: 2 to read, 1 asking you", forYouWords(2, 1))
        assertEquals(true, wantsYou(task(1, "x", "done", 1, unread = true), ticked = false, unread = true))
        assertEquals("a running one doesn't", false, wantsYou(task(2, "x", "working", 1), ticked = false, unread = false))
        assertEquals("a ticked one doesn't", false, wantsYou(task(3, "x", "needs", 1), ticked = true, unread = false))
    }
}
