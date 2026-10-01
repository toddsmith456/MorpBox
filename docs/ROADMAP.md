# MORP Box — Roadmap (research v0.2, approved)

Estimate: ~30–36 engineer-weeks M0–M6 (≈4–5 mo with 2–3 devs, 7–9 mo solo).
Field rig every milestone from M2: **4 phones + 2 LoRa + 1 morp-agent**.

## M0 — De-risk spikes ✅ DONE (this repo)
- [x] MORP NERD spec + MORP-PKT v1 frame + Python executable reference (18 tests green)
- [x] `morp-rns` wire spike (identity/announce/seal/ACK/ratchet, KT+PY mirrors)
- [x] BLE/GATT transport design + flood/dedup/beacon implementation (KT)
- [x] Blossom BUD-01/02/03/11 reference (KT+PY), NIP-55 Amber flow, GrapheneOS notes
- [x] Android scaffold: 8 tabs, alpha banner, Room/SQLCipher schema, relay pool,
      embedded relay, delivery machine, outbox worker, wipe stub, minSdk 26

## M1 — App skeleton & online Nostr (4 ew) ← WE ARE HERE (M1-final left)
- [ ] ServiceLocator wiring (keys → signer → db → relays → pipeline → worker)
- [ ] Feed UI on RelayPool + EmbeddedRelay (post/reply/repost/quote, NIP-65)
- [ ] F-Droid CI + reproducible-build pipeline; Kotlin JUnit vs `vectors/v0.1.json`
- [ ] On-device: Amber NIP-55 round-trip (online + airplane), GrapheneOS autostart
- [x] License ruling applied (MIT + CC0, final)

## M2 — MORP mesh core v1: morp-rns + BT (6 ew)
- [ ] `morp-rns` links (handshake, path discovery, announce routing), RNS beacons
- [ ] Onion engine over v1 DATA frames; portal P1 mode; receipt gossip; mailbox
- [ ] ADV/PRQ/PRA flooding; MORP ID bundles (kind 10080); offline kind-1 via P1
- [ ] Gradle split: `:core:morp-{rns,relay,blossom,mesh,onion,nostr,payments}`
- [ ] `tools/` simulator (loss/latency, partitions) + fuzzers; 4-phone field test

## M3 — Messaging, groups, Blossom media (6 ew)
- [ ] NIP-17 DMs + 12-member groups + `morpgrp1…` handles; geo/named channels
- [ ] ML-KEM-768 MORP-PQ wrapper (BouncyCastle; Luxas/Nymchat-aligned framing)
- [ ] Blossom pipeline end-to-end + P2 mesh media portals; NIP-94/NIP-17 kind 15
- [ ] Invite flows mesh+internet; exposure labels; delayed send; kill-switch

## M4 — LoRa leg + morp-agent (6 ew)
- [ ] MeshCore companion client (BLE NUS + USB CDC-ACM); Heltec V3/T114, RAK 4631, X1
- [ ] `morp-agent` daemon (portal/mailbox/relay) for Pi/Linux; full-rig field test
- [ ] LoRa budgets in UX (text-first, thumbnails); 2× redundancy flag

## M5 — Wallets & payments (6 ew)
- [ ] LDK Node JVM (invoices, keysend, NIP-57 zaps, offline direct-invoice mode)
- [ ] XMR thin client (Cake `cw_monero` scan pattern + Monerujo, node over Tor)
- [ ] kind 10133 payto targets; DeferredPayQueue execution; P3 portal mode
- [ ] Testnet→mainnet gating (backup-verified unlock)

## M6 — Hardening & 1.0 (4 ew)
- [ ] Old-device QA (API 26–28), cold start <3 s, RAM <250 MB, APK 40–55 MB
- [ ] External security review; threat-model audit; fuzz corpus clean
- [ ] F-Droid release; signed tags (ngit); upstream MORP-kind NIP proposal; **1.0.0**

## M7 — Post-1.0 stretch
Reticulum group channels (>12), Bitchat BLE envelope compat, NIP-47/98 NWC, iOS.
