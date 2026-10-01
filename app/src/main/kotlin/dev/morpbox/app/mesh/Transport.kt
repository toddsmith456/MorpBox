// SPDX-License-Identifier: MIT
package dev.morpbox.app.mesh

import dev.morpbox.app.protocol.MorpNerd
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LinkState { UP, DEGRADED, DOWN }

data class LinkStatus(
    val transport: String,
    val state: LinkState,
    val peers: Int = 0,
    val detail: String = "",
)

interface MorpTransport {
    val name: String
    val mtu: Int
    val status: StateFlow<LinkStatus>
    suspend fun send(nextHop: ByteArray, packet: ByteArray)
    suspend fun setEnabled(on: Boolean)
}

class TransportManager {
    private val transports = mutableMapOf<String, MorpTransport>()
    private val _links = MutableStateFlow<List<LinkStatus>>(emptyList())
    val links: StateFlow<List<LinkStatus>> = _links

    @Volatile var routePolicy: String = "auto"

    fun register(t: MorpTransport) {
        transports[t.name] = t
    }

    fun get(name: String): MorpTransport? = transports[name]

    fun snapshot(): List<LinkStatus> = transports.values.map { it.status.value }.also { _links.value = it }

    fun internetUp(): Boolean = transports["internet"]?.status?.value?.state == LinkState.UP

    fun meshUp(): Boolean = listOf("ble", "meshcore").any {
        transports[it]?.status?.value?.state != LinkState.DOWN
    }

    fun bestMesh(): MorpTransport? =
        listOf("ble", "meshcore", "rns").mapNotNull { transports[it] }
            .firstOrNull { it.status.value.state != LinkState.DOWN }

    suspend fun route(packet: ByteArray, firstHop: ByteArray): String {
        if (routePolicy != "force-mesh" && internetUp()) {
            transports["internet"]?.send(firstHop, packet)
            return "internet"
        }
        val t = bestMesh() ?: throw IllegalStateException("no mesh transport up")
        t.send(firstHop, packet)
        return t.name
    }

    suspend fun flood(packet: ByteArray) {
        (transports["ble"] as? BleMeshTransport)?.flood(packet)
        (transports["meshcore"] as? MeshCoreTransport)?.sendMorpPayload(packet)
    }

    companion object {
        const val FRAG_MTU_DEFAULT = MorpNerd.MTU_BLE_MESHCORE
    }
}
