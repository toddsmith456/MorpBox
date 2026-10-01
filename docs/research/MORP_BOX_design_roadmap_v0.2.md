# MORP Box — Research Findings, Design Requirements & Implementation Roadmap

**Version:** 0.2 (Incorporating Owner Intent Review & Protocol Scope Finalization)  
**Date:** 2026-09-20  
**Status:** Approved Architecture & Final Requirements Roadmap for Hand-off to Implementation Agents  
**Scope:** Android + GrapheneOS app ("MORP Box") bridging Nostr (kind-1 feed, NIP-17 DMs/groups, Blossom media), a Bluetooth phone mesh, MeshCore LoRa relays, embedded Nostr relay (`morp-relay`), and MORP NERD (Mesh Onion Relay Protocol for NoSTR Endpoint Resilient Dissemination) non-internet routes, with embedded Bitcoin Lightning + XMR wallets (LDK Node + Cake Wallet / Monerujo light pattern) and media support in all modes.

---

## 0. Executive Summary

**Verdict: MORP Box is buildable and fully feasible. Every component has a proven, open-source prior art, mature library, or clean specification.** Nothing in the intent set requires unproven cryptography or theoretical networking research. The work is clean-room protocol implementation, systems integration, and mobile engineering.

Following the owner review on 2026-09-20, eight critical discoveries and decisions have been locked into the design:

1. **Pure Open Licensing via Clean-Room Protocol Implementation (`morp-rns`):** Official Python Reticulum (`markqvist/Reticulum`) introduced a non-OSI "no intentional harm" license clause in April 2025, and trusted Rust implementations like `Leviculum` (`Lew_Palm/leviculum`) are AGPL-3.0. To protect journalists, organizers, and protesters from legal ambiguity or copyleft contamination, MORP Box implements **`morp-rns`** — a **100% clean-room Kotlin wire-compatible implementation** of the public Reticulum protocol specification (512-bit EC identity, X25519/Ed25519, token ratchets, headers, and unforgeable ACKs). This releases the entire MORP Box codebase under a **100% Pure MIT License** and all specifications under **CC0 (Public Domain)**, achieving the owner's goal of maximum open adoption and replication with zero license restrictions.
2. **Post-Quantum Standard (Luxas / Nymchat ML-KEM-768 Alignment):** As attested by developer Luxas on Nostr (`npub16jdfqgazrkapk0yrqm9rdxlnys7ck39c7zmdzxtxqlmmpxg04r0sd733sv`) for Nymchat (`https://web.nymchat.app`), MORP Box adopts **ML-KEM-768** (FIPS 203) hybrid post-quantum key exchange alongside secp256k1 NIP-44 ECDH. In-transit mesh traffic is protected by ratcheted forward secrecy (defeating harvest-now/decrypt-later), while stored Nostr payloads use the optional ML-KEM-768 MORP-PQ wrapper.
3. **Blossom Server Protocol Integration for Media:** Nostr media (photos, audio, short video) adheres to the **Blossom Protocol specifications** (BUD-01 through BUD-12, BUD-11 kind 24242 Nostr authentication, BUD-03 kind 10063 server lists). Online modes talk directly to HTTP Blossom servers (retrieving blobs by SHA-256 hash). Offline mesh modes run Blossom-compatible P2 media portals, chunking SHA-256 media blobs across MORP mesh nodes.
4. **XMR & Lightning Wallet Architecture (Cake Wallet + Monerujo + Ditto / NIP-A3 Patterns):** Lightning uses embedded **LDK Node** (Apache-2.0/MIT). Monero (XMR) uses the **light-wallet pattern** referencing both **Cake Wallet** (`cake-tech/cake_wallet`, MIT; `cw_monero` view-key local scanning, subaddresses, custom remote node over Tor) and **Monerujo** (Apache-2.0). XMR payment targets on profiles use `kind:10133` (NIP-A3 / Nosmero / Workstr standard: `["payto", "monero", "<subaddress>"]`) and profile metadata, aligning with Soapbox Pub's **Ditto** (`https://gitlab.com/soapbox-pub/ditto`) and **Amethyst** payment target models. Payments over mesh are queued as signed, non-revocable instructions executed when the payer's device reaches an internet path.
5. **Clarification on Non-Mobile Mesh Endpoints (`morp-agent`):** Phone-to-non-mobile-device communication over LoRa/mesh functions natively via the MORP wire protocol without custom mobile code. Non-mobile devices (Linux PCs, Raspberry Pis, base station radios) run **`morp-agent`** — a lightweight, headless companion daemon (Rust/C/Go/Kotlin) that speaks the MORP packet specification, serving as an always-on mesh node, portal (P1/P2/P3), mailbox, and embedded Nostr relay (`morp-relay`).
6. **Resilient Field Scale (4–7 Node Target Mesh):** The primary target scale and sweet-spot operating environment for MORP Box is a **4 to 7 device mesh** (e.g., 4 Android devices, 2 MeshCore LoRa nodes, 1 computer running `morp-agent`). The routing, flooding, dedup, and path-discovery mechanisms are optimized specifically for resilience at this realistic scale. Group chat membership cap remains **12 active members** due to NIP-17 $O(N)$ encryption fan-out overhead.
7. **Embedded Nostr Relay (`morp-relay`):** An embedded, lightweight NIP-01 local Nostr relay is integrated directly into MORP Box and `morp-agent` to cache, index, and serve kind-1 notes and events across local offline mesh islands.
8. **Reporter Identity vs. Testing Burners:** Persistent main npub identity is preserved as standard (reporters like Nick Shirley rely on established npubs to build public trust). For release v0.0.1, an alpha warning banner recommends burner profiles for testing, but rotating npubs are **not** forced by the protocol.

