package com.kivan.tether.dibs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.kivan.tether.dibs.Pick
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.RefIndex
import com.kivan.tether.dibs.pick
import com.kivan.tether.dibs.ideaWords
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.RefKind
import com.kivan.tether.dibs.RefLink
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space

// The tasks and ideas a task or idea is linked to (docs/DIBS-APP.md, "Links"): a titled list of
// rows, each `#230` or `idea 45` in mono, its title, and "why · state" muted under it; a tap opens it.

/** "#230" for a task, "idea 45" for an idea. */
internal fun refLabel(kind: RefKind, n: Int): String = if (kind == RefKind.TASK) "#$n" else "idea $n"

/** [links] under the heading [title]; nothing when there are none. */
@Composable
internal fun RefSection(title: String, links: List<RefLink>) {
    if (links.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Section(title)
        for (l in links) RefRow(l)
    }
}

@Composable
internal fun RefRow(l: RefLink) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { Dibs.openRef(l.kind, l.n) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Text(refLabel(l.kind, l.n), Modifier.widthIn(min = 56.dp), style = AppType.mono, color = Palette.Muted)
        Column(Modifier.weight(1f)) {
            Text(l.title.ifBlank { refLabel(l.kind, l.n) }, style = AppType.body, color = Palette.Text)
            val sub = listOf(l.why, l.state).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = AppType.small, color = Palette.Muted)
        }
    }
}

/** The dot's colour for a task's group (or an idea's state): needs warns, working is the accent, done is success, stopped is danger, the rest muted. */
internal fun groupColor(group: String): Color = when (group) {
    "needs" -> Palette.Warning
    "working", "active" -> Palette.Accent
    "done" -> Palette.Success
    "stopped" -> Palette.Danger
    else -> Palette.Muted
}

/** What the card and the picker show of a task or idea: its title, state in words, group, project. Null when the index doesn't know it. */
internal fun refInfo(index: RefIndex?, kind: RefKind, n: Int): Pick? {
    if (index == null) return null
    return if (kind == RefKind.TASK) index.task(n)?.let { Pick(kind, n, it.t, it.s, it.g, it.p) }
    else index.idea(n)?.let { Pick(kind, n, it.t, ideaWords(it.s), it.s, "") }
}

/** The card of a long press on a link: `#230 · project`, the title, the state with a dot, and Open. */
@Composable
internal fun RefCard(kind: RefKind, n: Int, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val info = refInfo(Dibs.index, kind, n)
    Column(
        Modifier.widthIn(max = 280.dp).shadow(8.dp, MaterialTheme.shapes.medium)
            .background(Palette.SurfaceHighest, MaterialTheme.shapes.medium).padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val head = listOf(refLabel(kind, n), info?.project.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
        Text(head, style = AppType.mono, color = Palette.Muted)
        Text(info?.title?.ifBlank { null } ?: refLabel(kind, n), style = AppType.body, color = Palette.Text)
        if (info != null && info.state.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(groupColor(info.group), CircleShape))
            Text(info.state, style = AppType.small, color = Palette.Muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpen, shape = MaterialTheme.shapes.medium) { Text("Open", style = AppType.label, color = Palette.Accent) }
        }
    }
}

/**
 * The card as a popup just below [at] (the press, in the text's own pixels; above it when there is no room);
 * dismissed by a tap outside or back, and by Open.
 */
@Composable
internal fun RefCardPopup(kind: RefKind, n: Int, at: IntOffset, onDismiss: () -> Unit) {
    val gap = with(LocalDensity.current) { 28.dp.roundToPx() }
    val where = remember(at, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (anchorBounds.left + at.x - popupContentSize.width / 2).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
                val below = anchorBounds.top + at.y + gap
                val y = if (below + popupContentSize.height <= windowSize.height) below else maxOf(0, anchorBounds.top + at.y - gap - popupContentSize.height)
                return IntOffset(x, y)
            }
        }
    }
    Popup(popupPositionProvider = where, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        RefCard(kind, n, onOpen = {
            onDismiss()
            Dibs.openRef(kind, n)
        }, onDismiss = onDismiss)
    }
}

/** The `#` picker's list: up to [PICK_MAX] rows, or one muted row when nothing matches; × closes it. */
@Composable
internal fun RefPicker(index: RefIndex, q: String, onPick: (Pick) -> Unit, onClose: () -> Unit) {
    val rows = remember(index, q) { pick(index, q).take(PICK_MAX) }
    Box(Modifier.fillMaxWidth().background(Palette.SurfaceHigh, MaterialTheme.shapes.large)) {
        if (rows.isEmpty()) {
            Text("No task or idea matches", Modifier.fillMaxWidth().padding(start = 14.dp, end = 44.dp, top = 12.dp, bottom = 12.dp), style = AppType.small, color = Palette.Muted)
        } else LazyColumn(Modifier.heightIn(max = 288.dp).testTag("ref-picker"), contentPadding = PaddingValues(end = 36.dp, top = 4.dp, bottom = 4.dp)) {
            items(rows, key = { "${it.kind}${it.n}" }) { p ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onPick(p) }.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.S),
                ) {
                    Text(refLabel(p.kind, p.n), Modifier.widthIn(min = 56.dp), style = AppType.mono, color = Palette.Muted)
                    Text(p.title.ifBlank { refLabel(p.kind, p.n) }, Modifier.weight(1f), style = AppType.body, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.state, style = AppType.small, color = Palette.Muted, maxLines = 1)
                }
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).size(36.dp)) {
            Icon(painterResource(R.drawable.lucide_x), "Close the list", Modifier.size(16.dp), tint = Palette.Muted)
        }
    }
}

private const val PICK_MAX = 30
