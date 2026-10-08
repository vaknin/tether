package com.kivan.tether.dibs.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.BoardCard
import com.kivan.tether.dibs.Brief
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Link
import com.kivan.tether.dibs.Page
import com.kivan.tether.dibs.Question
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.YourTask
import com.kivan.tether.dibs.dayWords
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// The merged story screen (the morning recap, design C): one task's account, opened from the Recap list or a
// Tasks card. The four parts of what happened, the actions (the Waiting card's buttons when it has an open
// question, Ask dibs, Open the task), then the full story underneath. From Recap, a swipe or the arrows move to the
// next or previous brief in its place (the page is replaced, so back still returns to the list).

/** How far a sideways drag goes to the next or previous brief. */
private val SWIPE = 60.dp

@Composable
internal fun BriefPage(page: Page.Brief, view: DibsView) {
    val id = page.task
    val brief = view.brief(id)
    val t = view.task(id)
    val card = view.card(id)
    val title = brief?.title?.takeIf { it.isNotBlank() } ?: view.labelOf(id) ?: return Gone()
    val now by rememberNow()
    val link by Dibs.host.link.collectAsStateWithLifecycle()

    // Shown: it's read. dibs is told once; the dot clears here at once.
    LaunchedEffect(id) {
        if (brief != null && Dibs.briefUnread(brief)) Dibs.briefSeen(id)
        if (t != null && Dibs.unread(t)) Dibs.seenTask(t)
    }

    val items = view.recapItems
    val at = if (page.fromRecap) items.indexOfFirst { it.task == id } else -1
    val go = { to: Int ->
        val b = items.getOrNull(to)
        // Replaces this page: back returns to the list, not to the brief just left.
        if (b != null && Dibs.pages.lastOrNull() == page) Dibs.pages[Dibs.pages.lastIndex] = Page.Brief(b.task, true)
    }
    val prev = at > 0
    val next = at in 0 until items.size - 1
    val st = rememberStory(id, view.storyOf(id), autoAsk = false)
    val threshold = with(LocalDensity.current) { SWIPE.toPx() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = Space.XS, end = Space.XS, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Dibs.back() }) {
                Icon(painterResource(R.drawable.lucide_arrow_left), "Back", Modifier.size(22.dp), tint = Palette.Text)
            }
            Text(
                if (at >= 0) "${at + 1} of ${items.size}" else "Story",
                Modifier.weight(1f).padding(start = 2.dp),
                style = AppType.mono,
                color = Palette.Muted,
            )
            if (at >= 0) {
                IconButton(onClick = { go(at - 1) }, enabled = prev) {
                    Icon(painterResource(R.drawable.lucide_chevron_left), "Previous", Modifier.size(22.dp), tint = if (prev) Palette.Text else Palette.Outline)
                }
                IconButton(onClick = { go(at + 1) }, enabled = next) {
                    Icon(painterResource(R.drawable.lucide_chevron_right), "Next", Modifier.size(22.dp), tint = if (next) Palette.Text else Palette.Outline)
                }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(at, items.size) {
                if (at < 0) return@pointerInput
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        if (dx < -threshold && next) go(at + 1) else if (dx > threshold && prev) go(at - 1)
                    },
                    onDragCancel = { dx = 0f },
                    onHorizontalDrag = { _, d -> dx += d },
                )
            },
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Space.L, end = Space.L, top = Space.XS, bottom = Space.XXL),
                verticalArrangement = Arrangement.spacedBy(Space.M),
            ) {
                item(key = "_head") { Head(id, title, brief, t, card, now) }
                if (brief != null) {
                    parts(brief)
                } else {
                    item(key = "_nothing") { Text("Nothing written up yet", style = AppType.body, color = Palette.Muted) }
                    t?.report?.takeIf { it.isNotBlank() }?.let { r ->
                        item(key = "_report") { Part("What it did", r) }
                    }
                }
                item(key = "_acts") { Actions(id, title, brief, view, now) }
                fullStoryItems(st, id, title, view, link == Link.CONNECTED)
            }
        }
    }
}

