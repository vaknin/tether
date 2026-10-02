//! `tether`: the laptop daemon and the CLI that talks to it.

mod daemon;
mod desktop;
mod fcm;
mod ipc;
mod mpris;
mod pick;

use std::{path::PathBuf, process::ExitCode, time::Duration};

use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use serde_json::Value;
use tether_core::node::{Config, Net, Node, PORT};
use tracing::{info, warn};
use tracing_subscriber::EnvFilter;

use crate::ipc::{Request, call};

#[derive(Parser)]
#[command(version, about = "Laptop ↔ phone: chat, files, pings, media and notifications")]
struct Cli {
    /// Daemon socket (default $TETHER_SOCKET or $XDG_RUNTIME_DIR/tether.sock).
    #[arg(long, global = true)]
    socket: Option<PathBuf>,
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Run the daemon (normally from the systemd user unit).
    Daemon {
        /// Database and keys (default $XDG_STATE_HOME/tether).
        #[arg(long)]
        state_dir: Option<PathBuf>,
        /// Where received files go (default ~/Downloads).
        #[arg(long)]
        download_dir: Option<PathBuf>,
        /// The name the phone shows for this device.
        #[arg(long, default_value = "Laptop")]
        name: String,
        /// UDP port; 0 picks a free one.
        #[arg(long, default_value_t = PORT)]
        port: u16,
        /// No toasts or clipboard (for a second test instance).
        #[arg(long)]
        no_desktop: bool,
        /// Firebase service-account JSON for waking the phone (default
        /// $XDG_CONFIG_HOME/tether/fcm-service-account.json). Without it the daemon only redials.
        #[arg(long, env = "TETHER_FCM_KEY")]
        fcm_key: Option<PathBuf>,
    },
    /// Show a pairing QR code and wait for the phone; or, given a code, pair with that device.
    Pair { code: Option<String> },
    /// Forget the paired device.
    Unpair,
    /// Pairing and connection state.
    Status {
        #[arg(long)]
        json: bool,
    },
    /// Send files (queued until the phone is reachable).
    Send {
        #[arg(required_unless_present_any = ["pick", "clipboard"])]
        files: Vec<PathBuf>,
        /// Choose the files in the desktop's file chooser.
        #[arg(long, conflicts_with = "clipboard")]
        pick: bool,
        /// Send the image on the clipboard.
        #[arg(long)]
        clipboard: bool,
    },
    /// Send a chat message.
    Msg {
        #[arg(required = true)]
        text: Vec<String>,
    },
    /// Send a one-line alert that shows as a notification on the phone.
    Ping {
        #[arg(required = true)]
        text: Vec<String>,
    },
    /// Ring the phone (dropped if it can't be reached within 2 minutes).
    Ring {
        /// Stop a ring that is going on.
        #[arg(long)]
        stop: bool,
    },
    /// The phone's active notifications, as last mirrored while it was connected.
    Notifications {
        #[arg(long)]
        json: bool,
        /// Wake the phone if it isn't connected and wait for its current list (up to 25 s); the
        /// link then stays open for 2 minutes, so repeated calls see new notifications live.
        #[arg(long)]
        fresh: bool,
    },
    /// Chat snapshot for the shell UI: status, media and recent messages.
    Json {
        #[arg(long, default_value_t = 200)]
        limit: usize,
    },
    /// Mark all received messages as read.
    Read,
    /// Dial the phone now.
    Connect,
    /// Stream events as JSON lines.
    Watch,
}

#[tokio::main]
async fn main() -> ExitCode {
    let cli = Cli::parse();
    let sock = cli.socket.clone().unwrap_or_else(ipc::default_socket);
    match run(cli.cmd, sock).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("tether: {e:#}");
            ExitCode::FAILURE
        }
    }
}

