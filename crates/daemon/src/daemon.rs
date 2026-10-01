//! `tether daemon`: runs the node and serves the CLI/UI socket.

use std::{path::Path, time::Duration};

use anyhow::{Context, Result};
use serde_json::{Value, json};
use tether_core::node::Node;
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    net::{UnixListener, UnixStream},
    sync::broadcast::error::RecvError,
};
use tracing::{debug, info};

use crate::ipc::Request;

const RING_TTL: Duration = Duration::from_secs(120);

pub async fn serve(node: Node, sock: &Path) -> Result<()> {
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
        let node = node.clone();
        tokio::spawn(async move {
            if let Err(e) = client(node, stream).await {
                debug!("client: {e:#}");
            }
        });
    }
}

async fn client(node: Node, stream: UnixStream) -> Result<()> {
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
    let res = handle(&node, req).await;
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

async fn handle(node: &Node, req: Request) -> Result<Value> {
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
        Request::Msg { text } => serde_json::to_value(node.send_text(text)?)?,
        Request::Ping { text } => serde_json::to_value(node.send_ping(text)?)?,
        Request::Ring => serde_json::to_value(node.ring(RING_TTL)?)?,
        Request::Notifications => serde_json::to_value(node.notifs())?,
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
