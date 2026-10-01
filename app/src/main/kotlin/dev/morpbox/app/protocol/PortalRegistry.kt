// SPDX-License-Identifier: MIT
package dev.morpbox.app.protocol

import dev.morpbox.app.crypto.toHex

data class PortalDesc(
    val hop: ByteArray,
    val devPub: ByteArray,
    val relays: List<String>,
    val caps: List<String>,
    val load: Int = 0,
    var lastSeen: Long = System.currentTimeMillis(),
    val transports: MutableSet<String> = mutableSetOf(),
)

data class PeerDesc(
    val hop: ByteArray,
    val devPub: ByteArray,
    var lastSeen: Long = System.currentTimeMillis(),
    val transports: MutableSet<String> = mutableSetOf(),
    var rssi: Int = 0,
    var name: String = "",
)

class PortalRegistry(private val beaconTtlMs: Long = 180_000) {
    private val portals = mutableMapOf<String, PortalDesc>()
    private val peers = mutableMapOf<String, PeerDesc>()

    @Synchronized
    fun addBeacon(
        hop: ByteArray,
        devPub: ByteArray,
        relays: List<String>,
        caps: List<String>,
        load: Int = 0,
        transport: String = "ble",
    ) {
        portals[hop.toHex()] = PortalDesc(hop, devPub, relays, caps, load).also {
            it.transports += transport
        }
        addPeer(hop, devPub, transport)
    }

    @Synchronized
    fun addPeer(hop: ByteArray, devPub: ByteArray, transport: String = "ble", rssi: Int = 0, name: String = "") {
        val p = peers.getOrPut(hop.toHex()) { PeerDesc(hop, devPub) }
        p.lastSeen = System.currentTimeMillis()
        p.transports += transport
        if (rssi != 0) p.rssi = rssi
        if (name.isNotBlank()) p.name = name
    }

    @Synchronized
    fun prune() {
        val now = System.currentTimeMillis()
        portals.keys.toList().filter { now - portals[it]!!.lastSeen > beaconTtlMs }.forEach { portals.remove(it) }
        peers.keys.toList().filter { now - peers[it]!!.lastSeen > beaconTtlMs * 2 }.forEach { peers.remove(it) }
    }

    @Synchronized
    fun snapshotPeers(): List<PeerDesc> {
        prune()
        return peers.values.sortedByDescending { it.lastSeen }
    }

    @Synchronized
    fun snapshotPortals(): List<PortalDesc> {
        prune()
        return portals.values.sortedByDescending { it.lastSeen }
    }

    @Synchronized
    fun pickPortal(needCaps: List<String> = emptyList()): PortalDesc? {
        prune()
        return portals.values.filter { p -> needCaps.all { it in p.caps } }
            .minByOrNull { it.load * 1_000_000L - it.lastSeen }
    }

    @Synchronized
    fun pickPath(
        nRelays: Int = 2,
        needCaps: List<String> = emptyList(),
    ): Triple<List<ByteArray>, List<ByteArray>, PortalDesc>? {
        val portal = pickPortal(needCaps) ?: return null
        val cands = peers.values.filter { !it.hop.contentEquals(portal.hop) }.shuffled()
        if (cands.size < nRelays) return null
        val pick = cands.take(nRelays)
        return Triple(pick.map { it.devPub } + portal.devPub, pick.map { it.hop } + portal.hop, portal)
    }

    @Synchronized fun portalCount(): Int { prune(); return portals.size }
    @Synchronized fun peerCount(): Int { prune(); return peers.size }
}
