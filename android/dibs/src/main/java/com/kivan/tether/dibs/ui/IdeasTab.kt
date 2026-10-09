package com.kivan.tether.dibs.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Page
import com.kivan.tether.dibs.RefKind
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.ideas.Draft
import com.kivan.tether.dibs.ideas.Drafts
import com.kivan.tether.dibs.ideas.GROUP_ASKS
import com.kivan.tether.dibs.ideas.GROUP_FILED
import com.kivan.tether.dibs.ideas.GROUP_NOTES
import com.kivan.tether.dibs.ideas.GROUP_READING
import com.kivan.tether.dibs.ideas.IdeaNote
import com.kivan.tether.dibs.ideas.Ideas
import com.kivan.tether.dibs.ideas.IdeaRecording
import com.kivan.tether.dibs.ideas.RecorderState
import com.kivan.tether.dibs.ideas.TextNote
import com.kivan.tether.dibs.ideas.TrashedIdea
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space

// The Ideas tab (PLAN.md "Capture moves into dibs"): record or type an idea at the top; below it
// what this phone made that dibs doesn't list yet, then dibs's notes drawn as it worded them, then
// Trash folded. A tap on a note opens its page.

private const val TRASH = "fold:idea-trash"
private const val FILED = "fold:idea-filed"
internal const val NEW_IDEA_FIELD = "idea:new"

@Composable
internal fun IdeasTab(view: DibsView) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Drafts.resume(context) }
    val all by Drafts.list.collectAsStateWithLifecycle()
    // A recording under way shows as its panel, not as a draft.
    val drafts = all.filter { !it.recording }
    val rec by IdeaRecording.state.collectAsStateWithLifecycle()
    val record = rememberRecord(drop = "ideas")
    val armed = rememberArmed()
    val ideas = view.ideas
    val notes = ideas?.notes.orEmpty().filter { !IdeaTaps.moved(ideas, it.id, "idea-done") }
    val listed = notes.map { it.id }.toSet()
    // A note made here, and an addition to a note this view doesn't list (gone to Trash meanwhile).
    val loose = drafts.filter { it.note == null || it.note !in listed }
    val box = ideas?.box
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.L, end = Space.L, top = Space.M, bottom = Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        item(key = "_make") {
            val recording = rec.takeIf { it is RecorderState.Recording || it is RecorderState.Starting }
            Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
                // The drop box: dibs's words over it, and a note dropped here is read and filed in the Backlog.
                if (box != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(box.placeholder, style = AppType.heading, color = Palette.Text)
                        if (box.hint.isNotBlank()) Text(box.hint, style = AppType.small, color = Palette.Muted)
                    }
                }
                if (recording != null) {
                    val note = (recording as? RecorderState.Recording)?.note
                    RecordPanel(recording, if (note == null) "Recording an idea" else "Adding to ${ideas?.note(note)?.label ?: "a note"}")
                } else {
                    RoundAction(R.drawable.lucide_mic, if (box != null) "Say it" else "Record an idea", Modifier.fillMaxWidth()) { record(null) }
                    FinishedLine(rec)
                }
                IdeaTextBox(NEW_IDEA_FIELD, if (box != null) "Or type it here" else "Type an idea") { t -> later { Drafts.typed(context, t, drop = "ideas") } }
            }
        }
        if (loose.isNotEmpty()) {
            item(key = "_drafts") { Section("On this phone") }
            items(loose, key = { "d-${it.id}" }) { d -> DraftRow(d, armed, Modifier.animateItem()) }
        }
        if (ideas != null) {
            fun group(g: String) = notes.filter { it.group == g }
            val sections = listOf(
                Triple(GROUP_ASKS, "Asks you", group(GROUP_ASKS)),
                Triple(GROUP_READING, "Being read", group(GROUP_READING)),
                Triple(GROUP_NOTES, if (box != null) "Notes" else "Ideas", group(GROUP_NOTES)),
            )
            for ((g, title, list) in sections) {
                if (list.isEmpty()) continue
                item(key = "_$g") { Section("$title · ${list.size}") }
                items(list, key = { "n-${it.id}" }) { n -> NoteRow(n, ideas, drafts.count { it.note == n.id }, Modifier.animateItem()) }
            }
            // A note just ticked off here counts out of the total too.
            val total = ideas.total - (ideas.notes.size - notes.size)
            if (total > notes.size) item(key = "_more") { Text("Showing the newest ${notes.size} of $total", style = AppType.small, color = Palette.Muted) }
            if (notes.isEmpty() && loose.isEmpty()) item(key = "_empty") { Quiet(ideas.empty) }
            val filed = group(GROUP_FILED)
            if (filed.isNotEmpty()) {
                item(key = "_filed") { IdeaFold("Filed · ${filed.size}", FILED) }
                if (Dibs.open[FILED] == true) items(filed, key = { "n-${it.id}" }) { n -> NoteRow(n, ideas, drafts.count { it.note == n.id }, Modifier.animateItem()) }
            }
            val trash = ideas.trash.filter { !IdeaTaps.moved(ideas, it.id, "idea-restore") }
            if (trash.isNotEmpty() || ideas.trashTitle.isNotBlank()) {
                item(key = "_trash") { IdeaFold(ideas.trashTitle.ifBlank { "Ticked off · ${trash.size}" }, TRASH) }
                if (Dibs.open[TRASH] == true) {
                    if (ideas.trashNote.isNotBlank()) item(key = "_trash_note") { Text(ideas.trashNote, style = AppType.small, color = Palette.Muted) }
                    items(trash, key = { "t-${it.id}" }) { t -> TrashRow(t, ideas, Modifier.animateItem()) }
                }
            }
        }
    }
}

