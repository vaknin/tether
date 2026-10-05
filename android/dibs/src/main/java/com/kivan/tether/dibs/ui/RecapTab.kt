package com.kivan.tether.dibs.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kivan.tether.dibs.Away
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.FeedItem
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.awayMinutes
import com.kivan.tether.dibs.dayWords
import com.kivan.tether.dibs.duration
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

// The Recap tab: "while you were away" first, then the day's feed (what got done, updates
// installed, sessions closed), newest first, grouped by day.

@Composable
internal fun RecapTab(view: DibsView) {
    val away = view.away?.takeIf { "w${it.id}" !in Dibs.answered }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val days = remember(view.feed) { view.feed.groupBy { Instant.ofEpochSecond(it.ts).atZone(zone).toLocalDate() } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.L, end = Space.L, bottom = Space.L),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (away != null) {
            item(key = "_away") { AwayCard(away) }
        } else {
            item(key = "_hero") { Hero("Done today", "${days[today]?.size ?: 0}") }
        }
        for ((day, items) in days) {
            item(key = "day-$day") { Section(dayWords(day, today)) }
            itemsIndexed(items, key = { i, _ -> "f-$day-$i" }) { _, f -> FeedRow(f) }
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
            for (line in a.lines) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.padding(top = 8.dp).size(5.dp).background(Palette.Muted, CircleShape))
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
                }
            }
            ActButton("Got it", "primary", Modifier.padding(top = 2.dp)) { Dibs.answer("w${a.id}", "Got it", "w${a.id}") }
        }
    }
}

/** One thing that happened: its time, a mark by kind, the line, and who did it. */
@Composable
private fun FeedRow(f: FeedItem) {
    val ctx = LocalContext.current
    val (icon, tint) = kindMark(f.kind)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            remember(f.ts) { DateFormat.getTimeFormat(ctx).format(Date(f.ts * 1000)) },
            Modifier.width(44.dp).padding(top = 2.dp),
            style = AppType.mono,
            color = Palette.Muted,
        )
        Icon(painterResource(icon), f.kind, Modifier.padding(top = 2.dp).size(16.dp), tint = tint)
        Column(Modifier.weight(1f)) {
            Text(f.text, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
            if (f.who.isNotBlank()) Text(f.who, style = AppType.small, color = Palette.Muted)
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
