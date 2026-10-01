# Amber / External Signer Integration

MORP Box treats the Nostr secret key as **optional to hold**. Preferred tactical
setup: `nsec` lives in Amber (or any NIP-46/Android-signer-compatible app), MORP Box
only ever sees `npub` + signatures. This includes **fully offline signing**: Amber
signs locally with no network, so airplane-mode + BLE-mesh posting works.

## Supported: `com.greenart7c3.nostrsigner` intents (+ NIP-46 bunker as fallback)

| Action | Intent / URI | Purpose |
|---|---|---|
| Get public key | `nostrsigner:` + `?compressionType=none&returnType=signature&type=get_public_key` | login / account add |
| Sign event | `type=sign_event`, extra `eventJson` (unsigned, no `id`/`sig`) | kind-1/DM/group events |
| NIP-44 encrypt/decrypt | `type=nip44_encrypt` / `nip44_decrypt`, extras `pubkey`,`plaintext`/`ciphertext` | seal layer compat |
| Decrypt zap / LN auth | `type=decrypt_zap_event` | wallet-adjacent flows |

Flow: MORP Box builds unsigned event → fires intent → user approves in Amber →
`onActivityResult` returns signed JSON → MORP Box verifies `id` locally → outbox
stores **signed bytes** → sends via internet or MORP NERD onion. Retry never
re-signs (one signature, many routes).

## Offline signing checklist
- Unsigned event JSON built locally (no relay fetch needed except reply-context, which uses cache).
- Intent extras include ` Romans`? no — include `id` preimage only if Amber version needs it (we compute `id` per NIP-01 ourselves and verify after).
- QR fallback (NIP-46 `nostrconnect://` + animated QR) for air-gapped signer phones — M3.
- UI badge per account: `AMBER · offline-capable` vs `APP KEY · keystore`.

## Bunker link (ngit contributor flow)
The maintainer-provided `bunker://` link is used for repo publishing identity on
gitworkshop.dev, NOT for user chat keys. `protocol/morp/nostr.py:parse_bunker_url`
validates the format; Android stores it in EncryptedSharedPrefs, never logs it.
