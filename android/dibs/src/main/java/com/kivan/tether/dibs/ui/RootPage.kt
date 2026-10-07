package com.kivan.tether.dibs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.KeyState
import com.kivan.tether.dibs.Page
import com.kivan.tether.dibs.Question
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.Root
import com.kivan.tether.dibs.RootFile
import com.kivan.tether.dibs.RootMessage
import com.kivan.tether.dibs.RootRequest
import com.kivan.tether.dibs.SignResult
import com.kivan.tether.dibs.age
import com.kivan.tether.dibs.fileSize
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.launch

// Root steps (Root.kt): the Waiting card, the page that shows a request word for word and approves
// it with a fingerprint or face, and setting up the phone's key. Approving needs the page: the card
// and the chat only open it (or deny).

/** A root step's card: its title, the agent's reason, dibs's check, Review and Deny. */
@Composable
internal fun RootCard(q: Question, root: RootRequest, now: Long, modifier: Modifier) {
    Column(modifier.card().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.lucide_shield_check), null, Modifier.size(14.dp), tint = Palette.Muted)
            Eyebrow(listOfNotNull("Root step", q.from.ifBlank { null }, age(now - q.ts)).joinToString(" · "))
        }
        Text(q.title, style = AppType.body.copy(fontWeight = Bold), color = Palette.Text)
        if (root.why.isNotBlank()) AgentWords(root.why)
        CheckLine(root)
        if (root.expires in 1..now) Text("Expired", style = AppType.small, color = Palette.Muted)
        RootActions(q, Modifier.padding(top = 4.dp))
    }
}

/** The phone's key isn't set up yet: Set up makes it, shows its code and sends it to the laptop. */
@Composable
internal fun RootKeyCard(q: Question, now: Long, modifier: Modifier) {
    Column(modifier.card().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.lucide_key_round), null, Modifier.size(14.dp), tint = Palette.Muted)
            Eyebrow(listOfNotNull("Root steps", age(now - q.ts)).joinToString(" · "))
        }
        Text(q.title, style = AppType.body.copy(fontWeight = Bold), color = Palette.Text)
        if (q.why.isNotBlank()) Text(q.why, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        RootActions(q, Modifier.padding(top = 4.dp))
    }
}

/**
 * A root question's buttons, on its card and in the chat: Review (a request) or Set up (the key),
 * which are the phone's own, then dibs's other buttons (Deny).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RootActions(q: Question, modifier: Modifier = Modifier) {
    val setup = q.kind == "rootkey"
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (setup) {
            ActButton("Set up", "primary", icon = R.drawable.lucide_key_round) { Dibs.open(Page.RootKey) }
        } else {
            ActButton("Review", "primary", icon = R.drawable.lucide_shield_check) { Dibs.open(Page.Root(q.id)) }
        }
        // Set up is handled here; dibs's own button for it (if it sent one) isn't drawn twice.
        for (a in q.actions.filter { !(setup && it.label.equals("Set up", ignoreCase = true)) }) {
            ActButton(a.label, a.style) { Dibs.answer("q${q.id}", a.label, a.id) }
        }
    }
}

/** The agent's reason, marked as its own words. */
@Composable
private fun AgentWords(why: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("In the agent's words", style = AppType.small, color = Palette.Muted)
        Text("“$why”", style = MaterialTheme.typography.bodyMedium, color = Palette.Text)
    }
}

/** dibs's pick and its one line why, or that it hasn't looked yet. Never colour alone: each has its icon. */
@Composable
private fun CheckLine(root: RootRequest) {
    val (icon, tint) = when {
        root.note == null -> R.drawable.lucide_clock to Palette.Muted
        root.pick == "reject" -> R.drawable.lucide_triangle_alert to Palette.Warning
        root.pick == "accept" -> R.drawable.lucide_check to Palette.Success
        else -> R.drawable.lucide_message_circle to Palette.Muted
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(icon), null, Modifier.padding(top = 1.dp).size(14.dp), tint = tint)
        Text(RootMessage.checkWords(root), style = AppType.small, color = if (root.pick == "reject") Palette.Warning else Palette.Muted)
    }
}

/**
 * A root step's request, all of it (SPEC.md §3): when it expires, the agent's reason (its own
 * words), dibs's check, the phone's own warnings, what it may reach, the script and files word for
 * word, the request's code (worked out here), Never ask again, then Approve and Deny. Approve asks
 * for a fingerprint or face, and only then does the phone's key sign.
 */
