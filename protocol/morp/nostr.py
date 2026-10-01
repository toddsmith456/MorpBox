"""Nostr events: kind-1/reply/repost/quote + NIP-17/59-style gift wrap.

Spec refs: NIP-01 (event id/sig), NIP-10 (reply e/p tags), NIP-18 (quote q tag),
NIP-17/59 (seal kind 13 in wrap kind 1059). Production seal encryption is
NIP-44 v2 + ML-KEM-768 hybrid (see ARCHITECTURE §3.6); this reference module
implements a transport-key-sealed prototype with identical *framing* so paths,
sizes and portal behavior match.
"""
from __future__ import annotations

import base64
import hashlib
import json
import secrets
import time
from dataclasses import dataclass, field

from nacl.public import PrivateKey as XPrivateKey, PublicKey as XPublicKey, SealedBox

from .identity import NostrIdentity, TransportIdentity, verify_schnorr_standalone


# ---------------------------------------------------------------- events
@dataclass
class Event:
    pubkey: str  # x-only hex
    created_at: int
    kind: int
    tags: list
    content: str
    id: str = ""
    sig: str = ""

    def canonical(self) -> bytes:
        return json.dumps(
            [0, self.pubkey, self.created_at, self.kind, self.tags, self.content],
            separators=(",", ":"), ensure_ascii=False,
        ).encode()

    def compute_id(self) -> str:
        self.id = hashlib.sha256(self.canonical()).hexdigest()
        return self.id

    def sign(self, ident: NostrIdentity) -> "Event":
        if ident.pubkey_hex != self.pubkey:
            raise ValueError("signer pubkey != event pubkey")
        self.compute_id()
        self.sig = ident.sign_bip340(bytes.fromhex(self.id)).hex()
        return self

    def verify(self) -> bool:
        if not self.id or not self.sig:
            return False
        if hashlib.sha256(self.canonical()).hexdigest() != self.id:
            return False
        return verify_schnorr_standalone(self.pubkey, bytes.fromhex(self.id),
                                         bytes.fromhex(self.sig))

    def to_json(self) -> str:
        return json.dumps({"id": self.id, "pubkey": self.pubkey,
                           "created_at": self.created_at, "kind": self.kind,
                           "tags": self.tags, "content": self.content,
                           "sig": self.sig}, separators=(",", ":"))

    @classmethod
    def from_json(cls, raw: str | bytes | dict) -> "Event":
        d = json.loads(raw) if isinstance(raw, (str, bytes)) else raw
        return cls(pubkey=d["pubkey"], created_at=d["created_at"], kind=d["kind"],
                   tags=d["tags"], content=d["content"],
                   id=d.get("id", ""), sig=d.get("sig", ""))


def make_kind1(author: NostrIdentity, content: str, tags: list | None = None,
               created_at: int | None = None) -> Event:
    return Event(pubkey=author.pubkey_hex, created_at=created_at or int(time.time()),
                 kind=1, tags=tags or [], content=content).sign(author)


def make_reply(author: NostrIdentity, content: str, reply_to: Event,
               relay_hint: str = "") -> Event:
    """NIP-10 reply: e-tag (root+reply simplified) + p-tag."""
    tags = [["e", reply_to.id, relay_hint, "reply"],
            ["p", reply_to.pubkey, relay_hint]]
    return Event(pubkey=author.pubkey_hex, created_at=int(time.time()),
                 kind=1, tags=tags, content=content).sign(author)


def make_repost(author: NostrIdentity, target: Event, relay_hint: str = "") -> Event:
    """NIP-18 kind-6 repost."""
    tags = [["e", target.id, relay_hint], ["p", target.pubkey, relay_hint]]
    return Event(pubkey=author.pubkey_hex, created_at=int(time.time()),
                 kind=6, tags=tags, content=target.to_json()).sign(author)


def make_quote(author: NostrIdentity, content: str, target: Event,
               relay_hint: str = "") -> Event:
    """NIP-18 quote post: kind-1 with q-tag + nevent-style mention in content."""
    tags = [["q", target.id, relay_hint, target.pubkey], ["p", target.pubkey]]
    body = f"{content}\nnostr:{target.id[:16]}…" if content else f"nostr:{target.id}"
    return Event(pubkey=author.pubkey_hex, created_at=int(time.time()),
                 kind=1, tags=tags, content=body).sign(author)


