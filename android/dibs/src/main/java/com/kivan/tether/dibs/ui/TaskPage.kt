package com.kivan.tether.dibs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.YourTask
import com.kivan.tether.dibs.duration
import com.kivan.tether.dibs.ranMinutes
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space

// A task's page (docs/DIBS-APP.md, "Your tasks"): its state and times, its open questions, what it
// did, its result, what was asked, the transcript, and the chat with its own agent.

@Composable
internal fun TaskPage(id: Long, view: DibsView) {
    val t = view.task(id) ?: return Gone()
    val now by rememberNow()
    val armed = rememberArmed()
    // Open: it's read, and its ping goes (again whenever new lines arrive while it's open).
    val newest = t.talk.lastOrNull()?.id
    LaunchedEffect(id, newest) { Dibs.seenTask(t) }
    val state = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        PageBar(t.label, t.project) { TaskActions(t, view, armed) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = state,
            contentPadding = PaddingValues(start = Space.L, end = Space.L, bottom = Space.L),
            verticalArrangement = Arrangement.spacedBy(Space.S),
        ) {
            summary(t, view, now)
        }
    }
}

/** Tick off once it's finished (Untick once ticked), Stop while it runs, and ⋮ Open on laptop. */
@Composable
private fun TaskActions(t: YourTask, view: DibsView, armed: Armed) {
    var menu by remember { mutableStateOf(false) }
    when {
        t.finishedState && Dibs.ticked(t) -> IconButton(onClick = { Dibs.untick(t) }) {
            Icon(painterResource(R.drawable.lucide_undo_2), "Untick", Modifier.size(22.dp), tint = Palette.Muted)
        }
        t.finishedState -> IconButton(onClick = {
            Dibs.tick(t)
            Dibs.back()
        }) { Icon(painterResource(R.drawable.lucide_circle_check), "Tick off", Modifier.size(22.dp), tint = Palette.Accent) }
        else -> {
            val k = "stop-task${t.id}"
            ActButton(if (armed.key == k) "Stop it?" else "Stop", "danger") { armed.press(k) { Dibs.stop(t.id) } }
        }
    }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(painterResource(R.drawable.lucide_ellipsis_vertical), "More", tint = Palette.Muted)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Open on laptop") },
                leadingIcon = { Icon(painterResource(R.drawable.lucide_laptop), null, Modifier.size(18.dp)) },
                onClick = {
                    menu = false
                    Dibs.openOnLaptop(view, t.id)
                },
            )
        }
    }
}

/** Top to bottom: state and times, open questions, what it did, result, what was asked, the transcript. */
private fun LazyListScope.summary(t: YourTask, view: DibsView, now: Long) {
    item(key = "_state") { StateBlock(t, now) }
    val questions = view.questions.filter { it.id in t.questions && "q${it.id}" !in Dibs.answered }
    items(questions, key = { "q${it.id}" }) { q -> QuestionCard(q, now, Modifier.animateItem()) }

    item(key = "_did") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            Section(if (t.finishedState) "What it did" else "What it's doing")
            val text = t.report ?: t.line
            if (text.isNotBlank()) {
                SelectionContainer { Text(remember(text) { linkified(text) }, style = bodyStyle, color = Palette.Text) }
            } else {
                Text("Nothing to say yet.", style = AppType.body, color = Palette.Muted)
            }
        }
    }
    val result = t.result
    if (result != null && result.shipped.isNotEmpty()) {
        item(key = "_result") { ResultBlock(t) }
    }
    if (t.asked.isNotBlank()) {
        item(key = "_asked") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                Section("You asked")
                TapFold(t.asked, "asked:${t.id}", 3, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            }
        }
    }
}

/** Its state in words, then when it started, how long it ran, when it finished. */
@Composable
private fun StateBlock(t: YourTask, now: Long) {
    Column(Modifier.padding(top = Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        TaskState(t, now)
        val ran = ranMinutes(t, now)
        val times = listOfNotNull(
            if (t.started > 0) "Started ${whenWords(t.started)}" else null,
            ran?.let { if (t.finishedState) "ran ${duration(it)}" else "running ${duration(it)}" },
            t.finished?.let { "finished ${whenWords(it)}" },
        ).joinToString(" · ")
        if (times.isNotEmpty()) Text(times, style = AppType.small, color = Palette.Muted)
    }
}

/** "Report" (its REPORT.md, in the reader) and where it shipped, with the For you lines. */
@Composable
private fun ResultBlock(t: YourTask) {
    val result = t.result ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Section("Result")
        for (s in result.shipped) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val changes = if (s.changes == 1) "1 change" else "${s.changes} changes"
                Text(if (s.changes > 0) "Shipped to ${s.repo} · $changes" else "Shipped to ${s.repo}", style = AppType.body.copy(fontWeight = Bold), color = Palette.Text)
                for (line in s.forYou) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.padding(top = 8.dp).size(4.dp).background(Palette.Muted, CircleShape))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
                    }
                }
            }
        }
    }
}

/** A page whose task the view no longer lists. */
@Composable
internal fun Gone() {
    Column(Modifier.fillMaxSize()) {
        PageBar("Not listed any more")
        Box(Modifier.padding(horizontal = Space.L)) { Quiet("dibs doesn't list this task any more.") }
    }
}
