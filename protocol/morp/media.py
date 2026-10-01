"""Media policy ladder + chunk manifests. See docs/MEDIA_POLICY.md.

Dependency-free: pure policy + manifest math. Android's MediaSvc implements the
actual codecs (WebP/Opus/H.264); this module is the shared contract both sides obey.
"""
from __future__ import annotations

import base64
import hashlib
import json
from dataclasses import dataclass

# (max_bytes, max_dim_or_dur, label) per route class
LADDER = {
    "photo": {"internet": (2_000_000, 2048), "mesh": (100_000, 1280), "lora": (30_000, 800)},
    "audio": {"internet": (3_600_000, 300), "mesh": (90_000, 60), "lora": (30_000, 30)},
    "video": {"internet": (8_000_000, 60), "mesh": (500_000, 15), "lora": (150_000, 6)},
}
INLINE_THRESHOLD = 8 * 1024  # ≤8 KB rides inside the onion; above → portal upload


@dataclass
class MediaPlan:
    media_type: str
    route: str
    max_bytes: int
    max_dim_or_s: int
    inline: bool  # False → portal-upload + URL+hash event

    def describe(self) -> str:
        mode = "inline-chunked" if self.inline else "portal-upload"
        return f"{self.media_type}/{self.route}: ≤{self.max_bytes}B, {mode}"


def plan_media(media_type: str, byte_len: int, route: str) -> MediaPlan:
    if media_type not in LADDER or route not in ("internet", "mesh", "lora"):
        raise ValueError("unknown media type or route")
    max_bytes, dim = LADDER[media_type][route]
    if byte_len > max_bytes:
        raise ValueError(f"{media_type} {byte_len}B exceeds {route} cap {max_bytes}B — compress more")
    return MediaPlan(media_type, route, max_bytes, dim, inline=byte_len <= INLINE_THRESHOLD)


@dataclass
class MediaManifest:
    mime: str
    byte_len: int
    sha256: str
    chunks: int
    chunk_bytes: int
    dims: list | None = None
    dur_ms: int | None = None
    thumb_b64: str = ""

    def to_json(self) -> str:
        return json.dumps({"v": 1, "mime": self.mime, "bytes": self.byte_len,
                           "sha256": self.sha256, "chunks": self.chunks,
                           "chunk_bytes": self.chunk_bytes, "dims": self.dims,
                           "dur_ms": self.dur_ms, "thumb": self.thumb_b64},
                          separators=(",", ":"))

    @classmethod
    def from_json(cls, raw: str) -> "MediaManifest":
        d = json.loads(raw)
        return cls(d["mime"], d["bytes"], d["sha256"], d["chunks"], d["chunk_bytes"],
                   d.get("dims"), d.get("dur_ms"), d.get("thumb", ""))


def build_manifest(data: bytes, mime: str, chunk_bytes: int = 90,
                   dims: list | None = None, dur_ms: int | None = None,
                   thumb: bytes = b"") -> tuple[MediaManifest, list[bytes]]:
    """Chunk data + manifest. chunk_bytes=90 → fits MORP-PKT v1 180B frames
    (90B header + 90B chunk). Chunk 0 carries this manifest (BLOSSOM_CHUNK)."""
    chunks = [data[i:i + chunk_bytes] for i in range(0, len(data), chunk_bytes)] or [b""]
    m = MediaManifest(mime=mime, byte_len=len(data),
                      sha256=hashlib.sha256(data).hexdigest(),
                      chunks=len(chunks), chunk_bytes=chunk_bytes,
                      dims=dims, dur_ms=dur_ms,
                      thumb_b64=base64.b64encode(thumb).decode() if thumb else "")
    return m, chunks


def verify_chunks(manifest: MediaManifest, chunks: list[bytes]) -> bytes:
    data = b"".join(chunks)
    if len(data) != manifest.byte_len:
        raise ValueError("media length mismatch")
    if hashlib.sha256(data).hexdigest() != manifest.sha256:
        raise ValueError("media hash mismatch")
    return data
