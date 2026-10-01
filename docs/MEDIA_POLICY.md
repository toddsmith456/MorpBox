# Media Policy — files in every mode (design intent §7)

## Principles
1. **Same UX on- and off-internet**: user attaches media; app decides inline-chunked
   (small) vs portal-upload + URL+hash event (large).
2. **Tactical privacy first**: EXIF/GPS stripped always; filename randomized; optional
   face/plate blur before send (M3); recipient sees hash + dimensions, never source path.
3. **Integrity**: SHA-256 manifest; every chunk verified on reassembly; Nostr event
   carries `x` (hash) + `url` + `m` (mime) per NIP-92/94 `imeta`.

## Compression ladder

| Type | Internet route | Mesh route (inline onion) | Mesh last-resort (LoRa-only) |
|---|---|---|---|
| Photo | WebP q80, ≤2048px, ≤2 MB | WebP q65, ≤1280px, ≤100 KB | WebP q50, ≤800px, ≤30 KB |
| Audio | Opus 24k mono, ≤5 min | Opus 12k mono, ≤60 s (~90 KB) | Opus 8k mono, ≤30 s |
| Video | H.264 720p, ≤60 s, ≤8 MB | H.264 480p, ≤15 s, ≤500 KB | 480p, ≤6 s, ≤150 KB or animated-WebP fallback |

Portal-upload threshold: payload > 8 KB goes portal-upload on internet legs; on pure
mesh the manifest + first chunks go first so recipient sees preview/progress.

## Manifest
```
MediaManifest = { "v":1, "mime":…, "bytes":N, "sha256":hex,
  "dims":[w,h]?, "dur_ms":…?, "chunks":K, "chunk_bytes":B, "thumb": b64-small? }
```
`thumb` ≤ 4 KB blurhash/WebP, shown in chat/feed while chunks arrive.

## Blossom binding (research §6.4, authoritative)

- Online: capture → compress → SHA-256 → BUD-11 `kind 24242` auth → HTTP `PUT /upload`
  to the user's `kind 10063` server list (BUD-03) → `kind 10082` MORP media descriptor.
- Offline mesh: same hash/manifest, chunked as `BLOSSOM_CHUNK` DATA frames (chunk 0 =
  manifest) to a **P2 portal**, which PUTs on reconnect and returns URL+hash via receipt.
- Retrieval: BUD-01 `GET /<sha256>` (+Range); every fetch hash-verified.
- LoRa legs: text + thumbnails only (physics).

## Nostr mapping
- Feed post with media: kind-1 `content` + `imeta` tags (NIP-92/94) pointing at
  the Blossom URL returned by portal receipt (or direct upload when online).
- DM/group: NIP-17 kind 15 file rumors carry Blossom URL + `x` hash; bytes sealed
  inside room encryption; v0.1 P2 portals see media plaintext for `media-upload`
  and the UI MUST warn (sealed-upload lands M3).
