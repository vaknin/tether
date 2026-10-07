package com.kivan.tether.dibs.ideas

import org.json.JSONArray
import org.json.JSONObject

/**
 * A button dibs words for the Ideas tab: the action it sends with its [value], its [label], and its
 * [style] (`primary`, `plain` or "" for the usual).
 */
data class IdeaButton(val action: String, val value: JSONObject, val label: String, val style: String)

/** What a note is waiting for or what became of it: its words, and [tone] `ask`, `work`, `done`, `kept` or `problem`. */
data class IdeaStatus(val text: String, val tone: String)

/**
 * One note as dibs sends it, worded for its row and its page (dibs's `ideasapp.rs`): [label] "#69",
 * [meta] "Voice · 6 Oct 14:03 · 1:41", the row's [actions] while its question is open, the page's
 * [page] buttons, and its [transcript] when it rides along (else [fetch] loads it).
 */
data class IdeaNote(
    val id: String,
    val num: Long?,
    val label: String,
    val title: String,
    val summary: String,
    val meta: String,
    val status: IdeaStatus?,
    val task: Long?,
    val actions: List<IdeaButton>,
    val page: List<IdeaButton>,
    val transcript: String?,
    val fetch: IdeaButton?,
    /** The ids of its additions, so an addition sent from here is known to have landed. */
    val adds: Set<String>,
    /** Ask about this note (a dibs that offers it). */
    val ask: com.kivan.tether.dibs.AskEntry? = null,
) {
    /**
     * The start of the name of the file a Load brings: `idea-<id>-<hash>`, the hash naming this
     * text's file, so an older file of a note added to since isn't taken (an older dibs sends none).
     */
    val loadPrefix: String get() = "idea-$id-" + fetch?.value?.optString("hash").orEmpty()
}

/** A note in Trash, with its Restore. */
data class TrashedIdea(val id: String, val label: String, val title: String, val meta: String, val actions: List<IdeaButton>)

/** The Ideas tab as dibs sends it: the notes newest first, how many there are, Trash, and what an empty tab says. */
data class Ideas(
    val notes: List<IdeaNote>,
    val total: Int,
    val empty: String,
    val trashTitle: String,
    val trashNote: String,
    val trash: List<TrashedIdea>,
) {
    fun note(id: String): IdeaNote? = notes.firstOrNull { it.id == id }

    /** Every note id dibs knows of here, listed or in Trash: a draft with one of these has landed. */
    val known: Set<String> by lazy { (notes.map { it.id } + trash.map { it.id }).toSet() }

    companion object {
        fun parse(o: JSONObject): Ideas {
            val t = o.optJSONObject("trash") ?: JSONObject()
            return Ideas(
                notes = o.optJSONArray("notes").objects().mapNotNull(::note),
                total = o.optInt("total"),
                empty = o.optString("empty"),
                trashTitle = t.optString("title"),
                trashNote = t.optString("note"),
                trash = t.optJSONArray("items").objects().mapNotNull { x ->
                    val id = x.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    TrashedIdea(id, x.optString("label"), x.optString("title"), x.optString("meta"), buttons(x.optJSONArray("actions")))
                },
            )
        }

        private fun note(o: JSONObject): IdeaNote? {
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            return IdeaNote(
                id = id,
                num = if (o.isNull("num")) null else o.optLong("num"),
                label = o.optString("label"),
                title = o.optString("title"),
                summary = o.optString("summary"),
                meta = o.optString("meta"),
                status = o.optJSONObject("status")?.let { IdeaStatus(it.optString("text"), it.optString("tone")) },
                task = if (o.isNull("task")) null else o.optLong("task").takeIf { it > 0 },
                actions = buttons(o.optJSONArray("actions")),
                page = buttons(o.optJSONArray("page")),
                transcript = if (o.isNull("transcript")) null else o.optString("transcript"),
                fetch = o.optJSONObject("fetch")?.let(::button),
                adds = o.optJSONArray("adds").let { a -> (0 until (a?.length() ?: 0)).mapNotNull { a?.optString(it)?.takeIf(String::isNotEmpty) }.toSet() },
                ask = o.optJSONObject("ask")?.let { a ->
                    val about = a.optString("about").takeIf { it.isNotEmpty() && !a.isNull("about") }
                    val label = a.optString("label").takeIf { it.isNotEmpty() && !a.isNull("label") }
                    if (about != null && label != null) com.kivan.tether.dibs.AskEntry(about, label) else null
                },
            )
        }

        private fun button(o: JSONObject): IdeaButton? {
            val action = o.optString("action").takeIf { it.isNotEmpty() } ?: return null
            return IdeaButton(action, o.optJSONObject("value") ?: JSONObject(), o.optString("label"), o.optString("style"))
        }

        private fun buttons(a: JSONArray?): List<IdeaButton> = a.objects().mapNotNull(::button)

        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
