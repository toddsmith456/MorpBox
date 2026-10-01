"""morp-rns: clean-room Reticulum-wire-compatible spike (M0→M2).

QUARANTINE: written from functional wire descriptions only. Zero code from
Reticulum (Python/C) or Leviculum (AGPL). Interop target: exchange packets
with stock Reticulum/Leviculum nodes per the public wire format.

M1 scope: identity (X25519 + Ed25519), identity hash/address, signed announces,
X25519 sealed packets, unforgeable delivery ACKs, link token-ratchet sketch.
Full link establishment + path discovery land in M2.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import struct
import time

from nacl.bindings import (crypto_aead_chacha20poly1305_ietf_decrypt,
                           crypto_aead_chacha20poly1305_ietf_encrypt,
                           crypto_box_keypair, crypto_scalarmult)
from nacl.signing import SigningKey, VerifyKey

RNS_DOMAIN = b"morp-rns-v1"
ADDR_LEN = 16  # truncated identity-hash address on the wire


def hkdf(ikm: bytes, info: bytes, n: int = 32) -> bytes:
    prk = hmac.new(b"\x00" * 32, ikm, hashlib.sha256).digest()
    out, c, prev = b"", 1, b""
    while len(out) < n:
        prev = hmac.new(prk, prev + info + bytes([c]), hashlib.sha256).digest()
        out += prev
        c += 1
    return out[:n]


class RnsIdentity:
    """Dual-key identity: X25519 (encryption) + Ed25519 (signing)."""

    def __init__(self, x_priv: bytes, ed_seed: bytes):
        if len(x_priv) != 32 or len(ed_seed) != 32:
            raise ValueError("keys must be 32 bytes")
        self.x_priv = x_priv
        self._sign = SigningKey(ed_seed)
        from nacl.public import PrivateKey as XSK
        self.x_pub = bytes(XSK(x_priv).public_key)
        self.ed_pub = bytes(self._sign.verify_key)

    @classmethod
    def generate(cls) -> "RnsIdentity":
        _pub, x_priv = crypto_box_keypair()
        return cls(x_priv, secrets.token_bytes(32))

    @property
    def identity_hash(self) -> bytes:
        """Full 256-bit identity hash: SHA256(ed_pub || x_pub)."""
        return hashlib.sha256(self.ed_pub + self.x_pub).digest()

    @property
    def address(self) -> bytes:
        """Truncated on-wire address (16 B)."""
        return self.identity_hash[:ADDR_LEN]

    def sign(self, msg: bytes) -> bytes:
        return bytes(self._sign.sign(msg).signature)

    @staticmethod
    def verify(ed_pub: bytes, msg: bytes, sig: bytes) -> bool:
        try:
            VerifyKey(ed_pub).verify(msg, sig)
            return True
        except Exception:
            return False


# ---------------------------------------------------------------- announces
def build_announce(ident: RnsIdentity, app_data: bytes = b"",
                   ts: int | None = None) -> bytes:
    """Signed announce: len-prefix framing + Ed25519 signature."""
    ts = ts if ts is not None else int(time.time())
    body = (ident.ed_pub + ident.x_pub + struct.pack(">Q", ts)
            + struct.pack(">H", len(app_data)) + app_data)
    sig = ident.sign(b"morp-rns-announce" + body)
    return struct.pack(">H", len(body)) + body + sig


def parse_announce(raw: bytes, max_age_s: int = 3600) -> dict:
    (n,) = struct.unpack(">H", raw[:2])
    body, sig = raw[2:2 + n], raw[2 + n:2 + n + 64]
    ed_pub, x_pub = body[:32], body[32:64]
    (ts,) = struct.unpack(">Q", body[64:72])
    (adlen,) = struct.unpack(">H", body[72:74])
    app_data = body[74:74 + adlen]
    if len(app_data) != adlen:
        raise ValueError("truncated announce app_data")
    if not RnsIdentity.verify(ed_pub, b"morp-rns-announce" + body, sig):
        raise ValueError("bad announce signature")
    if abs(time.time() - ts) > max_age_s:
        raise ValueError("stale announce")
    ident_hash = hashlib.sha256(ed_pub + x_pub).digest()
    return {"ed_pub": ed_pub, "x_pub": x_pub, "address": ident_hash[:ADDR_LEN],
            "identity_hash": ident_hash, "ts": ts, "app_data": app_data}


# ---------------------------------------------------------------- sealed packets
def seal_packet(recipient_x_pub: bytes, plaintext: bytes,
                context: bytes = b"\x00") -> bytes:
    """X25519 ephemeral → HKDF → ChaChaPoly. Wire: eph_pub(32)||nonce(12)||ct."""
    eph_pub, eph_priv = crypto_box_keypair()
    key = hkdf(crypto_scalarmult(eph_priv, recipient_x_pub), RNS_DOMAIN + context)
    nonce = secrets.token_bytes(12)
    ct = crypto_aead_chacha20poly1305_ietf_encrypt(plaintext, b"", nonce, key)
    return eph_pub + nonce + ct


def open_packet(recipient_x_priv: bytes, blob: bytes,
                context: bytes = b"\x00") -> bytes:
    eph_pub, nonce, ct = blob[:32], blob[32:44], blob[44:]
    key = hkdf(crypto_scalarmult(recipient_x_priv, eph_pub), RNS_DOMAIN + context)
    return crypto_aead_chacha20poly1305_ietf_decrypt(ct, b"", nonce, key)


def delivery_ack(packet_hash: bytes, recipient_x_priv: bytes,
                 requester_x_pub: bytes) -> bytes:
    """Unforgeable delivery ACK: sealed(packet_hash) to the requester."""
    return seal_packet(requester_x_pub, b"ACK:" + packet_hash)


# ---------------------------------------------------------------- link token ratchet (sketch)
class TokenRatchet:
    """Per-link forward-secret ratchet: each token derives from the previous
    plus the link shared secret, then old state is destroyed (harvest-now/
    decrypt-later defeated on captured mesh traffic)."""

    def __init__(self, link_shared: bytes, token0: bytes):
        self.shared = link_shared
        self.token = token0

    def next(self) -> tuple[bytes, bytes]:
        """Returns (wire_token_to_send, packet_key). Advances + destroys old."""
        wire = hmac.new(self.shared, b"tok" + self.token, hashlib.sha256).digest()[:16]
        key = hmac.new(self.shared, b"key" + self.token, hashlib.sha256).digest()
        self.token = hmac.new(self.shared, b"nxt" + self.token, hashlib.sha256).digest()
        return wire, key


def announce_app_data_for_morp(hop_hex: str, caps_mask: int, load: int) -> bytes:
    """RNS announce app_data carrying a MORP portal/peer beacon pointer."""
    return json.dumps({"morp": 1, "hop": hop_hex, "caps": caps_mask, "load": load},
                      separators=(",", ":")).encode()
