package com.kivan.tether.dibs.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kivan.tether.dibs.BoardCard
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Page
import com.kivan.tether.dibs.Progress
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.RefKind
import com.kivan.tether.dibs.ideas.Draft
import com.kivan.tether.dibs.ideas.Drafts
import com.kivan.tether.dibs.ideas.IdeaRecording
import com.kivan.tether.dibs.ideas.RecorderState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.TapState
import com.kivan.tether.dibs.YourTask
import com.kivan.tether.dibs.barFill
import com.kivan.tether.dibs.dayOf
import com.kivan.tether.dibs.forYouWords
import com.kivan.tether.dibs.progressLine
import com.kivan.tether.dibs.taskWords
import com.kivan.tether.dibs.tasksList
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Pill
import com.kivan.tether.dibs.ui.theme.Space
import java.time.LocalDate
import java.time.ZoneId

// The Tasks tab (docs/DIBS-APP.md, "Your tasks"): every task the user asked for, from its start
// until they tick it off; a tap opens its page. From a dibs that sends its board, the tab draws
// that board unchanged ("The board": its columns and cards in its order, then Done); from an
// older one, the tasks grouped by project here, the ticked ones folded at the bottom. dibs's own
// work folds away at the bottom either way.

private const val OWN_WORK = "fold:own-work"
private const val TICKED = "fold:ticked"
private const val DONE = "fold:done"

/**
 * A board card's actions in plain words, in the order its menu would show them if dibs sent them so. Start and Park
 * are the cards' faces ([BoardCard.primary]), not menu entries. dibs's "move" is never offered. The old names
 * (hold, resume, to_next, to_later) are for a dibs that has not moved to Active and Backlog yet.
 */
private val ACT_WORDS = mapOf(
    "start" to "Start",
    "park" to "Park",
    "start_now" to "Start now",
    "hold" to "Put on hold",
    "resume" to "Resume",
    "stop" to "Stop",
    "delete" to "Delete",
    "up" to "Move up",
    "down" to "Move down",
    "to_next" to "Move to the queue",
    "to_later" to "Move to Backlog",
    "story" to "Full story",
)

@Composable
internal fun TasksTab(view: DibsView) {
    val yours = view.yours.orEmpty()
    val now by rememberNow()
    val armed = rememberArmed()
    val answered = Dibs.answered.toMap()
    val list = remember(yours, answered) { tasksList(yours) { Dibs.ticked(it) } }
    val all by Drafts.list.collectAsStateWithLifecycle()
    val own = view.tasks.count { it.background } + view.sessions.size + view.ships.size
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.L, end = Space.L, bottom = Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        // The queue is on hold (a move, a used-up plan): said first, or it just looks stuck (word 601).
        view.board?.hold?.let { hold -> item(key = "_hold") { RoomLine(hold, warn = true, Modifier.padding(top = Space.M)) } }
        // What the tab's badge counts, said plainly (nothing when nothing wants you).
        val open = list.groups.flatMap { it.tasks }
        val read = open.count { it.state != "needs" && Dibs.unread(it) }
        val asking = open.count { it.state == "needs" }
        forYouWords(read, asking)?.let { words ->
            item(key = "_for_you") { Eyebrow(words, Modifier.padding(top = Space.M, bottom = Space.XS), dot = true, color = Palette.Accent) }
        }
        val board = view.board
        if (board != null) {
            // dibs's board as it sent it: no grouping or sorting here.
            val columns = board.columns.filter { it.cards.isNotEmpty() || it.box != null }
            for (c in columns) {
                // The laptop has no room: say why the waiting ones wait, right above them (an old dibs: the "next" column).
                if ((c.key == "active" || c.key == "next") && board.room?.n == 0) item(key = "_room") { RoomLine(board.room.line, warn = true, Modifier.padding(top = Space.M)) }
                item(key = "col-${c.key}") { Section("${c.title} · ${c.cards.count { !it.key.startsWith("drop:") }}${c.cards.count { it.key.startsWith("drop:") }.let { if (it > 0) " (+$it being read)" else "" }}") }
                c.box?.let { placeholder ->
                    item(key = "_box-${c.key}") { BacklogBox(placeholder) }
                    // What was dropped here and isn't listed yet: muted, until dibs's view lists it.
                    val pending = all.filter { it.drop == "backlog" && it.note == null && !it.recording }
                    items(pending, key = { "p-${it.id}" }) { d -> PendingDrop(d, Modifier.animateItem()) }
                }
                items(c.cards, key = { "c-${c.key}-${it.key}" }) { card -> BoardCardRow(card, view, armed, Modifier.animateItem()) }
            }
            if (columns.isEmpty()) item(key = "_none") { Quiet("Nothing on the board. Tasks you ask for show here.") }
            if (board.doneCount > 0 || board.done.isNotEmpty()) {
                item(key = "_done") { FoldRow("Done · ${board.doneCount}", DONE) }
                if (Dibs.open[DONE] == true) {
                    items(board.done, key = { "d-${it.key}" }) { card -> BoardCardRow(card, view, armed, Modifier.animateItem()) }
                }
            }
        } else {
            for (g in list.groups) {
                item(key = "g-${g.project}") { Section(g.project) }
                items(g.tasks, key = { "y${it.id}" }) { t -> TaskRow(t, view, now, Modifier.animateItem()) }
            }
            if (list.open == 0) item(key = "_none") { Quiet("Nothing open. Tasks you ask for wait here until you tick them off.") }
        }

        item(key = "_own") { FoldRow("dibs's own work · $own", OWN_WORK) }
        if (Dibs.open[OWN_WORK] == true) workItems(view, now, armed, ownOnly = true)

        if (board == null && list.ticked.isNotEmpty()) {
            item(key = "_ticked") { FoldRow("Ticked · ${list.ticked.size}", TICKED) }
            if (Dibs.open[TICKED] == true) {
                items(list.ticked, key = { "k${it.id}" }) { t -> TickedRow(t, Modifier.animateItem()) }
            }
        }
    }
}

