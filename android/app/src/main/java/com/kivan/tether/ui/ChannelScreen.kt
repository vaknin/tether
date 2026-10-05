package com.kivan.tether.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kivan.tether.ChannelInfo
import com.kivan.tether.ChannelLook
import com.kivan.tether.Channels
import com.kivan.tether.Core
import com.kivan.tether.Dir
import com.kivan.tether.R
import com.kivan.tether.Shortcuts
import com.kivan.tether.core.AppHistoryItem
import com.kivan.tether.ui.theme.AppType
import com.kivan.tether.ui.theme.Eyebrow
import com.kivan.tether.ui.theme.GeistMono
import com.kivan.tether.ui.theme.Palette
import com.kivan.tether.ui.theme.Pill
import com.kivan.tether.ui.theme.Space
import com.kivan.tether.textRtl
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

// --- The list (root screen) -----------------------------------------------------------------------

/** The root screen: the chat, then each app channel with its badge. A long press pins one. */
@Composable
internal fun ChannelList(peerName: String, queued: ULong) {
    val list by Channels.list.collectAsState()
    val views by Channels.views.collectAsState()
    val threads by Channels.threads.collectAsState()
    val messages by Core.messages.collectAsState()
    val status by Core.status.collectAsState()

    Column(Modifier.fillMaxSize()) {
        PeerBar(peerName, queued, onBack = null)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = Space.S)) {
            item(key = Channels.CHAT) {
                val last = messages.lastOrNull()
                EntryRow(
                    icon = { Avatar(R.drawable.lucide_laptop, 48.dp) },
                    title = "Chat",
                    line = last?.let { it.text ?: it.fileName?.let { f -> "📎 $f" } } ?: "Messages, files and pings",
                    badge = status?.unread?.toInt() ?: 0,
                    onClick = { Channels.show(Channels.CHAT) },
                    menu = null,
                )
            }
            if (list.isNotEmpty()) item(key = "_eyebrow") {
                Eyebrow("Channels", Modifier.padding(start = Space.L, end = Space.L, top = Space.XL, bottom = Space.XS))
            }
            items(list, key = { it.name }) { c ->
                val v = views[c.name]
                val line = if (c.thread) threadLine(threads[c.name]) else headerOf(v)?.optString("subtitle")
                EntryRow(
                    icon = { Glyph(c, 48) },
                    title = c.title,
                    line = line?.takeIf { it.isNotBlank() } ?: if (c.thread) "Thread" else "",
                    badge = v?.optInt("badge") ?: 0,
                    rtl = c.dir == Dir.RTL,
                    onClick = { Channels.show(c.name) },
                    menu = c,
                )
            }
            if (list.isEmpty()) item {
                Text(
                    "No app channels yet. A manifest in ~/.config/tether/apps on the laptop adds one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
                )
            }
        }
    }
}

private fun threadLine(items: List<AppHistoryItem>?): String? =
    items?.lastOrNull()?.let { postText(parse(it.data)) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    icon: @Composable () -> Unit,
    title: String,
    line: String,
    badge: Int,
    onClick: () -> Unit,
    menu: ChannelInfo?,
    rtl: Boolean = false,
) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { if (menu != null) open = true })
                .padding(horizontal = Space.L, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = AppType.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (line.isNotEmpty()) {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (badge > 0) {
                Spacer(Modifier.width(10.dp))
                Badge(containerColor = Palette.Accent, contentColor = Palette.OnAccent) { Text("$badge", style = AppType.small.copy(fontFeatureSettings = "tnum")) }
            }
        }
        if (menu != null) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("Add to home screen") },
                    onClick = {
                        open = false
                        if (!Shortcuts.pin(ctx, menu)) Toast.makeText(ctx, "The launcher can't pin shortcuts", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}

/** A channel's tile: its white Lucide mark on its hue's tile (docs/DESIGN.md), or the old glyph. */
@Composable
internal fun Glyph(c: ChannelInfo, size: Int) {
    val mark = c.icon
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(ChannelLook.background(c))),
        contentAlignment = Alignment.Center,
    ) {
        if (mark != null) {
            val fg = ChannelLook.foreground(c)
            Canvas(Modifier.size((size / 2).dp)) {
                drawIntoCanvas { ChannelLook.drawMark(it.nativeCanvas, mark, 0f, 0f, this.size.minDimension, fg) }
            }
        } else {
            Text(
                c.glyph,
                color = Color(ChannelLook.foreground(c)),
                fontWeight = FontWeight.Bold,
                style = if (size >= 40) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
internal fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.lucide_arrow_left), "Back") }
}

// --- A channel ------------------------------------------------------------------------------------

