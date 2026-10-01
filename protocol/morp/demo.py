"""End-to-end demo: offline kind-1 + DM through 3-hop MORP NERD to a portal.

Run:  python -m morp.demo
"""
from .identity import DeviceIdentity
from .nostr import (gift_wrap_for_dm, make_kind1, open_gift_wrap)
from .transport import (FakeRelaySet, MeshNode, PortalRegistry, SimulatedMesh, morp_send)

RELAYS = ["wss://relay.damus.io", "wss://nos.lol"]


def main() -> None:
    internet = FakeRelaySet()
    mesh = SimulatedMesh()

    alice = DeviceIdentity.generate()
    bob = DeviceIdentity.generate()
    r1 = mesh.add(MeshNode("relay-A"))
    r2 = mesh.add(MeshNode("relay-B"))
    portal = mesh.add(MeshNode("portal-P", is_portal=True, relay_urls=RELAYS))
    print(f"Alice {alice.nostr.npub[:16]}… (OFFLINE)   Bob {bob.nostr.npub[:16]}…")
    print(f"path: Alice → {r1.name} → {r2.name} → {portal.name} → internet {RELAYS}")

    reg = PortalRegistry()
    mesh.advertise_all(reg)

    # 1) kind-1 post, signed offline (Amber-style: sign once, send via mesh)
    note = make_kind1(alice.nostr, "Water station open at 5th & Main. #hurricane")
    req1, _pkt = morp_send(mesh, reg, "nostr-publish", note.to_json().encode(), RELAYS)
    for line in mesh.pump(internet):
        print("  " + line)
    assert internet.events.get(note.id) is not None, "portal failed to publish!"
    print(f"✓ kind-1 {note.id[:12]}… published via portal; receipt ok="
          f"{mesh.receipts[-1].ok} (req {req1[:8]})")

    # 2) blindness check: relays saw next-hop + size only
    for r in (r1, r2):
        print(f"  {r.name} observed: {r.observed}")
        assert all("EXIT" not in o for o in r.observed)
        assert alice.nostr.npub not in str(r.observed) and note.id not in str(r.observed)
    print("✓ relays learned next-hop only — no npub, no payload")

    # 3) quantum-safer DM: gift-wrapped seal → same onion path → Bob unwraps
    wrap = gift_wrap_for_dm(alice.nostr, alice.transport, bob.nostr.pubkey_hex,
                            bob.transport.pub, "North gate is blocked, use river rd.")
    req2, _ = morp_send(mesh, reg, "dm-deliver", wrap.to_json().encode(), RELAYS,
                        need_caps=["p1"])
    for line in mesh.pump(internet):
        print("  " + line)
    got = open_gift_wrap(internet.events[wrap.id], bob.nostr, bob.transport)
    assert got.plaintext.startswith("North gate")
    # portal saw the wrap but could NOT open the seal:
    assert got.plaintext not in str(portal.observed)
    print(f"✓ DM wrap {wrap.id[:12]}… routed; Bob reads: “{got.plaintext}”")
    print(f"✓ portal receipts: {[(r.req_id[:8], r.ok) for r in mesh.receipts]}")
    print("\nMORP NERD demo OK — offline author reached the Nostr network.")


if __name__ == "__main__":
    main()