/** A folded section's row: its name and count, a chevron; a tap opens or folds it. */
@Composable
private fun FoldRow(label: String, key: String) {
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

/**
 * A task: its plain title (bold while done and unread), its state in words, one line. A tap opens
 * its story screen; a long press offers Tick off (once finished) and Open on laptop.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(t: YourTask, view: DibsView, now: Long, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val unread = Dibs.unread(t)
    Box(modifier) {
        Column(
            Modifier.card().clip(MaterialTheme.shapes.medium)
                .combinedClickable(
                    onClick = { Dibs.open(Page.Brief(t.id, false)) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                t.label,
                style = AppType.body.copy(fontWeight = if (unread) FontWeight.W600 else FontWeight.W400),
                color = Palette.Text,
            )
            TaskState(t, now)
            if (t.line.isNotBlank()) {
                Text(t.line, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            }
        }
        TaskMenu(menu, t, view) { menu = false }
    }
}

/**
 * A card of dibs's board, drawn as sent: "#31" (a task's number), its title whole, its state in
 * dibs's words (a working one with the busy dot), its tags, a "Full story" mark once one is ready or
 * being written, and what it's doing. A tap opens a task's story screen (an idea has none: its menu); a long
 * press, the menu of what dibs lets it do ([BoardCard.actions]).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun BoardCardRow(c: BoardCard, view: DibsView, armed: Armed, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    // A task opens its story screen (the recap's account, then the full story), also when it isn't one of the user's;
    // an idea has none: its tap opens the menu.
    val tid = c.task
    val t = tid?.let(view::task)
    // The primary action is the button on the card's face, so the menu lists the rest.
    val primary = c.primary?.takeIf { it in ACT_WORDS && !drop }
    val acts = c.actions.filter { it in ACT_WORDS && it != primary && (it != "story" || tid != null) }
    // A tap on this card's button or menu shows at once, until dibs's next view.
    val busyTap = rememberTapState("card:${c.key}") == TapState.BUSY
    // A drop dibs is still reading or asks about: no number, no button; a tap opens its idea page.
    val drop = c.key.startsWith("drop:")
    val dropIdea = if (drop) c.open?.removePrefix("idea:")?.toIntOrNull() else null
    val openMenu = {
        if (acts.isNotEmpty() && !busyTap) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            menu = true
        }
    }
    Box(modifier) {
        Column(
            Modifier.card().clip(MaterialTheme.shapes.medium)
                .combinedClickable(onClick = {
                        when {
                            tid != null -> Dibs.open(Page.Brief(tid, false))
                            dropIdea != null -> Dibs.openRef(RefKind.IDEA, dropIdea)
                            else -> openMenu()
                        }
                    }, onLongClick = openMenu)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                c.n?.let { Text("#$it", style = AppType.mono, color = Palette.Muted) }
                Text(
                    c.title,
                    Modifier.weight(1f),
                    style = AppType.body.copy(fontWeight = if (t != null && Dibs.unread(t)) FontWeight.W600 else FontWeight.W400),
                    color = if (drop) Palette.Muted else Palette.Text,
                )
                if (primary != null) {
                    TextButton(
                        onClick = { Dibs.taskAct(c.key, primary) },
                        enabled = !busyTap,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        if (busyTap) Spinner(Modifier.padding(end = 6.dp))
                        Text(ACT_WORDS.getValue(primary), style = AppType.body, color = Palette.Accent)
                    }
                } else if (busyTap) {
                    Spinner(Modifier.padding(end = 10.dp), size = 16.dp)
                }
            }
            if (c.stateWords.isNotBlank()) {
                if (drop && c.stateWords.startsWith("dibs asks")) Text(c.stateWords, style = AppType.small, color = Palette.Accent) else CardState(c.stateWords, busy = t?.busy == true)
            }
            val story = c.story?.takeIf { it.state == "ready" || it.writing }
            if (c.tags.isNotEmpty() || story != null) {
                FlowRow(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (tag in c.tags) Chip(tag)
                    if (story != null) Chip(if (story.writing) "Full story · being written" else "Full story", accent = true)
                }
            }
            if (c.now.isNotBlank()) Text(c.now, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            c.progress?.let { CardProgress(it) }
        }
        CardMenu(menu, c, acts, armed) { menu = false }
    }
}

/** The box at the top of Backlog: type or say a rough idea; dibs reads it and files it as a parked task (or asks). */
@Composable
private fun BacklogBox(placeholder: String) {
    val context = LocalContext.current
    val rec by IdeaRecording.state.collectAsStateWithLifecycle()
    val record = rememberRecord(drop = BACKLOG_DROP)
    val recording = rec.takeIf { it is RecorderState.Recording || it is RecorderState.Starting }
    val mine = recording != null && (recording as? RecorderState.Recording)?.drop.let { it == null || it == BACKLOG_DROP }
    Column(Modifier.padding(bottom = Space.XS), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        if (recording != null && mine) {
            RecordPanel(recording, "Recording for the Backlog")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.S), verticalAlignment = Alignment.Top) {
                IdeaTextBox(BACKLOG_FIELD, placeholder, Modifier.weight(1f)) { t -> later { Drafts.typed(context, t, drop = BACKLOG_DROP) } }
                RoundAction(R.drawable.lucide_mic, "Say it", accent = false) { record(null) }
            }
            FinishedLine(rec)
        }
    }
}