/** What the blocks need besides their own JSON. */
private class Ctx(
    val name: String,
    val accent: Color,
    /** Text and icons on [accent]. */
    val onAccent: Color,
    val armed: String?,
    /** `dir = "auto"`: each text and list row takes its own direction. */
    val auto: Boolean,
    /** A second press within 4 s confirms: arms [key], or sends when it is already armed. */
    val press: (key: String, confirm: Boolean, obj: JSONObject) -> Unit,
)

private val LocalCh = compositionLocalOf<Ctx> { error("no channel") }

/** One channel: its view's blocks (or a thread), drawn in the channel's direction. */
@Composable
internal fun ChannelScreen(name: String) {
    val list by Channels.list.collectAsState()
    val views by Channels.views.collectAsState()
    val threads by Channels.threads.collectAsState()
    val connected by Core.connected.collectAsState()
    val c = list.firstOrNull { it.name == name }
        ?: ChannelInfo(name, name, name.take(1), null, dir = Dir.AUTO, thread = false, share = false, notify = false)
    val appCtx = LocalContext.current
    val blocks = remember(c, views[name], threads[name]) {
        if (c.thread) threadBlocks(appCtx, threads[name].orEmpty()) else blocksOf(views[name])
    }
    val header = blocks.firstOrNull { it.optString("type") == "header" }

    var armed by remember(name) { mutableStateOf<String?>(null) }
    LaunchedEffect(armed) {
        if (armed != null) {
            delay(4_000)
            armed = null
        }
    }
    val accent = c.accent?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val onAccent = c.onAccent?.let { Color(it) } ?: Palette.OnAccent
    val ctx = Ctx(name, accent, onAccent, armed, c.dir == Dir.AUTO) { key, confirm, obj ->
        if (confirm && armed != key) {
            armed = key
        } else {
            armed = null
            Channels.act(name, obj)
        }
    }

    // Text shared to this channel's tile goes into its first compose box.
    val shared = Channels.shared[name]
    LaunchedEffect(shared, blocks) {
        if (shared == null) return@LaunchedEffect
        val compose = blocks.firstOrNull { it.optString("type") == "compose" } ?: return@LaunchedEffect
        val key = "$name/${compose.optString("id")}/text"
        Channels.drafts[key] = listOfNotNull(Channels.drafts[key]?.takeIf { it.isNotBlank() }, shared).joinToString("\n")
        Channels.shared.remove(name)
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides if (c.dir == Dir.RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
        LocalCh provides ctx,
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            ChannelBar(
                c = c,
                title = header?.optString("title")?.takeIf { it.isNotBlank() } ?: c.title,
                subtitle = header?.optString("subtitle")?.takeIf { it.isNotBlank() }
                    ?: if (connected) "" else "Laptop offline · actions wait in the queue",
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(Space.L),
                verticalArrangement = Arrangement.spacedBy(Space.M),
            ) {
                val body = blocks.filter { it.optString("type") != "header" }
                itemsIndexed(body, key = { i, b -> b.optString("id").ifEmpty { "#$i" } }) { _, b -> Block(b) }
                if (body.isEmpty()) item {
                    Text(
                        "Nothing here yet: the app hasn't published a view.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    )
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

@Composable
private fun ChannelBar(c: ChannelInfo, title: String, subtitle: String) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Surface(color = Palette.Bg) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(68.dp).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton { Channels.show(null) }
            Glyph(c, 40)
            Spacer(Modifier.width(Space.M))
            Column(Modifier.weight(1f)) {
                Text(title, style = AppType.heading.auto(title), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall.auto(subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.lucide_ellipsis_vertical), "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Add to home screen") },
                        onClick = {
                            menu = false
                            if (!Shortcuts.pin(ctx, c)) Toast.makeText(ctx, "The launcher can't pin shortcuts", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }
}

// --- Blocks (docs/PLAN.md, "Blocks"; Channel.qml is the panel's twin) ----------------------------

/** In an `auto` channel, [s] in its own direction and aligned to its side; else the screen's. */
@Composable
private fun TextStyle.auto(s: String): TextStyle =
    if (!LocalCh.current.auto) this
    else merge(TextStyle(textDirection = TextDirection.Content, textAlign = if (textRtl(s)) TextAlign.Right else TextAlign.Left))

/** In an `auto` channel, lays out [content] (a list row) in the direction of [text]. */
@Composable
private fun RowDir(text: String, content: @Composable () -> Unit) {
    if (!LocalCh.current.auto) return content()
    CompositionLocalProvider(LocalLayoutDirection provides if (textRtl(text)) LayoutDirection.Rtl else LayoutDirection.Ltr, content = content)
}

@Composable
private fun Block(b: JSONObject) {
    when (b.optString("type")) {
        "notice" -> Notice(b)
        "text" -> TextBlock(b)
        "list" -> ListBlock(b)
        "checklist" -> Checklist(b)
        "compose" -> Compose(b)
        "form" -> Form(b)
        "progress" -> Progress(b)
        "buttons" -> Buttons(b)
        else -> {} // unknown types are skipped
    }
}

private fun tone(t: String, accent: Color): Color = when (t) {
    "error" -> Palette.Danger
    "warn" -> Palette.Warning
    "ok" -> Palette.Success
    else -> accent
}

@Composable
private fun Notice(b: JSONObject) {
    val color = tone(b.optString("tone"), LocalCh.current.accent)
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(b.optString("text"), style = MaterialTheme.typography.bodyMedium.auto(b.optString("text")), modifier = Modifier.fillMaxWidth().padding(12.dp))
    }
}

@Composable
private fun TextBlock(b: JSONObject) {
    val context = LocalContext.current
    val text = b.optString("text")
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Box {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium.auto(text),
                    fontFamily = if (b.optBoolean("mono")) GeistMono else null,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 40.dp),
                )
            }
            IconButton(onClick = { copy(context, text) }, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(painterResource(R.drawable.lucide_copy), "Copy", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", text))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListBlock(b: JSONObject) {
    val ch = LocalCh.current
    val items = objects(b.optJSONArray("items"))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty() && b.optString("empty").isNotEmpty()) {
            Text(b.optString("empty"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        for (it in items) {
            val id = it.optString("id")
            RowDir("${it.optString("title")}\n${it.optString("text")}") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Space.M), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val chips = strings(it.optJSONArray("chips"))
                        val meta = it.optString("meta")
                        val title = it.optString("title")
                        if (meta.isNotEmpty() || title.isNotEmpty() || chips.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (meta.isNotEmpty()) Text(meta, style = AppType.mono, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                                if (title.isNotEmpty()) Text(title, style = AppType.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterVertically))
                                for (chip in chips) Tag(chip, ch.accent)
                            }
                        }
                        if (it.optString("text").isNotEmpty()) Text(it.optString("text"), style = MaterialTheme.typography.bodyMedium)
                        val details = it.optString("details")
                        if (details.isNotEmpty()) Details(id, details)
                        val actions = objects(it.optJSONArray("actions"))
                        // A thread post's buttons answer with that action.
                        val buttons = objects(it.optJSONArray("buttons"))
                        if (actions.isNotEmpty() || buttons.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ItemActions(b.optString("id"), id, actions + buttons) }
                        }
                        it.optJSONObject("reply")?.let { r -> ItemReply(b.optString("id"), id, r) }
                    }
                }
            }
        }
    }
}

