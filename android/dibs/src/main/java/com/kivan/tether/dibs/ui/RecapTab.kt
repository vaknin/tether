package com.kivan.tether.dibs.ui

import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kivan.tether.dibs.Away
import com.kivan.tether.dibs.Brief
import com.kivan.tether.dibs.RecapSmall
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.FeedItem
import com.kivan.tether.dibs.Page
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.awayMinutes
import com.kivan.tether.dibs.dayWords
import com.kivan.tether.dibs.duration
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import org.json.JSONObject

// The Recap tab: "while you were away" first, then what was decided for you (Undo, nothing to
// clear: the user, 2026-10-06, word 127, wants Waiting for what needs them only), then the day's
// feed, newest first, grouped by day: one row per piece of work (dibs folds a task's lines into
// one), a tap for the whole story.

/** Decisions shown before "Show all". */
private const val DECIDED_SHOWN = 3
private const val DECIDED_ALL = "recap:decided"

@Composable
internal fun RecapTab(view: DibsView) {
    // A dibs that sends the unread list: the morning recap. An older one: the away card and the feed.
    if (view.recapList) UnreadRecap(view) else FeedRecap(view)
}

private const val SMALL_FIXES = "recap:small"

/**
 * The morning recap (design C): the unread count and who needs you, one row per brief (a dot until it's read, its tag,
 * its time, its headline and a line), then "The rest": the small fixes folded, and what was decided for you. A tap
 * opens the brief; the ones after it are a swipe away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnreadRecap(view: DibsView) {
    val now by rememberNow()
    val armed = rememberArmed()
    val items = view.recapItems
    val unread = if (items.isEmpty()) 0 else items.count { Dibs.briefUnread(it) }
    val small = view.recapSmall?.takeIf { it.n > 0 }
    val sub = listOfNotNull(
        view.recapNeeds.takeIf { it > 0 && unread > 0 }?.let { "$it need you" },
        small?.let { "${it.n} small fixes folded" },
    ).joinToString(" · ")
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.L, end = Space.L, bottom = Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        item(key = "_hero") {
            Column {
                Hero("Unread", "$unread")
                if (sub.isNotEmpty() || unread > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                        Text(sub, Modifier.weight(1f), style = AppType.body, color = Palette.Muted)
                        if (unread > 0) ActButton("Mark all read", "plain") { Dibs.recapSeenAll() }
                    }
                }
            }
        }
        if (items.isEmpty()) item(key = "_caught_up") { Quiet("You're caught up") }
        items(items, key = { "b${it.task}" }) { b -> BriefRow(b, Modifier.animateItem()) }
        if (small != null || view.recapDecided.isNotEmpty()) item(key = "_rest") { Section("The rest") }
        if (small != null) item(key = "_small") { SmallFixes(small) }
        decidedItems(view, now, armed)
    }
}

/** A brief: its unread dot (or room for one), tag, time, headline and line. Read ones are dimmed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BriefRow(b: Brief, modifier: Modifier) {
    val unread = Dibs.briefUnread(b)
    Row(
        modifier.card().alpha(if (unread) 1f else 0.6f).clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClickLabel = "Open") { Dibs.open(Page.Brief(b.task, true)) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (unread) Box(Modifier.padding(top = 6.dp).size(8.dp).background(Palette.Accent, CircleShape)) else Box(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TierChip(b)
                if (b.ts > 0) Text(briefTime(b.ts), style = AppType.mono, color = Palette.Muted)
            }
            Text(b.title, style = AppType.heading, color = Palette.Text)
            if (b.lede.isNotBlank()) Text(b.lede, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }
    }
}

/** needs and urgent: amber; asked and talked: the accent; an answer: blue. */
@Composable
internal fun TierChip(b: Brief, modifier: Modifier = Modifier) {
    when {
        b.tag == "Answers your question" -> Chip(b.tag, modifier, success = true)
        b.needsYou -> Chip(b.tag, modifier, warn = true)
        else -> Chip(b.tag, modifier, accent = true)
    }
}

