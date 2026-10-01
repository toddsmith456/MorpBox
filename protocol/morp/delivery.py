"""Delivery state machine: queued→on-mesh→published→delivered→confirmed.

Mirrors PublishPipeline.kt. Terminal: CONFIRMED, FAILED. Every transition is
recorded with a timestamp; kind 10083 receipts (from portals/mailboxes) drive
the published→delivered→confirmed legs.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import Enum


class DeliveryState(str, Enum):
    QUEUED = "queued"
    ON_MESH = "on-mesh"
    PUBLISHED = "published"     # portal ACKed receipt (req_id ok)
    DELIVERED = "delivered"     # seen on destination relay / recipient mailbox
    CONFIRMED = "confirmed"     # fetched back / NIP-44 read receipt (terminal ✔)
    FAILED = "failed"           # terminal ✘


_ALLOWED = {
    DeliveryState.QUEUED: {DeliveryState.ON_MESH, DeliveryState.PUBLISHED,
                            DeliveryState.FAILED},
    DeliveryState.ON_MESH: {DeliveryState.PUBLISHED, DeliveryState.QUEUED,  # requeue on loss
                             DeliveryState.FAILED},
    DeliveryState.PUBLISHED: {DeliveryState.DELIVERED, DeliveryState.ON_MESH,
                               DeliveryState.FAILED},
    DeliveryState.DELIVERED: {DeliveryState.CONFIRMED, DeliveryState.FAILED},
    DeliveryState.CONFIRMED: set(),
    DeliveryState.FAILED: {DeliveryState.QUEUED},  # manual retry only
}


@dataclass
class DeliveryRecord:
    key: str  # event id or req_id
    kind: str  # kind1 | dm | group | media | pay
    state: DeliveryState = DeliveryState.QUEUED
    history: list = field(default_factory=list)
    retries: int = 0

    def apply(self, nxt: DeliveryState, note: str = "") -> "DeliveryRecord":
        if nxt not in _ALLOWED[self.state]:
            raise ValueError(f"illegal delivery transition {self.state} → {nxt}")
        if nxt == DeliveryState.QUEUED:
            self.retries += 1
        self.history.append((self.state.value, nxt.value, int(time.time()), note))
        self.state = nxt
        return self

    @property
    def terminal(self) -> bool:
        return self.state in (DeliveryState.CONFIRMED, DeliveryState.FAILED)
