# MORP Box — Architecture

## 1. Goals & constraints

- **Users:** reporters / organizers in degraded-infrastructure environments
  (protests, hurricanes, hostile shutdowns). They need to (a) talk locally over
  the mesh, (b) get news OUT to the world via Nostr, (c) coordinate, (d) get
  paid permissionlessly.
- **Devices:** Android + GrapheneOS (minSdk 26, Android 8.0+ per research Q1 —
  today's phones are tomorrow's backup hardware). APK 40–55 MB, RAM <250 MB. No Play Services.
- **Networks:** Bluetooth (BLE + classic where available), Wi-Fi/cellular
  internet, Reticulum (incl. LoRa/RNode-style links via LXMF/RNS), MeshCore
  LoRa relays via companion radio over BLE/serial.
- **Interop:** standard Nostr relays (kind 1/6/7…), NYM-style ephemeral mesh
  rooms (geohash kind 20000 / named kind 23333 + NIP-17 gift-wrap DMs/groups),
  Amber external signer, MeshCore companion firmware, Sideband/LXMF conventions.

## 2. Layer diagram

```
┌──────────────────────────────────────────────────────────────┐
│ UI (4 tabs: Feed · Chats · Mesh · Wallet)    Kotlin, offline-first │
├──────────────────────────────────────────────────────────────┤
│ App services: FeedRepo · ChatRepo · MeshRepo · WalletSvc · MediaSvc │
├──────────────────────────────────────────────────────────────┤
│ MORP core: NostrMgr · OnionRouter · FrameCodec · PortalRegistry    │
│            SignerIf (in-app Keystore | Amber) · PQ-Seal (NIP44+KEM)│
├──────────────────────────────────────────────────────────────┤
│ Transports: Internet-WS · BLE-Mesh · Reticulum/LXMF · MeshCore-BLE │
├──────────────────────────────────────────────────────────────┤
│ Storage: Room (events/msgs/wallet-meta) · FileStore (media chunks)│
└──────────────────────────────────────────────────────────────┘
```

Every layer degrades gracefully: if Internet-WS is up, use it; else route via
`OnionRouter` over whatever mesh transports have peers/portals.

## 3. Modules

### 3.1 NostrMgr
- Relays over websocket (plain okHttp, no heavy client lib to stay small).
- Kinds: `1` post/reply/quote (NIP-10 `e`/`p`, NIP-18 `q`), `6` repost,
  `0/3/10002` profile/contacts/relay-list (cached), `9734/9735` zap receipts,
  `1059/13/14` gift-wrap/seal (NIP-17/59), `20000/23333` ephemeral mesh rooms.
- Outbox model: every outbound event first hits the outbox table with state
  `QUEUED → SENT_DIRECT | SENT_MORP | ACKED | FAILED`, so airplane-mode posts
  just work later. Outbox entry stores the *signed bytes* (sign once, send many
  ways — critical for Amber offline signing).

### 3.2 SignerIf
```kotlin
interface SignerIf {
  suspend fun getPublicKey(): String            // x-only hex
  suspend fun signEvent(unsigned: NostrEvent): NostrEvent  // adds id+sig
  suspend fun nip44Encrypt(peer: String, pt: ByteArray): ByteArray
  suspend fun nip44Decrypt(peer: String, ct: ByteArray): ByteArray
}
```
Implementations: `KeystoreSigner` (secp256k1 in Android Keystore / EncryptedSharedPrefs
fallback on old devices) and `AmberSigner` (see AMBER_INTEGRATION.md). UI shows
"Signed with Amber (offline)" vs "App key" badge per account.

### 3.3 OnionRouter (MORP NERD)
Pure-Kotlin, transports-agnostic. API:
- `buildPacket(payload, path: List<HopKey>, portal: PortalDesc): ByteArray`
- `peel(packet, myKey): PeelResult // FORWARD(nextHop, inner) | EXIT(innerRequest)`
- Path selection: `PortalRegistry` ranks portals by (recency, hops, load,
  relay-support, battery?) and builds 3-hop paths (default; 2–5 configurable).
- Reply path: request carries an optional single-use reply onion for receipts.
  Receipts are gossiped by `request_id` with no sender identity.

Crypto per hop: ephemeral X25519 → HKDF → ChaCha20-Poly1305 (libsodium `crypto_box`
semantics). No source address anywhere in the packet. Fixed-size padding tiers
(512 / 2K / 8K) + jitter to blunt traffic analysis. See MORP_NERD_SPEC_v0.1.md.

### 3.4 FrameCodec (MORP-PKT v1 — see MORP_PKT_v1.md)
MTUs: BLE advertising ~180 B usable, BLE GATT ~200–500 B, Reticulum ~500 B,
MeshCore LoRa ~~200 B. FrameCodec chunks onion packets to the route's MTU:
`msg_id(16) | total u16 | idx u16 | flags u8 | crc16 | chunk`.
Reassembly cache with TTL + dedup; missing-chunk NACK via gossip for connected
transports, pure redundancy (2× interleave) for LoRa broadcast legs.

### 3.5 Transports
| Transport | Link | Role |
|---|---|---|
| `InternetWs` | Wi-Fi/cellular | direct relay WS; portal uplink; Blossom/NIP-96 upload |
| `BleMesh` | BLE adv+GATT, L2CAP CoC on 10+ | phone-to-phone flood + directed forward; portal beacons |
| `RnsLink` | Reticulum over TCP to local `rnsd`/Sideband, or in-proc `RNS` via Chaquopy longer-term | LXMF + `morp.nerd` destination; RNS announce carries portal beacons |
| `MeshCoreLink` | BLE/serial to T-Echo/T-Deck/etc companion radio | `advert` + `sendmsg`/custom TLV to LoRa mesh; reaches non-phone nodes |