**Recommended Target Platform & minSdk:** Android 8.0+ (**minSdk 26**), targeting SDK 34+. Modern standard phones become tomorrow's target backup hardware, enabling clean Amber NIP-55 signer intent integration, hardware Keystore wrap, and modern AOSP cryptographic APIs.

**Effort Estimate:** ~30–36 engineer-weeks to a hardened v1.0 release (M0–M6), representing **4–5 months with a 2–3 developer team** or **7–9 months solo**.

---

## 1. Intent Analysis & Interpretation

Restatement of design intents (numbering preserved), with owner review clarifications and feasibility verdicts.

| # | Intent (as given) | Interpretation / Key Design Consequence | Verdict |
|---|---|---|---|
| 1 | Sleek, lightweight UI; runs on older Android as a backup comms system | Native Kotlin + Jetpack Compose, minSdk 26 (Android 8.0+), dark-first, low-power mode, no Google Services, APK ≈ 40–55 MB, peak RAM < ~250 MB | Feasible |
| 2 | Dual transport (BT ↔ WiFi/cellular) with automatic switching | Unified transport abstraction + capability router per message class; BT = BLE mesh + BT-Classic fast lane; WiFi = LAN discovery + TLS WebSockets; cellular = Nostr relays; LoRa = MeshCore companion (BLE/USB/TCP) | Feasible |
| 2a | Private messages via BT: phone↔phone, phone↔phone via MeshCore LoRa, phone↔npub via MORP NERD, phone↔non-mobile device via LoRa | P2P direct + mesh relay + LoRa tunnel + MORP NERD onion to an npub mailbox. "Non-mobile device" = headless `morp-agent` on Linux/Pi/server; speaks MORP wire protocol directly over LoRa/mesh | Feasible |
| 2b | MORP NERD protocol: Nostr identity + Reticulum device crypto; b1) offline kind-1 publish; b2) offline private messages; b3) offline group chats; b4) create/invite via mesh and internet | Multi-hop onion to an internet portal; NIP-17 sealed payloads inside; store-and-forward mailboxes; group rooms = NIP-17 p-tag rooms (capped at 12 members) with stable MORP group handle (`morpgrp1…`) | Feasible |
| 3 | Peer-to-peer XMR + BTC Lightning payments over BT-only (mesh) or internet; app holds a functional wallet of each | LDK Node embedded (BTC/LN); thin XMR wallet client referencing Cake Wallet (`cw_monero` view-key local scanning) + Monerujo patterns against remote node (Tor option). BT-only payments = queued signed instructions executed through first portal | Feasible (with stated caveats) |
| 4 | Lightweight kind-1 feed: post, replies, reposts, quotes — each also via MORP non-internet routes | Standard NIP-01/10/28 feed + embedded `morp-relay` for local caching; offline posts publish through P1 portals; repost = kind 10002; reply = kind 1 with e:/k: tags; quote = kind 1 citing original | Feasible |
| 5 | Anonymous BTC-LN/XMR payments from your Nostr profile to any other Nostr profile in the feed, via internet or BT-to-internet | NIP-57 zaps (9734/9735) for LN + custom MORP payment event (`kind 10081`) / `kind:10133` NIP-A3 targets for XMR (Ditto/Amethyst pattern); persistent reporter npub preserved; optional burner profiles for testing/source protection | Feasible |
| 6 | Tactical use: reporters/organizers in hostile-infrastructure environments (protests, hurricanes); get news local (mesh) and global (kind-1); compensate risk-takers permissionlessly | Offline-first, no central infra, store-and-forward, sideloadable, F-Droid, no telemetry, emergency wipe, wallet + zap integration for compensation. Optimized for 4–7 device field meshes | Feasible |
| 7 | Audio/photo/short-video in all modes (feed posts AND DMs/groups); compress if design limits prevent full-size | Blossom protocol specifications (BUD-01 through BUD-12, BUD-11 kind 24242 auth); compression targets per transport; chunked transfer with per-chunk ACK; NIP-94 (kind 1063) / NIP-17 kind 15 file messages; base64-inline fallback for tiny files; LoRa carries text + thumbnails | Feasible |
| M1 | MORP must hop through several devices (phones or LoRa nodes) to find one with internet | Advertisement + path-discovery protocol (§6.3); portal capability bitmask; multi-transport paths (BT→BT→LoRa→BT→portal). Optimized for 4–7 node mesh scale | Feasible |
| M2 | Intermediate relays must not learn publisher identity (reticulum OR npub) nor read content | Onion hop layers (§6.5): no source address anywhere; identity only in innermost sealed layer; content double-encrypted (mesh onion + NIP-59 seal) | Feasible |
| M3 | MORP must handle the throughput for all the above | Throughput budgeting + chunking strategy (§6.7); LoRa is text-first (hard physical limit, ~10–100 B/s typical sustained) | Feasible (LoRa = text-first by physics) |
| S1 | Compatible with Amber (or similar) Nostr signers, incl. offline signing | NIP-55 (Android `nostrsigner:` intents) + NIP-46 (connectable signer) support; minSdk 26 guarantees clean intent handling; internal signer fallback; NIP-49 bunker import | Feasible |
| S2 | Most open possible license; no credit retention; maximize adoption/replication | **100% Pure MIT License** for all code via `morp-rns` clean-room protocol implementation; **CC0 (Public Domain)** for protocol specifications; no trademark restriction | Feasible |

