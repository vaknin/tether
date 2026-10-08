package com.kivan.tether.dibs

import org.json.JSONObject

// Task and idea links in text (task #261): `#230` names task 230, `idea 45` idea 45. The same rule as
// dibs's `refs::find` and the desktop app's (`refs.ts`), held to the same cases (links-cases.json).
// The index (a file dibs sends when `index_rev` changes) gives each its title and state for the
// long-press card and the `#` picker. Pure: no Android, and offsets are UTF-16 like Kotlin strings.

enum class RefKind { TASK, IDEA }

/** One link: [start] until [end] of the text, the task or idea [n] it names. */
data class Ref(val start: Int, val end: Int, val kind: RefKind, val n: Int)

private fun wordChar(c: Char): Boolean = c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z' || c == '_'

/** Code: ``` fences (to the closing fence, or the end) and `inline` spans on one line. */
private fun codeRanges(text: String): List<IntRange> {
    val out = ArrayList<IntRange>()
    var i = 0
    while (i < text.length) {
        if (text[i] != '`') {
            i++
            continue
        }
        if (text.startsWith("```", i)) {
            val e = text.indexOf("```", i + 3)
            val end = if (e < 0) text.length else e + 3
            out += i until end
            i = end
            continue
        }
        var j = i + 1
        while (j < text.length && text[j] != '`' && text[j] != '\n') j++
        if (j < text.length && text[j] == '`') {
            out += i until j + 1
            i = j + 1
        } else i++
    }
    return out
}

/** 1 to 5 digits at [at], not followed by a letter or digit (or any non-ASCII character): the number and where it ends. */
private fun numberAt(text: String, at: Int): Pair<Int, Int>? {
    var e = at
    while (e < text.length && text[e] in '0'..'9') e++
    val len = e - at
    if (len == 0 || len > 5) return null
    if (e < text.length && (wordChar(text[e]) || text[e].code >= 0x80)) return null
    val n = text.substring(at, e).toInt()
    return if (n > 0) n to e else null
}

/** Every link in [text], in order. Not inside code, not glued to a word before it (`a#1`, `/#1`, `&#1`). */
fun findRefs(text: String): List<Ref> {
    val code = codeRanges(text)
    fun inCode(i: Int) = code.any { i in it }
    val out = ArrayList<Ref>()
    var i = 0
    while (i < text.length) {
        if (text.length >= i + 4 && text.regionMatches(i, "idea", 0, 4, ignoreCase = true) && (i == 0 || !wordChar(text[i - 1])) && !inCode(i)) {
            var j = i + 4
            val gap = j
            while (j < text.length && text[j] == ' ') j++
            if (j > gap && j < text.length && text[j] == '#') j++
            val m = if (j > gap) numberAt(text, j) else null
            if (m != null) {
                out += Ref(i, m.second, RefKind.IDEA, m.first)
                i = m.second
                continue
            }
        }
        if (text[i] == '#' && (i == 0 || !(wordChar(text[i - 1]) || text[i - 1] in "/&#")) && !inCode(i)) {
            val m = numberAt(text, i + 1)
            if (m != null) {
                out += Ref(i, m.second, RefKind.TASK, m.first)
                i = m.second
                continue
            }
        }
        i++
    }
    return out
}

/** A piece of a text: plain, or a link ([ref] set). */
data class Seg(val text: String, val ref: Ref? = null)

/** The text cut at its links; [known] keeps only links to tasks and ideas that exist (all without it). */
fun refSegments(text: String, known: ((RefKind, Int) -> Boolean)? = null): List<Seg> {
    val segs = ArrayList<Seg>()
    var at = 0
    for (r in findRefs(text)) {
        if (known != null && !known(r.kind, r.n)) continue
        if (r.start > at) segs += Seg(text.substring(at, r.start))
        segs += Seg(text.substring(r.start, r.end), r)
        at = r.end
    }
    if (at < text.length || segs.isEmpty()) segs += Seg(text.substring(at))
    return segs
}

// ---------- the index ----------

/** [s] its state in words ("Queued, 3rd in line"), [g] needs | working | queued | later | done | stopped, [p] project, [a] the ask's first words, [f] when it finished. */
data class IndexTask(val n: Int, val t: String, val s: String, val g: String, val p: String, val a: String, val f: Long?)

/** [s] active | ticked | deleted. */
data class IndexIdea(val n: Int, val t: String, val s: String)

class RefIndex(val tasks: List<IndexTask>, val ideas: List<IndexIdea>) {
    private val taskBy = tasks.associateBy { it.n }
    private val ideaBy = ideas.associateBy { it.n }

    fun task(n: Int): IndexTask? = taskBy[n]
    fun idea(n: Int): IndexIdea? = ideaBy[n]
    fun knows(kind: RefKind, n: Int): Boolean = if (kind == RefKind.TASK) n in taskBy else n in ideaBy

