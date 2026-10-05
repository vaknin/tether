package com.kivan.tether.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kivan.tether.R
import com.kivan.tether.ui.theme.Eyebrow
import com.kivan.tether.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

// The chat's parts, shared by the Laptop chat (TetherScreen) and a channel's `thread` block
// (ChannelScreen): rows with day headers and runs, the bottom-up list, the bubble and the input bar.

/** A row of a chat: a day header, or a line with its place in a run from the same side. */
internal sealed interface ChatRow<out T> {
    val key: String
}

internal class DayRow(val label: String, override val key: String) : ChatRow<Nothing>

internal class LineRow<T>(val line: T, val first: Boolean, val last: Boolean, override val key: String) : ChatRow<T>

/** Lines from one side within this long of each other form a run (tighter, shared corners). */
private const val GROUP_GAP_MS = 3 * 60_000L

internal fun dateOf(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

internal fun dayLabel(ctx: Context, day: LocalDate, ts: Long): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> {
            var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL
            flags = flags or if (day.year == today.year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
            DateUtils.formatDateTime(ctx, ts, flags)
        }
    }
}

/**
 * [lines] (oldest first) as rows, oldest first: a day header before each new day, and each line
 * marked first/last of its run. A line that [apart] (a ping, say) never joins a run.
 */
internal fun <T> chatRows(
    ctx: Context,
    lines: List<T>,
    key: (T) -> String,
    tsMs: (T) -> Long,
    mine: (T) -> Boolean,
    apart: (T) -> Boolean = { false },
): List<ChatRow<T>> {
    fun joins(a: T?, b: T?) = a != null && b != null && mine(a) == mine(b) && !apart(a) && !apart(b) &&
        tsMs(b) - tsMs(a) < GROUP_GAP_MS && dateOf(tsMs(a)) == dateOf(tsMs(b))
    val out = ArrayList<ChatRow<T>>(lines.size + 8)
    for ((i, m) in lines.withIndex()) {
        val prev = lines.getOrNull(i - 1)
        val day = dateOf(tsMs(m))
        if (prev == null || dateOf(tsMs(prev)) != day) out += DayRow(dayLabel(ctx, day, tsMs(m)), "day-$day")
        out += LineRow(m, first = !joins(prev, m), last = !joins(m, lines.getOrNull(i + 1)), key(m))
    }
    return out
}

/**
 * [rows] (oldest first) laid out from the bottom: it opens at the newest and follows new lines
 * while near the bottom, and always after one of mine ([newestMine]). Scrolled up, an arrow
 * goes back down. [footer] sits under the newest line (a status, say).
 */
@Composable
internal fun <T> MessageList(
    rows: List<ChatRow<T>>,
    newestMine: Boolean,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    line: @Composable (LineRow<T>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState()
    // Newest first, because the list is laid out bottom-up.
    val reversed = remember(rows) { rows.asReversed() }

    val newest = rows.lastOrNull()?.key
    LaunchedEffect(newest) {
        if (newest != null && (state.firstVisibleItemIndex <= 2 || newestMine)) state.animateScrollToItem(0)
    }
    val scrolledUp by remember { derivedStateOf { state.firstVisibleItemIndex > 3 } }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (footer != null) item(key = "_footer") { footer() }
            items(reversed, key = { it.key }, contentType = { it::class }) { row ->
                Box(Modifier.animateItem()) {
                    when (row) {
                        is DayRow -> DayHeader(row.label)
                        is LineRow -> line(row)
                    }
                }
            }
        }
        AnimatedVisibility(
            scrolledUp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            SmallFloatingActionButton(onClick = { scope.launch { state.animateScrollToItem(0) } }) {
                Icon(painterResource(R.drawable.lucide_arrow_down), "Latest")
            }
        }
    }
}

@Composable
internal fun DayHeader(label: String) {
    Box(Modifier.fillMaxWidth().padding(top = Space.XL, bottom = Space.S), contentAlignment = Alignment.Center) {
        Eyebrow(label)
    }
}