---

## 2. Reference Repository & Spec Analysis

### 2.1 Reticulum — `github.com/markqvist/Reticulum` & Clean-Room `morp-rns`
*Verified 2026-09-20. 7.3k★. Active.*

**License Nuance & Solution:** On April 15, 2025, the official Python Reticulum repository updated its license header to a custom "Reticulum License" adding a non-OSI condition ("shall not be used in any kind of system which includes amongst its functions the ability to purposefully do harm to human beings"). While well-intentioned, this non-standard clause creates legal ambiguity for journalists and protesters operating under hostile governments, who might arbitrarily brand news reporting or protest organization as "harm." Furthermore, independent Rust implementations like `Leviculum` (`Lew_Palm/leviculum` on Codeberg) are AGPL-3.0, which presents copyleft contamination risks for an MIT app binary.

**The Clean-Room Pathway (`morp-rns`):** The Reticulum network protocol specification itself is an unpatentable public wire format (512-bit EC identity hashes, X25519/Ed25519 ECDH, ratchet tokens, packet headers, unforgeable delivery ACKs). MORP Box implements **`morp-rns`** — a clean-room Kotlin module that implements the wire format directly from the public protocol specifications. `morp-rns` has zero code dependency on official Python RNS, C-CRNS, or AGPL Rust code. This enables distributing MORP Box, `core/`, `morp-rns`, and `morp-agent` under a **100% Pure MIT License**, completely free from non-OSI clauses or AGPL copyleft requirements.

### 2.2 NYM / Nymchat — `github.com/Spl0itable/NYM` & Post-Quantum Standard
*Verified 2026-09-20. 74★. Active (v3.75.x).*

**What it is.** Ephemeral geohash channels (`kind 20000`), named channels (`kind 23333`), and NIP-17 gift-wrapped DMs/groups (`kind 1059`), bridged with Bitchat for Bluetooth mesh.

**Post-Quantum Alignment:** Attested by developer Luxas on Nostr (`npub16jdfqgazrkapk0yrqm9rdxlnys7ck39c7zmdzxtxqlmmpxg04r0sd733sv`) for Nymchat (`https://web.nymchat.app`), Nymchat implements **ML-KEM-768** (FIPS 203 NIST post-quantum key exchange) alongside standard NIP-44 secp256k1 ECDH. Nymchat generates a static "Post-quantum recovery code" alongside the nsec key. MORP Box adopts this exact **ML-KEM-768 hybrid posture** for stored Nostr events, ensuring conceptual and wire alignment with Luxas's standard. (License: AGPL-3.0 → architecture and PQ reference only; no code reuse).

### 2.3 MeshCore — `github.com/meshcore-dev/MeshCore` & Hardware Baseline
*Verified 2026-09-20. 3.7k★. Active.*

**What it is.** C++ multi-hop packet routing library for embedded LoRa radios with companion firmware. Prebuilt for Heltec (V3, T114), RAK WisBlock (4631, 3401), LilyGo T-Echo, and Seeed (Xiao nRF52, MeshTracker X1/LR2021). Communicates with phones via **BLE (Nordic UART), USB-C serial (CDC-ACM), or TCP**. License: **MIT**.

**Hardware Optimization Baseline:** MORP Box optimizes for standard, low-cost LoRa nodes (**Heltec V3, Heltec T114, Seeed MeshTracker X1, RAK WisBlock 4631**). The app provides a hardware-agnostic MeshCore companion protocol layer (BLE NUS / USB CDC-ACM) to support the widest array of low-cost boards.

---

## 3. Cross-Referenced Research

Verified 2026-09-20.