/** Its time today, else "Yesterday" or the day. */
@Composable
private fun briefTime(ts: Long): String {
    val ctx = LocalContext.current
    return remember(ts) {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochSecond(ts).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        if (day == today) DateFormat.getTimeFormat(ctx).format(Date(ts * 1000)) else dayWords(day, today)
    }
}

/** "Small fixes · 47" folded, with each project's count under it; open: the groups with their lines ("n more" when cut). */
@Composable
private fun SmallFixes(small: RecapSmall) {
    val open = Dibs.open[SMALL_FIXES] == true
    Column(Modifier.card().clip(MaterialTheme.shapes.medium).clickable { Dibs.toggle(SMALL_FIXES) }.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow("Small fixes · ${small.n}")
                if (!open) Text(small.groups.joinToString(" · ") { "${it.project} ${it.n}" }, style = AppType.small, color = Palette.Muted)
            }
            Icon(painterResource(if (open) R.drawable.lucide_chevron_up else R.drawable.lucide_chevron_down), if (open) "Fold" else "Open", Modifier.size(16.dp), tint = Palette.Muted)
        }
        if (open) {
            for (g in small.groups) {
                Eyebrow("${g.project} · ${g.n}", Modifier.padding(top = Space.M, bottom = 2.dp))
                for (line in g.lines) Text(line, Modifier.padding(vertical = 2.dp), style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
                if (g.n > g.lines.size) Text("${g.n - g.lines.size} more", Modifier.padding(vertical = 2.dp), style = AppType.small, color = Palette.Accent)
            }
        }
    }
}

/** What was decided for the user lately, three of them before "Show all" (Undo on each). */
private fun LazyListScope.decidedItems(view: DibsView, now: Long, armed: Armed) {
    if (view.recapDecided.isEmpty()) return
    val all = Dibs.open[DECIDED_ALL] == true
    item(key = "_decided") { Section("Decided for you") }
    items(if (all) view.recapDecided else view.recapDecided.take(DECIDED_SHOWN), key = { "d${it.id}" }) { d -> DecisionRow(d, now, armed, Modifier) }
    if (view.recapDecided.size > DECIDED_SHOWN) {
        item(key = "_decided_more") {
            Text(
                if (all) "Show fewer" else "Show all ${view.recapDecided.size}",
                Modifier.clip(MaterialTheme.shapes.small).clickable { Dibs.toggle(DECIDED_ALL) }.padding(vertical = 6.dp),
                style = AppType.label,
                color = Palette.Accent,
            )
        }
    }
}

/** The old tab, for a dibs that sends no unread list. */
@Composable
private fun FeedRecap(view: DibsView) {
    val now by rememberNow()
    val armed = rememberArmed()
    val away = view.away?.takeIf { "w${it.id}" !in Dibs.answered }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val days = remember(view.feed) { view.feed.groupBy { Instant.ofEpochSecond(it.ts).atZone(zone).toLocalDate() } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.L, end = Space.L, bottom = Space.L),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (away != null) {
            item(key = "_away") { AwayCard(away) }
        } else {
            item(key = "_hero") { Hero("Done today", "${days[today]?.count { it.kind != "update" } ?: 0}") }
        }
        decidedItems(view, now, armed)
        for ((day, items) in days) {
            item(key = "day-$day") { Section(dayWords(day, today)) }
            itemsIndexed(items, key = { i, f -> f.id?.let { "f-$it" } ?: "f-$day-$i" }) { _, f -> FeedRow(f, f.task?.takeIf { view.task(it) != null }) }
        }
        if (view.feed.isEmpty()) item(key = "_empty") { Quiet("Nothing yet. What dibs and its sessions get done shows here.") }
    }
}

/** How long you were away, what happened meanwhile, and Got it (its dot on the tab goes then). */
@Composable
private fun AwayCard(a: Away) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Hero("While you were away", awayMinutes(a)?.let(::duration) ?: "", style = AppType.heroSmall)
        Column(Modifier.card().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            for ((i, line) in a.lines.withIndex()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.padding(top = 8.dp).size(5.dp).background(Palette.Muted, CircleShape))
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
                }
            }
            ActButton("Got it", "primary", Modifier.padding(top = 2.dp)) { Dibs.answer("w${a.id}", "Got it", "w${a.id}") }
        }
    }
}

