"""Deferred payments: PAYMENT instructions + kind 10133/10081 helpers.

Research §7: mesh payments are signed, NON-REVOCABLE instructions (nonce +
72 h expiry) executed by the payer's own device at first internet contact.
XMR profile targets use kind 10133 (NIP-A3 / Ditto / Amethyst):
  ["payto", "monero", "<subaddress>"] and ["payto", "xmr", "<subaddress>"]
MORP payment events use kind 10081.
"""
from __future__ import annotations

import hashlib
import json
import secrets
import time
from dataclasses import dataclass

from .nostr import Event

KIND_XMR_PAYTO = 10133    # NIP-A3 profile payment target
KIND_MORP_PAYMENT = 10081  # MORP payment event / instruction envelope
INSTRUCTION_TTL_S = 72 * 3600


@dataclass
class PaymentInstruction:
    asset: str          # "ln" | "xmr"
    payto: str          # bolt11 invoice | monero subaddress
    amount_msat: int | None = None
    amount_atomic: int | None = None
    nonce: str = ""
    created: int = 0
    expires: int = 0
    memo: str = ""
    payer_nostr: str = ""
    sig_hex: str = ""   # payer nostr sig over canonical (non-revocable)

    def __post_init__(self) -> None:
        self.nonce = self.nonce or secrets.token_hex(8)
        self.created = self.created or int(time.time())
        self.expires = self.expires or (self.created + INSTRUCTION_TTL_S)

    def canonical(self) -> bytes:
        return json.dumps({"v": 1, "asset": self.asset, "payto": self.payto,
                           "msat": self.amount_msat, "atomic": self.amount_atomic,
                           "nonce": self.nonce, "created": self.created,
                           "expires": self.expires, "memo": self.memo,
                           "payer": self.payer_nostr},
                          separators=(",", ":"), sort_keys=True).encode()

    def sign(self, author) -> "PaymentInstruction":
        from .identity import NostrIdentity
        assert isinstance(author, NostrIdentity)
        self.payer_nostr = author.pubkey_hex
        digest = hashlib.sha256(self.canonical()).digest()
        self.sig_hex = author.sign_bip340(digest).hex()
        return self

    def verify(self) -> bool:
        from .identity import verify_schnorr_standalone
        if not self.sig_hex or not self.payer_nostr:
            return False
        if self.expires < time.time():
            return False
        digest = hashlib.sha256(self.canonical()).digest()
        return verify_schnorr_standalone(self.payer_nostr, digest,
                                         bytes.fromhex(self.sig_hex))

    @property
    def expired(self) -> bool:
        return self.expires < time.time()


def parse_payto_targets(evt: Event) -> dict[str, str]:
    """Extract {asset: address} from kind 10133 (or kind-0 content fallback)."""
    out: dict[str, str] = {}
    if evt.kind == KIND_XMR_PAYTO:
        for t in evt.tags:
            if len(t) >= 3 and t[0] == "payto" and t[1] in ("monero", "xmr"):
                out["xmr"] = t[2]
            elif len(t) >= 3 and t[0] == "payto" and t[1] in ("lightning", "ln", "bolt11"):
                out["ln"] = t[2]
    return out


def build_payto_list(author, xmr_subaddress: str | None = None,
                     lud16: str | None = None) -> Event:
    """Publish kind 10133 with the payer-discoverable targets (replaceable)."""
    from .identity import NostrIdentity
    assert isinstance(author, NostrIdentity)
    tags = []
    if xmr_subaddress:
        tags.append(["payto", "monero", xmr_subaddress])
    if lud16:
        tags.append(["payto", "lightning", lud16])
    return Event(pubkey=author.pubkey_hex, created_at=int(time.time()),
                 kind=KIND_XMR_PAYTO, tags=tags, content="").sign(author)
