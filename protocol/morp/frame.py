"""MORP-PKT v1 binary frames. Approved spec: research §6.1 + docs/MORP_PKT_v1.md.

header (90 bytes, all big-endian):
  magic(4) 0x4D4F5250 "MORP" | ver(1)=0x01 | ftype(1)
  next_hop(32)   # dev_pub of next relay; 32 zero bytes = flood/broadcast
  path_id(8)     # sha256(payload)[0:8] — correlation without identity
  ttl(1)         # default 8, decrement per forward, drop at 0
  flags(1)       # CHUNKED|ACK_REQ|PRIORITY|PORTAL_BOUND|FIRST|LAST
  msg_id(32)     # sha256(full reassembled payload)
  seq(2) | total(2) | chunk_len(2) | pkt_len(2)
  crc16(2)       # CCITT over header-after-magic + chunk (corruption detection)

No source field anywhere (M2 relay blindness). Payloads: onion DATA blobs,
ADV/PRQ/PRA JSON, ACK/CTRL structs, BLOSSOM_CHUNK bytes.
"""
from __future__ import annotations

import hashlib
import struct
import time

MAGIC = b"MORP"
VERSION = 0x01
HEADER_LEN = 90

# ftype (research §6.1)
F_ADV, F_PRQ, F_PRA, F_DATA, F_ACK, F_CTRL = 0x01, 0x02, 0x03, 0x04, 0x05, 0x06

# flags
FLAG_CHUNKED = 0x01
FLAG_ACK_REQ = 0x02
FLAG_PRIORITY = 0x04
FLAG_PORTAL_BOUND = 0x08
FLAG_FIRST = 0x10
FLAG_LAST = 0x20

FLOOD_NEXT = b"\x00" * 32
TTL_DEFAULT = 8


def crc16_ccitt(data: bytes, crc: int = 0xFFFF) -> int:
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def msg_id_for(payload: bytes) -> bytes:
    return hashlib.sha256(payload).digest()


def path_id_for(payload: bytes) -> bytes:
    return hashlib.sha256(payload).digest()[:8]


def frame_payload(payload: bytes, mtu: int, next_hop: bytes = FLOOD_NEXT,
                  ftype: int = F_DATA, ttl: int = TTL_DEFAULT,
                  base_flags: int = 0) -> list[bytes]:
    """Chunk payload into MORP-PKT v1 frames fitting mtu."""
    if len(next_hop) != 32:
        raise ValueError("next_hop must be 32 bytes")
    if mtu <= HEADER_LEN + 1:
        raise ValueError("MTU too small for MORP-PKT header")
    msg_id, path_id = msg_id_for(payload), path_id_for(payload)
    chunk_size = mtu - HEADER_LEN
    chunks = [payload[i:i + chunk_size] for i in range(0, len(payload), chunk_size)] or [b""]
    total = len(chunks)
    out = []
    for seq, ch in enumerate(chunks):
        flags = base_flags
        if total > 1:
            flags |= FLAG_CHUNKED
        if seq == 0:
            flags |= FLAG_FIRST
        if seq == total - 1:
            flags |= FLAG_LAST
        head = (MAGIC + bytes([VERSION, ftype]) + next_hop + path_id
                + bytes([ttl, flags]) + msg_id
                + struct.pack(">HHHH", seq, total, len(ch), HEADER_LEN + len(ch)))
        crc = crc16_ccitt(head[4:] + ch)
        out.append(head + struct.pack(">H", crc) + ch)
    return out


class Frame:
    __slots__ = ("ftype", "next_hop", "path_id", "ttl", "flags", "msg_id",
                 "seq", "total", "chunk")

    def __init__(self, ftype, next_hop, path_id, ttl, flags, msg_id, seq, total, chunk):
        self.ftype, self.next_hop, self.path_id = ftype, next_hop, path_id
        self.ttl, self.flags, self.msg_id = ttl, flags, msg_id
        self.seq, self.total, self.chunk = seq, total, chunk

    @property
    def is_flood(self) -> bool:
        return self.next_hop == FLOOD_NEXT


def parse_frame(raw: bytes) -> Frame:
    if len(raw) < HEADER_LEN or raw[:4] != MAGIC or raw[4] != VERSION:
        raise ValueError("bad MORP-PKT header")
    ftype = raw[5]
    next_hop, path_id = raw[6:38], raw[38:46]
    ttl, flags = raw[46], raw[47]
    msg_id = raw[48:80]
    seq, total, chunk_len, pkt_len = struct.unpack(">HHHH", raw[80:88])
    (want,) = struct.unpack(">H", raw[88:90])
    chunk = raw[90:90 + chunk_len]
    if len(raw) < pkt_len or len(chunk) != chunk_len:
        raise ValueError("truncated MORP-PKT frame")
    if crc16_ccitt(raw[4:88] + chunk) != want:
        raise ValueError("MORP-PKT CRC mismatch")
    if seq >= total:
        raise ValueError("frame seq out of range")
    return Frame(ftype, next_hop, path_id, ttl, flags, msg_id, seq, total, chunk)


class Reassembler:
    """TTL reassembly cache with dedup, keyed by 32-B msg_id."""

    def __init__(self, ttl_s: float = 600.0, max_msgs: int = 256):
        self.ttl = ttl_s
        self.max_msgs = max_msgs
        self._msgs: dict[bytes, dict] = {}

    def _gc(self) -> None:
        now = time.time()
        for k in [k for k, v in self._msgs.items() if now - v["t"] > self.ttl]:
            del self._msgs[k]
        while len(self._msgs) > self.max_msgs:
            self._msgs.pop(next(iter(self._msgs)))

    def feed(self, raw: bytes) -> tuple[Frame, bytes | None]:
        """Returns (frame, complete_payload or None)."""
        self._gc()
        f = parse_frame(raw)
        e = self._msgs.setdefault(f.msg_id, {"t": time.time(), "total": f.total, "got": {}})
        if e["total"] != f.total:
            raise ValueError("conflicting total for msg_id")
        e["got"][f.seq] = f.chunk
        e["t"] = time.time()
        if len(e["got"]) == f.total:
            data = b"".join(e["got"][i] for i in range(f.total))
            if hashlib.sha256(data).digest() != f.msg_id:
                raise ValueError("reassembled payload hash != msg_id")
            del self._msgs[f.msg_id]
            return f, data
        return f, None

    def missing(self, msg_id: bytes) -> list[int]:
        e = self._msgs.get(msg_id)
        return [] if not e else [i for i in range(e["total"]) if i not in e["got"]]
