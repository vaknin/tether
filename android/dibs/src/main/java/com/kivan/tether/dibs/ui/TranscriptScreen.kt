package com.kivan.tether.dibs.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.AgentRow
import com.kivan.tether.dibs.AskRow
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Link
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.SeamRow
import com.kivan.tether.dibs.Step
import com.kivan.tether.dibs.StepsRow
import com.kivan.tether.dibs.SysRow
import com.kivan.tether.dibs.TRow
import com.kivan.tether.dibs.Transcript
import com.kivan.tether.dibs.UserRow
import com.kivan.tether.dibs.seamWords
import com.kivan.tether.dibs.transcriptRows
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Pill
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream

// A task's whole transcript (docs/DIBS-APP.md, "Your tasks"), read like a document from the top:
// what was asked (folded), the agent's text in full, the user's lines as bubbles, dibs's notes as
// muted lines, each run of tool calls as one line that opens to its steps. dibs sends it as a
// file on its channel when asked (`fetch`); it's never in the view.

/** How long "Getting it…" waits before it offers to ask again. */
private const val FETCH_WAIT_MS = 90_000L

/**
 * The newest [what] file dibs sent for [task] (`transcript` or `report`). On open it asks dibs for
 * one when there is none or it's older than [since] (the task's newest activity); [refresh] asks again.
 */
@Stable
internal class Fetch(private val task: Long, private val what: String) {
    var file by mutableStateOf<File?>(null)
    /** The first lookup is done. */
    var loaded by mutableStateOf(false)
    /** When it last asked dibs (phone clock, ms); 0: it didn't. */
    var askedAt by mutableLongStateOf(0L)
    var timedOut by mutableStateOf(false)

    /** Asked and nothing newer has come yet. */
    val waiting: Boolean get() = askedAt > 0 && (file?.lastModified() ?: 0) < askedAt

    fun refresh() {
        askedAt = System.currentTimeMillis()
        timedOut = false
        Dibs.fetch(task, what)
    }
}

@Composable
internal fun rememberFetch(task: Long, what: String, since: Long): Fetch {
    val f = remember(task, what) { Fetch(task, what) }
    LaunchedEffect(f) {
        Dibs.host.channelFile("$what-$task-").collect {
            f.file = it
            f.loaded = true
        }
    }
    LaunchedEffect(f.loaded) {
        val file = f.file
        if (f.loaded && f.askedAt == 0L && (file == null || file.lastModified() / 1000 < since)) f.refresh()
    }
    LaunchedEffect(f.askedAt) {
        if (f.askedAt == 0L) return@LaunchedEffect
        delay(FETCH_WAIT_MS)
        if (f.waiting) f.timedOut = true
    }
    return f
}

/** A fetched file's text: gunzipped when it ends in .gz. Blocking. */
internal fun readFetched(file: File): String =
    if (file.name.contains(".gz")) GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() } else file.readText()

/** "Getting it…" while dibs sends it, or why it isn't here, with Try again. */
@Composable
internal fun Waiting(fetch: Fetch, what: String) {
    val link by Dibs.host.link.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().padding(horizontal = Space.XL),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val (title, line) = when {
            link != Link.CONNECTED -> "Getting it…" to "It comes once the laptop is reachable."
            fetch.timedOut -> "It hasn't come" to "dibs didn't send the $what."
            else -> "Getting it…" to "dibs is sending the $what."
        }
        Text(title, style = AppType.heading, color = Palette.Text, textAlign = TextAlign.Center)
        Text(line, style = AppType.body, color = Palette.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.S))
        if (fetch.timedOut) ActButton("Try again", "primary", Modifier.padding(top = Space.L)) { fetch.refresh() }
    }
}

@Composable
internal fun TranscriptScreen(id: Long, view: DibsView) {
    val t = view.task(id) ?: return Gone()
    val fetch = rememberFetch(id, "transcript", t.ts)
    var bad by remember { mutableStateOf(false) }
    // The last one read stays on screen while a newer one is read.
    val transcript by produceState<Transcript?>(null, fetch.file) {
        val f = fetch.file ?: return@produceState
        val read = withContext(Dispatchers.IO) { runCatching { Transcript.parse(readFetched(f)) }.getOrNull() }
        bad = read == null
        if (read != null) value = read
    }
    val rows = remember(transcript) { transcript?.let(::transcriptRows).orEmpty() }

    Column(Modifier.fillMaxSize()) {
        PageBar("Transcript", t.label)
        val tr = transcript
        if (tr != null && !t.finishedState) AsOf(tr.asOf, fetch)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                tr != null -> TranscriptList(rows)
                bad && !fetch.waiting -> Column(
                    Modifier.fillMaxSize().padding(horizontal = Space.XL),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("It couldn't be read", style = AppType.heading, color = Palette.Text)
                    ActButton("Get it again", "primary", Modifier.padding(top = Space.L)) { fetch.refresh() }
                }
                else -> Waiting(fetch, "transcript")
            }
        }
    }
}

/** "As of 21:40 · Refresh": a running task's transcript is a snapshot. */
@Composable
private fun AsOf(asOf: Long, fetch: Fetch) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.L).padding(bottom = Space.XS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (asOf > 0) "As of ${whenWords(asOf)}" else "A snapshot", style = AppType.small, color = Palette.Muted)
        Text("·", style = AppType.small, color = Palette.Muted)
        if (fetch.waiting && !fetch.timedOut) {
            Text("Refreshing…", style = AppType.small, color = Palette.Muted)
        } else {
            Row(
                Modifier.clip(MaterialTheme.shapes.small).clickable { fetch.refresh() }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(painterResource(R.drawable.lucide_refresh_cw), null, Modifier.size(14.dp), tint = Palette.Accent)
                Text("Refresh", style = AppType.small, color = Palette.Accent)
            }
        }
    }
}

