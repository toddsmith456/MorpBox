"""MORP NERD onion packets. Spec §3.

Per-hop BOX: ephemeral X25519 → HKDF-SHA256(ecdh || 'morp-nerd-hop-v1')
→ ChaCha20-Poly1305. Wire per layer: eph_pub(32) || nonce(12) || ct.

Packet: ver(1)=0x01 || path_len(1) || L1
Layer plaintext: inner_len u32 || inner_blob || next_hop(8)  (zeros = EXIT)
Inner request: PAD(u32 len || InnerRequest JSON) to size tier.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import struct
import time
from dataclasses import dataclass, field

from nacl.bindings import (crypto_box_keypair, crypto_scalarmult,
                           crypto_aead_chacha20poly1305_ietf_encrypt,
                           crypto_aead_chacha20poly1305_ietf_decrypt)

VERSION = 0x01
HOP_DOMAIN = b"morp-nerd-hop-v1"
NEXT_NONE = b"\x00" * 8
PAD_TIERS = (512, 2048, 8192)


# ---------------------------------------------------------------- primitives
def hkdf_sha256(ikm: bytes, info: bytes, length: int = 32) -> bytes:
    prk = hmac.new(b"\x00" * 32, ikm, hashlib.sha256).digest()
    out, counter, prev = b"", 1, b""
    while len(out) < length:
        prev = hmac.new(prk, prev + info + bytes([counter]), hashlib.sha256).digest()
        out += prev
        counter += 1
    return out[:length]


def box_seal(recipient_pub: bytes, plaintext: bytes) -> bytes:
    eph_pub, eph_priv = crypto_box_keypair()
    shared = crypto_scalarmult(eph_priv, recipient_pub)
    key = hkdf_sha256(shared, HOP_DOMAIN)
    nonce = secrets.token_bytes(12)
    ct = crypto_aead_chacha20poly1305_ietf_encrypt(plaintext, b"", nonce, key)
    return eph_pub + nonce + ct


def box_open(recipient_priv: bytes, blob: bytes) -> bytes:
    if len(blob) < 32 + 12 + 16:
        raise ValueError("layer too short")
    eph_pub, nonce, ct = blob[:32], blob[32:44], blob[44:]
    shared = crypto_scalarmult(recipient_priv, eph_pub)
    key = hkdf_sha256(shared, HOP_DOMAIN)
    return crypto_aead_chacha20poly1305_ietf_decrypt(ct, b"", nonce, key)


def pad_to_tier(data: bytes) -> bytes:
    inner = struct.pack(">I", len(data)) + data
    for tier in PAD_TIERS:
        if len(inner) <= tier:
            return inner + b"\x00" * (tier - len(inner))
    raise ValueError(f"payload {len(data)}B exceeds max tier {PAD_TIERS[-1]}B")


def unpad(padded: bytes) -> bytes:
    (n,) = struct.unpack(">I", padded[:4])
    if 4 + n > len(padded):
        raise ValueError("bad padding length")
    return padded[4:4 + n]


# ---------------------------------------------------------------- inner request
@dataclass
class InnerRequest:
    op: str  # nostr-publish|dm-deliver|group-deliver|media-upload|ln-send|xmr-bcast|mesh-deliver
    payload_b64: str
    relays: list = field(default_factory=list)
    req_id: str = ""
    ts: int = 0
    reply: dict | None = None

    def __post_init__(self) -> None:
        self.req_id = self.req_id or secrets.token_hex(8)
        self.ts = self.ts or int(time.time())

    def to_bytes(self) -> bytes:
        return json.dumps({"v": 1, "req_id": self.req_id, "ts": self.ts, "op": self.op,
                           "relays": self.relays, "payload": self.payload_b64,
                           "reply": self.reply},
                          separators=(",", ":")).encode()

    @classmethod
    def from_bytes(cls, raw: bytes) -> "InnerRequest":
        d = json.loads(unpad(raw).decode())
        if d.get("v") != 1:
            raise ValueError("unsupported inner version")
        return cls(op=d["op"], payload_b64=d["payload"], relays=d.get("relays", []),
                   req_id=d["req_id"], ts=d["ts"], reply=d.get("reply"))

    @property
    def payload(self) -> bytes:
        return base64.b64decode(self.payload_b64)


def inner_request_bytes(op: str, payload: bytes, relays: list | None = None,
                        reply: dict | None = None) -> bytes:
    req = InnerRequest(op=op, payload_b64=base64.b64encode(payload).decode(),
                       relays=relays or [], reply=reply)
    return pad_to_tier(req.to_bytes())


# ---------------------------------------------------------------- onion build / peel
def _wrap_layer(inner_blob: bytes, next_hop: bytes, hop_pub: bytes) -> bytes:
    plain = struct.pack(">I", len(inner_blob)) + inner_blob + next_hop
    return box_seal(hop_pub, plain)


def _peel_layer(my_priv: bytes, blob: bytes) -> tuple[bytes, bytes]:
    plain = box_open(my_priv, blob)
    (n,) = struct.unpack(">I", plain[:4])
    inner = plain[4:4 + n]
    next_hop = plain[4 + n:4 + n + 8]
    if len(inner) != n or len(next_hop) != 8:
        raise ValueError("corrupt layer framing")
    return inner, next_hop


def build_packet(inner_padded: bytes, path_pubs: list[bytes], path_hops: list[bytes]) -> bytes:
    """Layer inner→portal outward. path_pubs[0] = first relay, [-1] = portal."""
    if len(path_pubs) != len(path_hops) or not (2 <= len(path_pubs) <= 5):
        raise ValueError("path must be 2..5 hops with matching pubs+hop-ids")
    # Wrap from the inside out: portal layer first, first-relay layer last.
    blob, nxt = inner_padded, NEXT_NONE
    for i in range(len(path_pubs) - 1, -1, -1):
        blob = _wrap_layer(blob, nxt, path_pubs[i])
        nxt = path_hops[i]
    return bytes([VERSION, len(path_pubs)]) + blob


@dataclass
class PeelResult:
    kind: str  # "forward" | "exit"
    next_hop: bytes  # 8 bytes; zeros when exit
    inner: bytes     # forward: next packet bytes; exit: padded InnerRequest
    remaining: int


def peel_packet(packet: bytes, my_priv: bytes) -> PeelResult:
    if len(packet) < 2 or packet[0] != VERSION:
        raise ValueError("bad packet version")
    remaining = packet[1]
    if remaining < 1:
        raise ValueError("empty path")
    inner, next_hop = _peel_layer(my_priv, packet[2:])
    if next_hop == NEXT_NONE:
        return PeelResult("exit", next_hop, inner, 0)
    return PeelResult("forward", next_hop, bytes([VERSION, remaining - 1]) + inner, remaining - 1)


def hop_id_for(dev_pub: bytes) -> bytes:
    return hashlib.sha256(b"morp-hop-v1" + dev_pub).digest()[:8]
