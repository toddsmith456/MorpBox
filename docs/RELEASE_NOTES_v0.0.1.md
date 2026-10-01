# MORP Box v0.0.1 — ALPHA (protocol in testing)

> Use burner profiles for testing. Not audited. Do not rely on for safety-critical use.

First installable alpha: native Android app (minSdk 26, no Play Services) with
8 sections (Feed·Channels·Chats·Mesh·Wallet·Media·Profile·Secure), alpha banner,
and emergency-wipe stub. Under the hood: MORP-PKT v1 frames, onion router,
portal tiers P1/P2/P3, `morp-rns` clean-room spike, embedded `morp-relay`,
Blossom BUD-01/02/03/11 client, BLE mesh flood/dedup, Room/SQLCipher store,
delivery machine + outbox worker, Amber NIP-55 signer flow.

- 18/18 Python reference tests green (`protocol/`); Kotlin mirrors byte-compatible.
- APK is debug-signed for this alpha; reproducible release signing lands before 1.0.
- Install: sideload the APK (Settings → allow unknown sources / F-Droid later).
- Source + vectors: this repo, tag `v0.0.1`.
