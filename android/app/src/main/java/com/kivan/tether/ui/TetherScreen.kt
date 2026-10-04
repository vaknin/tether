package com.kivan.tether.ui

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.kivan.tether.Channels
import com.kivan.tether.Core
import com.kivan.tether.Downloads
import com.kivan.tether.Outgoing
import com.kivan.tether.PhoneListener
import com.kivan.tether.R
import com.kivan.tether.Thumbs
import com.kivan.tether.core.ChatMessage
import com.kivan.tether.core.MsgKind
import com.kivan.tether.core.MsgState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.math.max

@Composable
fun TetherScreen() {
    val status by Core.status.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        val s = status
        when {
            s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            s.peer == null -> PairScreen()
            else -> Home(s.peer!!.name, s.queued)
        }
    }
}

@Composable
private fun PairScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pair(c: String) {
        busy = true
        error = null
        scope.launch {
            try {
                Core.withNode { it.pair(c.trim()) }
                // Pairing swaps this screen for the chat, which cancels `scope`; the dial must outlive it.
                Core.scope.launch { Core.connect() }
            } catch (e: Exception) {
                error = e.message ?: "Pairing failed"
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(R.drawable.ic_laptop, 96.dp)
        Spacer(Modifier.height(4.dp))
        Text("Link your laptop", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Run tether pair on the laptop and scan the QR code it shows. The code works once, for 5 minutes.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            onClick = {
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(ctx, options).startScan()
                    .addOnSuccessListener { b -> b.rawValue?.let(::pair) }
                    .addOnFailureListener { error = it.message }
            },
        ) {
            Icon(painterResource(R.drawable.ic_qr), null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Scan QR code", style = MaterialTheme.typography.titleMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Text(
                "or paste the code",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            HorizontalDivider(Modifier.weight(1f))
        }
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            placeholder = { Text("tether:1:…") },
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            enabled = !busy && code.startsWith("tether:"),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            onClick = { pair(code) },
        ) { Text("Pair") }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
    }
}

/** The paired app: the channel list, or the screen it opened (the chat, or a channel). */
@Composable
private fun Home(peerName: String, queued: ULong) {
    val open by Channels.open.collectAsState()
    when (val o = open) {
        null -> ChannelList(peerName, queued)
        Channels.CHAT -> ChatScreen(peerName, queued)
        else -> ChannelScreen(o)
    }
    if (open != null) BackHandler { Channels.show(null) }
}

/**
 * The top bar with the laptop's state and the ⋮ menu (setup, unpair), plus the sheet and dialog
 * they open. [onBack] adds a back arrow (the chat); the list has none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeerBar(peerName: String, queued: ULong, onBack: (() -> Unit)?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected by Core.connected.collectAsState()
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }

    // Grants happen in Settings, so re-check whenever the app comes back.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose {}
    }
    val granted = remember(resumed) { setupItems.map { it.granted(ctx) } }

    TopBar(
        peerName = peerName,
        connected = connected,
        connecting = connecting,
        queued = queued,
        setupMissing = granted.count { !it },
        onBack = onBack,
        onRetry = {
            connecting = true
            scope.launch {
                Core.connect()
                connecting = false
            }
        },
        onSetup = { setupOpen = true },
        onUnpair = { confirmUnpair = true },
    )
    val refused by Core.refused.collectAsState()
    if (refused) RefusedBanner(onUnpair = { confirmUnpair = true })

    if (setupOpen) {
        ModalBottomSheet(onDismissRequest = { setupOpen = false }) { SetupSheet(granted) }
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            icon = { Icon(painterResource(R.drawable.ic_link_off), null) },
            title = { Text("Unpair from $peerName?") },
            text = { Text("To link again, run tether pair on the laptop and scan the new code.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnpair = false
                        scope.launch { Core.withNode { it.unpair() } }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
}

/** The laptop refused the link (it unpaired, or paired with another phone): re-pairing is the fix. */
@Composable
private fun RefusedBanner(onUnpair: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "The laptop doesn't recognise this phone. Unpair, then pair again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUnpair) { Text("Unpair") }
        }
    }
}

