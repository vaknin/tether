package com.kivan.tether.dibs.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Page
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.YourTask
import com.kivan.tether.dibs.dayOf
import com.kivan.tether.dibs.forYouWords
import com.kivan.tether.dibs.laptopWords
import com.kivan.tether.dibs.taskWords
import com.kivan.tether.dibs.tasksList
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Pill
import com.kivan.tether.dibs.ui.theme.Space
import java.time.LocalDate
import java.time.ZoneId

// The Tasks tab (docs/DIBS-APP.md, "Your tasks"): every task the user asked for, grouped by
// project, from its start until they tick it off; a tap opens its page. dibs's own work and the
// ticked ones fold away at the bottom.

private const val OWN_WORK = "fold:own-work"
private const val TICKED = "fold:ticked"

@Composable
internal fun TasksTab(view: DibsView) {
    val yours = view.yours.orEmpty()
    val now by rememberNow()
    val armed = rememberArmed()
    val answered = Dibs.answered.toMap()
    val list = remember(yours, answered) { tasksList(yours) { Dibs.ticked(it) } }
    val own = view.tasks.count { it.background } + view.sessions.size + view.ships.size
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.L, end = Space.L, bottom = Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        // The laptop's state, where the running work is (a dibs that sends it).
        view.state.laptop?.let(::laptopWords)?.let { words ->
            item(key = "_laptop") {
                Column(Modifier.padding(top = Space.M), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Eyebrow("Laptop")
                    Text(words, style = AppType.small, color = Palette.Muted)
                }
            }
        }
        // What the tab's badge counts, said plainly (nothing when nothing wants you).
        val open = list.groups.flatMap { it.tasks }
        val read = open.count { it.state != "needs" && Dibs.unread(it) }
        val asking = open.count { it.state == "needs" }
        forYouWords(read, asking)?.let { words ->
            item(key = "_for_you") { Eyebrow(words, Modifier.padding(top = Space.M, bottom = Space.XS), dot = true, color = Palette.Accent) }
        }
        for (g in list.groups) {
            item(key = "g-${g.project}") { Section(g.project) }
            items(g.tasks, key = { "y${it.id}" }) { t -> TaskRow(t, view, now, Modifier.animateItem()) }
        }
        if (list.open == 0) item(key = "_none") { Quiet("Nothing open. Tasks you ask for wait here until you tick them off.") }

        item(key = "_own") { FoldRow("dibs's own work · $own", OWN_WORK) }
        if (Dibs.open[OWN_WORK] == true) workItems(view, now, armed, ownOnly = true)

        if (list.ticked.isNotEmpty()) {
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
 * its page; a long press offers Tick off (once finished) and Open on laptop.
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
                    onClick = { Dibs.open(Page.Task(t.id)) },
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
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { Dibs.open(Page.Task(t.id)) }.padding(vertical = 4.dp),
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