/**
 * A note as dibs worded it: "#45" and its title, its meta line, its state, and its question's
 * buttons while it's open. An answer tapped here shows at once.
 */
@Composable
private fun NoteRow(n: IdeaNote, ideas: Ideas, adding: Int, modifier: Modifier) {
    val answered = IdeaTaps.answered(ideas, n.id)
    Column(
        modifier.card().clip(MaterialTheme.shapes.medium).clickable { Dibs.open(Page.Idea(n.id)) }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TitleLine(n.label, n.title)
        if (n.meta.isNotBlank()) Text(n.meta, style = AppType.small, color = Palette.Muted)
        when {
            answered != null -> Text("$answered: sent", style = AppType.small, color = Palette.Muted)
            n.status != null -> IdeaStatusLine(n.status.text, n.status.tone)
        }
        // Filed: where it went, a link to the task's page.
        if (n.group == GROUP_FILED && n.task != null) {
            Text("Open #${n.task}", Modifier.clip(MaterialTheme.shapes.small).clickable { Dibs.openRef(RefKind.TASK, n.task.toInt()) }.padding(vertical = 2.dp), style = AppType.small, color = Palette.Accent)
        }
        if (adding > 0) Text(if (adding == 1) "An addition on its way" else "$adding additions on their way", style = AppType.small, color = Palette.Muted)
        // While dibs asks: an answer box right on the row (the words go as an addition, which dibs reads again).
        if (n.group == GROUP_ASKS && n.answer != null && answered == null) {
            val context = LocalContext.current
            IdeaTextBox(n.answer, "Answer in words", Modifier.padding(top = 4.dp)) { t -> later { Drafts.typed(context, t, note = n.id) } }
        }
        if (answered == null) IdeaButtons(n.actions, Modifier.padding(top = 4.dp)) { IdeaTaps.tap(it, ideas) }
    }
}

/** "#45" (or "#–") in mono, then the title whole. */
@Composable
internal fun TitleLine(label: String, title: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label.isNotBlank()) Text(label, Modifier.alignByBaseline(), style = AppType.mono, color = Palette.Muted)
        Text(title, Modifier.weight(1f).alignByBaseline(), style = AppType.body, color = Palette.Text)
    }
}

/**
 * Something made here that dibs doesn't list yet: being transcribed, waiting to try again, or sent
 * and waiting for the laptop. One Gemini gave up on offers Retry and Delete (asked twice).
 */
@Composable
private fun DraftRow(d: Draft, armed: Armed, modifier: Modifier) {
    val context = LocalContext.current
    Column(modifier.card().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TitleLine(if (d.note == null) "#–" else "", draftTitle(d))
        Text(draftMeta(d), style = AppType.small, color = Palette.Muted)
        val state = draftState(d)
        if (d.failed) Chip(state, warn = true) else Text(state, style = AppType.small, color = if (d.why != null) Palette.Warning else Palette.Muted)
        if (d.failed) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                ActButton("Retry", "") { later { Drafts.retry(context, d.id) } }
                val k = "draft-delete:${d.id}"
                ActButton(if (armed.key == k) "Delete it?" else "Delete", "danger") { armed.press(k) { Drafts.drop(context, d.id) } }
            }
        }
    }
}

internal fun draftTitle(d: Draft): String = when {
    d.title.isNotBlank() -> d.title
    d.note != null -> if (d.typed) d.text else "A spoken addition"
    d.typed -> TextNote.titleFrom(d.text)
    else -> "A spoken idea"
}

/** "Voice · 14:03 · 1:41", as dibs words a note's meta. */
@Composable
internal fun draftMeta(d: Draft): String {
    val kind = when {
        d.note != null -> "Addition"
        d.typed -> "Typed"
        else -> "Voice"
    }
    return listOfNotNull(kind, whenWords(d.createdMs / 1000), d.durationMs?.let(::clock)).joinToString(" · ")
}

/** Where a draft is, in words. */
internal fun draftState(d: Draft): String = when {
    d.failed && !d.gemini -> "Couldn't add it: ${d.why ?: "dibs didn't take it"}"
    d.failed -> "Couldn't transcribe: ${d.why ?: "Gemini gave up"}"
    d.gemini && d.why != null -> "Will try again: ${d.why}"
    d.gemini -> if (d.typed) "Being titled" else "Being transcribed"
    d.sentMs == null -> "Waiting to be sent"
    else -> "Sent, waiting for the laptop"
}

/** A note in Trash: its number and title, how long it stays, and Restore. */
@Composable
private fun TrashRow(t: TrashedIdea, ideas: Ideas, modifier: Modifier) {
    Column(modifier.card().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TitleLine(t.label, t.title)
        if (t.meta.isNotBlank()) Text(t.meta, style = AppType.small, color = Palette.Muted)
        t.status?.let { IdeaStatusLine(it.text, it.tone) }
        IdeaButtons(t.actions, Modifier.padding(top = 4.dp)) { IdeaTaps.tap(it, ideas) }
    }
}

/** A folded section's row: its name and count, a chevron; a tap opens or folds it. */
@Composable
private fun IdeaFold(label: String, key: String) {
    val open = Dibs.open[key] == true
    Row(
        Modifier.fillMaxWidth().padding(top = Space.M).clip(MaterialTheme.shapes.small).clickable { Dibs.toggle(key) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Eyebrow(label, Modifier.weight(1f))
        Icon(
            painterResource(if (open) R.drawable.lucide_chevron_up else R.drawable.lucide_chevron_down),
            if (open) "Fold" else "Open",
            Modifier.size(16.dp),
            tint = Palette.Muted,
        )
    }
}
