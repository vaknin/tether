# dibs on the phone: its own app inside the Tether APK (task #22, 2026-10-05)

The user chose (question #249, 2026-10-05): dibs gets a real app with sections, **inside the Tether
APK**, not a second APK. Background and the options weighed: `~/Projects/dibs/docs/RESEARCH-phone-app.md`
("(b+)"). Tether stays the link; dibs gets its own launcher icon, its own Recents card and its own
screens in their own Gradle module, fed by a structured payload on the `dibs` channel's view.

## What the user gets

- A **dibs** icon on the home screen, next to Tether's. It opens straight into dibs, which has its own
  card in Recents. Tether's icon stays the Laptop chat, files and the other channels; the Dibs entry in
  Tether's channel list and every dibs notification open the dibs app too.
- Four tabs at the bottom, each with a badge:
  1. **Chat** (home): the conversation with dibs, newest at the bottom, the box pinned. Photos and files
     go with a message (📎: photo picker, camera, any file; or Share → dibs from another app). A question
     asked in the chat carries its buttons on its line, and every question waiting on the user shows
     there too (task #66, 2026-10-06: dibs adds a quiet line for it, no notification of its own; the
     same question as its Waiting card, so answering either closes both). The chat is where the user wants
     questions (word 217): a question's notification opens the chat, not Waiting.
  2. **Waiting**: only what needs the user: every open question as a full card (line, why, Details,
     named buttons, a box for words), phone requests. "Decided for you" moved to Recap (word 127).
  3. **Work**: tasks (state, repo, what it's doing now, how long), the live sessions (busy or idle, what
     they hold), ships in progress. Tap a task for its recent lines; Stop it. (No "Tell it": the user talks only to dibs, word 221.)
  4. **Recap**: "while you were away" and the day's feed. **One entry per finished piece of work** (the user,
     2026-10-05, task #31: a finished task showed three or four near-identical lines, some technical, cut with
     "…" and nothing behind a tap): a short plain line of what changed for them, "You asked: …" (why), whose
     it was; a tap opens it whole (the task's report, its other lines, then all of what they asked, folded
     again since it was written for the agent), a long press copies it. dibs folds the lines (`src/recap.rs`): a task's `dibs did`s, shipped "For you" lines, its
     report and dibs's closing line are one entry, as are a handoff chain's sessions. Below it, **Decided for
     you** (word 127), each readable at a glance (word 155): the project, what was decided as one full plain
     sentence, "Why: …", and Undo. dibs's writer (a cheap Claude call in `dibs watch`) writes these from the
     agent's text; until it has, the agent's words show, marked so. The agent's whole text folds behind
     "Agent's words". Bookkeeping ("Started task X") is left out.