# ---------------------------------------------------------------- gift wrap (prototype seal)
@dataclass
class Seal:
    """Inner sealed rumor. Production: NIP-44 v2 + ML-KEM hybrid plaintext;
    reference: sealed-box to recipient transport pub (framing-identical sizes TBD)."""
    sender_nostr: str  # hex
    recipient_nostr: str  # hex
    ts: int
    plaintext: str
    room: str = ""  # group room id, empty = 1:1 DM
    kem_ct: str = ""  # hex ML-KEM ciphertext (production); empty in reference

    def to_json(self) -> str:
        return json.dumps({"v": 1, "from": self.sender_nostr, "to": self.recipient_nostr,
                           "ts": self.ts, "room": self.room,
                           "kem_ct": self.kem_ct, "pt": self.plaintext},
                          separators=(",", ":"))

    @classmethod
    def from_json(cls, raw: str) -> "Seal":
        d = json.loads(raw)
        return cls(sender_nostr=d["from"], recipient_nostr=d["to"], ts=d["ts"],
                   plaintext=d["pt"], room=d.get("room", ""), kem_ct=d.get("kem_ct", ""))


def seal_to_recipient(seal: Seal, recipient_transport_pub: bytes) -> bytes:
    """SealBox → recipient transport key. Portal/relays cannot open."""
    box = SealedBox(XPublicKey(recipient_transport_pub))
    return box.encrypt(seal.to_json().encode())


def open_seal(ct: bytes, recipient_transport: TransportIdentity) -> Seal:
    box = SealedBox(XPrivateKey(recipient_transport.priv))
    return Seal.from_json(box.decrypt(ct).decode())


def gift_wrap(sealed_ct: bytes, recipient_nostr_hex: str,
              wrap_key: NostrIdentity | None = None) -> Event:
    """NIP-59 wrap: kind 1059 signed by ephemeral random key."""
    wrap_key = wrap_key or NostrIdentity.generate()
    tags = [["p", recipient_nostr_hex]]
    return Event(pubkey=wrap_key.pubkey_hex, created_at=int(time.time()) - secrets.randbelow(600),
                 kind=1059, tags=tags,
                 content=base64.b64encode(sealed_ct).decode()).sign(wrap_key)


def gift_wrap_for_dm(sender_nostr: NostrIdentity, sender_transport: TransportIdentity,
                     recipient_nostr_hex: str, recipient_transport_pub: bytes,
                     plaintext: str, room: str = "") -> Event:
    seal = Seal(sender_nostr=sender_nostr.pubkey_hex, recipient_nostr=recipient_nostr_hex,
                ts=int(time.time()), plaintext=plaintext, room=room)
    return gift_wrap(seal_to_recipient(seal, recipient_transport_pub), recipient_nostr_hex)


# ---------------------------------------------------------------- NIP-17 v2 chat rumors (kinds 14/15)
GROUP_MEMBER_CAP = 12  # research §6.5: NIP-17 per-recipient fan-out limit


def make_chat_rumor(sender_hex: str, content: str, recipient_hex: str,
                    reply_to: str = "", relay_hint: str = "") -> dict:
    """Unsigned kind-14 rumor (goes INSIDE the kind-13 seal)."""
    tags = [["p", recipient_hex, relay_hint]]
    if reply_to:
        tags.append(["e", reply_to, relay_hint, "reply"])
    return {"kind": 14, "pubkey": sender_hex, "created_at": int(time.time()),
            "tags": tags, "content": content}


def make_file_rumor(sender_hex: str, file_url: str, mime: str, sha_hex: str,
                    recipient_hex: str, relay_hint: str = "") -> dict:
    """Unsigned kind-15 file-message rumor (Blossom URL + hash)."""
    tags = [["p", recipient_hex, relay_hint],
            ["file", file_url, mime], ["x", sha_hex]]
    return {"kind": 15, "pubkey": sender_hex, "created_at": int(time.time()),
            "tags": tags, "content": mime}


def seal_group_message(sender_nostr: NostrIdentity, sender_transport: TransportIdentity,
                       members: list[tuple[str, bytes]], plaintext: str,
                       room: str) -> list[Event]:
    """NIP-17 group fan-out: one seal+wrap per member (cap 12)."""
    if len(members) > GROUP_MEMBER_CAP:
        raise ValueError(f"group cap is {GROUP_MEMBER_CAP}")
    rumor = json.dumps({"kind": 14, "room": room, "content": plaintext,
                        "from": sender_nostr.pubkey_hex, "ts": int(time.time())},
                       separators=(",", ":"))
    wraps = []
    for nostr_hex, transport_pub in members:
        seal = Seal(sender_nostr=sender_nostr.pubkey_hex, recipient_nostr=nostr_hex,
                    ts=int(time.time()), plaintext=rumor, room=room)
        wraps.append(gift_wrap(seal_to_recipient(seal, transport_pub), nostr_hex))
    return wraps


def open_gift_wrap(wrap: Event, recipient_nostr: NostrIdentity,
                   recipient_transport: TransportIdentity) -> Seal:
    want_p = recipient_nostr.pubkey_hex
    if not any(t and t[0] == "p" and t[1] == want_p for t in wrap.tags):
        raise ValueError("wrap not addressed to this recipient")
    return open_seal(base64.b64decode(wrap.content), recipient_transport)
