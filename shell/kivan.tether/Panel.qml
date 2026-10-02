// Tether's chat on the laptop (docs/PLAN.md, Phase 4). One layer-shell window shows Chat.qml in
// two places: dropped down under the bar badge (`omarchy-shell tether dropdown <x>`, from
// Badge.qml), or centered over a scrim (`omarchy-shell tether toggle|open`, SUPER+M, and a click
// on a chat toast). Both take the keyboard, since an xdg popup can't be typed into reliably.
//
// It stays loaded (keepLoaded) so the IPC target exists from shell start. The messages come from
// `tether json` once, then from a long-running `tether watch` (JSON lines); sending runs
// `tether msg` and `tether send`, whose own events bring the new rows in.
import Quickshell
import Quickshell.Io
import Quickshell.Wayland
import QtQuick
import qs.Commons
import qs.Ui

Item {
  id: root

  // Injected by omarchy-shell's panel loader.
  property var shell: null
  property var manifest: null

  readonly property string cli: Quickshell.env("HOME") + "/.cargo/bin/tether"

  property bool opened: false
  property string mode: "center"            // or "dropdown"
  property int anchorX: -1                  // dropdown: the badge's centre on screen
  property string reopenMode: ""            // set while the file chooser has the panel hidden

  property bool daemonUp: false
  property var status: ({ connected: false, peer: null, queued: 0, unread: 0 })
  readonly property string peerName: status.peer && status.peer.name ? status.peer.name : "Phone"
  readonly property string statusLine: !daemonUp ? "daemon not running"
    : !status.peer ? "not paired"
    : (status.connected ? "connected" : "not connected")
      + (status.queued > 0 ? " · " + status.queued + " waiting to send" : "")

  ListModel { id: messageModel }
  readonly property alias messages: messageModel

  // --- Host lifecycle (the shell's summon/hide) --------------------------------------------------

  function open(payloadJson) {
    var p = ({})
    try { p = JSON.parse(payloadJson || "{}") } catch (e) {}
    show(p.mode === "dropdown" ? "dropdown" : "center", p.x)
  }

  function close() {
    opened = false
  }

  function show(m, x) {
    mode = m
    anchorX = x === undefined || x === "" ? -1 : Number(x)
    opened = true
    markRead()
    Qt.callLater(function() { card.focusInput(); card.scrollToEnd() })
  }

  IpcHandler {
    target: "tether"
    function toggle(): void { if (root.opened && root.mode === "center") root.close(); else root.show("center") }
    function open(): void { root.show("center") }
    function close(): void { root.close() }
    function dropdown(x: string): void { if (root.opened) root.close(); else root.show("dropdown", x) }
    function isOpen(): bool { return root.opened }
  }

  Component.onCompleted: reload()

  // --- Data -------------------------------------------------------------------------------------

  function reload() {
    if (!loader.running) loader.running = true
  }

  Process {
    id: loader
    command: [root.cli, "json"]
    stdout: StdioCollector {
      waitForEnd: true
      onStreamFinished: root.applySnapshot(text)
    }
    onExited: function(code) { if (code !== 0) root.daemonUp = false }
  }

  function applySnapshot(text) {
    var snap
    try { snap = JSON.parse(text) } catch (e) { daemonUp = false; return }
    daemonUp = true
    status = snap.status
    messageModel.clear()
    for (var i = 0; i < snap.messages.length; i++) messageModel.append(row(snap.messages[i]))
  }

  // A message as a ListModel row: no nulls, and the day it falls on for the section headers.
  function row(m) {
    return {
      mid: m.id,
      fromMe: m.from_me === true,
      ts: m.ts_ms,
      day: Qt.formatDate(new Date(m.ts_ms), "yyyy-MM-dd"),
      kind: m.kind || "text",
      text: m.text || "",
      fileName: m.file_name || "",
      fileSize: m.file_size || 0,
      path: m.path || "",
      state: m.state || "",
      done: 0,
      total: 0
    }
  }

  function indexOf(id) {
    for (var i = messageModel.count - 1; i >= 0; i--)
      if (messageModel.get(i).mid === id) return i
    return -1
  }

  function upsert(m) {
    var r = row(m)
    var i = indexOf(r.mid)
    if (i < 0) {
      messageModel.append(r)
    } else {
      // Keep the transfer progress; a finished file ends it.
      var old = messageModel.get(i)
      if (r.state !== "incoming" && r.state !== "queued") { r.done = 0; r.total = 0 }
      else { r.done = old.done; r.total = old.total }
      messageModel.set(i, r)
    }
  }

  Process {
    id: watch
    command: [root.cli, "watch"]
    running: true
    stdout: SplitParser { onRead: function(line) { root.onEvent(line) } }
    // Catch up on whatever happened while the watch wasn't running.
    onRunningChanged: if (running) root.reload()
    onExited: { root.daemonUp = false; rewatch.start() }
  }

  Timer { id: rewatch; interval: 5000; onTriggered: watch.running = true }
  Timer { id: statusSoon; interval: 150; onTriggered: if (!statusProc.running) statusProc.running = true }

  Process {
    id: statusProc
    command: [root.cli, "status", "--json"]
    stdout: StdioCollector {
      waitForEnd: true
      onStreamFinished: {
        try { root.status = JSON.parse(text); root.daemonUp = true } catch (e) {}
      }
    }
  }

  function onEvent(line) {
    var e
    try { e = JSON.parse(line) } catch (err) { return }
    daemonUp = true
    if (e.event === "message") {
      upsert(e)
      if (!e.from_me && opened && (e.state === "received" || e.state === "incoming")) markRead()
      statusSoon.restart()
    } else if (e.event === "progress") {
      var i = indexOf(e.id)
      if (i >= 0) { messageModel.setProperty(i, "done", e.done); messageModel.setProperty(i, "total", e.total) }
    } else if (e.event === "paired" || e.event === "unpaired") {
      reload()
    } else if (e.event === "connected" || e.event === "disconnected" || e.event === "read") {
      statusSoon.restart()
    }
  }

  // --- Actions ----------------------------------------------------------------------------------

  function markRead() {
    if (daemonUp) Quickshell.execDetached([cli, "read"])
  }

  function sendText(t) {
    Quickshell.execDetached([cli, "msg", "--", t])
  }

  // Files first, then the text, in that order, so a caption lands under its image.
  function sendWith(paths, t) {
    Quickshell.execDetached(["sh", "-c",
      'cli="$1"; t="$2"; shift 2; "$cli" send -- "$@" >/dev/null && { [ -z "$t" ] || "$cli" msg -- "$t"; }',
      "sh", cli, t].concat(paths))
  }

  function cancel(id) {
    Quickshell.execDetached([cli, "cancel", id])
  }

  function ring() {
    Quickshell.execDetached([cli, "ring"])
  }

  // As image data, the way received images land on the clipboard.
  function copyImage(path) {
    if (path) Quickshell.execDetached([cli, "copy", path])
  }

  function copy(t) {
    if (t) Quickshell.execDetached(["wl-copy", "--", t])
  }

  function openFile(path) {
    if (!path) return
    close()
    Quickshell.execDetached(["xdg-open", path])
  }

  // The file chooser is an ordinary window under this overlay, and the overlay holds the
  // keyboard, so the panel steps aside until the chooser closes.
  function pickFiles() {
    if (picker.running) return
    reopenMode = mode
    close()
    picker.running = true
  }

  Process {
    id: picker
    command: [root.cli, "send", "--pick"]
    onExited: {
      var m = root.reopenMode
      root.reopenMode = ""
      if (m) root.show(m, m === "dropdown" ? root.anchorX : undefined)
    }
  }

  // Ctrl+V: an image on the clipboard is saved and attached, to go with the next Enter; anything
  // else is pasted as text (exit 7).
  function paste() {
    if (!paster.running) paster.running = true
  }

  Process {
    id: paster
    command: ["sh", "-c", "wl-paste --list-types 2>/dev/null | grep -q '^image/' || exit 7; exec \"$0\" paste", root.cli]
    stdout: StdioCollector {
      waitForEnd: true
      onStreamFinished: { var p = text.trim(); if (p) card.attach(p) }
    }
    onExited: function(code) { if (code === 7) card.pasteText() }
  }

  // --- Surface ----------------------------------------------------------------------------------

  PanelWindow {
    id: panel
    visible: root.opened
    anchors { top: true; bottom: true; left: true; right: true }
    color: "transparent"
    WlrLayershell.namespace: "omarchy-tether"
    WlrLayershell.layer: WlrLayer.Overlay
    WlrLayershell.keyboardFocus: root.opened ? WlrKeyboardFocus.Exclusive : WlrKeyboardFocus.None
    // The dropdown keeps clear of the bar, so its top edge is just under it; the centered
    // panel covers the whole screen with its scrim.
    exclusionMode: root.mode === "dropdown" ? ExclusionMode.Normal : ExclusionMode.Ignore

    readonly property bool dropdown: root.mode === "dropdown"
    readonly property int gap: Style.gapsOut

    Rectangle {
      anchors.fill: parent
      color: panel.dropdown ? "transparent" : Color.menu.scrim
    }

    MouseArea { anchors.fill: parent; onClicked: root.close() }

    Chat {
      id: card
      ui: root
      roomy: !panel.dropdown
      width: Math.min(panel.dropdown ? 330 : 440, panel.width - panel.gap * 2)
      height: Math.min(panel.dropdown ? 400 : 470, panel.height - panel.gap * 2)
      x: panel.dropdown && root.anchorX >= 0
        ? Math.max(panel.gap, Math.min(root.anchorX - width / 2, panel.width - width - panel.gap))
        : Math.round((panel.width - width) / 2)
      y: panel.dropdown ? panel.gap : Math.round((panel.height - height) / 2)

      MouseArea { anchors.fill: parent; z: -1; onClicked: card.focusInput() }
    }
  }
}
