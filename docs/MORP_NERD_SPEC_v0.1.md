# MORP NERD v0.1 — Wire Specification (onion/crypto core)

> **v0.2 framing update (approved):** §4 fragmentation is SUPERSEDED by
> `docs/MORP_PKT_v1.md` (MORP-PKT v1 frames: magic `MORP`, ftype, 32-B next_hop,
> ttl/flags, 32-B msg_id, chunking) and portal tiers P1/P2/P3. The onion crypto
> (§1–§3), receipt model (§3.4–§3.5) and budgets (§7) below remain authoritative.

**M**esh **O**nion **R**elay **P**rotocol for **N**ostr **E**ndpoint **R**esilient **D**issemination

Status: `DRAFT v0.1` — implemented by `protocol/morp/` (Python reference) and
mirrored in `android/.../core/MorpNerd.kt`. Interop target: any client that can
speak BLE/Reticulum/MeshCore + Nostr.

## 0. Notation & crypto

- `X25519`, `ChaCha20-Poly1305`, `HKDF-SHA256`, `SHA-256`, `secp256k1/BIP-340`.
- All multi-byte integers big-endian. All keys raw bytes unless `npub/nsec` noted.
- `BOX(enc_to_pub, pt, aad)`: ephemeral X25519 → `HKDF(ecdh||"morp-nerd-hop-v1")`
  → ChaCha20-Poly1305 with random 12-B nonce. Wire: `eph_pub(32) || nonce(12) || ct`.
- `SEAL_STR`: domain separation strings are ASCII, e.g. `b"morp-nerd-hop-v1"`.

## 1. Identity & binding

Each device holds:
1. **Nostr identity**: secp256k1 keypair, `npub`/`nsec` bech32. May live in Amber —
   MORP Box never needs the `nsec`, only signatures.
2. **Transport identity**: X25519 keypair (`dev_pub`, 32 B). Long-lived per install;
   rotatable. This is what onion layers are addressed to.

**Binding attestation** (proves one owner holds both, without doxxing to relays):
```
attest = { "v":1, "npub":..., "dev_pub":hex, "ts":unix, "caps":{portal?:bool,…} }
sig    = BIP340_SIGN(nostr_sk, SHA256(canonical_json(attest)))
```
- Attestation is shown ONLY to chosen counterparties (portal during registration,
  DM peer during handshake) over an encrypted channel — never in relay headers.
- Relays route on `dev_pub`-derived **hop IDs** (`H = SHA256("hop"||dev_pub)[0:8]`),
  which are unlinkable to `npub` without the attestation.

## 2. Portal discovery (beacons)

A portal = any MORP device currently able and willing to uplink to the internet
(Nostr relays / Blossom / wallet broadcast). Portals emit beacons on all mesh
transports every `T_b = 60 s` (jittered ±20 s), also answering `PROBE`:

```
BEACON = { "v":1, "type":"morp-portal", "hop":H_portal, "dev_pub":hex,
           "ts":ts, "ttl":180, "load":0..255, "relays":[wss…(max 8)],
           "caps":["nostr","dm","media-up","ln","xmr-bcast"],
           "sig": EdDH-self-sign over above (X25519-derived Ed? see note) }
```

> v0.1 note: beacons are signed with the Nostr key (BIP-340) to avoid a second
> signature scheme; `dev_pub` ownership is proven by the attestation the portal
> serves on request. v0.2 may move to Ed25519 transport signatures.

Clients keep a `PortalRegistry`: `hop → (dev_pub, transports_seen, load, relays, caps, last_seen)`.
Portal selection prefers: freshest → lowest load → most matching caps → fewest hops.

Non-portal relays ALSO gossip `PEER` announcements (`hop`, `dev_pub`, transports)
so clients can build multi-hop paths even to a portal they can't hear directly.

## 3. Onion packet

### 3.1 Path
Default 3 hops: `relay_A → relay_B → portal`. Configurable 2–5. Client SHOULD pad
all packets to tier sizes `512 / 2048 / 8192` bytes (post-fragmentation total).

### 3.2 Inner request (portal plaintext, relay-opaque)
```
InnerRequest = JSON {
  "v":1, "req_id": hex16, "ts":ts,
  "op": "nostr-publish" | "dm-deliver" | "group-deliver" | "media-upload" |
        "ln-send" | "xmr-bcast" | "mesh-deliver",
  "relays":[wss…],            // for nostr/dm ops (client's choice)
  "payload": base64(...),     // signed event / gift-wrap / manifest / pay req
  "reply":  ReplyBlock?       // optional single-use reply onion
}
```
For `mesh-deliver` (recipient is on-mesh, no internet needed), `relays` is empty
and `payload.recipient_hash` routes the final flood/directed leg.

### 3.3 Layering
```
inner = PAD(serialize(InnerRequest))
L3 = BOX(portal_dev_pub,  inner || NEXT_NONE)
L2 = BOX(relayB_dev_pub,  L3    || NEXT(H_portal))
L1 = BOX(relayA_dev_pub,  L2    || NEXT(H_relayB))
PACKET = ver(1)=0x01 || path_len(1) || L1
```
Each relay: parse outer BOX with its `dev_priv`, recover `(inner_blob, next_hop)`,
re-wrap NOTHING (already the next layer), forward `ver||remaining||inner_blob` to
`next_hop` over any transport that reaches it. **Relay learns: previous-link peer
(BLE/RNS link layer, unavoidable), next_hop ID, timing/size tier. It does NOT learn:
origin identity (no source field), npub, payload, total path length beyond remaining,
or whether previous peer is origin or another relay.**