| Project / Spec | Repo / Link | License | Role & Integration in MORP Box |
|---|---|---|---|
| **Leviculum** | codeberg.org/Lew_Palm/leviculum | AGPL-3.0 | Full Rust implementation of Reticulum stack (`reticulum-core`, `lnsd`). Serves as protocol wire-compatibility reference for `morp-rns`. Code quarantined due to AGPL-3.0. |
| **Cake Wallet** | github.com/cake-tech/cake_wallet | **MIT** | Non-custodial multi-currency wallet (XMR + BTC). Serves alongside Monerujo as the primary architectural reference for MORP Box's XMR light wallet: `cw_monero` view-key local scanning, subaddress generation, custom remote nodes, and Tor proxy support. |
| **Monerujo** | github.com (cryptonomex/monerujo) | Apache-2.0 | Mobile XMR light-wallet pattern (remote node, view keys, Tor routing). |
| **Ditto** | gitlab.com/soapbox-pub/ditto | AGPL-3.0 | Soapbox Nostr client with native Monero integration. Reference for XMR payment routing and UI display. |
| **Amethyst & NIP-A3 / Nosmero** | github.com/vitorpamplona/amethyst · github.com/Workstr7blo/workstr-web (issue #246) | MIT | Profile payment targets: `kind:10133` with `["payto", "monero", "<subaddress>"]` and `["payto", "xmr", "..."]`. MORP Box adopts this format for XMR profile tipping/zaps. |
| **Blossom Protocol** | nostrcompass.org/en/topics/blossom · github.com/hzrd149/blossom | CC0 / MIT | Nostr media storage specifications: BUD-01 (GET/HEAD by SHA-256 hash), BUD-02 (PUT /upload), BUD-03 (kind 10063 server lists), BUD-11 (kind 24242 Nostr authorization tokens). Foundation of MORP Box media pipeline. |
| **Bitchat** | github.com/permissionlesstech/bitchat | **Unlicense** | Dual-transport BLE mesh + Nostr fallback reference (36k★). Public-domain reference for BLE mesh routing, flood dedup, and emergency wipe. Clean-room Kotlin implementation in MORP Box. |
| **Amber** | github.com/greenart7c3/Amber | **MIT** | Nostr signer app: NIP-46 + NIP-55, offline build, minSdk 26. Primary external signer target. |
| **LDK / LDK Node** | github.com/lightningdevkit/ldk-node | Apache-2.0 / MIT | Embedded Lightning wallet engine (`org.lightningdevkit:ldk-node-jvm`). |
| **Nostr NIPs** | github.com/nostr-protocol/nips | Spec | NIP-01, 10, 14, 15, 13, 1059 (NIP-17 v2), 19, 20, 25, 28, 44, 46, 55, 57, 59, 65, 94. |

---

## 4. Feasibility & Security Posture Assessment

### 4.1 Post-Quantum Security Posture (Luxas / Nymchat Alignment)

| Layer | Primitive | Security / PQ Status | Scope & Guarantee |
|---|---|---|---|
| Mesh Hop Layers (MORP Onion) | `morp-rns` ratchet + per-packet ephemeral X25519 tokens | **Forward-Secret (In-Transit QR)**: Ephemeral keys ratcheted and destroyed per link. Harvest-now/decrypt-later on captured mesh traffic is defeated. | Hop-by-hop mesh confidentiality & anonymity |
| Stored Nostr Payloads (DMs, Files) | NIP-44 (secp256k1 ECDH + ChaCha20-HMAC) + **Optional MORP-PQ Wrapper (ML-KEM-768)** | **Post-Quantum Hybrid**: ChaCha20/HMAC core is PQ-safe. ML-KEM-768 wrapper (aligned with Luxas's Nymchat standard) protects stored ciphertexts against future QC key recovery. | End-to-end payload secrecy on relays |
| Nostr Identity & Signatures | secp256k1 (BIP-340 Schnorr) | Standard / Non-PQ: Preserves 100% Nostr ecosystem interoperability. Identity binding handles future PQ-curve migrations. | Public attribution & event signing |

### 4.2 Intent Feasibility Summary

1. **Lightweight UI on minSdk 26 Android — FEASIBLE.** Native Kotlin + Jetpack Compose, dark-first UI, low-power background mode, target minSdk 26 (Android 8.0+). APK size 40–55 MB, peak RAM < 250 MB.
2. **Dual Transport + Non-Mobile Endpoints — FEASIBLE.** Capability router handles BLE, BT-Classic, WiFi LAN, LoRa (MeshCore companion), and Cellular/Internet. Communicating with non-mobile devices (Linux/Pi) is fully native over LoRa/mesh via the headless **`morp-agent`** daemon.
3. **P2P XMR & BTC Lightning Wallets — FEASIBLE.** LDK Node for Lightning; Cake Wallet + Monerujo light-wallet pattern for XMR over remote node (with Tor proxy option). Payments over mesh are queued signed instructions executed when the payer's device reaches internet contact.
4. **Kind-1 Feed & Embedded Relay — FEASIBLE.** NIP-01/10/28 feed + local **`morp-relay`** (embedded NIP-01 relay) for offline mesh island caching. Posts publish locally, over mesh, or to internet relays.
5. **Profile-to-Profile Payments & Reporter Identity — FEASIBLE.** LN zaps (NIP-57) + XMR profile payment targets (`kind:10133` / NIP-A3 / Ditto / Amethyst standard). Reporter npub identity is persistent and standard. Version v0.0.1 includes an alpha warning banner recommending burner profiles for testing without forcing rotating npubs in protocol.
6. **Tactical Resilience & 4–7 Node Mesh Focus — FEASIBLE.** Offline-first design, sideloadable APK, F-Droid repo, emergency wipe (triple-tap + confirm). Protocol optimized for 4 to 7 node field meshes.
7. **Blossom Protocol Media Pipeline — FEASIBLE.** Full support for Blossom specifications (BUD-01 through BUD-12). Images, audio (Opus 24 kbps), and short video (H.264 360p) are addressed by SHA-256 hash. Uploads use BUD-11 `kind 24242` Nostr authorization. Offline mesh uses Blossom P2 media portals.

---

## 5. System Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│ MORP Box (Android / GrapheneOS, Kotlin, minSdk 26, no GMS)               │
│                                                                          │
│  ┌─────────────────────────── UI (Compose, dark-first) ───────────────┐  │
│  │ Feed │ Channels │ Chats │ Mesh │ Wallet │ Media │ Profile │ Secure │  │
│  └──────────────────────────────┬──────────────────────────────────────┘  │
│                                 │                                         │
│  ┌──────────────────────────────▼──────────────────────────────────────┐  │
│  │ App Services                                                        │  │
│  │  PublishPipeline (feed/DM/group/pay/media → relay OR portal)        │  │
│  │  DeliveryStateMachine (queued→on-mesh→published→delivered→confirmed)│  │
│  │  SignerService (internal │ Amber NIP-55 │ Amber NIP-46 │ NIP-49)    │  │
│  │  WalletService (LDK Node LN │ XMR Cake Wallet light client)          │  │
│  │  DeferredPayQueue (signed instruction → portal execution)           │  │
│  │  BlossomMediaPipeline (capture→compress→SHA256→BUD-11 auth→chunk)   │  │
│  └──────────────┬─────────────────────────────────────┬────────────────┘  │
│                 │                                     │                   │
│  ┌──────────────▼──────────────┐   ┌──────────────────▼────────────────┐  │
│  │ Nostr Core                  │   │ MORP Core                         │  │
│  │  Events (Room/SQLCipher)    │   │  morp-rns (Clean-room Reticulum)  │  │
│  │  morp-relay (Embedded NIP01)│   │  Identity (MORP ID bundle, certs) │  │
│  │  Feed engine (subs, WoT-lite)│   │  Mesh routing (ADV/PRQ/flood/dedup)│  │
│  │  NIP-17 chat (14/15/13/1059) │   │  Portal discovery (P1/P2/P3 caps)  │  │
│  │  Blossom (BUD-01/02/03/11)  │   │  Onion engine (hop layers)        │  │
│  │  NIP-44/59 + ML-KEM-768     │   │  Mailbox (store-and-forward)      │  │
│  │  Kinds: 1,7,14,15,9734/35,   │   │  Chunking/ACK/resume              │  │
│  │  10002,10050,10063,10133,    │   │  Crypto: token ratchet/ML-KEM     │  │
│  │  10080-89, 24242            │   │                                   │  │
│  └──────────────┬──────────────┘   └──────────────────┬────────────────┘  │
│                 │                                     │                   │
│  ┌──────────────▼─────────────────────────────────────▼────────────────┐  │
│  │ Transport Abstraction (capability router, adaptive power)           │  │
│  │  BLE GATT/NUS (mesh + radio) │ BT-Classic RFCOMM (fast lane)        │  │
│  │  WiFi LAN (mDNS + TLS WS)    │ LoRa (MeshCore companion: BLE/USB/TCP)│ │
│  │  Internet (wss relays, HTTP Blossom, LNURL, XMR-node over Tor)     │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

### Module Layout (`core/` & `tools/`)
- `app/` — Android application (minSdk 26, Jetpack Compose, Jetpack Services)
- `core/morp-rns` — **100% Clean-room Kotlin implementation** of Reticulum wire protocol (512-bit EC identity, X25519/Ed25519, token ratchets, headers, ACKs)
- `core/morp-relay` — Embedded local NIP-01 Nostr relay for offline mesh island caching
- `core/morp-blossom` — Blossom protocol client and P2 media portal (BUD-01/02/03/11)
- `core/morp-mesh` — BLE, BT-Classic, WiFi LAN, and LoRa MeshCore companion transport router
- `core/morp-onion` — Hop-by-hop onion wrapper and ML-KEM-768 hybrid PQ engine
- `core/morp-nostr` — NIP-17 chats, NIP-44/59 crypto, Amber NIP-55 signer bridge
- `core/morp-payments` — LDK Node JVM wrapper + Cake Wallet / Monerujo light XMR client
- `spec/` — **CC0 Public Domain**: MORP NERD protocol spec, wire format, MORP kinds, threat model
- `tools/` — Multi-node mesh simulator, wire-format fuzzers, interop test harness
- `agent/` — Headless **`morp-agent`** daemon (Rust/Kotlin CLI) for Linux/Pi/server non-mobile endpoints

---

## 6. MORP NERD Protocol Specification (v0.2 Draft)

### 6.1 Binary Frame Structure
```
MORP-PKT v1 (binary, max packet MTU per transport)
  header (cleartext, ≤ 120 B):
    magic(4) 0x4D4F5250 | ver(1) | ftype(1: ADV|PRQ|PRA|DATA|ACK|CTRL)
    next_hop(32)            // Reticulum identity hash of next relay
    path_id(8)              // opaque correlation ID
    ttl(1) | flags(1)       // flags: chunked, ack-required, priority, portal-bound
    msg_id(32) | seq(2) | total(2) | chunk_len(2) | pkt_len(2)
  payload:
    H1( H2( ... ( Hn( P ) ) ... ) )
    Hi = ReticulumToken( channel-to-hop-i , content = next_hop_{i+1}(32) || H_{i+1} )
    Hn = ReticulumToken( channel-to-portal-or-final , content = P )
  P = one of:
    NOSTR_EVENT    // signed Nostr event (kind 1, 14, 15, 13→1059, 9734, 10002, 10080, 10133…)
    BLOSSOM_CHUNK  // Blossom SHA-256 media blob bytes + manifest in chunk 0
    PAYMENT        // signed payment instruction (LN invoice or XMR subaddr + nonce)
    CONTROL        // PRQ/PRA/ACK/NACK/ADV-response, mailbox-fetch
    MAILBOX_FETCH  // pull pending mail for identity X
```

### 6.2 MORP Kinds (Nostr Protocol Band 10080–10089 & Standards)
- `10080` MORP identity bundle (npub ↔ Reticulum hash ↔ capabilities)
- `10081` MORP payment event (XMR tx reference / LN zap supplement)
- `10082` MORP media descriptor (Blossom SHA-256 metadata tags)
- `10083` MORP delivery status (optional receipt)
- `10063` BUD-03 Blossom server list
- `10133` NIP-A3 Monero payment target (`["payto", "monero", "<subaddress>"]`)
- `24242` BUD-11 Blossom HTTP authorization token

### 6.3 Mesh Routing & 4–7 Node Target Optimization
- **Primary Scale:** The routing engine (flood-with-dedup, TTL default 8, jittered ADV every 5–20 s) is specifically tuned for optimal performance on **4 to 7 device meshes** (e.g., 4 Android devices, 2 MeshCore LoRa nodes, 1 `morp-agent` server).
- **Embedded Relay (`morp-relay`):** Nodes run `morp-relay` to index and cache local kind-1 notes and events. When two offline mesh islands merge, `morp-relay` instances perform light event reconciliation using NIP-01 filters.

### 6.4 Blossom Media Pipeline (BUD-01 through BUD-12)
- **Online Mode:** Files (photos, audio, video) are hashed (SHA-256), wrapped in BUD-11 `kind 24242` authorization tokens, and uploaded via HTTP `PUT /upload` to the user's preferred Blossom server list (`kind 10063`).
- **Offline Mesh Mode:** Files are compressed to transport targets (§6.7), hashed via SHA-256, and chunked into `BLOSSOM_CHUNK` frames. Nearby nodes with P2 media portal capabilities store the SHA-256 blob in local Blossom storage and forward it to internet Blossom servers upon reaching network connectivity.

### 6.5 Group Chat Model & Member Cap
- **Member Cap:** Groups are capped at **12 active members** due to NIP-17 per-recipient gift-wrap encryption fan-out overhead.
- **MORP Group Handle:** Groups are identified by a stable handle (`morpgrp1…`) over `{group-secret, subject}`, mapping room continuity across member changes.

### 6.6 Reporter Identity Model
- **Persistent npub Identity:** Reporters retain their established Nostr npubs. Rotating npubs are **not** mandated by the protocol.
- **Alpha Testing Banner:** App release v0.0.1 displays a prominent alpha warning banner recommending burner profiles for testing protocol alpha releases without risking main profile history.

---

## 7. Payments Design (BTC Lightning + XMR)

### 7.1 Bitcoin Lightning
- **Engine:** LDK Node JVM (`org.lightningdevkit:ldk-node-jvm`) embedded.
- **NIP-57 Zaps:** Online: standard LNURL-pay → `kind 9734` zap request → `kind 9735` receipt. Offline Mesh: MORP direct invoice mode (invoice sent via PAYMENT frame over mesh; paid when online; self-published 9734/9735 receipts).

### 7.2 Monero (XMR)
- **Engine:** Thin Kotlin JSON-RPC client referencing **Cake Wallet** (`cake-tech/cake_wallet`, MIT; `cw_monero` view-key local scanning) and **Monerujo** patterns against user-configured remote nodes over Tor.
- **Profile Discovery & Zaps:** Profiles publish XMR payment targets using `kind:10133` (NIP-A3 standard: `["payto", "monero", "<subaddress>"]`) and profile metadata, matching **Ditto** and **Amethyst** patterns.
- **Deferred Payment Queue:** Payments over mesh create a signed, non-revocable instruction (`PAYMENT` frame with nonce and 72 h expiry). The payer's own device executes the transaction when it reaches an internet path.

---

## 8. UI/UX & Tactical Performance

- **Target minSdk:** Android 8.0+ (minSdk 26). Modern standard phones become tomorrow's target backup phones.
- **Old Device Performance Budget:** Cold start < 3 s on API 26 hardware; APK 40–55 MB; peak RAM < 250 MB.
- **Alpha Banner:** v0.0.1 UI includes a top banner: *"Alpha Release — Protocol in Testing. Use Burner Profiles for Testing."*
- **Emergency Wipe:** Triple-tap action + 3-second hold confirmation re-randomizes SQLCipher database keys and zero-fills local key stores.
- **GrapheneOS Compatibility:** Standard AOSP APIs, foreground service autostart, battery exemption prompts, zero Play Integrity dependencies.

---

## 9. Security & Licensing Requirements

### 9.1 Licensing Architecture
- **App Code & Core Modules (`core/`, `app/`, `tools/`, `agent/`):** **100% Pure MIT License** via `morp-rns` clean-room protocol implementation.
- **Protocol Specifications (`spec/`):** **CC0 (Public Domain)**.
- **Trademark:** "MORP Box" is un-trademarked community property.
- **Copyleft Quarantine:** Zero AGPL or GPL code in app binaries. `Leviculum` (AGPL-3.0) and `NYM` (AGPL-3.0) are behavioral references only.

---

## 10. Implementation Roadmap

### M0 — De-Risk Spikes (2 engineer-weeks)
1. **`morp-rns` Clean-Room Wire Spike:** Implement Reticulum wire format (512-bit EC identity, X25519/Ed25519 token ratchet, headers, ACKs) in pure Kotlin. Test packet interchange against `Leviculum` / Python Reticulum.
2. **BLE Mesh Spike:** 4-device BLE flood, dedup, and path-discovery test on API 26 hardware.
3. **MeshCore Companion Spike:** Kotlin client over BLE NUS and USB CDC-ACM to Heltec V3, T114, and Seeed MeshTracker X1.
4. **LDK Node Spike:** Fund testnet channel, pay/receive on minSdk 26 hardware.
5. **XMR Light Wallet Spike:** Testnet wallet scan and send via remote node over Tor (Cake Wallet `cw_monero` pattern).
6. **Amber NIP-55 Intent Spike:** Test offline `nostrsigner:` intent round-trip on Android 8.0+.
7. **GrapheneOS Check:** Verify background service autostart without GMS.

### M1 — App Skeleton & Online Nostr (4 ew)
Identity, minSdk 26 setup, Amber signer integration, `morp-relay` (embedded local relay) integration, NIP-65 relay manager, kind-1 feed, delivery state machine v1, F-Droid CI pipeline.

### M2 — MORP Mesh Core v1 (`morp-rns` + BT) (6 ew)
Integrate `morp-rns`, BLE NUS, BT-Classic, WiFi LAN; ADV/PRQ/PRA flooding; MORP ID bundles; offline kind-1 publish through P1 portal; multi-node simulator in `tools/`. Field test on 4 Android devices.

### M3 — Messaging, Groups, Blossom Media (6 ew)
NIP-17 DMs and 12-member group chats; MORP group handles (`morpgrp1…`); Blossom protocol pipeline (`core/morp-blossom`, BUD-01/02/03/11); ML-KEM-768 hybrid PQ wrapper integration; offline P2 Blossom media portals.

### M4 — LoRa Companion Leg & `morp-agent` (6 ew)
Production MeshCore companion client (BLE/USB); hardware optimization for Heltec V3/T114, RAK 4631, Seeed MeshTracker X1; headless **`morp-agent`** daemon for Pi/Linux non-mobile endpoints. Field test on **4 Android devices + 2 LoRa nodes + 1 `morp-agent` PC**.

### M5 — Wallets & Payments (6 ew)
LDK Node production integration; Cake Wallet / Monerujo light XMR client; `kind:10133` NIP-A3 payment target support (Ditto/Amethyst format); deferred payment queue; alpha warning banner in UI.

### M6 — Hardening, Security Review & 1.0 Release (4 ew)
Old-device QA sweep (minSdk 26), SQLCipher at rest, external security review, F-Droid reproducible build, spec finalization in `spec/` (CC0), upstream MORP kind proposal to `nostr-protocol/nips`, **1.0.0 Release**.

### M7 — Post 1.0 (Unscheduled Stretch)
Full Reticulum group channel protocol for >12 member groups; Bitchat BLE envelope compatibility mode; Nostr Wallet Connect (NIP-47/98); iOS Swift client.

---

## 11. Finalized Answers to Owner Questions (Q1–Q10)

1. **minSdk Target:** **26 (Android 8.0+)**. Standard current phones become tomorrow's target backup hardware, enabling modern cryptographic APIs, Keystore wrapping, and clean Amber NIP-55 intent integration.
2. **LoRa Reference Hardware:** **Heltec V3, Heltec T114, Seeed MeshTracker X1, RAK WisBlock 4631**. Hardware-agnostic companion abstraction ensures broad compatibility with low-cost nodes.
3. **XMR Wallet Remote-Node Model:** **Accepted**. Light-wallet pattern combining **Cake Wallet** (`cw_monero` view-key local scanning) and **Monerujo** patterns over custom remote nodes or Tor proxy.
4. **Post-Quantum Security Framing:** **Accepted**. In-transit ratcheted forward secrecy + optional **ML-KEM-768 hybrid wrapper** (aligned with Luxas's Nymchat standard) + secp256k1 Nostr identity.
5. **Open Licensing Model:** **Pure MIT License** for all code via `morp-rns` clean-room protocol implementation; **CC0 (Public Domain)** for all specifications.
6. **Bitchat Reference Code:** **Clean-room Kotlin implementation** for BLE mesh layer; Bitchat used as public-domain behavioral reference.
7. **Group Chat Size Cap:** **12 active members** (NIP-17 per-recipient gift-wrap limit).
8. **Embedded Nostr Relay:** **Accepted and incorporated directly into MORP NERD spec v1** (`morp-relay` runs locally for offline mesh island event caching).
9. **Reporter Identity & Burner Profiles:** Persistent main npub identity preserved as standard for reporters (e.g. Nick Shirley pattern). Alpha warning banner added to v0.0.1 recommending burner profiles for testing.
10. **Field Testing Scale:** Realistic target scale locked at **4 Android devices + 2 MeshCore LoRa nodes + 1 computer running `morp-agent`**. Protocol fully optimized for resilience at this 4–7 device scale.

---

## 12. Risk Register

| # | Risk | L×I | Mitigation Strategy |
|---|---|---|---|
| R1 | Post-Quantum overclaim | M×H | Precise framing: in-transit forward secrecy + ML-KEM-768 stored wrapper; honest UI language. |
| R2 | Reticulum "no-harm" clause legal ambiguity | M×H | Solved by **`morp-rns`**: 100% clean-room Kotlin wire-compatible protocol stack released under pure MIT + CC0. |
| R3 | XMR remote node privacy dependency | H×M | Integrated Tor proxy option, custom node selection, Cake Wallet view-key local scanning pattern. |
| R4 | LoRa throughput limits for media | H×M | Blossom media pipeline enforces strict transport compression targets; text and payments prioritized (P0). |
| R5 | Copyleft contamination (AGPL code) | L×H | Hard quarantine rule; zero AGPL code bundled; `Leviculum` and `NYM` treated strictly as behavioral references. |
| R6 | Android BLE background restrictions | M×H | Foreground service with clear battery-exemption onboarding prompt. |
| R7 | Media server unavailability in offline zones | M×M | Embedded `morp-relay` and Blossom P2 media portals store and forward SHA-256 media blobs locally over mesh. |

---

## 13. Appendix & References

### A. Key References (Verified 2026-09-20)
- **Reticulum Specification:** `reticulum.network` / `github.com/markqvist/Reticulum`
- **Leviculum (Rust Reticulum):** `codeberg.org/Lew_Palm/leviculum` (AGPL-3.0)
- **Cake Wallet:** `github.com/cake-tech/cake_wallet` (MIT)
- **Nymchat & Luxas PQ Attestation:** `https://web.nymchat.app` / Nostr `npub16jdfqgazrkapk0yrqm9rdxlnys7ck39c7zmdzxtxqlmmpxg04r0sd733sv`
- **Blossom Protocol:** `nostrcompass.org/en/topics/blossom` / `github.com/hzrd149/blossom` (BUD-01 through BUD-12)
- **Ditto (Soapbox Pub):** `gitlab.com/soapbox-pub/ditto` (AGPL-3.0)
- **Nosmero / Workstr (`kind:10133` Payment Target):** `github.com/Workstr7blo/workstr-web/issues/246`
- **MeshCore:** `github.com/meshcore-dev/MeshCore` (MIT)
- **Bitchat:** `github.com/permissionlesstech/bitchat` (Unlicense)
- **Amber:** `github.com/greenart7c3/Amber` (MIT)
- **LDK Node:** `github.com/lightningdevkit/ldk-node` (Apache-2.0 / MIT)

---
*Prepared by the researcher for owner hand-off. Approved Version 0.2: Ready for implementation agents.*