/** A list item's `details`: a small toggle under its text; open or shut survives view reloads. */
@Composable
private fun Details(item: String, details: String) {
    val ch = LocalCh.current
    val key = "${ch.name}/$item"
    val open = Channels.expanded[key] == true
    Text(
        if (open) "Details ▴" else "Details ▾",
        style = MaterialTheme.typography.labelMedium,
        color = ch.accent,
        modifier = Modifier.clip(MaterialTheme.shapes.small).clickable { Channels.expanded[key] = !open }.padding(vertical = 2.dp),
    )
    if (open) {
        SelectionContainer {
            Text(
                details,
                style = MaterialTheme.typography.bodySmall.auto(details),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A list item's `reply` box (a free-text answer): one line and a button, sending
 * `{"action":<reply id>,"value":{"item","text"}}`. Sent texts wait under it (⏳) until a view
 * lists their uid or drops the item.
 */
@Composable
private fun ItemReply(block: String, item: String, r: JSONObject) {
    val ch = LocalCh.current
    val key = "${ch.name}/$block/$item/reply"
    val pending by Channels.pending.collectAsState()
    val text = Channels.drafts[key].orEmpty()
    val placeholder = r.optString("placeholder").ifEmpty { "Answer…" }
    val submit = {
        val t = text.trim()
        if (t.isNotEmpty()) {
            Channels.reply(ch.name, block, item, r.optString("id"), t)
            Channels.drafts.remove(key)
        }
    }
    for (p in pending["${ch.name}/$block/$item"].orEmpty()) {
        Text("⏳ ${p.text}", style = MaterialTheme.typography.bodyMedium.auto(p.text), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { Channels.drafts[key] = it },
            placeholder = { Text(placeholder, style = LocalTextStyle.current.auto(placeholder)) },
            textStyle = LocalTextStyle.current.auto(text),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ch.accent, cursorColor = ch.accent),
            modifier = Modifier.weight(1f),
        )
        ActionButton(r.optString("submit").ifEmpty { "Send" }, "primary", small = true, enabled = text.isNotBlank(), onClick = submit)
    }
}

/** An item's action buttons: each sends `{"action":<id>,"value":{"item":<item id>}}`. */
@Composable
private fun ItemActions(block: String, item: String, actions: List<JSONObject>) {
    val ch = LocalCh.current
    for (a in actions) {
        val key = "$block/$item/${a.optString("id")}"
        val confirm = confirmOf(a)
        val hot = ch.armed == key
        ActionButton(
            label = if (hot && confirm != null) confirm else a.optString("label"),
            style = if (hot) "danger" else "plain",
            small = true,
        ) {
            ch.press(key, confirm != null, JSONObject().put("action", a.optString("id")).put("value", JSONObject().put("item", item)))
        }
    }
}

/** An action's `confirm`: the label for the second press, or `true` (the label stays, in red). */
private fun confirmOf(a: JSONObject): String? {
    val c = a.opt("confirm")
    return when {
        c is String -> c.ifEmpty { null }
        c == true -> a.optString("label")
        else -> null
    }
}

@Composable
private fun Tag(text: String, accent: Color) {
    Text(
        text,
        style = AppType.small,
        modifier = Modifier.clip(Pill).background(accent.copy(alpha = 0.3f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun Checklist(b: JSONObject) {
    val ch = LocalCh.current
    Column {
        for (it in objects(b.optJSONArray("items"))) {
            val checked = it.optBoolean("checked")
            val toggle = {
                Channels.act(ch.name, JSONObject().put("action", b.optString("id")).put("value", JSONObject().put("item", it.optString("id")).put("checked", !checked)))
                Unit
            }
            val actions = objects(it.optJSONArray("actions"))
            // Controls stay put (box left, actions right); only the label's text takes its own side.
            run {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = toggle).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { toggle() },
                        colors = CheckboxDefaults.colors(checkedColor = ch.accent, checkmarkColor = ch.onAccent),
                    )
                    Text(
                        it.optString("label"),
                        style = MaterialTheme.typography.bodyLarge.auto(it.optString("label")),
                        color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (actions.isNotEmpty()) {
                        Row(Modifier.padding(start = 8.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ItemActions(b.optString("id"), it.optString("id"), actions)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Compose(b: JSONObject) {
    val ch = LocalCh.current
    val id = b.optString("id")
    val key = "${ch.name}/$id/text"
    val chipKey = "${ch.name}/$id"
    val pending by Channels.pending.collectAsState()
    val text = Channels.drafts[key].orEmpty()
    val chosen = Channels.chips[chipKey].orEmpty()
    val submit = {
        val t = text.trim()
        if (t.isNotEmpty()) {
            Channels.compose(ch.name, id, t)
            Channels.drafts.remove(key)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Sent, not yet in a view.
        for (p in pending["${ch.name}/$id"].orEmpty()) {
            Text("⏳ ${p.text}", style = MaterialTheme.typography.bodyMedium.auto(p.text), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        }
        val chips = objects(b.optJSONArray("chips"))
        if (chips.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in chips) {
                    val cid = c.optString("id")
                    val on = cid in chosen
                    FilterChip(
                        selected = on,
                        onClick = {
                            Channels.chips[chipKey] = when {
                                on -> chosen - cid
                                b.has("multi") && !b.optBoolean("multi") -> listOf(cid)
                                else -> chosen + cid
                            }
                        },
                        label = { Text(c.optString("label")) },
                        shape = Pill,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ch.accent.copy(alpha = 0.35f)),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { Channels.drafts[key] = it },
                placeholder = { Text(b.optString("placeholder"), style = LocalTextStyle.current.auto(b.optString("placeholder"))) },
                textStyle = LocalTextStyle.current.auto(text),
                maxLines = 6,
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ch.accent, cursorColor = ch.accent),
                modifier = Modifier.weight(1f),
            )
            ActionButton(b.optString("submit").ifEmpty { "Send" }, "primary", enabled = text.isNotBlank(), onClick = submit)
        }
    }
}

@Composable
private fun Form(b: JSONObject) {
    val ch = LocalCh.current
    val id = b.optString("id")
    val fields = objects(b.optJSONArray("fields"))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in fields) {
            val key = "${ch.name}/$id/${f.optString("id")}"
            OutlinedTextField(
                value = Channels.drafts[key] ?: f.optString("value"),
                onValueChange = { Channels.drafts[key] = it },
                label = { Text(f.optString("label")) },
                placeholder = { Text(f.optString("placeholder")) },
                singleLine = !f.optBoolean("multi"),
                minLines = if (f.optBoolean("multi")) 3 else 1,
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ch.accent, focusedLabelColor = ch.accent, cursorColor = ch.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ActionButton(b.optString("submit").ifEmpty { "Send" }, "primary") {
            val out = JSONObject()
            for (f in fields) {
                val fid = f.optString("id")
                out.put(fid, Channels.drafts["${ch.name}/$id/$fid"] ?: f.optString("value"))
            }
            Channels.act(ch.name, JSONObject().put("action", id).put("fields", out))
            Channels.drafts.keys.filter { it.startsWith("${ch.name}/$id/") }.forEach { Channels.drafts.remove(it) }
        }
    }
}

@Composable
private fun Progress(b: JSONObject) {
    val ch = LocalCh.current
    Surface(color = ch.accent.copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = Space.M, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), color = ch.accent, strokeWidth = 2.5.dp)
            Spacer(Modifier.width(12.dp))
            Text(b.optString("text"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            b.optJSONObject("cancel")?.let { c ->
                Spacer(Modifier.width(8.dp))
                ActionButton(c.optString("label"), "plain", small = true) {
                    Channels.act(ch.name, JSONObject().put("action", c.optString("id")))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(b: JSONObject) {
    val ch = LocalCh.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (it in objects(b.optJSONArray("items"))) {
            val key = "btn/${it.optString("id")}"
            val confirm = confirmOf(it)
            val hot = ch.armed == key
            ActionButton(
                label = if (hot && confirm != null) confirm else it.optString("label"),
                style = if (hot) "danger" else it.optString("style").ifEmpty { "plain" },
            ) { ch.press(key, confirm != null, JSONObject().put("action", it.optString("id"))) }
        }
    }
}

/** primary: the channel's accent; danger: red; plain: outlined. */
@Composable
private fun ActionButton(label: String, style: String, small: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = LocalCh.current.accent
    val onAccent = LocalCh.current.onAccent
    val pad = if (small) PaddingValues(horizontal = 12.dp, vertical = 4.dp) else ButtonDefaults.ContentPadding
    val mod = if (small) Modifier.height(34.dp) else Modifier
    when (style) {
        "primary", "danger" -> Button(
            onClick = onClick,
            enabled = enabled,
            contentPadding = pad,
            modifier = mod,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (style == "danger") Palette.Danger else accent,
                contentColor = if (style == "danger") Palette.OnDanger else onAccent,
            ),
        ) { Text(label, fontWeight = FontWeight.SemiBold) }
        else -> OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = pad, modifier = mod, shape = MaterialTheme.shapes.medium) {
            Text(label, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// --- JSON -----------------------------------------------------------------------------------------

private fun parse(s: String): JSONObject? = runCatching { JSONObject(s) }.getOrNull()

private fun objects(a: JSONArray?): List<JSONObject> =
    if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

private fun strings(a: JSONArray?): List<String> =
    if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

private fun blocksOf(view: JSONObject?): List<JSONObject> = objects(view?.optJSONArray("blocks"))

private fun headerOf(view: JSONObject?): JSONObject? = blocksOf(view).firstOrNull { it.optString("type") == "header" }

private fun postText(d: JSONObject?): String {
    if (d == null) return ""
    val p = d.optJSONObject("post") ?: d
    return p.optString("text").ifEmpty { d.optString("action").takeIf { it.isNotEmpty() }?.let { "↳ $it" } ?: "" }
}

/** A thread's items as a list (with a post's buttons) and a reply box, as the panel shows it. */
private fun threadBlocks(ctx: android.content.Context, items: List<AppHistoryItem>): List<JSONObject> {
    val list = JSONArray()
    for (it in items) {
        val d = parse(it.data)
        val time = DateUtils.formatDateTime(ctx, it.tsMs, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE)
        list.put(
            JSONObject()
                .put("id", it.id)
                .put("title", d?.optJSONObject("post")?.optString("title").orEmpty())
                .put("text", postText(d))
                .put("meta", (if (it.fromMe) "" else "↩ ") + time)
                .put("buttons", d?.optJSONObject("post")?.optJSONArray("actions") ?: JSONArray()),
        )
    }
    return listOf(
        JSONObject().put("type", "list").put("id", "_thread").put("items", list).put("empty", "No posts yet."),
        JSONObject().put("type", "compose").put("id", "_reply").put("placeholder", "Reply…").put("submit", "Send"),
    )
}
