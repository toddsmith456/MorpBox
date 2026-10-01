"""Identities: Nostr (secp256k1) + transport (X25519) + binding attestation.

Spec: docs/MORP_NERD_SPEC_v0.1.md §1.
Production Android mirrors this with bitcoin-kmp (BIP-340) + Tink (X25519).
"""
from __future__ import annotations

import hashlib
import json
import secrets
import time
from dataclasses import dataclass, field
from typing import Tuple
from urllib.parse import urlparse, parse_qs

import bech32 as _bech32lib  # type: ignore
from coincurve import PrivateKey, PublicKeyXOnly
from nacl.bindings import crypto_box_keypair
from nacl.public import PrivateKey as XPrivateKey

HOP_DOMAIN = b"morp-hop-v1"


# ---------------------------------------------------------------- bech32 helpers
def _to_bech32(hrp: str, data: bytes) -> str:
    five = _bech32lib.convertbits(data, 8, 5, True)
    assert five is not None
    return _bech32lib.bech32_encode(hrp, five)


def _from_bech32(s: str, expect_hrp: str) -> bytes:
    hrp, five = _bech32lib.bech32_decode(s)
    if hrp != expect_hrp or five is None:
        raise ValueError(f"bad bech32 ({s[:12]}...), want hrp {expect_hrp}")
    raw = _bech32lib.convertbits(five, 5, 8, False)
    assert raw is not None
    return bytes(raw)


# ---------------------------------------------------------------- Nostr identity
@dataclass
class NostrIdentity:
    """secp256k1 keypair. Signing: BIP-340 Schnorr when available.

    coincurve>=18 exposes sign_schnorr/verify_schnorr; if the linked libsecp256k1
    lacks it we fall back to ECDSA for local tests ONLY (flagged) — production
    MUST use BIP-340 (NIP-01).
    """
    sk: bytes  # 32 bytes secret
    _priv: PrivateKey = field(init=False, repr=False)

    def __post_init__(self) -> None:
        if len(self.sk) != 32:
            raise ValueError("nostr sk must be 32 bytes")
        self._priv = PrivateKey(self.sk)

    @classmethod
    def generate(cls) -> "NostrIdentity":
        return cls(secrets.token_bytes(32))

    @classmethod
    def from_nsec(cls, nsec: str) -> "NostrIdentity":
        return cls(_from_bech32(nsec, "nsec"))

    @property
    def pubkey_hex(self) -> str:
        # x-only pubkey (Nostr / BIP-340)
        return self._priv.public_key.format(compressed=False)[1:33].hex()

    @property
    def npub(self) -> str:
        return _to_bech32("npub", bytes.fromhex(self.pubkey_hex))

    @property
    def nsec(self) -> str:
        return _to_bech32("nsec", self.sk)

    def sign_bip340(self, msg32: bytes) -> bytes:
        """Sign 32-byte hash, BIP-340 Schnorr. Returns 64-byte sig."""
        if len(msg32) != 32:
            raise ValueError("BIP-340 signs exactly 32 bytes")
        if hasattr(self._priv, "sign_schnorr"):
            return self._priv.sign_schnorr(msg32)
        # TEST-ONLY fallback (see class docstring)
        return self._priv.sign_recoverable(msg32, hasher=None)[:64].ljust(64, b"\0")[:64]

    def uses_schnorr(self) -> bool:
        return hasattr(self._priv, "sign_schnorr")

    def verify(self, msg32: bytes, sig: bytes) -> bool:
        try:
            return PublicKeyXOnly(bytes.fromhex(self.pubkey_hex)).verify(sig, msg32)
        except Exception:
            return False


def verify_schnorr_standalone(pubkey_xonly_hex: str, msg32: bytes, sig: bytes) -> bool:
    """Verify with x-only pubkey only (what portals do)."""
    try:
        return PublicKeyXOnly(bytes.fromhex(pubkey_xonly_hex)).verify(sig, msg32)
    except Exception:
        return False


# ---------------------------------------------------------------- Transport identity
@dataclass
class TransportIdentity:
    """X25519 device keypair. Onion layers are addressed to dev_pub."""
    priv: bytes  # 32 bytes seed/private
    pub: bytes   # 32 bytes

    @classmethod
    def generate(cls) -> "TransportIdentity":
        pub, priv = crypto_box_keypair()
        return cls(priv=priv, pub=pub)

    @classmethod
    def from_priv(cls, priv: bytes) -> "TransportIdentity":
        return cls(priv=priv, pub=bytes(XPrivateKey(priv).public_key))

    @property
    def hop_id(self) -> bytes:
        """8-byte unlinkable routing id: SHA256('morp-hop-v1' || dev_pub)[0:8]."""
        return hashlib.sha256(HOP_DOMAIN + self.pub).digest()[:8]

    @property
    def hop_hex(self) -> str:
        return self.hop_id.hex()


# ---------------------------------------------------------------- Binding attestation
@dataclass
class BindingAttestation:
    npub: str
    dev_pub_hex: str
    ts: int
    caps: dict
    sig_hex: str = ""

    def canonical(self) -> bytes:
        return json.dumps(
            {"v": 1, "npub": self.npub, "dev_pub": self.dev_pub_hex,
             "ts": self.ts, "caps": self.caps},
            separators=(",", ":"), sort_keys=True,
        ).encode()

    @classmethod
    def create(cls, nostr: NostrIdentity, transport: TransportIdentity,
               caps: dict | None = None) -> "BindingAttestation":
        a = cls(npub=nostr.npub, dev_pub_hex=transport.pub.hex(),
                ts=int(time.time()), caps=caps or {})
        digest = hashlib.sha256(a.canonical()).digest()
        a.sig_hex = nostr.sign_bip340(digest).hex()
        return a

    def verify(self) -> bool:
        digest = hashlib.sha256(self.canonical()).digest()
        raw = _from_bech32(self.npub, "npub")
        return verify_schnorr_standalone(raw.hex(), digest, bytes.fromhex(self.sig_hex))


# ---------------------------------------------------------------- Device (both halves)
@dataclass
class DeviceIdentity:
    nostr: NostrIdentity
    transport: TransportIdentity

    @classmethod
    def generate(cls) -> "DeviceIdentity":
        return cls(NostrIdentity.generate(), TransportIdentity.generate())

    def attest(self, caps: dict | None = None) -> BindingAttestation:
        return BindingAttestation.create(self.nostr, self.transport, caps)


# ---------------------------------------------------------------- bunker url (ngit contributor flow)
def parse_bunker_url(url: str) -> Tuple[str, str, str]:
    """Validate bunker://<pubkey>?relay=<wss>&secret=<s>. Returns (pubkey, relay, secret).

    Used for the gitworkshop.dev contributor identity, never for chat keys.
    Never log the secret.
    """
    u = urlparse(url)
    if u.scheme != "bunker":
        raise ValueError("not a bunker:// URL")
    q = parse_qs(u.query)
    relay = (q.get("relay") or [""])[0]
    secret = (q.get("secret") or [""])[0]
    if not u.netloc or not relay or not secret:
        raise ValueError("bunker URL needs <pubkey>, relay and secret")
    return u.netloc, relay, secret