internal const val BACKLOG_DROP = "backlog"
internal const val BACKLOG_FIELD = "backlog:new"

/** Something dropped in the Backlog box that dibs's view doesn't list yet. */
@Composable
private fun PendingDrop(d: Draft, modifier: Modifier) {
    Column(modifier.card().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(draftTitle(d), style = AppType.body, color = Palette.Muted)
        Text(if (d.failed || d.why != null) draftState(d) else "Sending…", style = AppType.small, color = if (d.failed || d.why != null) Palette.Warning else Palette.Muted)
    }
}

/**
 * A running task's stage and rough time left, counted down on the phone's clock, over a thin bar: solid
 * up to its latest finish, light up to its earliest (the unsure stretch).
 */
@Composable
private fun CardProgress(p: Progress) {
    val now by rememberNow()
    val line = progressLine(p, now)
    val fill = barFill(p, now)
    if (line.isNotEmpty()) Text(line, style = AppType.small, color = Palette.Muted)
    if (fill != null) {
        Box(
            Modifier.padding(top = 2.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.SurfaceHigh)
                .semantics { contentDescription = line },
        ) {
            Box(Modifier.fillMaxWidth(fill.maybe).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Palette.AccentDim))
            Box(Modifier.fillMaxWidth(fill.sure).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Palette.Accent))
        }
    }
}

