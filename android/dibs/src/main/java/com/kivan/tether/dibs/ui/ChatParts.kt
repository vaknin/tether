package com.kivan.tether.dibs.ui

import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.About
import com.kivan.tether.dibs.Ask
import com.kivan.tether.dibs.ChatRow
import com.kivan.tether.dibs.Composer
import com.kivan.tether.dibs.DayRow
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.Echo
import com.kivan.tether.dibs.EchoRow
import com.kivan.tether.dibs.FileRef
import com.kivan.tether.dibs.FoldRow
import com.kivan.tether.dibs.LineRow
import com.kivan.tether.dibs.NoteRow
import com.kivan.tether.dibs.Page
import com.kivan.tether.dibs.Pending
import com.kivan.tether.dibs.Picked
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.TalkLine
import com.kivan.tether.dibs.aboutWords
import com.kivan.tether.dibs.chatRows
import com.kivan.tether.dibs.dayWords
import com.kivan.tether.dibs.fileSize
import com.kivan.tether.dibs.outcomeWords
import com.kivan.tether.dibs.picked
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Pill
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.math.max

// The chat's parts, shared by the dibs chat (ChatTab) and each task's chat with its agent
// (TaskPage): bubbles, day headers and folds, notes, typing dots, the input bar with its attach
// menu and strip, files and thumbnails. Nothing swipes away; a long press copies (or hides) a line.

/** A long line shows this many lines until "More". */
private const val CLAMP = 4
/** The dibs chat's earlier days unfold under this key prefix in [Dibs.open]. */
internal const val DAY = "day:"
/** A closed question's line opened to its full bubble. */
private const val ASK = "ask:"

/**
 * How a chat draws: [name] on the first bubble of each run of the other side's (a task's agent),
 * whether a line can be hidden (dibs's own chat only), the [Dibs.open] prefix its unfolded days
 * are kept under, and the tasks whose full story a line's "Read it" can open ([stories]).
 */
@Immutable
internal data class ChatLook(val name: String? = null, val hide: Boolean = true, val days: String = DAY, val stories: Set<Long> = emptySet())

/** The chat's rows from [talk] and the echoes still waiting, regrouped as the clock moves on. */
@Composable
internal fun rememberChatRows(talk: List<TalkLine>, pending: List<Pending>, look: ChatLook): List<ChatRow> {
    val now by rememberNow()
    val unfolded = Dibs.open.filter { it.value && it.key.startsWith(look.days) }.keys.mapTo(HashSet()) { it.removePrefix(look.days) }
    return remember(talk, pending, now / 60, unfolded) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        chatRows(talk, pending.map { Echo(it.uid, it.tsMs / 1000) }, now, unfolded, zone) { dayWords(it, today) }
    }
}

/** The rows as list items, in the order given (the dibs chat draws them from the bottom). */
internal fun LazyListScope.chatItems(rows: List<ChatRow>, echoes: Map<String, Pending>, look: ChatLook) {
    items(rows, key = { it.key }, contentType = { it::class }) { row ->
        Box(Modifier.animateItem()) {
            when (row) {
                is FoldRow -> Fold(row, look)
                is DayRow -> DayHeader(row, look)
                is NoteRow -> NoteLine(row.line, look)
                is LineRow -> Line(row, look)
                is EchoRow -> echoes[row.uid]?.let { EchoBubble(it, row) }
            }
        }
    }
}

/**
 * The rows from the bottom: it opens at the newest and follows new lines while near the bottom
 * (always after one of mine). Scrolled up, "↓ N new" goes back down.
 */
