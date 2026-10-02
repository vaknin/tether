use std::path::Path;

use tokio::sync::broadcast::Receiver;

use super::*;

const WAIT: Duration = Duration::from_secs(15);

fn config(dir: &Path, name: &str) -> Config {
    let mut cfg = Config::new(dir.join(name).join("state"), dir.join(name).join("dl"), name);
    cfg.net = Net::Loopback;
    cfg.redial = Some((Duration::from_millis(100), Duration::from_millis(400)));
    cfg
}

async fn start(dir: &Path, name: &str) -> Node {
    Node::start(config(dir, name)).await.unwrap()
}

async fn paired(dir: &Path) -> (Node, Node) {
    let a = start(dir, "a").await;
    let b = start(dir, "b").await;
    introduce(&a, &b);
    b.pair(&a.pair_offer()).await.unwrap();
    (a, b)
}

fn introduce(a: &Node, b: &Node) {
    a.add_hint(b.addr());
    b.add_hint(a.addr());
}

async fn wait_for(rx: &mut Receiver<Event>, f: impl Fn(&Event) -> bool) -> Event {
    timeout(WAIT, async {
        loop {
            let e = rx.recv().await.unwrap();
            if f(&e) {
                return e;
            }
        }
    })
    .await
    .expect("event did not arrive")
}

impl Node {
    fn db_state(&self, id: Uuid) -> State {
        self.inner.db(|s| s.get(id)).unwrap().unwrap().state
    }
}

fn msg_in(e: &Event, id: Uuid, state: State) -> bool {
    matches!(e, Event::Message(m) if m.id == id && m.state == state)
}

#[tokio::test]
async fn pairs_then_chats_both_ways() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    assert_eq!(a.peer().unwrap().id, b.id().to_string());
    assert_eq!(a.peer().unwrap().name, "b");
    assert_eq!(b.peer().unwrap().name, "a");

    let (mut ea, mut eb) = (a.events(), b.events());
    let m = a.send_text("hi").unwrap();
    let got = wait_for(&mut eb, |e| msg_in(e, m.id, State::Received)).await;
    let Event::Message(got) = got else { unreachable!() };
    assert_eq!(got.text.as_deref(), Some("hi"));
    wait_for(&mut ea, |e| msg_in(e, m.id, State::Delivered)).await;

    let r = b.send_text("back").unwrap();
    wait_for(&mut ea, |e| msg_in(e, r.id, State::Received)).await;
    assert_eq!(a.unread().unwrap(), 1);
    assert!(a.is_connected() && b.is_connected());
}

#[tokio::test]
async fn pairing_code_is_single_use() {
    let d = tempfile::tempdir().unwrap();
    let a = start(d.path(), "a").await;
    let b = start(d.path(), "b").await;
    let c = start(d.path(), "c").await;
    introduce(&a, &b);
    introduce(&a, &c);

    let code = a.pair_offer();
    b.pair(&code).await.unwrap();
    assert!(c.pair(&code).await.is_err());

    let forged = format!("tether:1:{}:{}", a.id(), BASE32_NOPAD.encode(&[7u8; 32]));
    a.pair_offer();
    assert!(c.pair(&forged).await.is_err());
    assert_eq!(a.peer().unwrap().id, b.id().to_string());
}

#[tokio::test]
async fn unpaired_endpoint_is_refused() {
    let d = tempfile::tempdir().unwrap();
    let (a, _b) = paired(d.path()).await;
    let c = start(d.path(), "c").await;
    c.add_hint(a.addr());
    let conn = c.inner.ep.connect(a.id(), ALPN).await.unwrap();
    let err = timeout(WAIT, conn.closed()).await.unwrap();
    match err {
        iroh::endpoint::ConnectionError::ApplicationClosed(close) => {
            assert_eq!(close.error_code, CLOSE_NOT_PAIRED.into());
        }
        other => panic!("expected a refusal, got {other:?}"),
    }
    assert!(c.connect().await.is_err(), "c has no peer to dial");
}

