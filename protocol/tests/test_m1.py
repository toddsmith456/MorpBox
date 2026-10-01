"""M1 module tests: morp-rns spike, Blossom, delivery machine, payments, chat."""
import time

import pytest

from morp import blossom as B
from morp import delivery as D
from morp import payments as P
from morp import rns as R
from morp.delivery import DeliveryRecord, DeliveryState
from morp.identity import DeviceIdentity, NostrIdentity
from morp.nostr import (GROUP_MEMBER_CAP, gift_wrap_for_dm, make_chat_rumor,
                        make_file_rumor, open_gift_wrap, seal_group_message)
from morp.payments import PaymentInstruction


def test_rns_identity_announce_roundtrip():
    a = R.RnsIdentity.generate()
    assert len(a.identity_hash) == 32 and len(a.address) == 16
    ann = R.build_announce(a, R.announce_app_data_for_morp("ab12", 0x03, 7))
    got = R.parse_announce(ann)
    assert got["address"] == a.address and b"morp" in got["app_data"]
    evil = bytearray(ann)
    evil[20] ^= 1
    with pytest.raises(Exception):
        R.parse_announce(bytes(evil))


def test_rns_seal_and_ack():
    a, b = R.RnsIdentity.generate(), R.RnsIdentity.generate()
    ct = R.seal_packet(b.x_pub, b"hello rns")
    assert R.open_packet(b.x_priv, ct) == b"hello rns"
    with pytest.raises(Exception):
        R.open_packet(a.x_priv, ct)
    import hashlib
    ack = R.delivery_ack(hashlib.sha256(ct).digest(), b.x_priv, a.x_pub)
    assert R.open_packet(a.x_priv, ack).startswith(b"ACK:")


def test_rns_token_ratchet_forward_secret():
    r1 = R.TokenRatchet(b"s" * 32, b"t" * 32)
    r2 = R.TokenRatchet(b"s" * 32, b"t" * 32)
    w1, k1 = r1.next()
    w2, k2 = r2.next()
    assert (w1, k1) == (w2, k2)  # deterministic peers stay in sync
    w3, k3 = r1.next()
    assert k3 != k1 and w3 != w1  # ratchet advances; old keys destroyed


def test_blossom_auth_and_lists():
    alice = NostrIdentity.generate()
    data = b"fake-webp-bytes"
    sha = B.sha256_hex(data)
    assert B.blob_url("https://cdn.example/", sha).endswith("/" + sha)
    sl = B.build_server_list(alice, ["https://a.example", "https://b.example"])
    assert sl.kind == 10063 and sl.verify()
    assert B.parse_server_list(sl) == ["https://a.example", "https://b.example"]
    auth = B.build_upload_auth(alice, sha)
    assert auth.kind == 24242 and B.verify_upload_auth(auth, sha)
    assert B.auth_header(auth).startswith("Nostr ")
    assert not B.verify_upload_auth(auth, "00" * 32)
    md = B.build_media_descriptor(alice, sha, "image/webp", len(data),
                                  B.blob_url("https://a.example", sha), dims=[640, 480])
    assert md.kind == 10082 and md.verify()


def test_delivery_machine_legal_and_illegal():
    r = DeliveryRecord("req1", "kind1")
    r.apply(DeliveryState.ON_MESH).apply(DeliveryState.PUBLISHED, "portal ok")
    r.apply(DeliveryState.DELIVERED).apply(DeliveryState.CONFIRMED)
    assert r.terminal and len(r.history) == 4
    r2 = DeliveryRecord("req2", "dm")
    with pytest.raises(ValueError):
        r2.apply(DeliveryState.CONFIRMED)  # skip-ahead illegal
    r2.apply(DeliveryState.FAILED)
    r2.apply(DeliveryState.QUEUED)  # manual retry ok
    assert r2.retries == 1


def test_payment_instruction_sign_verify_expire():
    alice = NostrIdentity.generate()
    ix = PaymentInstruction(asset="xmr", payto="4Abc…", amount_atomic=1_000_000_000_000,
                            memo="field report").sign(alice)
    assert ix.verify() and not ix.expired
    ix2 = PaymentInstruction(asset="ln", payto="lnbc…", amount_msat=5000,
                             expires=int(time.time()) - 1).sign(alice)
    assert ix2.expired and not ix2.verify()
    pl = P.build_payto_list(alice, xmr_subaddress="84xyz", lud16="a@b.co")
    assert pl.kind == 10133 and pl.verify()
    assert P.parse_payto_targets(pl) == {"xmr": "84xyz", "ln": "a@b.co"}


def test_nip17_rumors_and_group_fanout():
    alice, bob = DeviceIdentity.generate(), DeviceIdentity.generate()
    rumor = make_chat_rumor(alice.nostr.pubkey_hex, "hi", bob.nostr.pubkey_hex)
    assert rumor["kind"] == 14
    fr = make_file_rumor(alice.nostr.pubkey_hex, "https://cdn/x", "image/webp",
                         "ab" * 32, bob.nostr.pubkey_hex)
    assert fr["kind"] == 15
    members = [(bob.nostr.pubkey_hex, bob.transport.pub)]
    wraps = seal_group_message(alice.nostr, alice.transport, members, "squad msg", "room1")
    assert len(wraps) == 1 and wraps[0].kind == 1059
    seal = open_gift_wrap(wraps[0], bob.nostr, bob.transport)
    assert seal.room == "room1" and "squad msg" in seal.plaintext
    with pytest.raises(ValueError):
        seal_group_message(alice.nostr, alice.transport, members * 13, "x", "r")
    assert GROUP_MEMBER_CAP == 12
