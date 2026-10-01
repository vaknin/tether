//! SQLite state: the chat log (which doubles as the outbox) and a few settings.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use rusqlite::{Connection, OptionalExtension, params};
use serde::Serialize;
use uuid::Uuid;

use crate::proto::{Body, Item};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum State {
    /// Mine, not yet acked by the peer.
    Queued,
    /// Mine, acked.
    Delivered,
    /// Mine, never delivered before `expires_ms`.
    Expired,
    /// Theirs, a file still arriving.
    Incoming,
    /// Theirs, complete.
    Received,
}

impl State {
    fn as_str(self) -> &'static str {
        match self {
            State::Queued => "queued",
            State::Delivered => "delivered",
            State::Expired => "expired",
            State::Incoming => "incoming",
            State::Received => "received",
        }
    }

    fn parse(s: &str) -> Result<Self> {
        Ok(match s {
            "queued" => State::Queued,
            "delivered" => State::Delivered,
            "expired" => State::Expired,
            "incoming" => State::Incoming,
            "received" => State::Received,
            other => anyhow::bail!("unknown state {other}"),
        })
    }
}

/// One chat entry as the UIs see it (also the JSON shape of `tether json`).
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Message {
    pub id: Uuid,
    #[serde(skip)]
    pub seq: u64,
    pub from_me: bool,
    pub ts_ms: i64,
    #[serde(skip)]
    pub expires_ms: Option<i64>,
    pub kind: &'static str,
    pub text: Option<String>,
    pub file_name: Option<String>,
    pub file_size: Option<u64>,
    #[serde(skip)]
    pub sha256: Option<[u8; 32]>,
    /// Local file: the source for files I send, the saved file for files I receive.
    pub path: Option<PathBuf>,
    pub state: State,
    pub read: bool,
}

impl Message {
    pub fn body(&self) -> Body {
        match self.kind {
            "text" => Body::Text(self.text.clone().unwrap_or_default()),
            "ping" => Body::Ping(self.text.clone().unwrap_or_default()),
            "ring" => Body::Ring,
            _ => Body::File {
                name: self.file_name.clone().unwrap_or_default(),
                size: self.file_size.unwrap_or(0),
                sha256: self.sha256.unwrap_or([0; 32]),
            },
        }
    }

    pub fn item(&self) -> Item {
        Item {
            id: self.id,
            seq: self.seq,
            ts_ms: self.ts_ms,
            expires_ms: self.expires_ms,
            body: self.body(),
        }
    }
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub struct Store {
    db: Connection,
}

const COLS: &str =
    "id, seq, from_me, ts_ms, expires_ms, kind, text, file_name, file_size, sha256, path, state, read";

impl Store {
    pub fn open(path: &Path) -> Result<Self> {
        let db = Connection::open(path).with_context(|| format!("open {}", path.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
        }
        Self::init(db)
    }

    pub fn open_in_memory() -> Result<Self> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(db: Connection) -> Result<Self> {
        db.execute_batch(
            "PRAGMA journal_mode = WAL;
             CREATE TABLE IF NOT EXISTS messages (
                 id TEXT PRIMARY KEY,
                 seq INTEGER NOT NULL,
                 from_me INTEGER NOT NULL,
                 ts_ms INTEGER NOT NULL,
                 expires_ms INTEGER,
                 kind TEXT NOT NULL,
                 text TEXT,
                 file_name TEXT,
                 file_size INTEGER,
                 sha256 BLOB,
                 path TEXT,
                 state TEXT NOT NULL,
                 read INTEGER NOT NULL DEFAULT 0
             );
             CREATE INDEX IF NOT EXISTS messages_ts ON messages (ts_ms);
             CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value BLOB NOT NULL);",
        )?;
        Ok(Self { db })
    }

    pub fn get_kv(&self, key: &str) -> Result<Option<Vec<u8>>> {
        Ok(self
            .db
            .query_row("SELECT value FROM kv WHERE key = ?1", [key], |r| r.get(0))
            .optional()?)
    }

    pub fn set_kv(&self, key: &str, value: &[u8]) -> Result<()> {
        self.db.execute(
            "INSERT INTO kv (key, value) VALUES (?1, ?2)
             ON CONFLICT (key) DO UPDATE SET value = excluded.value",
            params![key, value],
        )?;
        Ok(())
    }

