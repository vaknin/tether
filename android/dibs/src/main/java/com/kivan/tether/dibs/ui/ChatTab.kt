package com.kivan.tether.dibs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.delay

// The Chat tab (docs/DIBS-APP.md, "Chat"): the conversation with dibs from the bottom, the box
// pinned under it. Its parts are shared with each task's chat (ChatParts.kt).

@Composable
internal fun ChatTab(view: DibsView) {
    val all by Dibs.pending.collectAsStateWithLifecycle()
    // The echoes of messages to dibs; a task's are in its own chat.
    val pending = remember(all) { all.filter { it.task == null } }
    val hidden = Dibs.hidden.keys.toSet()
    val talk = remember(view.talk, hidden) { view.talk.filter { it.id !in hidden } }
    // A note that a task's full story is ready opens it ("Read it") while the task is listed.
    val look = remember(view.yours) { ChatLook(stories = view.yours?.mapTo(HashSet()) { it.id }.orEmpty()) }
    val rows = rememberChatRows(talk, pending, look)
    val echoes = remember(pending) { pending.associateBy { it.uid } }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                Text(
                    "Say hello. Photos and files can go along too.",
                    style = AppType.body,
                    color = Palette.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(Space.XL),
                )
            }
            Conversation(rows, echoes, busy = view.state.busy, busyLine = view.state.line, look = look)
        }
        HoldBar(view)
        InputArea(Dibs.chat, "Message dibs")
    }
}

/**
 * Over the box (task #69): dibs's Wait/Stop chip, words and all (`state.hold`): a note, if any,
 * beside its button.
 */
@Composable
private fun HoldBar(view: DibsView) {
    val tap = Dibs.holdTap
    LaunchedEffect(tap) {
        if (tap == null) return@LaunchedEffect
        delay(Dibs.TAP_MS)
        if (Dibs.holdTap == tap) Dibs.holdTap = null
    }
    val hold = Dibs.hold(view) ?: return
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        if (hold.note != null) {
            Text(hold.note, style = AppType.small, color = Palette.Muted, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        ActButton(hold.button, hold.style) { Dibs.tapHold(hold) }
    }
}
