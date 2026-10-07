package com.kivan.tether.dibs

import org.json.JSONArray
import org.json.JSONObject

// A task's whole transcript (docs/DIBS-APP.md, "Your tasks"): dibs renders it on `fetch` and sends
// it as a channel file, `transcript-<task>-<rev>.json.gz`. Plain Kotlin, unit-tested on a real one.

/** One tool call: its one line, and its input and output (each cut at 2 KB by dibs, saying so). */
data class Step(val tool: String, val line: String, val input: String? = null, val output: String? = null, val error: Boolean = false)

/** who: ask | user | agent | note | steps. [src]: laptop | phone, for the user's lines. */
data class Turn(val who: String, val ts: Long, val text: String?, val src: String?, val steps: List<Step>)

/** One of the task's sessions; how: start | handoff | clear | reopen. */
data class Part(val session: String, val how: String, val ts: Long)

data class Transcript(val task: Long, val rev: String, val asOf: Long, val parts: List<Part>, val turns: List<Turn>) {
    companion object {
        /** The transcript JSON; throws on anything that isn't one. */
        fun parse(json: String): Transcript {
            val o = JSONObject(json)
            return Transcript(
                task = o.optLong("task"),
                rev = o.optString("rev"),
                asOf = o.optLong("as_of"),
                parts = o.optJSONArray("parts").objs().map { Part(it.optString("session"), it.optString("how"), it.optLong("ts")) },
                turns = o.optJSONArray("turns").objs().map { t ->
                    Turn(
                        who = t.optString("who"),
                        ts = t.optLong("ts"),
                        text = t.opt("text")?.takeIf { it != JSONObject.NULL }?.toString(),
                        src = t.optString("src").takeIf { it.isNotEmpty() },
                        steps = t.optJSONArray("steps").objs().map { s ->
                            Step(
                                tool = s.optString("tool"),
                                line = s.optString("line"),
                                input = s.opt("in")?.takeIf { it != JSONObject.NULL }?.toString(),
                                output = s.opt("out")?.takeIf { it != JSONObject.NULL }?.toString(),
                                error = s.optBoolean("error"),
                            )
                        },
                    )
                },
            )
        }
    }
}

/** A row of the transcript screen, top to bottom. [key] is unique in a list. */
sealed interface TRow {
    val key: String
}

/** The task's prompt, folded. */
data class AskRow(val text: String, override val key: String) : TRow

/** The agent's text, in full. */
data class AgentRow(val text: String, val ts: Long, override val key: String) : TRow

/** The user's own line, from the laptop or the phone. */
data class UserRow(val text: String, val ts: Long, val src: String?, override val key: String) : TRow

/** dibs's note or a "keep going" push: a muted system line. */
data class SysRow(val text: String, val ts: Long, override val key: String) : TRow

/** A run of tool calls folded to one line. */
data class StepsRow(val summary: String, val steps: List<Step>, val ts: Long, override val key: String) : TRow

/** Where a new session takes over: "Continued in a fresh context" or "Reopened 22:10" ([how] reopen). */
data class SeamRow(val how: String, val ts: Long, override val key: String) : TRow

/** The rows: each part after the first opens with its seam, before its first turn. */
fun transcriptRows(t: Transcript): List<TRow> {
    val seams = t.parts.drop(1).sortedBy { it.ts }.toMutableList()
    val out = ArrayList<TRow>(t.turns.size + seams.size)
    t.turns.forEachIndexed { i, turn ->
        while (seams.isNotEmpty() && seams[0].ts <= turn.ts) {
            val p = seams.removeAt(0)
            out += SeamRow(p.how, p.ts, "seam-${out.size}")
        }
        val key = "t$i"
        val text = turn.text.orEmpty()
        out += when (turn.who) {
            "ask" -> AskRow(text, key)
            "user" -> UserRow(text, turn.ts, turn.src, key)
            "agent" -> AgentRow(text, turn.ts, key)
            "steps" -> StepsRow(stepsSummary(turn.steps), turn.steps, turn.ts, key)
            else -> SysRow(text, turn.ts, key)
        }
    }
    seams.forEach { out += SeamRow(it.how, it.ts, "seam-${out.size}") }
    return out
}

/** A seam's words; [clock] says a time as the phone does. */
fun seamWords(how: String, ts: Long, clock: (Long) -> String): String =
    if (how == "reopen") "Reopened ${clock(ts)}" else "Continued in a fresh context"

/**
 * The task id and kind a fetched file is for: "transcript-31-ab12.json.gz" → ("transcript", 31);
 * a report or full story the same ("story-31-ab12.md").
 */
fun fetchedOf(name: String): Pair<String, Long>? {
    val m = Regex("""^(transcript|report|story)-(\d+)-""").find(name) ?: return null
    return m.groupValues[1] to m.groupValues[2].toLong()
}

/** The note a loaded idea transcript is for: "idea-<32 hex>-ab12.md" → its id. */
fun ideaFileOf(name: String): String? = Regex("""^idea-([0-9a-f]{32})-""").find(name)?.groupValues?.get(1)

private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