#[tokio::test]
async fn queued_while_offline_is_delivered_later() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    b.shutdown().await;

    let file = d.path().join("shot.png");
    std::fs::write(&file, b"not really a png").unwrap();
    let t = a.send_text("one").unwrap();
    let f = a.send_file(&file).await.unwrap();
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert_eq!(a.status().unwrap().queued, 2);

    // b comes back on a new port, with its old state.
    let b = start(d.path(), "b").await;
    let (mut ea, mut eb) = (a.events(), b.events());
    introduce(&a, &b);
    // The phone checks in when it starts; a's own redial may still be stuck on b's old address.
    b.connect().await.unwrap();
    wait_for(&mut eb, |e| msg_in(e, f.id, State::Received)).await;
    let got = b.recent(10).unwrap();
    assert!(got.iter().any(|m| m.id == t.id && m.text.as_deref() == Some("one")));
    let saved = got.iter().find(|m| m.id == f.id).unwrap().path.clone().unwrap();
    assert_eq!(saved, d.path().join("b/dl/shot.png"));
    assert_eq!(std::fs::read(saved).unwrap(), b"not really a png");

    wait_for(&mut ea, |e| msg_in(e, f.id, State::Delivered)).await;
    assert_eq!(a.status().unwrap().queued, 0);
}

#[tokio::test]
async fn file_resumes_from_partial_download() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    b.shutdown().await;

    let data: Vec<u8> = (0..1_000_000u32).map(|i| (i * 7 % 251) as u8).collect();
    let file = d.path().join("big.bin");
    std::fs::write(&file, &data).unwrap();
    let f = a.send_file(&file).await.unwrap();

    // An earlier attempt got 300 kB across before the link dropped.
    let dl = d.path().join("b/dl");
    std::fs::write(files::part_path(&dl, f.id), &data[..300_000]).unwrap();

    let b = start(d.path(), "b").await;
    let mut eb = b.events();
    introduce(&a, &b);
    b.connect().await.unwrap();
    let first = wait_for(&mut eb, |e| matches!(e, Event::Progress { id, .. } if *id == f.id)).await;
    assert!(matches!(first, Event::Progress { done: 300_000, total: 1_000_000, .. }));
    wait_for(&mut eb, |e| msg_in(e, f.id, State::Received)).await;
    assert_eq!(std::fs::read(dl.join("big.bin")).unwrap(), data);
    assert!(!files::part_path(&dl, f.id).exists());
}

#[tokio::test]
async fn cancel_stops_a_file_midway() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    let file = d.path().join("huge.bin");
    std::fs::write(&file, vec![7u8; 64 << 20]).unwrap();
    let (mut ea, mut eb) = (a.events(), b.events());
    let f = a.send_file(&file).await.unwrap();
    wait_for(&mut eb, |e| matches!(e, Event::Progress { id, done, .. } if *id == f.id && *done > 0)).await;
    assert!(a.cancel(f.id).unwrap());

    wait_for(&mut eb, |e| msg_in(e, f.id, State::Cancelled)).await;
    // My side shows it at once, then hears the peer confirm.
    wait_for(&mut ea, |e| msg_in(e, f.id, State::Cancelled)).await;
    assert_eq!(a.status().unwrap().queued, 0);
    let dl = d.path().join("b/dl");
    timeout(WAIT, async {
        while files::part_path(&dl, f.id).exists() {
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    })
    .await
    .expect("the partial file was not removed");
    assert!(!dl.join("huge.bin").exists());
    // Delivered or cancelled files can't be cancelled again.
    assert!(!a.cancel(f.id).unwrap());
}

