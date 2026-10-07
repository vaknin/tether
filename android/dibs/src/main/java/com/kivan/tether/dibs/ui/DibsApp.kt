package com.kivan.tether.dibs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.Badges
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.Lend
import com.kivan.tether.dibs.LendToggle
import com.kivan.tether.dibs.Lends
import com.kivan.tether.dibs.Link
import com.kivan.tether.dibs.Page
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.State
import com.kivan.tether.dibs.stateWords
import com.kivan.tether.dibs.Limit
import com.kivan.tether.dibs.ideas.Drafts
import com.kivan.tether.dibs.limitWords
import com.kivan.tether.dibs.wantsYou
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Eyebrow
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/**
 * The five tabs, by the key an intent names them with. The fourth is Tasks (the user's tasks), or
 * Work for a dibs that doesn't send them yet.
 */
internal enum class Tab(val key: String, val label: String, val icon: Int) {
    CHAT("chat", "Chat", R.drawable.lucide_message_circle),
    IDEAS("ideas", "Ideas", R.drawable.lucide_lightbulb),
    WAITING("waiting", "Waiting", R.drawable.lucide_inbox),
    TASKS("tasks", "Tasks", R.drawable.lucide_list_checks),
    RECAP("recap", "Recap", R.drawable.lucide_history),
}

/** dibs's screen: the header, the lend bar when dibs has the phone, a tab, and the tabs below. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DibsApp() {
    val host = Dibs.host
    val json by host.view.collectAsStateWithLifecycle()
    val link by host.link.collectAsStateWithLifecycle()
    val view = remember(json) { runCatching { DibsView.ofView(json) }.getOrNull() }
    val context = LocalContext.current
    LaunchedEffect(view) {
        Dibs.seen(view)
        Drafts.seen(context, view?.ideas)
        // A conversation's notification tapped before the views were loaded opens once one lists it.
        Dibs.resolveAsk(view)
    }

    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    val tabs = rememberSaveableStateHolder()
    val asked = Dibs.tab
    LaunchedEffect(asked) {
        if (asked == null) return@LaunchedEffect
        (if (asked == "work") Tab.TASKS else Tab.entries.firstOrNull { it.key == asked })?.let { tab = it }
        Dibs.tab = null
    }
    // The keyboard needs the room; the tabs come back when it closes.
    val typing = WindowInsets.isImeVisible

    // A task's page (and its transcript, report or full story) over the tabs; back pops it.
    val page = Dibs.pages.lastOrNull()
    BackHandler(enabled = page != null) { Dibs.back() }
    if (page != null && view != null) {
        Box(
            Modifier.fillMaxSize().background(Palette.Bg)
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)),
        ) {
            // Each page its own state (its scroll, its taps), also when one replaces another of its kind.
            key(page) {
                when (page) {
                    is Page.Task -> TaskPage(page.id, view)
                    is Page.Transcript -> TranscriptScreen(page.id, view)
                    is Page.Report -> ReportScreen(page.id, view)
                    is Page.Story -> StoryScreen(page.id, view)
                    is Page.Idea -> IdeaPage(page.id, view)
                    is Page.Ask -> AskPage(page.about, view)
                }
            }
        }
        return
    }

    Scaffold(
        containerColor = Palette.Bg,
        contentColor = Palette.Text,
        // The header (in the column, not the Scaffold's topBar), the tab bar and imePadding pad for
        // the bars and the keyboard themselves; the Scaffold's topBar slot left a status bar's
        // height of gap under the header, room the chat needs with the keyboard open.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (view != null && !typing) {
                // The Tasks badge counts what the tab says it does, and drops as soon as one is opened here.
                val forYou = view.yours?.count { wantsYou(it, Dibs.ticked(it), Dibs.unread(it)) } ?: view.badges.work
                NavBar(tab, view.badges, forYou, view.yours != null) { tab = it }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)
                // Sideways (landscape: a cutout or a side navigation bar), then the keyboard.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding(),
        ) {
            Header(link, view?.state, typing)
            if (view == null) {
                Empty(hasView = json != null)
                return@Column
            }
            // Two toggles from a dibs that sends them; the older "dibs has your phone" bar otherwise.
            val lends = view.lends
            // While typing they give their room to the chat: with the keyboard open on a small
            // screen they left the box no room at all (the user, 2026-10-06, word 117).
            if (!typing) {
                if (lends != null && (lends.phone != null || lends.laptop != null)) LendToggles(lends) else view.lend?.let { LendBar(it) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The other tabs keep their place (scroll, folds) under a page and across tab
                // changes; the chat opens at its newest line, as always.
                if (tab == Tab.CHAT) {
                    ChatTab(view)
                } else {
                    tabs.SaveableStateProvider(tab.key) {
                        when (tab) {
                            Tab.IDEAS -> IdeasTab(view)
                            Tab.WAITING -> WaitingTab(view)
                            Tab.TASKS -> if (view.yours != null) TasksTab(view) else WorkTab(view)
                            else -> RecapTab(view)
                        }
                    }
                }
            }
        }
    }
}

/** The mark on dibs's tile, the state in an eyebrow above the name, and ⋮. */
@Composable
private fun Header(link: Link, state: State?, typing: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val words = stateWords(link, state)
    // Red only with words: the link is lost. Muted while it can't do anything for you.
    val color = when {
        link == Link.UNPAIRED -> Palette.Danger
        link == Link.OFFLINE || state == null -> Palette.Muted
        state.doing == "out" -> Palette.Warning
        state.doing == null && !state.busy && !state.usage.isNullOrBlank() -> Palette.Warning
        else -> Palette.Accent
    }
    Column(Modifier.fillMaxWidth().background(Palette.Bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = Space.L, end = Space.XS, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Palette.Tile), contentAlignment = Alignment.Center) {
                // The launcher's monochrome layer: its mark fills 46 of 108 dp, so draw it larger than the tile.
                Icon(painterResource(R.drawable.ic_dibs_monochrome), null, Modifier.requiredSize(48.dp), tint = Palette.Text)
            }
            // The usage steps aside while typing. With it, the state takes what it needs up to 130 dp (a
            // longer one, "Waiting for dibs", wraps), and the usage the rest; without, all of it.
            val limits = state?.limits?.takeIf { it.isNotEmpty() && !typing }
            Column(
                (if (limits != null) Modifier.widthIn(max = 130.dp) else Modifier.weight(1f)).padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Eyebrow(words, color = color)
                Text("dibs", style = AppType.heading, color = Palette.Text)
            }
            if (limits != null) {
                Box(Modifier.weight(1f).padding(start = Space.S), contentAlignment = Alignment.CenterEnd) { Usage(limits) }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.lucide_ellipsis_vertical), "More", tint = Palette.Muted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Open Tether") },
                        leadingIcon = { Icon(painterResource(R.drawable.lucide_external_link), null, Modifier.size(18.dp)) },
                        onClick = {
                            menu = false
                            Dibs.host.openTether()
                        },
                    )
                }
            }
        }
        // dibs's brain is on but down: one tap starts it (your start: no limit holds it back).
        if (link == Link.CONNECTED && state?.brainDown == true) {
            Row(Modifier.fillMaxWidth().padding(start = Space.L, end = Space.L, bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                ActButton("Start dibs", "primary") { Dibs.host.act("brain-start") }
            }
        }
    }
}

