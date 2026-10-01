# MORP Box — Threat Model

> Audience: reporters/organizers under hostile governments or disaster blackouts.
> This doc states what MORP Box protects against, what it does NOT, and what must
> hold for the guarantees to mean anything. Prototype status: none of this is audited.

## 1. Adversaries

| ID | Adversary | Capability |
|---|---|---|
| A1 | Passive mesh observer | Sees BLE/RNS/LoRa ciphertext, sizes, timings, link-layer addrs |
| A2 | Malicious relay | Runs a MORP relay, peels one onion layer, may drop/replay/delay |
| A3 | Malicious portal | Has internet; sees unwrapped `InnerRequest` (signed events, gift-wrap *outer*) and publishes (or censors) |
| A4 | Network censor | Kills cell/internet regionally; may run hostile Nostr relays; deep-packet-inspects remaining links |
| A5 | Device seizure | Gets the unlocked/locked phone; forensic extraction |
| A6 | Global passive observer | Sees mesh + internet sides and tries to correlate (timing/size) |

Out of scope (but designed not to preclude later): A6 full resistance (needs cover
traffic, §9 of spec), hardware implants, coercion/rubber-hose.

## 2. Guarantees (when used correctly)

1. **Relay blindness (spec §MORP-NERD-2):** A1/A2 learn next-hop + size tier + timing
   only. They do NOT learn author npub, recipient identity, payload plaintext, or
   origin-vs-forwarded. Enforced by: no source fields, per-hop AEAD, padding tiers,
   ephemeral hop keys per packet.
2. **Portal minimization:** A3 necessarily sees the signed Nostr event (it must
   publish it) — so the *npub is visible to the portal* — but MUST NOT be able to link
   it to the author's device/link identity: no source addr, jittered timing, reply-via-gossip.
   For DMs, A3 sees gift-wrap outer only, never seal plaintext (NIP-44 + PQ-hybrid).
3. **End-to-end content:** kind-1 is public-by-design (signed plaintext to relays);
   DMs/groups are sealed to recipients; payments are sealed to portal-helper or recipient.
4. **Authenticity:** every published event is BIP-340-verified by the portal before
   publish; relays can't forge (no nsec); Amber keeps nsec off-app if desired.
5. **Forward secrecy (DMs):** per-message ephemeral ECDH + rotating sender keys;
   PQ-hybrid KEM for long-term confidentiality against "harvest now, decrypt later".
6. **Seizure resistance (partial):** SQLCipher DB, Keystore keys, auto-lock, duress/PIN-wipe
   (planned), EXIF strip, no plaintext logs. Locked-phone forensics should yield ciphertext only.
7. **Censorship resilience:** any portal suffices; multi-portal retry; mesh-local rooms
   work with zero internet; outbox survives reboots/airplane mode.

## 3. Explicit NON-guarantees

- **Portal sees your npub** when it publishes for you. If posting itself is the crime,
  use a single-purpose npub over MORP (pseudonym hygiene is on the user; UI will warn).
- **Traffic correlation:** A6 watching both your BLE neighborhood AND relay arrivals can
  statistically link bursts. Mitigations: padding tiers, jitter, multi-portal, delayed send
  ("publish in 10–60 min" button). Full cover traffic is v0.2+.
- **Malicious portal can censor or delay** your post (can't forge). Mitigation: retry via
  2+ portals; receipts prove publication.
- **Compromised peer phone** that you DM directly sees plaintext (endpoint security).
- **Radio direction-finding** defeats everything — mesh radio emits RF, full stop.

## 4. Trust assumptions

- Amber (if used) is honest-but-curious at worst; signing payload shown to user before approve.
- Android Keystore / GrapheneOS hardware keystore holds; Titan/StrongBox where present.
- Audited libs only for crypto (Tink, BouncyCastle, bitcoin-kmp); no custom primitives.
- Nostr relays may be hostile: client verifies `OK` + fetches back its event id (NIP-01 `REQ`)
  before showing ACKED.
- Portal list is untrusted input: verify attestations, cap relay substitution (portal MUST use
  client-chosen relays), gossip receipts.

## 5. Safety-critical UX rules

- Every offline action shows its exposure: "Portal P will see your npub" / "Relays see nothing".
- One-tap **mesh kill-switch** (stop all radios) + **duress wipe** (planned M3).
- Media: EXIF/location stripped by default, on-device ML face-blur option (planned).
- Wallet: testnet default in dev builds; mainnet requires explicit unlock + backup verified.