/**
 * A bubble on its side (mine at the end), rounded everywhere except where it meets the next one
 * of its run, at most 80% of the screen wide (or [width] wide when given).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BubbleBox(
    mine: Boolean,
    first: Boolean,
    last: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val big = 14.dp
    val small = 4.dp
    val shape = if (mine) {
        RoundedCornerShape(big, if (first) big else small, if (last) big else small, big)
    } else {
        RoundedCornerShape(if (first) big else small, big, big, if (last) big else small)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = if (first) 8.dp else 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier.widthIn(max = bubbleMaxWidth()).clip(shape).background(color)
                .combinedClickable(
                    enabled = onClick != null || onLongClick != null,
                    onClick = { onClick?.invoke() },
                    onLongClick = onLongClick,
                ),
            content = content,
        )
    }
}

@Composable
internal fun bubbleMaxWidth() = (LocalConfiguration.current.screenWidthDp * 0.8f).dp

/**
 * Message text with the meta tucked into the end of its last line when it fits there, and on a
 * line of its own when it doesn't (or when that line is right-to-left, where the free space is on
 * the other side).
 */
@Composable
internal fun TextWithMeta(text: AnnotatedString, color: Color, style: TextStyle = MaterialTheme.typography.bodyLarge, meta: @Composable () -> Unit) {
    // Not state: the layout is read in the same measure pass that produces it.
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout(content = {
        Text(text, color = color, style = style, onTextLayout = { layout[0] = it })
        meta()
    }) { measurables, constraints ->
        val gap = 10.dp.roundToPx()
        val t = measurables[0].measure(constraints.copy(minWidth = 0))
        val mt = measurables[1].measure(Constraints())
        val l = layout[0]
        val lastLine = l?.let { it.lineCount - 1 }
        val lastRight = if (l != null && lastLine != null) l.getLineRight(lastLine).toInt() else t.width
        val rtl = l != null && lastLine != null &&
            l.getParagraphDirection(l.getLineStart(lastLine)) == ResolvedTextDirection.Rtl
        val inline = !rtl && lastRight + gap + mt.width <= constraints.maxWidth
        val w = if (inline) max(t.width, lastRight + gap + mt.width) else max(t.width, mt.width)
        val h = if (inline) max(t.height, mt.height) else t.height + mt.height
        layout(w, h) {
            t.place(0, 0)
            mt.place(w - mt.width, h - mt.height)
        }
    }
}

private val urlPattern = Regex("""\b(?:https?://|www\.)[^\s<>"]+[^\s<>".,;:!?)\]']""")

internal fun linkified(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val style = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
    for (match in urlPattern.findAll(text)) {
        val url = match.value.let { if (it.startsWith("www.")) "https://$it" else it }
        addLink(LinkAnnotation.Url(url, style), match.range.first, match.range.last + 1)
    }
}

/**
 * The box at the bottom of a chat: a growing field (📎 before it when [onAttach] is given) and
 * the send button in [accent]. It pads for nothing; the caller places it above the keyboard.
 */
@Composable
internal fun InputBar(
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Message",
    onAttach: (() -> Unit)? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    onAccent: Color = MaterialTheme.colorScheme.onPrimary,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (onAttach != null) {
                    IconButton(onClick = onAttach, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)) {
                        Icon(
                            painterResource(R.drawable.lucide_paperclip),
                            "Send a file",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.width(18.dp))
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraft,
                    textStyle = textStyle.merge(TextStyle(color = MaterialTheme.colorScheme.onSurface)),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 6,
                    modifier = Modifier.weight(1f).padding(top = 15.dp, bottom = 15.dp, end = 18.dp),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            inner()
                        }
                    },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            enabled = draft.isNotBlank(),
            onClick = onSend,
            shape = MaterialTheme.shapes.medium,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = onAccent),
            modifier = Modifier.size(52.dp),
        ) { Icon(painterResource(R.drawable.lucide_send_horizontal), "Send", Modifier.size(22.dp)) }
    }
}