#[tokio::test]
async fn cancel_while_offline_reaches_the_peer_later() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    b.shutdown().await;
    let file = d.path().join("later.bin");
    std::fs::write(&file, b"never sent").unwrap();
    let f = a.send_file(&file).await.unwrap();
    assert!(a.cancel(f.id).unwrap());
    assert_eq!(a.db_state(f.id), State::Cancelling);

    let b = start(d.path(), "b").await;
    let mut ea = a.events();
    introduce(&a, &b);
    b.connect().await.unwrap();
    wait_for(&mut ea, |e| msg_in(e, f.id, State::Cancelled)).await;
    assert!(b.recent(10).unwrap().iter().all(|m| m.id != f.id));
}

#[tokio::test]
async fn idle_link_closes_unless_asked_to_stay() {
    let d = tempfile::tempdir().unwrap();
    let mut ca = config(d.path(), "a");
    ca.idle_timeout = Duration::from_millis(800);
    let a = Node::start(ca).await.unwrap();
    let b = start(d.path(), "b").await;
    introduce(&a, &b);
    b.pair(&a.pair_offer()).await.unwrap();

    let mut ea = a.events();
    b.connect().await.unwrap();
    b.set_stay(true);
    wait_for(&mut ea, |e| matches!(e, Event::Connected)).await;
    tokio::time::sleep(Duration::from_millis(2000)).await;
    assert!(a.is_connected(), "b asked to stay connected");

    b.set_stay(false);
    wait_for(&mut ea, |e| matches!(e, Event::Disconnected)).await;
}

#[tokio::test]
async fn stay_set_before_connecting_reaches_the_peer() {
    let d = tempfile::tempdir().unwrap();
    let mut ca = config(d.path(), "a");
    ca.idle_timeout = Duration::from_millis(800);
    let a = Node::start(ca).await.unwrap();
    let b = start(d.path(), "b").await;
    introduce(&a, &b);
    b.pair(&a.pair_offer()).await.unwrap();

    // The phone app sets stay when it starts the node, before there is any link.
    b.set_stay(true);
    let mut ea = a.events();
    b.connect().await.unwrap();
    wait_for(&mut ea, |e| matches!(e, Event::Connected)).await;
    tokio::time::sleep(Duration::from_millis(2000)).await;
    assert!(a.is_connected(), "b asked to stay connected before dialing");
}

#[tokio::test]
async fn simultaneous_dials_settle_on_one_link() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    let (ra, rb) = tokio::join!(a.connect(), b.connect());
    ra.unwrap();
    rb.unwrap();
    tokio::time::sleep(Duration::from_millis(500)).await;
    assert!(a.is_connected() && b.is_connected());
    let smaller = if a.id().as_bytes() < b.id().as_bytes() { a.id() } else { b.id() };
    assert_eq!(a.inner.current().unwrap().dialer, smaller);
    assert_eq!(b.inner.current().unwrap().dialer, smaller);

    let mut eb = b.events();
    let m = a.send_text("after the race").unwrap();
    wait_for(&mut eb, |e| msg_in(e, m.id, State::Received)).await;
}

#[tokio::test]
async fn media_state_is_dropped_with_the_link() {
    let d = tempfile::tempdir().unwrap();
    let (laptop, phone) = paired(d.path()).await;
    let mut el = laptop.events();
    phone.connect().await.unwrap();
    wait_for(&mut el, |e| matches!(e, Event::Connected)).await;
    let state = MediaState {
        player: "Spotify".into(),
        title: "t".into(),
        artist: "a".into(),
        album: String::new(),
        playing: true,
        position_ms: 1000,
        duration_ms: 60_000,
        volume: Some(40),
        can_seek: true,
        can_next: true,
        can_previous: true,
    };
    assert!(phone.send_live(Frame::Media(Some(state.clone()))));
    wait_for(&mut el, |e| matches!(e, Event::Media { state: Some(_) })).await;
    assert_eq!(laptop.media(), Some(state));

    phone.disconnect();
    wait_for(&mut el, |e| matches!(e, Event::Media { state: None })).await;
    assert_eq!(laptop.media(), None);
}

