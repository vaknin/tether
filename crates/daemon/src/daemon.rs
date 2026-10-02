//! `tether daemon`: runs the node and serves the CLI/UI socket.

use std::{
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

use crate::ipc::Request;

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
    hold_until: Arc<Mutex<Option<Instant>>>,
}

impl Ctx {
    pub fn new(node: Node, wake: Option<mpsc::UnboundedSender<()>>) -> Self {
        Self { node, wake, hold_until: Arc::default() }
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
    let Some(line) = BufReader::new(r).lines().next_line().await? else { return Ok(()) };
    let req: Request = match serde_json::from_str(&line) {
        Ok(r) => r,
        Err(e) => return reply(&mut w, Err(anyhow::anyhow!("bad request: {e}"))).await,
    };
    if let Request::Watch = req {
        let mut rx = node.events();
        loop {
            match rx.recv().await {
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
        Request::Status => serde_json::to_value(node.status()?)?,
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
        Request::Watch => unreachable!("handled by the caller"),
    })
}
