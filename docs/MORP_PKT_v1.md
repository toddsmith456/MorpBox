# MORP-PKT v1 — binary frame spec (authoritative)

Source: research §6.1 (approved). Implemented by `protocol/morp/frame.py` and
`android/.../core/Frame.kt` (byte-identical; cross-checked via `vectors/v0.1.json`).

## Header (90 bytes, big-endian)

| Field | Size | Notes |
|---|---|---|
| magic | 4 | `0x4D4F5250` `"MORP"` |
| ver | 1 | `0x01` |
| ftype | 1 | `01 ADV · 02 PRQ · 03 PRA · 04 DATA · 05 ACK · 06 CTRL` |
| next_hop | 32 | recipient `dev_pub`; all-zero = flood/broadcast |
| path_id | 8 | `sha256(payload)[0:8]` — correlation without identity |
| ttl | 1 | default 8; decrement per forward; drop at 0 |
| flags | 1 | `CHUNKED 0x01 · ACK_REQ 0x02 · PRIORITY 0x04 · PORTAL_BOUND 0x08 · FIRST 0x10 · LAST 0x20` |
| msg_id | 32 | `sha256(full reassembled payload)` — dedup + integrity |
| seq / total | 2+2 | chunk index / chunk count |
| chunk_len / pkt_len | 2+2 | this chunk / this frame lengths |
| crc16 | 2 | CCITT over header-after-magic + chunk (corruption detection) |

No source field anywhere (M2 relay blindness). Payload ≤ MTU−90 per frame
(90 B on 180-B BLE/MeshCore legs; 390 B on 480-B RNS legs).

## Payloads by ftype

- **ADV**: JSON portal/peer beacon (spec §2 + `caps_mask`: P1=1 P2=2 P3=4 MAILBOX=8 RELAY=16).
- **PRQ/PRA**: path-request / path-response (path discovery, M2).
- **DATA**: one onion layer blob (spec §3), or flood plaintext for subscribed
  ephemeral rooms, or `BLOSSOM_CHUNK` bytes (chunk 0 = manifest).
- **ACK**: `ACK:` + msg_id (unforgeable delivery ACKs; morp-rns links seal these).
- **CTRL**: NACK (missing seq list), mailbox-fetch, receipt gossip, reconcile digests.

## Portal tiers

- **P1** nostr uplink (publish kind-1/DM-wraps/group-wraps to client-chosen relays).
- **P2** media portal (reassemble Blossom blobs, BUD-02 PUT with BUD-11 auth, return URL+hash).
- **P3** payment portal (broadcast helper: LN invoice forwarding, XMR tx broadcast,
  return proofs; never holds payer keys — DeferredPayQueue executes payer-side).

## Reassembly

TTL 10 min, LRU 256 msgs, dupes tolerated, out-of-order OK, `msg_id` hash-verified
on completion. Missing-chunk NACK via CTRL on connected legs; 2× interleave
redundancy flag for LoRa broadcast legs (M4).
