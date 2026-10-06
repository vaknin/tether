package com.kivan.tether.dibs

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// The Tasks tab's rules (docs/DIBS-APP.md, "Your tasks"): the user's tasks grouped by project,
// what needs them first, ticked ones folded away; the words a row and a transcript show. Plain
// Kotlin, so the rules are unit-tested on the JVM.

/** Where a task sorts inside its project, first to last. */
enum class Rank { NEEDS, DONE_UNREAD, WORKING, DONE_READ, ENDED }

fun rank(t: YourTask): Rank = when (t.state) {
    "needs" -> Rank.NEEDS
    "done" -> if (t.unread) Rank.DONE_UNREAD else Rank.DONE_READ
    "stopped", "failed" -> Rank.ENDED
    else -> Rank.WORKING
}

/** One project's tasks, in the order they're drawn. */
data class TaskGroup(val project: String, val tasks: List<YourTask>)

/** The tab: its groups, the ticked fold (newest tick first), and how many are open (not ticked). */
data class TasksList(val groups: List<TaskGroup>, val ticked: List<YourTask>) {
    val open: Int get() = groups.sumOf { it.tasks.size }
}

/**
 * Groups [yours] by project: a group with something that needs the user first, then the others by
 * newest activity. Inside a group: needs you, done and unread, working (and paused), done and read,
 * stopped or failed; newest first within each. [ticked] says what counts as ticked off (the view's
 * `ticked`, or a tap here that the view hasn't caught up with).
 */
fun tasksList(yours: List<YourTask>, ticked: (YourTask) -> Boolean = { it.ticked != null }): TasksList {
    val (off, open) = yours.partition(ticked)
    val groups = open.groupBy { it.project.ifBlank { "Other" } }
        .map { (project, ts) -> TaskGroup(project, ts.sortedWith(compareBy<YourTask> { rank(it) }.thenByDescending { it.ts })) }
        .sortedWith(
            compareBy<TaskGroup> { g -> if (g.tasks.any { rank(it) == Rank.NEEDS }) 0 else 1 }
                .thenByDescending { g -> g.tasks.maxOf { it.ts } },
        )
    return TasksList(groups, off.sortedByDescending { it.ticked ?: it.ts })
}

/** It wants the user: finished and not opened yet, or asking them something. The Tasks badge counts these. */
fun wantsYou(t: YourTask, ticked: Boolean, unread: Boolean): Boolean = !ticked && (unread || t.state == "needs")

/**
 * What the Tasks badge counts, in words: "2 for you: 1 to read, 1 asking you" (to read: finished,
 * stopped, or its agent answered, and not opened since); null when nothing wants the user.
 */
fun forYouWords(read: Int, asking: Int): String? {
    if (read + asking == 0) return null
    val parts = listOfNotNull(
        read.takeIf { it > 0 }?.let { "$it to read" },
        asking.takeIf { it > 0 }?.let { "$it asking you" },
    )
    return "${read + asking} for you: ${parts.joinToString(", ")}"
}

/** A title's longest, in characters, as dibs cuts them. */
const val TITLE_MAX = 60

/**
 * The title a task shows: dibs's [title], without an aside in brackets ("… (the user, word 117)",
 * or one cut open: "Cut phone clutter (the user"). A title that is only a tag before a colon in
 * [asked] ("PLAN ONLY: build the …") takes the words after the tag instead.
 */
fun plainTitle(title: String, asked: String): String {
    val t = title.trim()
    val a = asked.trim()
    if (t.isNotEmpty() && a.length > t.length + 1 && a.startsWith(t) && a[t.length] == ':' && a[t.length + 1].isWhitespace()) {
        firstClause(a.substring(t.length + 1)).takeIf { it.isNotEmpty() }?.let { return it }
    }
    return withoutAsides(t).trimEnd('.', ',', ';', ':', '-', '—', ' ').ifEmpty { t }
}

/** The first clause of the user's words, as dibs makes a title: no `<repo>:` prefix, no asides, cut at a word. */
internal fun firstClause(text: String): String {
    var t = text.trim()
    // "tether: build X" → "build X" (a repo name: one word before the colon and a space after;
    // not a link's "https:" or a time's "12:30").
    val colon = t.indexOf(':')
    if (colon > 0 && t.substring(0, colon).none { it.isWhitespace() } && t.getOrNull(colon + 1)?.isWhitespace() == true && t.substring(colon + 1).isNotBlank()) {
        t = t.substring(colon + 1).trim()
    }
    t = withoutAsides(t.lineSequence().firstOrNull().orEmpty())
    val end = t.indices.firstOrNull { i -> t[i] in ".,;!?:" && t.getOrNull(i + 1) == ' ' } ?: t.length
    val first = t.substring(0, end).trim().trimEnd('.', '!', '?', ',', ';', ':')
    val out = StringBuilder()
    for (w in first.split(SPACES).filter { it.isNotEmpty() }) {
        val next = if (out.isEmpty()) w.length else out.length + 1 + w.length
        if (next > TITLE_MAX) {
            if (out.isEmpty()) out.append(w.take(TITLE_MAX - 1))
            out.append('…')
            break
        }
        if (out.isNotEmpty()) out.append(' ')
        out.append(w)
    }
    return out.toString().replaceFirstChar { it.uppercase() }
}

