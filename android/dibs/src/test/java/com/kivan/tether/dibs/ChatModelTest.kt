package com.kivan.tether.dibs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ChatModelTest {
    private val zone = ZoneOffset.UTC
    /** 2026-10-05 17:00 UTC. */
    private val now = LocalDate.of(2026, 10, 5).atTime(17, 0).toEpochSecond(zone)
    private val label: (LocalDate) -> String = { it.toString() }

    private var n = 0L
    private fun line(ts: Long, mine: Boolean = false, note: Boolean = false, ask: Ask? = null) =
        TalkLine("s${++n}", n, mine, "line $n", null, note, ts, emptyList(), ask)

    private fun at(day: Int, h: Int, m: Int = 0) = LocalDate.of(2026, 10, day).atTime(h, m).toEpochSecond(zone)

    private fun rows(talk: List<TalkLine>, echoes: List<Echo> = emptyList(), unfolded: Set<String> = emptySet(), at: Long = now) =
        chatRows(talk, echoes, at, unfolded, zone, label)

    @Test
    fun earlierDaysFoldOneRowEach() {
        val old = (0 until 5).map { line(at(3, 10, it)) } + (0 until 3).map { line(at(4, 9, it)) }
        val today = (0 until 9).map { line(at(5, 8 + it)) }
        val r = rows(old + today)
        assertEquals(FoldRow("2026-10-03", "2026-10-03", 5), r[0])
        assertEquals(FoldRow("2026-10-04", "2026-10-04", 3), r[1])
        assertEquals(DayRow("2026-10-05", "2026-10-05"), r[2])
        assertEquals(9, r.count { it is LineRow })
    }

    @Test
    fun theNewestEightStayOpen() {
        // Only two lines today: six of yesterday's stay open too, the rest of it folds.
        val y = (0 until 10).map { line(at(4, 9, it * 5)) }
        val t = (0 until 2).map { line(at(5, 16, it)) }
        val r = rows(y + t)
        assertEquals(FoldRow("2026-10-04", "2026-10-04", 4), r[0])
        assertEquals(DayRow("2026-10-04", "2026-10-04"), r[1])
        assertEquals(8, r.count { it is LineRow })
        assertEquals(DayRow("2026-10-05", "2026-10-05"), r[r.size - 3])
    }

    @Test
    fun theLastSixHoursStayOpenAcrossMidnight() {
        // 02:00: yesterday after 20:00 is open, earlier in the day folds.
        val early = at(5, 2)
        val talk = (0 until 10).map { line(at(4, 8, it)) } + (0 until 10).map { line(at(4, 21, it * 4)) }
        val r = rows(talk, at = early)
        assertEquals(FoldRow("2026-10-04", "2026-10-04", 10), r[0])
        assertEquals(DayRow("2026-10-04", "2026-10-04"), r[1])
        assertEquals(10, r.count { it is LineRow })
    }

    @Test
    fun anUnfoldedDayShowsInFull() {
        val y = (0 until 10).map { line(at(4, 9, it * 5)) }
        val t = (0 until 9).map { line(at(5, 16, it)) }
        val r = rows(y + t, unfolded = setOf("2026-10-04"))
        assertTrue(r.none { it is FoldRow })
        assertEquals(19, r.count { it is LineRow })
        assertEquals(2, r.count { it is DayRow })
    }

    @Test
    fun runsJoinTheSameSideWithinThreeMinutes() {
        val a = line(at(5, 12, 0), mine = true)
        val b = line(at(5, 12, 2), mine = true)
        val c = line(at(5, 12, 9), mine = true) // 7 min later: a new run
        val d = line(at(5, 12, 10)) // dibs
        val e = line(at(5, 12, 10)) // dibs
        val r = rows(listOf(a, b, c, d, e)).filterIsInstance<LineRow>().map { it.first to it.last }
        assertEquals(listOf(true to false, false to true, true to true, true to false, false to true), r)
    }

    @Test
    fun notesAndAsksNeverJoinRuns() {
        val a = line(at(5, 12, 0))
        val note = line(at(5, 12, 0), note = true)
        val b = line(at(5, 12, 1))
        val ask = line(at(5, 12, 1), ask = Ask(249, emptyList(), null, null))
        val c = line(at(5, 12, 2))
        val r = rows(listOf(a, note, b, ask, c))
        assertTrue(r[2] is NoteRow)
        val lines = r.filterIsInstance<LineRow>().map { it.first to it.last }
        assertEquals(listOf(true to true, true to true, true to true, true to true), lines)
    }

    @Test
    fun echoesGoLastAndJoinMyRun() {
        val a = line(at(5, 16, 59), mine = true)
        val r = rows(listOf(a), listOf(Echo("u1", now), Echo("u2", now)))
        assertEquals(listOf("day-2026-10-05", a.id, "u1", "u2"), r.map { it.key })
        assertEquals(EchoRow("u2", first = false, last = true), r.last())
        assertEquals(LineRow(a, first = true, last = false), r[1])
    }

    @Test
    fun anEchoTheViewListsIsDrawnOnce() {
        val a = TalkLine("u1", 7, true, "hi", null, false, at(5, 16), emptyList(), null)
        val r = rows(listOf(a), listOf(Echo("u1", now), Echo("u2", now), Echo("u2", now)))
        assertEquals(listOf("day-2026-10-05", "u1", "u2"), r.map { it.key })
        assertTrue(r[1] is LineRow)
    }

    @Test
    fun anEchoOnANewDayGetsItsHeader() {
        val a = line(at(4, 23, 59), mine = true)
        val r = rows(listOf(a), listOf(Echo("u1", now)))
        assertEquals(listOf("day-2026-10-04", a.id, "day-2026-10-05", "u1"), r.map { it.key })
    }

    @Test
    fun keysAreUnique() {
        val talk = (0 until 30).map { line(at(1 + it / 6, 10, it)) } + (0 until 3).map { line(at(5, 16, it)) }
        val r = rows(talk, listOf(Echo("x", now)), unfolded = setOf("2026-10-02"))
        assertEquals(r.size, r.map { it.key }.toSet().size)
    }

    @Test
    fun emptyIsEmpty() {
        assertEquals(emptyList<ChatRow>(), rows(emptyList()))
    }
}
