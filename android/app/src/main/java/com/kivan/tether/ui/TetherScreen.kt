package com.kivan.tether.ui

import android.content.Intent
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.kivan.tether.Core
import com.kivan.tether.Downloads
import com.kivan.tether.Outgoing
import com.kivan.tether.core.ChatMessage
import com.kivan.tether.core.MsgKind
import com.kivan.tether.core.MsgState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

@Composable
fun TetherScreen() {
    val status by Core.status.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding()) {
            val s = status
            when {
                s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                s.peer == null -> PairScreen()
                else -> ChatScreen(s.peer!!.name, s.queued)
            }
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
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pair with your laptop", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Run `tether pair` on the laptop, then scan the QR code it shows.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            enabled = !busy,
            onClick = {
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(ctx, options).startScan()
                    .addOnSuccessListener { b -> b.rawValue?.let(::pair) }
                    .addOnFailureListener { error = it.message }
            },
        ) { Text("Scan QR code") }
        Text("or paste the code", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            singleLine = true,
            placeholder = { Text("tether:1:…") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(enabled = !busy && code.startsWith("tether:"), onClick = { pair(code) }) { Text("Pair") }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ChatScreen(peerName: String, queued: ULong) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by Core.messages.collectAsState()
    val connected by Core.connected.collectAsState()
    val progress by Core.progress.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.map { Outgoing.copyIn(ctx, it) } }
            Core.withNode { n -> files.forEach { n.sendFile(it.path) } }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled = !connected) { scope.launch { Core.connect() } }) {
                Text(peerName, style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (connected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline),
                    )
                    Spacer(Modifier.size(6.dp))
                    val q = if (queued > 0uL) " · $queued queued" else ""
                    Text(
                        (if (connected) "Connected" else "Offline, tap to retry") + q,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Text("⋮", fontSize = 22.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Unpair") }, onClick = {
                        menu = false
                        scope.launch { Core.withNode { it.unpair() } }
                    })
                }
            }
        }
        HorizontalDivider()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        ) {
            items(messages.asReversed(), key = { it.id }) { m -> Bubble(m, progress[m.id]) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { pick.launch("*/*") }) { Text("+", fontSize = 24.sp) }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
            )
            Spacer(Modifier.size(8.dp))
            FilledIconButton(
                enabled = draft.isNotBlank(),
                onClick = {
                    val text = draft.trim()
                    draft = ""
                    scope.launch { Core.withNode { it.sendText(text) } }
                },
            ) { Text("➤") }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, progress: Pair<Long, Long>?) {
    val ctx = LocalContext.current
    val mine = m.fromMe
    val bg = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                .clickable(enabled = m.kind == MsgKind.FILE && !mine) {
                    Downloads.uriFor(ctx, m.id)?.let { uri ->
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(uri, Downloads.mimeOf(m.fileName ?: ""))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) { CompositionLocalProvider(LocalContentColor provides fg) {
            when (m.kind) {
                MsgKind.TEXT -> Text(m.text.orEmpty())
                MsgKind.PING -> Text("🔔 ${m.text.orEmpty()}", fontStyle = FontStyle.Italic)
                MsgKind.RING -> Text("📳 Ring", fontStyle = FontStyle.Italic)
                MsgKind.FILE -> {
                    val size = m.fileSize?.let { Formatter.formatShortFileSize(ctx, it.toLong()) } ?: ""
                    Text("📎 ${m.fileName.orEmpty()}")
                    Text(size, style = MaterialTheme.typography.labelSmall)
                    if (progress != null && progress.second > 0) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress.first.toFloat() / progress.second },
                            modifier = Modifier.widthIn(min = 160.dp),
                        )
                    }
                }
            }
            val time = DateFormat.getTimeFormat(ctx).format(Date(m.tsMs))
            val tick = if (!mine) "" else when (m.state) {
                MsgState.QUEUED -> " 🕓"
                MsgState.DELIVERED -> " ✓✓"
                MsgState.EXPIRED -> " ✗"
                else -> ""
            }
            Text(
                time + tick,
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.End),
            )
        } }
    }
}
