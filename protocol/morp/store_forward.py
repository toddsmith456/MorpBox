"""Store-and-forward cache for offline next-hops. Spec §5.

Nodes cache (msg_id -> frags) and serve them to any peer asking by msg_id.
Cached bytes are onion ciphertext: serving nodes learn nothing new.
"""
from __future__ import annotations

import time
from collections import OrderedDict

PRIORITY = {"receipt": 0, "dm": 1, "kind1": 2, "media": 3}


class StoreForwardCache:
    def __init__(self, ttl_s: float = 24 * 3600, max_msgs: int = 512,
                 max_bytes: int = 8 * 1024 * 1024):
        self.ttl = ttl_s
        self.max_msgs = max_msgs
        self.max_bytes = max_bytes
        self._store: "OrderedDict[bytes, dict]" = OrderedDict()
        self._bytes = 0

    def put(self, msg_id: bytes, frags: list[bytes], kind: str = "kind1") -> None:
        now = time.time()
        size = sum(len(f) for f in frags)
        if msg_id in self._store:
            return  # dedup: already cached
        self._store[msg_id] = {"t": now, "frags": frags, "kind": kind, "size": size}
        self._bytes += size
        self._evict()

    def _evict(self) -> None:
        now = time.time()
        for k in [k for k, v in self._store.items() if now - v["t"] > self.ttl]:
            self._bytes -= self._store.pop(k)["size"]
        # evict lowest priority / oldest first
        while (len(self._store) > self.max_msgs or self._bytes > self.max_bytes) and self._store:
            worst = max(self._store.items(),
                        key=lambda kv: (PRIORITY.get(kv[1]["kind"], 9), kv[1]["t"]))[0]
            self._bytes -= self._store.pop(worst)["size"]

    def get(self, msg_id: bytes) -> list[bytes] | None:
        e = self._store.get(msg_id)
        if not e:
            return None
        if time.time() - e["t"] > self.ttl:
            self._bytes -= self._store.pop(msg_id)["size"]
            return None
        self._store.move_to_end(msg_id)
        return e["frags"]

    def __len__(self) -> int:
        return len(self._store)
