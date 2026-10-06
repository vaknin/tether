package com.kivan.tether.dibs.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Link
import com.kivan.tether.dibs.MdBlock
import com.kivan.tether.dibs.Page
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.Story
import com.kivan.tether.dibs.YourTask
import com.kivan.tether.dibs.markdownBlocks
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// A task's full story (docs/DIBS-APP.md, "Full story"): a long readable account of what was asked,
// what it tried, what failed and why, the choices, what's left. dibs writes it only when asked
// (opening this screen asks, the first time; it takes a few minutes) and sends it as
// `story-<task>-<hash>.md` on its channel. From here the user talks to dibs about it: the chat
// opens with the story named (`about`), or one paragraph of it (a long press).

/** How long to wait for the file dibs sends once a story is written, before asking for it. */
private const val STORY_NUDGE_MS = 15_000L

/** The story reads roomier than a report: a large title, clear space above each part, airy lines. */
private val StoryLook = ReadLook(
    h1 = AppType.title, h1Top = Space.S,
    h2 = AppType.heading.copy(fontSize = 19.sp, lineHeight = 25.sp), h2Top = Space.XL,
    h3 = AppType.body.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.W600), h3Top = Space.M,
    body = bodyStyle.copy(fontSize = 16.sp, lineHeight = 26.sp),
    gap = Space.M,
)

@Composable
internal fun StoryScreen(id: Long, view: DibsView) {
    val t = view.task(id) ?: return Gone()
    val s = t.story
    // Opening asks for it only when it was never asked for (dibs writes one) or one is ready to get.
    // While one is written, or after a failure, it never asks: each ask could start a paid write.
    val fetch = rememberFetch(id, "story", since = s?.ts ?: 0, ask = s == null || s.state == "ready")
    // Asked to write it again here: it shows as being written until dibs's view moves on.
    var again by remember(id, s?.state, s?.since) { mutableStateOf(false) }
    val writeAgain = {
        again = true
        Dibs.story(id, again = true)
    }
    val writing = s?.writing == true || again

    // A newer one was written than the file here: dibs sends it when done; ask once if it doesn't come.
    var askedFor by remember(id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(s?.ts, fetch.file, fetch.loaded) {
        if (s?.state != "ready") return@LaunchedEffect
        val ts = s.ts ?: return@LaunchedEffect
        val f = fetch.file
        if (!fetch.loaded || fetch.waiting || ts == askedFor || (f != null && f.lastModified() / 1000 >= ts)) return@LaunchedEffect
        delay(STORY_NUDGE_MS)
        askedFor = ts
        fetch.refresh()
    }

    var bad by remember { mutableStateOf(false) }
    // The last one read stays on screen while a newer one is read.
    val blocks by produceState<List<MdBlock>?>(null, fetch.file) {
        val f = fetch.file ?: return@produceState
        val read = withContext(Dispatchers.IO) { runCatching { markdownBlocks(readFetched(f)) }.getOrNull() }
        bad = read == null
        if (read != null) value = read
    }
    val link by Dibs.host.link.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        PageBar("Full story", t.label)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val b = blocks
            when {
                b != null -> StoryText(b, t, s, writing, writeAgain)
                bad && !fetch.waiting -> Calm("It couldn't be read", null) {
                    ActButton("Get it again", "primary", Modifier.padding(top = Space.L)) { fetch.refresh() }
                }
                writing -> Writing(s)
                s?.state == "failed" -> Calm("It couldn't be written", "Something went wrong while it was written.") {
                    ActButton("Try again", "primary", Modifier.padding(top = Space.L)) { writeAgain() }
                }
                // Just asked: dibs starts writing; its view says so shortly.
                s == null && link == Link.CONNECTED && !fetch.timedOut -> Writing(null)
                else -> Waiting(fetch, "full story")
            }
        }
        StoryBar(t, blocks != null)
    }
}

/** "dibs is writing it": calm, with no timeout; the chat says when it's ready. */
@Composable
private fun Writing(s: Story?) {
    val agent = s?.by == "agent"
    Calm(
        if (agent) "Its agent is writing it" else "dibs is writing it",
        (if (agent) "Its agent is going over the task" else "A writer is reading the task") +
            "; it takes a few minutes. You can leave: dibs tells you in the chat when it's ready.",
    ) {
        s?.since?.let { Text("Started ${whenWords(it)}", Modifier.padding(top = Space.L), style = AppType.small, color = Palette.Muted) }
    }
}

