//! uniffi bindings of [`tether_core::node::Node`] for the Android app.
//!
//! The library owns one multi-threaded tokio runtime. Async exports run their work on it and are
//! awaited from Kotlin coroutines. The phone holds no connection while idle, so the app starts a
//! node when it has work (FCM wake, a share, the app opening) and shuts it down once the link has
//! gone idle; [`start`] can be called again afterwards.

use std::{
    path::PathBuf,
    sync::{Arc, LazyLock, Mutex},
    time::Duration,
};

use tether_core::{
    node::{self, Config, Net, Node},
    proto::{self, Frame},
    store::{self, State},
};
use tokio::{runtime::Runtime, sync::broadcast::error::RecvError, task::JoinHandle};

uniffi::setup_scaffolding!();

static RT: LazyLock<Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .thread_name("tether")
        .build()
        .expect("tokio runtime")
});

#[derive(Debug, uniffi::Error)]
pub enum TetherError {
    Failed { reason: String },
}

impl std::fmt::Display for TetherError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TetherError::Failed { reason } => f.write_str(reason),
        }
    }
}

impl std::error::Error for TetherError {}

impl From<anyhow::Error> for TetherError {
    fn from(e: anyhow::Error) -> Self {
        TetherError::Failed { reason: format!("{e:#}") }
    }
}

type Res<T> = Result<T, TetherError>;

