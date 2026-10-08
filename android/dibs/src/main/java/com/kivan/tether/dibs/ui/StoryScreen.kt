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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import com.kivan.tether.dibs.askQuote
import com.kivan.tether.dibs.markdownBlocks
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
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

/**
 * A task's full story as the screens read it: its [fetch] of the file, what the file says ([blocks]), and whether it
 * is being written ([writing]: also from a "write it again" tapped here, until dibs's view moves on).
 */
@Stable
internal class StoryState(
    val story: Story?,
    val fetch: Fetch,
    val blocks: List<MdBlock>?,
    val bad: Boolean,
    val writing: Boolean,
    val writeAgain: () -> Unit,
)

/**
 * Reads task [id]'s full story from its file, asking dibs for the file when a ready one isn't here. [autoAsk]: opening
 * also asks for one never written (dibs then writes it, which costs); without it that waits for the user's tap
 * ([Fetch.refresh]). While one is written, or after a failure, it never asks: each ask could start a paid write.
 */
@Composable
internal fun rememberStory(id: Long, s: Story?, autoAsk: Boolean): StoryState {
    val fetch = rememberFetch(id, "story", since = s?.ts ?: 0, ask = s?.state == "ready" || (autoAsk && s == null))
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
    return StoryState(s, fetch, blocks, bad, writing, writeAgain)
}

@Composable
internal fun StoryScreen(id: Long, view: DibsView) {
    val label = view.labelOf(id) ?: return Gone()
    val st = rememberStory(id, view.storyOf(id), autoAsk = true)
    val s = st.story
    val fetch = st.fetch
    val link by Dibs.host.link.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        PageBar("Full story", label)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val b = st.blocks
            when {
                b != null -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Space.L, end = Space.L, top = Space.XS, bottom = Space.XXL),
                    verticalArrangement = Arrangement.spacedBy(StoryLook.gap),
                ) { storyItems(b, st, id, label, view) }
                st.bad && !fetch.waiting -> Calm("It couldn't be read", null) {
                    ActButton("Get it again", "primary", Modifier.padding(top = Space.L)) { fetch.refresh() }
                }
                st.writing -> Writing(s)
                s?.state == "failed" -> Calm("It couldn't be written", "Something went wrong while it was written.") {
                    ActButton("Try again", "primary", Modifier.padding(top = Space.L)) { st.writeAgain() }
                }
                // Just asked: dibs starts writing; its view says so shortly.
                s == null && link == Link.CONNECTED && !fetch.timedOut -> Writing(null)
                else -> Waiting(fetch, "full story")
            }
        }
        StoryBar(id, label, view, st.blocks != null)
    }
}

/**
 * The full story inside a brief page's scrolling list, under its "Full story" eyebrow. Nothing is asked for that costs: a
 * story never written waits for a tap on "Read the full story" ([Fetch.refresh]), and one being written or failed is
 * never fetched. The same states as the story screen, said small.
 */
@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.fullStoryItems(st: StoryState, id: Long, label: String, view: DibsView, connected: Boolean) {
    item(key = "_full") { Eyebrow("Full story", Modifier.padding(top = Space.XL)) }
    val s = st.story
    val fetch = st.fetch
    val b = st.blocks
    when {
        b != null -> {
            storyItems(b, st, id, label, view)
            item(key = "_full_acts") {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = Space.S),
                    horizontalArrangement = Arrangement.spacedBy(Space.S),
                    verticalArrangement = Arrangement.spacedBy(Space.S),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (view.task(id) != null) ActButton("Show the conversation", "plain") { Dibs.open(Page.Transcript(id)) }
                    ActButton("Start a follow-up", "") { Dibs.chatAboutStory(id, label, "follow") }
                }
            }
        }
        st.bad && !fetch.waiting -> item(key = "_full_bad") {
            QuietBlock("It couldn't be read.") { ActButton("Get it again", "primary") { fetch.refresh() } }
        }
        st.writing -> item(key = "_full_writing") { QuietBlock(writingLine(s)) }
        s?.state == "failed" -> item(key = "_full_failed") {
            QuietBlock("It couldn't be written. Something went wrong while it was written.") { ActButton("Try again", "primary") { st.writeAgain() } }
        }
        // Never written and not asked for yet: a tap asks (dibs then writes it, a few minutes).
        s == null && fetch.askedAt == 0L -> item(key = "_full_ask") {
            QuietBlock("The full story is written only when you ask. It takes a few minutes.") {
                ActButton("Read the full story", "primary") { fetch.refresh() }
            }
        }
        s == null && connected && !fetch.timedOut -> item(key = "_full_asked") { QuietBlock(writingLine(null)) }
        fetch.timedOut -> item(key = "_full_late") {
            QuietBlock("It hasn't come. dibs didn't send the full story.") { ActButton("Try again", "primary") { fetch.refresh() } }
        }
        else -> item(key = "_full_wait") {
            QuietBlock(if (connected) "Getting the full story. dibs is sending it." else "Getting the full story. It comes once the laptop is reachable.")
        }
    }
}

private fun writingLine(s: Story?): String {
    val agent = s?.by == "agent"
    return (if (agent) "Its agent is writing it" else "dibs is writing it") +
        ". It takes a few minutes; you can leave: dibs tells you in the chat when it's ready."
}

/** A muted line with what can be done about it. */
@Composable
private fun QuietBlock(text: String, more: @Composable () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Text(text, style = AppType.body, color = Palette.Muted)
        more()
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
internal fun LazyListScope.storyItems(blocks: List<MdBlock>, st: StoryState, id: Long, label: String, view: DibsView) {
    item(key = "_meta") { Meta(st.story, st.writing, st.writeAgain) }
    if (st.story != null && st.story.stale && !st.writing) item(key = "_stale") { Stale(st.writeAgain) }
    itemsIndexed(blocks) { _, b -> StoryBlock(b, id, label, view) }
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
 * One block of the story. A long press on a paragraph or a point offers "Ask about this part" (its
 * Ask about page opens with the paragraph's start in the box; an older dibs: the chat with it quoted)
 * and Copy; that's why the text isn't selectable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StoryBlock(b: MdBlock, id: Long, label: String, view: DibsView) {
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
            val ask = view.askFor(id)
            DropdownMenuItem(
                text = { Text(if (ask != null) "Ask about this part" else "Ask dibs about this part") },
                leadingIcon = {
                    Icon(painterResource(if (ask != null) R.drawable.lucide_message_circle_question else R.drawable.lucide_message_circle), null, Modifier.size(18.dp))
                },
                onClick = {
                    menu = false
                    if (ask != null) Dibs.askAbout(view, ask.about, label, draft = askQuote(plain)) else Dibs.chatAboutStory(id, label, "ask", plain)
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
private fun StoryBar(id: Long, label: String, view: DibsView, shown: Boolean) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = Space.L, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.S, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(Space.S),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        // The conversation is the task's own page: only a task of the user's has one.
        if (view.task(id) != null) ActButton("Show the conversation", "plain") { Dibs.open(Page.Transcript(id)) }
        if (shown) {
            ActButton("Start a follow-up", "") { Dibs.chatAboutStory(id, label, "follow") }
            val ask = view.askFor(id)
            if (ask != null) {
                ActButton(ask.label, "primary", icon = R.drawable.lucide_message_circle_question) { Dibs.askAbout(view, ask.about, label) }
            } else {
                ActButton("Ask dibs about this", "primary") { Dibs.chatAboutStory(id, label, "ask") }
            }
        }
    }
}
