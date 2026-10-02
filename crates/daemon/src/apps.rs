//! App channels (`docs/PLAN.md`, "App channels"). A channel's manifest is
//! `~/.config/tether/apps/<name>.toml`: how the phone and the panel show it, and `exec`, the shell
//! command that starts its app. The daemon reads the folder at start and on `channels_reload`, and
//! publishes the list to the phone as the `_channels` view when it changed.
//!
//! Apps start on demand: when an item arrives on a channel that has no client subscribed, the
//! daemon runs its `exec` (`sh -c`), which starts the app in the background; the app then
//! subscribes and takes the waiting items. Also at start, for a channel that already has items
//! waiting.

use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::Duration,
};

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::json;
use tether_core::node::{Event, Node};
use tokio::{sync::broadcast::error::RecvError, time::Instant};
use tracing::{info, warn};

use crate::daemon::Clients;

/// A starter that hasn't produced a client yet isn't run again before this.
const RELAUNCH_GAP: Duration = Duration::from_secs(30);
/// The daemon's own channel: the manifest list, as a view for the phone.
pub const CHANNELS: &str = "_channels";

/// `~/.config/tether/apps` (`$XDG_CONFIG_HOME` respected).
pub fn default_dir() -> Result<PathBuf> {
    Ok(crate::config_dir()?.join("tether/apps"))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Dir {
    Ltr,
    Rtl,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    /// Has an app that publishes a view and takes actions.
    App,
    /// Posts and replies only (`tether post`).
    Thread,
}

/// A manifest file as written. Everything is optional.
#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct Raw {
    title: Option<String>,
    glyph: Option<String>,
    accent: Option<String>,
    dir: Option<Dir>,
    kind: Option<Kind>,
    exec: Option<String>,
    #[serde(default)]
    share: bool,
    notify: Option<bool>,
}

/// A channel as the UIs see it (also an entry of `_channels`).
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Channel {
    pub name: String,
    pub title: String,
    /// One emoji or letter.
    pub glyph: String,
    /// `#rrggbb`; `None` leaves it to the UI.
    pub accent: Option<String>,
    pub dir: Dir,
    pub kind: Kind,
    /// Accepts Android share text into its compose.
    pub share: bool,
    /// The phone may show a view's `notify` as a notification.
    pub notify: bool,
    #[serde(skip)]
    pub exec: Option<String>,
}

impl Channel {
    /// A channel with items but no manifest.
    pub fn thread(name: &str) -> Self {
        Self::from_raw(name, Raw::default())
    }

    fn from_raw(name: &str, r: Raw) -> Self {
        let title = r.title.filter(|t| !t.trim().is_empty()).unwrap_or_else(|| name.to_string());
        let glyph = r
            .glyph
            .filter(|g| !g.is_empty())
            .unwrap_or_else(|| title.chars().next().map(|c| c.to_uppercase().collect()).unwrap_or_default());
        let accent = r.accent.filter(|a| {
            let ok = a.len() == 7 && a.starts_with('#') && a[1..].bytes().all(|b| b.is_ascii_hexdigit());
            if !ok {
                warn!("channel {name}: accent {a:?} isn't #rrggbb; ignored");
            }
            ok
        });
        let exec = r.exec.filter(|e| !e.trim().is_empty());
        Self {
            name: name.into(),
            title,
            glyph,
            accent,
            dir: r.dir.unwrap_or(Dir::Ltr),
            kind: r.kind.unwrap_or(if exec.is_some() { Kind::App } else { Kind::Thread }),
            share: r.share,
            notify: r.notify.unwrap_or(true),
            exec,
        }
    }
}

/// `[a-z0-9_-]+`, not starting with `_` (kept for the daemon's own channels). Also keeps a
/// channel name from pointing outside the folder.
pub fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && !name.starts_with('_')
        && name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'_')
}

/// The manifests in `dir`, by name. A bad one is logged and skipped.
fn load(dir: &Path) -> Vec<Channel> {
    let mut list = Vec::new();
    let Ok(entries) = std::fs::read_dir(dir) else { return list };
    for e in entries.flatten() {
        let path = e.path();
        if path.extension().is_none_or(|x| x != "toml") {
            continue;
        }
        let Some(name) = path.file_stem().and_then(|s| s.to_str()) else { continue };
        if !valid_name(name) {
            warn!("{}: a channel name is [a-z0-9_-]+, not starting with _; skipped", path.display());
            continue;
        }
        let raw = std::fs::read_to_string(&path)
            .context("read")
            .and_then(|s| toml::from_str::<Raw>(&s).context("parse"));
        match raw {
            Ok(r) => list.push(Channel::from_raw(name, r)),
            Err(e) => warn!("{}: {e:#}; skipped", path.display()),
        }
    }
    list.sort_by(|a, b| a.name.cmp(&b.name));
    list
}

/// The manifests the daemon knows, shared by the socket and the starter.
pub struct Apps {
    dir: PathBuf,
    list: Mutex<Vec<Channel>>,
}

impl Apps {
    pub fn new(dir: PathBuf) -> Arc<Self> {
        let list = Mutex::new(load(&dir));
        Arc::new(Self { dir, list })
    }

    pub fn list(&self) -> Vec<Channel> {
        self.list.lock().unwrap().clone()
    }

