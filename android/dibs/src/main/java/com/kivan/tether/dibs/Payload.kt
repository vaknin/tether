package com.kivan.tether.dibs

import org.json.JSONArray
import org.json.JSONObject

// The payload dibs publishes in its channel's view (`"dibs": {…}`, docs/DIBS-APP.md), parsed once
// per view. Every field is optional on the wire: an older or newer dibs still draws.

data class Action(val id: String, val label: String, val style: String = "")

data class FileRef(val id: String, val name: String, val size: Long, val image: Boolean)

/** A question asked in the chat: its buttons while open, then how it ended. */
data class Ask(val q: Long, val actions: List<Action>, val reply: String?, val outcome: String?) {
    val open: Boolean get() = outcome == null
}

data class TalkLine(
    /** The phone's uid for the user's lines (their pending echo clears on it), else `s<n>`. */
    val id: String,
    /** dibs's line number: `h<n>` hides it. */
    val n: Long,
    val mine: Boolean,
    val text: String,
    /** What a note shows until a tap opens [text]. */
    val short: String?,
    /** dibs's own note (a confirmation), drawn small. */
    val note: Boolean,
    val ts: Long,
    val files: List<FileRef>,
    val ask: Ask?,
)

data class Question(
    val id: Long,
    val title: String,
    val why: String,
    val details: String?,
    val from: String,
    val repo: String?,
    val ts: Long,
    val blocking: Boolean,
    /** question | phone | update | permission | idea | task */
    val kind: String,
    val actions: List<Action>,
    /** The typed answer's action id (`r<id>`), when it takes one. */
    val reply: String?,
    val phoneSecs: Long?,
    val phoneUnlock: Boolean,
    /** Asked by one of the user's tasks ([YourTask.id]). */
    val task: Long? = null,
)

data class Decision(val id: Long, val text: String, val why: String, val from: String, val ts: Long, val undo: Boolean, val ack: String)

data class Task(
    val id: Long,
    val name: String,
    val state: String,
    val repo: String?,
    /** How long it has run, from its start (dibs sends no clock, so its views stay still). */
    val minutes: Long,
    val text: String,
    val doing: String?,
    /** busy | idle | shell, when its session is live. */
    val status: String?,
    /** Its live session's pid: what a peek asks for. */
    val pid: Long? = null,
    /** dibs started it by itself (not one of the user's tasks). */
    val background: Boolean = false,
)

data class Session(val name: String, val repo: String?, val branch: String?, val status: String, val task: String?, val holds: List<String>)

data class Ship(val repo: String, val who: String, val why: String, val since: Long)

data class Peek(val who: String, val at: Long, val lines: List<String>)

data class Away(val id: Long, val title: String, val lines: List<String>, val since: Long, val until: Long)

/**
 * One piece of work in Recap (dibs folds a task's lines into one): what changed for the user in
 * one line, why (what they asked), and behind a tap the rest in full.
 */
data class FeedItem(
    val ts: Long,
    /** done | stopped | did | closed | update */
    val kind: String,
    val who: String,
    val text: String,
    val repo: String? = null,
    /** The first sentence of what the user asked. */
    val why: String? = null,
    /** All of what they asked, when it's longer than [why]. */
    val asked: String? = null,
    /** Its other lines, oldest first. */
    val more: List<String> = emptyList(),
    /** The task's report, in full. */
    val report: String? = null,
    /** Stays the same while the piece of work goes on (an older dibs sends none). */
    val id: String? = null,
    /** Its saved Claude chat, when dibs can resume it in a tab on the laptop (action `reopen`). */
    val reopen: String? = null,
    /** One of the user's tasks: a tap opens its page (dibs then sends no report or more). */
    val task: Long? = null,
) {
    /** What keeps its row open while new lines arrive. */
    val key: String get() = "feed:" + (id ?: "$ts:$who")

    /** A tap shows more than the folded row does. */
    val opens: Boolean get() = task == null && (asked != null || more.isNotEmpty() || report != null || reopen != null)

    /** Everything, as plain text (Copy). */
    fun full(): String = buildString {
        append(text)
        report?.let { append("\n\nReport:\n").append(it) }
        if (more.isNotEmpty()) append("\n\nAlong the way:\n").append(more.joinToString("\n") { "• $it" })
        (asked ?: why)?.let { append("\n\nYou asked: ").append(it) }
    }
}