- Above the tabs, two lend toggles, **Phone** and **Laptop** (task #29, the user's word 48): each says "Yours" or
  "lent to dibs" with dibs's line (until when, who is on it), and one tap lends it or takes it back. A dibs
  without `lends` gets the older bar, **"dibs has your phone · Take it back"** (task #19).

## Chat: modern, tidy, never overwhelming (the user, 2026-10-05, words 31 and 32)

- **No swipe-to-delete.** Lines stay put. Long-press a line: Copy, Hide (the old swipe, tucked away).
- **Short by default, full on a tap.** A long line shows its first lines with a soft fade and "More";
  a tap opens it in place. dibs may send a `short` with the full `text` (its confirmations: "Started task
  X, as you said." with the quote behind the tap). dibs no longer cuts the user's quote out of the line
  (`dibs act`); the full text always reaches the phone.
- **dibs's own notes aren't bubbles.** Confirmations made by dibs's code ("Started task …", "Answered …,
  as you said …") are small centred lines in muted text, like a chat app's system lines. Bubbles are the
  conversation itself.
- **Finished cards fold.** A question asked in the chat shows its buttons until it's answered; then the
  card shrinks to one muted line: "✓ Inside Tether · 17:58".
- **Old days fold.** Today (and anything newer than the last six hours) is drawn in full; earlier days
  fold behind "Earlier · Mon 4 Oct · 23 lines", which opens on a tap. Grouped runs, a day header as an
  eyebrow, one time per run.
- "dibs is on it" is three calm dots under the newest line while dibs works. Scrolled up, a small
  "↓ 2 new" pill brings you back.

## Look (the user's call; asked with a mockup page before the screens are built)

- The shared design language (`~/Projects/design`): dark only, Rubik + Geist Mono, Lucide icons, one big
  tabular number with an eyebrow per screen (Waiting: how many wait; Work: how many run), 16 dp gutter,
  radii 8/14/24. Generated, never hand-edited (`docs/DESIGN.md`).
- **dibs's hue.** The channel uses 300 (violet) today. Checked with a protanopia simulation (the user is
  protan): violet's tile and accent are almost the same colour as Tether's blue (ΔE 3 against 8 in
  normal vision), so the two icons would look alike. Candidates that stay apart: 150 (green: ΔE 13 tile,
  17 accent under protan) and 170 (teal: 10 and 12). 60 to 120 sit on the warning amber, 0 to 50 near
  danger red. The mockup page shows them side by side, normal and protan.
- The mark: handshake (today's channel icon), hand (raised: "dibs!"), or another Lucide mark.
- **Chosen (the user, 2026-10-05):** teal 180 and a deadpan face drawn for dibs (`android/dibs/dibs-mark.svg`),
  from five marks based on dibs's personality.

## How it's built

### Tether's phone app (`android/`)
- **`:dibs` Gradle module** (`android/dibs`, Android library, package `com.kivan.tether.dibs`): the
  screens, `DibsActivity`, the model parsed from the payload, and its own generated theme (dibs's hue),
  icons and launcher. It knows nothing of Tether's core; it talks through one narrow interface,
  `DibsHost` (the payload as a flow, the link state, `act`, `say` with files, a UI hold, thumbnails).
  `:app` implements it (`DibsBridge.kt`) over `Channels` and `Core`. Splitting into a second APK later
  is then a packaging change.
- **`DibsActivity`**: its own `MAIN/LAUNCHER` entry (label "dibs", dibs's icon), `taskAffinity`
  `com.kivan.tether.dibs` (its own Recents card), `singleTask`. On start it takes the `ui` hold like
  `MainActivity`, so the link stays up while it's on screen and the notifications clear.
- Generated resources in the module are renamed `ic_dibs_*` (launcher, monochrome, notification), since
  the app's same-named ones would win the resource merge.
- Tether's side: the Dibs entry in the channel list, `tether://channel/dibs` intents, dibs's shortcut
  and its notifications open `DibsActivity` when the view carries the payload (else today's screen).
- Version **0.4.0** (versionCode 14; task #19 takes 0.3.9); the Recap rework is 0.4.3 (17); Your tasks is
  0.5.0 (19); the lend toggles are 0.5.1 (20; 18 went unused); task #64's fixes are 0.5.4 (23; 0.5.2 and 0.5.3 went to parallel tasks); task #66's questions, plain decisions and talking only to dibs are 0.5.5 (24). Never uninstall:
  `adb install -r`.

### The payload (dibs → phone, in the `dibs` channel's view)
The view stays a v1 view (`badge`, `open_tags`, `pin`, `notify` keep working), plus a top-level `dibs`
object. Tether's daemon and `view --check` ignore it; the module reads it. For an app ≥ 0.4.0 dibs sends
no blocks except the lend card (old screens aren't shown any more); older apps keep today's blocks.

```json
"dibs": {
  "v": 1, "now": 1791213484,
  "state": {"brain": "running|idle|off", "busy": true, "line": "dibs is on it", "usage": "…"?},
  "lend": {"until": 1791215000, "holder": "rami-0f", "waiting": 2, "text": "Until 15:40 · …"}?,
  "lends": {"phone": {"lent": false, "text": "", "until": null, "action": "phone-lend"},
            "laptop": {"lent": true, "text": "Until you take it back · rami-0f is on it", "until": 1791240000, "action": "laptop-back"}}?,
  "talk": [{"id": "64", "who": "user|dibs", "text": "full text", "short": "…"?, "ts": 1791212701,
            "note": true?, "ask": {"q": 249, "actions": [{"id","label","style"}], "reply": "r249"?,
                                   "hint": "Add a comment…"?, "outcome": "Inside Tether"?}?,
            "files": [{"id": "<tether file id>", "name": "a.jpg", "size": 123, "image": true}]?}],
  "questions": [{"id": 249, "title": "…", "why": "…", "details": "…"?, "from": "dibs", "repo": "tether"?,
                 "ts": …, "blocking": false, "phone": {"secs": 1800, "unlock": true}?,
                 "actions": [{"id": "y249", "label": "Yes", "style": "primary"}], "reply": "r249"?,
                 "hint": "Answer in your own words…"?}],
  "decided": [{"id": 250, "text": "…", "why": "…", "from": "…", "ts": …}],
  "tasks": [{"id": 16, "name": "…", "state": "running", "repo": "dibs", "minutes": 46,
             "text": "…", "doing": "last line", "background": false}],
  "sessions": [{"name": "…", "repo": "dibs", "branch": "brain-3a", "status": "busy|idle|shell",
                "task": 16?, "holds": ["repo:dibs:master"]}],
  "peek": {"who": "…", "at": …, "lines": ["…"]}?,
  "recap": {"away": {"id": 4, "title": "…", "lines": ["…"], "seen": false}?,
            "feed": [{"id": "t25", "ts": …, "kind": "done|stopped|did|closed|update", "who": "…", "text": "what changed, whole",
                      "repo": "…"?, "why": "first sentence of the ask"?, "asked": "the whole ask"?,
                      "more": ["its other lines, whole"]?, "report": "the task's report, whole"?,
                      "reopen": "<claude session id>"?}],
            "decided": [{"id": 250, "text": "…", "why": "…", "from": "…", "ts": …, "undo": true,
                         "project": "Tether", "plain": true, "raw": "the agent's whole text"}]?},
  "badges": {"waiting": 3, "work": 1, "recap": 1}
}
```

### Actions (phone → dibs, queued as today, `{"action", "value"?, "uid", "ts"}`)
Today's ids stay: `a|d|y|n|x|k<id>`, `r<id>` (typed answer), `w<id>` (away "Got it"), `h<talk id>`
(hide), `say` (`value.text`). New: `say` with `value.files` (ids of files sent to the channel, below),
`peek` (`value.who`), `tell` (`value.task`, `value.text`), `stop` (`value.task`), `reopen` (`value.reopen`: an open
Recap row's "Open on laptop" resumes that saved chat in a tab on the laptop, task #32), `undo` (`value.item`:
handed to the brain as the user's request), `phone-back` (task #19), `phone-lend`, `laptop-lend`, `laptop-back` (task #29: a toggle sends its
`action` as is; a tap seen more than 10 min late lends nothing, and taps made while an agent works the phone's
screen lend nothing either).
`ack-decided` (`value.items`: the decision ids the Waiting tab showed; "Got it to all", questions
among them stay open; unused since 0.5.x, when decisions moved to Recap with Undo only). `badges.waiting` counts questions only.

**Questions' buttons and words (task #66, 2026-10-06).** Each button says what a tap does (dibs's
docs/PLAN.md, "Question buttons", lists every kind). An `x<id>` button (a close: "Drop this
question", "Never offer it") comes only where it differs from the second one, so a card has two or
three. Every question but the weekly retro's has `reply` and a `hint` (its box's placeholder): the
words sent alone are `r<id>` as before; typed and then a button tapped (not `x`), the tap carries
them as `value.comment`, which dibs hands to its brain (the tap still answers).

### Files to dibs (Tether's core and daemon)
- New body `Body::ChannelFile { channel, name, size, sha256 }`, appended to the enum (an older peer
  skips the frame and the sender keeps it queued; the ship installs the daemon before the phone). It's
  a file in every other way: stored as kind `file` with its `channel` set, so resume, sha256, Ack,
  cancel, progress and `TransferService` all work unchanged.
- The receiver puts a channel file in `<state>/channels/<channel>/` (not Downloads), with no toast,
  no clipboard, out of the Laptop chat and its unread count. `tether thread <ch> --json` lists it as
  `{"id","from_me","ts_ms","file":{"name","size","state","path"?}}`, and `tether watch` sends the
  channel's notice when one completes.
- The phone sends each file with `send_channel_file`, then one `say` action naming their ids with the
  caption. dibs applies it once every named file has arrived (it waits otherwise, and a later notice
  retries), keeps the paths with the talk line and hands the brain the caption plus the paths, so it can
  read the images. The phone draws its own thumbnails (`Thumbs`, by file id).

### dibs (`~/Projects/dibs`, own branch)
- `tether.rs`: the payload (`dibs_json`), gated on `phone_app` ≥ 0.4.0; the new actions; files on `say`.
- `talk.rs`: `files`, `short` and `note` columns; `dibs act`'s lines keep the full quote (`short`
  without it) and are notes. Coordinate with the brain sessions working in `talk.rs`/`words.rs`.

## Order of work
1. This plan; mockups page and the look question to the user (escalate, with the page's link).
2. Rust: `ChannelFile` in core + ffi + daemon, tests over loopback.
3. dibs: payload, actions, files (branch in `~/Projects/dibs`), tests.
4. Android: the `:dibs` module skeleton, `DibsHost`, `DibsActivity`, wiring; the screens once the
   user has picked the look; unit tests for the payload parser and chat folding.
5. Independent review; on the Pixel with screenshots when the user is home (`dibs phone request`);
   `dibs ship` in Tether (daemon first, then the app) and in dibs.

## Later (not in this task)
- dibs's replies as a MessagingStyle conversation notification (Person "dibs").
- The Quick Settings lend tile, a home-screen widget, the voice button, files from dibs to the phone.

## Your tasks (task #37, Capture note #68; planned 2026-10-05, **built 2026-10-05** in the app, 0.5.0)

### As built (app side, build order steps 3 to 5)
- `:dibs`: `Payload.kt` (`YourTask`, `TaskResult`, `Shipped`; `yours` is null from a dibs that doesn't send it,
  which keeps the old Work tab), `TasksModel.kt` (grouping, order, state words, the steps summary), `Transcript.kt`
  (parser and rows), `Markdown.kt` (REPORT.md as blocks), all unit-tested (`TasksModelTest`, `TranscriptTest` on
  `src/test/resources/transcript-33.json`, `MarkdownTest`, `PayloadTest`).
- Screens: `ui/TasksTab.kt` (Work's content is `workItems` in the "dibs's own work" fold), `ui/TaskPage.kt`,
  `ui/TranscriptScreen.kt`, `ui/ReportScreen.kt`. The chat's parts moved to `ui/ChatParts.kt`; each chat has its own
  `Composer` (draft, picked files, `say`); the task page's own box went with word 221.
- Back stack: `Dibs.pages` (`Page.Task|Transcript|Report`), popped by system back; `DibsActivity.EXTRA_TASK`
  (`com.kivan.tether.dibs.TASK`, a Long) opens a task. The other tabs keep their scroll under a page
  (`SaveableStateHolder`); the Chat tab still opens at its newest line.
- `:app`: `DibsHost.channelFile(prefix)` reads the core's new `app_files` (ffi) listing, again on each received
  channel file (`Core.channelFiles`); with no node running it reads `state/channels/dibs/`. `send` takes the action
  (`task-say` with the task). Fetched files are pruned at start and on each arrival: the newest per task and kind is
  kept, then dropped after 14 days or 7 days after its task's `ticked` (while the view still lists it). Every dibs
  notification opens the chat (word 221), a task's too.
- Differently from the plan: a task's page opens at the top (its report) unless its agent replied since the page was
  last open (then at the chat); ticking off from the page goes back to the list; "Getting it…" offers Try again after
  90 s. Icons added through `regen-look.sh`: list-checks, arrow-left, refresh-cw, chevron-right, scroll-text,
  circle-check.

**The user's words (note #68):** with eight sessions at once they lose track of what is happening. When a task
*they* asked for finishes, they want to read what it found in a place that waits for them and doesn't vanish after
some minutes, and they want to be the one who ticks it off. They also want to read the whole transcript if they
choose, and to talk to the agent that did the work: "ask him questions about it in order to understand what
happened, why did he do what he chose to do, and perhaps iterate with him … or change a direction, or pivot." Tasks
dibs starts by itself are dibs's business. **Scope agreed with dibs (question #301):** the user's tasks wait in the
dibs app until ticked, grouped by project. Each one shows its plain summary and its full transcript, and the user
can talk to that task's own agent from the phone. No per-project dibs chats and no urgency meter. The dibs side of
the plan is in `~/Projects/dibs/docs/PLAN.md`, "Your tasks on the phone".

### What the user gets
- **A Tasks tab in place of Work.** It holds every task the user asked for, grouped by project (tether, dibs,
  the research folders under "Other"), from its start until they tick it off. Running ones are listed too, so a
  task's whole life is in one place.
  - **A row:** a plain title, its state ("Needs you", "Working · 40 min", "Done 21:36", "Stopped") and one line
    (what changed, or what it's doing now). A done task the user hasn't opened yet is bold.
  - **Order:** a group with something that needs the user comes first, then the others by newest activity. Inside
    a group: needs you, done and unread, working, done and read, stopped or failed.
  - **No big number** (task #64, the user's word 122: they never used "Yours · 40 open"). Instead, a line in the
    accent says what the tab's badge counts: "2 for you: 1 finished to read, 1 asking you" (none when nothing
    does), and a done task not opened yet says "Done 07:54 · new". The phone counts the badge itself from `yours`
    (the same rule as `badges.tasks`), so it drops as soon as a task is opened here.
  - **Titles** are dibs's, cleaned on the phone (`plainTitle`): an aside in brackets goes, including one cut open
    ("Cut phone notification clutter (the user"), and a title that is only a tag before a colon in the user's
    words ("PLAN ONLY") takes the words after it. dibs's `yours::title_from` still makes those titles; fixing it
    there is left for the dibs side.
  - **The laptop** (word 145): a "Laptop" line at the top, memory, load, builds running and waiting, agents, from
    `state.laptop` when dibs sends it.
- **dibs's own work** (its background tasks, the live sessions, ships) folds into one line at the bottom,
  "dibs's own work · 3", which opens on a tap. It holds today's Work tab content, and nothing in it pings.
- **One ping per task** when it finishes, when it asks the user something, or when its agent replies to the user. A
  tap opens the task. The ping goes away when the task is opened or ticked off. Tasks dibs started by itself never
  ping.
- **Ticking off** is the user's call alone; nothing is ticked automatically. A ticked task leaves the list and
  goes under "Ticked · 12" at the bottom for a week, where Untick brings it back.
- **Recap** stays the feed of everything. A Recap row that belongs to one of the user's tasks opens that task.

### Screens
1. **The Tasks tab**, as above. A long press on a row gives Tick off and Open on laptop.
2. **The task page**, from top to bottom:
   - The title and state, when it started, how long it ran and when it finished.
   - Its open questions as the same cards as in Waiting. Answering in either place closes the question in both.
   - **What it did:** the task's report, in full.
   - **Result:** a "Report" chip when the task wrote a REPORT.md (it opens in a reader on the phone), and "Shipped to
     tether · 3 changes" with their "For you" lines.
   - **You asked:** folded (it was written for the agent).
   - **Transcript.**
   - The earlier messages with its agent, read-only (below), and **Ask dibs about it**.
   - The top bar has **Tick off ✓** once the task is finished, Stop while it runs, and Open on laptop in the ⋮ menu.
3. **The transcript** reads like a document from the top, with "↓ End" to jump.
   - The task's prompt comes first, folded.
   - The agent's text is shown in full.
   - The user's own lines (typed at the laptop or sent from the phone) show as their bubbles.
   - dibs's notes and "keep going" pushes show as muted system lines.
   - Tool calls fold into one line per run, e.g. "12 steps: read 5 files, edited recap.rs, ran cargo test ✓". A tap
     lists the steps, and a tap on a step shows its input and output, each cut at 2 KB, saying so when cut.
   - Seams between the task's sessions read "Continued in a fresh context" (after a handoff or /clear) or "Reopened
     22:10".
   - A running task's transcript is a snapshot: "As of 21:40 · Refresh".
4. **No chat with the task's agent** (the user, word 221, 2026-10-06: "I ONLY talk to dibs, never to other agents").
   Until then (2026-10-05) the page had a box that went straight to the agent; it's gone.
   - The messages exchanged before stay, read-only, under "Earlier messages", in the dibs chat's bubbles.
   - **Ask dibs about it** at the bottom opens the dibs chat with "About <title>: " typed in. dibs passes on what's
     for the agent (`dibs task tell`).
   - "<title> is on it" shows as dots while its session is busy.

### Data (dibs → phone)
These are new keys in the `dibs` payload, sent only to app 0.5.0 and newer. Older apps keep `tasks` and the Work tab.
```json
"yours": [{"id": 31, "title": "Recap: one entry per finished job", "name": "the-dibs-app-s-recap-tab",
           "project": "tether", "state": "needs|working|paused|done|stopped|failed", "ts": 1791230000,
           "started": …, "finished": …?, "minutes": 95, "line": "what changed, or doing now",
           "asked": "the user's words, whole", "report": "whole"?,
           "result": {"report_md": true?, "shipped": [{"repo": "tether", "for_you": ["…"]}]?}?,
           "questions": [249], "busy": false, "live": true, "unread": true, "ticked": …?,
           "talk": [{"id": "t31-4", "who": "user|agent|note", "text": "whole", "short": "…"?, "ts": …,
                     "files": [{"id", "name", "size", "image"}]?}]}],
"badges": {"tasks": 2, …}
```
- `badges.tasks` counts the done-and-unread tasks plus the ones that need the user.
- Ticked tasks stay in the list for 7 days with `ticked` set. `talk` holds the last 30 lines; older ones are in the
  transcript.
- A Recap entry of one of the user's tasks carries `task: <id>` and leaves out `report` and `more`, because the task
  page has them.
- **Size.** Today's view is 93 KB, it is republished on every change, and the frame cap is 4 MB. A task adds about
  5 KB, but dropping the Recap duplicates takes most of that back. dibs keeps the view under 256 KB by trimming the
  oldest talk lines first.
- **Transcripts and REPORT.md never ride in the view.** The phone asks for one, and dibs sends it as a channel file.
  - Measured on tasks #23, #29, #31, #32 and #33: the raw transcripts are 0.7–6 MB, of which 4–30 KB is text,
    plus 30–180 tool calls. Rendered with each tool's input and output cut at 2 KB, that makes about 50–400 KB, and
    gzip shrinks it about 4×.
  - Format (`transcript-<task>-<rev>.json.gz`):
    `{"v":1, "task":31, "rev":"…", "as_of":…, "parts":[{"session","how":"start|handoff|clear|reopen","ts"}],
    "turns":[{"who":"ask|user|agent|note|steps","ts", "text"?, "src":"laptop|phone"?,
    "steps":[{"tool":"Edit","line":"Edit src/recap.rs","in"?,"out"?,"error":true?}]?}]}`.
  - The phone shows "Getting it…" (the link is up while the app is on screen). It keeps the newest file per task and
    deletes it 7 days after the task is ticked, or after 14 days.

### Actions (phone → dibs)
- New:
  - `task-say` (`value.task`, `value.text`, `value.files`?): sent only by apps before 0.5.5. Since word 221 dibs hands
    it to its brain as the user's chat line about that task, never to the agent.
  - `tick` / `untick` (`value.task`).
  - `seen` (`value.task`): sent when its page opens. It clears `unread` and the task's ping.
  - `fetch` (`value.task`, `value.what`: `transcript|report`).
- Unchanged: `stop`, `reopen` and the question answers. `tell` stays for older apps.

### What changes in Tether
- **Daemon:** a new `tether send --channel <ch> <file>…` (socket op `send_channel_file`), so dibs can hand the
  phone a file on its channel.
  - The core already sends `ChannelFile` both ways, and the phone already keeps channel files in
    `channels/<ch>/`, out of Downloads and the chat (`Core.kt`). The core doesn't change.
  - A loopback test in the laptop → phone direction.
- **`:app`:**
  - `DibsHost` gets `channelFile(name)`, a flow of the file once it has arrived, read through `appFiles`.
  - A dibs notification whose tag is `task:<id>` opens `DibsActivity` on that task (intent extra `task`).
  - Old dibs channel files are pruned.
- **`:dibs`:**
  - `Payload.kt` reads `yours` and `badges.tasks`.
  - `TasksTab.kt` replaces `WorkTab.kt`, whose content moves into the fold at the bottom.
  - New `TaskPage.kt`, plus `TranscriptScreen.kt` with its parser `Transcript.kt` (unit-tested on a real rendered
    file).
  - The task chat reuses the dibs chat's parts, so these move out of `ChatTab.kt` (813 lines) into shared parts
    first.
  - A small back stack inside `DibsActivity`: tab → task → transcript or report.
- **Version 0.5.0.** The ship installs the daemon first, then dibs, then the app.

### Build order (each step shippable and reviewed)
1. Tether daemon: `send --channel` and its test. Nothing uses it yet, so it ships alone.
2. dibs: the data and actions, delivery to the task's agent and its replies coming back, the transcript renderer
   (`dibs task transcript`, also useful at the laptop) and `fetch`. The plan is in dibs's PLAN.md.
3. App: the Tasks tab and the task page (summary, questions, result, tick off, stop, Open on laptop). This is
   useful before the chat and the transcript exist.
4. App: the task chat.
5. App: the transcript and report readers.
6. An independent review of both repos. Check on the Pixel with screenshots (`dibs phone request`), then `dibs ship`
   in the order above.

### Open questions for the user (the defaults go ahead if they say "build it" without answering)
1. **Should the Tasks tab replace Work**, with dibs's own work folded at its bottom? Default: yes. Four tabs stay
   four.
2. **Where should a finished task's chat reopen** when the user messages it from the phone? Either in their herdr
   `work` (visible but not focused, so they can watch it when back) or out of sight in dibs's hidden session.
   Default: where the task ran, which is `work` for their tasks.
3. **Should every reply from a task's agent ping the phone**, or only finishing and asking? Default: a reply to a
   line the user sent pings (they're waiting for it). There is one ping per task, updated in place.
4. **Should tasks also be ticked off at the laptop?** The widget's FINISHED section (task #32) would get ✓ and
   an unread dot, with the same state as the phone. Default: yes, it's small.

**Not planned:** per-project dibs chats, and an urgency meter. Two things already cover what the note asked for:
only the user's own tasks ping, and "Needs you" sorts first. If the list is still too long after real use, a "this
one matters" flag would be the next step.

## Task #64 (2026-10-06): the Chat box, pictures, usage

- **The Chat box went missing** (the user's word 117: they had to write through the Tasks tab). Cause, found with
  the JVM screen tests (`ScreensTest`, Robolectric and Roborazzi): with the keyboard open on a small screen the
  header and the lend toggles left the chat no room, and the box was squeezed out of the layout (at a 45 %
  keyboard on 320×568 dp it wasn't laid out at all). Now the toggles step aside while typing, as the tab bar
  already did, and `ScreensTest` checks the box at 30, 45 and 55 % keyboards. PNGs:
  `./gradlew :dibs:testDebugUnitTest -Pscreenshots --tests '*ScreensTest*'` → `dibs/build/outputs/roborazzi/`.
- **Typed answers on every question card** are task #66's (dibs-tether-questions), on both sides.
- **Pictures full screen** (word 125): a tap on a thumbnail opens `ImageViewer` (pinch or double tap to zoom, drag
  while zoomed, back or ✕ closes). `DibsHost.image(id, maxPx)` gives the sharpest copy: a file dibs sent is kept
  in its channel folder; the user's own sent copies are gone, so theirs show the 720 px thumbnail. Images dibs
  sends now get a thumbnail on arrival (`Core`), so they show as pictures, not chips.
- **Usage** (word 131): the header shows each window, "5h 88% · resets 12:20", amber from 80 %, from
  `state.limits` (`{"ts", "windows": [{"name", "pct", "resets"}]}`, added on the dibs side by task #66).
