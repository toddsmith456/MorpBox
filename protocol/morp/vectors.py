"""Deterministic cross-implementation test vectors (M1 Kotlin checks these).

Run:  python -m morp.vectors  → writes protocol/vectors/v0.1.json
"""
import hashlib
import json
from pathlib import Path

from . import frame as F
from . import onion as O
from .frame import Reassembler, frame_payload
from .identity import NostrIdentity, TransportIdentity
from .nostr import Event


def main() -> None:
    # Fixed keys (sha256 of labels — NOT secret, test-only).
    def tpriv(label: str) -> TransportIdentity:
        return TransportIdentity.from_priv(hashlib.sha256(label.encode()).digest())

    relays_privs = [tpriv(f"morp-vector-relay-{i}") for i in range(3)]
    pubs = [t.pub for t in relays_privs]
    hops = [t.hop_id for t in relays_privs]

    alice = NostrIdentity(hashlib.sha256(b"morp-vector-alice").digest())
    note = Event(pubkey=alice.pubkey_hex, created_at=1750000000, kind=1,
                 tags=[], content="vector note")
    note.sign(alice)

    inner = O.InnerRequest(op="nostr-publish",
                           payload_b64=__import__("base64").b64encode(
                               note.to_json().encode()).decode(),
                           relays=["wss://relay.example"], req_id="0011223344556677",
                           ts=1750000001)
    import struct as _s
    inner_padded = O.pad_to_tier(inner.to_bytes())
    packet = O.build_packet(inner_padded, pubs, hops)

    # Expected peel chain (deterministic given the fixed packet+keys).
    peels = []
    cur = packet
    for i, t in enumerate(relays_privs):
        r = O.peel_packet(cur, t.priv)
        peels.append({"kind": r.kind,
                      "next_hop": r.next_hop.hex(),
                      "inner_sha256": hashlib.sha256(r.inner).hexdigest(),
                      "inner_len": len(r.inner)})
        if r.kind == "forward":
            cur = r.inner

    nxt = relays_privs[0].pub  # first-hop dev_pub as v1 next_hop(32)
    frags = frame_payload(packet, mtu=180, next_hop=nxt, ftype=F.F_DATA, ttl=8)
    re = Reassembler()
    out = None
    for f in frags:
        _frm, got = re.feed(f)
        out = got or out
    assert out == packet

    vectors = {
        "spec": "morp-nerd/0.2-morp-pkt-v1",
        "hop_ids": [h.hex() for h in hops],
        "dev_pubs": [p.hex() for p in pubs],
        "dev_privs": [t.priv.hex() for t in relays_privs],
        "crc16_123456789": F.crc16_ccitt(b"123456789"),
        "frame_magic": F.MAGIC.hex(),
        "frame_header_len": F.HEADER_LEN,
        "msg_id": F.msg_id_for(packet).hex(),
        "packet_hex": packet.hex(),
        "packet_len": len(packet),
        "frame_count_mtu180": len(frags),
        "frame0_hex": frags[0].hex(),
        "peels": peels,
        "inner_req_id": inner.req_id,
        "inner_op": inner.op,
        "nostr_event_id": note.id,
        "nostr_event_sig": note.sig,
        "nostr_canonical": note.canonical().decode(),
        "npub": alice.npub,
    }
    dest = Path(__file__).resolve().parent.parent / "vectors" / "v0.1.json"
    dest.parent.mkdir(exist_ok=True)
    dest.write_text(json.dumps(vectors, indent=2))
    print(f"wrote {dest} ({len(packet)}B packet, {len(frags)} v1 frames)")


if __name__ == "__main__":
    main()