/**
 * One piece of work: its time, a mark by kind, what changed for you, why (what you asked), and
 * whose it was. A tap opens it whole: the task's report, its other lines, all of what you asked;
 * one of the user's own tasks ([task]) opens its page instead. A long press copies all of it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedRow(f: FeedItem, task: Long?) {
    val key = f.key
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val open = Dibs.open[key] == true
    val opens = f.opens
    val (icon, tint) = kindMark(f.kind)
    val longPress = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        copy(ctx, f.full())
    }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            // A row with nothing behind it only copies: no ripple for a tap that does nothing.
            .then(
                if (task != null) Modifier.combinedClickable(onClick = { Dibs.open(Page.Task(task)) }, onLongClick = longPress)
                else if (opens || open) Modifier.combinedClickable(onClick = { Dibs.toggle(key) }, onLongClick = longPress)
                else Modifier.pointerInput(f) { detectTapGestures(onLongPress = { longPress() }) },
            )
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            remember(f.ts) { DateFormat.getTimeFormat(ctx).format(Date(f.ts * 1000)) },
            Modifier.width(44.dp).padding(top = 2.dp),
            style = AppType.mono,
            color = Palette.Muted,
        )
        Icon(painterResource(icon), f.kind, Modifier.padding(top = 2.dp).size(16.dp), tint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                f.text,
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Text,
            )
            if (f.why != null) {
                Text(
                    "You asked: ${f.why}",
                    style = AppType.small,
                    color = Palette.Muted,
                )
            }
            // Open: what was done first (the report, the other lines), then all of what you asked.
            if (open && f.report != null) {
                Eyebrow("Report", Modifier.padding(top = Space.S))
                Text(f.report, style = AppType.small, color = Palette.Text)
            }
            if (open && f.more.isNotEmpty()) {
                Eyebrow("Along the way", Modifier.padding(top = Space.S))
                for (line in f.more) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.padding(top = 8.dp).size(4.dp).background(Palette.Muted, CircleShape))
                        Text(line, style = AppType.small, color = Palette.Text)
                    }
                }
            }
            if (open && f.asked != null) {
                Eyebrow("What you asked", Modifier.padding(top = Space.S))
                Text(f.asked, style = AppType.small, color = Palette.Muted)
            }
            if (open && f.reopen != null) {
                val sent = "reopen:${f.reopen}" in Dibs.answered
                ActButton(if (sent) "Opening on the laptop" else "Open on laptop", "outline", Modifier.padding(top = Space.S), enabled = !sent) {
                    Dibs.host.act("reopen", JSONObject().put("reopen", f.reopen))
                    Dibs.answered["reopen:${f.reopen}"] = "sent"
                }
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val who = listOfNotNull(f.who.ifBlank { null }, f.repo?.takeIf { it != f.who }).joinToString(" · ")
                Text(who, Modifier.weight(1f, fill = false), style = AppType.small, color = Palette.Muted)
                if (task != null) {
                    Text("Open", style = AppType.small, color = Palette.Accent)
                    Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(14.dp), tint = Palette.Accent)
                } else if (opens || open) {
                    Text(if (open) "Less" else "More", style = AppType.small, color = Palette.Accent)
                    Icon(
                        painterResource(if (open) R.drawable.lucide_chevron_up else R.drawable.lucide_chevron_down),
                        null,
                        Modifier.size(14.dp),
                        tint = Palette.Accent,
                    )
                }
            }
        }
    }
}

/** did: a blue check (success is blue); done: sparkles; closed, updates and the rest: muted marks. */
private fun kindMark(kind: String): Pair<Int, Color> = when (kind) {
    "did" -> R.drawable.lucide_check to Palette.Success
    "done" -> R.drawable.lucide_sparkles to Palette.Accent
    "close", "closed" -> R.drawable.lucide_circle_stop to Palette.Muted
    "update" -> R.drawable.lucide_arrow_down to Palette.Muted
    "answered", "decided" -> R.drawable.lucide_message_square_reply to Palette.Muted
    else -> R.drawable.lucide_history to Palette.Muted
}
