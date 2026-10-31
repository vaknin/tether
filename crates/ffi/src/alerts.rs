//! Jarvis's notifications for Aviv: the forum gate dials this phone with each one (the jarvis repo's
//! `forum/phone/tunnel/src/alerts.rs`, which has the why and the wire format) and the app shows it.
//!
//! The phone's side is the forum tunnel's endpoint ([`crate::forum`]), which accepts [`ALERT_ALPN`]
//! from the gate's id only. [`alerts_start`] keeps that endpoint up for good, instead of closing it
//! when the forum is idle: an endpoint with no connection costs only iroh's relay ping (every 15 s,
//! hard-coded), and no QUIC keep-alives, since no connection stays open. Each alert is a connection
//! that lasts a second: one JSON line in, the app shows it, its id goes back. One it didn't show gets
//! a line saying why instead, so the sender can tell Aviv (thread #179): an empty answer told no one.
//!
//! When the server dies the gate dies with it, so a dedicated helper may say so itself (thread #166,
//! the jarvis repo's `alert-gate/`). It dials with its own key and carries the gate's signature on that
//! key and an end date ([`vouched`]), so no helper is listed here and none needs a new build. Its
//! key is the one thing a thief of that machine holds, so a backup gets little: its line is forced
//! plain ([`plain`], no link, since a link can ring or act; the title is ours), and only one shows in
//! [`BACKUP_EVERY`], however many helpers saw the same death ([`backup_turn`]).

use std::{
    sync::{Arc, Mutex},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use anyhow::{Context, Result};
use iroh::{Endpoint, EndpointId, Signature, endpoint::Incoming};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, BufReader};

use crate::RT;

pub(crate) const ALERT_ALPN: &[u8] = b"jarvis-alert/0";
/// The longest alert line read; the gate sends at most 4000 characters of text.
const MAX_LINE: u64 = 32 * 1024;
/// The longest reason sent back; the gate reads a short line.
const MAX_WHY: usize = 120;
/// The longest line read from a backup, and the longest text of its shown.
const MAX_BACKUP_LINE: u64 = 2 * 1024;
const MAX_BACKUP_TEXT: usize = 300;
const BACKUP_TITLE: &str = "Jarvis backup";
/// One backup line shows in this long, from any helper.
const BACKUP_EVERY: u64 = 30 * 60 * 1000;
/// What the gate signs to vouch for a helper, then its id and the end date.
const VOUCH: &[u8] = b"jarvis-backup/0\0";
/// How often the keeper checks that the endpoint is up (a local look, no network).
const KEEP_EVERY: Duration = Duration::from_secs(20);

/// The app's side: shows one notification. None once it is on screen, else why not, in a few words
/// ("app notifications off"). `url` is a forum path for the tap to open, or empty.
#[uniffi::export(with_foreign)]
pub trait AlertListener: Send + Sync {
    fn show(&self, id: u64, title: String, text: String, url: String) -> Option<String>;
}

static LISTENER: Mutex<Option<Arc<dyn AlertListener>>> = Mutex::new(None);
/// When a backup's line last showed, ms.
static BACKUP_AT: Mutex<u64> = Mutex::new(0);

