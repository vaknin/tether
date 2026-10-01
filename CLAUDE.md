# Tether

A lean replacement for KDE Connect between this laptop (Omarchy/Hyprland) and the Pixel 8.
It covers chat with offline delivery, files both ways (the Android Direct Share tile is called
"Laptop"), phone media control from the bar, ring phone, pings, and mirrored phone notifications.
The full plan is in `docs/PLAN.md`. Read it before changing scope.

## Layout
- `crates/core`: protocol (`proto.rs`), SQLite chat log/outbox (`store.rs`), file helpers (`files.rs`).
  There is no platform code here. The phone uses this same core through uniffi (`crates/ffi`).
- `crates/ffi`: uniffi 0.32 bindings (`TetherNode`, records, `EventListener` callback) with its own
  tokio runtime. Kotlin package `com.kivan.tether.core` (`uniffi.toml`).
- `crates/daemon`: the `tether` binary. It runs the daemon (systemd user unit) and the CLI, which talks
  to the daemon over `$XDG_RUNTIME_DIR/tether.sock`.
- `crates/core/src/node.rs`: the iroh endpoint and the single link (pairing, dial race, outbox resend,
  file resume, idle close). Its tests run two nodes over loopback (`Net::Loopback`).
- `contrib/tether.service`: the systemd user unit. It isn't installed yet; KDE Connect stays live
  until the Phase 5 cut-over.
- `android/`: Kotlin/Compose app `com.kivan.tether` (toolchain copied from ~/Projects/chordhand).
  Gradle runs cargo-ndk and uniffi-bindgen itself (`buildSrc/.../RustTasks.kt`). `Core.kt` owns the
  node: it runs only while held (UI, `SyncService` after FCM wake or share, `OutboxWorker` retries)
  or a link is open, then shuts down. FCM is configured from `fcm.*` in `android/local.properties`.
  Release signing reads `~/.config/tether/keystore.properties`.
  `Native.load` (in `TetherApp`) loads the .so through the JVM and hands iroh the app context, so it
  reads the phone's DNS servers; it must run before the first node start. The phone's mDNS send
  fails with EPERM (no `MulticastLock`). That's deferred on purpose: a lock costs battery, the home
  Wi-Fi drops client-to-client multicast anyway, and relay plus hole-punching finds the direct path.
- Still to come: `shell/` (Omarchy QML plugin `kivan.tether`).

## Build and test
Rust is pinned in `mise.toml` (1.98.1). Run `cargo test` and `cargo build --release`.
Android: `cd android && ./gradlew :app:assembleRelease` (needs the `aarch64-linux-android` target and
`cargo-ndk`).

## Design decisions (keep these)
- **Transport is iroh 1.3** with `presets::N0`, which includes n0's free public relay, chosen by the
  user. Traffic is end-to-end encrypted. Add `iroh-mdns-address-lookup` 0.6 for the LAN and bind a fixed UDP port
  (`bind_addr("0.0.0.0:47114")`) so nftables needs one rule. See `docs/iroh-1.3-notes.md`.
- **Battery: the phone has no connection of its own when idle; it is woken through FCM.** The user
  agreed to this on 2026-10-01, and battery must be watched.
  - Why: iroh sends a QUIC heartbeat every 5 s on an open connection. The home-relay link also pings
    every 15 s, and that interval is a hard-coded constant (`iroh-1.3.0/src/socket/transports/relay/actor.rs:74`).
    Doze cuts both anyway.
  - Phone→laptop: the phone dials only when it has something to send. The laptop is always listening.
  - Laptop→phone: if the phone isn't connected, the laptop sends a high-priority FCM data message
    with **no content** (`{"t":"wake"}`). The phone wakes, starts its iroh endpoint, dials the laptop,
    takes delivery of the queue, and then shuts the endpoint down. Google learns only that a ping
    happened.
  - The phone stays connected (endpoint up, connection open) only while it is cheap or needed:
    while media is playing (so bar controls are instant), while the chat screen is open, or while the
    app is in the foreground.
  - Any connection closes after about 60 s with no traffic.
  - Without FCM (no key, or Google is down) the laptop retries queued items with backoff (30 s up to
    5 min), and the phone checks in when the app starts and when its network changes.
  - High-priority FCM must lead to a visible notification, or Android throttles it. Chat, files,
    pings and rings all show one. Media commands are only sent while the phone is already connected.
  - FCM setup: a free Firebase project. The service-account JSON goes to
    `~/.config/tether/fcm-service-account.json` (0600). The laptop calls the FCM HTTP v1 API.
    The phone sends its registration token over the paired connection (`Frame::PushToken`).
    The sender is `crates/daemon/src/fcm.rs`: it sends at most one wake per 30 s, `--fcm-key` sets
    the key path, and `cargo test -p tether -- --ignored fcm_live` checks auth against Google.
  - The user rejected battery-costly background daemons on the phone before.
- **If both sides dial at once**, keep the connection whose dialer has the smaller endpoint id. Both
  sides then agree.
- **Offline delivery.** Every item goes into the sender's `messages` table as `queued`. On each
  connect the sender resends all queued items. The receiver dedupes by uuid and sends `Ack`, and the
  sender marks the item `delivered`. A file item is acked only after its bytes are complete and the
  sha256 matches. Resuming a file works like this: the receiver sends `FileWant{offset = len of .part}`,
  and the sender opens a uni stream with a `FileHeader` and streams from that offset.
- **Pairing.** `tether pair` shows a QR with `tether:1:<endpoint id>:<base32 32-byte token>`, valid
  for 5 minutes and usable once. The phone connects with `PAIR_ALPN` and sends the token, and then
  each side stores the other's id. Connections from any other endpoint id are refused.
- **Media and notifications are live state** and are never queued.
- **Laptop side effects stay in the daemon, not the core.** They are:
  - an image goes onto the clipboard as image data (`wl-copy --type`, converted to PNG with EXIF
    orientation);
  - a toast with `omarchy-notification-send --image file://… --exec xdg-open`, because DND drops
    toasts from other senders;
  - MPRIS `org.mpris.MediaPlayer2.tether.pixel` through zbus.

## Machine notes
- Phone over adb: `adb connect Android_FBR2HM5L.local:5555`. Always pass `-s`, and check which app
  has focus before any `adb input`.
- QML plugin edits only take effect after `omarchy restart shell`.
