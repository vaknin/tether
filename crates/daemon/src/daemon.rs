//! `tether daemon`: runs the node and serves the CLI/UI socket.

use std::{
    collections::{HashMap, HashSet},
    path::Path,
    sync::{Arc, Mutex},
    time::Duration,
};

use anyhow::{Context, Result};
use serde_json::{Value, json};
use tether_core::{
    node::{Event, Node},
    proto::Frame,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    net::{UnixListener, UnixStream},
    sync::{broadcast::error::RecvError, mpsc},
    time::Instant,
};
use tracing::{debug, info};

use crate::{
    apps::{self, Apps, Channel},
    fcm,
    ipc::{AppDone, Request},
};

const RING_TTL: Duration = Duration::from_secs(120);
/// How long `notifications --fresh` waits for the phone's list after asking for a wake.
const FRESH_WAIT: Duration = Duration::from_secs(25);
/// A connected phone sends its list right after the link comes up; give it this long.
const SNAPSHOT_GRACE: Duration = Duration::from_secs(3);
/// After a `--fresh` request the link stays open this long, so polling (rami-login waiting for an
/// SMS code) sees new notifications live instead of waking the phone each time.
const FRESH_HOLD: Duration = Duration::from_secs(120);

#[derive(Clone)]
pub struct Ctx {
    pub node: Node,
    /// Asks the FCM loop to wake the phone; `None` without a key.
    pub wake: Option<mpsc::UnboundedSender<()>>,
    /// The last wake got no link (see [`fcm::run`]); reported by `status`.
    pub unanswered: fcm::Unanswered,
    hold_until: Arc<Mutex<Option<Instant>>>,
    /// App channels with a client subscribed, and how many.
    pub clients: Clients,
    pub apps: Arc<Apps>,
}

pub type Clients = Arc<Mutex<HashMap<String, usize>>>;

/// Counts a channel client while it lives.
struct Subscribed(Clients, String);

impl Drop for Subscribed {
    fn drop(&mut self) {
        let mut c = self.0.lock().unwrap();
        if let Some(n) = c.get_mut(&self.1) {
            *n -= 1;
            if *n == 0 {
                c.remove(&self.1);
            }
        }
    }
}

impl Ctx {
    pub fn new(
        node: Node,
        wake: Option<mpsc::UnboundedSender<()>>,
        unanswered: fcm::Unanswered,
        clients: Clients,
        apps: Arc<Apps>,
    ) -> Self {
        Self { node, wake, unanswered, hold_until: Arc::default(), clients, apps }
    }

    /// Manifests first, then the channels that only have items (threads), each with its newest
    /// view (parsed) and that view's badge.
    fn channels(&self) -> Result<Value> {
        let mut list = self.apps.list();
        for name in self.node.app_channels()? {
            if !name.starts_with('_') && !list.iter().any(|c| c.name == name) {
                list.push(Channel::thread(&name));
            }
        }
        let mut out = Vec::new();
        for c in list {
            let view = self.node.app_view(&c.name)?.map(|d| parse(&d)).unwrap_or(Value::Null);
            let mut v = serde_json::to_value(&c)?;
            v["laptop"] = c.laptop.into();
            v["show"] = serde_json::to_value(c.show)?;
            v["badge"] = view.get("badge").cloned().unwrap_or(Value::Null);
            v["view"] = view;
            out.push(v);
        }
        Ok(Value::Array(out))
    }

    /// Keeps the link open until `FRESH_HOLD` after the last call.
    fn hold(&self) {
        let until = Instant::now() + FRESH_HOLD;
        let start = self.hold_until.lock().unwrap().replace(until).is_none();
        self.node.set_stay(true);
        if start {
            let me = self.clone();
            tokio::spawn(async move {
                loop {
                    let until = me.hold_until.lock().unwrap().expect("set while held");
                    tokio::time::sleep_until(until).await;
                    let mut slot = me.hold_until.lock().unwrap();
                    if slot.is_some_and(|u| u <= Instant::now()) {
                        *slot = None;
                        me.node.set_stay(false);
                        return;
                    }
                }
            });
        }
    }

    async fn fresh_notifs(&self) -> Result<Value> {
        let node = &self.node;
        let mut rx = node.events();
        self.hold();
        if !node.is_connected() {
            match &self.wake {
                Some(w) => {
                    let _ = w.send(());
                }
                None => return Ok(serde_json::to_value(node.notifs())?),
            }
            let wait = async {
                let mut grace: Option<Instant> = None;
                loop {
                    let ev = match grace {
                        Some(g) => match tokio::time::timeout_at(g, rx.recv()).await {
                            Ok(ev) => ev,
                            Err(_) => return,
                        },
                        None => rx.recv().await,
                    };
                    match ev {
                        Ok(Event::Notifs { .. }) | Err(RecvError::Closed) => return,
                        Ok(Event::Connected) => grace = Some(Instant::now() + SNAPSHOT_GRACE),
                        Ok(_) | Err(RecvError::Lagged(_)) => {}
                    }
                }
            };
            let _ = tokio::time::timeout(FRESH_WAIT, wait).await;
        }
        Ok(serde_json::to_value(node.notifs())?)
    }
}

