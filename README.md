# MorpBox

**A simple Android client for the MORP Box stack.** Nostr notes, private chats, Lightning zaps and Monero xaps — over the internet when it is there, over a Bluetooth mesh (and MeshCore LoRa) when it is not. Each path covers for the other.

Kotlin + Jetpack Compose. Themed with **[Youniversal](https://github.com/toddsmith456/Youniversal)** (Material You, Light / Dark / Cream, live controls). No Google Play Services.

Licensed under [MIT](LICENSE). Protocol specs under `docs/` remain CC0.

> Alpha-grade cryptography and networking. Use burner profiles. Not audited.

---

## What it does

| # | Feature | How |
|---|---------|-----|
| 1 | **Bitchat-style phone-to-phone Bluetooth mesh** | BLE advertise + scan + GATT flood, MORP-PKT v1 frames, TTL, dedup |
| 2 | **Bluetooth → Nostr kind 1** | Sign once, gossip the signed event on the mesh; a portal phone with internet publishes it to *your* relays |
| 3 | **Phone → MeshCore** | Nordic UART companion protocol; MORP payloads as channel datagrams (`data_type` 0xFFFF) |
| 4 | **Nostr kind 1 feed from the mesh** | Incoming BLE/LoRa envelopes are verified and land in the same feed as relay events (`via mesh`) |
| 5 | **PMs and group chats** | NIP-17 gift wrap (kind 14 rumor → 13 seal → 1059 wrap), NIP-44 v2, 12-member groups, named mesh rooms |
| 6 | **Wallets, zaps and xaps** | NIP-57 zap requests + LNURL-pay, optional NWC pay, kind 10133 Monero payto, 72h deferred queue |

Routing policy per send: **auto** (internet if up, else mesh), **force-mesh**, **force-internet**. Toggle **Act as portal** and a phone with signal becomes a P1/P2/P3 uplink for everyone nearby.

```
┌──────────── Compose UI (Youniversal) ────────────┐
│  Feed · Chats · Mesh · Wallet · You              │
├──────────────────────────────────────────────────┤
│  MorpSession  sign-once outbox · portal bridge   │
├──────────────┬──────────────┬────────────────────┤
│ RelayPool    │ BLE mesh     │ MeshCore NUS       │
│ (kind 1/17)  │ phone↔phone  │ LoRa companion     │
└──────────────┴──────────────┴────────────────────┘
```

## Requirements

| | |
|---|---|
| Android Gradle Plugin | 8.13.2 |
| Gradle | 8.13 |
| JDK | 17 |
| Kotlin | 2.3.20 |
| Compose BOM | 2026.04.01 |
| `compileSdk` / `targetSdk` | 36 |
| `minSdk` | 26 (Android 8.0) — GrapheneOS included |
| Hardware | Bluetooth LE required; MeshCore radio optional |

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :youniversal:testDebugUnitTest
```

The debug APK is signed with the Android debug key so it sideloads. Release is minified with the same debug key (replace before Play / F-Droid).

## Use

1. Open the app. A secp256k1 identity is generated and sealed in Android Keystore.
2. **You** → set a display name, lightning address, optional NWC and XMR subaddress, then publish.
3. **Feed** → write a note. `auto` sends to relays when online and always gossips on the mesh if mesh is on.
4. **Mesh** → grant Bluetooth, tap **MESH ON**. Nearby MorpBox phones appear as peers; a phone with internet advertises itself as a portal.
5. **Chats** → paste an npub, send a NIP-17 DM. Same dual path.
6. **Wallet** → zap a note from the feed, or queue an XMR xap.

Emergency wipe: **You** → triple-tap, hold 3 seconds.

## Project layout

```
MorpBox/
├── app/                 native Kotlin app (dev.morpbox.app)
├── youniversal/         Youniversal theme library (vendored, MIT)
├── protocol/            Python executable spec (MORP Box)
├── docs/                architecture, NERD spec, threat model (CC0)
├── tools/               Youniversal palette generator
└── LICENSE              MIT
```

The Python `protocol/` package is the wire-format authority. Kotlin classes under `app/.../protocol` mirror it.

## Lineage

- [MORP Box](https://gitworkshop.dev/npub1te6ect9y5n3z9wn6lz0xluc4u8f8ssl730g28elxre96tvwqwvnqgneqew/relay.ngit.dev/MORP-Box) — protocol, onion, BLE design, wallets
- [Youniversal](https://github.com/toddsmith456/Youniversal) — Material You theme system
- BitChat (behavioral reference for BLE flood/dedup only; clean-room Kotlin)
- [MeshCore companion protocol](https://docs.meshcore.io/companion_protocol/)

## License

MIT. See [LICENSE](LICENSE).