/**
 * Claude's usage, always in sight: each window's percent and when it resets, amber near the end.
 * Never cut: a line too long for its room breaks only after its "·" ("7d 93% ·" over "resets Sun
 * 10:00"), as each half is held together.
 */
@Composable
private fun Usage(limits: List<Limit>) {
    val ctx = LocalContext.current
    val now by rememberNow()
    val lines = remember(limits, now / 60) {
        val zone = ZoneId.systemDefault()
        limitWords(
            limits, now,
            clock = { android.text.format.DateFormat.getTimeFormat(ctx).format(Date(it * 1000)) },
            day = { Instant.ofEpochSecond(it).atZone(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) },
        )
    }
    if (lines.isEmpty()) return
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        for (l in lines) {
            Text(
                remember(l.text) { l.text.replace(' ', NBSP).replace("$NBSP·$NBSP", "$NBSP· ") },
                style = AppType.small,
                color = if (l.warn) Palette.Warning else Palette.Muted,
                textAlign = TextAlign.End,
            )
        }
    }
}

private const val NBSP = '\u00A0'

/** "dibs has your phone": above every tab while dibs has it, Take it back in one tap. */
@Composable
private fun LendBar(lend: Lend) {
    Row(
        Modifier.padding(horizontal = Space.L).padding(bottom = Space.S).fillMaxWidth()
            .background(Palette.AccentDim, MaterialTheme.shapes.medium)
            .padding(start = Space.M, end = Space.S, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.lucide_smartphone), null, Modifier.size(18.dp), tint = Palette.Accent)
        Column(Modifier.weight(1f)) {
            Text("dibs has your phone", style = AppType.label, color = Palette.Text)
            if (lend.text.isNotBlank()) Text(lend.text, style = AppType.small, color = Palette.Text)
        }
        ActButton("Take it back", "primary") { Dibs.host.act("phone-back") }
    }
}

