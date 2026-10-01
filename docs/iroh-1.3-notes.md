# iroh 1.3.0 API notes (read from source, 2026-10-01)

QUIC library is **noq** (not quinn), re-exported at `iroh::endpoint::*`. Examples worth reading:
`~/.cargo/registry/src/*/iroh-1.3.0/examples/{echo-no-router,connect,listen,transfer}.rs`,
`iroh-mdns-address-lookup-0.6.0/examples/mdns_address_lookup.rs`.

- Endpoint: `Endpoint::builder(iroh::endpoint::presets::N0).secret_key(sk).alpns(vec![..])
  .bind_addr("0.0.0.0:47114")?.address_lookup(MdnsAddressLookup::builder()).transport_config(cfg).bind().await?`
  - N0 = pkarr/DNS lookup + n0 relays. `N0DisableRelay` exists. Needs default `tls-ring` feature.
  - Fixed v4 port replaces the default v4 socket; v6 keeps a random port. Busy port → bind error
    unless `bind_addr_with_opts(a, BindOpts::default().set_is_required(false))`.
- Keys: `SecretKey::generate()`, `.to_bytes() -> [u8;32]`, `SecretKey::from_bytes(&[u8;32])`, `.public()`.
  `EndpointId = PublicKey`; Display = hex, `FromStr` accepts hex/base32; `.as_bytes()`, `PublicKey::from_bytes(&[u8;32])?`.
- `ep.id()`, `ep.addr()`, `ep.online().await` (never returns without relay — don't await on LAN-only).
- Connect: `ep.connect(peer_id, ALPN).await?` — bare EndpointId is fine (lookup resolves it).
- Accept (no Router needed): `while let Some(inc) = ep.accept().await { let conn = inc.await?; conn.alpn(); conn.remote_id(); }`
  Only ALPNs registered in `.alpns()` are accepted. Router alternative: `Router::builder(ep).accept(ALPN, handler).spawn()`.
- Streams: `open_bi/accept_bi/open_uni/accept_uni`; SendStream/RecvStream implement tokio AsyncWrite/AsyncRead.
  `send.finish()` is NOT async. A bi stream is invisible to the peer until written to.
- `conn.closed().await -> ConnectionError`; `conn.close(0u32.into(), b"reason")` (not async).
  Peer graceful close = `ConnectionError::ApplicationClosed(_)`.
- Direct vs relayed: `conn.paths().iter().any(|p| p.is_selected() && p.is_ip())`; changes via `conn.path_events()`
  (`PathEvent::Selected{..}`), stream from n0-future/futures StreamExt.
- Shutdown: `ep.close().await` (always, before drop).
- Transport: `iroh::endpoint::QuicTransportConfig::builder()...build()`. Defaults: keep-alive/heartbeat **5 s**,
  path idle 15 s (clamped max), relay path idle 30 s. Hence on-demand connections (see CLAUDE.md).
- Errors are all `std::error::Error + Send + Sync`, fine with anyhow `?`.