/** [s] without anything in brackets, and without an opened bracket's tail. */
internal fun withoutAsides(s: String): String {
    val out = StringBuilder()
    var depth = 0
    for (c in s) {
        when {
            c == '(' -> depth++
            c == ')' && depth > 0 -> depth--
            depth == 0 -> out.append(c)
        }
    }
    return out.toString().replace(SPACES, " ").replace(" ,", ",").trim()
}

private val SPACES = Regex("\\s+")

/**
 * A task's state in words: "Needs you", "Working · 40 min", "Paused", "Done 21:36" (a day when it
 * wasn't today), "Stopped", "Couldn't finish". [clock] says a time as the phone does.
 */
fun taskWords(t: YourTask, now: Long, zone: ZoneId, clock: (Long) -> String): String = when (t.state) {
    "needs" -> "Needs you"
    "working" -> if (t.started > 0) "Working · ${duration((now - t.started) / 60)}" else "Working"
    "paused" -> "Paused"
    "done" -> {
        val at = t.finished ?: t.ts
        if (at <= 0) {
            "Done"
        } else {
            val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
            val day = Instant.ofEpochSecond(at).atZone(zone).toLocalDate()
            when (day) {
                today -> "Done ${clock(at)}"
                today.minusDays(1) -> "Done yesterday"
                else -> "Done ${dayWords(day, today)}"
            }
        }
    }
    "stopped" -> "Stopped"
    "failed" -> "Couldn't finish"
    else -> t.state.replaceFirstChar { it.uppercase() }
}

/** How long it ran ([YourTask.minutes] once finished), or has run so far by the phone's clock. */
fun ranMinutes(t: YourTask, now: Long): Long? = t.minutes ?: if (t.started > 0 && !t.finishedState) (now - t.started) / 60 else null

/** A day the screens name: "Today", "Yesterday", "Sun 4 Oct". */
fun dayOf(ts: Long, zone: ZoneId, today: LocalDate): String = dayWords(Instant.ofEpochSecond(ts).atZone(zone).toLocalDate(), today)

/**
 * One run of tool calls in plain words: "12 steps: read 5 files, edited recap.rs, ran 3 commands,
 * 1 failed". A single step is just its own line.
 */
fun stepsSummary(steps: List<Step>): String {
    if (steps.isEmpty()) return "No steps"
    val failed = steps.count { it.error }
    if (steps.size == 1) return steps[0].line.ifBlank { steps[0].tool } + if (failed > 0) ", failed" else ""
    // Kinds in the order they first appear.
    val kinds = LinkedHashMap<String, MutableList<Step>>()
    for (s in steps) kinds.getOrPut(kindOf(s.tool)) { ArrayList() } += s
    val parts = kinds.map { (kind, list) -> kindWords(kind, list) }.toMutableList()
    if (failed > 0) parts += "$failed failed"
    return "${steps.size} steps: ${parts.joinToString(", ")}"
}

private fun kindOf(tool: String): String = when (tool) {
    "Read" -> "read"
    "Edit", "MultiEdit", "NotebookEdit" -> "edit"
    "Write" -> "write"
    "Bash" -> "bash"
    "Grep", "Glob" -> "search"
    "WebSearch" -> "web"
    "WebFetch" -> "fetch"
    "Agent", "Task" -> "agent"
    else -> "other"
}

private fun kindWords(kind: String, list: List<Step>): String {
    val n = list.size
    // The file a Read/Edit/Write line names: "Edited src/recap.rs" → recap.rs.
    val files = list.map { it.line.substringAfter(' ').substringAfterLast('/').trim() }.filter { it.isNotEmpty() }.distinct()
    fun onFiles(verb: String): String = if (files.size == 1) "$verb ${files[0]}" else "$verb ${files.size.coerceAtLeast(1)} files"
    return when (kind) {
        "read" -> onFiles("read")
        "edit" -> onFiles("edited")
        "write" -> onFiles("wrote")
        "bash" -> if (n == 1) "ran a command" else "ran $n commands"
        "search" -> if (n == 1) "searched once" else "searched $n times"
        "web" -> if (n == 1) "searched the web" else "searched the web $n times"
        "fetch" -> if (n == 1) "opened a web page" else "opened $n web pages"
        "agent" -> if (n == 1) "asked a helper" else "asked $n helpers"
        else -> if (n == 1) "1 other step" else "$n other steps"
    }
}
