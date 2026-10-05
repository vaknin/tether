package com.kivan.tether.dibs.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space

// The Chat tab (docs/DIBS-APP.md, "Chat"): the conversation with dibs from the bottom, the box
// pinned under it. Its parts are shared with each task's chat (ChatParts.kt).

@Composable
internal fun ChatTab(view: DibsView) {
    val all by Dibs.pending.collectAsStateWithLifecycle()
    // The echoes of messages to dibs; a task's are in its own chat.
    val pending = remember(all) { all.filter { it.task == null } }
    val hidden = Dibs.hidden.keys.toSet()
    val talk = remember(view.talk, hidden) { view.talk.filter { it.id !in hidden } }
    val rows = rememberChatRows(talk, pending, ChatLook())
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
            Conversation(rows, echoes, busy = view.state.busy, busyLine = view.state.line)
        }
        InputArea(Dibs.chat, "Message dibs")
    }
}