/** The tag, the big title and the meta line; a task with no brief shows its number and state words instead. */
@Composable
private fun Head(id: Long, title: String, brief: Brief?, t: YourTask?, card: BoardCard?, now: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        if (brief != null && brief.tag.isNotEmpty()) TierChip(brief)
        Text(title, style = AppType.title, color = Palette.Text)
        if (brief != null) {
            Meta(id, brief)
        } else {
            Text("#$id", style = AppType.mono, color = Palette.Muted)
            if (t != null) TaskState(t, now) else if (card != null && card.stateWords.isNotBlank()) CardState(card.stateWords, busy = false)
        }
    }
}

/** "#210 · dibs · Plan · ready 06:40"; unread and from before today, "since yesterday" in amber. */
@Composable
private fun Meta(id: Long, b: Brief) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val day = b.ts.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
    val whenWords = if (b.ts > 0) whenWords(b.ts) else null
    val kind = b.kind?.replaceFirstChar { it.uppercase() }
    val line = listOfNotNull("#$id", b.project, kind, whenWords).joinToString(" · ")
    val since = if (Dibs.briefUnread(b) && day != null && day.isBefore(today)) {
        "since " + dayWords(day, today).let { if (it == "Yesterday") it.lowercase() else it }
    } else {
        null
    }
    Text(
        buildAnnotatedString {
            append(line)
            if (since != null) {
                append(" · ")
                withStyle(SpanStyle(color = Palette.Warning)) { append(since) }
            }
        },
        style = AppType.small,
        color = Palette.Muted,
    )
}

/** Each part under its eyebrow, skipping the empty ones. Still being written: what/lede and a muted line saying so. */
private fun LazyListScope.parts(b: Brief) {
    b.answer?.let { a -> item(key = "_answer") { Part("The answer", a) } }
    val what = b.what.ifBlank { if (b.writing) b.lede else "" }
    if (b.writing) item(key = "_writing") { Text("The full write-up is being written", style = AppType.small, color = Palette.Muted) }
    if (what.isNotBlank()) item(key = "_what") { Part("What happened", what) }
    if (b.why.isNotBlank()) item(key = "_why") { Part("Why", b.why) }
    if (b.means.isNotBlank()) item(key = "_means") { Part("What it means for you", b.means) }
    if (b.next.isNotBlank()) item(key = "_next") { Part("What's next", b.next) }
    if (!b.hasParts && !b.writing && b.answer == null && b.lede.isNotBlank()) item(key = "_lede") { Text(b.lede, style = AppType.body, color = Palette.Text) }
}

@Composable
private fun Part(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        Eyebrow(label)
        Text(text, style = AppType.body, color = Palette.Text)
    }
}

/**
 * The open question the brief names (`card`) with its buttons as Waiting draws them (answering here closes it there
 * too), then Ask dibs about this and Open the task (a task of the user's).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(id: Long, title: String, brief: Brief?, view: DibsView, now: Long) {
    val q: Question? = brief?.card?.let { c -> view.questions.firstOrNull { it.id == c && "q$c" !in Dibs.answered } }
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        if (q != null) {
            if (q.kind == "root" || q.kind == "rootkey") {
                QuestionCard(q, now, Modifier)
            } else {
                Column(Modifier.card().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(q.title, style = AppType.body.copy(fontWeight = Bold), color = Palette.Text)
                    if (q.why.isNotBlank()) Text(q.why, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                    QuestionControls(q.id, "q/${q.id}", q.actions, q.reply, q.hint, Modifier.padding(top = 4.dp))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S), verticalArrangement = Arrangement.spacedBy(Space.S), itemVerticalAlignment = Alignment.CenterVertically) {
            val ask = view.askFor(id)
            if (ask != null) {
                ActButton(ask.label, "primary", icon = R.drawable.lucide_message_circle_question) { Dibs.askAbout(view, ask.about, title) }
            } else {
                ActButton("Ask dibs about this", "primary") { Dibs.chatAboutStory(id, title, "ask") }
            }
            if (view.task(id) != null) ActButton("Open the task", "") { Dibs.open(Page.Task(id)) }
        }
    }
}