    pub fn get(&self, name: &str) -> Option<Channel> {
        self.list.lock().unwrap().iter().find(|c| c.name == name).cloned()
    }

    /// Reads the folder again and publishes the list if it changed.
    pub fn reload(&self, node: &Node) -> Result<()> {
        *self.list.lock().unwrap() = load(&self.dir);
        self.publish(node)?;
        Ok(())
    }

    /// Queues the list as the `_channels` view, unless the newest one already says the same.
    /// True when it was queued.
    pub fn publish(&self, node: &Node) -> Result<bool> {
        let list = self.list();
        let data = json!({ "v": 1, "channels": list }).to_string();
        if node.app_view(CHANNELS)?.as_deref() == Some(data.as_str()) {
            return Ok(false);
        }
        info!("publishing the channel list ({} channels)", list.len());
        node.send_app(CHANNELS, data, true)?;
        Ok(true)
    }
}

/// `~` at the start of a word becomes `home`, so `exec = "~/bin/app"` works even quoted.
fn expand_tilde(cmd: &str, home: &str) -> String {
    let mut out = String::with_capacity(cmd.len());
    let mut prev: Option<char> = None;
    let mut chars = cmd.chars().peekable();
    while let Some(c) = chars.next() {
        let word_start = prev.is_none_or(|p| p.is_whitespace() || p == '"' || p == '\'' || p == '=');
        let ends = chars.peek().is_none_or(|n| *n == '/' || n.is_whitespace() || *n == '"' || *n == '\'');
        if c == '~' && word_start && ends {
            out.push_str(home);
        } else {
            out.push(c);
        }
        prev = Some(c);
    }
    out
}

pub async fn run(node: Node, clients: Clients, apps: Arc<Apps>) {
    let mut rx = node.events();
    let home = crate::home().map(|h| h.to_string_lossy().into_owned()).unwrap_or_default();
    let mut last: HashMap<String, Instant> = HashMap::new();
    let mut launch = |channel: &str| {
        if clients.lock().unwrap().contains_key(channel) {
            return;
        }
        let Some(exec) = apps.get(channel).and_then(|c| c.exec) else { return };
        if last.get(channel).is_some_and(|t| t.elapsed() < RELAUNCH_GAP) {
            return;
        }
        last.insert(channel.to_string(), Instant::now());
        let cmd = expand_tilde(&exec, &home);
        info!("starting the {channel} app ({cmd})");
        let spawned = tokio::process::Command::new("sh")
            .arg("-c")
            .arg(&cmd)
            .stdin(std::process::Stdio::null())
            .kill_on_drop(false)
            .spawn();
        match spawned {
            Ok(mut child) => {
                tokio::spawn(async move {
                    let _ = child.wait().await;
                });
            }
            Err(e) => warn!("starting {cmd}: {e}"),
        }
    };
    for c in apps.list() {
        if node.app_pending(&c.name).is_ok_and(|p| !p.is_empty()) {
            launch(&c.name);
        }
    }
    loop {
        match rx.recv().await {
            Ok(Event::App { id: Some(_), channel, from_me: false, view: false, .. }) => launch(&channel),
            Ok(_) | Err(RecvError::Lagged(_)) => {}
            Err(RecvError::Closed) => return,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn manifests_load_with_defaults_and_bad_ones_are_skipped() {
        let d = tempfile::tempdir().unwrap();
        let w = |name: &str, s: &str| std::fs::write(d.path().join(name), s).unwrap();
        w(
            "demo.toml",
            r##"title = "Demo"
glyph = "🏠"
accent = "#d79921"
dir = "rtl"
exec = "~/bin/demo-app --serve"
share = true
"##,
        );
        w("notes.toml", "accent = \"orange\"\n");
        w("typo.toml", "titel = \"x\"\n");
        w("Bad.toml", "");
        w("_channels.toml", "");
        w("readme.md", "");
        let list = load(d.path());
        assert_eq!(list.iter().map(|c| c.name.as_str()).collect::<Vec<_>>(), ["demo", "notes"]);
        let demo = &list[0];
        assert_eq!((demo.kind, demo.dir, demo.share, demo.notify), (Kind::App, Dir::Rtl, true, true));
        assert_eq!(demo.accent.as_deref(), Some("#d79921"));
        let j = serde_json::to_value(demo).unwrap();
        assert_eq!(j["dir"], "rtl");
        assert!(j.get("exec").is_none(), "the phone doesn't need it");
        let notes = &list[1];
        assert_eq!((notes.title.as_str(), notes.glyph.as_str()), ("notes", "N"));
        assert_eq!((notes.accent.clone(), notes.kind, notes.dir), (None, Kind::Thread, Dir::Ltr));
    }

    #[test]
    fn tilde_expands_at_word_starts_only() {
        let h = "/home/u";
        assert_eq!(expand_tilde("~/bin/app --x", h), "/home/u/bin/app --x");
        assert_eq!(expand_tilde("app ~/a \"~/b c\" --dir=~/d", h), "app /home/u/a \"/home/u/b c\" --dir=/home/u/d");
        assert_eq!(expand_tilde("cd ~ && a~b ~user", h), "cd /home/u && a~b ~user");
    }
}