@Composable
internal fun RootPage(id: Long, view: DibsView) {
    val q = view.questions.firstOrNull { it.id == id }
    val root = q?.root
    // Answered here, or no longer asked: back to where it was opened from.
    val gone = q == null || root == null || "q$id" in Dibs.answered
    LaunchedEffect(gone) { if (gone) Dibs.back() }
    if (q == null || root == null || gone) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val now by rememberNow()
    var never by rememberSaveable(id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf<KeyState?>(null) }
    LaunchedEffect(Unit) { key = Root.key.state() }

    val problem = remember(root) { RootMessage.problem(root) }
    val message = remember(root, never, problem) { if (problem == null) RootMessage.build(root, never) else null }
    val warnings = remember(root, now / 30) { RootMessage.warnings(root, now) }
    val expired = root.expires <= now

    Column(Modifier.fillMaxSize()) {
        PageBar(q.title, "Root step")
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = Space.L, end = Space.L, bottom = Space.XL),
            verticalArrangement = Arrangement.spacedBy(Space.S),
        ) {
            Text(
                if (expired) "Expired" else "Expires in ${leftWords(root.expires - now)}",
                style = AppType.small,
                color = if (expired || root.expires - now <= RootMessage.SOON_SECS) Palette.Warning else Palette.Muted,
            )
            if (root.why.isNotBlank()) {
                Section("Why, in the agent's words")
                Text("“${root.why}”", style = AppType.body, color = Palette.Text)
            }
            Section("dibs's check")
            CheckLine(root)

            Section("What the phone sees in it")
            if (warnings.isEmpty()) {
                Text("Nothing on the phone's list of risky things.", style = AppType.small, color = Palette.Muted)
            }
            for (w in warnings) WarningLine(w)
            Text(
                listOf(
                    if (root.network) "Network on" else "No network",
                    if (root.home == "ro") "home folder visible, read-only" else "home folder hidden",
                    "stops after ${RootMessage.timeoutWords(root.timeout)}",
                ).joinToString(" · "),
                style = AppType.small,
                color = Palette.Muted,
            )

            Section("The script, word for word")
            Code(root.script)
            for (f in root.files) FileBlock(f)

            Section("Request code")
            message?.let {
                Text(RootMessage.shortHash(it), style = AppType.mono.copy(fontSize = 17.sp, lineHeight = 22.sp), color = Palette.Text)
                Text(
                    "Worked out on this phone from what's shown here, and shown in the fingerprint prompt too.",
                    style = AppType.small,
                    color = Palette.Muted,
                )
            }

            // The laptop refuses Never ask again for a step that reads the home folder: it could
            // read something different every time.
            if (root.home != "ro") Row(
                Modifier.padding(top = Space.S).fillMaxWidth().toggleable(value = never, role = Role.Checkbox) { never = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = never,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = Palette.Accent, checkmarkColor = Palette.OnAccent, uncheckedColor = Palette.Muted),
                )
                Column(Modifier.padding(start = Space.S)) {
                    Text("Never ask again", style = AppType.label, color = Palette.Text)
                    Text("Runs this exact script again without asking", style = AppType.small, color = Palette.Muted)
                }
            }

            // Why Approve can't be used, in plain words.
            val blocked = when {
                expired -> "This request expired, so approving it would do nothing. Ask for it again if it's still needed."
                problem != null -> "The phone can't sign this request: $problem Deny it."
                key is KeyState.None -> "This phone has no key for root steps yet. Set it up; the laptop needs one password to trust it."
                key is KeyState.Invalidated -> Root.INVALIDATED
                key is KeyState.Broken -> (key as KeyState.Broken).why
                else -> null
            }
            blocked?.let { Text(it, style = AppType.body, color = Palette.Warning) }
            if (!expired && problem == null && (key is KeyState.None || key is KeyState.Invalidated)) {
                ActButton(if (key is KeyState.None) "Set up" else "Set up again", "", icon = R.drawable.lucide_key_round) { Dibs.open(Page.RootKey) }
            }
            error?.let { Text(it, style = AppType.small, color = Palette.Danger) }

            Spacer(Modifier.height(Space.XS))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                ActButton(
                    "Approve",
                    "primary",
                    Modifier.weight(1f),
                    enabled = blocked == null && key is KeyState.Ready && !busy,
                    icon = R.drawable.lucide_fingerprint,
                ) {
                    val r = root
                    val keep = never
                    if (r.expires <= System.currentTimeMillis() / 1000) {
                        error = "This request just expired."
                        return@ActButton
                    }
                    // Built again now, from what's on screen, with the tick as it is at the tap.
                    val msg = RootMessage.build(r, keep)
                    busy = true
                    error = null
                    scope.launch {
                        val res = Root.key.sign(context, msg.toByteArray(Charsets.UTF_8), q.title, "Request code ${RootMessage.shortHash(msg)}")
                        busy = false
                        when (res) {
                            is SignResult.Signed -> {
                                Dibs.approveRoot(q, r.request!!, res.der, keep)
                                Dibs.back()
                            }
                            SignResult.Cancelled -> {}
                            SignResult.Invalidated -> key = KeyState.Invalidated
                            is SignResult.Failed -> error = res.why
                        }
                    }
                }
                ActButton("Deny", "danger", Modifier.weight(1f)) {
                    Dibs.denyRoot(q)
                    Dibs.back()
                }
            }
        }
    }
}

