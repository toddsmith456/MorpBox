"""MORP NERD spec conformance tests (run: pytest tests/ -q)."""
import base64
import json
import secrets

import pytest

from morp import frame as F
from morp import media as M
from morp import nostr as N
from morp import onion as O
from morp.frame import Reassembler
from morp.identity import (BindingAttestation, DeviceIdentity, NostrIdentity,
                           TransportIdentity, parse_bunker_url)
from morp.nostr import (gift_wrap_for_dm, make_kind1, make_quote, make_reply,
                        make_repost, open_gift_wrap)
from morp.store_forward import StoreForwardCache
from morp.transport import (FakeRelaySet, MeshNode, PortalRegistry, SimulatedMesh,
                            morp_send)

RELAYS = ["wss://relay.damus.io", "wss://nos.lol"]


def test_binding_attestation_roundtrip():
    dev = DeviceIdentity.generate()
    a = dev.attest(caps={"portal": True})
    assert a.verify()
    bad = BindingAttestation(a.npub, TransportIdentity.generate().pub.hex(), a.ts,
                             a.caps, a.sig_hex)
    assert not bad.verify()  # sig bound to the real dev_pub


def test_nostr_kinds_sign_verify():
    alice = NostrIdentity.generate()
    bob = NostrIdentity.generate()
    note = make_kind1(alice, "hello mesh")
    assert note.kind == 1 and note.verify()
    reply = make_reply(bob, "copy that", note)
    assert reply.verify() and any(t[0] == "e" and t[1] == note.id for t in reply.tags)
    repost = make_repost(bob, note)
    assert repost.kind == 6 and repost.verify()
    quote = make_quote(bob, "signal boost", note)
    assert quote.kind == 1 and quote.verify() and any(t[0] == "q" for t in quote.tags)
    # tamper detection
    evil = N.Event.from_json(note.to_json())
    evil.content = "forged"
    assert not evil.verify()


def test_gift_wrap_roundtrip():
    alice, bob = DeviceIdentity.generate(), DeviceIdentity.generate()
    wrap = gift_wrap_for_dm(alice.nostr, alice.transport, bob.nostr.pubkey_hex,
                            bob.transport.pub, "meet at dawn", room="")
    assert wrap.kind == 1059 and wrap.verify()
    seal = open_gift_wrap(wrap, bob.nostr, bob.transport)
    assert seal.plaintext == "meet at dawn" and seal.sender_nostr == alice.nostr.pubkey_hex
    # someone else cannot open it
    mallory = DeviceIdentity.generate()
    with pytest.raises(Exception):
        open_gift_wrap(wrap, mallory.nostr, mallory.transport)


def _path(n=3):
    devs = [TransportIdentity.generate() for _ in range(n)]
    return [d.pub for d in devs], [d.hop_id for d in devs], devs


def test_onion_peel_order_and_blindness():
    pubs, hops, devs = _path(3)
    inner = O.inner_request_bytes("nostr-publish", b'{"hello":"world"}', RELAYS)
    pkt = O.build_packet(inner, pubs, hops)
    assert pkt[0] == O.VERSION and pkt[1] == 3
    # wrong key cannot peel
    with pytest.raises(Exception):
        O.peel_packet(pkt, TransportIdentity.generate().priv)
    # peel in order; each forward reveals only next_hop
    r = O.peel_packet(pkt, devs[0].priv)
    assert r.kind == "forward" and r.next_hop == hops[1]
    assert b"hello" not in r.inner  # still onion-encrypted
    r2 = O.peel_packet(r.inner, devs[1].priv)
    assert r2.kind == "forward" and r2.next_hop == hops[2]
    r3 = O.peel_packet(r2.inner, devs[2].priv)
    assert r3.kind == "exit"
    req = O.InnerRequest.from_bytes(r3.inner)
    assert req.op == "nostr-publish" and req.payload == b'{"hello":"world"}'


def test_end_to_end_kind1_via_portal():
    internet, mesh = FakeRelaySet(), SimulatedMesh()
    alice = DeviceIdentity.generate()
    r1, r2 = mesh.add(MeshNode("r1")), mesh.add(MeshNode("r2"))
    portal = mesh.add(MeshNode("portal", is_portal=True, relay_urls=RELAYS))
    reg = PortalRegistry()
    mesh.advertise_all(reg)

    note = make_kind1(alice.nostr, "Bridge is OUT at mile 12. #flood")
    req_id, pkt = morp_send(mesh, reg, "nostr-publish", note.to_json().encode(), RELAYS)
    log = mesh.pump(internet)
    assert len(log) == 3  # r1 forward, r2 forward, portal exit
    stored = internet.events.get(note.id)
    assert stored is not None and stored.verify()
    assert internet.stored[note.id] == RELAYS  # portal used CLIENT-chosen relays
    # blindness: relay observations contain no npub / event id / content
    blob = json.dumps([r1.observed, r2.observed])
    assert alice.nostr.npub not in blob and note.id not in blob and "Bridge" not in blob
    # receipt gossiped back under req_id with no identity attached
    assert any(r.req_id == req_id and r.ok for r in mesh.receipts)