@Composable
private fun ChatScreen(peerName: String, queued: ULong) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by Core.messages.collectAsState()
    val progress by Core.progress.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.map { Outgoing.copyIn(ctx, it) } }
            Core.withNode { n ->
                for (f in files) {
                    val m = n.sendFile(f.path)
                    withContext(Dispatchers.IO) { Thumbs.save(ctx, m.id, f) }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PeerBar(peerName, queued, onBack = { Channels.show(null) })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) EmptyChat(peerName) else MessageList(messages, progress)
        }
        InputBar(
            draft = draft,
            onDraft = { draft = it },
            onAttach = { pick.launch("*/*") },
            onSend = {
                val text = draft.trim()
                draft = ""
                scope.launch { Core.withNode { it.sendText(text) } }
            },
        )
    }
}

@Composable
private fun TopBar(
    peerName: String,
    connected: Boolean,
    connecting: Boolean,
    queued: ULong,
    setupMissing: Int,
    onBack: (() -> Unit)?,
    onRetry: () -> Unit,
    onSetup: () -> Unit,
    onUnpair: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val online = Gruvbox.green
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(68.dp).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let { BackButton(it) }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = !connected && !connecting, onClick = onRetry)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Avatar(R.drawable.ic_laptop, 44.dp)
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer).padding(2.5.dp).clip(CircleShape)
                            .background(if (connected) online else MaterialTheme.colorScheme.outline),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(peerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val line = when {
                        connected -> "Connected"
                        connecting -> "Connecting…"
                        else -> "Offline · tap to reconnect"
                    } + if (queued > 0uL) " · $queued waiting" else ""
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (connected) online else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    BadgedBox(badge = { if (setupMissing > 0) Badge() }) {
                        Icon(painterResource(R.drawable.ic_more), "More")
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (setupMissing > 0) "Finish phone setup ($setupMissing)" else "Phone setup") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_tune), null) },
                        onClick = {
                            menu = false
                            onSetup()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Unpair") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_link_off), null) },
                        onClick = {
                            menu = false
                            onUnpair()
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun Avatar(icon: Int, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
private fun EmptyChat(peerName: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(R.drawable.ic_laptop, 72.dp)
        Text("Say hi to $peerName", style = MaterialTheme.typography.titleLarge)
        Text(
            "Messages and files wait here until the laptop is reachable, then go through on their own.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A row of the chat: a day header, or a message with its place in a run from the same sender. */
private sealed interface ChatRow {
    val key: String
}

private class DayRow(val label: String, override val key: String) : ChatRow
private class MsgRow(val m: ChatMessage, val first: Boolean, val last: Boolean) : ChatRow {
    override val key get() = m.id
}

private const val GROUP_GAP_MS = 3 * 60_000L

private fun dateOf(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

private fun isEvent(m: ChatMessage) = m.kind == MsgKind.PING || m.kind == MsgKind.RING

private fun joins(a: ChatMessage?, b: ChatMessage?) =
    a != null && b != null && a.fromMe == b.fromMe && !isEvent(a) && !isEvent(b) &&
        b.tsMs - a.tsMs < GROUP_GAP_MS && dateOf(a.tsMs) == dateOf(b.tsMs)

private fun dayLabel(ctx: Context, day: LocalDate, ts: Long): String {
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

private fun rowsOf(ctx: Context, messages: List<ChatMessage>): List<ChatRow> {
    val out = ArrayList<ChatRow>(messages.size + 8)
    for ((i, m) in messages.withIndex()) {
        val prev = messages.getOrNull(i - 1)
        val day = dateOf(m.tsMs)
        if (prev == null || dateOf(prev.tsMs) != day) out += DayRow(dayLabel(ctx, day, m.tsMs), "day-$day")
        out += MsgRow(m, first = !joins(prev, m), last = !joins(m, messages.getOrNull(i + 1)))
    }
    return out
}

@Composable
private fun MessageList(messages: List<ChatMessage>, progress: Map<String, Pair<Long, Long>>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState()
    // Newest first, because the list is laid out bottom-up.
    val rows = remember(messages) { rowsOf(ctx, messages).asReversed() }

    // Follow new messages while at the bottom, and always after sending one.
    val last = messages.lastOrNull()
    LaunchedEffect(last?.id) {
        if (last != null && (state.firstVisibleItemIndex <= 2 || last.fromMe)) state.animateScrollToItem(0)
    }
    val scrolledUp by remember { derivedStateOf { state.firstVisibleItemIndex > 3 } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(rows, key = { it.key }, contentType = { it::class }) { row ->
                Box(Modifier.animateItem()) {
                    when (row) {
                        is DayRow -> DayHeader(row.label)
                        is MsgRow -> if (isEvent(row.m)) EventChip(row.m) else Bubble(row, progress[row.m.id])
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
                Icon(painterResource(R.drawable.ic_arrow_down), "Latest")
            }
        }
    }
}

@Composable
private fun DayHeader(label: String) {
    Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun EventChip(m: ChatMessage) {
    val ctx = LocalContext.current
    val (icon, label) = when (m.kind) {
        MsgKind.RING -> R.drawable.ic_vibrate to (if (m.fromMe) "You rang the laptop" else "The laptop rang this phone")
        else -> R.drawable.ic_bell to buildString {
            append(if (m.fromMe) "You pinged" else "Ping")
            m.text?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fg = MaterialTheme.colorScheme.onTertiaryContainer
            Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(timeOf(ctx, m), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
        }
    }
}

private fun timeOf(ctx: Context, m: ChatMessage): String = DateFormat.getTimeFormat(ctx).format(Date(m.tsMs))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(row: MsgRow, progress: Pair<Long, Long>?) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val m = row.m
    val mine = m.fromMe
    val bg = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    // Round everywhere except where this bubble meets the next one from the same sender.
    val big = 20.dp
    val small = 6.dp
    val shape = if (mine) {
        RoundedCornerShape(big, if (row.first) big else small, if (row.last) big else small, big)
    } else {
        RoundedCornerShape(if (row.first) big else small, big, big, if (row.last) big else small)
    }
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.8f).dp
    val image = m.kind == MsgKind.FILE && Thumbs.isImage(m.fileName)
    val thumb = if (image) thumbOf(m) else null

    val open: (() -> Unit)? = if (m.kind == MsgKind.FILE && !mine) {
        {
            Downloads.uriFor(ctx, m.id)?.let { uri ->
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, Downloads.mimeOf(m.fileName ?: ""))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }
            }
        }
    } else null
    val copy: (() -> Unit)? = m.text?.takeIf { m.kind == MsgKind.TEXT }?.let { text ->
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", text))
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(top = if (row.first) 8.dp else 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier.widthIn(max = maxWidth).clip(shape).background(bg)
                .combinedClickable(enabled = open != null || copy != null, onClick = { (open ?: copy)?.invoke() }, onLongClick = copy),
        ) {
            when {
                thumb != null -> {
                    // The whole image, at its own aspect, inside a 260×320 box: no cropping.
                    val ratio = thumb.width.toFloat() / thumb.height
                    val w = minOf(maxWidth.coerceAtMost(260.dp), 320.dp * ratio)
                    Image(
                        thumb,
                        m.fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.width(w).aspectRatio(ratio),
                    )
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 2.dp),
                    ) { Meta(m, Color.White) }
                }
                m.kind == MsgKind.FILE -> FileChip(m, progress, fg)
                else -> Box(Modifier.padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
                    val linkColor = if (mine) fg else MaterialTheme.colorScheme.primary
                    TextWithMeta(remember(m.text, linkColor) { linkified(m.text.orEmpty(), linkColor) }, fg) {
                        Meta(m, fg.copy(alpha = 0.72f))
                    }
                }
            }
        }
    }
}

@Composable
private fun thumbOf(m: ChatMessage): ImageBitmap? {
    val ctx = LocalContext.current
    val version by Thumbs.version.collectAsState()
    val bmp by produceState(Thumbs.cached(m.id), m.id, m.state, version) {
        if (value == null) value = withContext(Dispatchers.IO) { Thumbs.load(ctx, m) }
    }
    return bmp
}

@Composable
private fun FileChip(m: ChatMessage, progress: Pair<Long, Long>?, fg: Color) {
    val ctx = LocalContext.current
    Column(Modifier.padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(fg.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_file), null, tint = fg, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.widthIn(min = 120.dp)) {
                Text(
                    m.fileName.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                val size = m.fileSize?.let { Formatter.formatShortFileSize(ctx, it.toLong()) }
                val ext = m.fileName?.substringAfterLast('.', "")?.uppercase()?.takeIf { it.isNotEmpty() && it.length <= 5 }
                val sub = when {
                    progress != null && progress.second > 0 ->
                        Formatter.formatShortFileSize(ctx, progress.first) + " of " +
                            Formatter.formatShortFileSize(ctx, progress.second)
                    else -> listOfNotNull(size, ext).joinToString(" · ")
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.72f))
            }
        }
        if (progress != null && progress.second > 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress.first.toFloat() / progress.second },
                color = fg,
                trackColor = fg.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth().clip(CircleShape),
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.align(Alignment.End)) { Meta(m, fg.copy(alpha = 0.72f)) }
    }
}

/**
 * Time, and for my messages the delivery state: a clock while queued, ✓✓ once the laptop has it.
 * A file I'm still sending gets a Cancel; the laptop drops what it got.
 */
@Composable
private fun Meta(m: ChatMessage, color: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (m.fromMe && m.kind == MsgKind.FILE && m.state == MsgState.QUEUED) {
            Text(
                "✕ Cancel",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { scope.launch { Core.withNode { n -> runCatching { n.cancel(m.id) } } } }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(timeOf(ctx, m), style = MaterialTheme.typography.labelSmall, color = color)
        if (m.state == MsgState.CANCELLED) {
            Spacer(Modifier.width(4.dp))
            Text("Cancelled", style = MaterialTheme.typography.labelSmall, color = color)
            return@Row
        }
        if (!m.fromMe) return@Row
        val (icon, desc) = when (m.state) {
            MsgState.QUEUED -> R.drawable.ic_schedule to "Waiting"
            MsgState.DELIVERED -> R.drawable.ic_done_all to "Delivered"
            MsgState.EXPIRED -> R.drawable.ic_error to "Not delivered"
            else -> return@Row
        }
        Spacer(Modifier.width(3.dp))
        Icon(
            painterResource(icon),
            desc,
            tint = if (m.state == MsgState.EXPIRED) MaterialTheme.colorScheme.error else color,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * Message text with the meta tucked into the end of its last line when it fits there, and on a
 * line of its own when it doesn't (or when that line is right-to-left, where the free space is on
 * the other side).
 */
@Composable
private fun TextWithMeta(text: AnnotatedString, color: Color, meta: @Composable () -> Unit) {
    // Not state: the layout is read in the same measure pass that produces it.
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout(content = {
        Text(text, color = color, style = MaterialTheme.typography.bodyLarge, onTextLayout = { layout[0] = it })
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

private fun linkified(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val style = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
    for (match in urlPattern.findAll(text)) {
        val url = match.value.let { if (it.startsWith("www.")) "https://$it" else it }
        addLink(LinkAnnotation.Url(url, style), match.range.first, match.range.last + 1)
    }
}

@Composable
private fun InputBar(draft: String, onDraft: (String) -> Unit, onAttach: () -> Unit, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttach, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_attach),
                        "Send a file",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraft,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 6,
                    modifier = Modifier.weight(1f).padding(top = 15.dp, bottom = 15.dp, end = 18.dp),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    "Message",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
            modifier = Modifier.size(52.dp),
        ) { Icon(painterResource(R.drawable.ic_send), "Send", Modifier.size(22.dp)) }
    }
}

/** One permission the phone side needs. */
private class SetupItem(
    val title: String,
    val why: String,
    val granted: (Context) -> Boolean,
    /** Opened when [intent]'s screen doesn't exist on this build. */
    val fallback: String? = null,
    val intent: (Context) -> Intent,
)

private val setupItems = listOf(
    SetupItem(
        "Notification access",
        "Mirror notifications and media to the laptop",
        PhoneListener::enabled,
        Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
    ) { ctx ->
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(ctx, PhoneListener::class.java).flattenToString(),
        )
    },
    SetupItem(
        "Battery: Unrestricted",
        "Stay linked while media plays",
        { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
    ) { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}")) },
    SetupItem(
        "Full-screen alerts",
        "Show a ring over the lock screen",
        { it.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() },
    ) { Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${it.packageName}")) },
)

@Composable
private fun SetupSheet(granted: List<Boolean>) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
        Text("Phone setup", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Text(
            "Chat and files work without these.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
        )
        for ((item, ok) in setupItems.zip(granted)) {
            ListItem(
                headlineContent = { Text(item.title) },
                supportingContent = { Text(item.why) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.padding(horizontal = 8.dp),
                trailingContent = {
                    if (ok) {
                        Icon(painterResource(R.drawable.ic_check), "Allowed", tint = MaterialTheme.colorScheme.primary)
                    } else {
                        FilledTonalButton(onClick = {
                            runCatching { ctx.startActivity(item.intent(ctx)) }.onFailure {
                                item.fallback?.let { runCatching { ctx.startActivity(Intent(it)) } }
                            }
                        }) { Text("Allow") }
                    }
                },
            )
        }
    }
}