### 3.4 Portal processing
1. Peel final layer → `InnerRequest`.
2. Verify `ts` freshness (±24 h, mesh clocks skew!), `req_id` unseen (replay cache 48 h).
3. Op-specific:
   - `nostr-publish`: parse event, verify BIP-340 `id`+`sig`, POST to each relay in
     `relays` (client-chosen; portal MUST NOT substitute). Collect `OK` receipts.
   - `dm-deliver`/`group-deliver`: verify outer gift-wrap parses (do NOT decrypt seal —
     portal can't), publish to recipient relay hints.
   - `media-upload`: upload bytes to NIP-96/Blossom, return URL+hash receipt.
   - `ln-send`/`xmr-bcast`: forward to wallet helper / broadcast, return proof.
4. Emit `RECEIPT{req_id, ok, details}` back via `ReplyBlock` if present, else gossip
   `RECEIPT` on mesh topics (any client watching `req_id` picks it up; receipts carry
   no identity).

### 3.5 Reply blocks (v0.1, single-use)
`ReplyBlock = { first_hop: H, onion: BOX(first_hop, BOX(second?, receipt_fwd...)) }`
1–2 hops. Portal wraps receipt in it and injects to `first_hop`. OPTIONAL — gossip
fallback always exists.

## 4. Fragmentation (all mesh transports)

```
FRAG = magic(2)=0xM0RP? -> 0x4D42 ("MB") || ver(1)=0x01
     || msg_id(16) || total u16 || idx u16 || flags u8 || crc16
     || chunk (≤ MTU-28)
flags: 0x01 FIRST | 0x02 LAST | 0x04 REDUNDANT | 0x08 NACK_REQ
crc16: CCITT over header-after-magic + chunk
```
- MTU default 180 (fits BLE adv + MeshCore); RNS legs may negotiate 480.
- Reassembly TTL 10 min, LRU 256 msgs. `NACK_REQ` asks neighbors for missing idx.
- `msg_id = SHA256(onion_packet)[0:16]` — dedup without identity.

## 5. Store-and-forward

Any node MAY cache `(msg_id → frags)` for offline next-hops, TTL 24 h default,
priority: receipts > DMs > kind-1 > media chunks. Cache serves any peer asking by
`msg_id` — **serving node learns nothing new** (frags are onion ciphertext).

## 6. Rate limits & abuse

- Per-link fair queueing; relays SHOULD cap `8 KB/min/link` for non-priority traffic.
- Portals SHOULD publish per-`req_id` (dedup) and rate-limit identical event `id`s.
- Relays MUST NOT modify packets (AEAD fails downstream; portal drops + receipt `ok:false`).
- Eclipse/Sybil: clients SHOULD use ≥2 distinct portals for important posts over time.

## 7. Throughput budget (design targets)

| Payload | Typical size | Route | Feasibility |
|---|---|---|---|
| kind-1 text (+tags) | 0.3–2 KB | 3-hop BLE/RNS | ✅ seconds–minutes |
| kind-1 + 100 KB photo manifest | ~1 KB event + portal upload | onion carries event; media inline-chunked or portal-fetched | ✅ |
| DM seal+wrap | 0.5–3 KB | same | ✅ |
| 60 s Opus voice | ~90 KB | chunked, redundant | ⚠️ minutes, LoRa slow leg ok w/ patience |
| 15 s 480p video | ~500 KB | chunked | ⚠️ BLE/RNS ok; pure-LoRa only as last resort w/ heavy compress |
| invoices / xmr-bcast | 1–5 KB | onion | ✅ |

Throughput requirement (§MORP-NERD-3) is met by: small-MTU fragmentation,
store-and-forward, portal upload offload (heavy bytes go portal→cloud, not over LoRa twice),
and the media ladder (MEDIA_POLICY.md).

## 8. Worked example (also `protocol/morp/demo.py`)

Alice (offline) → relays R1,R2 → portal P (online) → `wss://relay.damus.io`:
1. Alice signs kind-1 with Amber (offline QR/intent) → `EVT`.
2. Alice picks path R1→R2→P from registry, builds `PACKET` per §3.3, pads to 2 KB, frags to 180 B.
3. R1,R2 peel+forward (§3.3). Neither sees npub nor EVT.
4. P reassembles, peels, verifies `EVT.sig`, publishes to Damus + Alice's other relays, gossips RECEIPT.
5. Alice (or anyone watching `req_id`) sees receipt; feed marks post ACKED.

## 9. Open issues (v0.2)

- Cover traffic + batching to resist timing correlation by a global observer.
- Congestion control + FEC (XOR parity shards) for LoRa legs.
- Portal reputation / web-of-trust + pay-the-portal (microscopic Lightning fee).
- Post-quantum transport handshake (ML-KEM in the per-hop BOX).