def test_end_to_end_dm_portal_cannot_read_seal():
    internet, mesh = FakeRelaySet(), SimulatedMesh()
    alice, bob = DeviceIdentity.generate(), DeviceIdentity.generate()
    mesh.add(MeshNode("r1"))
    mesh.add(MeshNode("r2"))
    portal = mesh.add(MeshNode("portal", is_portal=True, relay_urls=RELAYS,
                               caps=["p1", "p2"]))
    reg = PortalRegistry()
    mesh.advertise_all(reg)
    wrap = gift_wrap_for_dm(alice.nostr, alice.transport, bob.nostr.pubkey_hex,
                            bob.transport.pub, "extraction at 0300")
    req_id, _ = morp_send(mesh, reg, "dm-deliver", wrap.to_json().encode(), RELAYS,
                          need_caps=["p1"])
    mesh.pump(internet)
    assert internet.events[wrap.id].kind == 1059
    assert "extraction" not in json.dumps(portal.observed)
    seal = open_gift_wrap(internet.events[wrap.id], bob.nostr, bob.transport)
    assert seal.plaintext == "extraction at 0300"


def test_morp_pkt_v1_roundtrip_and_reorder():
    pkt = secrets.token_bytes(3000)
    nxt = secrets.token_bytes(32)
    frames = F.frame_payload(pkt, mtu=180, next_hop=nxt, ftype=F.F_DATA, ttl=8)
    assert all(len(f) <= 180 for f in frames)
    # header field checks (research §6.1)
    f0 = F.parse_frame(frames[0])
    assert frames[0][:4] == b"MORP" and f0.ftype == F.F_DATA
    assert f0.next_hop == nxt and f0.ttl == 8
    assert f0.msg_id == F.msg_id_for(pkt) and f0.path_id == F.path_id_for(pkt)
    assert f0.flags & F.FLAG_FIRST
    assert F.parse_frame(frames[-1]).flags & F.FLAG_LAST
    # out-of-order reassembly
    re = Reassembler()
    out = None
    for f in reversed(frames):
        _frm, got = re.feed(f)
        out = got or out
    assert out == pkt
    # duplicates tolerated
    re2 = Reassembler()
    for f in frames:
        re2.feed(f)
        re2.feed(f)
    # corruption detected
    evil = bytearray(frames[0])
    evil[-1] ^= 0xFF
    with pytest.raises(Exception):
        Reassembler().feed(bytes(evil))
    # flood frames carry zero next_hop
    adv = F.frame_payload(b'{"adv":1}', mtu=180, ftype=F.F_ADV, ttl=3)
    assert F.parse_frame(adv[0]).is_flood


def test_store_forward_cache():
    c = StoreForwardCache(ttl_s=60, max_msgs=2)
    c.put(b"a" * 16, [b"x"], kind="kind1")
    c.put(b"b" * 16, [b"y"], kind="dm")
    c.put(b"c" * 16, [b"z"], kind="receipt")  # evicts lowest priority (kind1)
    assert c.get(b"a" * 16) is None
    assert c.get(b"c" * 16) == [b"z"]


def test_media_policy_and_manifest():
    with pytest.raises(ValueError):
        M.plan_media("photo", 500_000, "mesh")  # over cap → must compress
    plan = M.plan_media("photo", 90_000, "mesh")
    assert not plan.inline  # 90KB → portal upload
    small = M.plan_media("audio", 4_000, "mesh")
    assert small.inline
    data = secrets.token_bytes(10_000)
    man, chunks = M.build_manifest(data, "image/webp", chunk_bytes=154)
    assert M.verify_chunks(man, chunks) == data
    chunks2 = list(chunks)
    chunks2[3] = b"X" * len(chunks2[3])
    with pytest.raises(ValueError):
        M.verify_chunks(man, chunks2)


def test_bunker_url_parse():
    pub, relay, secret = parse_bunker_url(
        "bunker://abc123?relay=wss%3A%2F%2Fr.example&secret=s3cret")
    assert pub == "abc123" and relay.startswith("wss") and secret == "s3cret"
    with pytest.raises(ValueError):
        parse_bunker_url("https://example.com/x")


def test_test_vector_export_smoke():
    """Ensure vectors exist for the Kotlin side to verify byte-compat (M1)."""
    vec = {"onion_version": O.VERSION, "frame_magic": F.MAGIC.hex(),
           "frame_header_len": F.HEADER_LEN,
           "pad_tiers": list(O.PAD_TIERS), "next_none": O.NEXT_NONE.hex()}
    assert vec["frame_magic"] == "4d4f5250"  # "MORP"
