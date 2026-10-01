"""Blossom media protocol (BUD-01/02/03/11) reference. Research §6.4.

- BUD-01: GET/HEAD https://server/<sha256> (+Range)
- BUD-02: PUT /upload (also /mirror), returns BlobDescriptor
- BUD-03: kind 10063 server list event
- BUD-11: kind 24242 Nostr authorization events (upload/delete).
Mesh legs carry BLOSSOM_CHUNK = media.py manifest (chunk 0) + raw chunks;
a P2 portal reassembles and performs the HTTP PUT on behalf of the author.
"""
from __future__ import annotations

import base64
import hashlib
import json
import time
import urllib.request

from .nostr import Event

KIND_BLOSSOM_SERVERS = 10063   # BUD-03
KIND_BLOSSOM_AUTH = 24242      # BUD-11
KIND_MORP_MEDIA_DESC = 10082   # MORP media descriptor (Blossom sha metadata)


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def blob_url(server: str, sha_hex: str) -> str:
    return server.rstrip("/") + "/" + sha_hex


def build_server_list(author, servers: list[str]) -> Event:
    """BUD-03 kind 10063 (replaceable): one `server` tag per URL."""
    from .identity import NostrIdentity  # local import: typing only
    assert isinstance(author, NostrIdentity)
    tags = [["server", s] for s in servers]
    return Event(pubkey=author.pubkey_hex, created_at=int(time.time()),
                 kind=KIND_BLOSSOM_SERVERS, tags=tags, content="").sign(author)


def parse_server_list(evt: Event) -> list[str]:
    if evt.kind != KIND_BLOSSOM_SERVERS:
        raise ValueError("not a blossom server list")
    return [t[1] for t in evt.tags if len(t) >= 2 and t[0] == "server"]


def build_upload_auth(author, sha_hex: str, action: str = "Upload blob",
                      ttl_s: int = 600) -> Event:
    """BUD-11 kind 24242 authorization token for one blob."""
    from .identity import NostrIdentity
    assert isinstance(author, NostrIdentity)
    now = int(time.time())
    tags = [["t", "upload"], ["x", sha_hex],
            ["expiration", str(now + ttl_s)]]
    return Event(pubkey=author.pubkey_hex, created_at=now,
                 kind=KIND_BLOSSOM_AUTH, tags=tags, content=action).sign(author)


def verify_upload_auth(evt: Event, want_sha: str) -> bool:
    if evt.kind != KIND_BLOSSOM_AUTH or not evt.verify():
        return False
    tags = {t[0]: t[1] for t in evt.tags if len(t) >= 2}
    if tags.get("x") != want_sha or tags.get("t") != "upload":
        return False
    try:
        if int(tags.get("expiration", "0")) < time.time():
            return False
    except ValueError:
        return False
    return True


def auth_header(evt: Event) -> str:
    """HTTP Authorization value: 'Nostr <base64(event json)>'."""
    return "Nostr " + base64.b64encode(evt.to_json().encode()).decode()


def build_media_descriptor(author, sha_hex: str, mime: str, byte_len: int,
                           blossom_url: str, dims: list | None = None,
                           dur_ms: int | None = None) -> Event:
    """MORP kind 10082: links a Blossom blob to its metadata (NIP-94 imeta style)."""
    from .identity import NostrIdentity
    assert isinstance(author, NostrIdentity)
    imeta = [f"url {blossom_url}", f"m {mime}", f"x {sha_hex}", f"size {byte_len}"]
    if dims:
        imeta.append(f"dim {dims[0]}x{dims[1]}")
    if dur_ms:
        imeta.append(f"dur {dur_ms}")
    tags = [["imeta"] + imeta, ["x", sha_hex]]
    return Event(pubkey=author.pubkey_hex, created_at=int(time.time()),
                 kind=KIND_MORP_MEDIA_DESC, tags=tags, content=mime).sign(author)


class BlossomClient:
    """Minimal BUD-01/02 HTTP client (urllib; Android mirrors with okHttp)."""

    def __init__(self, timeout_s: float = 30.0):
        self.timeout = timeout_s

    def head(self, server: str, sha_hex: str) -> dict:
        req = urllib.request.Request(blob_url(server, sha_hex), method="HEAD")
        with urllib.request.urlopen(req, timeout=self.timeout) as r:
            return dict(r.headers.items())

    def get(self, server: str, sha_hex: str) -> bytes:
        with urllib.request.urlopen(blob_url(server, sha_hex), timeout=self.timeout) as r:
            data = r.read()
        if sha256_hex(data) != sha_hex:
            raise ValueError("blossom blob hash mismatch")
        return data

    def upload(self, server: str, data: bytes, mime: str, auth: Event) -> dict:
        sha = sha256_hex(data)
        if not verify_upload_auth(auth, sha):
            raise ValueError("bad blossom auth token")
        req = urllib.request.Request(server.rstrip("/") + "/upload", data=data, method="PUT",
                                     headers={"Content-Type": mime,
                                              "Authorization": auth_header(auth)})
        with urllib.request.urlopen(req, timeout=self.timeout) as r:
            return json.loads(r.read().decode())