    pub fn del_kv(&self, key: &str) -> Result<()> {
        self.db.execute("DELETE FROM kv WHERE key = ?1", [key])?;
        Ok(())
    }

    pub fn get_str(&self, key: &str) -> Result<Option<String>> {
        Ok(self.get_kv(key)?.map(|v| String::from_utf8_lossy(&v).into_owned()))
    }

    /// Adds an outgoing item to the log/outbox.
    pub fn enqueue(&self, body: Body, path: Option<PathBuf>, expires_ms: Option<i64>) -> Result<Message> {
        let seq: i64 = self.db.query_row(
            "SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE from_me = 1",
            [],
            |r| r.get(0),
        )?;
        let item = Item { id: Uuid::new_v4(), seq: seq as u64, ts_ms: now_ms(), expires_ms, body };
        let msg = to_message(&item, true, path, State::Queued, true);
        self.insert(&msg)?;
        Ok(msg)
    }

    /// Stores an item from the peer. Returns `(message, is_new)`.
    pub fn receive(&self, item: &Item) -> Result<(Message, bool)> {
        if let Some(m) = self.get(item.id)? {
            return Ok((m, false));
        }
        let state = match item.body {
            Body::File { .. } => State::Incoming,
            _ => State::Received,
        };
        let msg = to_message(item, false, None, state, false);
        self.insert(&msg)?;
        Ok((msg, true))
    }

    fn insert(&self, m: &Message) -> Result<()> {
        self.db.execute(
            &format!("INSERT INTO messages ({COLS}) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)"),
            params![
                m.id.to_string(),
                m.seq as i64,
                m.from_me,
                m.ts_ms,
                m.expires_ms,
                m.kind,
                m.text,
                m.file_name,
                m.file_size.map(|s| s as i64),
                m.sha256.map(|h| h.to_vec()),
                m.path.as_ref().map(|p| p.to_string_lossy().into_owned()),
                m.state.as_str(),
                m.read,
            ],
        )?;
        Ok(())
    }

    pub fn get(&self, id: Uuid) -> Result<Option<Message>> {
        Ok(self
            .db
            .query_row(&format!("SELECT {COLS} FROM messages WHERE id = ?1"), [id.to_string()], row)
            .optional()?)
    }

    /// Unacked items, oldest first. Items past their expiry are marked expired and returned
    /// separately so the UI can show it.
    pub fn outbox(&self) -> Result<(Vec<Message>, Vec<Message>)> {
        let now = now_ms();
        let mut stmt = self.db.prepare(&format!(
            "SELECT {COLS} FROM messages WHERE from_me = 1 AND state = 'queued' ORDER BY seq"
        ))?;
        let all = stmt.query_map([], row)?.collect::<rusqlite::Result<Vec<_>>>()?;
        let (expired, live): (Vec<_>, Vec<_>) =
            all.into_iter().partition(|m| m.expires_ms.is_some_and(|e| e <= now));
        let expired = expired
            .into_iter()
            .map(|m| self.set_state(m.id, State::Expired))
            .collect::<Result<Vec<_>>>()?
            .into_iter()
            .flatten()
            .collect();
        Ok((live, expired))
    }

    pub fn set_state(&self, id: Uuid, state: State) -> Result<Option<Message>> {
        self.db.execute(
            "UPDATE messages SET state = ?2 WHERE id = ?1",
            params![id.to_string(), state.as_str()],
        )?;
        self.get(id)
    }

    /// Marks my item as delivered; `None` if it isn't mine or was already delivered.
    pub fn ack(&self, id: Uuid) -> Result<Option<Message>> {
        let n = self.db.execute(
            "UPDATE messages SET state = 'delivered' WHERE id = ?1 AND from_me = 1 AND state = 'queued'",
            [id.to_string()],
        )?;
        if n == 0 { Ok(None) } else { self.get(id) }
    }

    pub fn set_path(&self, id: Uuid, path: &Path) -> Result<Option<Message>> {
        self.db.execute(
            "UPDATE messages SET path = ?2 WHERE id = ?1",
            params![id.to_string(), path.to_string_lossy()],
        )?;
        self.get(id)
    }