/** How long until it expires: "8 min", "11 h". */
private fun leftWords(secs: Long): String = when {
    secs < 3600 -> "${maxOf(1, secs / 60)} min"
    else -> "${secs / 3600} h"
}

@Composable
private fun WarningLine(text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.lucide_triangle_alert), null, Modifier.padding(top = 2.dp).size(16.dp), tint = Palette.Warning)
        Text(text, style = AppType.body, color = Palette.Text)
    }
}

/** Text word for word in monospace, wrapping (so nothing hides off to the side); hidden characters written out. */
@Composable
private fun Code(text: String, background: Color = Palette.SurfaceLow) {
    SelectionContainer {
        Text(
            remember(text) { RootMessage.visible(text) },
            Modifier.fillMaxWidth().background(background, MaterialTheme.shapes.small).padding(10.dp),
            style = AppType.mono,
            color = Palette.Text,
        )
    }
}

/** A file it brings: a text file whole, a binary one by name, size and the laptop's hash. */
@Composable
private fun FileBlock(f: RootFile) {
    Section("File: ${f.name}")
    if (f.text != null) {
        Code(f.text)
        return
    }
    Column(
        Modifier.fillMaxWidth().background(Palette.SurfaceLow, MaterialTheme.shapes.small).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("${f.name} · ${f.size?.let(::fileSize) ?: "size unknown"}", style = AppType.label, color = Palette.Text)
        SelectionContainer { Text(f.sha256.orEmpty(), style = AppType.mono, color = Palette.Text) }
        Text("Hash from the laptop, not checked by the phone.", style = AppType.small, color = Palette.Warning)
    }
}

/**
 * Setting up the phone's key: it's made (unless there is one that still works), its code shown,
 * and its public half sent to the laptop. The laptop's setup shows the same code.
 */
@Composable
internal fun RootKeyPage() {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<KeyState?>(null) }
    LaunchedEffect(attempt) {
        state = null
        val s = Root.key.ensure(context)
        state = s
        if (s is KeyState.Ready) Root.sendKey(s)
    }
    Column(Modifier.fillMaxSize()) {
        PageBar("Key for root steps", "Set up")
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Space.L, end = Space.L, bottom = Space.XL),
            verticalArrangement = Arrangement.spacedBy(Space.S),
        ) {
            Text(
                "This phone makes its own key for approving root steps on the laptop. The key never leaves the phone, " +
                    "and it signs only after your fingerprint or face.",
                style = AppType.body,
                color = Palette.Text,
            )
            when (val s = state) {
                null -> Text("Making the key", style = AppType.body, color = Palette.Muted)
                is KeyState.Ready -> {
                    Section("Key code")
                    Text(s.fingerprint, style = AppType.mono.copy(fontSize = 24.sp, lineHeight = 30.sp), color = Palette.Text)
                    Text(
                        "The laptop shows a code when you set it up there. Check that it's the same, so only this phone can approve root steps.",
                        style = AppType.body,
                        color = Palette.Text,
                    )
                    Text(
                        if (s.strongbox) "Kept in the phone's security chip." else "Kept in the phone's secure area (its security chip wasn't available).",
                        style = AppType.small,
                        color = Palette.Muted,
                    )
                    Text("Sent to the laptop.", style = AppType.small, color = Palette.Muted)
                }
                is KeyState.Broken -> Problem(s.why) { attempt++ }
                KeyState.Invalidated -> Problem(Root.INVALIDATED) { attempt++ }
                KeyState.None -> Problem("The phone couldn't make its key.") { attempt++ }
            }
        }
    }
}

@Composable
private fun Problem(text: String, retry: () -> Unit) {
    Text(text, style = AppType.body, color = Palette.Warning)
    ActButton("Try again", "", icon = R.drawable.lucide_refresh_cw, onClick = retry)
}