@Composable
internal fun Conversation(rows: List<ChatRow>, echoes: Map<String, Pending>, busy: Boolean, busyLine: String?, look: ChatLook = ChatLook()) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reversed = remember(rows) { rows.asReversed() }
    val newest = rows.lastOrNull()?.key
    val newestMine = when (val r = rows.lastOrNull()) {
        is EchoRow -> true
        is LineRow -> r.line.mine
        else -> false
    }
    LaunchedEffect(newest, busy) {
        if (newest != null && (state.firstVisibleItemIndex <= 2 || newestMine)) state.animateScrollToItem(0)
    }
    val atBottom by remember { derivedStateOf { state.firstVisibleItemIndex <= 1 } }
    var seen by remember { mutableStateOf(newest) }
    LaunchedEffect(atBottom, newest) { if (atBottom) seen = newest }
    val fresh = remember(rows, seen) {
        val i = rows.indexOfLast { it.key == seen }
        if (i < 0) 0 else rows.drop(i + 1).count { it !is DayRow && it !is FoldRow }
    }
    val scrolledUp by remember { derivedStateOf { state.firstVisibleItemIndex > 3 } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = Space.L, vertical = Space.S),
        ) {
            if (busy) item(key = "_busy") { Typing(busyLine) }
            chatItems(reversed, echoes, look)
        }
        AnimatedVisibility(
            scrolledUp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.L),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                Modifier.clip(Pill).background(Palette.SurfaceHighest)
                    .clickable { scope.launch { state.animateScrollToItem(0) } }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(painterResource(R.drawable.lucide_arrow_down), null, Modifier.size(16.dp), tint = Palette.Text)
                Text(if (fresh > 0) "$fresh new" else "Latest", style = AppType.label, color = Palette.Text)
            }
        }
    }
}

/** "Earlier · Sun 4 Oct · 23 lines": a day folded away, opened on a tap. */
@Composable
private fun Fold(row: FoldRow, look: ChatLook) {
    Box(Modifier.fillMaxWidth().padding(top = Space.S), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(Pill).background(Palette.SurfaceLow)
                .clickable { Dibs.open[look.days + row.day] = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(painterResource(R.drawable.lucide_chevron_down), null, Modifier.size(16.dp), tint = Palette.Muted)
            val lines = if (row.count == 1) "1 line" else "${row.count} lines"
            Text("Earlier · ${row.label} · $lines", style = AppType.small, color = Palette.Muted)
        }
    }
}

/** A day's eyebrow; an earlier day opened here folds again on a tap. */
@Composable
private fun DayHeader(row: DayRow, look: ChatLook) {
    val unfolded = Dibs.open[look.days + row.day] == true
    Box(
        Modifier.fillMaxWidth()
            .then(if (unfolded) Modifier.clickable { Dibs.open.remove(look.days + row.day) } else Modifier)
            .padding(top = Space.L, bottom = Space.XS),
        contentAlignment = Alignment.Center,
    ) { Eyebrow(row.label) }
}

/**
 * dibs's own note ("Started task …"): a small centred line; a tap shows the full text. A note that a
 * full story is ready has "Read it" under it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteLine(l: TalkLine, look: ChatLook) {
    val open = Dibs.open[l.id] == true
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Column {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                buildAnnotatedString {
                    append(if (open || l.short == null) l.text else l.short)
                    if (l.short != null && !open) append(" ›")
                },
                style = AppType.small,
                color = Palette.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(0.88f).clip(MaterialTheme.shapes.small)
                    .combinedClickable(
                        onClick = { if (l.short != null) Dibs.toggle(l.id) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menu = true
                        },
                    )
                    .padding(4.dp),
            )
            LineMenu(menu, l, look) { menu = false }
        }
        ReadIt(l, look, Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).padding(bottom = 6.dp))
    }
}

/** "Read it" under dibs's note that a task's full story is ready: it opens the story. */
@Composable
private fun ReadIt(l: TalkLine, look: ChatLook, modifier: Modifier = Modifier) {
    val task = l.open?.takeIf { it in look.stories } ?: return
    Box(modifier) {
        Row(
            Modifier.clip(Pill).background(Palette.AccentDim).clickable { Dibs.open(Page.Story(task)) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(painterResource(R.drawable.lucide_book_open), null, Modifier.size(16.dp), tint = Palette.Accent)
            Text("Read it", style = AppType.label, color = Palette.Accent)
        }
    }
}

/** Copy and (in dibs's own chat) Hide, on a long press (the old swipe, tucked away). */
@Composable
private fun LineMenu(expanded: Boolean, l: TalkLine, look: ChatLook, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (l.text.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text("Copy") },
                leadingIcon = { Icon(painterResource(R.drawable.lucide_copy), null, Modifier.size(18.dp)) },
                onClick = {
                    onDismiss()
                    copy(ctx, l.text)
                },
            )
        }
        if (look.hide) {
            DropdownMenuItem(
                text = { Text("Hide") },
                leadingIcon = { Icon(painterResource(R.drawable.lucide_eye_off), null, Modifier.size(18.dp)) },
                onClick = {
                    onDismiss()
                    Dibs.hide(l)
                },
            )
        }
    }
}