    /// The most recent `limit` messages, oldest first.
    pub fn recent(&self, limit: usize) -> Result<Vec<Message>> {
        let mut stmt = self.db.prepare(&format!(
            "SELECT * FROM (SELECT {COLS} FROM messages ORDER BY ts_ms DESC, seq DESC LIMIT ?1)
             ORDER BY ts_ms, seq"
        ))?;
        Ok(stmt.query_map([limit as i64], row)?.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn unread(&self) -> Result<u64> {
        Ok(self.db.query_row(
            "SELECT COUNT(*) FROM messages WHERE from_me = 0 AND read = 0",
            [],
            |r| r.get::<_, i64>(0),
        )? as u64)
    }

    pub fn mark_read(&self) -> Result<()> {
        self.db.execute("UPDATE messages SET read = 1 WHERE read = 0", [])?;
        Ok(())
    }
}

fn to_message(item: &Item, from_me: bool, path: Option<PathBuf>, state: State, read: bool) -> Message {
    let (kind, text, file_name, file_size, sha256) = match &item.body {
        Body::Text(t) => ("text", Some(t.clone()), None, None, None),
        Body::Ping(t) => ("ping", Some(t.clone()), None, None, None),
        Body::Ring => ("ring", None, None, None, None),
        Body::File { name, size, sha256 } => ("file", None, Some(name.clone()), Some(*size), Some(*sha256)),
    };
    Message {
        id: item.id,
        seq: item.seq,
        from_me,
        ts_ms: item.ts_ms,
        expires_ms: item.expires_ms,
        kind,
        text,
        file_name,
        file_size,
        sha256,
        path,
        state,
        read,
    }
}

fn row(r: &rusqlite::Row<'_>) -> rusqlite::Result<Message> {
    let id: String = r.get(0)?;
    let kind: String = r.get(5)?;
    let state: String = r.get(11)?;
    let sha: Option<Vec<u8>> = r.get(9)?;
    let conv = |e: anyhow::Error| {
        rusqlite::Error::FromSqlConversionFailure(0, rusqlite::types::Type::Text, e.into())
    };
    Ok(Message {
        id: Uuid::parse_str(&id).map_err(|e| conv(e.into()))?,
        seq: r.get::<_, i64>(1)? as u64,
        from_me: r.get(2)?,
        ts_ms: r.get(3)?,
        expires_ms: r.get(4)?,
        kind: match kind.as_str() {
            "text" => "text",
            "ping" => "ping",
            "ring" => "ring",
            _ => "file",
        },
        text: r.get(6)?,
        file_name: r.get(7)?,
        file_size: r.get::<_, Option<i64>>(8)?.map(|s| s as u64),
        sha256: sha.and_then(|v| v.try_into().ok()),
        path: r.get::<_, Option<String>>(10)?.map(PathBuf::from),
        state: State::parse(&state).map_err(conv)?,
        read: r.get(12)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn outbox_holds_until_acked_and_receive_dedupes() {
        let a = Store::open_in_memory().unwrap();
        let b = Store::open_in_memory().unwrap();
        let m1 = a.enqueue(Body::Text("one".into()), None, None).unwrap();
        let m2 = a.enqueue(Body::Text("two".into()), None, None).unwrap();
        assert_eq!((m1.seq, m2.seq), (1, 2));

        let (out, _) = a.outbox().unwrap();
        assert_eq!(out.len(), 2);
        // Delivered, then the ack is lost: the item is resent and must not duplicate.
        assert!(b.receive(&out[0].item()).unwrap().1);
        assert!(!b.receive(&out[0].item()).unwrap().1);
        assert_eq!(b.recent(10).unwrap().len(), 1);
        assert_eq!(b.unread().unwrap(), 1);

        assert_eq!(a.ack(m1.id).unwrap().unwrap().state, State::Delivered);
        assert!(a.ack(m1.id).unwrap().is_none());
        let (out, _) = a.outbox().unwrap();
        assert_eq!(out.iter().map(|m| m.id).collect::<Vec<_>>(), vec![m2.id]);
    }

    #[test]
    fn stale_items_expire_instead_of_sending() {
        let a = Store::open_in_memory().unwrap();
        a.enqueue(Body::Ring, None, Some(now_ms() - 1)).unwrap();
        let (live, expired) = a.outbox().unwrap();
        assert!(live.is_empty());
        assert_eq!(expired[0].state, State::Expired);
    }
}
