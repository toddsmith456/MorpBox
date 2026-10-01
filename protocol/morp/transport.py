"""Transports, portal registry, simulated mesh + portal + fake relays.

MORP-PKT v1 frames (frame.py) on every leg. Portal tiers (research §5/§6.3):
  p1 = Nostr publish/uplink portal · p2 = media/Blossom portal ·
  p3 = payment/wallet-broadcast portal · mailbox · relay (morp-relay island cache)

Production Android has InternetWs / BleMesh / RnsLink / MeshCoreLink transports
behind the same interface; here an in-memory SimulatedMesh proves the routing
and blindness properties deterministically.
"""
from __future__ import annotations

import secrets
import time
from dataclasses import dataclass, field

from . import frame as FR
from . import onion
from .frame import Reassembler
from .identity import DeviceIdentity
from .nostr import Event

# Portal capability tiers -------------------------------------------------
P1_NOSTR = "p1"
P2_MEDIA = "p2"
P3_PAY = "p3"
CAP_MAILBOX = "mailbox"
CAP_RELAY = "relay"

CAPS_MASK = {P1_NOSTR: 0x01, P2_MEDIA: 0x02, P3_PAY: 0x04, CAP_MAILBOX: 0x08, CAP_RELAY: 0x10}


def caps_mask(caps: list[str]) -> int:
    m = 0
    for c in caps:
        m |= CAPS_MASK.get(c, 0)
    return m


# ---------------------------------------------------------------- portal registry
@dataclass
class PortalDesc:
    hop: bytes
    dev_pub: bytes
    relays: list
    caps: list
    load: int = 0
    last_seen: float = field(default_factory=time.time)
    transports: set = field(default_factory=set)


@dataclass
class PeerDesc:
    hop: bytes
    dev_pub: bytes
    last_seen: float = field(default_factory=time.time)
    transports: set = field(default_factory=set)


class PortalRegistry:
    """Client-side view from BEACON/PEER gossip (spec §2)."""

    def __init__(self, beacon_ttl: float = 180.0):
        self.portals: dict[bytes, PortalDesc] = {}
        self.peers: dict[bytes, PeerDesc] = {}
        self.ttl = beacon_ttl

    def add_beacon(self, hop: bytes, dev_pub: bytes, relays: list, caps: list,
                   load: int = 0, transport: str = "ble") -> None:
        p = self.portals.setdefault(hop, PortalDesc(hop, dev_pub, relays, caps))
        p.dev_pub, p.relays, p.caps, p.load = dev_pub, relays, caps, load
        p.last_seen = time.time()
        p.transports.add(transport)
        self.add_peer(hop, dev_pub, transport)

    def add_peer(self, hop: bytes, dev_pub: bytes, transport: str = "ble") -> None:
        p = self.peers.setdefault(hop, PeerDesc(hop, dev_pub))
        p.dev_pub = dev_pub
        p.last_seen = time.time()
        p.transports.add(transport)

    def prune(self) -> None:
        now = time.time()
        self.portals = {h: p for h, p in self.portals.items() if now - p.last_seen < self.ttl}
        self.peers = {h: p for h, p in self.peers.items() if now - p.last_seen < self.ttl * 2}

    def pick_portal(self, need_caps: list | None = None) -> PortalDesc:
        self.prune()
        cands = [p for p in self.portals.values()
                 if not need_caps or all(c in p.caps for c in need_caps)]
        if not cands:
            raise LookupError("no portal with caps=%s" % (need_caps,))
        cands.sort(key=lambda p: (p.load, -p.last_seen))
        return cands[0]

    def pick_path(self, n_relays: int = 2, need_caps: list | None = None,
                  rng: object = None) -> tuple[list[bytes], list[bytes], PortalDesc]:
        """Returns (path_pubs, path_hops, portal) with portal last."""
        portal = self.pick_portal(need_caps)
        cands = [p for h, p in self.peers.items()
                 if h != portal.hop and h in self.peers]
        if len(cands) < n_relays:
            raise LookupError(f"need {n_relays} relays, know {len(cands)}")
        pick = secrets.SystemRandom().sample(cands, n_relays) if rng is None else rng.sample(cands, n_relays)
        pubs = [p.dev_pub for p in pick] + [portal.dev_pub]
        hops = [p.hop for p in pick] + [portal.hop]
        return pubs, hops, portal


# ---------------------------------------------------------------- fake internet relays
class FakeRelaySet:
    """Stands in for wss:// relays: verifies sig, stores event, returns OK."""

    def __init__(self):
        self.stored: dict[str, list[str]] = {}  # event_id -> [relay urls]
        self.events: dict[str, Event] = {}

    def publish(self, event_json: str, relay_urls: list[str]) -> dict[str, bool]:
        evt = Event.from_json(event_json)
        if not evt.verify():
            return {u: False for u in relay_urls}
        self.events[evt.id] = evt
        got = self.stored.setdefault(evt.id, [])
        for u in relay_urls:
            if u not in got:
                got.append(u)
        return {u: True for u in relay_urls}


# ---------------------------------------------------------------- mesh nodes
@dataclass
class Receipt:
    req_id: str
    ok: bool
    details: str = ""


