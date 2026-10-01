// SPDX-License-Identifier: MIT
package dev.morpbox.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.morpbox.app.MorpSession
import dev.morpbox.app.crypto.toHex
import dev.morpbox.app.ui.MorpIcons
import dev.youniversal.theme.YouniversalCard
import dev.youniversal.theme.YouniversalEmptyState
import dev.youniversal.theme.YouniversalMetrics
import dev.youniversal.theme.YouniversalSectionHeader
import dev.youniversal.theme.YouniversalSettingRow
import dev.youniversal.theme.YouniversalSwitch

@Composable
fun MeshScreen(session: MorpSession, modifier: Modifier = Modifier) {
    val status by session.status.collectAsStateWithLifecycle()
    val peers = session.portals.snapshotPeers()
    val portals = session.portals.snapshotPortals()

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(YouniversalMetrics.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingMd),
    ) {
        item {
            YouniversalCard {
                Column {
                    YouniversalSectionHeader(
                        title = "Transports",
                        subtitle = "Internet and Bluetooth cover for each other. MeshCore is the LoRa hop.",
                    )
                    YouniversalSettingRow("Internet", subtitle = status.internet)
                    YouniversalSettingRow("Bluetooth mesh", subtitle = "${status.blePeers} peers · bitchat-style flood")
                    YouniversalSettingRow("MeshCore radio", subtitle = status.meshcore, leadingIcon = MorpIcons.Radio)
                    YouniversalSettingRow("Relays up", subtitle = "${status.relaysUp} websocket(s)")
                    YouniversalSettingRow(
                        title = "Act as portal",
                        subtitle = "Publish mesh notes to Nostr, and gossip internet notes onto the mesh",
                        trailing = {
                            YouniversalSwitch(checked = status.portal, onCheckedChange = session::setPortal)
                        },
                    )
                }
            }
        }
        item {
            YouniversalSectionHeader(
                title = "Portals nearby",
                subtitle = "${status.portals} advertising P1/P2/P3",
            )
        }
        if (portals.isEmpty()) {
            item {
                YouniversalEmptyState(
                    title = "No portals yet",
                    description = "A portal is any MorpBox with internet. Toggle mesh on two phones to see beacons.",
                    icon = MorpIcons.Mesh,
                )
            }
        } else {
            items(portals, key = { it.hop.toHex() }) { p ->
                YouniversalCard {
                    YouniversalSettingRow(
                        title = p.caps.joinToString(" · ").ifBlank { "peer" },
                        subtitle = p.devPub.toHex().take(16) + " · load ${p.load}",
                    )
                }
            }
        }
        item { YouniversalSectionHeader(title = "Peers", subtitle = "${peers.size} heard") }
        items(peers, key = { it.hop.toHex() }) { peer ->
            YouniversalCard {
                YouniversalSettingRow(
                    title = peer.name.ifBlank { peer.devPub.toHex().take(12) },
                    subtitle = peer.transports.joinToString() + " · rssi ${peer.rssi}",
                )
            }
        }
    }
}
