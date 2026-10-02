# Tether — own Rust replacement for KDE Connect (idea 338810ec)

## Context
The idea note: KDE Connect is bloated and unreliable. Build a leaner Rust version: simpler pairing, far fewer
options, plus a **chat** between laptop and phone that also delivers messages to an offline
device later, peer-to-peer with no cloud. It must keep the current "phone sends screenshot → laptop
clipboard" behaviour. (The note's first line, KDE Connect dark theme, is dropped: the app goes away.)

Decisions so far (user):
- Connect anywhere: direct on LAN, otherwise through **n0's free public iroh relay**. Everything stays
  end-to-end encrypted, so the relay can't read it. It is meant for hobby use: no SLA, rate-limited, and
  iroh has to be kept current. Moving to a self-hosted relay later is a config change.
- Phone → laptop: an Android **Direct Share tile "Laptop"** at the top of the share sheet, like
  KDE Connect's, not auto-sending every screenshot.
- Keep: send files and screenshots both ways, **phone media control from the laptop** (bar widget),
  **ring phone**. Drop: clipboard sync, notification toasts, the 30 other plugins.
- Laptop chat UI (picked from the mockups at https://claude.ai/artifact/1U3QvzxTCqRn5oYohQ1Cq9):
  **one QML chat component with two ways in**. Clicking the bar badge (which shows the unread count)
  opens it as a dropdown, and SUPER+M opens the same component as a centered panel.

Hidden consumers of KDE Connect found on the laptop, which all have to move to Tether:
| Consumer | Today | After |
|---|---|---|
| `~/.local/bin/kdeconnect-share-notify` + user unit | dbus-monitor `shareReceived` → `wl-copy` image (magick→PNG for non-PNG/GIF) + `omarchy-notification-send --image file:// --exec xdg-open` | built into the daemon; unit removed |
| `kivan.phonemedia` widget | MPRIS players with prefix `org.mpris.MediaPlayer2.kdeconnect.` | daemon publishes MPRIS `org.mpris.MediaPlayer2.tether.pixel`; change `busPrefix` |
| `~/.local/bin/dictation` (`playerctl -l \| grep '^kdeconnect\.'`), voxtype `pause_media_ignored_players` | player name | grep/ignore `tether` |
| scheduled-jobs `scripts/notify`, `/usr/local/bin/suspend-guard` (source `~/.config/laptop/etc/local-bin/`) | `kdeconnect-cli --ping-msg` | `tether ping "msg"` (queued if offline; now works off-LAN too) |
| `headless-browser/scripts/rami-login.ts` | reads phone notifications over KDE Connect D-Bus for the SMS code | `tether notifications --json` (phone mirrors active notifications, not shown as toasts) |
| nftables `1714-1764` tcp+udp, packages list `kdeconnect` | | open one UDP port for Tether instead; remove the package |

## Architecture
Project `~/Projects/tether`, a Cargo workspace plus a Gradle project, both on GitHub (private).

```
crates/core     protocol, SQLite store (rusqlite bundled), sync/outbox, file transfer; no platform code
crates/daemon   `tether` binary: daemon (systemd user unit) + CLI subcommands, talks to daemon over
                $XDG_RUNTIME_DIR/tether.sock (JSON lines)
crates/ffi      uniffi bindings of core for Android (cdylib, arm64-v8a via cargo-ndk)
android/        Kotlin + Compose app (com.kivan.tether), same toolchain as chordhand
shell/          Omarchy plugin(s) for the chosen chat UI + bar badge
```

**Transport**: iroh 1.x (1.3.0 is the latest, 2026-09-28; re-check at implementation time). Each device is an Ed25519
endpoint key. ALPN `tether/1`. Discovery: n0 preset (DNS/pkarr + relay) + `iroh-mdns-address-lookup`
(0.5, n0-maintained) for fast LAN finding. Accept handler drops any peer whose key isn't the paired one.
Daemon binds a fixed UDP port (e.g. 47114) so the firewall needs one LAN rule.

**Why one Rust core with uniffi on the phone, not a Kotlin re-implementation**: the protocol, outbox and
resume logic exist only once and are tested once. uniffi is still 0.x, but Mozilla ships it in Firefox
for Android, so it meets "production-proven". It's the one exception to the 1.0+ rule. Check the
current uniffi and cargo-ndk versions before adding them.

**Pairing (the entire flow)**: `tether pair` shows a QR in the terminal (laptop endpoint id + one-time
32-byte secret, valid 5 min). Phone app → "Pair" → scan → connects, proves secret, both store the
other's public key. No device list, no plugin toggles. `tether unpair` wipes it.

**Protocol**: one QUIC connection; control stream with length-prefixed postcard frames:
`Hello{max_seq_seen_from_you}`, `Chat{id(uuid), seq, ts, text | file_ref}`, `Ack{seq}`, `Ping{text}`,
`Ring/StopRing`, `MediaState{title, artist, pos, dur, playing, volume}`, `MediaCmd{…}`,
`Notifs{list}` (phone→laptop snapshot+diffs). Files: one uni-stream per file
`{id, name, size, sha256, offset}` + bytes, resumable from offset.

**Offline delivery**: every chat message, file offer and ping goes into the sender's SQLite outbox
with a per-sender monotonic `seq`. On every (re)connect both sides send `Hello` with the highest seq
they hold from the other, and the other resends everything after it. The receiver dedupes by id and
acks, and acked rows are marked delivered. Messages arrive when both devices are online at the same
time, which the relay makes almost always.

**Phone app** (Kotlin/Compose):
- **Wake, then connect (battery).** When idle, the phone runs no endpoint and holds no connection.
  The laptop wakes it with a content-free high-priority FCM data message. The phone then runs a
  short foreground service (type `remoteMessaging`) that dials, syncs, and stops after about 60 s
  with no traffic. It stays connected only while media is playing, the chat is open, or the app is
  in the foreground (`StayConnected`). The phone sends its FCM token as `Frame::PushToken`.
  Details are in CLAUDE.md. Check battery use after a day, under Settings → Battery for Tether.
- Direct Share: `res/xml/shortcuts.xml` share-target + `ShortcutManagerCompat.pushDynamicShortcut`
  ("Laptop", long-lived, category `com.kivan.tether.SHARE`) + `ACTION_SEND/SEND_MULTIPLE` activity
  that queues files. Text shares go into the chat as messages.
- Chat screen (Compose), pairing via QR scan (CameraX + ML Kit barcode, or ZXing embedded; pick at impl).
- `NotificationListenerService`: mirrors active notifications and, via `MediaSessionManager`,
  publishes the active media session's state and applies commands (play/pause/next/prev/seek/volume).
- Ring: max-volume alarm-stream sound + full-screen notification with Stop.
- Receiving files saves them to Downloads/Tether via MediaStore and shows a notification.

**Laptop daemon** (`tether daemon`, systemd user unit, `Restart=on-failure`):
- FCM wake sender (`crates/daemon/src/fcm.rs`, done): FCM HTTP v1 with a service-account JWT (ring
  RS256, reqwest; both already built for iroh) from `--fcm-key`/`$TETHER_FCM_KEY`, default
  `~/.config/tether/fcm-service-account.json` (0600). It wakes when an item is queued and the phone
  isn't connected, at most once per 30 s, and once at start if the outbox isn't empty. The payload is
  `data {t: wake}`, `android {priority: high, collapse_key: wake}`. `UNREGISTERED`/404 drops the
  token. With no key or a failed send, the node's backoff redial still delivers.
  Live auth check: `cargo test -p tether -- --ignored fcm_live`.
- Received image → clipboard as image data (`wl-copy --type`, PNG/GIF as is, else convert to PNG with
  EXIF orientation via the `image` crate instead of magick) + `omarchy-notification-send -u normal
  --image file://… "Pixel 8 sent a file" <name> --exec xdg-open <path>`. Same for chat messages
  (toast opens the chat UI). Files land in `~/Downloads`. (Logic ported from
  `~/.local/bin/kdeconnect-share-notify`.)
- MPRIS server via zbus (`org.mpris.MediaPlayer2.tether.pixel`, Player + Volume + Seek). Only
  present while the phone has an active session.
- CLI: `tether pair|unpair|status|send <files…>|msg <text>|ping <text>|ring|notifications --json|json|watch`
  (`json` = chat snapshot, `watch` = event stream for the shell UI).
- Optional: add "Send to phone (Tether)" to the Omarchy share menu
  (`~/.config/omarchy/extensions/omarchy-menu.jsonc`), which covers the other idea 3c52ab0d.

## Phases
1. **Core + daemon on the laptop.** Two `tether` instances on one machine (different state dirs)
   pair, chat, queue offline, resume files. Unit tests: outbox resend after reconnect, pair rejects
   unknown key, file resume at offset.
2. **Android app.** Pair, chat, Direct Share tile, receive files. Install over adb
   (`Android_FBR2HM5L.local:5555`, `-s` explicit; check focus before any `adb input`). Keep the
   release keystore safe (lesson from babah).
3. **Parity with KDE Connect** (done 2026-10-02, live-checked): MPRIS/media, ring, notifications mirror, ping. Switch
   phonemedia `busPrefix`, dictation grep, voxtype ignore list, scheduled-jobs `notify`,
   suspend-guard, rami-login.ts. Run both side by side for a few days.
4. **Laptop chat UI** (done 2026-10-02; user confirmed send, 📎, badge, toast, cancel, Ctrl+V attach, image to clipboard). Plugin `kivan.tether` in `~/Projects/tether/shell/`, installed with an
   `install.sh` symlink into `~/.config/omarchy/plugins/` like capture's Ideas panel. It has two parts:
   - a `bar-widget`: the chat badge with the unread count, which opens the dropdown;
   - a `panel` (`keepLoaded`): the same `Chat.qml` centered, opened by SUPER+M through IPC target
     `tether` (`omarchy-shell tether toggle|open`).

   Data comes from a long-running `tether watch` Process (JSON lines) plus `tether json` for the
   snapshot; sending goes through `tether msg` / `tether send`, with a file picker for 📎 and Ctrl+V
   for images.

   Before writing any QML, check how `kivan.ideas/Ideas.qml` and the `kivan.phonemedia` popup are
   built and copy their PanelWindow and popup patterns. Size the dropdown at about 330×400 and the
   panel at about 440×470 to fit 1024×576. Add the SUPER+M bind in `~/.config/hypr/bindings.lua`
   (first check that it's free) and validate with `hyprctl reload` + `hyprctl configerrors`.

   After QML edits, run `omarchy restart shell`. Toasts for chat messages run
   `omarchy-shell tether open` when clicked.
5. **Cut over** (done 2026-10-02: KDE Connect removed from the laptop and the phone; idea ticked off). One sudo step: deploy nftables (drop 1714-1764, add Tether UDP port for LAN),
   reinstall suspend-guard, `pacman -Rns kdeconnect`. Remove kdeconnect-share-notify unit and script,
   drop `kdeconnect` from `packages-repo.txt`, uninstall KDE Connect on the phone. Read
   `~/.config/system-notes.md` first and append an entry (firewall + package change). Update memories
   (kdeconnect-share-notify, phonemedia, voxtype) and run `laptop save` for files under
   `~/.config/laptop/paths`. Tick the idea off.

## Verification
- `cargo test` in the workspace; `./gradlew :app:testDebugUnitTest`.
- Share a screenshot from the phone via the "Laptop" tile → toast appears under DND, `wl-paste -l`
  shows `image/png`, pasting in an app pastes the image.
- Phone idle with the screen off for 10 min, then `tether msg hi` → it arrives within a few seconds
  (FCM wake). `adb shell dumpsys deviceidle force-idle` (Doze) gives the same result.
- After a day, Tether's battery use in Settings is small; on mobile data there are no wakeups
  apart from real deliveries.
- Airplane-mode the phone, send 3 laptop messages + a file → they show as queued. Turn the phone back
  on (mobile data, not Wi-Fi) → they arrive via relay, laptop shows ✓✓.
- `omarchy-shell phonemedia status` shows phone track; play/pause/seek/volume from the bar work.
- `tether ping test` from a scheduled job arrives; `tether ring` rings; rami-login gets the SMS code.
- After cut-over: `ss -ulpn` shows only Tether, nft rules listed, nothing references kdeconnect
  (`grep -r kdeconnect ~/.config ~/.local/bin ~/.claude/skills`).

## App channels (2026-10-02; branch `teen-channel`)
Apps built on Tether get their own channel instead of the chat. The owner wants this general
("like IRC, but better"), with teen-app's חפיפה as the first user. Decided with the owner on
2026-10-02 (design: https://claude.ai/artifact/D4342Ja8c9GSVi3XvkXuZw): declarative blocks drawn
natively on the phone and in the panel; laptop input through the panel's channel mode (SUPER+N);
a channel list as the phone's root screen; a TOML manifest per channel; generic first.

**Built so far (Rust side, 2026-10-02):** everything under "Wire and store", "Manifest" and
"Daemon and CLI" below except the panel and the phone. Core: `Body::App{channel, data, replace}`,
kinds `app`/`view` (both out of the chat and the unread count), view replace on enqueue and receive,
`Event::App{…, view}`, `app_view`, `app_history`, `app_local`, `app_channels`, the 30-day prune.
Daemon: manifests (`apps.rs`, `toml` crate; a bad file is logged and skipped), `exec` through
`sh -c` with `~` expanded (30 s relaunch gap; at start, for channels with items waiting),
`_channels` published at start and on reload when it differs from the stored one, socket
`app_action` (fills `from:"laptop"`, `uid`, `ts` when missing), `channels`, `channels_reload`,
`thread`, and the watch notice. Socket sends refuse `_`-names (the daemon's own). FCM: a queued view
wakes only with a non-null top-level `notify`. CLI: `post`, `channels`, `view`, `action`, `thread`.
ffi: `Event::App.view`, `app_view`, `app_history` (`AppHistoryItem`); `send_app` now dials too.
Manifest defaults: `title` = name, `glyph` = its first letter, `dir` ltr, `kind` app with `exec`
else thread, `share` false, `notify` true; channels list order is by name.

### Model
A channel is a room with one live **view** (state) plus a timeline of **items**, as Matrix keeps
room state apart from the timeline. The laptop app owns the data and publishes its view; the phone
and the panel draw it and send back **actions**, which are queued items, so offline taps merge
without a CRDT. A channel with no app is a plain **thread** (ntfy-style `tether post`).

### Wire and store
- `Body::App { channel, data, replace: bool }`. `replace = true` is a view: stored with kind
  `view` (else `app`). Storing a view, on either side, deletes the older views of that channel from
  the same sender, whatever their state (only the newest matters; also bounds the DB).
- `Event::App { id, channel, data, from_me, view }`.
- `app_pending` lists only kind `app` (actions, posts, replies), never views.
- `Node::app_view(channel)`: the newest view on the channel (mine on the laptop, the peer's on the
  phone; in practice: the newest view row of that channel).
- `Node::app_history(channel, limit)`: kind `app` items both ways, oldest first (threads).
- `Node::app_local(channel, data)`: an item from a local UI (the panel), stored as if received
  (from_me false, kind `app`) and emitted, so the subscribed app (or a start on demand) takes it.
- Taken/delivered `app` items older than 30 days are pruned when the store opens.

### Data (JSON in `data`; Tether reads only what it draws)
- View (app → phone/panel, `replace`): `{"v":1, "blocks":[…], "badge":N?, "notify":{"title","text"}?}`.
  `notify` is shown as a notification when that view arrives on the phone (if the channel's
  manifest allows it); `badge` is the channel's count in the list and the panel strip.
  A queued view wakes the phone (FCM) only when it has `notify`; otherwise it waits for the next
  connection, since apps republish their view on every small change.
- Action (phone/panel → app, queued): `{"action":"<id>", "from":"phone"|"laptop", "uid":"<uuid>",
  "ts":<sender's clock, ms since epoch>, "value":…?, "fields":{…}?}`. `uid` is made by the sender; an app that turns it into a list item
  uses it as the item id, so the sender can drop its pending echo.
- Live (`Frame::App`): `{"patch":{"<block id>":{…fields to replace}}}` (progress text, say).
- Thread post (laptop → phone, queued): `{"post":{"text":"…","actions":[{"id","label"}]?}}`;
  a reply is `{"text":"…","from":…}` or an action.
- Channel `_channels` (daemon → phone, a view): `{"v":1,"channels":[{name,title,glyph,accent,dir,kind,share,notify}]}`.

### Blocks (`{"type":…, "id":…?}` plus the fields below; unknown types are skipped)
- `header {title, subtitle?}`
- `notice {text, tone: info|ok|warn|error}`
- `text {text, mono?}`: multi-line, selectable (a report, say).
- `list {items:[{id, title?, text, meta?, chips?:[str], actions?:[{id,label,confirm?}]}], empty?}`:
  an item action sends `{"action":<action id>,"value":{"item":<item id>}}`.
- `checklist {items:[{id,label,checked}]}`: a tap sends `{"action":<block id>,"value":{"item","checked"}}`.
- `compose {id, placeholder, submit, chips?:[{id,label}], multi?}`: sends
  `{"action":<id>,"uid","value":{"text","chips":[ids]}}`. Until a view lists an item with that
  uid, the renderer shows the text as a pending (⏳) item at the end of the list before it.
- `form {id, fields:[{id,label,multi?,placeholder?,value?}], submit}`: sends `{"action":<id>,"fields":{…}}`.
- `progress {id, text, cancel?:{id,label}}`: indeterminate; live patches change `text`.
- `buttons {items:[{id,label,style?:primary|danger|plain,confirm?}]}`: sends `{"action":<id>}`.
- `web`: reserved (a webxdc-style bundle, later).

### Manifest `~/.config/tether/apps/<name>.toml` (name: `[a-z0-9_-]+`)
`title`, `glyph` (one emoji or letter), `accent` (`#rrggbb`), `dir` (`ltr`|`rtl`), `kind`
(`app`|`thread`), `exec` (a shell command; `~` expanded; started on demand as before), `share`
(accept Android share text into the compose), `notify` (bool). It replaces the earlier bare
executable. The daemon reads the folder at start and on `tether channels --reload`, and publishes
`_channels` when the list changed. A channel with items but no manifest shows as a thread.

### Daemon and CLI
- Socket: `app_send` (replace = view), `app_subscribe`, `app_action{channel,data}` (local route),
  `channels` (manifests + newest view + badge each), `channels_reload`, `thread{channel,limit}`.
  `watch` also streams `{"type":"app","channel","view":bool}` change notices (no data) for views and
  thread items, so the panel can re-read.
- CLI: `tether post <ch> <text…> [--action id:label]…`, `tether channels [--json] [--reload]`,
  `tether view <ch> [<file>|-]` (publish a view), `tether action <ch> <json>`, `tether thread <ch> [--json]`.
- Panel (QML): **built 2026-10-02, not live yet** (the live plugin links to the main checkout).
  `Channel.qml` draws the blocks (RTL by mirroring; drafts kept by block/field across view
  reloads; ⏳ echo until a view lists the uid; confirm = second press within 4 s); `Panel.qml`
  has the strip (Chat + channels with badges), Ctrl+1…9, Ctrl+K switcher, reload on watch `app`
  notices, and IPC `channel <name>` / `toggleChannel <name>` (`open` keeps no argument, since chat
  toasts call it bare and `qs ipc` rejects a wrong arg count). SUPER+N → `omarchy-shell tether
  toggleChannel teen`, to bind after merge. `TETHER_PANEL_OUTPUT=<output>` is the test mode: that
  output, keyboard focus None (OnDemand took the owner's keystrokes once on a headless output).
  Checked in an isolated `qs -p` with a fake CLI: rendering of every block, RTL, the action JSON.
  Not checked: real clicks/keys, Exclusive focus, dropdown with the strip, a real watch notice.
  Live progress patches don't reach the panel (watch drops id-less `Event::App`). Phone (Compose, later): a channel list as root,
  a block renderer, pinned/app shortcuts and share targets, one notification channel per Tether channel.