/** A centred state: a heading, a muted line, and what can be done about it. */
@Composable
private fun Calm(title: String, line: String?, more: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Space.XL),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = AppType.heading, color = Palette.Text, textAlign = TextAlign.Center)
        if (line != null) Text(line, style = AppType.body, color = Palette.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.S))
        more()
    }
}

/** When and by whom it was written, whether the task moved on since, then the story itself. */
@Composable
private fun StoryText(blocks: List<MdBlock>, t: YourTask, s: Story?, writing: Boolean, writeAgain: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.L, end = Space.L, top = Space.XS, bottom = Space.XXL),
        verticalArrangement = Arrangement.spacedBy(StoryLook.gap),
    ) {
        item(key = "_meta") { Meta(s, writing, writeAgain) }
        if (s != null && s.stale && !writing) item(key = "_stale") { Stale(writeAgain) }
        itemsIndexed(blocks) { _, b -> StoryBlock(b, t) }
    }
}

/** "Written 21:40 by dibs's writer"; "A new version is being written" while one is. */
@Composable
private fun Meta(s: Story?, writing: Boolean, writeAgain: () -> Unit) {
    val by = when (s?.by) {
        "agent" -> "by its agent"
        "writer" -> "by dibs's writer"
        else -> null
    }
    val written = listOfNotNull(s?.ts?.let { "Written ${whenWords(it)}" }, by).joinToString(" ").replaceFirstChar { it.uppercase() }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (written.isNotEmpty()) Text(written, style = AppType.small, color = Palette.Muted)
        when {
            writing -> Text("A new version is being written", style = AppType.small, color = Palette.Muted)
            s?.state == "failed" -> Text(
                "A new version couldn't be written · Try again",
                Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = writeAgain).padding(vertical = 2.dp),
                style = AppType.small,
                color = Palette.Accent,
            )
        }
    }
}

/** The task went on after it was written: say so quietly, with Write it again. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Stale(writeAgain: () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().background(Palette.Surface, MaterialTheme.shapes.medium).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.S),
        verticalArrangement = Arrangement.spacedBy(Space.S),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("The task moved on since this was written.", Modifier.padding(vertical = 4.dp), style = AppType.small, color = Palette.Text)
        ActButton("Write it again", "", onClick = writeAgain)
    }
}

/**
 * One block of the story. A long press on a paragraph or a point offers "Ask dibs about this part"
 * (the chat opens with it quoted) and Copy; that's why the text isn't selectable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StoryBlock(b: MdBlock, t: YourTask) {
    val text = when (b) {
        is MdBlock.Para -> b.text
        is MdBlock.Item -> b.text
        else -> null
    }
    if (text == null) {
        Block(b, StoryLook)
        return
    }
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    // As read: the Markdown marks gone, a link's text kept.
    val plain = remember(text) { inline(text).text }
    Box {
        Block(
            b,
            StoryLook,
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .background(if (menu) Palette.SurfaceLow else Palette.Bg)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                ),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Ask dibs about this part") },
                leadingIcon = { Icon(painterResource(R.drawable.lucide_message_circle), null, Modifier.size(18.dp)) },
                onClick = {
                    menu = false
                    Dibs.chatAboutStory(t, "ask", plain)
                },
            )
            DropdownMenuItem(
                text = { Text("Copy") },
                leadingIcon = { Icon(painterResource(R.drawable.lucide_copy), null, Modifier.size(18.dp)) },
                onClick = {
                    menu = false
                    copy(ctx, plain)
                },
            )
        }
    }
}

/**
 * Under the story, always in reach: the conversation it came from, and the two ways on through
 * dibs. They wrap on a narrow screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoryBar(t: YourTask, shown: Boolean) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = Space.L, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.S, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(Space.S),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ActButton("Show the conversation", "plain") { Dibs.open(Page.Transcript(t.id)) }
        if (shown) {
            ActButton("Start a follow-up", "") { Dibs.chatAboutStory(t, "follow") }
            ActButton("Ask dibs about this", "primary") { Dibs.chatAboutStory(t, "ask") }
        }
    }
}