/** dibs's state words: a working one with the busy dot (filled while its session works), the rest muted. */
@Composable
internal fun CardState(words: String, busy: Boolean) {
    if (words.startsWith("working", ignoreCase = true)) StateWord(words, busy = busy) else Text(words, style = AppType.small, color = Palette.Muted)
}

/**
 * What dibs lets a card do, in plain words, nothing else. Stop and Delete ask again ("Stop it?", Delete
 * with dibs's words for what it loses) and act on the second tap within 4 s; Full story opens the story's page.
 */
@Composable
private fun CardMenu(expanded: Boolean, c: BoardCard, acts: List<String>, armed: Armed, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        for (a in acts) {
            val words = ACT_WORDS.getValue(a)
            val k = "act:${c.key}:$a"
            val confirm = a == "stop" || a == "delete"
            val asking = confirm && armed.key == k
            DropdownMenuItem(
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (asking) "$words it?" else words, color = if (confirm) Palette.Danger else Palette.Text)
                        if (asking && a == "delete") c.deleteText?.let { Text(it, style = AppType.small, color = Palette.Muted) }
                    }
                },
                onClick = {
                    when {
                        a == "story" -> {
                            onDismiss()
                            c.task?.let { Dibs.open(Page.Story(it)) }
                        }
                        confirm -> armed.press(k) {
                            onDismiss()
                            Dibs.taskAct(c.key, a, confirm = true)
                        }
                        else -> {
                            onDismiss()
                            Dibs.taskAct(c.key, a)
                        }
                    }
                },
            )
        }
    }
}

/** Tick off (finished only) and Open on laptop. */
@Composable
internal fun TaskMenu(expanded: Boolean, t: YourTask, view: DibsView, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (t.finishedState) {
            DropdownMenuItem(
                text = { Text("Tick off") },
                leadingIcon = { Icon(painterResource(R.drawable.lucide_circle_check), null, Modifier.size(18.dp)) },
                onClick = {
                    onDismiss()
                    Dibs.tick(t)
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Open on laptop") },
            leadingIcon = { Icon(painterResource(R.drawable.lucide_laptop), null, Modifier.size(18.dp)) },
            onClick = {
                onDismiss()
                Dibs.openOnLaptop(view, t.id)
            },
        )
    }
}

/**
 * Its state in words: "Needs you" in the accent with a dot, a running one with the busy dot
 * (filled while its session works), the rest muted. The words say it; colour only helps.
 */
@Composable
internal fun TaskState(t: YourTask, now: Long) {
    val ctx = LocalContext.current
    val words = remember(t, now / 60) {
        taskWords(t, now, ZoneId.systemDefault()) { android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(it * 1000)) }
    }
    // Not opened since it finished, stopped or its agent answered: one of what the badge counts.
    if (t.state != "needs" && Dibs.unread(t)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(7.dp).background(Palette.Accent, Pill))
            Text("$words · new", style = AppType.small, color = Palette.Accent)
        }
        return
    }
    when (t.state) {
        "needs" -> Eyebrow(words, dot = true, color = Palette.Accent)
        "working", "paused" -> StateWord(words, busy = t.busy)
        "failed" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(painterResource(R.drawable.lucide_triangle_alert), null, Modifier.size(13.dp), tint = Palette.Warning)
            Text(words, style = AppType.small, color = Palette.Warning)
        }
        else -> Text(words, style = AppType.small, color = Palette.Muted)
    }
}

/** A ticked task: its title, when it was ticked, and Untick. */
@Composable
private fun TickedRow(t: YourTask, modifier: Modifier) {
    val zone = ZoneId.systemDefault()
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { Dibs.open(Page.Brief(t.id, false)) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.lucide_check), null, Modifier.padding(top = 2.dp).size(16.dp), tint = Palette.Success)
        Column(Modifier.weight(1f)) {
            Text(t.label, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
            // "ticked today", "ticked Sun 4 Oct"
            val day = t.ticked?.let { dayOf(it, zone, LocalDate.now(zone)) }?.let { if (it == "Today" || it == "Yesterday") it.lowercase() else it }
            Text(listOfNotNull(t.project, day?.let { "ticked $it" }).joinToString(" · "), style = AppType.small, color = Palette.Muted)
        }
        ActButton("Untick", "plain") { Dibs.untick(t) }
    }
}