/// Starts taking Jarvis's notifications, shown through `listener`; a second call only swaps the
/// listener. Needs [`crate::forum::forum_start`].
#[uniffi::export]
pub fn alerts_start(listener: Arc<dyn AlertListener>) {
    if LISTENER.lock().unwrap().replace(listener).is_some() {
        return;
    }
    RT.spawn(async {
        // Both start with the process, and forum_start's endpoint comes a moment later.
        while !crate::forum::keep_up().await {
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
        loop {
            tokio::time::sleep(KEEP_EVERY).await;
            crate::forum::keep_up().await;
        }
    });
}

/// Whether the endpoint should accept alerts at all.
pub(crate) fn wanted() -> bool {
    LISTENER.lock().unwrap().is_some()
}

/// Takes the alerts that come to `ep` until it closes: the gate's, and a helper's it vouched for.
pub(crate) async fn accept(ep: Endpoint, gate: EndpointId) {
    while let Some(incoming) = ep.accept().await {
        tokio::spawn(async move {
            if let Err(e) = take(incoming, gate).await {
                tracing::warn!("alert: {e:#}");
            }
        });
    }
}

async fn take(incoming: Incoming, gate: EndpointId) -> Result<()> {
    let conn = incoming.accept()?.await.context("accept")?;
    let who = conn.remote_id();
    if conn.alpn() != ALERT_ALPN {
        conn.close(1u8.into(), b"not allowed");
        anyhow::bail!("refused {}", who.fmt_short());
    }
    let backup = who != gate;
    let (mut send, recv) = tokio::time::timeout(Duration::from_secs(10), conn.accept_bi()).await.context("no stream")??;
    let mut line = String::new();
    let most = if backup { MAX_BACKUP_LINE } else { MAX_LINE };
    tokio::time::timeout(Duration::from_secs(10), BufReader::new(recv).take(most).read_line(&mut line))
        .await
        .context("no alert")??;
    let v: serde_json::Value = serde_json::from_str(line.trim()).context("bad alert")?;
    let now = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64);
    if backup && !vouched(&v, who, gate, now) {
        conn.close(1u8.into(), b"not allowed");
        anyhow::bail!("refused {}", who.fmt_short());
    }
    let id = v["id"].as_u64().context("no id")?;
    let s = |k: &str| v[k].as_str().unwrap_or_default().to_string();
    let (title, text, url) = if backup { plain(&s("text")) } else { (s("title"), s("text"), s("url")) };
    // A check of the way here shows nothing, and neither does a line he has just had: that helper is done.
    if backup && (v["ping"] == true || !backup_turn(&mut BACKUP_AT.lock().unwrap(), now)) {
        tracing::info!("alert {id}: from a backup, not shown");
        send.write_all(format!("{id}\n").as_bytes()).await?;
        send.finish()?;
        let _ = tokio::time::timeout(Duration::from_secs(5), conn.closed()).await;
        return Ok(());
    }
    let listener = LISTENER.lock().unwrap().clone();
    let why = match listener {
        // The endpoint is up but the app never said who shows these: the gate hears that too.
        None => Some("no listener".to_string()),
        // A join error is the app's callback panicking through uniffi, past Alerts.show's own catch.
        Some(l) => tokio::task::spawn_blocking(move || l.show(id, title, text, url)).await.unwrap_or_else(|e| Some(format!("threw: {e}"))),
    };
    // Not on screen: the helper tries again next round, and that one may show.
    if backup && why.is_some() {
        *BACKUP_AT.lock().unwrap() = 0;
    }
    match &why {
        None => {
            tracing::info!("alert {id}: shown");
            send.write_all(format!("{id}\n").as_bytes()).await?;
        }
        Some(w) => {
            tracing::info!("alert {id}: not shown: {w}");
            send.write_all(format!("{}\n", one_line(w)).as_bytes()).await?;
        }
    }
    send.finish()?;
    // The gate closes once it has the answer.
    let _ = tokio::time::timeout(Duration::from_secs(5), conn.closed()).await;
    Ok(())
}

/// Did the gate sign this helper, and is that still good at `now`? The bytes are the gate's
/// (`forum_tunnel::alerts::endorse`, in the jarvis repo).
fn vouched(v: &serde_json::Value, who: EndpointId, gate: EndpointId, now: u64) -> bool {
    let (Some(until), Some(sig)) = (v["until"].as_u64(), v["cert"].as_str().and_then(signature)) else { return false };
    until > now && gate.verify(&[VOUCH, who.as_bytes(), &until.to_be_bytes()].concat(), &sig).is_ok()
}

fn signature(hex: &str) -> Option<Signature> {
    let bytes: Option<Vec<u8>> = (0..128).step_by(2).map(|i| u8::from_str_radix(hex.get(i..i + 2)?, 16).ok()).collect();
    (hex.len() == 128).then_some(Signature::from_bytes(&bytes?.try_into().ok()?))
}

/// What a backup may show, whatever it sent: the title, one short line of text, no link.
fn plain(text: &str) -> (String, String, String) {
    let line: String = text.split_whitespace().collect::<Vec<_>>().join(" ").chars().take(MAX_BACKUP_TEXT).collect();
    (BACKUP_TITLE.to_string(), line, String::new())
}

/// May a backup's line show at `now`? Taken here, so two helpers at once can't both pass.
fn backup_turn(last: &mut u64, now: u64) -> bool {
    let free = now.saturating_sub(*last) >= BACKUP_EVERY;
    if free {
        *last = now;
    }
    free
}

