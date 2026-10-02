//! CLI ↔ daemon protocol over the unix socket: one JSON request line, then one JSON reply line
//! (`{"ok":true,"data":…}` or `{"ok":false,"error":"…"}`). `watch` instead streams one event per line.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader, Lines},
    net::{
        UnixStream,
        unix::{OwnedReadHalf, OwnedWriteHalf},
    },
};

#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "cmd", rename_all = "snake_case")]
pub enum Request {
    Status,
    PairOffer,
    Pair { code: String },
    Unpair,
    Send { paths: Vec<PathBuf> },
    /// Stop a file I'm sending.
    Cancel { id: String },
    Msg { text: String },
    Ping { text: String },
    Ring,
    StopRing,
    /// `fresh`: wake the phone if needed, wait for its list, and keep the link up for a while.
    Notifications {
        #[serde(default)]
        fresh: bool,
    },
    Json { limit: usize },
    MarkRead,
    Connect,
    Watch,
}

pub fn default_socket() -> PathBuf {
    if let Some(p) = std::env::var_os("TETHER_SOCKET") {
        return p.into();
    }
    let dir = std::env::var_os("XDG_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir);
    dir.join("tether.sock")
}

async fn open(sock: &Path, req: &Request) -> Result<(Lines<BufReader<OwnedReadHalf>>, OwnedWriteHalf)> {
    let stream = UnixStream::connect(sock).await.with_context(|| {
        format!(
            "the tether daemon isn't running ({} not found); start it with `systemctl --user start tether`",
            sock.display()
        )
    })?;
    let (r, mut w) = stream.into_split();
    let mut line = serde_json::to_vec(req)?;
    line.push(b'\n');
    w.write_all(&line).await?;
    Ok((BufReader::new(r).lines(), w))
}

pub async fn call(sock: &Path, req: &Request) -> Result<Value> {
    let (mut lines, _w) = open(sock, req).await?;
    let line = lines.next_line().await?.context("daemon closed the connection")?;
    let mut reply: Value = serde_json::from_str(&line)?;
    if reply["ok"] == Value::Bool(true) {
        Ok(reply["data"].take())
    } else {
        bail!("{}", reply["error"].as_str().unwrap_or("daemon error"))
    }
}

/// Event lines, as JSON strings, until the daemon goes away. Keep the write half alive with them.
pub async fn watch(sock: &Path) -> Result<(Lines<BufReader<OwnedReadHalf>>, OwnedWriteHalf)> {
    open(sock, &Request::Watch).await
}
