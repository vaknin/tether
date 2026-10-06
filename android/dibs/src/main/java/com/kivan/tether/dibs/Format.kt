package com.kivan.tether.dibs

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Words and numbers the screens show, kept plain so they're unit-tested.

/** "46 min", "1 h 52 min", "3 h". */
fun duration(minutes: Long): String {
    val m = maxOf(0, minutes)
    if (m < 60) return "$m min"
    val h = m / 60
    val rest = m % 60
    return if (rest == 0L) "$h h" else "$h h $rest min"
}

/** How long ago, short: "now", "12 min", "1 h", "3 d". */
fun age(secs: Long): String = when {
    secs < 60 -> "now"
    secs < 3600 -> "${secs / 60} min"
    secs < 86400 -> "${secs / 3600} h"
    else -> "${secs / 86400} d"
}

/** "980 B", "12 KB", "3.4 MB". */
fun fileSize(bytes: Long): String = when {
    bytes < 1000 -> "$bytes B"
    bytes < 1_000_000 -> "${(bytes + 500) / 1000} KB"
    bytes < 1_000_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)
    else -> String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)
}

private val awayTitle = Regex("""(\d+)\s*h(?:\s*(\d+)\s*m)?|(\d+)\s*m""")

/**
 * How long the user was away, in minutes: from [Away.since] and [Away.until], else from its title
 * ("While you were away (3h 7m)"); null when neither says.
 */
fun awayMinutes(a: Away): Long? {
    if (a.since > 0 && a.until > a.since) return (a.until - a.since) / 60
    val m = awayTitle.find(a.title.substringAfter('(', "")) ?: return null
    val (h, hm, mOnly) = m.destructured
    return if (h.isNotEmpty()) h.toLong() * 60 + (hm.toLongOrNull() ?: 0) else mOnly.toLong()
}

/** A closed question's outcome as the folded row shows it ("Answered: Yes" → "Yes"). */
fun outcomeWords(outcome: String): String = outcome.removePrefix("Answered: ").removePrefix("Answered:").trim()

/** "Today", "Yesterday", or "Sun 4 Oct" for a chat or feed day. */
fun dayWords(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", locale))
}

/** A held thing as a chip says it: "repo:dibs:master" → "dibs master", "phone:…" → "phone". */
fun holdWords(hold: String): String = when {
    hold.startsWith("phone") -> "phone"
    hold.startsWith("repo:") -> hold.removePrefix("repo:").replace(':', ' ')
    else -> hold
}

/** The header's state line, by precedence: the link first, then what dibs is doing. */
fun stateWords(link: Link, state: State?): String = when {
    link == Link.UNPAIRED -> "Pairing lost"
    link == Link.OFFLINE -> "Not connected"
    state == null -> "Waiting for dibs"
    state.busy -> "On it"
    !state.usage.isNullOrBlank() -> "Out of usage"
    else -> "Ready"
}

/** A usage window's short name: "5h", "7d", "spend". */
fun limitName(name: String): String = when (name) {
    "five_hour" -> "5h"
    "seven_day" -> "7d"
    "spend_limit" -> "spend"
    else -> name
}

/** At this percent a window's line turns amber. */
const val LIMIT_WARN = 80.0

/** One line of the header's usage: its words, and whether it's near the end ([LIMIT_WARN]). */
data class LimitLine(val text: String, val warn: Boolean)

/**
 * The header's usage lines, one per window still running at [now]: "5h 87% · resets 12:20",
 * "7d 63% · resets Thu 09:00" (the day when it's more than 20 hours off). [clock] says a time as
 * the phone does; [day] its weekday.
 */
fun limitWords(limits: List<Limit>, now: Long, clock: (Long) -> String, day: (Long) -> String): List<LimitLine> =
    limits.filter { it.resets > now }.map { l ->
        val at = if (l.resets - now > 20 * 3600) "${day(l.resets)} ${clock(l.resets)}" else clock(l.resets)
        LimitLine("${limitName(l.name)} ${kotlin.math.round(l.pct).toInt()}% · resets $at", l.pct >= LIMIT_WARN)
    }