/// A reason as one short line, never empty: the gate reads a line, and an empty one is an old phone's.
fn one_line(why: &str) -> String {
    let line: String = why.split_whitespace().collect::<Vec<_>>().join(" ").chars().take(MAX_WHY).collect();
    if line.is_empty() { "no reason".to_string() } else { line }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// What it was shown, and what it says when it won't show one.
    #[derive(Default)]
    struct Shown(Mutex<Vec<(u64, String, String)>>, Mutex<Option<String>>);

    impl AlertListener for Shown {
        fn show(&self, id: u64, title: String, text: String, url: String) -> Option<String> {
            self.0.lock().unwrap().push((id, title, format!("{text}{url}")));
            let why = self.1.lock().unwrap().clone();
            if why.as_deref() == Some("panic") {
                panic!("Callback interface failure");
            }
            why
        }
    }

    fn local() -> iroh::endpoint::Builder {
        Endpoint::builder(iroh::endpoint::presets::Minimal).relay_mode(iroh::RelayMode::Disabled).clear_ip_transports().bind_addr("127.0.0.1:0").unwrap()
    }

    async fn endpoint(alpns: Vec<Vec<u8>>) -> Endpoint {
        local().alpns(alpns).bind().await.unwrap()
    }

    async fn keyed(key: iroh::SecretKey) -> Endpoint {
        local().secret_key(key).bind().await.unwrap()
    }

    /// What `forum-gate endorse` prints, as hex.
    fn vouch(gate: &iroh::SecretKey, who: EndpointId, until: u64) -> String {
        gate.sign(&[VOUCH, who.as_bytes(), &until.to_be_bytes()].concat()).to_bytes().iter().map(|b| format!("{b:02x}")).collect()
    }

    /// What the gate does: one line out, the answer back.
    async fn send(from: &Endpoint, to: &Endpoint, line: &str) -> Result<String> {
        let conn = from.connect(to.addr(), ALERT_ALPN).await?;
        let (mut s, r) = conn.open_bi().await?;
        s.write_all(line.as_bytes()).await?;
        s.finish()?;
        let mut answer = String::new();
        BufReader::new(r).read_line(&mut answer).await?;
        conn.close(0u8.into(), b"done");
        Ok(answer)
    }

    #[tokio::test]
    async fn shows_what_the_gate_sends_and_says_so() {
        let shown = Arc::new(Shown::default());
        *LISTENER.lock().unwrap() = Some(shown.clone());
        let key = iroh::SecretKey::generate();
        let (phone, gate, stranger) = (endpoint(vec![ALERT_ALPN.to_vec()]).await, keyed(key.clone()).await, endpoint(vec![]).await);
        let (backup, second) = (endpoint(vec![]).await, endpoint(vec![]).await);
        tokio::spawn(accept(phone.clone(), gate.id()));

        let answer = send(&gate, &phone, "{\"id\":42,\"ts_ms\":1,\"title\":\"Job done\",\"text\":\"jarvis notify \",\"url\":\"/t/42\"}\n").await.unwrap();
        assert_eq!(answer, "42\n");
        assert_eq!(shown.0.lock().unwrap().as_slice(), [(42, "Job done".to_string(), "jarvis notify /t/42".to_string())]);

        // Not shown (notifications off): the reason instead of the id, so the sender can say it.
        *shown.1.lock().unwrap() = Some("Jarvis channel off".to_string());
        assert_eq!(send(&gate, &phone, "{\"id\":43,\"title\":\"\",\"text\":\"x\"}\n").await.unwrap(), "Jarvis channel off\n");

        // A callback that panics through uniffi still answers.
        *shown.1.lock().unwrap() = Some("panic".to_string());
        let answer = send(&gate, &phone, "{\"id\":45,\"title\":\"\",\"text\":\"x\"}\n").await.unwrap();
        assert!(answer.starts_with("threw: ") && answer.ends_with('\n') && answer.lines().count() == 1, "{answer:?}");

        // Anyone but the gate, without its word, shows nothing.
        assert!(send(&stranger, &phone, "{\"id\":44,\"title\":\"\",\"text\":\"spoof\"}\n").await.is_err());
        assert_eq!(shown.0.lock().unwrap().len(), 3);

        // A helper with the gate's word: forced plain, and one line however many say it.
        *shown.1.lock().unwrap() = None;
        let until = u64::MAX;
        let word = |who: EndpointId, until: u64| vouch(&key, who, until);
        let line = |id: u64, cert: String, until: u64, more: &str| format!("{{\"id\":{id},\"text\":\"the server\\nis gone\",\"title\":\"yours\",\"url\":\"/ring/x\",\"until\":{until},\"cert\":\"{cert}\"{more}}}\n");
        assert_eq!(send(&backup, &phone, &line(50, word(backup.id(), until), until, ",\"ping\":true")).await.unwrap(), "50\n");
        assert_eq!(shown.0.lock().unwrap().len(), 3, "a ping shows nothing");
        *shown.1.lock().unwrap() = Some("Jarvis channel off".to_string());
        assert_eq!(send(&backup, &phone, &line(51, word(backup.id(), until), until, "")).await.unwrap(), "Jarvis channel off\n");
        shown.0.lock().unwrap().pop();
        *shown.1.lock().unwrap() = None;
        assert_eq!(send(&backup, &phone, &line(51, word(backup.id(), until), until, "")).await.unwrap(), "51\n", "a refused one left the half hour free");
        assert_eq!(shown.0.lock().unwrap().last().unwrap(), &(51, BACKUP_TITLE.to_string(), "the server is gone".to_string()));
        assert_eq!(send(&second, &phone, &line(52, word(second.id(), until), until, "")).await.unwrap(), "52\n");
        assert_eq!(shown.0.lock().unwrap().len(), 4, "the second helper's line isn't shown, and it's done");
        // Another helper's word, or one past its date, gets nothing.
        assert!(send(&second, &phone, &line(53, word(backup.id(), until), until, "")).await.is_err());
        assert!(send(&stranger, &phone, &line(54, "00".repeat(64), until, "")).await.is_err());
        assert!(send(&backup, &phone, &line(55, word(backup.id(), 1), 1, "")).await.is_err());
        assert_eq!(shown.0.lock().unwrap().len(), 4);

        // The tunnel up with nobody to show it: said, not a dropped connection.
        *LISTENER.lock().unwrap() = None;
        assert_eq!(send(&gate, &phone, "{\"id\":46,\"title\":\"\",\"text\":\"x\"}\n").await.unwrap(), "no listener\n");
    }

    #[test]
    fn a_word_is_for_one_helper_until_its_date() {
        let (gate, who, other) = (iroh::SecretKey::generate(), iroh::SecretKey::generate().public(), iroh::SecretKey::generate().public());
        let word = |until: u64, sig: String| serde_json::json!({"until": until, "cert": sig});
        assert!(vouched(&word(10, vouch(&gate, who, 10)), who, gate.public(), 9));
        assert!(!vouched(&word(10, vouch(&gate, who, 10)), who, gate.public(), 10), "over");
        assert!(!vouched(&word(11, vouch(&gate, who, 10)), who, gate.public(), 9), "the date is signed");
        assert!(!vouched(&word(10, vouch(&gate, other, 10)), who, gate.public(), 9), "someone else's");
        assert!(!vouched(&word(10, vouch(&iroh::SecretKey::generate(), who, 10)), who, gate.public(), 9), "not the gate's");
        assert!(!vouched(&word(10, "ab".into()), who, gate.public(), 9));
        assert!(!vouched(&serde_json::json!({}), who, gate.public(), 9));
    }

    #[test]
    fn a_backups_line_is_plain() {
        let (title, text, url) = plain(&format!("the server\n  is {}", "x".repeat(500)));
        assert_eq!(title, BACKUP_TITLE);
        assert!(text.starts_with("the server is x") && text.chars().count() == MAX_BACKUP_TEXT);
        assert_eq!(url, "");
    }

    #[test]
    fn one_backup_line_in_the_half_hour() {
        let mut last = 0;
        let start = BACKUP_EVERY * 2;
        assert!(backup_turn(&mut last, start));
        assert!(!backup_turn(&mut last, start + 1));
        assert!(!backup_turn(&mut last, start + BACKUP_EVERY - 1));
        assert!(backup_turn(&mut last, start + BACKUP_EVERY));
    }

    #[test]
    fn a_reason_is_one_short_line() {
        assert_eq!(one_line("threw: a\nb  c"), "threw: a b c");
        assert_eq!(one_line("  \n"), "no reason");
        assert_eq!(one_line(&"x".repeat(500)).len(), MAX_WHY);
    }
}