/** A line of the conversation: a bubble, or a question answered (folded to one row). */
@Composable
private fun Line(row: LineRow, look: ChatLook) {
    val l = row.line
    val ask = l.ask
    // Answered here: it folds at once, with what was chosen, until the view says how it ended.
    val outcome = ask?.outcome ?: ask?.let { Dibs.answered["q${it.q}"] }
    if (ask != null && outcome != null && Dibs.open[ASK + l.id] != true) {
        AnsweredRow(l, outcomeWords(outcome), look)
        return
    }
    Bubble(row, outcome, look)
}

/** "✓ Inside Tether, separate app? · Inside Tether · 17:58", wrapping whole; a tap opens the full bubble. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnsweredRow(l: TalkLine, outcome: String, look: ChatLook) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(top = Space.S)) {
        Row(
            Modifier.clip(MaterialTheme.shapes.small)
                .combinedClickable(onClick = { Dibs.open[ASK + l.id] = true }, onLongClick = { menu = true })
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(painterResource(R.drawable.lucide_check), "Answered", Modifier.size(16.dp), tint = Palette.Success)
            // One sentence, so a long question wraps as text does instead of squeezing the answer.
            val at = time(l.ts)
            val words = remember(l.text, outcome, at) {
                buildAnnotatedString {
                    append(l.text)
                    append(" · ")
                    pushStyle(SpanStyle(color = Palette.Text, fontWeight = Bold))
                    append(outcome)
                    pop()
                    append(" · ")
                    pushStyle(AppType.mono.toSpanStyle())
                    append(at)
                    pop()
                }
            }
            Text(words, style = AppType.small, color = Palette.Muted, modifier = Modifier.weight(1f, fill = false))
        }
        LineMenu(menu, l, look) { menu = false }
    }
}

/**
 * A bubble on its side (mine at the end): the other side's name on the first of a run (when the
 * chat has one), files, the text (long ones fold to [CLAMP] lines), the time on the last of a run,
 * and an open question's buttons and answer box inside it.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun Bubble(row: LineRow, outcome: String?, look: ChatLook) {
    val l = row.line
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val meta = if (l.mine) MineMeta else Palette.Muted
    val ask = l.ask
    BubbleBox(l.mine, row.first, row.last) {
        Box {
            Column(
                Modifier.combinedClickable(
                    onClick = { if (ask != null && outcome != null) Dibs.open.remove(ASK + l.id) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                ).padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!l.mine && row.first && look.name != null) {
                    Text(look.name, style = AppType.label, color = Palette.Accent)
                }
                for (f in l.files) FileView(f, l.mine)
                if (l.text.isNotEmpty() || row.last) {
                    LineText(l.id, l.text, if (row.last) time(l.ts) else null, meta)
                }
                ReadIt(l, look)
                when {
                    ask != null && outcome != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(painterResource(R.drawable.lucide_check), null, Modifier.size(16.dp), tint = Palette.Success)
                        Text(outcomeWords(outcome), style = AppType.small, color = Palette.Muted)
                    }
                    ask != null -> AskButtons(ask)
                }
            }
            LineMenu(menu, l, look) { menu = false }
        }
    }
}

/** An open question's buttons (the first is the main one) and its box for words, as on its Waiting card. */
@Composable
private fun AskButtons(ask: Ask) {
    QuestionControls(ask.q, "ask/${ask.q}", ask.actions, ask.reply, ask.hint, Modifier.padding(top = 3.dp))
}

/** A message of mine not in a view yet: what was picked for it, its text, a clock (and how far its files got). */
@Composable
private fun EchoBubble(p: Pending, row: EchoRow) {
    val uploads by Dibs.host.uploads.collectAsStateWithLifecycle()
    val done = p.files.mapNotNull { f -> Dibs.sentIds[f.uri]?.let { uploads[it] } }
    BubbleBox(mine = true, first = row.first, last = row.last) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (f in p.files) PickedView(f)
            EchoText(p.text) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (done.isNotEmpty()) Text("${(done.average() * 100).toInt()}%", style = AppType.mono, color = MineMeta)
                    Icon(painterResource(R.drawable.lucide_clock), "Waiting", Modifier.size(14.dp), tint = MineMeta)
                }
            }
        }
    }
}

// The echo's text with its meta tucked in, or just the meta when there is no text.
@Composable
private fun ColumnScope.EchoText(text: String, meta: @Composable () -> Unit) {
    if (text.isEmpty()) {
        Box(Modifier.align(Alignment.End)) { meta() }
    } else {
        TextWithMeta(remember(text) { linkified(text) }, Palette.Text, bodyStyle, CLAMP_NONE, {}, meta)
    }
}

