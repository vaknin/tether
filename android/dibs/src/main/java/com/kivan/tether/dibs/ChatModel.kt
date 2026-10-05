package com.kivan.tether.dibs

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// The chat's rows (docs/DIBS-APP.md, "Chat"): today in full, earlier days folded one row each,
// grouped runs. Plain Kotlin, so the rules are unit-tested on the JVM.

/** One row of the chat, oldest first. [key] is unique in a list (it keys the LazyColumn). */
sealed interface ChatRow {
    val key: String
}

/** An earlier day folded to one row: "Earlier · Sun 4 Oct · 23 lines". [day] is what [Dibs.open] keys on. */
data class FoldRow(val day: String, val label: String, val count: Int) : ChatRow {
    override val key get() = "fold-$day"
}

/** A day's header (an eyebrow). */
data class DayRow(val day: String, val label: String) : ChatRow {
    override val key get() = "day-$day"
}

/** A bubble, with its place in a run of lines from the same side (one time per run). */
data class LineRow(val line: TalkLine, val first: Boolean, val last: Boolean) : ChatRow {
    override val key get() = line.id
}

/** dibs's own note: a small centred line, never in a run. */
data class NoteRow(val line: TalkLine) : ChatRow {
    override val key get() = line.id
}

/** A message sent from here that no view lists yet, by its uid. */
data class EchoRow(val uid: String, val first: Boolean, val last: Boolean) : ChatRow {
    override val key get() = uid
}

/** A message of mine not in a view yet: its uid and when it was sent (seconds). */
data class Echo(val uid: String, val ts: Long)

/** Lines from one side within this long of each other form a run. */
const val RUN_GAP_SECS = 3 * 60L
/** Anything newer than this is drawn in full, even across midnight. */
const val OPEN_SECS = 6 * 3600L
/** At least this many of the newest lines are always drawn in full. */
const val KEEP_OPEN = 8

/**
 * The chat's rows from [talk] (oldest first) and the [echoes] still waiting, at [now] (seconds).
 * Lines newer than the start of today or the last six hours are open, and so are the newest
 * [KEEP_OPEN]; each earlier day folds to one row unless its key is in [unfolded]. Echoes go last.
 */
fun chatRows(
    talk: List<TalkLine>,
    echoes: List<Echo>,
    now: Long,
    unfolded: Set<String>,
    zone: ZoneId,
    dayLabel: (LocalDate) -> String,
): List<ChatRow> {
    fun day(ts: Long): LocalDate = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate()
    val today = day(now)
    val since = minOf(today.atStartOfDay(zone).toEpochSecond(), now - OPEN_SECS)
    val firstNew = talk.indexOfFirst { it.ts >= since }.let { if (it < 0) talk.size else it }
    val cut = minOf(firstNew, maxOf(0, talk.size - KEEP_OPEN))

    // Ids are list keys: an echo the view already lists is drawn once, as the view's line.
    val ids = talk.mapTo(HashSet()) { it.id }
    val items = talk.mapIndexed { i, l -> Item(l, null, l.ts, l.mine, apart = l.note || l.ask != null, open = i >= cut || day(l.ts).toString() in unfolded) } +
        echoes.filter { it.uid !in ids }.distinctBy { it.uid }.map { Item(null, it.uid, it.ts, mine = true, apart = false, open = true) }

    val out = ArrayList<ChatRow>(items.size + 8)
    var lastOpenDay: LocalDate? = null
    var i = 0
    while (i < items.size) {
        val it = items[i]
        val d = day(it.ts)
        if (!it.open) {
            // Closed lines are all before the cut, so a day's closed lines sit together.
            var n = 0
            while (i + n < items.size && !items[i + n].open && day(items[i + n].ts) == d) n++
            out += FoldRow(d.toString(), dayLabel(d), n)
            lastOpenDay = null
            i += n
            continue
        }
        if (d != lastOpenDay) out += DayRow(d.toString(), dayLabel(d))
        lastOpenDay = d
        val prev = items.getOrNull(i - 1)?.takeIf { out.last() !is DayRow && out.last() !is FoldRow }
        val next = items.getOrNull(i + 1)
        val first = !joins(prev, it, ::day)
        val last = !joins(it, next, ::day)
        out += when {
            it.line == null -> EchoRow(it.uid!!, first, last)
            it.line.note -> NoteRow(it.line)
            else -> LineRow(it.line, first, last)
        }
        i++
    }
    return out
}

private class Item(val line: TalkLine?, val uid: String?, val ts: Long, val mine: Boolean, val apart: Boolean, val open: Boolean)

private fun joins(a: Item?, b: Item?, day: (Long) -> LocalDate): Boolean =
    a != null && b != null && a.open && b.open && a.mine == b.mine && !a.apart && !b.apart &&
        b.ts - a.ts < RUN_GAP_SECS && day(a.ts) == day(b.ts)