/** Lend the phone and the laptop to dibs, or take them back (task #29): one tap each, above every tab. */
@Composable
private fun LendToggles(lends: Lends) {
    Row(
        // Both as tall as the taller one, when one's words wrap.
        Modifier.padding(horizontal = Space.L).padding(bottom = Space.S).fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        lends.phone?.let { LendToggleCard(it, "Phone", R.drawable.lucide_smartphone, Modifier.weight(1f).fillMaxHeight()) }
        lends.laptop?.let { LendToggleCard(it, "Laptop", R.drawable.lucide_laptop, Modifier.weight(1f).fillMaxHeight()) }
    }
}

@Composable
private fun LendToggleCard(t: LendToggle, title: String, icon: Int, modifier: Modifier) {
    // Between the tap and dibs's next view: say so, and don't send it twice.
    var sent by remember(t.lent, t.action) { mutableStateOf(false) }
    // A tap dibs refused (a locked laptop, a phone out of reach) changes nothing: free it again.
    LaunchedEffect(sent) {
        if (sent) {
            delay(15_000)
            sent = false
        }
    }
    val sub = when {
        sent -> if (t.lent) "Taking it back" else "Lending"
        t.lent -> t.text.ifBlank { "Lent to dibs" }
        else -> "Yours"
    }
    Row(
        modifier.clip(MaterialTheme.shapes.medium)
            .background(if (t.lent) Palette.AccentDim else Palette.SurfaceLow)
            .toggleable(value = t.lent, enabled = !sent, role = Role.Switch) {
                sent = true
                Dibs.host.act(t.action)
            }
            .semantics { stateDescription = if (t.lent) "lent to dibs" else "yours" }
            .padding(horizontal = Space.M, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(icon), null, Modifier.size(18.dp), tint = if (t.lent) Palette.Accent else Palette.Muted)
        Column(Modifier.weight(1f)) {
            Text(if (t.lent) "$title lent to dibs" else title, style = AppType.label, color = Palette.Text)
            Text(sub, style = AppType.small, color = if (t.lent) Palette.Text else Palette.Muted)
        }
    }
}

@Composable
private fun NavBar(tab: Tab, badges: Badges, tasks: Int, yours: Boolean, onTab: (Tab) -> Unit) {
    NavigationBar(containerColor = Palette.SurfaceLow, tonalElevation = 0.dp) {
        for (t in Tab.entries) {
            val count = when (t) {
                Tab.WAITING -> badges.waiting
                Tab.TASKS -> tasks
                else -> 0
            }
            // An older dibs: the old Work tab.
            val (label, icon) = if (t == Tab.TASKS && !yours) "Work" to R.drawable.lucide_hammer else t.label to t.icon
            val dot = t == Tab.RECAP && badges.recap > 0
            NavigationBarItem(
                selected = t == tab,
                onClick = { onTab(t) },
                icon = {
                    BadgedBox(badge = {
                        when {
                            count > 0 -> Badge(containerColor = Palette.Accent, contentColor = Palette.OnAccent) {
                                Text("$count", style = AppType.mono)
                            }
                            dot -> Badge(containerColor = Palette.Accent)
                        }
                    }) { Icon(painterResource(icon), null, Modifier.size(22.dp)) }
                },
                label = { Text(label, style = AppType.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Palette.Accent,
                    selectedTextColor = Palette.Text,
                    indicatorColor = Palette.AccentDim,
                    unselectedIconColor = Palette.Muted,
                    unselectedTextColor = Palette.Muted,
                ),
            )
        }
    }
}

/** Before dibs's first view, or a view from a dibs too old to fill these screens. */
@Composable
private fun Empty(hasView: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Space.XL),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (hasView) "dibs needs an update to fill these screens" else "Waiting for dibs", style = AppType.heading, textAlign = TextAlign.Center)
        if (hasView) {
            Text(
                "Its questions and chat are in Tether meanwhile.",
                style = AppType.body,
                color = Palette.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Space.S),
            )
            Spacer(Modifier.height(Space.L))
            ActButton("Open in Tether", "primary") { Dibs.host.openTether(classic = true) }
        } else {
            Text(
                "Its chat, questions and work show here once the laptop sends them.",
                style = AppType.body,
                color = Palette.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Space.S),
            )
        }
    }
}