/**
 * A file in a line: an image as a thumbnail when there is one (a tap opens it full screen), else
 * a chip with its name and size.
 */
@Composable
private fun FileView(f: FileRef, mine: Boolean) {
    if (f.image) {
        val thumb = rememberThumb(f.id)
        if (thumb != null) {
            var full by remember { mutableStateOf(false) }
            Thumbnail(thumb, f.name) { full = true }
            if (full) ImageViewer(thumb, rememberFullImage(f.id), f.name) { full = false }
            return
        }
    }
    FileChip(f.name, fileSize(f.size), mine)
}

@Composable
private fun PickedView(p: Picked) {
    if (p.image) {
        val bmp = rememberPicked(p.uri)
        if (bmp != null) {
            var full by remember { mutableStateOf(false) }
            Thumbnail(bmp, p.name) { full = true }
            if (full) ImageViewer(bmp, rememberPicked(p.uri, FULL_PX), p.name) { full = false }
            return
        }
    }
    FileChip(p.name, null, mine = true)
}

/** An image at its own aspect, never cropped, inside 220 dp; a tap calls [onOpen]. */
@Composable
private fun Thumbnail(bmp: ImageBitmap, name: String, onOpen: () -> Unit) {
    val ratio = bmp.width.toFloat() / max(1, bmp.height)
    val w = if (ratio >= 1f) 220.dp else 220.dp * ratio
    Image(
        bmp,
        name,
        Modifier.width(w).aspectRatio(ratio).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Open full screen", onClick = onOpen),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun FileChip(name: String, size: String?, mine: Boolean) {
    Row(
        Modifier.background(if (mine) Palette.Bg.copy(alpha = 0.35f) else Palette.SurfaceHigh, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.lucide_file_text), null, Modifier.size(18.dp), tint = Palette.Muted)
        Text(name, style = AppType.label, color = Palette.Text, modifier = Modifier.weight(1f, fill = false))
        if (size != null) Text(size, style = AppType.mono, color = Palette.Muted)
    }
}

/**
 * A line's text, with the time tucked into its last line. A long one shows [CLAMP] lines with a
 * soft fade and "More", which opens it in place ([Dibs.open] by [id]).
 */
@Composable
private fun LineText(id: String, text: String, time: String?, metaColor: Color) {
    val open = Dibs.open[id] == true
    var long by remember(text) { mutableStateOf(false) }
    val annotated = remember(text) { linkified(text) }
    val meta: @Composable () -> Unit = { if (time != null) Text(time, style = AppType.mono, color = metaColor) }
    if (!long) {
        TextWithMeta(annotated, Palette.Text, bodyStyle, CLAMP, { long = true }, meta)
        return
    }
    Column {
        Text(
            annotated,
            color = Palette.Text,
            style = bodyStyle,
            maxLines = if (open) Int.MAX_VALUE else CLAMP,
            modifier = if (open) Modifier else Modifier.fadeOut(),
        )
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(MaterialTheme.shapes.small).clickable { Dibs.toggle(id) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(if (open) "Less" else "More", style = AppType.label, color = Palette.Accent)
                Icon(
                    painterResource(if (open) R.drawable.lucide_chevron_up else R.drawable.lucide_chevron_down),
                    null,
                    Modifier.size(16.dp),
                    tint = Palette.Accent,
                )
            }
            Spacer(Modifier.weight(1f).width(Space.S))
            meta()
        }
    }
}

private const val CLAMP_NONE = Int.MAX_VALUE

internal val bodyStyle = AppType.body.merge(TextStyle(textDirection = TextDirection.Content))

/** The time on my bubbles: the accent toned down toward muted, as the mockup has it. */
internal val MineMeta = lerp(Palette.Accent, Palette.Muted, 0.45f)

// Fades the bottom of a folded line into the bubble.
private fun Modifier.fadeOut() = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0.5f to Palette.Text, 1f to Palette.Text.copy(alpha = 0f)), blendMode = BlendMode.DstIn)
    }

/**
 * Text with the meta tucked into the end of its last line when it fits there, and on a line of
 * its own when it doesn't (or when that line is right-to-left). Past [maxLines] it calls [onLong].
 */
