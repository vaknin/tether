package com.kivan.tether.dibs.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kivan.tether.dibs.Limit
import com.kivan.tether.dibs.UsageRing
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.usageRings
import com.kivan.tether.dibs.usageStale
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/**
 * Claude's usage in the slim bar: a ring for the 5-hour window and one for the weekly, each filled by the
 * percent used with the number inside and its name under it, so the meaning never rests on colour (amber
 * from 80 % is only a second cue). A tap shows when each resets in a small card. Nothing when dibs sent
 * neither window.
 */
@Composable
internal fun UsageRings(limits: List<Limit>) {
    val ctx = LocalContext.current
    val now by rememberNow()
    val clock: (Long) -> String = { android.text.format.DateFormat.getTimeFormat(ctx).format(Date(it * 1000)) }
    val day: (Long) -> String = { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    val rings = remember(limits, now / 60) { usageRings(limits, now, clock, day) }
    val stale = remember(limits, now / 60) { usageStale(limits, now, clock) }
    if (rings.isEmpty()) return
    var card by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClickLabel = "Show when usage resets") { card = true }
                .semantics { contentDescription = "Claude usage: " + rings.joinToString("; ") { it.words } }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (r in rings) Ring(r)
        }
        if (card) UsageCard(rings, stale) { card = false }
    }
}

/** One gauge: 28 dp, a Line track and an arc from the top, clockwise, [UsageRing.pct] of the way round. */
@Composable
private fun Ring(r: UsageRing) {
    val fill = if (r.warn) Palette.Warning else Palette.Accent
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(28.dp)) {
                val w = 3.dp.toPx()
                val box = Size(size.width - w, size.height - w)
                val at = Offset(w / 2, w / 2)
                drawArc(Palette.Line, 0f, 360f, false, at, box, style = Stroke(w))
                val sweep = (360.0 * r.pct / 100.0).toFloat()
                if (sweep > 0f) {
                    drawArc(
                        fill, -90f, sweep, false, at, box,
                        style = Stroke(w, cap = if (sweep < 360f) StrokeCap.Round else StrokeCap.Butt),
                    )
                }
            }
            Text(r.number.toString(), style = AppType.mono.copy(fontSize = 10.sp, lineHeight = 12.sp), color = Palette.Text, maxLines = 1, softWrap = false)
        }
        Text(r.label, style = AppType.small.copy(fontSize = 10.sp, lineHeight = 12.sp), color = Palette.Muted, maxLines = 1, softWrap = false)
    }
}

/** The tap's card under the rings, right-aligned and kept on screen: each window's words, then how old the numbers are. */
@Composable
private fun UsageCard(rings: List<UsageRing>, stale: String?, onDismiss: () -> Unit) {
    val where = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
                return IntOffset(x, anchorBounds.bottom)
            }
        }
    }
    Popup(popupPositionProvider = where, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.testTag("usage-card").padding(horizontal = 8.dp)
                .background(Palette.SurfaceHigh, MaterialTheme.shapes.large).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Claude usage", style = AppType.label, color = Palette.Text)
            for (r in rings) {
                Text(wrapAfterColon(r.words), style = AppType.small, color = if (r.warn) Palette.Warning else Palette.Text)
            }
            if (stale != null) Text(stale, style = AppType.small, color = Palette.Muted)
        }
    }
}

/** "Weekly: 63% used, resets Thu 09:00" with its parts held together, so a narrow card breaks only after the comma. */
private fun wrapAfterColon(words: String): String {
    val head = words.substringBefore(": ")
    val rest = words.substringAfter(": ").split(", ").joinToString(", ") { it.replace(' ', NBSP) }
    return "$head: $rest"
}