pub async fn serve(ctx: Ctx, sock: &Path) -> Result<()> {
    if UnixStream::connect(sock).await.is_ok() {
        anyhow::bail!("another tether daemon is already serving {}", sock.display());
    }
    let _ = std::fs::remove_file(sock);
    let listener = UnixListener::bind(sock).with_context(|| format!("bind {}", sock.display()))?;
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(sock, std::fs::Permissions::from_mode(0o600))?;
    }
    info!("listening on {}", sock.display());
    loop {
        let (stream, _) = listener.accept().await?;
        let ctx = ctx.clone();
        tokio::spawn(async move {
            if let Err(e) = client(ctx, stream).await {
                debug!("client: {e:#}");
            }
        });
    }
}

async fn client(ctx: Ctx, stream: UnixStream) -> Result<()> {
    let node = &ctx.node;
    let (r, mut w) = stream.into_split();
    let mut lines = BufReader::new(r).lines();
    let Some(line) = lines.next_line().await? else { return Ok(()) };
    let req: Request = match serde_json::from_str(&line) {
        Ok(r) => r,
        Err(e) => return reply(&mut w, Err(anyhow::anyhow!("bad request: {e}"))).await,
    };
    if let Request::AppSubscribe { channel } = req {
        return subscribe(&ctx, channel, lines, w).await;
    }
    if let Request::Watch = req {
        let mut rx = node.events();
        loop {
            match rx.recv().await {
                // App data is for its channel's client only; UIs get a notice to re-read.
                Ok(Event::App { id: Some(_), channel, view, .. }) => {
                    send_line(&mut w, &json!({ "type": "app", "channel": channel, "view": view })).await?;
                }
                Ok(Event::App { id: None, .. }) => {}
                Ok(e) => {
                    let mut line = serde_json::to_vec(&e)?;
                    line.push(b'\n');
                    w.write_all(&line).await?;
                }
                Err(RecvError::Lagged(n)) => debug!("watcher lagged by {n} events"),
                Err(RecvError::Closed) => return Ok(()),
            }
        }
    }
    let res = handle(&ctx, req).await;
    reply(&mut w, res).await
}

/// A channel client: the items waiting for it, then new ones and live messages as they come.
/// An item it doesn't mark done comes again on its next subscription.
async fn subscribe(
    ctx: &Ctx,
    channel: String,
    mut lines: tokio::io::Lines<BufReader<tokio::net::unix::OwnedReadHalf>>,
    mut w: tokio::net::unix::OwnedWriteHalf,
) -> Result<()> {
    let node = &ctx.node;
    let mut rx = node.events();
    *ctx.clients.lock().unwrap().entry(channel.clone()).or_default() += 1;
    let _sub = Subscribed(ctx.clients.clone(), channel.clone());
    let mut sent = HashSet::new();
    for (id, data) in node.app_pending(&channel)? {
        sent.insert(id);
        send_line(&mut w, &json!({ "id": id, "data": data })).await?;
    }
    loop {
        tokio::select! {
            ev = rx.recv() => match ev {
                Ok(Event::App { id, channel: c, data, from_me: false, view: false }) if c == channel => match id {
                    Some(id) if sent.insert(id) => send_line(&mut w, &json!({ "id": id, "data": data })).await?,
                    Some(_) => {}
                    None => send_line(&mut w, &json!({ "data": data })).await?,
                },
                Ok(_) => {}
                Err(RecvError::Lagged(n)) => debug!("app client lagged by {n} events"),
                Err(RecvError::Closed) => return Ok(()),
            },
            l = lines.next_line() => {
                let Some(l) = l? else { return Ok(()) };
                let done: AppDone = serde_json::from_str(&l).context("bad app client line")?;
                node.app_done(done.done.parse().context("bad item id")?)?;
            }
        }
    }
}

async fn send_line(w: &mut (impl AsyncWriteExt + Unpin), v: &Value) -> Result<()> {
    let mut line = serde_json::to_vec(v)?;
    line.push(b'\n');
    w.write_all(&line).await?;
    Ok(())
}

async fn reply(w: &mut (impl AsyncWriteExt + Unpin), res: Result<Value>) -> Result<()> {
    let v = match res {
        Ok(data) => json!({ "ok": true, "data": data }),
        Err(e) => json!({ "ok": false, "error": format!("{e:#}") }),
    };
    let mut line = serde_json::to_vec(&v)?;
    line.push(b'\n');
    w.write_all(&line).await?;
    Ok(())
}

