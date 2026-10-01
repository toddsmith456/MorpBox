// SPDX-License-Identifier: MIT
package dev.morpbox.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.morpbox.app.MorpSession
import dev.morpbox.app.crypto.Bech32
import dev.morpbox.app.ui.MorpIcons
import dev.youniversal.theme.YouniversalButton
import dev.youniversal.theme.YouniversalCard
import dev.youniversal.theme.YouniversalEmptyState
import dev.youniversal.theme.YouniversalMetrics
import dev.youniversal.theme.YouniversalOutlinedTextField
import dev.youniversal.theme.YouniversalSectionHeader
import dev.youniversal.theme.YouniversalSettingRow
import kotlinx.coroutines.launch

@Composable
fun ChatsScreen(
    session: MorpSession,
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit,
) {
    val rooms by session.rooms.collectAsStateWithLifecycle()
    var peer by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize()) {
        YouniversalCard(Modifier.padding(YouniversalMetrics.ScreenPadding)) {
            Column(
                Modifier.padding(YouniversalMetrics.SpacingLg),
                verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingMd),
            ) {
                YouniversalSectionHeader(
                    title = "Private messages",
                    subtitle = "NIP-17 gift-wrap. Delivered over internet, Bluetooth mesh, or MeshCore — whichever is up.",
                )
                YouniversalOutlinedTextField(
                    value = peer,
                    onValueChange = { peer = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "npub or hex",
                    placeholder = "npub1…",
                    singleLine = true,
                )
                YouniversalOutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "First message",
                    singleLine = true,
                )
                YouniversalButton(
                    text = "Start chat",
                    onClick = {
                        val hex = runCatching {
                            if (peer.trim().startsWith("npub1")) Bech32.npubToHex(peer.trim()) else peer.trim().lowercase()
                        }.getOrNull() ?: return@YouniversalButton
                        scope.launch {
                            session.sendDm(hex, first.ifBlank { "hi" })
                            onOpen("dm:$hex")
                        }
                    },
                    enabled = peer.isNotBlank(),
                    leadingIcon = MorpIcons.Chat,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (rooms.isEmpty()) {
            YouniversalEmptyState(
                title = "No conversations",
                description = "DMs are sealed to the recipient. Relays and mesh hops see only a gift wrap.",
                icon = MorpIcons.Chat,
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(YouniversalMetrics.ScreenPadding)) {
                items(rooms, key = { it.id }) { room ->
                    YouniversalCard(onClick = { onOpen(room.id) }, modifier = Modifier.padding(bottom = YouniversalMetrics.SpacingSm).fillMaxWidth()) {
                        YouniversalSettingRow(
                            title = room.title.ifBlank { room.id },
                            subtitle = "${room.kind} · ${room.lastPreview}",
                        )
                    }
                }
            }
        }
    }
}