    companion object {
        /** The index file's JSON (`{tasks:[…], ideas:[…]}`); null when it isn't an object. Entries without a number are dropped. */
        fun parse(json: String): RefIndex? = runCatching { parse(JSONObject(json)) }.getOrNull()

        fun parse(o: JSONObject): RefIndex {
            fun s(x: JSONObject, k: String) = if (x.isNull(k)) "" else x.optString(k)
            val tasks = (0 until (o.optJSONArray("tasks")?.length() ?: 0)).mapNotNull { o.optJSONArray("tasks")?.optJSONObject(it) }
                .map { IndexTask(it.optInt("n"), s(it, "t"), s(it, "s"), s(it, "g"), s(it, "p"), s(it, "a"), if (it.has("f") && !it.isNull("f")) it.optLong("f") else null) }
                .filter { it.n > 0 }
            val ideas = (0 until (o.optJSONArray("ideas")?.length() ?: 0)).mapNotNull { o.optJSONArray("ideas")?.optJSONObject(it) }
                .map { IndexIdea(it.optInt("n"), s(it, "t"), s(it, "s")) }
                .filter { it.n > 0 }
            return RefIndex(tasks, ideas)
        }
    }
}

// ---------- the # picker ----------

/** One row of the picker: a task or an idea, its [state] in words and [group] (tasks: needs…stopped; ideas: active, ticked, deleted). */
data class Pick(val kind: RefKind, val n: Int, val title: String, val state: String, val group: String, val project: String)

private val GROUPS = listOf("needs", "working", "queued", "later", "done", "stopped")
private val IDEA_STATES = listOf("active", "ticked", "deleted")
private val wordRe = Regex("[\\p{L}\\p{N}]+")

private fun words(s: String): List<String> = wordRe.findAll(s.lowercase()).map { it.value }.toList()

/** Every word of the query starts a word of the text. */
private fun matches(q: List<String>, text: String): Boolean {
    val w = words(text)
    return q.all { x -> w.any { it.startsWith(x) } }
}

/**
 * What the picker lists for [q] (what follows the #): what needs the user, then working, queued,
 * held, done and stopped (finished ones newest first, the others highest number first), then ideas.
 * Only digits: the number itself first, then numbers starting with them. Words: every word starts a
 * word of the title or the ask.
 */
fun pick(index: RefIndex, q: String): List<Pick> {
    val query = q.trim()
    val digits = query.isNotEmpty() && query.all { it in '0'..'9' }
    val qw = words(query)
    fun g(x: String) = GROUPS.indexOf(x).let { if (it < 0) GROUPS.size else it }
    fun exact(n: Int) = if (digits && n.toString() == query) 0 else 1
    // Sorted by (rank, then newest finished or highest number); a stable sort keeps the index's order for ties.
    val tasks = index.tasks.filter { if (digits) it.n.toString().startsWith(query) else matches(qw, "${it.t} ${it.a}") }
        .sortedWith(compareBy<IndexTask> { exact(it.n) * 100 + g(it.g) }.thenBy { -(it.f ?: it.n.toLong()) })
    val ideas = index.ideas.filter { if (digits) it.n.toString().startsWith(query) else matches(qw, it.t) }
        .sortedWith(compareBy<IndexIdea> { exact(it.n) * 100 + maxOf(0, IDEA_STATES.indexOf(it.s)) }.thenBy { -it.n })
    return tasks.map { Pick(RefKind.TASK, it.n, it.t, it.s, it.g, it.p) } +
        ideas.map { Pick(RefKind.IDEA, it.n, it.t, ideaWords(it.s), it.s, "") }
}

fun ideaWords(s: String): String = when (s) {
    "ticked" -> "Ticked off"
    "deleted" -> "Deleted"
    else -> "Idea"
}

/** The `#` being typed before the caret: where it is ([start]) and what follows it ([q]). */
data class PickQuery(val start: Int, val q: String)

private val beforeHash = Regex("""\s|[(\[{"'“‘]""")

/** The `#…` being typed before [caret]; null when none. */
fun pickQuery(text: String, caret: Int): PickQuery? {
    val before = text.substring(0, caret.coerceIn(0, text.length))
    val at = before.lastIndexOf('#')
    if (at < 0) return null
    if (at > 0 && !beforeHash.matches(before[at - 1].toString())) return null
    val q = before.substring(at + 1)
    // A line break, two spaces or a long run ends it; so does a finished link ("#230 ").
    if (q.contains('\n') || q.contains("  ") || q.length > 40 || Regex("""^\d+\s""").containsMatchIn(q)) return null
    return PickQuery(at, q)
}

/** The text with the `#…` at [start] until [caret] replaced by the pick and a space; the caret after it. */
fun insertPick(text: String, start: Int, caret: Int, kind: RefKind, n: Int): Pair<String, Int> {
    val word = if (kind == RefKind.TASK) "#$n " else "idea $n "
    val after = text.substring(caret).removePrefix(" ")
    return (text.substring(0, start) + word + after) to (start + word.length)
}
