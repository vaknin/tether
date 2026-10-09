package com.kivan.tether.dibs.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsActivity
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Look
import com.kivan.tether.dibs.Page
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import org.json.JSONObject

// "To look over", the top of the Recap tab: what dibs went ahead with (a plan, a design, a choice: Keep or
// Change) and what only needs a read (research, an answer, a page: Got it or Reply), with a line that
// counts the real questions in Waiting. The user, 2026-10-09: plans and designs no longer wait for them.

/** Looks shown before "Show all". */
private const val LOOKS_SHOWN = 4
private const val LOOKS_ALL = "recap:looks"

/** The section: its questions line, then the looks, newest first. Nothing at all when there is neither. */
internal fun LazyListScope.lookItems(view: DibsView) {
    val waiting = view.questions.count { "q${it.id}" !in Dibs.answered }
    val looks = view.looks.filter { "l${it.id}" !in Dibs.answered }
    if (waiting == 0 && looks.isEmpty()) return
    val all = Dibs.open[LOOKS_ALL] == true
    item(key = "_looks") { Section("To look over") }
    if (waiting > 0) item(key = "_look_questions") { QuestionsLine(waiting) }
    items(if (all) looks else looks.take(LOOKS_SHOWN), key = { "l${it.id}" }) { l -> LookCard(l, Modifier.animateItem()) }
    if (looks.size > LOOKS_SHOWN) {
        item(key = "_looks_more") {
            Text(
                if (all) "Show fewer" else "Show all ${looks.size}",
                Modifier.clip(MaterialTheme.shapes.small).clickable { Dibs.toggle(LOOKS_ALL) }.padding(vertical = 6.dp),
                style = AppType.label,
                color = Palette.Accent,
            )
        }
    }
}

/** "2 questions wait for your answer": a tap goes to Waiting. */
@Composable
private fun QuestionsLine(n: Int) {
    Row(
        Modifier.card().clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClickLabel = "Open Waiting") { Dibs.tab = DibsActivity.TAB_WAITING }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (n == 1) "1 question waits for your answer" else "$n questions wait for your answer", Modifier.weight(1f), style = AppType.body, color = Palette.Text)
        Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(18.dp), tint = Palette.Muted)
    }
}

private fun kindWords(kind: String) = when (kind) {
    "plan" -> "Plan"
    "design" -> "Design"
    "research" -> "Research"
    "answer" -> "Answer"
    "page" -> "Page"
    else -> kind.replaceFirstChar { it.uppercase() }
}

/** Where the build dibs started stands; none for a read. */
private fun stateWords(l: Look) = when (l.followState) {
    "queued" -> "Queued to build"
    "running" -> "Building now"
    "done" -> "Built"
    else -> null
}

/**
 * One look: its kind and where its build is, the title, what dibs picked and why (three lines, a tap opens all of it),
 * then its page and Keep or Change (a read: Got it or Reply). Change and Reply open a box for the user's words.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookCard(l: Look, modifier: Modifier) {
    val uri = LocalUriHandler.current
    val key = "l${l.id}"
    val boxKey = "lookbox:${l.id}"
    val more = Dibs.open["look:${l.id}"] == true
    val box = Dibs.open[boxKey] == true
    Column(modifier.card().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.S),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(kindWords(l.kind), accent = true)
            stateWords(l)?.let { Text(it, style = AppType.small, color = Palette.Muted) }
        }
        Text(l.title, style = AppType.heading, color = Palette.Text)
        if (l.pick.isNotBlank()) {
            Text(
                l.pick,
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClickLabel = if (more) "Fold" else "Read all") { Dibs.toggle("look:${l.id}") },
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
                maxLines = if (more) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (l.link != null) {
                // The page in the browser; the task's own page when it won't open.
                ActButton("Open page", "") { if (runCatching { uri.openUri(l.link) }.isFailure && l.task != null) Dibs.open(Page.Task(l.task)) }
            } else if (l.task != null) {
                ActButton("Open task", "") { Dibs.open(Page.Task(l.task)) }
            }
            val ok = if (l.read) "got" else "keep"
            ActButton(if (l.read) "Got it" else "Keep", "primary") {
                Dibs.answer(key, if (l.read) "Got it" else "Kept", "look", JSONObject().put("id", l.id).put("act", ok))
            }
            ActButton(if (l.read) "Reply" else "Change", "plain") { Dibs.toggle(boxKey) }
        }
        if (box) {
            val act = if (l.read) "reply" else "change"
            AnswerField("look/${l.id}", if (l.read) "Your reply" else "What should change?", Modifier.fillMaxWidth()) { text ->
                Dibs.answer(key, text, "look", JSONObject().put("id", l.id).put("act", act).put("text", text))
            }
        }
    }
}