`TransportManager` exposes one `send(route, bytes)` + peer/portal discovery
streams. Auto-switch: link monitor scores each transport; on change, drains
outbox over the new best route. Manual override per message ("force mesh").

### 3.6 Chat (NYM-compatible, PQ-hardened)
- 1:1 DMs: NIP-17 DM rooms (`kind 13` seal in `kind 1059` wrap) but the seal
  key is **hybrid**: `K = KDF(ECDH_x25519 || KEM_ml768 || transcript)`.
  Peers exchange ML-KEM public keys inside the first seal (or via kind-0 extension
  `pqkem` field). Pure NIP-17 clients can still read the inner NIP-44 layer if
  the sender enables compat mode — flagged in UI ("quantum-safe: strict/compat").
- Group chats: sender-keys (Signal-style) distributed via pairwise PQ-hybrid
  seals; group room kinds mirror NYM. Invite = sealed sender-key + room descriptor,
  deliverable over mesh or internet.
- Local mesh rooms: ephemeral geohash (`20000`) + named (`23333`) rooms flood over
  BLE/RNS/MeshCore with TTL, no internet needed — straight NYM interop.

### 3.7 WalletSvc
- **Lightning:** LDK (lightningdevkit) in-app node, neutrino/BDK chain source.
  Sends: BOLT11, keysend, LNURL-pay/LUD-16 from Nostr profiles (NIP-57 zaps).
  Offline: invoice/request payloads ride MORP to a portal (or a trusted
  always-online "wallet helper") that returns the preimage-proof; channel ops
  need internet and are queued with clear UX.
- **Monero:** `monero-java` wallet, subaddress per contact + per-invoice;
  **offline sign → portal broadcast**: unsigned tx built offline, sealed to portal,
  portal broadcasts and returns txid proof. View-only tracking for receipts.
- **Anonymous to-profile payments:** payer fetches recipient `lud16`/xmr address
  from kind-0 (or NIP-60 wallet hints); Lightning uses unannounced/private route
  + no sender TLV; XMR is private by default. Over mesh, the payment payload is
  onion-wrapped so relays can't link payer→payee.
- Wallet DB encrypted (SQLCipher), keys in Keystore. Testnet/regtest mode for dev.

### 3.8 MediaSvc
See MEDIA_POLICY.md. Pipeline: pick/capture → policy ladder compress →
manifest (sha256, chunks, mime, dims) → chunk → send inline (mesh, small) or
portal-upload (NIP-96/Blossom) with event carrying `imeta`/URL+hash (NIP-92/94).
SHA-256 verified at every reassembly. EXIF stripped by default (tactical!).

## 4. Data model (Room, sketch)

- `accounts(npub, signer_type, …)`
- `events(id, kind, author, created_at, raw_json, relay, via)` — kind-1 cache + outbox
- `messages(room_id, type, sender, ts, sealed_bytes, plain_cache?, state)`
- `rooms(room_id, kind, members_json, sender_key_state)`
- `mesh_peers(dev_key, transport, last_seen, rssi?, is_portal, portal_caps)`
- `outbox(id, payload_type, signed_bytes, route_policy, state, retries)`
- `wallet_meta(…)` + LDK/Monero stores
- `media_manifests(id, …)` + `media_chunks(id, idx, bytes)`

## 5. Background & battery

- Foreground `MeshService` only while "Mesh on" toggle is active; BLE duty-cycled
  (scan 4s / sleep 12s adaptive) + RNS/MeshCore event-driven.
- WorkManager for outbox drain, relay sync, wallet sync. Doze-friendly: batch.
- Old-device mode: disable animations, smaller feed pages, no auto-media-download.

## 6. What talks to what (flows)

- **Post kind-1 offline:** UI → SignerIf → outbox → OnionRouter → FrameCodec →
  best mesh transport → … → portal → InternetWs to user's relays → receipt gossip.
- **DM offline, recipient far:** gift-wrap+seal → same onion path → portal → recipient's
  relays (NIP-17 relay hints) → recipient client unwraps.
- **DM offline, recipient near:** gift-wrap → directed BLE/RNS forward or flood with
  `recipient_hash` topic; no portal needed.
- **Pay offline:** build invoice/tx → onion to portal/helper → proof back via reply
  onion/gossip → wallet marks settled.
- **Feed refresh offline:** serve cache + mesh-gossiped recent events; mark staleness.

## 7. Dependencies (Android, pinned in gradle/libs.versions.toml)

Kotlin, coroutines, Room, WorkManager, DataStore, okHttp+okio, Tink,
`bitcoin-kmp` (BIP-340/schnorr), BouncyCastle (ML-KEM), LDK + BDK, monero-java,
SQLCipher, Coil (images), ExoPlayer? (no — use platform MediaPlayer to stay small),
JUnit5+Robolectric+Turbine for tests. Zero Play/Firebase.

## 8. Research v0.2 alignment (M1)

- Packages `dev.morpbox.{rns,nostr,mesh,blossom,…}` inside `:app` for M1; M2 splits
  into `:core:morp-{rns,relay,blossom,mesh,onion,nostr,payments}` (research §5).
- Reticulum is reached via **`morp-rns`** (clean-room wire-compatible Kotlin, zero
  Reticulum/AGPL code — license quarantine, see RECONCILIATION §4).
- `morp-relay` (embedded NIP-01) + `morp-agent` (headless portal/mailbox/relay) +
  Blossom BUD-01/02/03/11 + P1/P2/P3 portal tiers + delivery machine are specified;
  `docs/` ≙ research `spec/` (CC0, final).
