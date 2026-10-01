// SPDX-License-Identifier: MIT
package dev.morpbox.app.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Scores Wi-Fi / cellular. Does not itself send MORP packets — RelayPool does. */
class InternetTransport(ctx: Context) : MorpTransport {
    override val name = "internet"
    override val mtu = 65_536
    private val _status = MutableStateFlow(LinkStatus("internet", LinkState.DOWN))
    override val status: StateFlow<LinkStatus> = _status

    private val cm = ctx.getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
    }

    init {
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(req, callback) }
        refresh()
    }

    fun refresh() {
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val up = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        _status.value = LinkStatus(
            "internet",
            if (up) LinkState.UP else LinkState.DOWN,
            peers = if (up) 1 else 0,
            detail = when {
                !up -> "offline"
                wifi -> "wifi"
                else -> "cellular"
            },
        )
    }

    override suspend fun send(nextHop: ByteArray, packet: ByteArray) {
        // Internet path for signed Nostr events is RelayPool, not this transport.
    }

    override suspend fun setEnabled(on: Boolean) {
        if (!on) _status.value = LinkStatus("internet", LinkState.DOWN, detail = "disabled")
        else refresh()
    }
}