fn app_ev(e: &Event) -> bool {
    matches!(e, Event::App { .. })
}

#[tokio::test]
async fn app_views_replace_and_items_reach_the_channel_not_the_chat() {
    let d = tempfile::tempdir().unwrap();
    let (a, b) = paired(d.path()).await;
    b.shutdown().await;

    // Offline: two views queued, the newer replaces the older; a post queues beside them.
    a.send_app("teen", "old".into(), true).unwrap();
    let v = a.send_app("teen", "new".into(), true).unwrap();
    let post = a.send_app("teen", "post".into(), false).unwrap();
    assert_eq!(a.status().unwrap().queued, 2);
    assert_eq!(a.app_view("teen").unwrap().as_deref(), Some("new"));

    let b = start(d.path(), "b").await;
    let mut eb = b.events();
    introduce(&a, &b);
    b.connect().await.unwrap();
    let got = wait_for(&mut eb, app_ev).await;
    let Event::App { id, channel, data, from_me, view } = got else { unreachable!() };
    assert_eq!((id, channel.as_str(), data.as_str(), from_me, view), (Some(v), "teen", "new", false, true));
    let got = wait_for(&mut eb, app_ev).await;
    assert!(matches!(got, Event::App { id: Some(id), view: false, .. } if id == post));
    assert!(b.recent(10).unwrap().is_empty(), "not in the chat");
    assert_eq!(b.unread().unwrap(), 0);
    assert_eq!(b.app_view("teen").unwrap().as_deref(), Some("new"));
    assert_eq!(b.app_pending("teen").unwrap(), vec![(post, "post".to_string())], "views aren't pending");
    b.app_done(post).unwrap();
    assert!(b.app_pending("teen").unwrap().is_empty());

    // Online: a newer view replaces the delivered one on the receiving side too.
    let v2 = a.send_app("teen", "newer".into(), true).unwrap();
    wait_for(&mut eb, |e| matches!(e, Event::App { id: Some(id), .. } if *id == v2)).await;
    assert_eq!(b.app_view("teen").unwrap().as_deref(), Some("newer"));
    assert!(b.inner.db(|s| s.get(v)).unwrap().is_none(), "the old view is gone");
    assert!(a.inner.db(|s| s.get(v)).unwrap().is_none());

    // A reply both ways makes the thread; views stay out of it.
    let mut ea = a.events();
    let reply = b.send_app("teen", "reply".into(), false).unwrap();
    wait_for(&mut ea, |e| matches!(e, Event::App { id: Some(id), .. } if *id == reply)).await;
    let h = a.app_history("teen", 10).unwrap();
    let h: Vec<_> = h.iter().map(|m| (m.text.as_deref().unwrap(), m.from_me)).collect();
    assert_eq!(h, vec![("post", true), ("reply", false)]);
    assert_eq!(a.app_channels().unwrap(), vec!["teen".to_string()]);

    assert!(b.send_app_live("teen", "progress".into()));
    let got = wait_for(&mut ea, app_ev).await;
    assert!(matches!(got, Event::App { id: None, ref data, view: false, .. } if data == "progress"));
}

#[tokio::test]
async fn a_local_app_item_is_pending_and_emitted() {
    let d = tempfile::tempdir().unwrap();
    let a = start(d.path(), "a").await;
    let mut ea = a.events();
    let id = a.app_local("teen", "tap".into()).unwrap();
    let got = wait_for(&mut ea, app_ev).await;
    assert!(matches!(got, Event::App { id: Some(i), from_me: false, view: false, .. } if i == id));
    assert_eq!(a.app_pending("teen").unwrap(), vec![(id, "tap".to_string())]);
    assert_eq!(a.status().unwrap().queued, 0, "nothing to send");
    assert_eq!(a.status().unwrap().unread, 0);
}
