package com.kivan.tether.dibs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Page
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.ideas.Drafts
import com.kivan.tether.dibs.ideas.GROUP_ASKS
import com.kivan.tether.dibs.ideas.IdeaNote
import com.kivan.tether.dibs.ideas.IdeaRecording
import com.kivan.tether.dibs.ideas.RecorderState
import com.kivan.tether.dibs.markdownBlocks
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A note's page (PLAN.md "Capture moves into dibs"): its number, title and meta, its state (and its
 * task's page when it has one), Summary | Transcript, Copy, Add (type or record more), and dibs's
 * buttons for it. A long transcript loads on a tap.
 */
@Composable
internal fun IdeaPage(id: String, view: DibsView) {
    val n = view.ideas?.note(id)
    LaunchedEffect(n == null) { if (n == null) Dibs.back() }
    n ?: return
    val context = LocalContext.current
    val all by Drafts.list.collectAsStateWithLifecycle()
    val drafts = all.filter { !it.recording }
    val rec by IdeaRecording.state.collectAsStateWithLifecycle()
    val record = rememberRecord()
    val transcript = rememberTranscript(n)
    var showTranscript by rememberSaveable(id) { mutableStateOf(n.summary.isBlank()) }
    val answered = IdeaTaps.answered(view.ideas, n.id)
    val armed = rememberArmed()

    Column(Modifier.fillMaxSize()) {
        PageBar(n.title, n.label) {
            val shown = if (showTranscript) transcript else n.summary
            if (!shown.isNullOrBlank()) {
                IconButton(onClick = { copy(context, shown) }) {
                    Icon(painterResource(R.drawable.lucide_copy), "Copy", Modifier.size(20.dp), tint = Palette.Text)
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = Space.L, end = Space.L, bottom = Space.XXL),
            verticalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            if (n.meta.isNotBlank()) Text(n.meta, style = AppType.small, color = Palette.Muted)
            when {
                answered != null -> Text("$answered: sent", style = AppType.small, color = Palette.Muted)
                n.status != null -> IdeaStatusLine(n.status.text, n.status.tone)
            }
            n.task?.let { task ->
                if (view.task(task) != null) ActButton("Open its task", "") { Dibs.open(Page.Task(task)) }
            }
            IdeaButtons(n.actions.takeIf { answered == null }.orEmpty()) { IdeaTaps.tap(it, view.ideas) }
            // A dibs that offers it: a short conversation about this note, on its own page.
            n.ask?.let { ask ->
                ActButton(ask.label, "primary", icon = R.drawable.lucide_message_circle_question) { Dibs.askAbout(view, ask.about, n.title) }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Summary", "Transcript").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = showTranscript == (i == 1),
                        onClick = { showTranscript = i == 1 },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Palette.AccentDim,
                            activeContentColor = Palette.Text,
                            inactiveContainerColor = Palette.Bg,
                            inactiveContentColor = Palette.Muted,
                        ),
                    ) { Text(label, style = AppType.label) }
                }
            }
            if (showTranscript) TranscriptPart(n, transcript) else SummaryPart(n.summary)
            RefSection("Linked", n.links)

            val adding = drafts.filter { it.note == n.id }.sortedBy { it.createdMs }
            if (adding.isNotEmpty()) {
                Eyebrow("On its way", Modifier.padding(top = Space.S))
                for (d in adding) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (d.typed) Text(d.text, style = AppType.body, color = Palette.Text)
                        Text(draftState(d), style = AppType.small, color = if (d.failed || d.why != null) Palette.Warning else Palette.Muted)
                        if (d.failed) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                                ActButton("Retry", "") { later { Drafts.retry(context, d.id) } }
                                val k = "draft-delete:${d.id}"
                                ActButton(if (armed.key == k) "Delete it?" else "Delete", "danger") { armed.press(k) { Drafts.drop(context, d.id) } }
                            }
                        }
                    }
                }
            }

            // While dibs asks about it, the box is the answer (it goes as an addition, which dibs reads again).
            val asking = n.group == GROUP_ASKS
            Eyebrow(if (asking) "Answer" else "Add to it", Modifier.padding(top = Space.S))
            val recording = rec.takeIf { it is RecorderState.Recording || it is RecorderState.Starting }
            if (recording != null) {
                RecordPanel(recording, if ((recording as? RecorderState.Recording)?.note == n.id) "Adding to ${n.label}" else "Recording")
            } else {
                IdeaTextBox(n.answer ?: "idea:add:${n.id}", if (asking) "Type your answer" else "Type more") { t -> later { Drafts.typed(context, t, note = n.id) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActButton(if (asking) "Say your answer" else "Record more", "") { record(n.id) }
                    Box(Modifier.weight(1f).padding(start = Space.S)) { FinishedLine(rec) }
                }
            }

            // What the row already offers above (File it as it is, Drop it) isn't offered twice.
            val rest = n.page.filter { p -> n.actions.none { it.action == p.action && it.value.toString() == p.value.toString() } }
            IdeaButtons(rest.takeIf { answered == null }.orEmpty(), Modifier.padding(top = Space.S)) { b ->
                IdeaTaps.tap(b, view.ideas)
                if (b.action == "idea-done") Dibs.back()
            }
        }
    }
}

/** The transcript as dibs sent it, else the one loaded on a tap (a channel file), else null. */
@Composable
private fun rememberTranscript(n: IdeaNote): String? {
    val prefix = n.loadPrefix
    val file by remember(prefix) { Dibs.host.channelFile(prefix) }.collectAsStateWithLifecycle(null)
    // A note added to since names a new file: the old text goes until that one is loaded.
    val loaded by produceState<String?>(null, file) {
        value = file?.let { f -> withContext(Dispatchers.IO) { runCatching { f.readText() }.getOrNull() } }
    }
    return n.transcript ?: loaded
}

@Composable
private fun TranscriptPart(n: IdeaNote, transcript: String?) {
    when {
        transcript != null -> SelectionContainer {
            Text(transcript, style = AppType.body.copy(textDirection = TextDirection.Content), color = Palette.Text)
        }
        n.fetch != null -> {
            var asked by remember(n.id) { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                Text(
                    if (asked) "Asked the laptop for it. It shows here when it arrives." else "This transcript is long, so it comes from the laptop.",
                    style = AppType.small,
                    color = Palette.Muted,
                )
                ActButton(n.fetch.label.ifBlank { "Load the whole transcript" }, if (asked) "" else "primary") {
                    asked = true
                    IdeaTaps.tap(n.fetch, null)
                }
            }
        }
        else -> Quiet("No transcript.")
    }
}

@Composable
private fun SummaryPart(summary: String) {
    if (summary.isBlank()) {
        Quiet("No summary yet.")
        return
    }
    val blocks = remember(summary) { runCatching { markdownBlocks(summary) }.getOrNull() }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(ReportLook.gap)) {
            if (blocks == null) Text(summary, style = AppType.body, color = Palette.Text) else blocks.forEach { Block(it, ReportLook) }
        }
    }
}