class MeshNode:
    """One phone (or LoRa-attached node) in the simulated mesh."""

    def __init__(self, name: str, ident: DeviceIdentity | None = None,
                 is_portal: bool = False, relay_urls: list | None = None,
                 caps: list | None = None):
        self.name = name
        self.ident = ident or DeviceIdentity.generate()
        self.is_portal = is_portal
        self.relay_urls = relay_urls or []
        self.caps = caps or ([P1_NOSTR, P2_MEDIA] if is_portal else [])
        self.inbox: list[bytes] = []       # reassembled payloads
        self.reasm = Reassembler()
        self.replay_seen: set[str] = set()
        # observable by tests: what THIS node learned while relaying
        self.observed: list[dict] = []
        self.receipts: list[Receipt] = []

    @property
    def hop(self) -> bytes:
        return self.ident.transport.hop_id

    @property
    def dev_pub(self) -> bytes:
        return self.ident.transport.pub

    # -- wire IO ---------------------------------------------------------
    def receive_frame(self, raw: bytes) -> tuple[object, bytes | None]:
        return self.reasm.feed(raw)

    # -- relay / exit ----------------------------------------------------
    def handle_packet(self, packet: bytes, mesh: "SimulatedMesh",
                      internet: FakeRelaySet) -> str:
        """Peel one layer; forward or (portal) exit. Returns action taken."""
        res = onion.peel_packet(packet, self.ident.transport.priv)
        if res.kind == "forward":
            self.observed.append({"node": self.name, "next_hop": res.next_hop.hex(),
                                  "size": len(packet), "remaining": res.remaining})
            mesh.send_to(res.next_hop, res.inner, _from=self.name)
            return f"{self.name}: forward → {res.next_hop.hex()[:8]}"
        # EXIT
        req = onion.InnerRequest.from_bytes(res.inner)
        self.observed.append({"node": self.name, "EXIT": True, "op": req.op,
                              "req_id": req.req_id, "size": len(packet)})
        if not self.is_portal:
            self.receipts.append(Receipt(req.req_id, False, "not a portal"))
            return f"{self.name}: EXIT but not a portal, drop"
        if req.req_id in self.replay_seen:
            return f"{self.name}: replay {req.req_id}, drop"
        self.replay_seen.add(req.req_id)
        if req.op == "nostr-publish":
            ok = internet.publish(req.payload.decode(), req.relays or self.relay_urls)
            good = all(ok.values()) and bool(ok)
            receipt = Receipt(req.req_id, good, f"published→{list(ok)}")
        elif req.op in ("dm-deliver", "group-deliver"):
            # Portal verifies wrap parses + signature, canNOT open seal.
            wrap = Event.from_json(req.payload.decode())
            valid = wrap.verify() and wrap.kind == 1059
            if valid:
                internet.events[wrap.id] = wrap
                receipt = Receipt(req.req_id, True, "wrap stored for recipient relays")
            else:
                receipt = Receipt(req.req_id, False, "bad gift wrap")
        else:
            receipt = Receipt(req.req_id, False, f"op {req.op} unsupported in sim")
        mesh.gossip_receipt(receipt)
        return f"{self.name}: EXIT {req.op} req={req.req_id[:8]} ok={receipt.ok}"


class SimulatedMesh:
    """Deterministic in-memory mesh: unicast by hop-id + receipt gossip."""

    def __init__(self):
        self.nodes: dict[bytes, MeshNode] = {}
        self.trace: list[str] = []
        self.receipts: list[Receipt] = []

    def add(self, node: MeshNode) -> MeshNode:
        self.nodes[node.hop] = node
        return node

    def send_to(self, hop: bytes, packet: bytes, _from: str = "") -> None:
        node = self.nodes.get(hop)
        if node is None:
            self.trace.append(f"DROP: unknown hop {hop.hex()[:8]} (from {_from})")
            return
        # Every leg framed as MORP-PKT v1 DATA (proves framing on every hop).
        frames = FR.frame_payload(packet, mtu=180, next_hop=node.dev_pub,
                                  ftype=FR.F_DATA, ttl=FR.TTL_DEFAULT)
        got = None
        for f in frames:
            _frame, got = node.receive_frame(f)
        assert got == packet, "MORP-PKT round-trip must be lossless"
        node.inbox.append(packet)
        self.trace.append(f"{_from or '?'} → {node.name} ({len(packet)}B, {len(frames)} frames)")

    def gossip_receipt(self, r: Receipt) -> None:
        self.receipts.append(r)
        for n in self.nodes.values():
            n.receipts.append(r)

    def pump(self, internet: FakeRelaySet, max_steps: int = 32) -> list[str]:
        """Deliver all inboxes until quiet. Returns action log."""
        log: list[str] = []
        for _ in range(max_steps):
            busy = False
            for node in self.nodes.values():
                while node.inbox:
                    busy = True
                    pkt = node.inbox.pop(0)
                    log.append(node.handle_packet(pkt, self, internet))
            if not busy:
                break
        return log

    def advertise_all(self, reg: PortalRegistry) -> None:
        for n in self.nodes.values():
            reg.add_peer(n.hop, n.dev_pub)
            if n.is_portal:
                reg.add_beacon(n.hop, n.dev_pub, n.relay_urls, n.caps)


# ---------------------------------------------------------------- client helper
def morp_send(mesh: SimulatedMesh, reg: PortalRegistry, op: str, payload: bytes,
              relays: list[str], first_hop_override: bytes | None = None,
              n_relays: int = 2, need_caps: list | None = None) -> tuple[str, bytes]:
    """Build onion + inject at first hop. Returns (req_id, packet)."""
    pubs, hops, _portal = reg.pick_path(n_relays=n_relays, need_caps=need_caps)
    inner = onion.inner_request_bytes(op, payload, relays)
    packet = onion.build_packet(inner, pubs, hops)
    req = onion.InnerRequest.from_bytes(inner)
    assert FR.msg_id_for(packet)  # cheap sanity: frame ids derive cleanly
    mesh.send_to(first_hop_override or hops[0], packet, _from="origin")
    return req.req_id, packet