async fn run(cmd: Cmd, sock: PathBuf) -> Result<()> {
    match cmd {
        Cmd::Daemon { state_dir, download_dir, name, port, no_desktop, fcm_key } => {
            tracing_subscriber::fmt()
                .with_env_filter(
                    EnvFilter::try_from_default_env()
                        .unwrap_or_else(|_| EnvFilter::new("info,iroh=warn,noq=warn,noq_proto=warn")),
                )
                .init();
            let state_dir = state_dir.map_or_else(default_state_dir, Ok)?;
            let download_dir = download_dir.map_or_else(|| Ok(home()?.join("Downloads")), Ok::<_, anyhow::Error>)?;
            let mut cfg = Config::new(state_dir, download_dir, name);
            cfg.net = Net::Internet { port: Some(port) };
            let node = Node::start(cfg).await?;
            if !no_desktop {
                tokio::spawn(desktop::run(node.clone()));
                tokio::spawn(mpris::run(node.clone()));
            }
            let fcm_key = fcm_key.map_or_else(default_fcm_key, Ok)?;
            let wake = match fcm::Fcm::load(&fcm_key) {
                Ok(f) => {
                    info!("FCM wake on ({})", fcm_key.display());
                    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
                    tokio::spawn(fcm::run(node.clone(), f, node.events(), rx, fcm::WAKE_GAP));
                    Some(tx)
                }
                Err(e) => {
                    warn!("FCM wake off, relying on redial: {e:#}");
                    None
                }
            };
            let res = tokio::select! {
                r = daemon::serve(daemon::Ctx::new(node.clone(), wake), &sock) => r,
                _ = shutdown_signal() => Ok(()),
            };
            node.shutdown().await;
            let _ = std::fs::remove_file(&sock);
            res
        }
        Cmd::Pair { code: Some(code) } => {
            let peer = call(&sock, &Request::Pair { code }).await?;
            println!("Paired with {}", peer["name"].as_str().unwrap_or("?"));
            Ok(())
        }
        Cmd::Pair { code: None } => pair_interactive(&sock).await,
        Cmd::Unpair => {
            call(&sock, &Request::Unpair).await?;
            println!("Unpaired");
            Ok(())
        }
        Cmd::Status { json } => {
            let s = call(&sock, &Request::Status).await?;
            if json {
                println!("{s}");
            } else {
                print_status(&s);
            }
            Ok(())
        }
        Cmd::Send { files, pick, clipboard } => {
            let mut paths = files.iter().map(std::path::absolute).collect::<std::io::Result<Vec<_>>>()?;
            if pick {
                paths.extend(pick::choose_files().await?);
            }
            if clipboard {
                paths.push(pick::clipboard_image(&default_state_dir()?.join("pasted")).await?);
            }
            if paths.is_empty() {
                return Ok(());
            }
            let sent = call(&sock, &Request::Send { paths }).await?;
            for m in sent.as_array().into_iter().flatten() {
                println!("{} {}", m["state"].as_str().unwrap_or("?"), m["file_name"].as_str().unwrap_or("?"));
            }
            Ok(())
        }
        Cmd::Msg { text } => print_state(call(&sock, &Request::Msg { text: text.join(" ") }).await?),
        Cmd::Ping { text } => print_state(call(&sock, &Request::Ping { text: text.join(" ") }).await?),
        Cmd::Ring { stop: false } => print_state(call(&sock, &Request::Ring).await?),
        Cmd::Ring { stop: true } => call(&sock, &Request::StopRing).await.map(drop),
        Cmd::Notifications { json, fresh } => {
            let list = call(&sock, &Request::Notifications { fresh }).await?;
            if json {
                println!("{list}");
            } else {
                for n in list.as_array().into_iter().flatten() {
                    println!("[{}] {}: {}", s(&n["app"]), s(&n["title"]), s(&n["text"]));
                }
            }
            Ok(())
        }
        Cmd::Json { limit } => {
            println!("{}", call(&sock, &Request::Json { limit }).await?);
            Ok(())
        }
        Cmd::Read => call(&sock, &Request::MarkRead).await.map(drop),
        Cmd::Connect => call(&sock, &Request::Connect).await.map(drop),
        Cmd::Watch => {
            let (mut lines, _w) = ipc::watch(&sock).await?;
            while let Some(line) = lines.next_line().await? {
                println!("{line}");
            }
            Ok(())
        }
    }
}

async fn pair_interactive(sock: &std::path::Path) -> Result<()> {
    let (mut events, _w) = ipc::watch(sock).await?;
    let offer = call(sock, &Request::PairOffer).await?;
    let code = offer["code"].as_str().context("no pairing code")?;
    let qr = std::process::Command::new("qrencode")
        .args(["-t", "ANSIUTF8", "-m", "2", code])
        .status();
    if !qr.is_ok_and(|s| s.success()) {
        eprintln!("(install qrencode to see a QR code)");
    }
    println!("Scan with Tether on the phone, or run `tether pair {code}` on the other device.");
    println!("Valid for 5 minutes, once.");
    let wait = async {
        while let Some(line) = events.next_line().await? {
            let e: Value = serde_json::from_str(&line)?;
            if e["event"] == "paired" {
                return Ok(e["peer"]["name"].as_str().unwrap_or("?").to_string());
            }
        }
        anyhow::bail!("daemon went away")
    };
    let name = tokio::time::timeout(Duration::from_secs(300), wait)
        .await
        .context("pairing code expired")??;
    println!("Paired with {name}");
    Ok(())
}

fn print_status(st: &Value) {
    println!("This device  {} ({})", s(&st["name"]), s(&st["id"]));
    match st["peer"].as_object() {
        Some(p) => {
            let link = if st["connected"] == true { "connected" } else { "not connected" };
            println!("Paired with  {} ({}), {link}", s(&p["name"]), s(&p["id"]));
        }
        None => println!("Not paired (run `tether pair`)"),
    }
    println!("Queued {}, unread {}", st["queued"], st["unread"]);
}

fn print_state(m: Value) -> Result<()> {
    println!("{}", s(&m["state"]));
    Ok(())
}

fn s(v: &Value) -> &str {
    v.as_str().unwrap_or("")
}

fn home() -> Result<PathBuf> {
    std::env::var_os("HOME").map(PathBuf::from).context("HOME is not set")
}

fn default_fcm_key() -> Result<PathBuf> {
    let config = match std::env::var_os("XDG_CONFIG_HOME") {
        Some(d) => PathBuf::from(d),
        None => home()?.join(".config"),
    };
    Ok(config.join("tether/fcm-service-account.json"))
}

fn default_state_dir() -> Result<PathBuf> {
    Ok(match std::env::var_os("XDG_STATE_HOME") {
        Some(d) => PathBuf::from(d).join("tether"),
        None => home()?.join(".local/state/tether"),
    })
}

async fn shutdown_signal() {
    use tokio::signal::unix::{SignalKind, signal};
    let mut term = signal(SignalKind::terminate()).expect("SIGTERM handler");
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {}
        _ = term.recv() => {}
    }
}