data class Lend(val until: Long?, val holder: String?, val text: String)

/** One lend toggle (task #29): lent to dibs or the user's, what it says, and the action that flips it. */
data class LendToggle(val lent: Boolean, val text: String, val action: String)

/** The phone's and the laptop's toggles; null in a payload from a dibs without them. */
data class Lends(val phone: LendToggle?, val laptop: LendToggle?)

/** One Claude usage window as dibs last read it: five_hour, seven_day or spend_limit, its percent, when it resets. */
data class Limit(val name: String, val pct: Double, val resets: Long)

/** The laptop as dibs last saw it; any part may be missing. Memory in bytes, [load] the 1-minute load average. */
data class Laptop(
    val memUsed: Long?,
    val memTotal: Long?,
    val load: Double?,
    val cores: Int?,
    val builds: Int?,
    val waiting: Int?,
    val agents: Int?,
)

/**
 * [usage]: the line while dibs is out of usage. [limits]: the plan's windows, and [laptop] the
 * laptop's state (a dibs that sends them).
 */
data class State(
    val brain: String?,
    val busy: Boolean,
    val line: String?,
    val usage: String?,
    val limits: List<Limit> = emptyList(),
    val laptop: Laptop? = null,
)

data class Badges(val waiting: Int, val work: Int, val recap: Int, val tasks: Int = 0)

/** Where a task's work went: "Shipped to tether · 3 changes" and their For you lines. */
data class Shipped(val repo: String, val changes: Int, val forYou: List<String>)

data class TaskResult(
    /** The task wrote a REPORT.md (the phone asks for it with `fetch`). */
    val reportMd: Boolean,
    val shipped: List<Shipped>,
)

/**
 * One of the user's own tasks (docs/DIBS-APP.md, "Your tasks"): it waits in the Tasks tab from its
 * start until they tick it off. Its [talk] is the chat with its own agent (who: user, agent, note).
 */
data class YourTask(
    val id: Long,
    val title: String,
    val name: String,
    /** The repo it worked in, or "Other". */
    val project: String,
    /** needs | working | paused | done | stopped | failed */
    val state: String,
    /** Its newest activity. */
    val ts: Long,
    val started: Long,
    val finished: Long? = null,
    /** How long it ran, once finished. */
    val minutes: Long? = null,
    /** What changed, or what it's doing now. */
    val line: String = "",
    /** The user's words, whole. */
    val asked: String = "",
    /** Its report, whole, once finished. */
    val report: String? = null,
    val result: TaskResult? = null,
    /** Its open questions' ids (the same as [DibsView.questions]). */
    val questions: List<Long> = emptyList(),
    /** Its session is working now. */
    val busy: Boolean = false,
    /** It has a live session. */
    val live: Boolean = false,
    /** Finished and not opened yet. */
    val unread: Boolean = false,
    /** When the user ticked it off. */
    val ticked: Long? = null,
    /** The chat with its agent, the last 30 lines, oldest first. */
    val talk: List<TalkLine> = emptyList(),
) {
    /** Its name as the screens show it. */
    val label: String get() = plainTitle(title, asked).ifBlank { name }.ifBlank { "Task $id" }

    /** It has ended one way or another: done, stopped or failed. */
    val finishedState: Boolean get() = state == "done" || state == "stopped" || state == "failed"
}