@Composable
private fun TextWithMeta(text: AnnotatedString, color: Color, style: TextStyle, maxLines: Int, onLong: () -> Unit, meta: @Composable () -> Unit) {
    // Not state: the layout is read in the same measure pass that produces it.
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout(content = {
        Text(text, color = color, style = style, maxLines = maxLines, onTextLayout = {
            layout[0] = it
            if (it.hasVisualOverflow) onLong()
        })
        meta()
    }) { measurables, constraints ->
        val gap = 10.dp.roundToPx()
        val t = measurables[0].measure(constraints.copy(minWidth = 0))
        // [meta] may draw nothing (a line with no time shown): then it's the text alone.
        val mt = measurables.getOrNull(1)?.measure(Constraints())
            ?: return@Layout layout(t.width, t.height) { t.place(0, 0) }
        val l = layout[0]
        val lastLine = l?.let { it.lineCount - 1 }
        val lastRight = if (l != null && lastLine != null) l.getLineRight(lastLine).toInt() else t.width
        val rtl = l != null && lastLine != null && l.getParagraphDirection(l.getLineStart(lastLine)) == ResolvedTextDirection.Rtl
        val inline = !rtl && lastRight + gap + mt.width <= constraints.maxWidth
        val w = if (inline) max(t.width, lastRight + gap + mt.width) else max(t.width, mt.width)
        val h = if (inline) max(t.height, mt.height) else t.height + mt.height
        layout(w, h) {
            t.place(0, 0)
            mt.place(w - mt.width, h - mt.height)
        }
    }
}

/**
 * A bubble on its side, at most 82% of the width: rounded everywhere, tighter where it meets the
 * next of its run, with a small tail corner on the last.
 */
@Composable
internal fun BubbleBox(mine: Boolean, first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    val big = 14.dp
    val join = 4.dp
    val tail = 6.dp
    val shape = if (mine) {
        RoundedCornerShape(big, if (first) big else join, if (last) tail else join, big)
    } else {
        RoundedCornerShape(if (first) big else join, big, big, if (last) tail else join)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = if (first) Space.S else 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(Modifier.fillMaxWidth(0.82f), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.clip(shape).background(if (mine) Palette.AccentDim else Palette.Surface)) { content() }
        }
    }
}

/** "dibs is on it": three calm dots under the newest line while dibs (or a task's agent) works. */
@Composable
internal fun Typing(line: String?) {
    val pulse = rememberInfiniteTransition(label = "typing")
    Row(
        Modifier.fillMaxWidth().padding(top = Space.S, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Row(
            Modifier.background(Palette.Surface, MaterialTheme.shapes.medium).padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (i in 0 until 3) {
                val a by pulse.animateFloat(
                    0.35f, 1f,
                    infiniteRepeatable(tween(600, delayMillis = i * 200), RepeatMode.Reverse),
                    label = "dot$i",
                )
                Box(Modifier.size(6.dp).graphicsLayer { alpha = a }.background(Palette.Muted, CircleShape))
            }
        }
        Text(line?.takeIf { it.isNotBlank() } ?: "dibs is on it", Modifier.weight(1f, fill = false), style = AppType.small, color = Palette.Muted)
    }
}

/**
 * The box: what the next message is about (a full story) and what's picked wait above it; 📎
 * offers Photos, Camera and Files.
 */
@Composable
internal fun InputArea(box: Composer, placeholder: String) {
    val draft = box.draft
    val canSend = box.canSend
    Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        box.about?.let { AboutChip(it) }
        if (box.picked.isNotEmpty()) Strip(box)
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface, MaterialTheme.shapes.large).padding(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Attach(box)
            BasicTextField(
                value = draft,
                onValueChange = { box.draft = it },
                textStyle = bodyStyle.merge(TextStyle(color = Palette.Text)),
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 6,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 9.dp),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) Text(placeholder, style = AppType.body, color = Palette.Muted)
                        inner()
                    }
                },
            )
            FilledIconButton(
                onClick = { box.send() },
                enabled = canSend,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Palette.Accent,
                    contentColor = Palette.OnAccent,
                    disabledContainerColor = Palette.SurfaceHigh,
                    disabledContentColor = Palette.Muted,
                ),
                modifier = Modifier.size(40.dp),
            ) { Icon(painterResource(R.drawable.lucide_send_horizontal), "Send", Modifier.size(20.dp)) }
        }
    }
}