/// Runs `fut` on the library's runtime; iroh and the node's tasks need a tokio context.
async fn on_rt<T: Send + 'static>(fut: impl Future<Output = T> + Send + 'static) -> T {
    RT.spawn(fut).await.expect("tether task panicked")
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MsgState {
    Queued,
    Delivered,
    Expired,
    Incoming,
    Received,
}

impl From<State> for MsgState {
    fn from(s: State) -> Self {
        match s {
            State::Queued => MsgState::Queued,
            State::Delivered => MsgState::Delivered,
            State::Expired => MsgState::Expired,
            State::Incoming => MsgState::Incoming,
            State::Received => MsgState::Received,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum MsgKind {
    Text,
    File,
    Ping,
    Ring,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ChatMessage {
    pub id: String,
    pub from_me: bool,
    pub ts_ms: i64,
    pub kind: MsgKind,
    /// The text of a text message or ping.
    pub text: Option<String>,
    pub file_name: Option<String>,
    pub file_size: Option<u64>,
    /// Local file: the source of a file I send, the saved file of one I receive.
    pub path: Option<String>,
    pub state: MsgState,
    pub read: bool,
}

impl From<store::Message> for ChatMessage {
    fn from(m: store::Message) -> Self {
        ChatMessage {
            id: m.id.to_string(),
            from_me: m.from_me,
            ts_ms: m.ts_ms,
            kind: match m.kind {
                "text" => MsgKind::Text,
                "ping" => MsgKind::Ping,
                "ring" => MsgKind::Ring,
                _ => MsgKind::File,
            },
            text: m.text,
            file_name: m.file_name,
            file_size: m.file_size,
            path: m.path.map(|p| p.to_string_lossy().into_owned()),
            state: m.state.into(),
            read: m.read,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PeerInfo {
    pub id: String,
    pub name: String,
}

impl From<node::PeerInfo> for PeerInfo {
    fn from(p: node::PeerInfo) -> Self {
        PeerInfo { id: p.id, name: p.name }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Status {
    pub id: String,
    pub name: String,
    pub peer: Option<PeerInfo>,
    pub connected: bool,
    pub queued: u64,
    pub unread: u64,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum MediaCmd {
    Play,
    Pause,
    PlayPause,
    Next,
    Previous,
    Seek { position_ms: i64 },
    Volume { percent: u8 },
}

impl From<proto::MediaCmd> for MediaCmd {
    fn from(c: proto::MediaCmd) -> Self {
        match c {
            proto::MediaCmd::Play => MediaCmd::Play,
            proto::MediaCmd::Pause => MediaCmd::Pause,
            proto::MediaCmd::PlayPause => MediaCmd::PlayPause,
            proto::MediaCmd::Next => MediaCmd::Next,
            proto::MediaCmd::Previous => MediaCmd::Previous,
            proto::MediaCmd::Seek { position_ms } => MediaCmd::Seek { position_ms },
            proto::MediaCmd::Volume(percent) => MediaCmd::Volume { percent },
        }
    }
}

/// The node's events, minus the laptop-only ones (the phone's own media and notifications).
#[derive(Debug, Clone, uniffi::Enum)]
pub enum Event {
    Connected,
    Disconnected,
    Paired { peer: PeerInfo },
    Unpaired,
    Message { msg: ChatMessage },
    Progress { id: String, done: u64, total: u64 },
    MediaCmd { cmd: MediaCmd },
    StopRing,
}

impl Event {
    fn from_core(e: node::Event) -> Option<Self> {
        Some(match e {
            node::Event::Connected => Event::Connected,
            node::Event::Disconnected => Event::Disconnected,
            node::Event::Paired { peer } => Event::Paired { peer: peer.into() },
            node::Event::Unpaired => Event::Unpaired,
            node::Event::Message(m) => Event::Message { msg: m.into() },
            node::Event::Progress { id, done, total } => {
                Event::Progress { id: id.to_string(), done, total }
            }
            node::Event::MediaCmd { cmd } => Event::MediaCmd { cmd: cmd.into() },
            node::Event::StopRing => Event::StopRing,
            node::Event::Media { .. } | node::Event::Notifs { .. } => return None,
        })
    }
}

/// Implemented in Kotlin. Called on a runtime thread, in order.
#[uniffi::export(callback_interface)]
pub trait EventListener: Send + Sync {
    fn on_event(&self, event: Event);
}

#[derive(uniffi::Object)]
pub struct TetherNode {
    node: Node,
    listener: Mutex<Option<JoinHandle<()>>>,
}

/// Starts the phone's node: n0 relays + mDNS, a random port, and no redial loop (the phone dials
/// only when it has something to send or was woken).
#[uniffi::export]
pub async fn start(state_dir: String, download_dir: String, name: String) -> Res<Arc<TetherNode>> {
    init_logging();
    on_rt(async move {
        let mut cfg = Config::new(PathBuf::from(state_dir), PathBuf::from(download_dir), name);
        cfg.net = Net::Internet { port: None };
        cfg.redial = None;
        let node = Node::start(cfg).await?;
        Ok(Arc::new(TetherNode { node, listener: Mutex::new(None) }))
    })
    .await
}

#[uniffi::export]
impl TetherNode {
    /// Delivers every later event to `listener`, replacing any earlier one.
    pub fn set_listener(&self, listener: Box<dyn EventListener>) {
        let mut rx = self.node.events();
        let task = RT.spawn(async move {
            loop {
                match rx.recv().await {
                    Ok(e) => {
                        if let Some(e) = Event::from_core(e) {
                            listener.on_event(e);
                        }
                    }
                    Err(RecvError::Lagged(n)) => tracing::warn!("listener lagged by {n} events"),
                    Err(RecvError::Closed) => return,
                }
            }
        });
        if let Some(old) = self.listener.lock().unwrap().replace(task) {
            old.abort();
        }
    }

    pub fn status(&self) -> Res<Status> {
        let s = self.node.status()?;
        Ok(Status {
            id: s.id,
            name: s.name,
            peer: s.peer.map(Into::into),
            connected: s.connected,
            queued: s.queued as u64,
            unread: s.unread,
        })
    }

    pub fn is_connected(&self) -> bool {
        self.node.is_connected()
    }

    /// The newest `limit` messages, oldest first.
    pub fn recent(&self, limit: u32) -> Res<Vec<ChatMessage>> {
        Ok(self.node.recent(limit as usize)?.into_iter().map(Into::into).collect())
    }

    pub fn unread(&self) -> Res<u64> {
        Ok(self.node.unread()?)
    }

    pub fn mark_read(&self) -> Res<()> {
        Ok(self.node.mark_read()?)
    }

    /// Pairs with the laptop whose QR showed `code` (`tether:1:<id>:<token>`).
    pub async fn pair(&self, code: String) -> Res<PeerInfo> {
        let node = self.node.clone();
        Ok(on_rt(async move { node.pair(&code).await }).await?.into())
    }

    pub fn unpair(&self) -> Res<()> {
        Ok(self.node.unpair()?)
    }

    /// Queues a chat message and dials if not connected.
    pub fn send_text(&self, text: String) -> Res<ChatMessage> {
        let m = self.node.send_text(text)?;
        self.dial_soon();
        Ok(m.into())
    }

    pub fn send_ping(&self, text: String) -> Res<ChatMessage> {
        let m = self.node.send_ping(text)?;
        self.dial_soon();
        Ok(m.into())
    }

    /// Queues the file at `path`, which must stay in place until the message is delivered.
    pub async fn send_file(&self, path: String) -> Res<ChatMessage> {
        let node = self.node.clone();
        let m = on_rt(async move { node.send_file(std::path::Path::new(&path)).await }).await?;
        self.dial_soon();
        Ok(m.into())
    }

    /// Sends the FCM registration token; false when not connected.
    pub fn send_push_token(&self, token: String) -> bool {
        self.node.send_live(Frame::PushToken(token))
    }

    pub fn stop_ring(&self) -> bool {
        self.node.send_live(Frame::StopRing)
    }

    /// Keeps the link open past the idle timeout while the app is in front or media plays.
    pub fn set_stay(&self, stay: bool) {
        self.node.set_stay(stay);
    }

    /// Dials the laptop unless connected. Gives up after 30 s.
    pub async fn connect(&self) -> Res<()> {
        let node = self.node.clone();
        Ok(on_rt(async move { node.connect().await }).await?)
    }

    pub fn disconnect(&self) {
        self.node.disconnect();
    }

    /// Closes the link and the endpoint. The object is unusable afterwards; call [`start`] again.
    pub async fn shutdown(&self) {
        if let Some(t) = self.listener.lock().unwrap().take() {
            t.abort();
        }
        let node = self.node.clone();
        on_rt(async move {
            // Bounded: a hung close must not keep the wake service alive.
            let _ = tokio::time::timeout(Duration::from_secs(5), node.shutdown()).await;
        })
        .await;
    }
}

impl TetherNode {
    fn dial_soon(&self) {
        if self.node.is_connected() {
            return;
        }
        let node = self.node.clone();
        RT.spawn(async move {
            if let Err(e) = node.connect().await {
                tracing::debug!("dial after send failed: {e:#}");
            }
        });
    }
}

fn init_logging() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        let filter = tracing_subscriber::EnvFilter::new("info,tether_core=debug,iroh=warn");
        let b = tracing_subscriber::fmt().with_env_filter(filter).with_ansi(false);
        #[cfg(target_os = "android")]
        let b = b.without_time().with_writer(logcat::Writer::default);
        let _ = b.try_init();
    });
}

/// Minimal logcat writer: each formatted line goes to `__android_log_write` with tag "tether".
#[cfg(target_os = "android")]
mod logcat {
    use std::{ffi::CString, io};

    #[link(name = "log")]
    unsafe extern "C" {
        fn __android_log_write(prio: i32, tag: *const u8, text: *const u8) -> i32;
    }

    const INFO: i32 = 4;

    #[derive(Default)]
    pub struct Writer(Vec<u8>);

    impl io::Write for Writer {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            self.0.extend_from_slice(buf);
            Ok(buf.len())
        }

        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    impl Drop for Writer {
        fn drop(&mut self) {
            let text: Vec<u8> = self.0.iter().copied().filter(|&b| b != 0 && b != b'\n').collect();
            if let Ok(text) = CString::new(text) {
                // SAFETY: both pointers are NUL-terminated and live for the call.
                unsafe { __android_log_write(INFO, c"tether".as_ptr().cast(), text.as_ptr().cast()) };
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Two FFI nodes can't use loopback (that's core-only), so this only checks the plumbing that
    /// needs no network: start, status, queue, recent, shutdown, restart on the same state.
    #[test]
    fn start_queue_restart() {
        let dir = std::env::temp_dir().join(format!("tether-ffi-{}", uuid::Uuid::new_v4()));
        let state = dir.join("state").to_string_lossy().into_owned();
        let dl = dir.join("dl").to_string_lossy().into_owned();
        RT.block_on(async {
            let n = start(state.clone(), dl.clone(), "Phone".into()).await.unwrap();
            let id = n.status().unwrap().id;
            assert!(n.status().unwrap().peer.is_none());
            // Not paired: queues fine, the background dial just fails.
            let m = n.send_text("hi".into()).unwrap();
            assert_eq!(m.state, MsgState::Queued);
            assert_eq!(m.kind, MsgKind::Text);
            n.shutdown().await;
            let n = start(state, dl, "Phone".into()).await.unwrap();
            assert_eq!(n.status().unwrap().id, id, "key survives a restart");
            let r = n.recent(10).unwrap();
            assert_eq!(r.len(), 1);
            assert_eq!(r[0].text.as_deref(), Some("hi"));
            n.shutdown().await;
        });
        let _ = std::fs::remove_dir_all(dir);
    }
}
