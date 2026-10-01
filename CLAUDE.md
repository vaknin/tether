# Tether

A lean replacement for KDE Connect between this laptop (Omarchy/Hyprland) and the Pixel 8.
It covers chat with offline delivery, files both ways (the Android Direct Share tile is called
"Laptop"), phone media control from the bar, ring phone, pings, and mirrored phone notifications.
The full plan is in `docs/PLAN.md`. Read it before changing scope.

## Layout
- `crates/core`: protocol (`proto.rs`), SQLite chat log/outbox (`store.rs`), file helpers (`files.rs`).
  There is no platform code here. The phone uses this same core through uniffi (`crates/ffi`, not
  written yet).
- `crates/daemon`: the `tether` binary. It runs the daemon (systemd user unit) and the CLI, which talks
  to the daemon over `$XDG_RUNTIME_DIR/tether.sock`.
- Still to come: `crates/ffi`, `android/` (Kotlin/Compose, toolchain same as ~/Projects/chordhand),
  and `shell/` (Omarchy QML plugin `kivan.tether`).

## Build and test
Rust is pinned in `mise.toml` (1.98.1). Run `cargo test` and `cargo build --release`.

## Design decisions (keep these)
- **Transport is iroh 1.3** with `presets::N0`, which includes n0's free public relay, chosen by the
  user. Traffic is end-to-end encrypted. Add `iroh-mdns-address-lookup` 0.6 for the LAN and bind a fixed UDP port
  (`bind_addr("0.0.0.0:47114")`) so nftables needs one rule. See `docs/iroh-1.3-notes.md`.
- **Connections are on demand, not persistent.** iroh sends a QUIC heartbeat every 5 s while a
  connection is open, which would drain the phone. The rules:
  - Dial when there is something to send: an outbox item, a media state change, a media command,
    a notification diff.
  - Close after about 60 s with no traffic.
  - The laptop retries queued items with backoff (30 s up to 5 min).
  - The phone retries when its network changes and when the app starts.
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
