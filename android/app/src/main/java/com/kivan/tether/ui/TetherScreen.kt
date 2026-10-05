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
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.kivan.tether.ui.theme.AppType
import com.kivan.tether.ui.theme.Eyebrow
import com.kivan.tether.ui.theme.Palette
import com.kivan.tether.ui.theme.Pill
import com.kivan.tether.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

@Composable
fun TetherScreen() {
    val status by Core.status.collectAsState()
    Surface(Modifier.fillMaxSize(), color = Palette.Bg) {
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
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = Space.XL),
        verticalArrangement = Arrangement.spacedBy(Space.L, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(R.drawable.lucide_laptop, 96.dp)
        Spacer(Modifier.height(Space.XS))
        Eyebrow("Not paired")
        Text("Link your laptop", style = AppType.title)
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
            shape = MaterialTheme.shapes.medium,
            onClick = {
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(ctx, options).startScan()
                    .addOnSuccessListener { b -> b.rawValue?.let(::pair) }
                    .addOnFailureListener { error = it.message }
            },
        ) {
            Icon(painterResource(R.drawable.lucide_qr_code), null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Scan QR code", style = MaterialTheme.typography.titleMedium)
        }
        Eyebrow("or paste the code", Modifier.padding(top = Space.S))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            textStyle = AppType.mono,
            placeholder = { Text("tether:1:…", style = AppType.mono) },
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            enabled = !busy && code.startsWith("tether:"),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = MaterialTheme.shapes.medium,
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
            icon = { Icon(painterResource(R.drawable.lucide_unlink), null) },
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
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(horizontal = Space.L, vertical = Space.XS),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = Space.L, end = Space.S, top = Space.XS, bottom = Space.XS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.lucide_circle_alert),
                null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Space.M))
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
            if (messages.isEmpty()) EmptyChat(peerName) else ChatList(messages, progress)
        }
        InputBar(
            draft = draft,
            onDraft = { draft = it },
            modifier = Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
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
    val online = Palette.Success
    Surface(color = Palette.Bg) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(68.dp).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let { BackButton(it) }
            Row(
                Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                    .clickable(enabled = !connected && !connecting, onClick = onRetry)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Avatar(R.drawable.lucide_laptop, 44.dp)
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape)
                            .background(Palette.Bg).padding(2.5.dp).clip(CircleShape)
                            .background(if (connected) online else MaterialTheme.colorScheme.outline),
                    )
                }
                Spacer(Modifier.width(Space.M))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val line = when {
                        connected -> "Connected"
                        connecting -> "Connecting…"
                        else -> "Offline · tap to reconnect"
                    } + if (queued > 0uL) " · $queued waiting" else ""
                    Eyebrow(line, color = if (connected) online else Palette.Muted)
                    Text(
                        peerName,
                        style = AppType.heading,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    BadgedBox(badge = { if (setupMissing > 0) Badge() }) {
                        Icon(painterResource(R.drawable.lucide_ellipsis_vertical), "More")
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (setupMissing > 0) "Finish phone setup ($setupMissing)" else "Phone setup") },
                        leadingIcon = { Icon(painterResource(R.drawable.lucide_sliders_horizontal), null) },
                        onClick = {
                            menu = false
                            onSetup()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Unpair") },
                        leadingIcon = { Icon(painterResource(R.drawable.lucide_unlink), null) },
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
        Modifier.size(size).clip(CircleShape).background(Palette.Tile),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            null,
            tint = Palette.Text,
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
        Avatar(R.drawable.lucide_laptop, 72.dp)
        Text("Say hi to $peerName", style = MaterialTheme.typography.titleLarge)
        Text(
            "Messages and files wait here until the laptop is reachable, then go through on their own.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun isEvent(m: ChatMessage) = m.kind == MsgKind.PING || m.kind == MsgKind.RING

@Composable
private fun ChatList(messages: List<ChatMessage>, progress: Map<String, Pair<Long, Long>>) {
    val ctx = LocalContext.current
    val rows = remember(messages) { chatRows(ctx, messages, { it.id }, { it.tsMs }, { it.fromMe }, ::isEvent) }
    MessageList(rows, newestMine = messages.lastOrNull()?.fromMe == true) { row ->
        if (isEvent(row.line)) EventChip(row.line) else Bubble(row, progress[row.line.id])
    }
}

@Composable
private fun EventChip(m: ChatMessage) {
    val ctx = LocalContext.current
    val (icon, label) = when (m.kind) {
        MsgKind.RING -> R.drawable.lucide_vibrate to (if (m.fromMe) "You rang the laptop" else "The laptop rang this phone")
        else -> R.drawable.lucide_bell to buildString {
            append(if (m.fromMe) "You pinged" else "Ping")
            m.text?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(Pill).background(MaterialTheme.colorScheme.tertiaryContainer)
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
            Text(timeOf(ctx, m), style = AppType.mono, color = fg.copy(alpha = 0.7f))
        }
    }
}

private fun timeOf(ctx: Context, m: ChatMessage): String = DateFormat.getTimeFormat(ctx).format(Date(m.tsMs))

@Composable
private fun Bubble(row: LineRow<ChatMessage>, progress: Pair<Long, Long>?) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val m = row.line
    val mine = m.fromMe
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val bg = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val maxWidth = bubbleMaxWidth()
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
            scope.launch {
                copied = true
                delay(1200)
                copied = false
            }
        }
    }

    BubbleBox(mine, row.first, row.last, bg, onClick = open ?: copy, onLongClick = copy) {
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
                    Modifier.align(Alignment.BottomEnd).padding(Space.S).clip(Pill)
                        .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 2.dp),
                ) { Meta(m, Color.White) }
            }
            m.kind == MsgKind.FILE -> FileChip(m, progress, fg)
            else -> Box(Modifier.padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
                val linkColor = if (mine) fg else MaterialTheme.colorScheme.primary
                TextWithMeta(remember(m.text, linkColor) { linkified(m.text.orEmpty(), linkColor) }, fg) {
                    Meta(m, fg.copy(alpha = 0.72f), copied)
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
                Modifier.size(44.dp).clip(MaterialTheme.shapes.small).background(fg.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.lucide_file), null, tint = fg, modifier = Modifier.size(22.dp)) }
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
                Text(sub, style = AppType.small.copy(fontFeatureSettings = "tnum"), color = fg.copy(alpha = 0.72f))
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
private fun Meta(m: ChatMessage, color: Color, copied: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (m.fromMe && m.kind == MsgKind.FILE && m.state == MsgState.QUEUED) {
            Text(
                "✕ Cancel",
                style = AppType.small,
                color = color,
                modifier = Modifier
                    .clip(Pill)
                    .clickable { scope.launch { Core.withNode { n -> runCatching { n.cancel(m.id) } } } }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            if (copied) "Copied" else timeOf(ctx, m),
            style = if (copied) AppType.small else AppType.mono,
            color = color,
            fontWeight = if (copied) FontWeight.Bold else null,
        )
        if (m.state == MsgState.CANCELLED) {
            Spacer(Modifier.width(4.dp))
            Text("Cancelled", style = AppType.small, color = color)
            return@Row
        }
        if (!m.fromMe) return@Row
        val (icon, desc) = when (m.state) {
            MsgState.QUEUED -> R.drawable.lucide_clock to "Waiting"
            MsgState.DELIVERED -> R.drawable.lucide_check_check to "Delivered"
            MsgState.EXPIRED -> R.drawable.lucide_circle_alert to "Not delivered"
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
        Eyebrow("Optional", Modifier.padding(horizontal = Space.XL))
        Text("Phone setup", style = AppType.title, modifier = Modifier.padding(horizontal = Space.XL))
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
                        Icon(painterResource(R.drawable.lucide_check), "Allowed", tint = Palette.Success)
                    } else {
                        FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = {
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