data class DibsView(
    /** dibs's clock when it published. */
    val now: Long,
    val state: State,
    val talk: List<TalkLine>,
    val questions: List<Question>,
    val decided: List<Decision>,
    val tasks: List<Task>,
    val sessions: List<Session>,
    val ships: List<Ship>,
    val peek: Peek?,
    val away: Away?,
    val feed: List<FeedItem>,
    val lend: Lend?,
    val lends: Lends?,
    val badges: Badges,
    /** The user's own tasks; null from a dibs that doesn't send them (it shows the old Work tab). */
    val yours: List<YourTask>? = null,
) {
    fun task(id: Long): YourTask? = yours?.firstOrNull { it.id == id }

    companion object {
        /** The payload of a whole channel view, or null when dibs sent none (an older dibs). */
        fun ofView(view: JSONObject?): DibsView? = view?.optJSONObject("dibs")?.let(::parse)

        fun parse(o: JSONObject): DibsView {
            val st = o.optJSONObject("state") ?: JSONObject()
            val recap = o.optJSONObject("recap") ?: JSONObject()
            val b = o.optJSONObject("badges") ?: JSONObject()
            return DibsView(
                now = o.optLong("now"),
                state = State(
                    st.str("brain"), st.optBoolean("busy"), st.str("line"), st.str("usage"),
                    st.optJSONObject("limits")?.optJSONArray("windows").objects().map { Limit(it.optString("name"), it.optDouble("pct", 0.0), it.optLong("resets")) },
                    st.optJSONObject("laptop")?.let { l ->
                        Laptop(l.long("mem_used"), l.long("mem_total"), l.double("load"), l.long("cores")?.toInt(), l.long("builds")?.toInt(), l.long("waiting")?.toInt(), l.long("agents")?.toInt())
                    },
                ),
                talk = o.optJSONArray("talk").objects().map(::talkLine),
                questions = o.optJSONArray("questions").objects().map(::question),
                decided = o.optJSONArray("decided").objects().map {
                    Decision(it.optLong("id"), it.optString("text"), it.optString("why"), it.optString("from"), it.optLong("ts"), it.optBoolean("undo"), it.optString("ack"))
                },
                tasks = o.optJSONArray("tasks").objects().map {
                    val started = it.optLong("started")
                    val minutes = if (started > 0) (System.currentTimeMillis() / 1000 - started) / 60 else it.optLong("minutes")
                    Task(
                        it.optLong("id"), it.optString("name"), it.optString("state"), it.str("repo"), minutes, it.optString("text"), it.str("doing"),
                        it.str("status"), it.optLong("pid").takeIf { p -> p > 0 }, it.optBoolean("background"),
                    )
                },
                sessions = o.optJSONArray("sessions").objects().map {
                    Session(it.optString("name"), it.str("repo"), it.str("branch"), it.optString("status"), it.str("task"), it.optJSONArray("holds").strings())
                },
                ships = o.optJSONArray("ships").objects().map { Ship(it.optString("repo"), it.optString("who"), it.optString("why"), it.optLong("since")) },
                peek = o.optJSONObject("peek")?.let { Peek(it.optString("who"), it.optLong("at"), it.optJSONArray("lines").strings()) },
                away = recap.optJSONObject("away")?.let {
                    Away(it.optLong("id"), it.optString("title"), it.optJSONArray("lines").strings(), it.optLong("since"), it.optLong("until"))
                },
                feed = recap.optJSONArray("feed").objects().map {
                    FeedItem(
                        it.optLong("ts"), it.optString("kind"), it.optString("who"), it.optString("text"),
                        it.str("repo"), it.str("why"), it.str("asked"), it.optJSONArray("more").strings(), it.str("report"), it.str("id"), it.str("reopen"),
                        it.long("task"),
                    )
                },
                lend = o.optJSONObject("lend")?.let { Lend(it.optLong("until").takeIf { u -> u > 0 }, it.str("holder"), it.optString("text")) },
                lends = o.optJSONObject("lends")?.let { l -> Lends(l.optJSONObject("phone")?.let(::lendToggle), l.optJSONObject("laptop")?.let(::lendToggle)) },
                badges = Badges(b.optInt("waiting"), b.optInt("work"), b.optInt("recap"), b.optInt("tasks")),
                yours = if (o.has("yours")) o.optJSONArray("yours").objects().map(::yourTask) else null,
            )
        }

        private fun yourTask(o: JSONObject): YourTask {
            val r = o.optJSONObject("result")
            return YourTask(
                id = o.optLong("id"),
                title = o.optString("title"),
                name = o.optString("name"),
                project = o.str("project") ?: "Other",
                state = o.optString("state"),
                ts = o.optLong("ts"),
                started = o.optLong("started"),
                finished = o.long("finished"),
                minutes = if (o.has("minutes") && !o.isNull("minutes")) o.optLong("minutes") else null,
                line = o.optString("line"),
                asked = o.optString("asked"),
                report = o.str("report"),
                result = r?.let {
                    TaskResult(
                        it.optBoolean("report_md"),
                        it.optJSONArray("shipped").objects().map { s -> Shipped(s.optString("repo"), s.optInt("changes"), s.optJSONArray("for_you").strings()) },
                    )
                },
                questions = o.optJSONArray("questions").let { a -> if (a == null) emptyList() else (0 until a.length()).map { a.optLong(it) } },
                busy = o.optBoolean("busy"),
                live = o.optBoolean("live"),
                unread = o.optBoolean("unread"),
                ticked = o.long("ticked"),
                talk = o.optJSONArray("talk").objects().map { l ->
                    val who = l.optString("who")
                    TalkLine(
                        id = l.optString("id"), n = 0, mine = who == "user", text = l.optString("text"), short = l.str("short"),
                        note = who == "note", ts = l.optLong("ts"), files = files(l.optJSONArray("files")), ask = null,
                    )
                },
            )
        }

        private fun files(a: JSONArray?) = a.objects().map { FileRef(it.optString("id"), it.optString("name"), it.optLong("size"), it.optBoolean("image")) }

        private fun lendToggle(o: JSONObject): LendToggle? =
            o.optString("action").takeIf { it.isNotBlank() }?.let { LendToggle(o.optBoolean("lent"), o.optString("text"), it) }

        private fun talkLine(o: JSONObject) = TalkLine(
            id = o.optString("id"),
            n = o.optLong("n"),
            mine = o.optString("who") == "user",
            text = o.optString("text"),
            short = o.str("short"),
            note = o.optBoolean("note"),
            ts = o.optLong("ts"),
            files = files(o.optJSONArray("files")),
            ask = o.optJSONObject("ask")?.let { a ->
                Ask(a.optLong("q"), actions(a.optJSONArray("actions")), a.str("reply"), a.str("outcome"))
            },
        )

        private fun question(o: JSONObject): Question {
            val phone = o.optJSONObject("phone")
            return Question(
                id = o.optLong("id"),
                title = o.optString("title"),
                why = o.optString("why"),
                details = o.str("details"),
                from = o.optString("from"),
                repo = o.str("repo"),
                ts = o.optLong("ts"),
                blocking = o.optBoolean("blocking"),
                kind = o.optString("kind", "question"),
                actions = actions(o.optJSONArray("actions")),
                reply = o.str("reply"),
                phoneSecs = phone?.optLong("secs"),
                phoneUnlock = phone?.optBoolean("unlock") == true,
                task = o.long("task"),
            )
        }

        private fun actions(a: JSONArray?) = a.objects().map { Action(it.optString("id"), it.optString("label"), it.optString("style")) }
    }
}

/** A string field, or null when it's missing, null or empty. */
private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

/** A number field, or null when it's missing or null. */
private fun JSONObject.long(k: String): Long? = if (!has(k) || isNull(k)) null else optLong(k)

private fun JSONObject.double(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it)?.toString() }