async fn handle(ctx: &Ctx, req: Request) -> Result<Value> {
    let node = &ctx.node;
    Ok(match req {
        Request::Status => {
            let mut v = serde_json::to_value(node.status()?)?;
            v["wake_unanswered"] = ctx.unanswered.load(std::sync::atomic::Ordering::Relaxed).into();
            v
        }
        Request::PairOffer => json!({ "code": node.pair_offer() }),
        Request::Pair { code } => serde_json::to_value(node.pair(&code).await?)?,
        Request::Unpair => {
            node.unpair()?;
            Value::Null
        }
        Request::Send { paths } => {
            let mut sent = Vec::new();
            for p in paths {
                sent.push(node.send_file(&p).await?);
            }
            serde_json::to_value(sent)?
        }
        Request::Cancel { id } => {
            anyhow::ensure!(node.cancel(id.parse().context("bad message id")?)?, "not a file that is still sending");
            Value::Null
        }
        Request::Msg { text } => serde_json::to_value(node.send_text(text)?)?,
        Request::Ping { text } => serde_json::to_value(node.send_ping(text)?)?,
        Request::Ring => serde_json::to_value(node.ring(RING_TTL)?)?,
        Request::StopRing => {
            anyhow::ensure!(node.send_live(Frame::StopRing), "the phone isn't connected");
            Value::Null
        }
        Request::Notifications { fresh: false } => serde_json::to_value(node.notifs())?,
        Request::Notifications { fresh: true } => ctx.fresh_notifs().await?,
        Request::Json { limit } => json!({
            "status": node.status()?,
            "media": node.media(),
            "messages": node.recent(limit)?,
        }),
        Request::MarkRead => {
            node.mark_read()?;
            Value::Null
        }
        Request::Connect => {
            node.connect().await?;
            Value::Null
        }
        Request::AppSend { channel, data, live, replace } => {
            check_channel(&channel)?;
            if live {
                json!({ "sent": node.send_app_live(&channel, data) })
            } else {
                json!({ "id": node.send_app(&channel, data, replace)? })
            }
        }
        Request::AppAction { channel, data } => {
            check_channel(&channel)?;
            json!({ "id": node.app_local(&channel, action(&data)?)? })
        }
        Request::Channels => ctx.channels()?,
        Request::ChannelsReload => {
            ctx.apps.reload(node)?;
            ctx.channels()?
        }
        Request::List { channel, op } => {
            check_channel(&channel)?;
            let c = ctx.apps.get(&channel).filter(|c| c.kind == apps::Kind::List);
            let c = c.with_context(|| format!("{channel} isn't a list channel (kind = \"list\" in its manifest)"))?;
            ctx.apps.lists().run_op(node, &channel, c.keep_done, op)?
        }
        Request::Thread { channel, limit } => Value::Array(
            node.app_history(&channel, limit)?
                .into_iter()
                .map(|m| {
                    let data = m.text.as_deref().map_or(Value::Null, parse);
                    json!({ "id": m.id, "data": data, "from_me": m.from_me, "ts_ms": m.ts_ms })
                })
                .collect(),
        ),
        Request::Watch | Request::AppSubscribe { .. } => unreachable!("handled by the caller"),
    })
}

/// App data as JSON for the UIs; data that isn't JSON comes as a string.
fn parse(data: &str) -> Value {
    serde_json::from_str(data).unwrap_or_else(|_| Value::String(data.into()))
}

/// A channel the socket may send on: a plain name, and not one of the daemon's own (`_…`).
fn check_channel(channel: &str) -> Result<()> {
    anyhow::ensure!(apps::valid_name(channel), "bad channel name {channel:?} (use [a-z0-9_-]+, not starting with _)");
    Ok(())
}

/// Completes a laptop UI's action envelope: `from`, `uid` and `ts` unless it set them.
fn action(data: &str) -> Result<String> {
    let mut v: Value = serde_json::from_str(data).context("an action is a JSON object")?;
    let o = v.as_object_mut().context("an action is a JSON object")?;
    o.entry("from").or_insert_with(|| json!("laptop"));
    o.entry("uid").or_insert_with(|| json!(uuid::Uuid::new_v4().to_string()));
    o.entry("ts").or_insert_with(|| json!(tether_core::store::now_ms()));
    Ok(v.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_laptop_action_gets_its_envelope() {
        let v: Value = serde_json::from_str(&action(r#"{"action":"add","uid":"u1"}"#).unwrap()).unwrap();
        assert_eq!((v["action"].as_str(), v["from"].as_str(), v["uid"].as_str()), (Some("add"), Some("laptop"), Some("u1")));
        assert!(v["ts"].as_i64().is_some_and(|t| t > 0));
        assert!(action("[1]").is_err());
        assert!(action("nope").is_err());
        assert!(check_channel("_channels").is_err());
        assert!(check_channel("teen").is_ok());
    }
}
