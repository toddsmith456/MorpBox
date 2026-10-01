# Reconciliation: M0 build ↔ approved research v0.2 → M1 plan

Research: `docs/research/MORP_BOX_design_roadmap_v0.2.md` (approved 2026-09-20).
This doc maps every owner-locked decision onto the repo and records the two
open conflicts. Read this before M1 work.

## 1. Adopted as-is (repo updated this turn)

| # | Research decision | Repo change |
|---|---|---|
| minSdk 26 | Android 8.0+, Amber NIP-55, Keystore wrap | `app/build.gradle.kts`, manifest perms, `MorpApp`, README/ARCHITECTURE |
| Portal tiers P1/P2/P3 | P1=nostr uplink, P2=media/Blossom, P3=pay/wallet-bcast; +mailbox/+relay; caps bitmask | `transport.py` caps, `PortalRegistry`, `docs/MORP_PKT_v1.md` |
| MORP-PKT v1 frame | magic `MORP`, ftype ADV/PRQ/PRA/DATA/ACK/CTRL, next_hop(32), path_id(8), ttl=8, flags, msg_id(32), seq/total/chunk/pkt_len + crc16 ext | NEW `protocol/morp/frame.py` + `Frame.kt`; `fragment.py`/`Fragmenter.kt` deleted; `transport.py`/tests/vectors migrated |
| MORP kinds | 10080 identity bundle, 10081 payment event, 10082 media descriptor, 10083 delivery status, 10063 Blossom servers, 10133 NIP-A3 XMR payto, 24242 BUD-11 auth | `MorpKinds.kt`, `protocol/morp/blossom.py`, NIP-17 kind 14/15 rumors |
| NIP-17 v2 chat | kinds 14/15 rumors in 13-seal in 1059-wrap; group cap 12; `morpgrp1…` handle | `nostr.py` rumor builders, `Bech32.kt`, `GroupHandle` |
| `morp-rns` clean-room | Kotlin wire-compatible Reticulum (X25519/Ed25519 identity, token ratchet, announce, ACKs); ZERO Reticulum/Leviculum code (license quarantine) | `rns/RnsIdentity.kt` + `protocol/morp/rns.py` spike + tests |
| `morp-relay` | embedded NIP-01 relay in app + agent (island cache, merge reconcile) | `nostr/EmbeddedRelay.kt` (in-process; localhost-WS in M2) |
| Blossom BUD-01/02/03/11 | hash-addressed media, PUT upload, kind 10063 server lists, kind 24242 auth; P2 mesh media portals | `blossom/BlossomClient.kt` + `protocol/morp/blossom.py` + MEDIA_POLICY update |
| `morp-agent` | headless daemon for Pi/Linux (mesh node, P1/P2/P3 portal, mailbox, morp-relay) | `agent/README.md` contract (impl M4) |
| Delivery states | queued→on-mesh→published→delivered→confirmed (+failed) | `delivery.py`, `PublishPipeline.kt`, kind 10083 receipts |
| LDK Node JVM | `org.lightningdevkit:ldk-node-jvm` | `libs.versions.toml` comment → pinned coord (dep add M5) |
| XMR light pattern | Cake Wallet `cw_monero` view-key scan + Monerujo, remote node over Tor; `kind:10133` payto | `DeferredPay.kt`, wallet docs |
| DeferredPayQueue | signed non-revocable instruction, nonce + 72 h expiry, payer executes at internet | `wallet/DeferredPay.kt` + `protocol/morp/payments.py` |
| Offline LN invoices | MORP direct-invoice mode; self-published 9734/9735 | PAYMENT frame types (M5 execute) |
| UI: 8 tabs + alpha banner | Feed·Channels·Chats·Mesh·Wallet·Media·Profile·Secure; banner “Alpha Release — Protocol in Testing. Use Burner Profiles for Testing.” | `MainActivity.kt` |
| Emergency wipe | triple-tap + 3 s hold → SQLCipher rekey-random, zero keystores | `SecureWipe.kt` + Secure pane |
| 4–7 node tuning | ADV jitter 5–20 s, TTL 8, flood+dedup; field rig 4 phones + 2 LoRa + 1 agent | `BleMeshTransport.kt`, `tools/README.md` sim plan |
| Module layout | `core/morp-{rns,relay,blossom,mesh,onion,nostr,payments}`, `spec/`, `tools/`, `agent/` | packages `dev.morpbox.{rns,relay,blossom,mesh,onion,nostr,payments}` in `:app` for M1; Gradle split M2. `docs/` ≙ `spec/`. |
| Bitchat BLE reference | Unlicense; clean-room flood/dedup/wipe in Kotlin | `BleMeshTransport.kt` header note |
| LoRa hardware baseline | Heltec V3/T114, RAK 4631, Seeed MeshTracker X1; BLE NUS + USB CDC-ACM | `adapters/meshcore/README.md` update |

## 2. Conflict A — LICENSE (RESOLVED 2026-09-20: MIT + CC0)

- Research (§9.1, locked): **Pure MIT code + CC0 specs**, enabled by `morp-rns`
  clean-room (avoids Reticulum Apr-2025 “no-harm” clause ambiguity + Leviculum/NYM AGPL quarantine).
- Owner's final ruling (post-M0-vote review): **MIT + CC0**. Rationale: S2 intent
  (“most open possible; no credit retention; maximize adoption/replication”) wins;
  copyleft would restrict newsroom/radio-vendor integrators.
- Applied: `LICENSE` (MIT + CC0), README, ARCHITECTURE §8, ROADMAP M1.

## 3. Conflict B — wire frame (resolved: adopt v0.2)

M0 shipped a v0.1 frame (`MB` magic, 16-B msg-id). Research §6.1 locks MORP-PKT v1
(`MORP` magic, ftype, 32-B next_hop, ttl/flags, 32-B msg-id, chunking). Resolution:
**v0.2 frame is authoritative**; M0 framing removed (git history keeps it), onion
crypto unchanged (layers now ride inside `DATA` frames). `SimulatedMesh` proves the
v1 frame on every leg. See `docs/MORP_PKT_v1.md`.

## 4. Quarantine rules (hard)

1. No Reticulum Python/C code, no Leviculum Rust, no NYM/Nymchat code in this repo — EVER.
   Behavioral/wire references only. `morp-rns` is written from functional descriptions.
2. AGPL deps (Ditto, NYM) are reference-only; never linked.
3. Crypto from audited libs only (Tink, BouncyCastle, bitcoin-kmp, liboqs-via-BC).

## 5. M1 exit criteria (this milestone)

- [ ] Room/SQLCipher schema + DAOs; outbox + delivery machine; OutboxWorker
- [ ] RelayPool (NIP-01 WS) + RelayManager (NIP-65) + EmbeddedRelay
- [ ] BleMeshTransport (ADV/scan/GATT flood+dedup, beacons, duty-cycle)
- [ ] morp-rns spike passes packet self-test (KT + PY mirror)
- [ ] Blossom BUD-01/02/03/11 client (KT + PY) against URL/auth vectors
- [ ] UI: 8 tabs, alpha banner, Secure wipe stub; minSdk 26 green
- [ ] Python reference: frame/rns/blossom/delivery/payments/chat tests green
