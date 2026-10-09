package com.kivan.tether.dibs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class FormatTest {
    @Test
    fun durations() {
        assertEquals("0 min", duration(0))
        assertEquals("46 min", duration(46))
        assertEquals("1 h 52 min", duration(112))
        assertEquals("3 h", duration(180))
    }

    @Test
    fun ages() {
        assertEquals("now", age(5))
        assertEquals("12 min", age(12 * 60 + 30))
        assertEquals("1 h", age(3600 + 59 * 60))
        assertEquals("3 d", age(3 * 86400 + 5))
    }

    @Test
    fun sizes() {
        assertEquals("980 B", fileSize(980))
        assertEquals("12 KB", fileSize(12_345))
        assertEquals("3.4 MB", fileSize(3_400_000))
    }

    @Test
    fun awayFromItsTimesOrItsTitle() {
        assertEquals(187L, awayMinutes(Away(4, "x", emptyList(), 1000, 1000 + 187 * 60)))
        assertEquals(187L, awayMinutes(Away(4, "While you were away (3h 7m)", emptyList(), 0, 0)))
        assertEquals(120L, awayMinutes(Away(4, "While you were away (2h)", emptyList(), 0, 0)))
        assertEquals(45L, awayMinutes(Away(4, "While you were away (45m)", emptyList(), 0, 0)))
        assertNull(awayMinutes(Away(4, "While you were away", emptyList(), 0, 0)))
    }

    @Test
    fun words() {
        assertEquals("Inside Tether", outcomeWords("Answered: Inside Tether"))
        assertEquals("Dismissed", outcomeWords("Dismissed"))
        assertEquals("dibs master", holdWords("repo:dibs:master"))
        assertEquals("phone", holdWords("phone:1234"))
        val today = LocalDate.of(2026, 10, 5)
        assertEquals("Today", dayWords(today, today))
        assertEquals("Yesterday", dayWords(today.minusDays(1), today))
        assertEquals("Sun 4 Oct", dayWords(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 7), Locale.UK))
        assertEquals("Sat 4 Oct 2025", dayWords(LocalDate.of(2025, 10, 4), today, Locale.UK))
    }

    @Test
    fun theHeaderState() {
        val idle = State("idle", false, null, null)
        assertEquals("Pairing lost", stateWords(Link.UNPAIRED, idle.copy(busy = true)))
        assertEquals("Not connected", stateWords(Link.OFFLINE, idle))
        assertEquals("On it", stateWords(Link.CONNECTED, idle.copy(busy = true, usage = "Out until 18:00")))
        assertEquals("Out of usage", stateWords(Link.CONNECTED, idle.copy(usage = "Out until 18:00")))
        assertEquals("Ready", stateWords(Link.CONNECTED, idle))
        // A dibs that says what its brain is doing: its words win, also over a reply still pending.
        val out = idle.copy(busy = true, usage = "Out until 12:20", doing = "out", words = "Out of usage until 12:20")
        assertEquals("Out of usage until 12:20", stateWords(Link.CONNECTED, out))
        assertEquals("Working", stateWords(Link.CONNECTED, idle.copy(doing = "working", words = "Working")))
        assertEquals("Idle", stateWords(Link.CONNECTED, idle.copy(doing = "idle", words = "Idle")))
        assertEquals("Not connected", stateWords(Link.OFFLINE, out))
    }

    @Test
    fun usageSaysEachWindowAndItsReset() {
        val now = 1_000_000L
        val clock: (Long) -> String = { "t${it - now}" }
        val day: (Long) -> String = { "Thu" }
        val lines = limitWords(
            listOf(Limit("five_hour", 87.6, now + 3600), Limit("seven_day", 63.0, now + 3 * 86400), Limit("spend_limit", 10.0, now - 1)),
            now, clock, day,
        )
        assertEquals(
            listOf(LimitLine("5h 88% · resets t3600", true), LimitLine("7d 63% · resets Thu t259200", false)),
            lines,
        )
    }

    @Test
    fun theRingsComeFiveHourFirstAndDropTheSpendLine() {
        val now = 1_000_000L
        val clock: (Long) -> String = { "t${it - now}" }
        val day: (Long) -> String = { "Thu" }
        val rings = usageRings(
            listOf(Limit("spend_limit", 10.0, now + 60), Limit("seven_day", 63.0, now + 3 * 86400), Limit("five_hour", 87.6, now + 3600)),
            now, clock, day,
        )
        assertEquals(listOf("5h", "7d"), rings.map { it.label })
        assertEquals(listOf(88, 63), rings.map { it.number })
        assertEquals(listOf(true, false), rings.map { it.warn })
        assertEquals("5-hour window: 88% used, resets t3600", rings[0].words)
        assertEquals("Weekly: 63% used, resets Thu t259200", rings[1].words)
        assertEquals(emptyList<UsageRing>(), usageRings(listOf(Limit("spend_limit", 10.0, now + 60)), now, clock, day))
        assertEquals(1, usageRings(listOf(Limit("seven_day", 5.0, now + 60)), now, clock, day).size)
    }

    @Test
    fun aRingPastItsResetIsEmptyAndSaysItStartedOver() {
        val now = 1_000_000L
        val clock: (Long) -> String = { "t${it - now}" }
        val r = usageRings(listOf(Limit("five_hour", 95.0, now - 30)), now, clock) { "Thu" }.single()
        assertEquals(0, r.number)
        assertEquals(0.0, r.pct, 0.0)
        assertEquals(false, r.warn)
        assertEquals("5-hour window: started over at t-30", r.words)
        // Reset time unknown (0): treated as running, with no time in the words.
        val u = usageRings(listOf(Limit("five_hour", 42.0, 0)), now, clock) { "Thu" }.single()
        assertEquals(42, u.number)
        assertEquals("5-hour window: 42% used", u.words)
    }

    @Test
    fun theRingNumberIsClampedAndWarnsByPercentNotByTheRoundedNumber() {
        val now = 1_000_000L
        val one = { p: Double -> usageRings(listOf(Limit("five_hour", p, now + 60)), now, { "t" }, { "Thu" }).single() }
        val over = one(103.0)
        assertEquals(100, over.number)
        assertEquals(100.0, over.pct, 0.0)
        assertEquals(true, over.warn)
        val near = one(79.6)
        assertEquals(80, near.number)
        assertEquals(false, near.warn)
        assertEquals(true, one(80.0).warn)
        assertEquals(0, one(-3.0).number)
    }

    @Test
    fun oldNumbersSayWhenTheyAreFrom() {
        val now = 1_000_000L
        val clock: (Long) -> String = { "t${it - now}" }
        assertEquals("Numbers from t-1000", usageStale(listOf(Limit("five_hour", 5.0, now + 60, asof = now - 1000)), now, clock))
        assertNull(usageStale(listOf(Limit("five_hour", 5.0, now + 60, asof = now - 600)), now, clock))
        assertNull(usageStale(listOf(Limit("five_hour", 5.0, now + 60, asof = 0)), now, clock))
        // The newest reading counts; the spend line is not a ring.
        val mixed = listOf(Limit("five_hour", 5.0, now + 60, asof = now - 5000), Limit("seven_day", 5.0, now + 60, asof = now - 10), Limit("spend_limit", 1.0, 0, asof = now - 9000))
        assertNull(usageStale(mixed, now, clock))
        assertNull(usageStale(emptyList(), now, clock))
    }

    @Test
    fun theLaptopInOneLine() {
        val gb = 1_073_741_824L
        assertEquals(
            "11.0 of 16.0 GB used · load 6.2 on 16 cores · 2 building, 11 waiting · 14 sessions on all machines",
            laptopWords(Laptop(11 * gb, 16 * gb, 6.2, 16, 2, 11, 14)),
        )
        assertEquals("1 session on all machines", laptopWords(Laptop(null, null, null, null, null, null, 1)))
        assertEquals("0 building", laptopWords(Laptop(null, null, null, null, 0, 0, null)))
        assertNull(laptopWords(Laptop(null, null, null, null, null, null, null)))
    }

    @Test
    fun aFullStorysLine() {
        assertEquals("A long read: what it tried, what failed, the choices, what's left", storyWords(null, null))
        assertEquals("dibs is writing it", storyWords(Story("writing"), null))
        assertEquals("Its agent is writing it", storyWords(Story("writing", by = "agent"), null))
        assertEquals("Written 21:40 · a new one is being written", storyWords(Story("writing", ts = 5, have = true), "21:40"))
        assertEquals("Written 21:40", storyWords(Story("ready", ts = 5, have = true), "21:40"))
        assertEquals("Written 21:40 · the task moved on since", storyWords(Story("ready", ts = 5, stale = true, have = true), "21:40"))
        assertEquals("It couldn't be written", storyWords(Story("failed"), null))
        assertEquals("Written 21:40", storyWords(Story("failed", ts = 5, have = true), "21:40"))
    }

    @Test
    fun theChipOverTheBox() {
        assertEquals("About the full story of #31 Recap", aboutWords(About(31, "ask", "Recap")))
        assertEquals("About a part of the full story of #31 Recap", aboutWords(About(31, "ask", "Recap", "It tried X first.")))
        assertEquals("Follow-up to #31 Recap", aboutWords(About(31, "follow", "Recap")))
    }
}
