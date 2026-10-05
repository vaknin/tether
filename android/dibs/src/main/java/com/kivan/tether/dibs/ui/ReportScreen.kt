package com.kivan.tether.dibs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.MdBlock
import com.kivan.tether.dibs.MdSpan
import com.kivan.tether.dibs.markdownBlocks
import com.kivan.tether.dibs.markdownSpans
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.GeistMono
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// A task's REPORT.md as plain readable text: headings, paragraphs, bullets, code in monospace.
// dibs sends it as `report-<task>-<hash>.md` on its channel when asked (`fetch`).

@Composable
internal fun ReportScreen(id: Long, view: DibsView) {
    val t = view.task(id) ?: return Gone()
    val fetch = rememberFetch(id, "report", t.finished ?: t.ts)
    var bad by remember { mutableStateOf(false) }
    val blocks by produceState<List<MdBlock>?>(null, fetch.file) {
        val f = fetch.file ?: return@produceState
        val read = withContext(Dispatchers.IO) { runCatching { markdownBlocks(readFetched(f)) }.getOrNull() }
        bad = read == null
        if (read != null) value = read
    }
    Column(Modifier.fillMaxSize()) {
        PageBar("Report", t.label)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val b = blocks
            when {
                b != null -> SelectionContainer {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = Space.L, end = Space.L, top = Space.XS, bottom = Space.XXL),
                        verticalArrangement = Arrangement.spacedBy(Space.S),
                    ) {
                        itemsIndexed(b) { _, block -> Block(block) }
                    }
                }
                bad && !fetch.waiting -> Column(
                    Modifier.fillMaxSize().padding(horizontal = Space.XL),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("It couldn't be read", style = AppType.heading, color = Palette.Text)
                    ActButton("Get it again", "primary", Modifier.padding(top = Space.L)) { fetch.refresh() }
                }
                else -> Waiting(fetch, "report")
            }
        }
    }
}

@Composable
private fun Block(b: MdBlock) {
    when (b) {
        is MdBlock.Heading -> Text(
            inline(b.text),
            Modifier.padding(top = if (b.level <= 2) Space.M else Space.S),
            style = if (b.level == 1) AppType.heading else AppType.body.copy(fontWeight = FontWeight.W600),
            color = Palette.Text,
        )
        is MdBlock.Para -> Text(inline(b.text), style = bodyStyle, color = Palette.Text)
        is MdBlock.Item -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
            Text(b.mark, Modifier.width(22.dp), style = bodyStyle, color = Palette.Muted)
            Text(inline(b.text), Modifier.weight(1f), style = bodyStyle, color = Palette.Text)
        }
        is MdBlock.Code -> Text(
            b.text,
            Modifier.fillMaxWidth().background(Palette.SurfaceLow, MaterialTheme.shapes.small)
                .horizontalScroll(rememberScrollState()).padding(10.dp),
            style = AppType.mono,
            color = Palette.Text,
        )
        is MdBlock.Table -> Column(
            Modifier.fillMaxWidth().background(Palette.Surface, MaterialTheme.shapes.small).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            b.rows.forEachIndexed { i, cells ->
                Text(
                    inline(cells.joinToString(" · ")),
                    style = if (i == 0) AppType.small.copy(fontWeight = FontWeight.W600) else AppType.small,
                    color = if (i == 0) Palette.Muted else Palette.Text,
                )
            }
        }
        MdBlock.Rule -> Spacer(Modifier.height(Space.S))
    }
}

private val urls = Regex("""\b(?:https?://|www\.)[^\s<>"]+[^\s<>".,;:!?)\]']""")
private val mdLink = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")

/** **bold**, `code` and links ([text](url) shows its text) in a line. */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    val links = ArrayList<Pair<String, String>>()
    val plain = mdLink.replace(text) { m ->
        links += m.groupValues[1] to m.groupValues[2]
        m.groupValues[1]
    }
    val spans = markdownSpans(plain)
    for (s in spans) {
        when (s) {
            is MdSpan.Plain -> append(s.text)
            is MdSpan.Bold -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.W600))
                append(s.text)
                pop()
            }
            is MdSpan.Code -> {
                pushStyle(SpanStyle(fontFamily = GeistMono, background = Palette.SurfaceHigh))
                append(s.text)
                pop()
            }
        }
    }
    val shown = spans.joinToString("") { it.text }
    val style = TextLinkStyles(SpanStyle(color = Palette.Accent, textDecoration = TextDecoration.Underline))
    for ((label, url) in links) {
        val i = shown.indexOf(label)
        if (i >= 0 && (url.startsWith("http://") || url.startsWith("https://"))) addLink(LinkAnnotation.Url(url, style), i, i + label.length)
    }
    for (m in urls.findAll(shown)) {
        val url = m.value.let { if (it.startsWith("www.")) "https://$it" else it }
        addLink(LinkAnnotation.Url(url, style), m.range.first, m.range.last + 1)
    }
}