/** The rows from the top, with "↓ End" to jump to the newest. */
@Composable
private fun TranscriptList(rows: List<TRow>) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // What's opened here: runs of steps, a step's input and output, the ask.
    val open = remember { mutableStateMapOf<String, Boolean>() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = Space.L, end = Space.L, top = Space.XS, bottom = Space.XXL),
            verticalArrangement = Arrangement.spacedBy(Space.S),
        ) {
            items(rows, key = { it.key }, contentType = { it::class }) { row ->
                when (row) {
                    is AskRow -> AskBlock(row)
                    is AgentRow -> SelectionContainer {
                        Text(remember(row.text) { linkified(row.text) }, Modifier.fillMaxWidth().padding(vertical = 2.dp), style = bodyStyle, color = Palette.Text)
                    }
                    is UserRow -> UserBubble(row)
                    is SysRow -> Text(
                        row.text,
                        Modifier.fillMaxWidth().padding(horizontal = Space.L),
                        style = AppType.small,
                        color = Palette.Muted,
                        textAlign = TextAlign.Center,
                    )
                    is StepsRow -> StepsBlock(row, open)
                    is SeamRow -> Box(Modifier.fillMaxWidth().padding(vertical = Space.S), contentAlignment = Alignment.Center) {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        Eyebrow(seamWords(row.how, row.ts) { android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(it * 1000)) })
                    }
                }
            }
        }
        AnimatedVisibility(
            state.canScrollForward,
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.L),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                Modifier.clip(Pill).background(Palette.SurfaceHighest)
                    .clickable { scope.launch { state.animateScrollToItem(maxOf(0, rows.size - 1)) } }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(painterResource(R.drawable.lucide_arrow_down), null, Modifier.size(16.dp), tint = Palette.Text)
                Text("End", style = AppType.label, color = Palette.Text)
            }
        }
    }
}

/** What the task was asked, folded: it was written for the agent. */
@Composable
private fun AskBlock(row: AskRow) {
    Column(Modifier.card().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        Eyebrow("You asked")
        TapFold(row.text, "transcript-ask:${row.key}:${row.text.hashCode()}", 3, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
    }
}

/** The user's own line, typed at the laptop or sent from the phone, as their bubble. */
@Composable
private fun UserBubble(row: UserRow) {
    BubbleBox(mine = true, first = true, last = true) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(remember(row.text) { linkified(row.text) }, style = bodyStyle, color = Palette.Text)
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (row.src) {
                    "phone" -> Icon(painterResource(R.drawable.lucide_smartphone), "From the phone", Modifier.size(12.dp), tint = MineMeta)
                    "laptop" -> Icon(painterResource(R.drawable.lucide_laptop), "From the laptop", Modifier.size(12.dp), tint = MineMeta)
                }
                if (row.ts > 0) Text(whenWords(row.ts), style = AppType.mono, color = MineMeta)
            }
        }
    }
}

/** A run of tool calls as one line; a tap lists its steps, a tap on a step shows its input and output. */
@Composable
private fun StepsBlock(row: StepsRow, open: MutableMap<String, Boolean>) {
    val opened = open[row.key] == true
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { open[row.key] = !opened }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(R.drawable.lucide_hammer), null, Modifier.size(15.dp), tint = Palette.Muted)
            Text(row.summary, Modifier.weight(1f), style = AppType.small, color = Palette.Muted)
            Icon(
                painterResource(if (opened) R.drawable.lucide_chevron_up else R.drawable.lucide_chevron_down),
                if (opened) "Fold" else "Show the steps",
                Modifier.size(16.dp),
                tint = Palette.Muted,
            )
        }
        if (opened) {
            Column(Modifier.padding(start = 23.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                row.steps.forEachIndexed { i, s -> StepLine(s, "${row.key}:$i", open) }
            }
        }
    }
}

@Composable
private fun StepLine(s: Step, key: String, open: MutableMap<String, Boolean>) {
    val opened = open[key] == true
    val has = s.input != null || s.output != null
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .then(if (has) Modifier.clickable { open[key] = !opened } else Modifier)
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (s.error) {
                Icon(painterResource(R.drawable.lucide_triangle_alert), "Failed", Modifier.padding(top = 2.dp).size(13.dp), tint = Palette.Warning)
            } else {
                Box(Modifier.padding(top = 7.dp, start = 4.dp, end = 4.dp).size(5.dp).background(Palette.Muted, CircleShape))
            }
            Text(s.line.ifBlank { s.tool }, Modifier.weight(1f), style = AppType.small, color = Palette.Text)
        }
        if (opened) {
            Column(Modifier.padding(start = 21.dp, bottom = Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                s.input?.let { Io("Input", it) }
                s.output?.let { Io(if (s.error) "Output (failed)" else "Output", it) }
            }
        }
    }
}

/** A step's input or output: monospace, as dibs cut it (at 2 KB, saying so). */
@Composable
private fun Io(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow(label)
        SelectionContainer {
            Text(
                text,
                Modifier.fillMaxWidth().background(Palette.SurfaceLow, MaterialTheme.shapes.small)
                    .horizontalScroll(rememberScrollState()).padding(10.dp),
                style = AppType.mono,
                color = Palette.Text,
            )
        }
    }
}