/** "About the full story of …" (and the paragraph asked about), with ✕: the next message carries it. */
@Composable
private fun AboutChip(a: About) {
    Row(
        Modifier.fillMaxWidth().background(Palette.AccentDim, MaterialTheme.shapes.medium).padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.lucide_book_open), null, Modifier.size(16.dp), tint = Palette.Accent)
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(aboutWords(a), style = AppType.label, color = Palette.Text)
            a.quote?.let { Text("“${it.trim()}”", style = AppType.small, color = Palette.Muted) }
        }
        IconButton(onClick = { Dibs.dropAbout() }, modifier = Modifier.size(36.dp)) {
            Icon(painterResource(R.drawable.lucide_x), "Not about the story", Modifier.size(16.dp), tint = Palette.Muted)
        }
    }
}

/** 📎 and its menu: the photo picker, the camera, any file. */
@Composable
private fun Attach(box: Composer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var shot by rememberSaveable { mutableStateOf<Uri?>(null) }
    val add: (List<Uri>) -> Unit = { uris ->
        scope.launch {
            val got = withContext(Dispatchers.IO) { uris.filter { u -> box.picked.none { it.source == u } }.mapNotNull { picked(ctx, it) } }
            box.picked += got
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { add(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { add(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = shot
        if (ok && uri != null) add(listOf(uri))
    }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
            Icon(painterResource(R.drawable.lucide_paperclip), "Attach", Modifier.size(20.dp), tint = Palette.Muted)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            // A launch throws when no app takes it (no camera app, say): then nothing opens.
            AttachItem("Photos", R.drawable.lucide_image) {
                menu = false
                runCatching { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
            }
            AttachItem("Camera", R.drawable.lucide_camera) {
                menu = false
                runCatching {
                    val uri = cameraFile(ctx)
                    shot = uri
                    camera.launch(uri)
                }
            }
            AttachItem("Files", R.drawable.lucide_file) {
                menu = false
                runCatching { files.launch(arrayOf("*/*")) }
            }
        }
    }
}

@Composable
private fun AttachItem(label: String, icon: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(painterResource(icon), null, Modifier.size(18.dp)) },
        onClick = onClick,
    )
}

/** At most this many photos at once from the picker. */
private const val MAX_PICK = 10

/**
 * A new file for the camera to write, in cache/camera (shared through the module's FileProvider).
 * Shots from earlier days go: each was copied for sending.
 */
private fun cameraFile(ctx: android.content.Context): Uri {
    val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
    val old = System.currentTimeMillis() - 24 * 3600_000L
    dir.listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
    val f = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(ctx, ctx.packageName + ".dibs.files", f)
}

/** What's picked for the next message, each with ✕. */
@Composable
private fun Strip(box: Composer) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (p in box.picked) {
            Box {
                if (p.image) {
                    val bmp = rememberPicked(p.uri, 200)
                    Box(Modifier.size(54.dp).clip(MaterialTheme.shapes.small).background(Palette.SurfaceHigh)) {
                        if (bmp != null) Image(bmp, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                } else {
                    Row(
                        Modifier.heightIn(min = 54.dp).widthIn(max = 160.dp).background(Palette.SurfaceHigh, MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(painterResource(R.drawable.lucide_file_text), null, Modifier.size(18.dp), tint = Palette.Muted)
                        Text(p.name, style = AppType.small, color = Palette.Text)
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).size(22.dp).clip(CircleShape)
                        .background(Palette.SurfaceHighest).clickable { box.unpick(p) },
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.lucide_x), "Remove ${p.name}", Modifier.size(12.dp), tint = Palette.Text) }
            }
        }
    }
}

private val urlPattern = Regex("""\b(?:https?://|www\.)[^\s<>"]+[^\s<>".,;:!?)\]']""")

/** The text with its links tappable. */
internal fun linkified(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    val style = TextLinkStyles(SpanStyle(color = Palette.Accent, textDecoration = TextDecoration.Underline))
    for (match in urlPattern.findAll(text)) {
        val url = match.value.let { if (it.startsWith("www.")) "https://$it" else it }
        addLink(LinkAnnotation.Url(url, style), match.range.first, match.range.last + 1)
    }
}

/** A line's time, as the phone's clock shows times (12 or 24 hours). */
@Composable
internal fun time(ts: Long): String {
    val ctx = LocalContext.current
    return remember(ts) { DateFormat.getTimeFormat(ctx).format(Date(ts * 1000)) }
}
